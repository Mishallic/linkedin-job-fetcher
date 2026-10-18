package com.jobwatch.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Collections

/** The check routine against a fake LinkedIn, including what happens when LinkedIn says "slow down". */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CheckerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val sample = javaClass.getResource("/sample_page.html")!!.readText()
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf()) // a round reads side by side

    @Before
    fun setUp() {
        Checker.pauseBetweenSearchesMs = 0L..0L
    }

    @After
    fun tearDown() {
        Http.fake = null
        Checker.pauseBetweenSearchesMs = 2_000L..4_000L
    }

    /** A fake LinkedIn: [status], and [page] as the first results page (later pages empty). */
    private fun linkedInAnswers(status: Int, page: String = sample) {
        Http.fake = { url ->
            requests += url
            if (status != 200) status to "" else 200 to (if ("start=0" in url) page else "")
        }
    }

    @Test
    fun slowDownCostsOneRequestAndTheNextCheckTriesAgain() {
        val start = System.currentTimeMillis()
        val addedAt = Store.load(context).searches.map { it.lastCheck }

        linkedInAnswers(429)
        assertEquals(emptyList<Job>(), Checker.run(context, force = true))
        assertEquals("stops at the first refusal instead of trying all 5 searches", 1, requests.size)
        var data = Store.load(context)
        assertTrue(data.limited)
        assertTrue(data.searches.all { it.status.startsWith("Waiting: LinkedIn asked to slow down") })
        assertEquals("windows untouched, so nothing is missed", addedAt, data.searches.map { it.lastCheck })

        // No extra waiting: a check you ask for goes straight to LinkedIn again.
        Checker.run(context, force = true)
        assertEquals(2, requests.size)

        // The next background check (10 minutes later) is not held back either.
        Store.update(context) { it.copy(lastRun = it.lastRun - 10 * 60_000) }
        Checker.run(context)
        assertEquals(3, requests.size)

        // LinkedIn answers again: jobs come in, including those posted while it was refusing.
        linkedInAnswers(200)
        val jobs = Checker.run(context, force = true)!!
        assertEquals(10, jobs.size)
        data = Store.load(context)
        assertFalse(data.limited)
        assertTrue(data.searches.all { it.status.startsWith("Checked") })
        assertTrue(data.searches.all { it.lastCheck >= start })
    }

    @Test
    fun aPageWithFewerThanTenJobsIsTheLastOne() {
        val threeJobs = sample.split("<li>").take(4).joinToString("<li>") // the page's first 3 jobs
        assertEquals(3, LinkedIn.parse(threeJobs).size)
        linkedInAnswers(200, threeJobs)
        Checker.run(context, force = true)
        assertEquals("one request per search, no extra request for an empty page", 5, requests.size)

        requests.clear()
        Store.update(context) { it.copy(lastRun = 0L) }
        linkedInAnswers(200) // a full page of 10: there may be more, so the next page is fetched
        Checker.run(context, force = true)
        assertEquals(10, requests.size)
    }

    @Test
    fun deletedJobsDontComeBackAndUndoPutsThemWhereTheyWere() {
        linkedInAnswers(200)
        Checker.run(context, force = true)
        val before = Store.load(context).jobs.map { it.id }
        assertEquals(10, before.size)
        val doomed = setOf(before[0], before[4])

        val removed = Store.deleteJobs(context, doomed)
        assertEquals(before - doomed, Store.load(context).jobs.map { it.id })
        Store.restoreJobs(context, removed)
        assertEquals("undo puts them back in place", before, Store.load(context).jobs.map { it.id })

        Store.deleteJobs(context, doomed)
        assertEquals("a later check doesn't bring them back", emptyList<Job>(), Checker.run(context, force = true))
        assertEquals(before - doomed, Store.load(context).jobs.map { it.id })
    }

    @Test
    fun everyRoundIsRecordedWithWhatStartedItAndHowItEnded() {
        linkedInAnswers(200)
        Checker.round(context, force = true, trigger = Checker.TRIGGER_REFRESH)
        var rounds = Store.load(context).rounds
        assertEquals(1, rounds.size)
        assertEquals(Checker.TRIGGER_REFRESH, rounds[0].trigger)
        assertTrue("finished", rounds[0].finishedAt >= rounds[0].startedAt)
        assertEquals("10 new on LinkedIn", rounds[0].result)

        Checker.round(context) // a background round right after is skipped, and the history says so
        rounds = Store.load(context).rounds
        assertEquals(2, rounds.size)
        assertEquals(Checker.TRIGGER_ALARM, rounds[0].trigger)
        assertTrue(rounds[0].result, rounds[0].result.startsWith("Skipped: the last check was"))

        linkedInAnswers(429)
        Checker.round(context, force = true)
        assertEquals("LinkedIn asked to slow down at the first search", Store.load(context).rounds[0].result)

        repeat(45) { Store.beginRound(context, "test") }
        assertEquals("only the last ${Store.KEEP_ROUNDS} are kept", Store.KEEP_ROUNDS, Store.load(context).rounds.size)
    }

    @Test
    fun backgroundCheckRightAfterAnotherIsSkipped() {
        linkedInAnswers(200)
        Checker.run(context)
        val sent = requests.size
        assertNull(Checker.run(context))
        assertEquals(sent, requests.size)
    }
}
