package app.chompass.health.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch

/**
 * Re-arms the medication alarms after a reboot, an app update, or a clock /
 * time-zone change.
 *
 * **Alarms do not survive a reboot**: everything `AlarmManager` holds is gone.
 * Without this the user gets no reminder until they open the app again, and the
 * failure cannot be reproduced without "reboot + wait a whole day".
 *
 * The rollover needs to run too: if the phone was off across midnight, "today"
 * has moved on and yesterday's pending rows are now missed.
 */
class HealthBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in TRIGGERS) return

        val scope = healthReminderScope(context) ?: return
        val center = healthReminderCenter(context) ?: return
        val pending = goAsync()

        scope.launch {
            try {
                val (rolled, created) = center.runDayTick()
                Log.i(TAG, "re-armed ($action): rolled=$rolled created=$created")
            } catch (t: Throwable) {
                Log.e(TAG, "re-arm failed action=$action", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        private const val TAG = "HealthReminder"

        val TRIGGERS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            // Some vendor ROMs send only this after a quick boot.
            "android.intent.action.QUICKBOOT_POWERON",
        )
    }
}
