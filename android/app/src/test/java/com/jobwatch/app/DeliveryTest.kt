package com.jobwatch.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The delivery-time measure: each job's stages, and the summary the app shows and shares. */
class DeliveryTest {
    private val now = 1_790_000_000_000L
    private val minute = 60_000L

    private fun job(id: Int, postedAgo: Long?, foundAgo: Long, notifiedAgo: Long?, source: String = "LinkedIn") = Job(
        "$id", "Job $id", "Acme", "Dubai", "", "https://www.linkedin.com/jobs/view/$id/", "data analyst @ Dubai",
        foundAt = now - foundAgo * minute, source = source,
        postedAt = postedAgo?.let { now - it * minute } ?: 0L, notifiedAt = notifiedAgo?.let { now - it * minute } ?: 0L,
    )

    @Test
    fun eachStageOfAJobsJourney() {
        val t = Delivery.Times(job(1, postedAgo = 30, foundAgo = 22, notifiedAgo = 12))
        assertEquals(8L, t.toFound)
        assertEquals(10L, t.toNotified)
        assertEquals(18L, t.total)

        val unknown = Delivery.Times(job(2, postedAgo = null, foundAgo = 5, notifiedAgo = null))
        assertNull(unknown.toFound)
        assertNull(unknown.toNotified)
        assertNull(unknown.total)
    }

    @Test
    fun percentiles() {
        val values = (1L..10L).toList().shuffled()
        assertEquals(5L, Delivery.percentile(values, 0.5))
        assertEquals(9L, Delivery.percentile(values, 0.9))
        assertEquals(10L, Delivery.percentile(values, 1.0))
        assertNull(Delivery.percentile(emptyList(), 0.5))
    }

    @Test
    fun onlyLinkedInsRecentJobsAreMeasuredAndTheSummaryNamesEachStage() {
        val jobs = listOf(
            job(1, postedAgo = 12, foundAgo = 6, notifiedAgo = 6),
            job(2, postedAgo = 30, foundAgo = 20, notifiedAgo = 10),
            job(3, postedAgo = 60, foundAgo = 50, notifiedAgo = 50, source = "Remotive"), // not to the minute
            job(4, postedAgo = 30 * 60, foundAgo = 25 * 60, notifiedAgo = 25 * 60), // more than a day ago
        )
        val times = Delivery.recent(jobs, now)
        assertEquals(listOf("1", "2"), times.map { it.job.id })
        assertEquals(listOf(
            "2 LinkedIn jobs found in the last 24 hours.",
            "Posted to notified: median 6 min, 9 in 10 within 20 min",
            "Posted to found: median 6 min, 9 in 10 within 10 min",
            "Found to notified: median 0 min, 9 in 10 within 10 min",
        ), Delivery.summary(times))

        val report = Delivery.report(times)
        assertTrue(report, report.startsWith("Job Watch delivery times"))
        assertTrue(report, "Job 2 · Acme (data analyst @ Dubai)" in report && "(10 min after)" in report)
    }
}
