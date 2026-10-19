package com.jobwatch.app

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Job(
    val id: String,
    val title: String,
    val company: String,
    val location: String,
    val posted: String,
    val url: String,
    val search: String = "",
    val foundAt: Long = 0L,
    val isNew: Boolean = false,
    val source: String = "LinkedIn", // the site that published it; remote boards require naming them
    val postedAt: Long = 0L, // when it was posted, as its source tells it (LinkedIn: to the minute); 0 = unknown
    val notifiedAt: Long = 0L, // when the phone was told about it; 0 = not (yet)
)

data class Search(
    val keywords: String,
    val location: String,
    // Jobs are covered up to this moment. Starting it at "now" means that if the first
    // check fails, the next one still looks back to when the search was added.
    val lastCheck: Long = Clock.now(),
    val found: Int = 0,
    val status: String = "Not checked yet",
    /** When LinkedIn was last asked for this search (any page, any answer). Its next turn is [Scheduler.INTERVAL_MINUTES] minutes later. */
    val lastTry: Long = 0L,
    /** A check reading several pages of results: where its next page starts. 0 when none is under way. */
    val cursor: Int = 0,
    /** When the check under way began. Its window ends there, so the next check starts from it. */
    val checkStartedAt: Long = 0L,
    /** New jobs the check under way has found so far. */
    val checkNew: Int = 0,
    /** The job ids on the page read last, to notice LinkedIn repeating a page instead of ending. */
    val lastPageIds: List<String> = emptyList(),
) {
    /** Where the search looks, as shown to the user. */
    val place: String get() = location.ifBlank { "Anywhere" }

    val label: String get() = if (location.isBlank()) keywords else "$keywords @ $location"
}

/** LinkedIn answered 429 (or its 999 bot block): it wants fewer requests from this connection. */
class SlowDownException : IllegalStateException("LinkedIn asked to slow down")

/** LinkedIn's public (logged-out) job search, the same one linkedin.com/jobs uses. */
object LinkedIn {
    private const val SEARCH_URL = "https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search"
    private const val JOB_URL = "https://www.linkedin.com/jobs/view/"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    // Android can delay background work, so each check looks back to the previous one
    // (plus an overlap) instead of a fixed interval. Jobs never fall into a gap.
    const val MIN_WINDOW_MINUTES = 15L
    const val OVERLAP_MINUTES = 5L
    const val MAX_WINDOW_HOURS = 24L
    const val MAX_PAGES = 40 // safety stop only
    const val PAGE_SIZE = 10 // LinkedIn returns 10 jobs per page; a shorter page is the last one

    private val CARD_START = "data-entity-urn=\"urn:li:jobPosting:"
    private val ID = Regex("^\\d+")
    private val TITLE = Regex("class=\"base-search-card__title\"[^>]*>([\\s\\S]*?)</h3>")
    private val COMPANY = Regex("class=\"base-search-card__subtitle\"[^>]*>([\\s\\S]*?)</h4>")
    private val LOCATION = Regex("class=\"job-search-card__location\"[^>]*>([\\s\\S]*?)</span>")
    private val POSTED = Regex("<time[^>]*datetime=\"([^\"]*)\"")
    private val AGE = Regex("<time[^>]*>\\s*([^<]*?)\\s*</time>")
    private val AGO = Regex("(\\d+)\\s+(second|minute|hour|day|week|month)s?\\s+ago", RegexOption.IGNORE_CASE)

    /** Minutes since a job was posted, from LinkedIn's "5 minutes ago" or "2 hours ago"; "Just now" is 0. */
    fun ageMinutes(text: String): Long? {
        if (text.trim().equals("just now", ignoreCase = true)) return 0
        val match = AGO.find(text) ?: return null
        val n = match.groupValues[1].toLong()
        return when (match.groupValues[2].lowercase()) {
            "second" -> 0
            "minute" -> n
            "hour" -> n * 60
            "day" -> n * 60 * 24
            "week" -> n * 60 * 24 * 7
            else -> n * 60 * 24 * 30
        }
    }

