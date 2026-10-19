package com.jobwatch.app

import android.content.Context
import android.util.Xml
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.StringReader
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

/**
 * Job sites that search by position and place, like LinkedIn, through their official feeds and
 * APIs: ReliefWeb (run by UN OCHA; UN and NGO jobs; no key) and Jooble (a job search engine; with
 * your own free API key). Their jobs are filed under the search they answer, and each names the
 * site it came from. Each site is read at most hourly (ReliefWeb asks feed readers to wait an hour;
 * Jooble, one search per hour each), and a check sends each site at most one request.
 */
object JobSites {
    const val LABEL = "Job sites"
    const val RELIEFWEB = "ReliefWeb"
    const val JOOBLE = "Jooble"
    const val JOOBLE_KEY_PAGE = "https://jooble.org/api/about"
    const val GAP_MINUTES = 60L
    private const val FIRST_CHECK_DAYS = 7L // the first read shows the past week's jobs
    private const val MAX_AGE_DAYS = 14L
    private const val DAY_MS = 86_400_000L
    private val QUERY_WORDS = setOf("and", "or", "not")
    private val lock = ReentrantLock()

    /** What one read of the sites did: the new jobs, and a line for the history (null when no site was due). */
    class Read(val jobs: List<Job>, val note: String?) {
        companion object {
            val NONE = Read(emptyList(), null)
        }
    }

    /** A site's listing, matched to the search it answers. */
    class Found(val search: Search, val listing: RemoteBoards.Listing)

    /** One request that's due: the key its time is saved under, the site, the search (Jooble's), and the read. */
    private class Due(val key: String, val site: String, val search: Search?, val read: () -> List<Found>)

    private fun joobleKey(search: Search) = "$JOOBLE|${search.label}"

    private fun isDue(data: AppData, key: String, now: Long) =
        now - minOf(data.siteChecks[key] ?: 0L, now) >= GAP_MINUTES * 60_000

    /** The sites' requests that are due now: ReliefWeb's, and Jooble's for the search it read longest ago. */
    private fun due(data: AppData, now: Long, force: Boolean): List<Due> {
        if (!data.sitesOn || (!data.checksOn && !force) || data.searches.isEmpty()) return emptyList()
        val due = mutableListOf<Due>()
        if (reliefWebUrl(data.searches) != null && isDue(data, RELIEFWEB, now)) {
            due += Due(RELIEFWEB, RELIEFWEB, null) { reliefWeb(data.searches) }
        }
        if (data.joobleKey.isNotBlank()) {
            data.searches.filter { isDue(data, joobleKey(it), now) }.minByOrNull { data.siteChecks[joobleKey(it)] ?: 0L }
                ?.let { search -> due += Due(joobleKey(search), JOOBLE, search) { jooble(data.joobleKey, search) } }
        }
        return due
    }

    /** When the next site read is due, or null while the sites aren't being read. */
    fun nextDueAt(data: AppData): Long? {
        if (!data.sitesOn || !data.checksOn || data.searches.isEmpty()) return null
        val keys = buildList {
            if (reliefWebUrl(data.searches) != null) add(RELIEFWEB)
            if (data.joobleKey.isNotBlank()) addAll(data.searches.map { joobleKey(it) })
        }
        return keys.minOfOrNull { (data.siteChecks[it] ?: 0L) + GAP_MINUTES * 60_000 }
    }

    /**
     * Read the sites that are due and save the new jobs; announcing them is up to the caller
     * ([Checker]). Reads nothing while the sites are off, while background checks are off (unless
     * [force]: you asked), or while another read of the sites is running.
     */
    fun check(context: Context, force: Boolean = false): Read {
        if (!lock.tryLock()) return Read.NONE
        try {
            return checkDue(context, force)
        } finally {
            lock.unlock()
        }
    }

    private fun checkDue(context: Context, force: Boolean): Read {
        val now = Clock.now()
        val data = Store.load(context)
        val due = due(data, now, force)
        if (due.isEmpty()) return Read.NONE
        // Note the reads before making them, so each site's hour holds even if this one is cut short.
        Store.update(context) { it.copy(siteChecks = it.siteChecks + due.associate { d -> d.key to now }) }
        val results = arrayOfNulls<Result<List<Found>>>(due.size)
        due.mapIndexed { i, d -> thread { results[i] = runCatching { d.read() } } }.forEach { it.join() }

        val seen = data.seen.toHashSet()
        val listed = data.jobs.mapTo(HashSet()) { RemoteBoards.duplicateKey(it.title, it.company) } // e.g. on LinkedIn already
        val fresh = mutableListOf<Job>()
        val newSeen = mutableListOf<String>()
        val status = mutableMapOf<String, String>()
        val failed = mutableListOf<String>()
        due.forEachIndexed { i, d ->
            val where = d.search?.let { ", ${it.label}" }.orEmpty()
            val found = results[i]?.getOrElse { e ->
                failed += d.site
                status[d.site] = "Couldn't read it ${timeText(now)}$where: ${e.message ?: e.javaClass.simpleName}"
                null
            } ?: return@forEachIndexed
            val oldest = now - (if (data.siteChecks[d.key] == null) FIRST_CHECK_DAYS else MAX_AGE_DAYS) * DAY_MS
            var added = 0
            for (f in found.sortedByDescending { it.listing.postedAt }) {
                val listing = f.listing
                val title = cleanText(listing.title)
                val company = cleanText(listing.company)
                val duplicate = RemoteBoards.duplicateKey(title, company) // the same job is often on several sites
                when {
                    listing.id in seen || duplicate in seen || duplicate in listed -> continue
                    !listing.url.startsWith("http") -> continue
                    listing.postedAt in 1 until oldest -> continue
                    !RemoteBoards.titleMatches(title, f.search.keywords) -> continue
                    !Places.inPlace(listing.where, f.search.location) -> continue
                }
                seen += listing.id
                seen += duplicate
                newSeen += listOf(listing.id, duplicate)
                added++
                val posted = if (listing.postedAt > 0) Instant.ofEpochMilli(listing.postedAt).atOffset(ZoneOffset.UTC).toLocalDate().toString() else ""
                fresh += Job(listing.id, title, company, listing.where.ifBlank { f.search.place }, posted, listing.url,
                    search = f.search.label, foundAt = now, isNew = true, source = listing.source)
            }
            status[d.site] = "Checked ${timeText(now)}$where: $added new of ${found.size} found"
        }
        Store.update(context) { current ->
            current.copy(seen = current.seen + newSeen, jobs = fresh + current.jobs, siteStatus = current.siteStatus + status)
        }
        return Read(fresh, "Job sites: ${fresh.size} new" + if (failed.isNotEmpty()) " (couldn't read ${failed.joinToString(", ")})" else "")
    }

