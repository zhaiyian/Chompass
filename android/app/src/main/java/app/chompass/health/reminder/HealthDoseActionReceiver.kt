package app.chompass.health.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch

/**
 * The three buttons on the dose notification.
 *
 * Uses `goAsync()` for the same reason as [HealthReminderReceiver]: writing the
 * record is asynchronous and a broadcast receiver's process is low priority,
 * so an unfinished write would be killed halfway.
 *
 * "Taken" records `SCHEDULED`, not `BACKFILL`: the user tapped when the reminder
 * fired, so the intake time is now — asking them to confirm a time they just
 * created would be asking for the same information twice.
 */
class HealthDoseActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val instanceId = intent.getLongExtra(HealthReminderReceiver.EXTRA_INSTANCE_ID, -1L)
        if (instanceId <= 0) return

        val scope = healthReminderScope(context) ?: return
        val center = healthReminderCenter(context) ?: return
        val pending = goAsync()

        scope.launch {
            try {
                when (action) {
                    ACTION_TAKE -> {
                        center.recordFromNotification(instanceId)
                        center.onDoseTaken(instanceId)
                    }

                    ACTION_SKIP -> {
                        center.skipFromNotification(instanceId)
                        center.onDoseSkipped(instanceId)
                    }

                    ACTION_SNOOZE -> center.onDoseSnoozed(instanceId)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "dose action failed action=$action id=$instanceId", t)
            } finally {
                // Dismiss the notification either way: a tap that visibly does
                // nothing invites the user to keep tapping.
                HealthNotifications.cancelDose(context, instanceId)
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "HealthReminder"

        const val ACTION_TAKE = "app.chompass.action.HEALTH_DOSE_TAKE"
        const val ACTION_SNOOZE = "app.chompass.action.HEALTH_DOSE_SNOOZE"
        const val ACTION_SKIP = "app.chompass.action.HEALTH_DOSE_SKIP"
    }
}
