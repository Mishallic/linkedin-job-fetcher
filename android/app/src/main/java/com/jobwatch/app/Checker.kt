package com.jobwatch.app

import android.content.Context
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

/**
 * The checks, one "tick" at a time. A tick sends LinkedIn at most one request: the next page of
 * the search whose turn it is. LinkedIn requests are at least [Scheduler.SPACING_MINUTES] minutes
 * apart whatever starts the tick (the alarm, the refresh button, a new search), so with up to
 * [Store.MAX_SEARCHES] searches LinkedIn gets at most 5 requests every 10 minutes, and each search
 * is checked every [Scheduler.INTERVAL_MINUTES] minutes. A search with more new jobs than one page
 * holds reads one page per turn. A tick also reads the job boards that are due, side by side with
 * LinkedIn: other sites, with limits of their own.
 *
 * Every tick goes into the history ([AppData.rounds]) when it starts and again when it ends, so one
 * the phone stopped half-way shows as unfinished. New jobs are announced at most every 10 minutes,
 * all together, so a busy stretch brings one alert rather than five.
 */
object Checker {
    private const val TOLERANCE_MS = 5_000L // alarms and clocks are a little out; don't miss a turn over it
    private const val SPACING_MS = Scheduler.SPACING_MINUTES * 60_000
    private const val INTERVAL_MS = Scheduler.INTERVAL_MINUTES * 60_000
    private const val WAITING = "Waiting: LinkedIn asked to slow down"
    private val lock = ReentrantLock()

    /** What started a tick, as the history shows it. */
    const val TRIGGER_ALARM = "Scheduled check"
    const val TRIGGER_OPENED = "Opened the app, no check was scheduled"
    const val TRIGGER_REFRESH = "Refresh button"
    const val TRIGGER_SWITCH = "Checks turned on"
    const val TRIGGER_ADDED = "Search added"

    /** What a tick found, and a line about it for the screen. */
    class TickResult(val jobs: List<Job>, val message: String)

    /** The LinkedIn part of a tick: the new jobs, lines for the history and the screen, and what to save. */
    private class Turn(val jobs: List<Job>, val note: String, val message: String, val save: (AppData) -> AppData = { it })

    /**
     * The search whose turn it is: one still reading its pages, or the one asked longest ago once
     * its 10 minutes are up; [prefer] (a search just added) goes first when it's among them. With
     * [anyway] (you tapped refresh), the one asked longest ago even before its 10 minutes are up.
     */
    fun nextSearch(searches: List<Search>, now: Long, anyway: Boolean = false, prefer: String? = null): Search? {
        val due = searches.filter { it.cursor > 0 || now - minOf(it.lastTry, now) >= INTERVAL_MS - TOLERANCE_MS }
        val candidates = due.ifEmpty { if (anyway) searches else emptyList() }
        return candidates.firstOrNull { it.label == prefer } ?: candidates.minByOrNull { it.lastTry }
    }

    /**
     * The soonest LinkedIn may be asked again: 2 minutes after the last request, and, unless [force],
     * not before a slow-down pause ends. Times saved under a clock that was later set back count as now.
     */
    fun linkedInReadyAt(data: AppData, now: Long, force: Boolean = false): Long {
        val spaced = minOf(data.lastLinkedInAt, now) + SPACING_MS
        val paused = if (force) 0L else minOf(data.linkedInPausedUntil, now + INTERVAL_MS)
        return maxOf(spaced, paused)
    }

    /**
     * When the next tick should run: the next search's turn (once LinkedIn may be asked) or the next
     * job board read, whichever comes first, and never sooner than 2 minutes after the last tick.
     */
    fun nextTickAt(context: Context, data: AppData, now: Long): Long {
        val linkedIn = data.searches.minOfOrNull { if (it.cursor > 0) 0L else minOf(it.lastTry, now) + INTERVAL_MS }
            ?.let { maxOf(it, linkedInReadyAt(data, now)) }
        val due = listOfNotNull(linkedIn, RemoteBoards.nextDueAt(context, data)).minOrNull() ?: (now + INTERVAL_MS)
        return maxOf(due, minOf(data.lastRun, now) + SPACING_MS, now + 1_000)
    }

