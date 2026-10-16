package com.jobwatch.app

import android.content.Context
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

object Checker {
    private const val MIN_MINUTES_BETWEEN_CHECKS = 2L
    private const val TIME_BUDGET_MS = 30_000L // background runs get about a minute; leave the rest for next time
    private const val WAITING = "Waiting: LinkedIn asked to slow down"
    private val lock = ReentrantLock()

    /** Gap between searches, so a round doesn't hit LinkedIn in one burst. Tests set it to 0. */
    @Volatile
    var pauseBetweenSearchesMs: LongRange = 2_000L..4_000L

    /**
     * A full round: all LinkedIn searches and the remote boards that are due, side by side, then
     * one alert for everything new. Returns the new jobs, or null when the LinkedIn check was
     * skipped (see [run]) and the boards found nothing.
     */
    fun round(context: Context, force: Boolean = false): List<Job>? {
        var fromBoards = emptyList<Job>()
        val boards = thread { fromBoards = runCatching { RemoteBoards.check(context, force, alert = false) }.getOrDefault(emptyList()) }
        val fromLinkedIn = run(context, force = force, alert = false)
        boards.join()
        val all = fromLinkedIn.orEmpty() + fromBoards
        if (all.isNotEmpty()) Alerts.deliver(context, all)
        return if (fromLinkedIn == null && fromBoards.isEmpty()) null else all
    }

    /**
     * Check the searches named in [labels] (null = all), save what's new and, with [alert], send alerts.
     * Returns the new jobs, or null when a background check ([force] false) is skipped:
     * checks are turned off, a check is running, or one ran moments ago. Checks the user
     * asked for wait for a running check to finish and then run.
     *
     * If LinkedIn says "slow down", the round stops there (one refused request, not one per
     * search) and the next regular check tries again. Nothing is lost meanwhile: each search's
     * next check looks back to its last successful one.
     */
    fun run(context: Context, labels: Set<String>? = null, force: Boolean = false, alert: Boolean = true): List<Job>? {
        if (force) lock.lock() else if (!lock.tryLock()) return null
        try {
            val now = System.currentTimeMillis()
            val data = Store.load(context)
            if (!force && !data.checksOn) return null // background checks are off; only checks the user asks for run
            if (!force && labels == null && now - data.lastRun < MIN_MINUTES_BETWEEN_CHECKS * 60_000) return null

            val seen = data.seen.toHashSet()
            val outcomes = mutableMapOf<String, Pair<List<Job>?, String>>() // label -> (new jobs, or null on error; status)
            val batch = mutableListOf<Job>()
            var slowDown = false
            val targets = data.searches.filter { labels == null || it.label in labels }
            for ((index, search) in targets.withIndex()) {
                if (System.currentTimeMillis() - now > TIME_BUDGET_MS) break // the rest keep their window for next time
                if (index > 0) Thread.sleep(pauseBetweenSearchesMs.random())
                try {
                    val jobs = LinkedIn.fetchNewJobs(search, seen, now)
                        .map { it.copy(search = search.label, foundAt = now, isNew = true) }
                    jobs.forEach { seen += it.id }
                    batch += jobs
                    outcomes[search.label] = jobs to "Checked ${timeText(now)}: ${jobs.size} new"
                } catch (e: SlowDownException) {
                    slowDown = true // stop here: trying the other searches would only make it worse
                    break
                } catch (e: Exception) {
                    outcomes[search.label] = null to "Error ${timeText(now)}: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            if (slowDown) targets.filter { it.label !in outcomes }.forEach { outcomes[it.label] = null to WAITING }

            // Merge into the latest saved state: a search may have been added or removed meanwhile.
            Store.update(context) { current ->
                current.copy(
                    searches = current.searches.map { search ->
                        val (jobs, status) = outcomes[search.label] ?: return@map search
                        if (jobs == null) search.copy(status = status) // not checked: keep its window so nothing is missed
                        else search.copy(lastCheck = now, found = search.found + jobs.size, status = status)
                    },
                    seen = current.seen + batch.map { it.id },
                    jobs = batch + current.jobs,
                    lastRun = if (labels == null) now else current.lastRun,
                    limited = when {
                        slowDown -> true
                        outcomes.values.any { it.first != null } -> false // LinkedIn answered again
                        else -> current.limited
                    },
                )
            }
            if (alert && batch.isNotEmpty()) Alerts.deliver(context, batch)
            return batch
        } finally {
            lock.unlock()
        }
    }
}
