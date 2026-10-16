package com.jobwatch.app

/** Text for alerts. Plain Kotlin, so it is unit-tested on the PC. */
object Messages {
    const val WHATSAPP_MAX_JOBS = 15 // the message travels in a URL, so keep it a sensible size

    val SAMPLE_JOB = Job("0", "Test job - your alerts work!", "Job Watch", "Anywhere", "",
        "https://www.linkedin.com/jobs/", search = "Test")

    fun headline(jobs: List<Job>) = "${jobs.size} new LinkedIn job${if (jobs.size == 1) "" else "s"}"

    private fun bySearch(jobs: List<Job>): Map<String, List<Job>> = jobs.groupBy { it.search }

    fun emailSubject(jobs: List<Job>) = "${headline(jobs)}: ${bySearch(jobs).keys.joinToString(", ")}"

    fun emailText(jobs: List<Job>): String = bySearch(jobs).entries.joinToString("\n\n") { (search, group) ->
        search + "\n" + group.joinToString("\n") { "- ${it.title} - ${it.company} (${it.location})\n  ${it.url}" }
    }

    fun emailHtml(jobs: List<Job>): String = buildString {
        append("<div style='font-family:Roboto,Segoe UI,Arial,sans-serif;font-size:14px'>")
        for ((search, group) in bySearch(jobs)) {
            append("<h3 style='margin:18px 0 6px'>${escape(search)}</h3><ul style='padding-left:18px'>")
            for (job in group) {
                append("<li style='margin-bottom:6px'><a href='${escape(job.url)}'>${escape(job.title)}</a>")
                append("<br>${escape(job.company)} <span style='color:#666'>&middot; ${escape(job.location)}</span></li>")
            }
            append("</ul>")
        }
        append("</div>")
    }

    fun whatsappText(jobs: List<Job>): String {
        val lines = mutableListOf("*${headline(jobs)}*")
        var shown = 0
        for ((search, group) in bySearch(jobs)) {
            if (shown == WHATSAPP_MAX_JOBS) break
            lines += "\n*$search*"
            for (job in group.take(WHATSAPP_MAX_JOBS - shown)) {
                lines += "- ${job.title} @ ${job.company}\n${job.url}"
                shown++
            }
        }
        if (jobs.size > shown) lines += "\n...and ${jobs.size - shown} more in the app."
        return lines.joinToString("\n")
    }

    /**
     * CallMeBot replies with a page that echoes our message back before its verdict
     * ("Message to: ... Text to send: <text> <verdict>"). Return only the verdict, so a
     * job titled e.g. "Error Analyst" isn't mistaken for a failure.
     */
    fun callMeBotVerdict(replyText: String, sentText: String): String {
        val lastLine = sentText.lines().last()
        val at = replyText.lastIndexOf(lastLine)
        val verdict = if (at >= 0) replyText.substring(at + lastLine.length).trim() else replyText
        return verdict.ifBlank { replyText }
    }

    fun isCallMeBotFailure(verdict: String) = Regex("invalid|error|wrong", RegexOption.IGNORE_CASE).containsMatchIn(verdict)

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("'", "&#39;").replace("\"", "&quot;")
}
