package com.jobwatch.app

import android.content.Context
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

object Checker {
    private const val MIN_MINUTES_BETWEEN_CHECKS = 2L
    private const val TIME_BUDGET_MS = 30_000L // background runs get about a minute; leave the rest for next time
    private const val WAITING = "Waiting: LinkedIn asked to slow down"
    private val lock = ReentrantLock()

    /** What started a round, as the history ([AppData.rounds]) shows it. */
    const val TRIGGER_ALARM = "10-minute alarm"
    const val TRIGGER_OPENED = "Opened the app, no alarm was set"
    const val TRIGGER_REFRESH = "Refresh button"
    const val TRIGGER_SWITCH = "Checks turned on"

    /** Gap between searches, so a round doesn't hit LinkedIn in one burst. Tests set it to 0. */
    @Volatile
    var pauseBetweenSearchesMs: LongRange = 2_000L..4_000L

    /** How a LinkedIn check went: the new jobs (null when it was skipped) and a line for the history. */
    private class Outcome(val jobs: List<Job>?, val note: String)

    /**
     * A full round: all LinkedIn searches and the remote boards that are due, side by side, then
     * one alert for everything new. Returns the new jobs, or null when the LinkedIn check was
     * skipped (see [run]) and the boards found nothing.
     *
     * Every round goes into the history when it starts and again when it ends, so a round the
     * phone stopped half-way shows up too (started, never finished). An error in a round is
     * recorded there as well, never thrown: a background round must not take the app down.
     */
    fun round(context: Context, force: Boolean = false, trigger: String = TRIGGER_ALARM): List<Job>? {
        val startedAt = Store.beginRound(context, trigger)
        var note = ""
        try {
            var fromBoards: Result<List<Job>> = Result.success(emptyList())
            val boards = thread { fromBoards = runCatching { RemoteBoards.check(context, force, alert = false) } }
            val linkedIn = check(context, labels = null, force = force)
            boards.join()
            val boardJobs = fromBoards.getOrDefault(emptyList())
            val all = linkedIn.jobs.orEmpty() + boardJobs
            if (all.isNotEmpty()) Alerts.deliver(context, all)
            note = listOfNotNull(
                linkedIn.note,
                if (boardJobs.isNotEmpty()) "${boardJobs.size} new from the boards" else null,
                fromBoards.exceptionOrNull()?.let { "boards failed: ${it.message ?: it.javaClass.simpleName}" },
            ).joinToString(" · ")
            return if (linkedIn.jobs == null && boardJobs.isEmpty()) null else all
        } catch (e: Exception) {
            note = "Stopped by an error: ${e.message ?: e.javaClass.simpleName}"
            return null
        } finally {
            Store.finishRound(context, startedAt, note)
        }
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
        val outcome = check(context, labels, force)
        if (alert && !outcome.jobs.isNullOrEmpty()) Alerts.deliver(context, outcome.jobs)
        return outcome.jobs
    }

    private fun check(context: Context, labels: Set<String>?, force: Boolean): Outcome {
        if (force) lock.lock() else if (!lock.tryLock()) return Outcome(null, "Skipped: another check is still running")
        try {
            val now = System.currentTimeMillis()
            val data = Store.load(context)
            if (!force && !data.checksOn) return Outcome(null, "Skipped: background checks are off")
            if (!force && labels == null && now - data.lastRun < MIN_MINUTES_BETWEEN_CHECKS * 60_000) {
                return Outcome(null, "Skipped: the last check was ${(now - data.lastRun) / 1000} s ago")
            }

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
            return Outcome(batch, note(targets.size, outcomes, batch.size, slowDown))
        } finally {
            lock.unlock()
        }
    }

    /** One line on how the LinkedIn part went, for the history. */
    private fun note(searches: Int, outcomes: Map<String, Pair<List<Job>?, String>>, found: Int, slowDown: Boolean): String {
        val checked = outcomes.count { it.value.first != null }
        val errors = outcomes.values.filter { it.first == null && it.second != WAITING }
        val parts = mutableListOf<String>()
        if (found > 0) parts += "$found new on LinkedIn" else if (checked > 0) parts += "no new jobs on LinkedIn"
        when {
            slowDown -> parts += "LinkedIn asked to slow down" + if (checked > 0) " after $checked of $searches searches" else " at the first search"
            checked + errors.size < searches -> parts += "${checked + errors.size} of $searches searches done, the rest next time"
        }
        if (errors.isNotEmpty()) {
            parts += "errors in ${errors.size} search${if (errors.size == 1) "" else "es"}: ${errors.first().second.substringAfter(": ")}"
        }
        return parts.joinToString(" · ").replaceFirstChar { it.uppercase() }.ifEmpty { "No searches to check" }
    }
}
