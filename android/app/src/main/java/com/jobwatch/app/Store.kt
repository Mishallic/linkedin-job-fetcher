package com.jobwatch.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class AppData(
    val searches: List<Search> = emptyList(),
    val seen: List<String> = emptyList(), // ids of every job already reported
    val jobs: List<Job> = emptyList(),    // newest first
    val lastRun: Long = 0L,
    val limited: Boolean = false, // LinkedIn said "slow down" on the last check; the next check tries again
    val checksOn: Boolean = true, // false: nothing runs in the background until the user turns it back on
    val remoteOn: Boolean = true, // also check the remote job boards
    val remoteLastCheck: Long = 0L,
    val remoteStatus: String = "",
    val boardChecks: Map<String, Long> = emptyMap(), // board name -> when it was last read
    val rounds: List<Round> = emptyList(), // the last checks, newest first
)

/**
 * One check as the history shows it: when it started, what started it ([Checker.TRIGGER_ALARM] and
 * the others), and how it ended. [finishedAt] stays 0 while it runs, and for good if the phone
 * stopped the app before the check could finish.
 */
data class Round(val startedAt: Long, val trigger: String, val finishedAt: Long = 0L, val result: String = "")

/**
 * All app state in one JSON file. Every change goes through [update], one at a time,
 * so the screen and background checks never overwrite each other's changes.
 */
object Store {
    private const val KEEP_JOBS = 300
    private const val KEEP_SEEN = 5000
    const val KEEP_ROUNDS = 40

    // The searches you were using in the desktop app. Only used the very first time.
    private val STARTING_SEARCHES = listOf(
        "software engineer" to "Amman, Jordan",
        "software engineer" to "United Arab Emirates",
        "data analyst" to "United Arab Emirates",
        "software engineer" to "Saudi Arabia",
        "data analyst" to "Saudi Arabia",
    )

    /** Bumped on every save, so the screen can redraw when a background check finishes. */
    val changes = MutableStateFlow(0)

    private fun file(context: Context) = File(context.filesDir, "jobs.json")

    @Synchronized
    fun load(context: Context): AppData {
        val file = file(context)
        if (file.exists()) {
            try {
                return fromJson(JSONObject(file.readText()))
            } catch (e: Exception) {
                // unreadable file: start over below
            }
        }
        return AppData(searches = STARTING_SEARCHES.map { (keywords, location) -> Search(keywords, location) })
            .also { write(context, it) }
    }

    @Synchronized
    fun update(context: Context, change: (AppData) -> AppData): AppData {
        val next = change(load(context))
        write(context, next)
        return next
    }

    /** Mark jobs as new (dot) or read (no dot). */
    fun setNew(context: Context, ids: Set<String>, isNew: Boolean) =
        update(context) { d -> d.copy(jobs = d.jobs.map { if (it.id in ids) it.copy(isNew = isNew) else it }) }

    /**
     * Delete jobs from the list. Their ids stay in [AppData.seen], so a later check doesn't bring
     * them back as new. Returns them with their places in the list, for [restoreJobs].
     */
    fun deleteJobs(context: Context, ids: Set<String>): List<IndexedValue<Job>> {
        var removed = emptyList<IndexedValue<Job>>()
        update(context) { d ->
            removed = d.jobs.withIndex().filter { it.value.id in ids }
            d.copy(jobs = d.jobs.filter { it.id !in ids })
        }
        return removed
    }

    /** Undo [deleteJobs]: put the jobs back where they were. */
    fun restoreJobs(context: Context, removed: List<IndexedValue<Job>>) = update(context) { d ->
        val jobs = d.jobs.toMutableList()
        for ((index, job) in removed.sortedBy { it.index }) {
            if (jobs.none { it.id == job.id }) jobs.add(minOf(index, jobs.size), job)
        }
        d.copy(jobs = jobs)
    }

    /** Record that a round started. Returns its start time, which [finishRound] takes to find it again. */
    fun beginRound(context: Context, trigger: String): Long {
        val now = System.currentTimeMillis()
        update(context) { d -> d.copy(rounds = (listOf(Round(now, trigger)) + d.rounds).take(KEEP_ROUNDS)) }
        return now
    }