    /**
     * One tick: LinkedIn's next page for the search whose turn it is, if LinkedIn may be asked yet,
     * and the job boards that are due. Saves what's new, queues it for an alert, records the tick in
     * the history and sets the alarm for the next one. A background tick ([force] false) is skipped
     * while checks are off or another tick runs. A tick you asked for waits for a running one, runs
     * with checks off and needn't wait out a slow-down pause, but still keeps LinkedIn's 2 minutes.
     */
    fun tick(context: Context, trigger: String = TRIGGER_ALARM, force: Boolean = false, prefer: String? = null): TickResult {
        if (force) lock.lock() else if (!lock.tryLock()) return TickResult(emptyList(), "A check is already running.")
        val (result, announce) = try {
            tickLocked(context, trigger, force, prefer)
        } finally {
            lock.unlock()
        }
        if (announce.isNotEmpty()) runCatching { Alerts.deliver(context, announce) } // email can be slow: not under the lock
        return result
    }

    private fun tickLocked(context: Context, trigger: String, force: Boolean, prefer: String?): Pair<TickResult, List<Job>> {
        val data = Store.load(context)
        if (!force && !data.checksOn) return TickResult(emptyList(), "Background checks are off.") to emptyList()
        val now = System.currentTimeMillis()
        val ready = linkedInReadyAt(data, now, force)
        val search = if (now >= ready - TOLERANCE_MS) nextSearch(data.searches, now, anyway = force, prefer = prefer) else null
        // Note the tick, and the request before making it, so the 2 minutes hold even if the phone stops this tick.
        Store.update(context) { d ->
            d.copy(
                rounds = (listOf(Round(now, trigger)) + d.rounds).take(Store.KEEP_ROUNDS),
                lastRun = now,
                lastLinkedInAt = if (search != null) now else d.lastLinkedInAt,
                searches = d.searches.map { if (it.label == search?.label) it.copy(lastTry = now) else it },
            )
        }
        var announce = emptyList<Job>()
        val result = try {
            var boards = Result.success(RemoteBoards.Read.NONE)
            val reading = thread { boards = runCatching { RemoteBoards.check(context, force) } }
            val paused = !force && now < minOf(data.linkedInPausedUntil, now + INTERVAL_MS) - TOLERANCE_MS
            val turn = if (search != null) ask(search, data, now) else noRequest(data, now, ready, paused)
            reading.join()
            val jobs = turn.jobs + boards.getOrNull()?.jobs.orEmpty()
            val boardsNote = boards.fold({ it.note }, { "Job boards failed: ${it.message ?: it.javaClass.simpleName}" })
            val note = listOfNotNull(turn.note, boardsNote).joinToString(" · ")
            Store.update(context) { d ->
                val (queued, due) = queue(turn.save(d), jobs, now, immediate = force)
                announce = due
                queued.copy(rounds = finish(queued.rounds, now, note))
            }
            TickResult(jobs, if (jobs.isNotEmpty()) Messages.headline(jobs) else turn.message)
        } catch (e: Exception) {
            val why = e.message ?: e.javaClass.simpleName
            Store.update(context) { d -> d.copy(rounds = finish(d.rounds, now, "Stopped by an error: $why")) }
            TickResult(emptyList(), "The check stopped: $why")
        }
        Scheduler.reschedule(context)
        return result to announce
    }

