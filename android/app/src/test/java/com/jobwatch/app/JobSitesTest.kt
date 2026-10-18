package com.jobwatch.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.URLDecoder
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections

/** ReliefWeb's feed (saved for real) and Jooble's API (no key to save a real answer; see jooble.json), on fakes. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class JobSitesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val posts: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    private fun sample(name: String) = javaClass.getResource("/remote/$name")!!.readText()
    private fun data() = Store.load(context)
    private val emptyFeed = "<rss><channel></channel></rss>"

    /** The saved ReliefWeb feed, its jobs dated an hour ago so they count as new whenever the test runs. */
    private fun reliefWeb(): String {
        val anHourAgo = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC).minusHours(1))
        return sample("reliefweb.xml").replace(Regex("<pubDate>[^<]*</pubDate>"), "<pubDate>$anHourAgo</pubDate>")
    }

    /** Jooble's answer in the shape its API clients document, its jobs updated an hour ago. */
    private fun jooble() = sample("jooble.json").replace("UPDATED", LocalDateTime.now(ZoneOffset.UTC).minusHours(1).toString())

    private fun searches(vararg searches: Pair<String, String>) =
        Store.update(context) { d -> d.copy(searches = searches.map { (keywords, place) -> Search(keywords, place) }) }

    @Before
    fun setUp() {
        Store.update(context) { it.copy(remoteOn = false) } // the boards have their own tests
    }

    @After
    fun tearDown() {
        Http.fake = null
        Http.fakePost = null
    }

    @Test
    fun placesKnowTheirCountryAndCities() {
        assertEquals("Jordan", Places.countryOf("Amman, Jordan"))
        assertEquals("Jordan", Places.countryOf("Amman"))
        assertEquals("United Arab Emirates", Places.countryOf("Dubai"))
        assertEquals("Saudi Arabia", Places.countryOf("saudi arabia"))
        assertEquals("a place it doesn't know is taken as a country", "Germany", Places.countryOf("berlin, germany"))
        assertNull(Places.countryOf(""))
        assertNull(Places.countryOf("Remote"))
        assertTrue(Places.inPlace("Riyadh", "Saudi Arabia"))
        assertTrue(Places.inPlace("Abu Dhabi, UAE", "United Arab Emirates"))
        assertFalse(Places.inPlace("Dubai", "Saudi Arabia"))
        assertTrue("a search without a place takes jobs anywhere", Places.inPlace("London", ""))
    }

    @Test
    fun reliefWebIsAskedOnlyForYourPositionsInYourCountries() {
        val url = JobSites.reliefWebUrl(data().searches)!! // the 5 starting searches
        assertEquals("country.exact:(\"Jordan\" OR \"United Arab Emirates\" OR \"Saudi Arabia\") AND " +
            "title:((software AND engineer) OR (data AND analyst))", URLDecoder.decode(url.substringAfter("search="), "UTF-8"))
        assertNull("no place, no ReliefWeb", JobSites.reliefWebUrl(listOf(Search("data analyst", ""))))
    }

    @Test
    fun readsReliefWebsRealFeed() {
        val listings = JobSites.parseReliefWeb(sample("reliefweb.xml"))
        assertEquals(3, listings.size)
        assertTrue(listings.all { it.id.matches(Regex("reliefweb:\\d+")) && it.url.startsWith("https://reliefweb.int/job/") })
        assertTrue(listings.all { it.source == "ReliefWeb" && "Jordan" in it.where && it.company.isNotBlank() && it.postedAt > 0 })
        assertEquals("Senior Research Officer - MSNA - Global, Based in Amman", listings[0].title)
        assertEquals("IMPACT Initiatives", listings[0].company)
    }

    @Test
    fun reliefWebJobsAreFiledUnderTheSearchTheyMatchAndReadHourly() {
        searches("research officer" to "Amman, Jordan", "finance officer" to "Jordan", "software engineer" to "Dubai")
        Http.fake = { url -> requests += url; 200 to (if ("reliefweb.int" in url) reliefWeb() else "") }

        val found = JobSites.check(context).jobs
        assertEquals(mapOf(
            "Senior Research Officer - MSNA - Global, Based in Amman" to "research officer @ Amman, Jordan",
            "Finance Officer-National position" to "finance officer @ Jordan",
            "Senior Research Monitoring Evaluation and Learning Officer" to "research officer @ Amman, Jordan",
        ), found.associate { it.title to it.search })
        assertTrue(found.all { it.source == "ReliefWeb" && it.isNew })
        assertEquals(1, requests.size)
        val status = data().siteStatus.getValue("ReliefWeb")
        assertTrue(status, "3 new of 3 found" in status)

        assertEquals("read at most hourly", emptyList<Job>(), JobSites.check(context).jobs)
        assertEquals(1, requests.size)
        minutesPass(context, 60)
        assertEquals("an hour on it's read again, and nothing is announced twice", emptyList<Job>(), JobSites.check(context).jobs)
        assertEquals(2, requests.size)
    }

    @Test
    fun joobleNeedsAKeyAndIsAskedForOneSearchPerCheck() {
        searches("software engineer" to "United Arab Emirates", "data analyst" to "Saudi Arabia")
        Http.fake = { url -> requests += url; 200 to emptyFeed } // ReliefWeb has nothing new
        Http.fakePost = { url, body -> posts += url to body; 200 to jooble() }
        val onLinkedIn = Job("4100000001", "Senior Software Engineer", "Acme", "Dubai", "", "https://www.linkedin.com/jobs/view/4100000001/")
        Store.update(context) { it.copy(jobs = listOf(onLinkedIn)) }

        JobSites.check(context)
        assertEquals("no key, no Jooble", 0, posts.size)

        Store.update(context) { it.copy(joobleKey = "KEY-123") }
        val found = JobSites.check(context).jobs
        assertEquals(1, posts.size)
        assertEquals("https://jooble.org/api/KEY-123", posts[0].first)
        val body = JSONObject(posts[0].second)
        assertEquals("software engineer", body.getString("keywords"))
        assertEquals("United Arab Emirates", body.getString("location"))
        // Acme's job is on LinkedIn already, Globex's is in Riyadh, and Hooli's isn't a software engineer's.
        assertEquals(listOf("Software Engineer (Backend) @ Initech, Abu Dhabi"), found.map { "${it.title} @ ${it.company}, ${it.location}" })
        assertEquals("software engineer @ United Arab Emirates", found.single().search)
        assertEquals("Jooble", found.single().source)

        JobSites.check(context)
        assertEquals("the next check asks for the other search", 2, posts.size)
        assertEquals("data analyst", JSONObject(posts[1].second).getString("keywords"))
        JobSites.check(context)
        assertEquals("both were asked within the hour", 2, posts.size)
    }

    @Test
    fun aKeyJoobleRefusesIsReported() {
        Store.update(context) { it.copy(joobleKey = "WRONG") }
        Http.fake = { _ -> 200 to emptyFeed }
        Http.fakePost = { _, _ -> 403 to "" }
        assertEquals(emptyList<Job>(), JobSites.check(context).jobs)
        val status = data().siteStatus.getValue("Jooble")
        assertTrue(status, "didn't accept the API key" in status)
    }

    @Test
    fun turnedOffOrWithChecksOffNothingIsRead() {
        Store.update(context) { it.copy(sitesOn = false, joobleKey = "KEY") }
        Http.fake = { url -> fail("no request while the job sites are off: $url"); 200 to "" }
        Http.fakePost = { url, _ -> fail("no request while the job sites are off: $url"); 200 to "" }
        assertEquals(JobSites.Read.NONE, JobSites.check(context))
        assertNull(JobSites.nextDueAt(data()))

        Store.update(context) { it.copy(sitesOn = true, checksOn = false) }
        assertEquals("nor while background checks are off", JobSites.Read.NONE, JobSites.check(context))
    }

    @Test
    fun theSitesRideAlongOnTheChecks() {
        Http.fake = { url -> requests += url; 200 to "" }
        Checker.tick(context)
        assertTrue("a check reads ReliefWeb when it's due", requests.any { "reliefweb.int" in it })
        val data = data()
        assertEquals("and it's due again an hour later", data.siteChecks.getValue("ReliefWeb") + 60 * 60_000, JobSites.nextDueAt(data))
    }
}