    fun finishRound(context: Context, startedAt: Long, result: String) = update(context) { d ->
        d.copy(rounds = d.rounds.map { if (it.startedAt == startedAt) it.copy(finishedAt = System.currentTimeMillis(), result = result) else it })
    }

    private fun write(context: Context, data: AppData) {
        val tidy = data.copy(
            searches = data.searches.distinctBy { it.label },
            jobs = data.jobs.distinctBy { it.id }.take(KEEP_JOBS),
            seen = data.seen.distinct().takeLast(KEEP_SEEN),
            rounds = data.rounds.take(KEEP_ROUNDS),
        )
        // Write a temp file, then swap it in, so a crash mid-write can't leave a half-written file.
        val tmp = File(context.filesDir, "jobs.json.tmp")
        tmp.writeText(toJson(tidy).toString())
        Files.move(tmp.toPath(), file(context).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        changes.value = changes.value + 1
    }

    private fun toJson(data: AppData) = JSONObject()
        .put("searches", JSONArray(data.searches.map {
            JSONObject().put("keywords", it.keywords).put("location", it.location).put("lastCheck", it.lastCheck)
                .put("found", it.found).put("status", it.status)
        }))
        .put("seen", JSONArray(data.seen))
        .put("jobs", JSONArray(data.jobs.map {
            JSONObject().put("id", it.id).put("title", it.title).put("company", it.company).put("location", it.location)
                .put("posted", it.posted).put("url", it.url).put("search", it.search).put("foundAt", it.foundAt)
                .put("isNew", it.isNew).put("source", it.source)
        }))
        .put("lastRun", data.lastRun)
        .put("limited", data.limited)
        .put("checksOn", data.checksOn)
        .put("remoteOn", data.remoteOn)
        .put("remoteLastCheck", data.remoteLastCheck)
        .put("remoteStatus", data.remoteStatus)
        .put("boardChecks", JSONObject(data.boardChecks))
        .put("rounds", JSONArray(data.rounds.map {
            JSONObject().put("startedAt", it.startedAt).put("trigger", it.trigger).put("finishedAt", it.finishedAt).put("result", it.result)
        }))

    private fun fromJson(json: JSONObject): AppData {
        val searches = json.optJSONArray("searches") ?: JSONArray()
        val seen = json.optJSONArray("seen") ?: JSONArray()
        val jobs = json.optJSONArray("jobs") ?: JSONArray()
        val boardChecks = json.optJSONObject("boardChecks") ?: JSONObject()
        val rounds = json.optJSONArray("rounds") ?: JSONArray()
        return AppData(
            searches = List(searches.length()) { i ->
                val o = searches.getJSONObject(i)
                Search(o.getString("keywords"), o.optString("location"), o.optLong("lastCheck", System.currentTimeMillis()),
                    o.optInt("found"), o.optString("status", "Not checked yet"))
            },
            seen = List(seen.length()) { i -> seen.getString(i) },
            jobs = List(jobs.length()) { i ->
                val o = jobs.getJSONObject(i)
                Job(o.getString("id"), o.optString("title"), o.optString("company"), o.optString("location"),
                    o.optString("posted"), o.optString("url"), o.optString("search"), o.optLong("foundAt"), o.optBoolean("isNew"),
                    o.optString("source", "LinkedIn"))
            },
            lastRun = json.optLong("lastRun"),
            limited = json.optBoolean("limited"),
            checksOn = json.optBoolean("checksOn", true),
            remoteOn = json.optBoolean("remoteOn", true),
            remoteLastCheck = json.optLong("remoteLastCheck"),
            remoteStatus = json.optString("remoteStatus"),
            boardChecks = boardChecks.keys().asSequence().associateWith { boardChecks.optLong(it) },
            rounds = List(rounds.length()) { i ->
                val o = rounds.getJSONObject(i)
                Round(o.getLong("startedAt"), o.optString("trigger"), o.optLong("finishedAt"), o.optString("result"))
            },
        )
    }
}
