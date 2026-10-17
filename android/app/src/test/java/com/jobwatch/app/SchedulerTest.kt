package com.jobwatch.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.URI
import java.util.Collections
import java.util.concurrent.TimeUnit

/** Turning background checks off must stop the app from running at all. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf()) // a round reads side by side

    @After
    fun tearDown() {
        Http.fake = null
        Checker.pauseBetweenSearchesMs = 2_000L..4_000L
    }

    @Test
    fun turningChecksOffCancelsTheAlarmAndNothingRestartsIt() {
        Http.fake = { url -> requests += url; 200 to "" }
        Scheduler.scheduleNext(context)
        assertNotNull("checks are on by default", alarms.peekNextScheduledAlarm())
        assertTrue(Scheduler.nextCheckAt(context) > System.currentTimeMillis())

        Store.update(context) { it.copy(checksOn = false) }
        Scheduler.stop(context)
        assertNull(alarms.peekNextScheduledAlarm())
        assertEquals(0L, Scheduler.nextCheckAt(context))

        // While off, nothing brings it back: opening the app, a phone restart or app update,
        // or an alarm that was already on its way.
        Scheduler.scheduleNext(context)
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        CheckReceiver().onReceive(context, Intent())
        assertNull(Checker.run(context))
        assertNull(alarms.peekNextScheduledAlarm())
        assertEquals("no LinkedIn requests while off", 0, requests.size)

        // Checks you ask for yourself still run once.
        Checker.pauseBetweenSearchesMs = 0L..0L
        assertNotNull(Checker.run(context, force = true))
        assertTrue(requests.isNotEmpty())
        assertNull("...without starting the background chain", alarms.peekNextScheduledAlarm())
        Checker.pauseBetweenSearchesMs = 2_000L..4_000L

        // Turning it back on starts the chain again, including after a restart.
        Store.update(context) { it.copy(checksOn = true) }
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNotNull(alarms.peekNextScheduledAlarm())
    }

    /** Opening the app used to check LinkedIn every time. It may only do so when no alarm is on its way. */
    @Test
    fun openingTheAppOnlyChecksWhenNoAlarmIsOnItsWay() {
        assertFalse("fresh install: nothing scheduled yet", Scheduler.isScheduled(context))

        Scheduler.scheduleNext(context)
        assertTrue("an alarm is on its way: opening the app leaves it alone", Scheduler.isScheduled(context))

        // Its time came and it hasn't fired (the phone was asleep): catch up.
        Scheduler.scheduleNext(context, 0)
        assertFalse(Scheduler.isScheduled(context))

        // The phone forgot the alarm: a restart, an update or a force-stop drops its intent.
        Scheduler.scheduleNext(context)
        assertTrue(Scheduler.isScheduled(context))
        PendingIntent.getBroadcast(context, 0, Intent(context, CheckReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)!!.cancel()
        assertFalse(Scheduler.isScheduled(context))

        // Turned off: nothing on its way, and nothing to start.
        Store.update(context) { it.copy(checksOn = false) }
        Scheduler.stop(context)
        assertFalse(Scheduler.isScheduled(context))
    }

    @Test
    fun eachRoundReadsTheRemoteBoardsUnlessEitherSwitchIsOff() {
        Http.fake = { url -> requests += url; 200 to "" }
        Checker.pauseBetweenSearchesMs = 0L..0L
        fun boardRequests() = requests.count { "linkedin.com" !in it }

        Store.update(context) { it.copy(checksOn = false) }
        assertNull(Checker.round(context))
        assertEquals("background checks off: the boards aren't read either", 0, boardRequests())

        Store.update(context) { it.copy(checksOn = true, remoteOn = false) }
        Checker.round(context)
        assertTrue("LinkedIn is checked", requests.isNotEmpty())
        assertEquals("the boards are off", 0, boardRequests())

        Store.update(context) { it.copy(remoteOn = true) }
        Checker.round(context) // what the 10-minute alarm runs
        val boardsRead = requests.map { URI(it).host }.filter { "linkedin" !in it }.toSet()
        assertEquals("both on: the round reads every board that's due", RemoteBoards.boards.size, boardsRead.size)
    }

    @Test
    fun theOldSixHourScheduleIsCancelledOnUpdate() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val work = WorkManager.getInstance(context)
        // What 1.8 left behind: a periodic job named "remote-boards".
        work.enqueueUniquePeriodicWork("remote-boards", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<IdleWorker>(6, TimeUnit.HOURS).build())
        fun state() = work.getWorkInfosForUniqueWork("remote-boards").get().single().state
        assertEquals(WorkInfo.State.ENQUEUED, state())

        BootReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(WorkInfo.State.CANCELLED, state())
    }
}

class IdleWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = Result.success()
}
