package com.jobwatch.app

import android.content.Context
import android.net.ConnectivityManager
import android.util.Xml
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

/**
 * Remote job boards that publish official feeds. Their terms ask apps to link to the original
 * listing and name the board as the source (every job keeps its URL and source), and two of them
 * limit how often they may be read: Jobicy at most hourly, Remotive at most 4 times a day.
 *
 * The boards are read on the same 10-minute rounds as LinkedIn, each one only when it's due:
 * the boards without a limit every round, Jobicy and Remotive as often as their terms allow.
 * On mobile data every board is read at most hourly, since a round downloads about 400 KB.
 */
object RemoteBoards {
    const val LABEL = "Remote job boards"
    const val MOBILE_DATA_MINUTES = 60L
    private const val EVERY_ROUND = 5L // no limit: read on every round; this only stops a refresh right after one
    private const val LEGACY_WORK = "remote-boards" // 1.8 and earlier read the boards every 6 hours with WorkManager
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) JobWatch/1.11"
    private const val FIRST_CHECK_DAYS = 7L // the first check shows the past week's jobs
    private const val MAX_AGE_DAYS = 14L
    private const val DAY_MS = 86_400_000L

    // A listing qualifies when it's open to everyone, to a region that includes yours,
    // or names a place from your searches.
    private val OPEN_TO_ALL = listOf("worldwide", "anywhere", "global", "emea", "middle east", "mena", "gcc")
    private val ALIASES = mapOf("united arab emirates" to listOf("uae"), "saudi arabia" to listOf("ksa"))

    data class Listing(
        val id: String, val title: String, val company: String, val where: String,
        val postedAt: Long, val url: String, val source: String,
    )

    /** A board's feed, and the least time its terms allow between two reads. */
    class Board(val name: String, val gapMinutes: Long, val read: (positions: List<String>) -> List<Listing>)

    val boards = listOf(
        Board("We Work Remotely", EVERY_ROUND) { parseWeWorkRemotely(get("https://weworkremotely.com/remote-jobs.rss")) },
        Board("Himalayas", EVERY_ROUND) { positions ->
            positions.flatMap { parseHimalayas(get("https://himalayas.app/jobs/api/search?q=${encode(it)}&worldwide=true")) }
        },
        Board("Remote OK", EVERY_ROUND) { parseRemoteOk(get("https://remoteok.com/api")) },
        Board("Jobicy", 60) { parseJobicy(get("https://jobicy.com/api/v2/remote-jobs?count=100")) },
        Board("Remotive", 6 * 60) { parseRemotive(get("https://remotive.com/api/remote-jobs?limit=150")) },
    )

    val boardNames = boards.dropLast(1).joinToString(", ") { it.name } + " and " + boards.last().name

    /** Whether the phone is on mobile data (or another metered network) right now. Tests swap it. */
    @Volatile
    var onMobileData: (Context) -> Boolean = { context ->
        context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false
    }

    /**
     * On mobile data, a board without a stricter limit waits 55 minutes, so that on the 10-minute
     * rounds it's read every hour rather than every 70 minutes.
     */
    fun gapMinutes(board: Board, mobileData: Boolean): Long =
        if (mobileData) maxOf(board.gapMinutes, MOBILE_DATA_MINUTES - EVERY_ROUND) else board.gapMinutes

    // ---------- reading each board's feed ----------

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    fun parseRemotive(json: String): List<Listing> = JSONObject(json).getJSONArray("jobs").objects().map {
        Listing("remotive:${it.opt("id")}", it.optString("title"), it.optString("company_name"),
            it.optString("candidate_required_location"), parseTime(it.optString("publication_date")), it.optString("url"), "Remotive")
    }

    fun parseRemoteOk(json: String): List<Listing> = JSONArray(json).objects().filter { it.has("position") }.map {
        Listing("remoteok:${it.opt("id")}", it.optString("position"), it.optString("company"),
            it.optString("location"), parseTime(it.optString("date")), it.optString("url"), "Remote OK")
    }

    fun parseHimalayas(json: String): List<Listing> = JSONObject(json).getJSONArray("jobs").objects().map {
        val places = it.optJSONArray("locationRestrictions")?.let { a -> List(a.length()) { i -> a.optString(i) } }.orEmpty()
        Listing("himalayas:${it.optString("guid").ifBlank { it.optString("applicationLink") }}", it.optString("title"),
            it.optString("companyName"), places.joinToString(", ").ifBlank { "Worldwide" }, // no restriction = worldwide
            parseTime(it.optString("pubDate")), it.optString("applicationLink"), "Himalayas")
    }

    fun parseJobicy(json: String): List<Listing> = (JSONObject(json).optJSONArray("jobs")?.objects() ?: emptyList()).map {
        Listing("jobicy:${it.opt("id")}", it.optString("jobTitle"), it.optString("companyName"),
            it.optString("jobGeo"), parseTime(it.optString("pubDate")), it.optString("url"), "Jobicy")
    }

    /** RSS where each title reads "Company: Role". */
    fun parseWeWorkRemotely(rss: String): List<Listing> {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(rss))
        val listings = mutableListOf<Listing>()
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
                        val (company, role) = fields["title"].orEmpty().trim().split(": ", limit = 2)
                            .let { if (it.size == 2) it[0] to it[1] else "" to it[0] }
                        val link = fields["link"].orEmpty().trim()
                        listings += Listing("wwr:${fields["guid"]?.trim()?.ifBlank { null } ?: link}", role, company,
                            fields["region"].orEmpty().trim(), parseTime(fields["pubDate"].orEmpty()), link, "We Work Remotely")
                    }
                    if (parser.name == "item") item = null
                    tag = ""
                }
            }
        }
        return listings
    }

    /** The boards use ISO dates with and without offsets, epoch seconds, and RSS dates. 0 = unknown. */
    fun parseTime(value: String): Long {
        val text = value.trim()
        if (text.isEmpty()) return 0L
        text.toLongOrNull()?.let { return if (it < 100_000_000_000L) it * 1000 else it }
        return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }
            .recoverCatching { LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli() }
            .recoverCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .getOrDefault(0L)
    }

    // ---------- which listings to keep ----------

    /** Every word of the position appears in the title: "data analyst" matches "Senior Data Analyst". */
    fun titleMatches(title: String, position: String): Boolean {
        val words = position.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
        val text = title.lowercase()
        return words.isNotEmpty() && words.all { it in text }
    }

    /** The places named in your searches, e.g. "Amman, Jordan" gives "amman" and "jordan". */
    fun placesOf(searches: List<Search>): List<String> = searches
        .flatMap { search -> search.location.lowercase().split(",").map { it.trim() } }
        .filter { it.isNotEmpty() }
        .flatMap { listOf(it) + ALIASES[it].orEmpty() }
        .distinct()

    fun isOpenTo(where: String, places: List<String>): Boolean {
        val text = where.lowercase()
        return (OPEN_TO_ALL + places).any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(text) }
    }

    private fun duplicateKey(title: String, company: String) =
        "dup:" + "$title|$company".lowercase().replace(Regex("[^a-z0-9|]"), "")

    // ---------- checking ----------

    private fun get(url: String): String {
        val (status, body) = Http.get(url, mapOf("User-Agent" to USER_AGENT))
        if (status != 200) throw IllegalStateException("HTTP $status")
        return body
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    /** Listings from the [due] boards, read side by side, and the names of boards that couldn't be read. */
    private fun fetch(due: List<Board>, positions: List<String>): Pair<List<Listing>, List<String>> {
        val results = arrayOfNulls<Result<List<Listing>>>(due.size)
        due.mapIndexed { i, board -> thread { results[i] = runCatching { board.read(positions) } } }.forEach { it.join() }
        val listings = results.flatMap { it?.getOrNull().orEmpty() }
        val failed = due.filterIndexed { i, _ -> results[i]?.isSuccess != true }.map { it.name }
        return listings to failed
    }

    private val lock = ReentrantLock()

    /**
     * Read the boards that are due, for your positions. Saves and returns the new jobs, and with
     * [alert] announces them. Reads nothing while the boards are off, while background checks are
     * off (unless [force]: you asked), or while another read of the boards is running.
     */
    fun check(context: Context, force: Boolean = false, alert: Boolean = true): List<Job> {
        if (!lock.tryLock()) return emptyList()
        try {
            return checkDue(context, force, alert)
        } finally {
            lock.unlock()
        }
    }

    private fun checkDue(context: Context, force: Boolean, alert: Boolean): List<Job> {
        val now = System.currentTimeMillis()
        val data = Store.load(context)
        val positions = data.searches.map { it.keywords.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        if (!data.remoteOn || (!data.checksOn && !force) || positions.isEmpty()) return emptyList()
        val mobileData = onMobileData(context)
        val due = boards.filter { board ->
            now - (data.boardChecks[board.name] ?: data.remoteLastCheck) >= gapMinutes(board, mobileData) * 60_000
        }
        if (due.isEmpty()) return emptyList()
        // Note the reads before making them, so each board's limit holds even if this one is cut short.
        Store.update(context) { it.copy(boardChecks = it.boardChecks + due.associate { board -> board.name to now }) }
        val places = placesOf(data.searches)
        val (listings, failed) = fetch(due, positions)

        val oldest = now - (if (data.remoteLastCheck == 0L) FIRST_CHECK_DAYS else MAX_AGE_DAYS) * DAY_MS
        val seen = data.seen.toHashSet()
        val fresh = mutableListOf<Job>()
        val newSeen = mutableListOf<String>()
        for (listing in listings.sortedByDescending { it.postedAt }) {
            val title = cleanText(listing.title)
            val company = cleanText(listing.company)
            val duplicate = duplicateKey(title, company) // the same job is often on several boards
            when {
                listing.id in seen || duplicate in seen -> continue
                !listing.url.startsWith("http") -> continue
                listing.postedAt in 1 until oldest -> continue
                positions.none { titleMatches(title, it) } -> continue
                !isOpenTo(listing.where, places) -> continue
            }
            seen += listing.id
            seen += duplicate
            newSeen += listOf(listing.id, duplicate)
            val posted = if (listing.postedAt > 0) Instant.ofEpochMilli(listing.postedAt).atOffset(ZoneOffset.UTC).toLocalDate().toString() else ""
            fresh += Job(listing.id, title, company, listing.where.ifBlank { "Remote" }, posted, listing.url,
                search = LABEL, foundAt = now, isNew = true, source = listing.source)
        }

        val status = "Checked ${timeText(now)}: ${fresh.size} new" +
            if (failed.isNotEmpty()) " (couldn't reach ${failed.joinToString(", ")}; trying again later)" else ""
        Store.update(context) { current ->
            current.copy(seen = current.seen + newSeen, jobs = fresh + current.jobs, remoteLastCheck = now, remoteStatus = status)
        }
        if (alert && fresh.isNotEmpty()) Alerts.deliver(context, fresh)
        return fresh
    }

    /** Cancel the 6-hourly schedule that 1.8 and earlier left in WorkManager. */
    fun cancelOldSchedule(context: Context) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(LEGACY_WORK) }
    }
}
