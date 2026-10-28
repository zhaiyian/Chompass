package app.chompass.health.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import app.chompass.health.util.TimeUtils

/**
 * Alarms for the medication reminders.
 *
 * `AlarmManager` and not WorkManager: its smallest reliable interval is 15
 * minutes, while a dose reminder has to fire at the 08:00 the user picked.
 *
 * **Exact alarms must degrade, not fail.** Since Android 12
 * `SCHEDULE_EXACT_ALARM` is a special permission the user can revoke; calling
 * `setExactAndAllowWhileIdle` without it throws `SecurityException`, which from
 * the user's side looks like "this app simply doesn't remind me". So the
 * fallback is a 10-minute window — a few minutes late beats silence.
 *
 * Alarms do not survive a reboot; [HealthBootReceiver] re-arms them.
 */
class HealthReminderScheduler(private val context: Context) {

    private val alarms: AlarmManager? = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean {
        val am = alarms ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return am.canScheduleExactAlarms()
    }

    /** Arms (or re-arms) one instance; a past or concluded dose is simply cancelled. */
    fun schedule(instanceId: Long, planAt: Long, now: Long) {
        if (planAt <= now) {
            cancel(instanceId)
            return
        }
        setAt(planAt, alarmPendingIntent(instanceId), windowMs = 10 * 60_000L)
    }

    /** "Snooze 10 minutes": re-arm the same alarm without touching any record. */
    fun snooze(instanceId: Long, now: Long, delayMinutes: Long = 10L) {
        setAt(now + delayMinutes * 60_000L, alarmPendingIntent(instanceId), windowMs = 10 * 60_000L)
    }

    fun cancel(instanceId: Long) {
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode(instanceId),
            alarmIntent(instanceId),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarms?.cancel(pi)
        pi.cancel()
    }

    /**
     * The daily rollover that keeps the chain alive without the app being opened.
     *
     * Instances are created per calendar day, so without this an alarm armed for
     * today is the last one the user ever gets if they don't open the app.
     */
    fun scheduleDayTick(now: Long) {
        val at = TimeUtils.startOfDay(now) + DAY_MS + 5 * 60_000L // 00:05 tomorrow
        val intent = Intent(context, HealthReminderReceiver::class.java).apply {
            action = HealthReminderReceiver.ACTION_DAY_TICK
            data = Uri.parse("app.chompass://health/daytick")
        }
        val pi = PendingIntent.getBroadcast(
            context,
            DAY_TICK_REQUEST,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        setAt(at, pi, windowMs = 15 * 60_000L)
    }

    /** The master switch off must also drop the tick: a daily wake-up is a reminder. */
    fun cancelDayTick() {
        val intent = Intent(context, HealthReminderReceiver::class.java).apply {
            action = HealthReminderReceiver.ACTION_DAY_TICK
            data = Uri.parse("app.chompass://health/daytick")
        }
        val pi = PendingIntent.getBroadcast(
            context,
            DAY_TICK_REQUEST,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarms?.cancel(pi)
        pi.cancel()
    }

    private fun setAt(at: Long, pi: PendingIntent, windowMs: Long) {
        // Setters throw when the permission was revoked after the check and on
        // some vendor ROMs; a missing reminder must not take the process down.
        runCatching {
            val am = alarms ?: return
            if (canScheduleExact()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setWindow(AlarmManager.RTC_WAKEUP, at, windowMs, pi)
            }
        }
    }

    private fun alarmIntent(instanceId: Long): Intent =
        Intent(context, HealthReminderReceiver::class.java).apply {
            action = HealthReminderReceiver.ACTION_DOSE_REMINDER
            data = Uri.parse("app.chompass://health/reminder/$instanceId")
            putExtra(HealthReminderReceiver.EXTRA_INSTANCE_ID, instanceId)
        }

    private fun alarmPendingIntent(instanceId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode(instanceId),
            alarmIntent(instanceId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun requestCode(instanceId: Long): Int = (instanceId % 100_000).toInt()

    private companion object {
        const val DAY_MS = 24 * 60 * 60_000L

        /** Must stay clear of the main app's 1001-1012 and the per-instance range. */
        const val DAY_TICK_REQUEST = 900_101
    }
}
