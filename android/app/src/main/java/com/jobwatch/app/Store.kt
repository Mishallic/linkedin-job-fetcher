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
    val remoteOn: Boolean = true, // also check the remote job boards (every few hours)
    val remoteLastCheck: Long = 0L,
    val remoteStatus: String = "",
)

/**
 * All app state in one JSON file. Every change goes through [update], one at a time,
 * so the screen and background checks never overwrite each other's changes.
 */
object Store {
    private const val KEEP_JOBS = 300
    private const val KEEP_SEEN = 5000

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

    private fun write(context: Context, data: AppData) {
        val tidy = data.copy(
            searches = data.searches.distinctBy { it.label },
            jobs = data.jobs.distinctBy { it.id }.take(KEEP_JOBS),
            seen = data.seen.distinct().takeLast(KEEP_SEEN),
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

    private fun fromJson(json: JSONObject): AppData {
        val searches = json.optJSONArray("searches") ?: JSONArray()
        val seen = json.optJSONArray("seen") ?: JSONArray()
        val jobs = json.optJSONArray("jobs") ?: JSONArray()
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
        )
    }
}
