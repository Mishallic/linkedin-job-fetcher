package com.jobwatch.app

import android.content.Context

/**
 * Moves every time the app saved [minutes] into the past, as if that much time had gone by: the
 * last check and LinkedIn request, each search's turn and window, the slow-down pause, the last
 * alert, and the boards' and job sites' reads. Zero ("never") stays zero.
 */
fun minutesPass(context: Context, minutes: Long) {
    val ms = minutes * 60_000
    fun back(time: Long) = if (time > 0) time - ms else time
    Store.update(context) { d ->
        d.copy(
            lastRun = back(d.lastRun),
            lastLinkedInAt = back(d.lastLinkedInAt),
            linkedInPausedUntil = back(d.linkedInPausedUntil),
            lastAlertAt = back(d.lastAlertAt),
            remoteLastCheck = back(d.remoteLastCheck),
            boardChecks = d.boardChecks.mapValues { back(it.value) },
            siteChecks = d.siteChecks.mapValues { back(it.value) },
            searches = d.searches.map {
                it.copy(lastTry = back(it.lastTry), lastCheck = back(it.lastCheck), checkStartedAt = back(it.checkStartedAt))
            },
        )
    }
}