    // ---------- ReliefWeb ----------

    /** A position's words for ReliefWeb's search: "data analyst" gives data AND analyst. */
    private fun queryWords(position: String) =
        position.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 && it !in QUERY_WORDS }

    /**
     * ReliefWeb's job feed, asked only for your positions in the countries of your searches, so its
     * answer is small. Null when no search names a place.
     */
    fun reliefWebUrl(searches: List<Search>): String? {
        val countries = searches.mapNotNull { Places.countryOf(it.location) }.distinct()
        val positions = searches.map { queryWords(it.keywords) }.filter { it.isNotEmpty() }.distinct()
        if (countries.isEmpty() || positions.isEmpty()) return null
        val query = "country.exact:(" + countries.joinToString(" OR ") { "\"$it\"" } + ") AND title:(" +
            positions.joinToString(" OR ") { "(" + it.joinToString(" AND ") + ")" } + ")"
        return "https://reliefweb.int/jobs/rss.xml?search=" + URLEncoder.encode(query, "UTF-8").replace("+", "%20")
    }

    private fun reliefWeb(searches: List<Search>): List<Found> {
        val url = reliefWebUrl(searches) ?: return emptyList()
        val (status, body) = Http.get(url, mapOf("User-Agent" to RemoteBoards.USER_AGENT))
        if (status != 200) throw IllegalStateException("HTTP $status")
        val listings = try {
            parseReliefWeb(body)
        } catch (e: XmlPullParserException) {
            throw IllegalStateException("ReliefWeb's answer wasn't the feed it should be")
        }
        return listings.mapNotNull { listing ->
            searches.firstOrNull { RemoteBoards.titleMatches(listing.title, it.keywords) && Places.inPlace(listing.where, it.location) }
                ?.let { Found(it, listing) }
        }
    }

    /** ReliefWeb's RSS: the country and organization are lines in each item's description. */
    fun parseReliefWeb(rss: String): List<RemoteBoards.Listing> {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(rss))
        val listings = mutableListOf<RemoteBoards.Listing>()
        var item: MutableMap<String, String>? = null
        var tag = ""
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = parser.name
                    if (tag == "item") item = mutableMapOf()
                }
                XmlPullParser.TEXT -> item?.let { it[tag] = (it[tag] ?: "") + parser.text }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "item") item?.let { fields ->
                        val link = fields["link"].orEmpty().trim()
                        val description = fields["description"].orEmpty()
                        fun line(label: String) = Regex("$label: ([^<]+)").find(description)?.groupValues?.get(1)?.trim().orEmpty()
                        val id = Regex("/job/(\\d+)").find(link)?.groupValues?.get(1) ?: link
                        listings += RemoteBoards.Listing("reliefweb:$id", fields["title"].orEmpty().trim(),
                            line("Organi[sz]ation"), line("Countr(?:y|ies)"), RemoteBoards.parseTime(fields["pubDate"].orEmpty()),
                            link, RELIEFWEB)
                        item = null
                    }
                    tag = ""
                }
            }
        }
        return listings
    }

    // ---------- Jooble ----------

    /** One search on Jooble: its documented request, the search's position and place. */
    private fun jooble(key: String, search: Search): List<Found> {
        val body = JSONObject().put("keywords", search.keywords).put("location", search.location).toString()
        val (status, text) = Http.post("https://jooble.org/api/${URLEncoder.encode(key.trim(), "UTF-8")}", body,
            mapOf("Content-Type" to "application/json", "User-Agent" to RemoteBoards.USER_AGENT))
        if (status == 401 || status == 403) throw IllegalStateException("Jooble didn't accept the API key (HTTP $status)")
        if (status != 200) throw IllegalStateException("HTTP $status")
        val listings = try {
            parseJooble(text)
        } catch (e: JSONException) {
            throw IllegalStateException("Jooble's answer wasn't in the format its API documents")
        }
        return listings.map { Found(search, it) }
    }

    /** Jooble's answer: {"totalCount": n, "jobs": [{"id", "title", "company", "location", "link", "updated", ...}]}. */
    fun parseJooble(json: String): List<RemoteBoards.Listing> {
        val jobs = JSONObject(json).optJSONArray("jobs") ?: JSONArray()
        return (0 until jobs.length()).mapNotNull { jobs.optJSONObject(it) }.map {
            val link = it.optString("link")
            val id = it.opt("id")?.toString()?.takeIf { s -> s.isNotBlank() && s != "null" } ?: link
            RemoteBoards.Listing("jooble:$id", it.optString("title"), it.optString("company"), it.optString("location"),
                RemoteBoards.parseTime(it.optString("updated")), link, JOOBLE)
        }
    }
}
