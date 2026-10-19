package com.jobwatch.app

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.URLDecoder

/**
 * End-to-end delivery time on a simulated clock: a fake LinkedIn where jobs are posted at set
 * minutes and show in its search after a set delay, the alarm firing whenever it's due, and three
 * hours of checks in a moment. It measures what the app adds between LinkedIn showing a job and the
 * phone's notification, and holds the app to its budget: found at the job's search's next turn
 * (within one interval), and announced by the check that found it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DeliveryTimeTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val minute = 60_000L
    private val start = 1_790_000_000_000L // a fixed moment, so every run is the same
    private var now = start

    /** A job on the fake LinkedIn: posted at [postedAt], shown in its search from [shownAt]. */
    private class Posting(val id: Long, val search: String, val postedAt: Long, val shownAt: Long)
    private val postings = mutableListOf<Posting>()

    /** What became of a posting: the app's job for it, if a check found it. */
    private inner class Outcome(val posting: Posting, val job: Job?) {
        val shownToFound: Long? = job?.let { (it.foundAt - posting.shownAt) / minute }
        val foundToNotified: Long? = job?.takeIf { it.notifiedAt > 0 }?.let { (it.notifiedAt - it.foundAt) / minute }
        val postedToNotified: Long? = job?.takeIf { it.notifiedAt > 0 }?.let { (it.notifiedAt - posting.postedAt) / minute }
    }

    @Before
    fun setUp() {
        Clock.now = { now }
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Store.update(context) { it.copy(remoteOn = false, sitesOn = false) } // LinkedIn alone
        Http.fake = { url -> 200 to page(url) }
    }

    @After
    fun tearDown() {
        Clock.now = { System.currentTimeMillis() }
        Http.fake = null
    }

    /** The fake LinkedIn's answer: the search's jobs it shows by now, posted within the window asked for, newest first. */
    private fun page(url: String): String {
        val params = url.substringAfter("?").split("&")
            .associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }
        val search = "${params["keywords"]} @ ${params["location"]}"
        val window = params.getValue("f_TPR").removePrefix("r").toLong() * 1000
        return postings.filter { it.search == search && it.shownAt <= now && it.postedAt >= now - window }
            .sortedByDescending { it.postedAt }
            .drop(params.getValue("start").toInt()).take(LinkedIn.PAGE_SIZE)
            .joinToString("") { card(it) }
    }

    private fun card(p: Posting): String {
        val ago = when (val minutes = (now - p.postedAt) / minute) {
            0L -> "Just now"
            1L -> "1 minute ago"
            else -> "$minutes minutes ago"
        }
        return """<li><div data-entity-urn="urn:li:jobPosting:${p.id}">
            <h3 class="base-search-card__title">Job ${p.id}</h3>
            <h4 class="base-search-card__subtitle">Company ${p.id}</h4>
            <span class="job-search-card__location">${p.search.substringAfter(" @ ")}</span>
            <time class="job-search-card__listdate--new" datetime="2026-09-21">$ago</time></div></li>"""
    }

    /**
     * Each of the 5 searches gets a job every 7 minutes for [hours] hours, starting at different
     * minutes, so jobs land at every point between a search's turns. LinkedIn shows each [lag]
     * minutes after it's posted.
     */
    private fun postJobs(hours: Long, lag: Long) {
        var id = 4_000_000_000L
        for ((i, search) in Store.load(context).searches.map { it.label }.withIndex()) {
            var at = start + (3 * i + 1) * minute
            while (at < start + hours * 60 * minute) {
                postings += Posting(id++, search, at, at + lag * minute)
                at += 7 * minute
            }
        }
    }

    /** Let [minutes] of simulated time pass, the alarm firing whenever it's due, as on a phone that stays awake. */
    private fun run(minutes: Long): List<Outcome> {
        Checker.tick(context, Checker.TRIGGER_OPENED) // opening the app starts the chain
        val end = now + minutes * minute
        while (now < end) {
            now += 5_000
            val due = Scheduler.nextCheckAt(context)
            if (due in 1..now) Checker.tick(context, Checker.TRIGGER_ALARM, due = due)
        }
        val jobs = Store.load(context).jobs.associateBy { it.id }
        return postings.map { Outcome(it, jobs[it.id.toString()]) }
    }

    /** The measured times, as the investigation reports them. */
    private fun report(label: String, outcomes: List<Outcome>) {
        fun stats(values: List<Long>) = if (values.isEmpty()) "none"
        else "median ${Delivery.percentile(values, 0.5)}, 90% within ${Delivery.percentile(values, 0.9)}, max ${values.max()} min"
        println("$label: ${outcomes.size} jobs, ${outcomes.count { it.job == null }} missed | " +
            "shown to found: ${stats(outcomes.mapNotNull { it.shownToFound })} | " +
            "found to notified: ${stats(outcomes.mapNotNull { it.foundToNotified })} | " +
            "posted to notified: ${stats(outcomes.mapNotNull { it.postedToNotified })}")
    }

    @Test
    fun aJobIsFoundAtItsSearchsNextTurnAndAnnouncedByThatCheck() {
        postJobs(hours = 3, lag = 0)
        val outcomes = run(3 * 60 + 30)
        report("LinkedIn shows jobs at once", outcomes)
        assertEquals("none missed", 0, outcomes.count { it.job == null })
        assertTrue("each found within one turn (an interval) of LinkedIn showing it",
            outcomes.all { it.shownToFound!! <= Scheduler.INTERVAL_MINUTES })
        assertTrue("each announced by the check that found it", outcomes.all { it.foundToNotified == 0L })
    }

    /**
     * Each check looks back at least [LinkedIn.MIN_WINDOW_MINUTES], so a job LinkedIn shows late is
     * still caught as long as its delay and the wait for its search's turn fit in that window: up to
     * the window less one interval (a minute's margin for alarms that fire a little late).
     */
    @Test
    fun jobsLinkedInShowsLateAreNotMissedWhileTheWindowCoversThem() {
        val lag = LinkedIn.MIN_WINDOW_MINUTES - Scheduler.INTERVAL_MINUTES - 1
        postJobs(hours = 3, lag = lag)
        val outcomes = run(3 * 60 + 30)
        report("LinkedIn shows jobs $lag minutes late", outcomes)
        assertEquals("none missed", 0, outcomes.count { it.job == null })
    }
}