    /** The jobs on a results page. [now] is when it was read: "5 minutes ago" is counted back from it. */
    fun parse(html: String, now: Long = Clock.now()): List<Job> = html.split(CARD_START).drop(1).mapNotNull { card ->
        val id = ID.find(card)?.value ?: return@mapNotNull null
        val age = AGE.find(card)?.groupValues?.get(1)?.let(::ageMinutes)
        Job(
            id = id,
            title = grab(card, TITLE),
            company = grab(card, COMPANY),
            location = grab(card, LOCATION),
            posted = POSTED.find(card)?.groupValues?.get(1).orEmpty(),
            url = "$JOB_URL$id/",
            postedAt = age?.let { now - it * 60_000 } ?: 0L,
        )
    }

    /** Seconds to look back: since the last successful check plus the overlap, within bounds. */
    fun windowSeconds(lastCheck: Long, now: Long): Long {
        val sinceLast = (now - lastCheck).coerceAtLeast(0L) / 1000 + OVERLAP_MINUTES * 60
        return sinceLast.coerceIn(MIN_WINDOW_MINUTES * 60, MAX_WINDOW_HOURS * 3600)
    }

    /** The search URL for one results page. */
    fun pageUrl(search: Search, seconds: Long, start: Int): String {
        val params = listOf(
            "keywords" to search.keywords,
            "location" to search.location,
            "f_TPR" to "r$seconds",
            "sortBy" to "DD", // newest first
            "start" to start.toString(),
        )
        return "$SEARCH_URL?" + params.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
    }

    /**
     * One page of the search's results, newest first, starting at [start]: its jobs, or null when
     * LinkedIn says there are no more. [seconds] is how far back to look (see [windowSeconds]). One
     * request; the checks space them out ([Checker]).
     */
    fun fetchPage(search: Search, seconds: Long, start: Int): List<Job>? {
        val (status, body) = Http.get(pageUrl(search, seconds, start), mapOf("User-Agent" to USER_AGENT, "Accept-Language" to "en-US,en;q=0.9"))
        return when {
            status == 200 -> parse(body)
            status == 400 && start > 0 -> null // LinkedIn's answer past the last page
            status == 429 || status == 999 -> throw SlowDownException()
            else -> throw IllegalStateException("LinkedIn returned HTTP $status")
        }
    }
}

object Http {
    /** Tests swap in canned answers here instead of calling LinkedIn or CallMeBot. */
    @Volatile
    var fake: ((String) -> Pair<Int, String>)? = null

    /** Tests swap in canned answers to POST requests (Jooble's) here; without it, [fake] answers them too. */
    @Volatile
    var fakePost: ((url: String, body: String) -> Pair<Int, String>)? = null

    fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        fakePost?.let { return it(url, body) }
        fake?.let { return it(url) }
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            connection.outputStream.use { it.write(body.toByteArray()) }
            val status = connection.responseCode
            val stream = if (status < 400) connection.inputStream else connection.errorStream
            return status to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            connection.disconnect()
        }
    }

    fun get(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        fake?.let { return it(url) }
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            val status = connection.responseCode
            val stream = if (status < 400) connection.inputStream else connection.errorStream
            return status to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            connection.disconnect()
        }
    }
}

private val ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")
private val ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#\\d+|[a-zA-Z]+);")
private val TAG = Regex("<[^>]+>")
private val SPACES = Regex("\\s+")

fun decodeEntities(text: String): String = ENTITY.replace(text) { match ->
    val code = match.groupValues[1]
    val number = when {
        code.startsWith("#x") || code.startsWith("#X") -> code.substring(2).toIntOrNull(16)
        code.startsWith("#") -> code.substring(1).toIntOrNull()
        else -> null
    }
    when {
        number != null -> String(Character.toChars(number))
        code.startsWith("#") -> match.value
        else -> ENTITIES[code.lowercase()] ?: match.value
    }
}

fun cleanText(html: String): String = decodeEntities(TAG.replace(html, " ")).replace(SPACES, " ").trim()

private fun grab(text: String, pattern: Regex): String = pattern.find(text)?.let { cleanText(it.groupValues[1]) }.orEmpty()

/** "every minute" or "every 5 minutes". */
fun everyText(minutes: Long): String = if (minutes == 1L) "every minute" else "every $minutes minutes"

fun timeText(ms: Long): String {
    if (ms <= 0) return "never"
    val date = Date(ms)
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
    val day = SimpleDateFormat("yyyyMMdd", Locale.US)
    return if (day.format(date) == day.format(Date())) time
    else "${SimpleDateFormat("MMM d", Locale.getDefault()).format(date)}, $time"
}
