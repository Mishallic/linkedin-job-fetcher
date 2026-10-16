package com.jobwatch.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class CoreTest {
    private val sample = javaClass.getResource("/sample_page.html")!!.readText()

    private fun job(i: Int, search: String = "python developer @ Dubai", title: String = "Job $i") =
        Job("$i", title, "Acme & Co", "Dubai", "2026-10-15", "https://www.linkedin.com/jobs/view/$i/", search)

    @Test
    fun parsesLinkedInResultsPage() {
        val jobs = LinkedIn.parse(sample)
        assertEquals(10, jobs.size)
        val first = jobs.first()
        assertEquals("4479797853", first.id)
        assertEquals("Data Analyst", first.title)
        assertEquals("Radian Engineering Consulting Company", first.company)
        assertEquals("Riyadh, Saudi Arabia", first.location)
        assertEquals("2026-10-15", first.posted)
        assertEquals("https://www.linkedin.com/jobs/view/4479797853/", first.url)
        assertTrue(jobs.all { it.title.isNotBlank() && it.company.isNotBlank() })
        assertEquals(10, jobs.map { it.id }.toSet().size)
    }

    @Test
    fun emptyPageHasNoJobs() {
        assertTrue(LinkedIn.parse("").isEmpty())
        assertTrue(LinkedIn.parse("<li>nothing here</li>").isEmpty())
    }

    @Test
    fun decodesHtmlEntities() {
        assertEquals("R&D <Senior> \"Lead\" it's café", decodeEntities("R&amp;D &lt;Senior&gt; &quot;Lead&quot; it&#39;s caf&#xe9;"))
        assertEquals("&unknown;", decodeEntities("&unknown;"))
        assertEquals("A B", cleanText("  <b>A</b>\n\n  B "))
    }

    @Test
    fun windowCoversTheGapSinceTheLastCheck() {
        val now = 1_000_000_000_000L
        assertEquals(15 * 60L, LinkedIn.windowSeconds(lastCheck = now, now = now))                        // brand-new search
        assertEquals(15 * 60L, LinkedIn.windowSeconds(lastCheck = now - 10 * 60_000, now = now))          // on schedule
        assertEquals(55 * 60L, LinkedIn.windowSeconds(lastCheck = now - 50 * 60_000, now = now))          // delayed: gap + 5 min
        assertEquals(24 * 3600L, LinkedIn.windowSeconds(lastCheck = now - 3 * 24 * 3600_000L, now = now)) // phone was off: one day
    }

    @Test
    fun searchLabelsAndRequests() {
        val search = Search("software engineer", "Amman, Jordan")
        assertEquals("software engineer @ Amman, Jordan", search.label)
        assertEquals("Amman, Jordan", search.place)
        val url = LinkedIn.pageUrl(search, 900, 10)
        assertTrue(url, url.endsWith("?keywords=software+engineer&location=Amman%2C+Jordan&f_TPR=r900&sortBy=DD&start=10"))
        assertEquals("python developer", Search("python developer", "").label)
        assertEquals("Anywhere", Search("python developer", "").place)
    }


    @Test
    fun remoteBoardJobsAreCreditedInAlerts() {
        val remote = job(1, search = RemoteBoards.LABEL).copy(source = "Remotive")
        assertEquals("1 new job", Messages.headline(listOf(remote)))
        assertEquals("2 new jobs", Messages.headline(listOf(remote, job(2))))
        assertEquals("1 new LinkedIn job", Messages.headline(listOf(job(2))))
        assertTrue(Messages.whatsappText(listOf(remote)).contains("@ Acme & Co (via Remotive)"))
        assertTrue(Messages.emailText(listOf(remote)).contains("(via Remotive)"))
        assertTrue(Messages.emailHtml(listOf(remote)).contains("(via Remotive)"))
        assertFalse(Messages.whatsappText(listOf(job(2))).contains("via"))
    }

    @Test
    fun whatsappMessageIsCappedAndGrouped() {
        val jobs = (0 until 20).map { job(it, if (it < 12) "python developer @ Dubai" else "data analyst @ Riyadh") }
        val text = Messages.whatsappText(jobs)
        assertTrue(text.startsWith("*20 new LinkedIn jobs*"))
        assertEquals(Messages.WHATSAPP_MAX_JOBS, text.lines().count { it.startsWith("- ") })
        assertTrue(text.contains("*data analyst @ Riyadh*"))
        assertTrue(text.endsWith("...and 5 more in the app."))
        assertEquals("*1 new LinkedIn job*", Messages.whatsappText(listOf(job(1))).lines().first())
    }

    @Test
    fun callMeBotVerdictIgnoresTheEchoedMessage() {
        val sent = Messages.whatsappText(listOf(job(1, title = "Error Analyst")))
        val ok = "Message to: +1 Text to send: ${sent.replace("\n", "%0A")} Message queued. You will receive it in a few seconds."
        val okVerdict = Messages.callMeBotVerdict(ok, sent)
        assertFalse("a job title containing 'Error' is not a failure", Messages.isCallMeBotFailure(okVerdict))

        val bad = "Message to: +1 Text to send: ${sent.replace("\n", "%0A")} APIKey is invalid. Please create a new one."
        val badVerdict = Messages.callMeBotVerdict(bad, sent)
        assertTrue(Messages.isCallMeBotFailure(badVerdict))
        assertEquals("APIKey is invalid. Please create a new one.", badVerdict)
    }

    @Test
    fun emailIsGroupedAndEscaped() {
        val jobs = listOf(job(1, title = "<Senior> Dev"), job(2, "data analyst @ Riyadh"))
        assertEquals("2 new LinkedIn jobs: python developer @ Dubai, data analyst @ Riyadh", Messages.emailSubject(jobs))
        val html = Messages.emailHtml(jobs)
        assertTrue(html.contains("&lt;Senior&gt; Dev") && html.contains("Acme &amp; Co"))
        assertFalse(html.contains("<Senior>"))
        assertTrue(Messages.emailText(jobs).contains("https://www.linkedin.com/jobs/view/2/"))
    }

    @Test
    fun alertSettingsAreValidated() {
        assertEquals(null, AlertConfig().problem())
        assertTrue(AlertConfig(emailEnabled = true).problem()!!.startsWith("Fill in all the email fields"))
        val email = AlertConfig(emailEnabled = true, emailTo = "me@example.com", smtpUser = "bot@gmail.com", smtpPassword = "x")
        assertEquals(null, email.problem())
        assertTrue(email.copy(smtpPort = "abc").problem() != null)
        assertTrue(AlertConfig(whatsappEnabled = true, whatsappPhone = "+971").problem()!!.contains("WhatsApp"))
        assertEquals("+971501234567", AlertConfig(whatsappPhone = " +971 50 123 4567 ").trimmed().whatsappPhone)
    }

    /** Talks to LinkedIn for real; run with LIVE_LINKEDIN=1. */
    @Test
    fun liveFetchFromLinkedIn() {
        assumeTrue(System.getenv("LIVE_LINKEDIN") == "1")
        val now = System.currentTimeMillis()
        val jobs = LinkedIn.fetchNewJobs(Search("data analyst", "Saudi Arabia", lastCheck = now - 60 * 60_000), emptySet(), now)
        println("live: ${jobs.size} jobs in the last hour, e.g. ${jobs.firstOrNull()}")
        assertEquals(jobs.size, jobs.map { it.id }.toSet().size)
        assertTrue(jobs.all { it.url.startsWith("https://www.linkedin.com/jobs/view/") })
    }
}