    /** Ask LinkedIn for the next page of [search]'s results, and work out what to save. */
    private fun ask(search: Search, data: AppData, now: Long): Turn {
        val startedAt = if (search.cursor == 0) now else search.checkStartedAt
        val page = search.cursor / LinkedIn.PAGE_SIZE + 1
        val where = "LinkedIn, ${search.label}"
        return try {
            val listed = LinkedIn.fetchPage(search, LinkedIn.windowSeconds(search.lastCheck, now), search.cursor)
            val jobs = listed.orEmpty().distinctBy { it.id }
            // LinkedIn sometimes answers a page past the end with the last page again.
            val repeated = search.cursor > 0 && jobs.isNotEmpty() && jobs.all { it.id in search.lastPageIds }
            val seen = data.seen.toHashSet()
            val fresh = if (repeated) emptyList()
            else jobs.filter { it.id !in seen }.map { it.copy(search = search.label, foundAt = now, isNew = true) }
            val done = listed == null || listed.size < LinkedIn.PAGE_SIZE || repeated ||
                search.cursor + listed.size >= LinkedIn.MAX_PAGES * LinkedIn.PAGE_SIZE
            val total = search.checkNew + fresh.size
            val status = if (done) "Checked ${timeText(now)}: $total new"
            else "Checking ${timeText(now)}: $total new so far, more pages to read"
            Turn(
                jobs = fresh,
                note = "$where: " + (if (page > 1) "page $page, " else "") + (if (done) "$total new" else "$total new so far, more to read"),
                message = "No new jobs for ${search.label}.",
                save = { d ->
                    val known = d.seen.toHashSet()
                    val added = fresh.filter { it.id !in known }
                    d.copy(
                        searches = d.searches.map { s ->
                            when {
                                s.label != search.label -> s
                                // The next check looks back to when this one began, so nothing posted meanwhile is missed.
                                done -> s.copy(lastCheck = startedAt, cursor = 0, checkStartedAt = 0L, checkNew = 0,
                                    lastPageIds = emptyList(), found = s.found + added.size, status = status)
                                else -> s.copy(cursor = s.cursor + listed!!.size, checkStartedAt = startedAt, checkNew = total,
                                    lastPageIds = jobs.map { it.id }, found = s.found + added.size, status = status)
                            }
                        },
                        seen = d.seen + added.map { it.id },
                        jobs = added + d.jobs,
                        limited = false, // LinkedIn answered again
                        linkedInPausedUntil = 0L,
                    )
                },
            )
        } catch (e: SlowDownException) {
            // Leave LinkedIn alone for a while. The window and page stay where they were, so nothing is missed.
            val until = now + INTERVAL_MS
            Turn(emptyList(), "$where: asked to slow down, LinkedIn left alone until ${timeText(until)}",
                "LinkedIn asked to slow down. Trying again at ${timeText(until)}; nothing posted meanwhile is missed.",
                save = { d ->
                    d.copy(limited = true, linkedInPausedUntil = until,
                        searches = d.searches.map { if (it.label == search.label) it.copy(status = WAITING) else it })
                })
        } catch (e: Exception) {
            // Start this search's check over at its next turn; its window is kept, so nothing is missed.
            val why = e.message ?: e.javaClass.simpleName
            Turn(emptyList(), "$where: error: $why", "Couldn't check ${search.label}: $why",
                save = { d ->
                    d.copy(searches = d.searches.map { s ->
                        if (s.label != search.label) s
                        else s.copy(status = "Error ${timeText(now)}: $why", cursor = 0, checkStartedAt = 0L, checkNew = 0,
                            lastPageIds = emptyList())
                    })
                })
        }
    }

    /** Why this tick doesn't ask LinkedIn. */
    private fun noRequest(data: AppData, now: Long, ready: Long, paused: Boolean): Turn = when {
        data.searches.isEmpty() -> Turn(emptyList(), "LinkedIn: no searches", "Add a search to check LinkedIn.")
        now >= ready - TOLERANCE_MS -> Turn(emptyList(), "LinkedIn: no search due",
            "Every search was checked in the last ${Scheduler.INTERVAL_MINUTES} minutes.")
        paused -> Turn(emptyList(), "LinkedIn: left alone until ${timeText(ready)} after it asked to slow down",
            "LinkedIn asked to slow down. Trying again at ${timeText(ready)}.")
        else -> Turn(emptyList(), "LinkedIn: next search at ${timeText(ready)}, one every ${Scheduler.SPACING_MINUTES} minutes",
            "LinkedIn gets one search every ${Scheduler.SPACING_MINUTES} minutes. Next one at ${timeText(ready)}.")
    }

    /**
     * Queue [jobs] for an alert. All that's queued goes out together once 10 minutes have passed
     * since the last alert, or straight away when [immediate] (a check you asked for). Returns the
     * new state and the jobs to announce now.
     */
    private fun queue(data: AppData, jobs: List<Job>, now: Long, immediate: Boolean): Pair<AppData, List<Job>> {
        val queued = (data.alertQueue + jobs.map { it.id }).distinct()
        if (queued.isEmpty()) return data to emptyList()
        if (!immediate && now - minOf(data.lastAlertAt, now) < INTERVAL_MS - TOLERANCE_MS) {
            return data.copy(alertQueue = queued) to emptyList()
        }
        val ids = queued.toHashSet()
        val due = data.jobs.filter { it.id in ids } // jobs deleted meanwhile drop out
        return data.copy(alertQueue = emptyList(), lastAlertAt = if (due.isEmpty()) data.lastAlertAt else now) to due
    }

    /** Announce [jobs] found outside a tick (the boards just turned on), through the same queue. */
    fun announce(context: Context, jobs: List<Job>, immediate: Boolean) {
        var due = emptyList<Job>()
        Store.update(context) { d ->
            val (saved, now) = queue(d, jobs, System.currentTimeMillis(), immediate)
            due = now
            saved
        }
        if (due.isNotEmpty()) runCatching { Alerts.deliver(context, due) }
    }

    private fun finish(rounds: List<Round>, startedAt: Long, note: String) =
        rounds.map { if (it.startedAt == startedAt) it.copy(finishedAt = System.currentTimeMillis(), result = note) else it }
}
