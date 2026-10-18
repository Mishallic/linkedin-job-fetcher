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
 * Wakes the app with an alarm for each check ([Checker.tick]): at the next search's turn, and at
 * most every [SPACING_MINUTES] minutes, so LinkedIn gets one request per 2 minutes and each of up to
 * 5 searches is checked every [INTERVAL_MINUTES] minutes. Alarms (unlike scheduled jobs) still fire
 * when the phone is idle, and an app exempt from battery optimization may use the network then, so
 * checks keep their rhythm while the phone sits in a pocket.
 *
 * When the user turns background checks off, the alarm is cancelled and nothing here
 * schedules a new one, so the app doesn't run at all until it's opened again.
 */
object Scheduler {
    /** Each search is checked this often, and so are the job boards without a stricter limit. */
    const val INTERVAL_MINUTES = 10L

    /** LinkedIn gets at most one request per this many minutes, and the app wakes no more often. */
    const val SPACING_MINUTES = 2L

    private fun prefs(context: Context) = context.getSharedPreferences("scheduler", Context.MODE_PRIVATE)

    private fun alarmIntent(context: Context) = PendingIntent.getBroadcast(context, 0,
        Intent(context, CheckReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Set the alarm for [at], unless background checks are off (then make sure none is left). */
    fun scheduleAt(context: Context, at: Long) {
        if (!Store.load(context).checksOn) {
            stop(context)
            return
        }
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (canExact(context)) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(context))
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(context))
        prefs(context).edit().putLong("nextAt", at).apply()
    }

    /** Set the alarm [delayMs] from now. */
    fun scheduleNext(context: Context, delayMs: Long = SPACING_MINUTES * 60_000) =
        scheduleAt(context, System.currentTimeMillis() + delayMs)

    /** Set the alarm for the next check that's due ([Checker.nextTickAt]). */
    fun reschedule(context: Context) =
        scheduleAt(context, Checker.nextTickAt(context, Store.load(context), System.currentTimeMillis()))

    fun stop(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))
        prefs(context).edit().putLong("nextAt", 0L).apply()
    }

    /** When the next background check is due, or 0 if none is scheduled. */
    fun nextCheckAt(context: Context): Long = prefs(context).getLong("nextAt", 0L)

    /**
     * Whether a background check is on its way: an alarm was set for a time that hasn't come,
     * and the phone still has it. The phone forgets alarms (and their intents) when it restarts,
     * and when the app is updated or force-stopped. [BootReceiver] covers the first two; opening
     * the app covers the rest, and leaves a healthy chain alone ([MainActivity.onResume]).
     */
    fun isScheduled(context: Context): Boolean =
        nextCheckAt(context) > System.currentTimeMillis() && PendingIntent.getBroadcast(context, 0,
            Intent(context, CheckReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE) != null

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
        Scheduler.scheduleNext(app) // keep the chain going whatever happens below; the check then sets the exact time
        val pending = goAsync()
        thread {
            try {
                Checker.tick(app, Checker.TRIGGER_ALARM)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Alarms are cleared when the phone restarts or the app is updated; start the chain again if checks are on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RemoteBoards.cancelOldSchedule(context.applicationContext) // the boards now ride on the alarm
        Scheduler.scheduleNext(context.applicationContext, 60_000)
    }
}
