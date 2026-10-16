package com.jobwatch.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import kotlin.concurrent.thread

/**
 * Wakes the app every 10 minutes with an alarm. Alarms (unlike scheduled jobs) still
 * fire when the phone is idle, and an app exempt from battery optimization may use the
 * network then, so checks keep their rhythm while the phone sits in a pocket.
 *
 * When the user turns background checks off, the alarm is cancelled and nothing here
 * schedules a new one, so the app doesn't run at all until it's opened again.
 */
object Scheduler {
    const val INTERVAL_MINUTES = 10L

    private fun prefs(context: Context) = context.getSharedPreferences("scheduler", Context.MODE_PRIVATE)

    private fun alarmIntent(context: Context) = PendingIntent.getBroadcast(context, 0,
        Intent(context, CheckReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Set the next alarm, unless background checks are off (then make sure none is left). */
    fun scheduleNext(context: Context, delayMs: Long = INTERVAL_MINUTES * 60_000) {
        if (!Store.load(context).checksOn) {
            stop(context)
            return
        }
        val alarms = context.getSystemService(AlarmManager::class.java)
        val at = System.currentTimeMillis() + delayMs
        if (canExact(context)) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(context))
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(context))
        prefs(context).edit().putLong("nextAt", at).apply()
    }

    fun stop(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))
        prefs(context).edit().putLong("nextAt", 0L).apply()
    }

    /** When the next background check is due, or 0 if none is scheduled. */
    fun nextCheckAt(context: Context): Long = prefs(context).getLong("nextAt", 0L)

    fun canExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun ignoresBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
}

class CheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (!Store.load(app).checksOn) { // turned off after this alarm was set: end the chain here
            Scheduler.stop(app)
            return
        }
        Scheduler.scheduleNext(app) // keep the 10-minute chain going whatever happens below
        val pending = goAsync()
        thread {
            try {
                Checker.run(app)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Alarms are cleared when the phone restarts or the app is updated; start the chain again if checks are on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.scheduleNext(context.applicationContext, 60_000)
    }
}
