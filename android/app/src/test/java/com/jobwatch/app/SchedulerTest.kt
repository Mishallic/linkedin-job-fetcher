package com.jobwatch.app

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Turning background checks off must stop the app from running at all. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val requests = mutableListOf<String>()

    @After
    fun tearDown() {
        Http.fake = null
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
}
