package com.jobwatch.app

/**
 * How long jobs took to reach you, stage by stage: from being posted (as LinkedIn tells it, to the
 * minute) to a check finding them, and from there to the phone's notification. This is the measure
 * behind "the job came late", shown in the app's Delivery times and shared from there.
 */
object Delivery {
    private const val MINUTE = 60_000L

    /** One job's times, in minutes; null where a stage isn't known (no posting time, or not notified). */
    class Times(val job: Job) {
        val toFound: Long? = if (job.postedAt > 0) (job.foundAt - job.postedAt).coerceAtLeast(0) / MINUTE else null
        val toNotified: Long? = if (job.notifiedAt > 0) (job.notifiedAt - job.foundAt).coerceAtLeast(0) / MINUTE else null
        val total: Long? = if (toFound != null && toNotified != null) toFound + toNotified else null
    }

    /** LinkedIn's jobs found in the last [hours] hours, newest first. The boards' posting times aren't to the minute. */
    fun recent(jobs: List<Job>, now: Long, hours: Long = 24): List<Times> =
        jobs.filter { it.source == "LinkedIn" && it.foundAt >= now - hours * 60 * MINUTE }
            .sortedByDescending { it.foundAt }
            .map { Times(it) }

    /**
     * The nearest-rank percentile: the smallest value with at least [fraction] of [values] at or below it
     * (0.5 is the median, 0.9 the value 9 in 10 are within). Null for no values.
     */
    fun percentile(values: List<Long>, fraction: Double): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[(kotlin.math.ceil(fraction * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]
    }

    /** The summary: how many jobs, and the median and 90th percentile of each stage. */
    fun summary(times: List<Times>): List<String> {
        if (times.isEmpty()) return listOf("No LinkedIn jobs found in the last 24 hours yet.")
        fun stage(label: String, values: List<Long>) =
            if (values.isEmpty()) "$label: not known yet"
            else "$label: median ${percentile(values, 0.5)} min, 9 in 10 within ${percentile(values, 0.9)} min"
        return listOf(
            "${times.size} LinkedIn job${if (times.size == 1) "" else "s"} found in the last 24 hours.",
            stage("Posted to notified", times.mapNotNull { it.total }),
            stage("Posted to found", times.mapNotNull { it.toFound }),
            stage("Found to notified", times.mapNotNull { it.toNotified }),
        )
    }

    /** One job's line: when it was posted, found and notified. */
    fun line(t: Times): String {
        val job = t.job
        val posted = if (job.postedAt > 0) "posted about ${timeText(job.postedAt)}" else "posted at an unknown time"
        val found = "found ${timeText(job.foundAt)}" + (t.toFound?.let { " ($it min after)" } ?: "")
        val notified = if (job.notifiedAt > 0) "notified ${timeText(job.notifiedAt)} (${t.toNotified} min after)" else "not notified"
        return "$posted · $found · $notified"
    }

    /** Everything as plain text, to share. */
    fun report(times: List<Times>): String = buildString {
        appendLine("Job Watch delivery times")
        summary(times).forEach { appendLine(it) }
        times.forEach { appendLine(); appendLine("${it.job.title} · ${it.job.company} (${it.job.search})"); appendLine(line(it)) }
    }.trim()
}
