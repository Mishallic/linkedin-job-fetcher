package com.jobwatch.app

import android.content.Context
import java.util.concurrent.locks.ReentrantLock

object Checker {
    private const val MIN_MINUTES_BETWEEN_CHECKS = 2L
    private const val TIME_BUDGET_MS = 30_000L // background runs get about a minute; leave the rest for next time
    private const val WAITING = "Waiting: LinkedIn asked to slow down"
    private val lock = ReentrantLock()

    /** Gap between searches, so a round doesn't hit LinkedIn in one burst. Tests set it to 0. */
    @Volatile
    var pauseBetweenSearchesMs: LongRange = 2_000L..4_000L

    /**
     * Check the searches named in [labels] (null = all), save what's new and send alerts.
     * Returns the new jobs, or null when a background check ([force] false) is skipped:
     * checks are turned off, a check is running, or one ran moments ago. Checks the user
     * asked for wait for a running check to finish and then run.
     *
     * If LinkedIn says "slow down", the round stops there (one refused request, not one per
     * search) and the next regular check tries again. Nothing is lost meanwhile: each search's
     * next check looks back to its last successful one.
     */
    fun run(context: Context, labels: Set<String>? = null, force: Boolean = false): List<Job>? {
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
            if (batch.isNotEmpty()) Alerts.deliver(context, batch)
            return batch
        } finally {
            lock.unlock()
        }
    }
}
