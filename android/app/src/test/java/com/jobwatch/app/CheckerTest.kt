package com.jobwatch.app

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Checks against a fake LinkedIn: one request every 2 minutes whatever starts it, each search's turn
 * every 10 minutes, busy searches one page per turn, slow-downs, the history and batched alerts.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CheckerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val sample = javaClass.getResource("/sample_page.html")!!.readText()
    private val shortPage = sample.split("<li>").take(4).joinToString("<li>") // 3 jobs: a check done in one request
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private fun linkedIn() = requests.filter { "linkedin.com" in it }
    private fun data() = Store.load(context)
    private fun lastAsked() = data().searches.maxBy { it.lastTry }.label
    private fun minutesPass(minutes: Long) = minutesPass(context, minutes)

    @Before
    fun setUp() {
        Store.update(context) { it.copy(remoteOn = false, sitesOn = false) } // the boards and sites have their own tests
    }

    @After
    fun tearDown() {
        Http.fake = null
    }

    /** A fake LinkedIn: [status], and [page] as the first results page (later pages empty). */
    private fun linkedInAnswers(status: Int, page: String = sample) {
        Http.fake = { url ->
            requests += url
            if (status != 200) status to "" else 200 to (if ("start=0" in url) page else "")
        }
    }

    /** The sample page with its job ids changed, so each answer brings different jobs. */
    private fun differentJobs(tag: Any) = sample.replace("urn:li:jobPosting:", "urn:li:jobPosting:9$tag")

    @Test
    fun linkedInGetsOneRequestEveryTwoMinutesAndEachSearchItsTurnEveryTen() {
        linkedInAnswers(200, shortPage)
        val labels = data().searches.map { it.label } // the 5 starting searches
        val asked = mutableListOf<String>()
        repeat(5) {
            val before = linkedIn().size
            Checker.tick(context)
            assertEquals("one request per check", before + 1, linkedIn().size)
            asked += lastAsked()
            Checker.tick(context)
            Checker.tick(context, Checker.TRIGGER_REFRESH, force = true)
            assertEquals("none again within 2 minutes, not even when you tap refresh", before + 1, linkedIn().size)
            minutesPass(2)
        }
        assertEquals("each search in turn", labels, asked)

        // Ten minutes after the first search was asked, it's its turn again.
        Checker.tick(context)
        assertEquals(6, linkedIn().size)
        assertEquals(labels[0], lastAsked())
    }

    @Test
    fun withFewerSearchesTheChecksWaitForEachTurn() {
        Store.update(context) { d -> d.copy(searches = d.searches.take(2)) }
        linkedInAnswers(200, shortPage)
        Checker.tick(context)
        minutesPass(2)
        Checker.tick(context)
        minutesPass(2)
        Checker.tick(context)
        assertEquals("both searches were asked in the last 10 minutes: no request", 2, linkedIn().size)

        val now = System.currentTimeMillis()
        val first = data().searches.minBy { it.lastTry }
        assertEquals("the next check is the first search's turn, 10 minutes after it was asked",
            first.lastTry + 10 * 60_000, Checker.nextTickAt(context, data(), now))
    }

    @Test
    fun aSearchWithManyNewJobsReadsOnePagePerTurn() {
        Store.update(context) { d -> d.copy(searches = d.searches.take(1)) }
        Http.fake = { url ->
            requests += url
            val start = Regex("start=(\\d+)").find(url)!!.groupValues[1].toInt()
            200 to if (start < 20) differentJobs(start) else shortPage.replace("urn:li:jobPosting:", "urn:li:jobPosting:9$start")
        }

        Checker.tick(context)
        var search = data().searches.single()
        assertEquals("a full page: more to read", 10, search.cursor)
        assertTrue(search.status, search.status.startsWith("Checking"))
        val began = search.checkStartedAt

        minutesPass(2)
        Checker.tick(context)
        assertEquals(20, data().searches.single().cursor)

        minutesPass(2)
        Checker.tick(context)
        search = data().searches.single()
        assertEquals("a short page ends the check", 0, search.cursor)
        assertEquals(listOf("start=0", "start=10", "start=20"), linkedIn().map { Regex("start=\\d+").find(it)!!.value })
        assertEquals(23, search.found)
        assertTrue(search.status, search.status.startsWith("Checked") && "23 new" in search.status)
        assertEquals("the next check looks back to when this one began", began - 4 * 60_000, search.lastCheck)
    }

    @Test
    fun slowDownLeavesLinkedInAloneButATapCanTryAgainAfterTwoMinutes() {
        val windows = data().searches.map { it.lastCheck }
        linkedInAnswers(429)
        Checker.tick(context)
        assertEquals("one refused request", 1, linkedIn().size)
        assertTrue(data().limited)
        assertEquals("windows untouched, so nothing is missed", windows, data().searches.map { it.lastCheck })

        minutesPass(2)
        Checker.tick(context)
        assertEquals("background checks leave LinkedIn alone for 10 minutes", 1, linkedIn().size)
        assertEquals("and the alarm is set for the end of the pause", data().linkedInPausedUntil,
            Checker.nextTickAt(context, data(), System.currentTimeMillis()))
        Checker.tick(context, Checker.TRIGGER_REFRESH, force = true)
        assertEquals("a check you ask for tries again", 2, linkedIn().size)

        linkedInAnswers(200)
        minutesPass(10)
        Checker.tick(context)
        assertFalse("LinkedIn answered again", data().limited)
        assertEquals(10, data().jobs.size)
    }

    @Test
    fun deletedJobsDontComeBackAndUndoPutsThemWhereTheyWere() {
        linkedInAnswers(200)
        Checker.tick(context)
        val before = data().jobs.map { it.id }
        assertEquals(10, before.size)
        val doomed = setOf(before[0], before[4])

        val removed = Store.deleteJobs(context, doomed)
        assertEquals(before - doomed, data().jobs.map { it.id })
        Store.restoreJobs(context, removed)
        assertEquals("undo puts them back in place", before, data().jobs.map { it.id })

        Store.deleteJobs(context, doomed)
        minutesPass(2)
        assertEquals("a later check doesn't bring them back", emptyList<Job>(), Checker.tick(context).jobs)
        assertEquals(before - doomed, data().jobs.map { it.id })
    }

    @Test
    fun everyCheckIsRecordedWithWhatStartedItAndHowItEnded() {
        linkedInAnswers(200, shortPage)
        Checker.tick(context, Checker.TRIGGER_REFRESH, force = true)
        var rounds = data().rounds
        assertEquals(1, rounds.size)
        assertEquals(Checker.TRIGGER_REFRESH, rounds[0].trigger)
        assertTrue("finished", rounds[0].finishedAt >= rounds[0].startedAt)
        assertEquals("LinkedIn, software engineer @ Amman, Jordan: 3 new", rounds[0].result)

        Checker.tick(context) // straight after: LinkedIn waits its 2 minutes, and the history says so
        rounds = data().rounds
        assertEquals(2, rounds.size)
        assertEquals(Checker.TRIGGER_ALARM, rounds[0].trigger)
        assertTrue(rounds[0].result, rounds[0].result.startsWith("LinkedIn: next search at"))

        linkedInAnswers(429)
        minutesPass(2)
        Checker.tick(context)
        assertTrue(data().rounds[0].result, "asked to slow down" in data().rounds[0].result)

        repeat(Store.KEEP_ROUNDS) { Checker.tick(context) }
        assertEquals("only the last ${Store.KEEP_ROUNDS} are kept", Store.KEEP_ROUNDS, data().rounds.size)
    }

    @Test
    fun alertsGoOutAtMostEveryTenMinutesAllTogether() {
        val answers = AtomicInteger()
        Http.fake = { url -> requests += url; 200 to differentJobs(answers.incrementAndGet()) }
        fun queued() = data().alertQueue.size

        Checker.tick(context)
        assertTrue("the first new jobs go out at once", data().lastAlertAt > 0)
        assertEquals(0, queued())

        minutesPass(2)
        Checker.tick(context)
        assertEquals("later ones wait", 10, queued())
        minutesPass(2)
        Checker.tick(context)
        assertEquals(20, queued())
        minutesPass(6)
        Checker.tick(context)
        assertEquals("10 minutes after the last alert, everything waiting goes out together", 0, queued())

        minutesPass(2)
        Checker.tick(context)
        assertEquals(10, queued())
        minutesPass(2)
        Checker.tick(context, Checker.TRIGGER_REFRESH, force = true)
        assertEquals("a check you ask for announces at once", 0, queued())
    }

    @Test
    fun eachJobsPostedFoundAndNotifiedTimesAreRecorded() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        linkedInAnswers(200, shortPage)
        val due = System.currentTimeMillis() - 90_000
        Checker.tick(context, Checker.TRIGGER_ALARM, due = due)
        val jobs = data().jobs
        assertEquals(3, jobs.size)
        assertTrue("posted, counted back from LinkedIn's \"hours ago\"", jobs.all { it.postedAt in 1 until it.foundAt })
        assertTrue("the phone was told, and when", jobs.all { it.notifiedAt >= it.foundAt })
        assertEquals("the check notes when its alarm was due", due, data().rounds[0].dueAt)
    }

    @Test
    fun atMostFiveSearchesAndANewOneGoesNext() {
        assertEquals(5, data().searches.size)
        assertFalse("five is the most", Store.addSearch(context, Search("python developer", "Dubai")))

        linkedInAnswers(200)
        Checker.tick(context)
        Store.update(context) { d -> d.copy(searches = d.searches.dropLast(1)) }
        assertTrue(Store.addSearch(context, Search("python developer", "Dubai")))
        assertFalse("not twice", Store.addSearch(context, Search("python developer", "Dubai")))

        minutesPass(2)
        Checker.tick(context, Checker.TRIGGER_ADDED, force = true, prefer = "python developer @ Dubai")
        assertEquals("python developer @ Dubai", lastAsked())
    }
}
