package app.chompass.health.reminder

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import app.chompass.health.data.db.dao.SettingDao
import app.chompass.health.data.db.entity.AppSettingEntity
import app.chompass.health.data.db.entity.SettingKeys
import app.chompass.health.data.repo.MedRepository
import app.chompass.health.data.repo.TodaySnapshot
import app.chompass.health.domain.dose.StockCalculator
import app.chompass.health.domain.model.DoseInstanceStatus
import app.chompass.health.domain.model.DoseLogKind
import app.chompass.health.ui.med.medDoseText
import app.chompass.health.util.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Translates "the pending doses in the database" into "the alarms in the system".
 *
 * One entry point, [sync]: app start, day rollover, editing a medication,
 * recording / skipping / undoing, and the master switch all call the same
 * function, so no path can forget to re-arm. Repeated calls are idempotent — the
 * same request code is overwritten rather than stacked.
 *
 * Text is never built here: [HealthNotifications] owns the copy and this class
 * only passes names, numbers and times.
 */
class HealthReminderCenter(
    context: Context,
    private val meds: MedRepository,
    private val settings: SettingDao,
    private val clock: () -> Long,
) {
    private val appContext = context.applicationContext
    private val scheduler = HealthReminderScheduler(appContext)

    /**
     * Process scope, not `viewModelScope`: the receivers drive this after the UI
     * is gone. Unbounded re-syncs are harmless — [sync] only writes alarms.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Master switch (Settings row); missing value means "on", like the seeded default. */
    val masterEnabled: Flow<Boolean> =
        settings.observeAll().map { rows ->
            rows.firstOrNull { it.key == SettingKeys.REMINDER_MASTER_ENABLED }?.value != "0"
        }

    suspend fun isEnabled(): Boolean =
        settings.get(SettingKeys.REMINDER_MASTER_ENABLED) != "0"

    fun canPostNotifications(): Boolean =
        NotificationManagerCompat.from(appContext).areNotificationsEnabled()

    fun canScheduleExact(): Boolean = scheduler.canScheduleExact()

    /**
     * Re-arms today's remaining reminders and tomorrow's day tick.
     *
     * Deliberately **not** calling `ensureToday()`: arming alarms must not write
     * rows. Creating instances is the job of the start-up path and the day tick;
     * mixing the two means revoking a permission also performs a write.
     */
    suspend fun sync() {
        HealthNotifications.ensureChannels(appContext)

        if (!isEnabled()) {
            clearToday()
            scheduler.cancelDayTick()
            return
        }

        val now = clock()
        val snapshot = meds.observeToday().first()
        val reminderMeds = snapshot.medications
            .filter { it.reminderEnabled }
            .map { it.id }
            .toSet()

        // One pass over today's rows: arm exactly the ones that still need a
        // reminder and drop every other alarm. Two separate passes would leave a
        // stale alarm behind for a dose whose medication switched its reminder off.
        snapshot.instances.forEach { inst ->
            val shouldArm = inst.status == DoseInstanceStatus.PENDING &&
                inst.planAt > now &&
                inst.medicationId in reminderMeds
            if (shouldArm) {
                scheduler.schedule(inst.id, inst.planAt, now)
            } else {
                scheduler.cancel(inst.id)
            }
        }

        scheduler.scheduleDayTick(now)

        if (canPostNotifications()) {
            runCatching { maybeNotifyLowStock(snapshot, now) }
        }
    }

    /**
     * One refill reminder per day, for the most urgent medication only.
     *
     * The de-duplication key lives in the settings table: [sync] runs on every
     * record, undo and cold start, so an in-memory flag would reset with the
     * process and the user would get the same nag several times a day. And
     * posting three refill notifications at once tells the user we cannot pick
     * the one that matters.
     */
    private suspend fun maybeNotifyLowStock(snapshot: TodaySnapshot, now: Long) {
        val low = snapshot.medications
            .mapNotNull { med ->
                val days = StockCalculator.remainingDays(
                    med.stockUnits,
                    med.intakeUnits,
                    med.freqPerDay,
                ) ?: return@mapNotNull null
                if (!StockCalculator.isLow(days, med.stockAlertDays)) return@mapNotNull null
                med to days
            }
            .minByOrNull { it.second }
            ?: return

        val today = TimeUtils.toDateString(now)
        if (settings.get(SettingKeys.STOCK_ALERT_LAST_DATE) == today) return

        HealthNotifications.showStock(appContext, low.first.name, low.second)
        settings.put(
            AppSettingEntity(
                key = SettingKeys.STOCK_ALERT_LAST_DATE,
                value = today,
                updatedAt = now,
            )
        )
    }

    /** Fired by the alarm: post the reminder if the dose is still outstanding. */
    suspend fun notifyDose(instanceId: Long) {
        if (!isEnabled()) return
        if (!canPostNotifications()) return

        val inst = meds.instance(instanceId) ?: return
        // The alarm and the user race: they may have recorded this dose already.
        if (inst.status != DoseInstanceStatus.PENDING) return
        val med = meds.medication(inst.medicationId)
        if (med != null && !med.reminderEnabled) return

        HealthNotifications.showDose(
            context = appContext,
            instanceId = instanceId,
            drugName = inst.drugName,
            doseText = medDoseText(
                strengthValue = inst.strengthValue,
                strengthUnit = inst.strengthUnit,
                intakeUnits = inst.intakeUnits,
                unitLabel = inst.unitLabel,
            ),
            timeText = TimeUtils.toTimeString(inst.planAt),
        )
    }

    suspend fun onDoseTaken(instanceId: Long) {
        HealthNotifications.cancelDose(appContext, instanceId)
        scheduler.cancel(instanceId)
        sync()
    }

    suspend fun onDoseSkipped(instanceId: Long) {
        HealthNotifications.cancelDose(appContext, instanceId)
        scheduler.cancel(instanceId)
        sync()
    }

    /** "Snooze": only the alarm moves; no business state changes. */
    suspend fun onDoseSnoozed(instanceId: Long) {
        HealthNotifications.cancelDose(appContext, instanceId)
        val inst = meds.instance(instanceId) ?: return
        if (inst.status != DoseInstanceStatus.PENDING) return
        scheduler.snooze(instanceId, clock())
    }

    /** Record a dose straight from the notification; `SCHEDULED`, not a backfill. */
    suspend fun recordFromNotification(instanceId: Long) {
        meds.record(instanceId, DoseLogKind.SCHEDULED)
    }

    suspend fun skipFromNotification(instanceId: Long) {
        meds.skip(instanceId)
    }

    /** After any medication edit; never blocks the screen that triggered it. */
    fun syncAsync() {
        scope.launch { runCatching { sync() } }
    }

    /** Day rollover: conclude yesterday, create today, then re-arm. */
    suspend fun runDayTick(): Pair<Int, Int> {
        val rolled = meds.rollover()
        val created = meds.ensureToday()
        sync()
        return rolled to created
    }

    suspend fun setMaster(enabled: Boolean) {
        settings.put(
            AppSettingEntity(
                key = SettingKeys.REMINDER_MASTER_ENABLED,
                value = if (enabled) "1" else "0",
                updatedAt = clock(),
            )
        )
        sync()
    }

    fun notificationSettingsIntent(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** Only meaningful from Android 12 on; the row that opens it is hidden before that. */
    fun exactAlarmSettingsIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${appContext.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private suspend fun clearToday() {
        runCatching {
            meds.observeToday().first().instances.forEach { scheduler.cancel(it.id) }
        }
    }
}
