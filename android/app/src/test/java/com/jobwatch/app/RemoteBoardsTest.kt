package com.jobwatch.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections

/** The remote job boards: reading their real feeds, choosing which jobs to keep, and a full check. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RemoteBoardsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf()) // boards are read side by side
    private val readsNetwork = RemoteBoards.onMobileData

    private fun sample(name: String) = javaClass.getResource("/remote/$name")!!.readText()

    /** Move every board's last read [minutes] into the past. */
    private fun minutesPass(minutes: Long) = Store.update(context) { data ->
        data.copy(boardChecks = data.boardChecks.mapValues { it.value - minutes * 60_000 },
            remoteLastCheck = data.remoteLastCheck - minutes * 60_000)
    }

    @Before
    fun setUp() {
        RemoteBoards.onMobileData = { false }
    }

    @After
    fun tearDown() {
        Http.fake = null
        RemoteBoards.onMobileData = readsNetwork
    }

    @Test
    fun readsEachBoardsRealFeed() {
        val boards = mapOf(
            "Remotive" to RemoteBoards.parseRemotive(sample("remotive.json")),
            "Remote OK" to RemoteBoards.parseRemoteOk(sample("remoteok.json")),
            "Himalayas" to RemoteBoards.parseHimalayas(sample("himalayas.json")),
            "Jobicy" to RemoteBoards.parseJobicy(sample("jobicy.json")),
            "We Work Remotely" to RemoteBoards.parseWeWorkRemotely(sample("weworkremotely.xml")),
        )
        for ((board, listings) in boards) {
            assertTrue("$board has listings", listings.isNotEmpty())
            assertTrue("$board: title, link and source on every listing",
                listings.all { it.title.isNotBlank() && it.url.startsWith("http") && it.source == board })
            assertTrue("$board: dates are read", listings.count { it.postedAt > 0 } >= listings.size * 9 / 10)
            assertEquals("$board: ids are unique", listings.size, listings.map { it.id }.toSet().size)
        }
        val wwr = boards.getValue("We Work Remotely")
        assertTrue("\"Company: Role\" titles are split", wwr.all { it.company.isNotBlank() && ": " !in it.title })
    }

    @Test
    fun matchingRules() {
        assertTrue(RemoteBoards.titleMatches("Senior Data Analyst (Remote)", "data analyst"))
        assertTrue(RemoteBoards.titleMatches("Staff Software Engineer, Infrastructure", "software engineer"))
        assertFalse(RemoteBoards.titleMatches("Data Analytics Engineer", "data analyst"))

        val places = RemoteBoards.placesOf(listOf(Search("a", "Amman, Jordan"), Search("b", "United Arab Emirates"), Search("c", "")))
        assertEquals(listOf("amman", "jordan", "united arab emirates", "uae"), places)
        assertTrue(RemoteBoards.isOpenTo("Anywhere in the World", places))
        assertTrue(RemoteBoards.isOpenTo("EMEA", places))
        assertTrue(RemoteBoards.isOpenTo("UAE, Saudi Arabia", places))
        assertFalse(RemoteBoards.isOpenTo("USA Only", places))
        assertFalse(RemoteBoards.isOpenTo("", places))

        assertEquals(1_792_145_168_000L, RemoteBoards.parseTime("1792145168"))
        assertEquals(RemoteBoards.parseTime("2026-10-16T07:31:07Z"), RemoteBoards.parseTime("Fri, 16 Oct 2026 07:31:07 +0000"))
        assertEquals(RemoteBoards.parseTime("2026-10-11T20:17:27+00:00"), RemoteBoards.parseTime("2026-10-11T20:17:27"))
        assertEquals(0L, RemoteBoards.parseTime("soon"))
    }

    @Test
    fun checkFindsYourRemoteJobsOnceAndCreditsTheBoard() {
        val now = Instant.now()
        val anHourAgo = now.minusSeconds(3600).toString().removeSuffix("Z") // Remotive's dates have no offset
        val twoHoursAgo = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.minusSeconds(7200).atZone(ZoneOffset.UTC))
        val remotive = """{"jobs": [
            {"id": 1, "title": "Senior Software Engineer", "company_name": "Initech", "candidate_required_location": "Worldwide",
             "publication_date": "$anHourAgo", "url": "https://remotive.com/remote-jobs/1"},
            {"id": 2, "title": "Software Engineer", "company_name": "Globex", "candidate_required_location": "USA Only",
             "publication_date": "$anHourAgo", "url": "https://remotive.com/remote-jobs/2"},
            {"id": 3, "title": "Marketing Manager", "company_name": "Hooli", "candidate_required_location": "Worldwide",
             "publication_date": "$anHourAgo", "url": "https://remotive.com/remote-jobs/3"}]}"""
        val wwr = """<rss><channel>
            <item><title>Initech: Senior Software Engineer</title><region>Anywhere in the World</region>
              <pubDate>$twoHoursAgo</pubDate><link>https://weworkremotely.com/remote-jobs/initech</link></item>
            <item><title>Umbrella: Data Analyst</title><region>EMEA</region>
              <pubDate>$twoHoursAgo</pubDate><link>https://weworkremotely.com/remote-jobs/umbrella</link></item>
            </channel></rss>"""
        Http.fake = { url ->
            requests += url
            when {
                "remotive.com" in url -> 200 to remotive
                "weworkremotely.com" in url -> 200 to wwr
                "remoteok.com" in url -> 200 to """[{"legal": "API terms"}]"""
                "jobicy.com" in url -> 200 to """{"jobs": []}"""
                else -> 503 to "" // Himalayas is down this time
            }
        }

        val found = RemoteBoards.check(context) // the starting searches: software engineer, data analyst
        // Globex is US-only, Hooli isn't one of your positions, and Initech's job is on two boards.
        assertEquals(setOf("Senior Software Engineer @ Initech via Remotive", "Data Analyst @ Umbrella via We Work Remotely"),
            found.map { "${it.title} @ ${it.company} via ${it.source}" }.toSet())
        assertTrue(found.all { it.search == RemoteBoards.LABEL && it.isNew && it.url.startsWith("https://") })
        assertEquals("Remotive asks for few requests: one per check", 1, requests.count { "remotive.com" in it })

        val data = Store.load(context)
        assertEquals(2, data.jobs.count { it.search == RemoteBoards.LABEL })
        assertTrue(data.remoteStatus, "2 new" in data.remoteStatus && "couldn't reach Himalayas" in data.remoteStatus)
        assertTrue(data.remoteLastCheck > 0)

        minutesPass(6 * 60) // every board is due again
        assertEquals("the same listings aren't announced twice", emptyList<Job>(), RemoteBoards.check(context))
        assertEquals(2, requests.count { "remotive.com" in it })
    }

    @Test
    fun eachBoardIsReadAsOftenAsItsTermsAllow() {
        Http.fake = { url -> requests += url; 200 to "" } // what they answer doesn't matter here
        fun boardsRead(): Set<String> {
            requests.clear()
            RemoteBoards.check(context)
            return requests.map { URI(it).host.removePrefix("www.") }.toSet()
        }
        val unlimited = setOf("weworkremotely.com", "himalayas.app", "remoteok.com")
        val all = unlimited + "jobicy.com" + "remotive.com"

        assertEquals("the first check reads every board", all, boardsRead())
        assertEquals("and none again straight away", emptySet<String>(), boardsRead())
        minutesPass(10)
        assertEquals("next round: the boards without a limit", unlimited, boardsRead())
        minutesPass(50)
        assertEquals("an hour on: Jobicy too", unlimited + "jobicy.com", boardsRead())
        minutesPass(5 * 60)
        assertEquals("six hours on: Remotive too", all, boardsRead())

        RemoteBoards.onMobileData = { true }
        minutesPass(10)
        assertEquals("on mobile data, not every round", emptySet<String>(), boardsRead())
        minutesPass(50)
        assertEquals("but hourly", unlimited + "jobicy.com", boardsRead())
    }

    @Test
    fun turnedOffSendsNoRequests() {
        Store.update(context) { it.copy(remoteOn = false) }
        Http.fake = { url -> fail("no request while remote boards are off: $url"); 200 to "" }
        assertEquals(emptyList<Job>(), RemoteBoards.check(context))

        Store.update(context) { it.copy(remoteOn = true, checksOn = false) }
        assertEquals("nor while background checks are off", emptyList<Job>(), RemoteBoards.check(context))
    }
}
