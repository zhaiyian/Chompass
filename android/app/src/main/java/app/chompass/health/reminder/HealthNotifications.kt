package app.chompass.health.reminder

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.chompass.R
import app.chompass.services.ChompassLaunchIntents
import app.chompass.ui.navigation.ChompassRoutes
import app.chompass.utils.LocaleHelper

/**
 * Channels, text and actions for the medication reminders.
 *
 * Two channels on purpose. "Time for X" is a reminder the user asked for and must
 * not be silenced by accident; "running low" is bookkeeping. Merged into one
 * channel, muting the refill nag would also silence the doses.
 *
 * The three action buttons live on the notification because the user is usually
 * holding the phone one-handed at that moment — making them open the app and tap
 * twice more is how a reminder gets ignored.
 *
 * Text is resolved here, not in the domain layer: [HealthReminderCenter] carries
 * numbers, codes and timestamps only.
 */
object HealthNotifications {

    const val CHANNEL_DOSE = "health_med_reminder"
    const val CHANNEL_STOCK = "health_stock_alert"

    /**
     * Notification ids must fit in 32 bits. Dose ids sit in their own band so they
     * can never collide with the refill alert or with the main app's ids (1001-1012,
     * 4242, 5555).
     */
    private const val ID_DOSE_PREFIX = 7_300
    private const val ID_DOSE_SPAN = 100_000

    /** Above the whole dose band (which ends at 107_299). */
    private const val ID_STOCK = 907_300

    private const val ACTION_TAKE_OFFSET = 1
    private const val ACTION_SNOOZE_OFFSET = 2
    private const val ACTION_SKIP_OFFSET = 3

    /** API < 33 resolves channel names and bodies through the cached in-app language. */
    private fun localized(context: Context): Context = LocaleHelper.wrapIfLegacy(context)

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        val strings = localized(context)

        val dose = NotificationChannel(
            CHANNEL_DOSE,
            strings.getString(R.string.notif_health_dose_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = strings.getString(R.string.notif_health_dose_channel_desc)
            enableVibration(true)
        }
        val stock = NotificationChannel(
            CHANNEL_STOCK,
            strings.getString(R.string.notif_health_stock_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = strings.getString(R.string.notif_health_stock_channel_desc)
        }
        mgr.createNotificationChannels(listOf(dose, stock))
    }

    fun doseId(instanceId: Long): Int =
        ID_DOSE_PREFIX + (instanceId % ID_DOSE_SPAN).toInt()

    fun showDose(
        context: Context,
        instanceId: Long,
        drugName: String,
        doseText: String,
        timeText: String,
    ) {
        val strings = localized(context)
        val n = buildDoseNotification(context, strings, instanceId, drugName, doseText, timeText)
        notifySafely(context, doseId(instanceId), n)
    }

    fun cancelDose(context: Context, instanceId: Long) {
        NotificationManagerCompat.from(context).cancel(doseId(instanceId))
    }

    fun showStock(context: Context, name: String, days: Int) {
        val strings = localized(context)
        val text = if (days <= 0) {
            strings.getString(R.string.notif_health_stock_empty, name)
        } else {
            strings.getString(R.string.notif_health_stock_text, name, days)
        }
        val n = NotificationCompat.Builder(context, CHANNEL_STOCK)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(strings.getString(R.string.notif_health_stock_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openApp(context, ID_STOCK))
            .setAutoCancel(true)
            .build()
        notifySafely(context, ID_STOCK, n)
    }

    private fun buildDoseNotification(
        context: Context,
        strings: Context,
        instanceId: Long,
        drugName: String,
        doseText: String,
        timeText: String,
    ): Notification {
        val title = strings.getString(R.string.notif_health_dose_title, drugName)
        val text = strings.getString(R.string.notif_health_dose_text, doseText, timeText)
        val big = strings.getString(R.string.notif_health_dose_big, doseText, timeText)

        return NotificationCompat.Builder(context, CHANNEL_DOSE)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openApp(context, instanceId.toInt()))
            .setAutoCancel(true)
            .addAction(
                0,
                strings.getString(R.string.notif_health_action_take),
                action(context, instanceId, HealthDoseActionReceiver.ACTION_TAKE, ACTION_TAKE_OFFSET),
            )
            .addAction(
                0,
                strings.getString(R.string.notif_health_action_snooze),
                action(context, instanceId, HealthDoseActionReceiver.ACTION_SNOOZE, ACTION_SNOOZE_OFFSET),
            )
            .addAction(
                0,
                strings.getString(R.string.notif_health_action_skip),
                action(context, instanceId, HealthDoseActionReceiver.ACTION_SKIP, ACTION_SKIP_OFFSET),
            )
            .build()
    }

    private fun openApp(context: Context, request: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            request,
            ChompassLaunchIntents.openApp(context, destination = ChompassRoutes.HEALTH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun action(
        context: Context,
        instanceId: Long,
        action: String,
        offset: Int,
    ): PendingIntent {
        val intent = Intent(context, HealthDoseActionReceiver::class.java).apply {
            this.action = action
            // The data URI is what makes the three actions distinct to the
            // system; without it they would collapse into one PendingIntent.
            data = Uri.parse("app.chompass://health/dose/$instanceId/$offset")
            putExtra(HealthReminderReceiver.EXTRA_INSTANCE_ID, instanceId)
        }
        return PendingIntent.getBroadcast(
            context,
            (instanceId % 100_000).toInt() * 10 + offset,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Permission can be revoked between arming and firing; never crash the receiver. */
    @SuppressLint("MissingPermission")
    private fun notifySafely(context: Context, id: Int, notification: Notification) {
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}
