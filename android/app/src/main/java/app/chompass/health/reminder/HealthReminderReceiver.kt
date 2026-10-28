package app.chompass.health.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.chompass.ChompassApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Alarms firing for the medication module.
 *
 * Two kinds:
 * - [ACTION_DOSE_REMINDER] — one instance is due → post the notification;
 * - [ACTION_DAY_TICK] — a new day started → conclude yesterday, create today's
 *   instances, re-arm. Without it the alarm chain dies the first day the user
 *   doesn't open the app, because instances are created per calendar day.
 *
 * `goAsync()` (plus the application's process-lifetime scope) because
 * `onReceive` returning kills the process, and the database read + notify are
 * asynchronous. Skipping it is the classic "the reminder only rings when the
 * screen is on" bug.
 */
class HealthReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_DOSE_REMINDER && action != ACTION_DAY_TICK) return

        val scope = healthReminderScope(context) ?: return
        val center = healthReminderCenter(context) ?: return
        val pending = goAsync()

        scope.launch {
            try {
                when (action) {
                    ACTION_DOSE_REMINDER -> {
                        val id = intent.getLongExtra(EXTRA_INSTANCE_ID, -1L)
                        if (id > 0) center.notifyDose(id)
                    }

                    ACTION_DAY_TICK -> {
                        val (rolled, created) = center.runDayTick()
                        Log.i(TAG, "day tick: rolled=$rolled created=$created")
                    }
                }
            } catch (t: Throwable) {
                // A crashed receiver would take down a process the user did not
                // open; log and let the next sync heal the alarm state.
                Log.e(TAG, "reminder failed action=$action", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "HealthReminder"

        const val ACTION_DOSE_REMINDER = "app.chompass.action.HEALTH_DOSE_REMINDER"
        const val ACTION_DAY_TICK = "app.chompass.action.HEALTH_DAY_TICK"
        const val EXTRA_INSTANCE_ID = "instance_id"
    }
}

/**
 * The app's process-lifetime scope. Null only when the broadcast arrives in a
 * process without the application class (instrumentation, bad manifest).
 */
internal fun healthReminderScope(context: Context): CoroutineScope? =
    (context.applicationContext as? ChompassApp)?.applicationScope

/**
 * The health graph's reminder entry point. Touching [ChompassApp.container] here
 * opens the diary database lazily — which is exactly what a reminder needs, and
 * still keeps a plain nutrition-only cold start from opening it.
 */
internal fun healthReminderCenter(context: Context): HealthReminderCenter? {
    val app = context.applicationContext as? ChompassApp ?: return null
    return runCatching { app.container.healthDiary.reminders }.getOrNull()
}
