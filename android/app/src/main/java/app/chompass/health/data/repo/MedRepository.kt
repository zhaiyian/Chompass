package app.chompass.health.data.repo

import androidx.room.withTransaction
import app.chompass.health.data.db.HealthDatabase
import app.chompass.health.data.db.dao.DoseDao
import app.chompass.health.data.db.dao.MedDao
import app.chompass.health.data.db.entity.DoseInstanceEntity
import app.chompass.health.data.db.entity.DoseLogEntity
import app.chompass.health.data.db.entity.MedScheduleEntity
import app.chompass.health.data.db.entity.MedicationEntity
import app.chompass.health.domain.dose.InstanceGenerator
import app.chompass.health.domain.dose.ScheduleSeed
import app.chompass.health.domain.model.DoseInstanceStatus
import app.chompass.health.domain.model.DoseLogKind
import app.chompass.health.domain.model.MealRelation
import app.chompass.health.domain.model.MedStatus
import app.chompass.health.domain.model.SkipReason
import app.chompass.health.model.MedCategory
import app.chompass.health.util.TimeUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate

/** Upper bound for one intake (tablets / capsules / ml). Beyond it is a typo, not a dose. */
private const val MAX_INTAKE_UNITS = 100.0

/** Raw snapshot for the today list; the ViewModel turns it into rows. */
data class TodaySnapshot(
    val date: LocalDate,
    val instances: List<DoseInstanceEntity>,
    /** instanceId -> the active (not revoked) log */
    val logsByInstance: Map<Long, DoseLogEntity>,
    val medications: List<MedicationEntity>,
)

/** Window snapshot for the log screen: planned instances plus every log. */
data class LogWindow(
    val from: LocalDate,
    val to: LocalDate,
    val instances: List<DoseInstanceEntity>,
    val logs: List<DoseLogEntity>,
)

/** A medication plus its raw `HH:mm` times — the edit form needs the strings back. */
data class MedDetail(
    val med: MedicationEntity,
    val times: List<String>,
)

/**
 * Medication draft.
 *
 * Category ([category]) and as-needed ([isPrn]) are orthogonal and must not be
 * derived from one another: treating "acute" as "as needed" silently drops an
 * acute-phase medication that is taken every day from the whole pipeline — no
 * reminders, no rows, and no explanation for the user.
 */
data class MedDraft(
    val id: Long = 0L,
    val name: String,
    val category: MedCategory,
    val isPrn: Boolean,
    val strengthValue: Double?,
    val strengthUnit: String?,
    val intakeUnits: Double,
    /** Display name of the smallest unit (tablet / capsule / ml), from resources. */
    val unitLabel: String,
    val mealRelation: MealRelation,
    /** HH:mm; must be empty for as-needed medications. */
    val times: List<String>,
    val reminderEnabled: Boolean,
    val stockUnits: Double?,
    val stockAlertDays: Int,
    val note: String? = null,
)

/** Business-rule failure; the screen maps each one to its own message. */
sealed interface MedSaveResult {
    /**
     * @param backfilledPastTimes when a **new** medication already has times
     *   earlier today, instances are created for them and come back here so the
     *   screen can acknowledge it. Otherwise the user meets a sudden overdue row
     *   with no explanation.
     */
    data class Success(
        val id: Long,
        val backfilledPastTimes: List<String> = emptyList(),
    ) : MedSaveResult

    data object NameRequired : MedSaveResult
    data object TimesRequired : MedSaveResult
    data object SaveFailed : MedSaveResult
}

interface MedRepository {
    /** Idempotent: creates today's instances and returns how many rows were new. */
    suspend fun ensureToday(): Int

    /** Rolls every still-pending instance from before today over to MISSED. */
    suspend fun rollover(): Int

    fun observeToday(): Flow<TodaySnapshot>
    fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<DoseInstanceEntity>>

    /** Data source for the log screen. */
    fun observeWindow(from: LocalDate, to: LocalDate): Flow<LogWindow>

    fun observeMeds(): Flow<List<MedicationEntity>>

    /** Needed to render "twice daily · 08:00 / 20:00" from one flow. */
    fun observeSchedules(): Flow<List<MedScheduleEntity>>

    suspend fun detail(id: Long): MedDetail?

    /**
     * One instance by id.
     *
     * The reminder fires outside the UI, where there is no snapshot to search:
     * an alarm armed at 23:59 can be delivered after midnight, when the instance
     * is no longer part of "today".
     */
    suspend fun instance(id: Long): DoseInstanceEntity?

    /** One medication by id; the reminder has to read its per-medication switch. */
    suspend fun medication(id: Long): MedicationEntity?

    /** Create or update (id = 0 creates); also syncs the schedule and today's future instances. */
    suspend fun saveMed(draft: MedDraft): MedSaveResult

    /** Stop: keep the data, cancel today's and later pending instances. */
    suspend fun stopMed(id: Long): Boolean

    /** As-needed / extra dose: no instance, no effect on any plan. */
    suspend fun recordExtra(
        medicationId: Long,
        actualAt: Long,
        kind: DoseLogKind,
        note: String? = null,
    ): Boolean

    suspend fun record(
        instanceId: Long,
        kind: DoseLogKind = DoseLogKind.SCHEDULED,
        /** Actual intake time; null means now. A backfill must pass its own time. */
        actualAt: Long? = null,
    ): Boolean

    suspend fun skip(instanceId: Long): Boolean
    suspend fun markMissed(instanceId: Long): Boolean

    /**
     * Undo "missed", back to pending.
     *
     * `planAt` is long past, so the derived state immediately becomes "overdue"
     * and the "backfill / missed" pair reappears. That is the correct semantics:
     * undo withdraws a conclusion, it does not assert "taken".
     */
    suspend fun reopenMissed(instanceId: Long): Boolean
    suspend fun undo(instanceId: Long): Boolean
    suspend fun resume(instanceId: Long): Boolean

    /**
     * Correct one log: actual time and intake amount.
     *
     * Only these two, because both are "the user misremembered, and fixing it
     * does not change history's conclusions". The time may not be in the future,
     * and the amount must be positive — changing it has to adjust the stock by
     * the difference, or the drawer count stops matching the app.
     *
     * The drug name and per-unit strength are properties of the medication, not
     * of one intake; they are edited in "My medications".
     */
    suspend fun updateLog(logId: Long, actualAt: Long, intakeUnits: Double): Boolean

    /**
     * Delete one log (the log screen's "delete").
     *
     * Same semantics as "undo" on the today list: revoke the log, put the
     * instance back to `PENDING` (derived as overdue) and add the stock back.
     * No physical DELETE — `dose_log` is append-only, so delete-then-record keeps
     * both events visible.
     */
    suspend fun deleteLog(logId: Long): Boolean
}

/**
 * Room-backed implementation. `internal` because it needs [HealthDatabase] for
 * transactions, which stays an implementation detail of this module.
 */
internal class MedRepositoryImpl(
    private val database: HealthDatabase,
    private val medDao: MedDao,
    private val doseDao: DoseDao,
    private val clock: () -> Long,
) : MedRepository {

    override suspend fun instance(id: Long): DoseInstanceEntity? = doseDao.instanceById(id)

    override suspend fun medication(id: Long): MedicationEntity? = medDao.byId(id)

    override suspend fun ensureToday(): Int {
        val now = clock()
        val today = TimeUtils.toLocalDate(now)
        val dateText = TimeUtils.dateString(today)

        // As-needed medications have no "should have taken it" moment: no instances.
        val meds = medDao.scheduledOnce()
        if (meds.isEmpty()) {
            // Stopped medications still need yesterday's rows rolled over — that is
            // `rollover()`'s job, not ours.
            return 0
        }

        val seeds = mutableListOf<ScheduleSeed>()
        for (med in meds) {
            val schedules = medDao.schedulesOf(med.id)
            if (schedules.isEmpty()) continue
            schedules.forEach { s ->
                seeds += ScheduleSeed(
                    medicationId = med.id,
                    scheduleId = s.id,
                    drugName = med.name,
                    strengthValue = med.strengthValue,
                    strengthUnit = med.strengthUnit,
                    intakeUnits = med.intakeUnits,
                    unitLabel = med.unitLabel,
                    mealRelation = med.mealRelation,
                    timeOfDay = s.timeOfDay,
                )
            }
        }

        val planned = InstanceGenerator.planFor(today, seeds)
        if (planned.isEmpty()) return 0

        val entities = planned.map { p ->
            DoseInstanceEntity(
                medicationId = p.medicationId,
                scheduleId = p.scheduleId,
                planAt = p.planAt,
                planDate = dateText,
                drugName = p.drugName,
                strengthValue = p.strengthValue,
                strengthUnit = p.strengthUnit,
                intakeUnits = p.intakeUnits,
                unitLabel = p.unitLabel,
                mealRelation = p.mealRelation,
                status = DoseInstanceStatus.PENDING,
                generatedAt = now,
                updatedAt = now,
            )
        }
        // Unique index + IGNORE keeps repeated calls from duplicating rows.
        val ids = doseDao.insertInstances(entities)
        return ids.count { it != -1L }
    }

    override suspend fun rollover(): Int {
        val now = clock()
        val today = TimeUtils.todayString(now)
        return doseDao.rolloverPending(today, now)
    }

    override fun observeToday(): Flow<TodaySnapshot> {
        val now = clock()
        val today = TimeUtils.toLocalDate(now)
        val dateText = TimeUtils.dateString(today)
        val dayStart = TimeUtils.startOfDay(today)
        val dayEnd = TimeUtils.endOfDay(today)

        return combine(
            doseDao.observeByDate(dateText),
            doseDao.observeLogs(dayStart, dayEnd),
            medDao.observeActive(),
        ) { instances, logs, meds ->
            TodaySnapshot(
                date = today,
                instances = instances,
                logsByInstance = logs
                    .filter { it.instanceId != null }
                    .associateBy { it.instanceId!! },
                medications = meds,
            )
        }
    }

    override fun observeBetween(from: LocalDate, to: LocalDate): Flow<List<DoseInstanceEntity>> =
        doseDao.observeBetween(TimeUtils.dateString(from), TimeUtils.dateString(to))

    override fun observeWindow(from: LocalDate, to: LocalDate): Flow<LogWindow> = combine(
        doseDao.observeBetween(TimeUtils.dateString(from), TimeUtils.dateString(to)),
        doseDao.observeLogs(TimeUtils.startOfDay(from), TimeUtils.endOfDay(to)),
    ) { instances, logs ->
        LogWindow(from = from, to = to, instances = instances, logs = logs)
    }

    override fun observeMeds(): Flow<List<MedicationEntity>> = medDao.observeAll()

    override fun observeSchedules(): Flow<List<MedScheduleEntity>> = medDao.observeAllSchedules()

    override suspend fun detail(id: Long): MedDetail? {
        val med = medDao.byId(id) ?: return null
        return MedDetail(
            med = med,
            times = medDao.schedulesOf(id).map { it.timeOfDay },
        )
    }

    override suspend fun saveMed(draft: MedDraft): MedSaveResult {
        val name = draft.name.trim()
        if (name.isEmpty()) return MedSaveResult.NameRequired

        // As-needed medications have no times; a scheduled one needs at least one,
        // otherwise the list would hold a plan with no moment attached to it.
        val times = if (draft.isPrn) emptyList() else draft.times.distinct().sorted()
        if (!draft.isPrn && times.isEmpty()) return MedSaveResult.TimesRequired

        val now = clock()
        val today = TimeUtils.toLocalDate(now)
        val todayText = TimeUtils.dateString(today)
        // New and edited medications apply to today differently; see applyScheduleToToday.
        val isNew = draft.id == 0L

        val savedId = runCatching {
            database.withTransaction {
                val id = if (draft.id == 0L) {
                    medDao.insert(
                        MedicationEntity(
                            name = name,
                            category = draft.category,
                            isPrn = draft.isPrn,
                            strengthValue = draft.strengthValue,
                            strengthUnit = draft.strengthUnit,
                            intakeUnits = draft.intakeUnits,
                            unitLabel = draft.unitLabel,
                            freqPerDay = times.size.coerceAtLeast(1),
                            mealRelation = draft.mealRelation,
                            stockUnits = draft.stockUnits,
                            stockAlertDays = draft.stockAlertDays,
                            // An as-needed medication has no scheduled moment to remind about.
                            reminderEnabled = if (draft.isPrn) false else draft.reminderEnabled,
                            status = MedStatus.ACTIVE,
                            startedOn = todayText,
                            stoppedOn = null,
                            note = draft.note,
                            createdAt = now,
                            updatedAt = now,
                        )
                    )
                } else {
                    // Read the old row first: a full REPLACE would wipe fields the
                    // draft never carried.
                    val old = medDao.byId(draft.id) ?: error("medication gone")
                    medDao.update(
                        old.copy(
                            name = name,
                            category = draft.category,
                            isPrn = draft.isPrn,
                            strengthValue = draft.strengthValue,
                            strengthUnit = draft.strengthUnit,
                            intakeUnits = draft.intakeUnits,
                            unitLabel = draft.unitLabel,
                            freqPerDay = times.size.coerceAtLeast(1),
                            mealRelation = draft.mealRelation,
                            stockUnits = draft.stockUnits,
                            stockAlertDays = draft.stockAlertDays,
                            reminderEnabled = if (draft.isPrn) false else draft.reminderEnabled,
                            note = draft.note,
                            updatedAt = now,
                        )
                    )
                    old.id
                }

                // Detach first: deleting a schedule row that an instance still
                // references fails with a foreign-key error.
                doseDao.detachSchedule(id)
                medDao.clearSchedules(id)
                if (times.isNotEmpty()) {
                    medDao.insertSchedules(
                        times.mapIndexed { index, t ->
                            MedScheduleEntity(medicationId = id, timeOfDay = t, ordinal = index)
                        }
                    )
                }
                id
            }
        }.getOrElse { return MedSaveResult.SaveFailed }

        val backfilled = runCatching {
            applyScheduleToToday(savedId, times, now, backfillPast = isNew)
        }.getOrDefault(emptyList())
        return MedSaveResult.Success(savedId, backfilled)
    }

    /**
     * Applies the new times to today's instances that have not happened yet.
     *
     * - a removed time deletes its future instance (nothing references it) and
     *   cancels a past one as "schedule removed";
     * - an added time gets an instance only if it has not passed.
     *
     * **Whether a past time is backfilled depends on new vs edit**:
     *
     * - edit (`backfillPast = false`): no. Changing a medication at 18:00 should
     *   not invent two overdue 08:00 rows; only the user knows whether those were
     *   taken.
     * - new (`backfillPast = true`): yes. Adding a medication at 10:00 that is
     *   taken at 08:00 would otherwise make it vanish from today's list — the user
     *   can neither see the dose nor backfill it, and both the list and the
     *   adherence denominator are wrong. It shows up as overdue instead, and the
     *   user closes it with "backfill / missed".
     *
     * @return the backfilled times that already passed today, for the receipt.
     */
    private suspend fun applyScheduleToToday(
        medId: Long,
        times: List<String>,
        now: Long,
        backfillPast: Boolean,
    ): List<String> {
        val med = medDao.byId(medId) ?: return emptyList()
        val today = TimeUtils.toLocalDate(now)
        val todayText = TimeUtils.dateString(today)
        val pending = doseDao.pendingOf(medId, todayText)
        val wanted = times.toSet()

        val kept = mutableSetOf<String>()
        for (inst in pending) {
            val t = TimeUtils.toTimeString(inst.planAt)
            if (t in wanted) {
                kept += t
            } else if (inst.planAt > now) {
                doseDao.deletePendingInstance(inst.id)
            } else {
                doseDao.transit(
                    id = inst.id,
                    expected = DoseInstanceStatus.PENDING,
                    next = DoseInstanceStatus.SKIPPED,
                    reason = SkipReason.SCHEDULE_REMOVED,
                    at = now,
                )
            }
        }

        if (med.status != MedStatus.ACTIVE || med.isPrn) return emptyList()
        val scheduleIdByTime = medDao.schedulesOf(med.id).associate { it.timeOfDay to it.id }
        val toCreate = times
            .filter { it !in kept }
            .mapNotNull { t ->
                val at = TimeUtils.at(today, t) ?: return@mapNotNull null
                if (!backfillPast && at <= now) return@mapNotNull null
                t to at
            }
        if (toCreate.isEmpty()) return emptyList()

        doseDao.insertInstances(
            toCreate.map { (timeOfDay, at) ->
                DoseInstanceEntity(
                    medicationId = med.id,
                    scheduleId = scheduleIdByTime[timeOfDay],
                    planAt = at,
                    planDate = todayText,
                    drugName = med.name,
                    strengthValue = med.strengthValue,
                    strengthUnit = med.strengthUnit,
                    intakeUnits = med.intakeUnits,
                    unitLabel = med.unitLabel,
                    mealRelation = med.mealRelation,
                    status = DoseInstanceStatus.PENDING,
                    generatedAt = now,
                    updatedAt = now,
                )
            }
        )
        return toCreate.filter { (_, at) -> at <= now }.map { it.first }
    }

    override suspend fun stopMed(id: Long): Boolean {
        val now = clock()
        val todayText = TimeUtils.todayString(now)
        return database.withTransaction {
            medDao.stop(id, todayText, now)
            doseDao.cancelPendingFrom(id, todayText, SkipReason.DOCTOR_STOP, now)
            true
        }
    }

    override suspend fun recordExtra(
        medicationId: Long,
        actualAt: Long,
        kind: DoseLogKind,
        note: String?,
    ): Boolean {
        val now = clock()
        val med = medDao.byId(medicationId) ?: return false
        return database.withTransaction {
            doseDao.insertLog(
                DoseLogEntity(
                    instanceId = null,
                    medicationId = medicationId,
                    actualAt = actualAt,
                    actualDate = TimeUtils.toDateString(actualAt),
                    planAt = null,
                    deviationMinutes = null,
                    drugName = med.name,
                    strengthValue = med.strengthValue,
                    strengthUnit = med.strengthUnit,
                    intakeUnits = med.intakeUnits,
                    unitLabel = med.unitLabel,
                    kind = kind,
                    note = note,
                    createdAt = now,
                )
            )
            medDao.decreaseStock(medicationId, med.intakeUnits, now)
            true
        }
    }

    override suspend fun record(
        instanceId: Long,
        kind: DoseLogKind,
        actualAt: Long?,
    ): Boolean {
        val now = clock()
        val at = actualAt ?: now
        // Recording a dose that has not been taken pollutes adherence and puts a
        // tick on a row whose time has not come.
        if (at > now) return false
        val inst = doseDao.instanceById(instanceId) ?: return false
        if (inst.status != DoseInstanceStatus.PENDING) return false
        // An active log means this row was already handled (double tap).
        if (doseDao.activeLogFor(instanceId) != null) return false

        return database.withTransaction {
            val changed = doseDao.transit(
                id = instanceId,
                expected = DoseInstanceStatus.PENDING,
                next = DoseInstanceStatus.TAKEN,
                reason = null,
                at = now,
            )
            if (changed == 0) return@withTransaction false

            doseDao.insertLog(
                DoseLogEntity(
                    instanceId = instanceId,
                    medicationId = inst.medicationId,
                    actualAt = at,
                    actualDate = TimeUtils.toDateString(at),
                    planAt = inst.planAt,
                    deviationMinutes = ((at - inst.planAt) / 60_000L).toInt(),
                    drugName = inst.drugName,
                    strengthValue = inst.strengthValue,
                    strengthUnit = inst.strengthUnit,
                    intakeUnits = inst.intakeUnits,
                    unitLabel = inst.unitLabel,
                    kind = kind,
                    createdAt = now,
                )
            )
            medDao.decreaseStock(inst.medicationId, inst.intakeUnits, now)
            true
        }
    }

    override suspend fun skip(instanceId: Long): Boolean {
        val now = clock()
        return doseDao.transit(
            id = instanceId,
            expected = DoseInstanceStatus.PENDING,
            next = DoseInstanceStatus.SKIPPED,
            reason = SkipReason.USER_SKIP,
            at = now,
        ) > 0
    }

    override suspend fun markMissed(instanceId: Long): Boolean {
        val now = clock()
        return doseDao.transit(
            id = instanceId,
            expected = DoseInstanceStatus.PENDING,
            next = DoseInstanceStatus.MISSED,
            reason = null,
            at = now,
        ) > 0
    }

    override suspend fun reopenMissed(instanceId: Long): Boolean {
        val now = clock()
        val changed = doseDao.transit(
            id = instanceId,
            expected = DoseInstanceStatus.MISSED,
            next = DoseInstanceStatus.PENDING,
            reason = null,
            at = now,
        )
        return changed > 0
    }

    override suspend fun undo(instanceId: Long): Boolean {
        val log = doseDao.activeLogFor(instanceId) ?: return false
        return deleteLog(log.id)
    }

    override suspend fun updateLog(logId: Long, actualAt: Long, intakeUnits: Double): Boolean {
        val now = clock()
        // A future time is rejected, never silently clamped to "now": clamping
        // would leave the user believing the time they picked was stored.
        if (actualAt > now) return false
        if (!intakeUnits.isFinite() || intakeUnits <= 0.0 || intakeUnits > MAX_INTAKE_UNITS) return false

        val log = doseDao.logById(logId) ?: return false
        if (log.revokedAt != null) return false

        val deviation = log.planAt?.let { ((actualAt - it) / 60_000L).toInt() }
        // Stock adjusts by the difference only; re-deducting the whole amount
        // would double-count when the user edits twice.
        val delta = intakeUnits - log.intakeUnits

        return database.withTransaction {
            doseDao.updateLog(
                id = logId,
                actualAt = actualAt,
                actualDate = TimeUtils.toDateString(actualAt),
                deviationMinutes = deviation,
                intakeUnits = intakeUnits,
            )
            if (delta != 0.0) {
                // Imported history has no local medication, so no stock to adjust.
                log.medicationId?.let { medId ->
                    if (delta > 0) medDao.decreaseStock(medId, delta, now)
                    else medDao.increaseStock(medId, -delta, now)
                }
            }
            true
        }
    }

    override suspend fun deleteLog(logId: Long): Boolean {
        val now = clock()
        val log = doseDao.logById(logId) ?: return false
        if (log.revokedAt != null) return false

        // A log attached to a plan needs its instance to still be TAKEN. If it is
        // not, something else already changed the state (say, undo on the today
        // list) and revoking here would leave the two sides disagreeing.
        val instanceId = log.instanceId
        if (instanceId != null) {
            val inst = doseDao.instanceById(instanceId) ?: return false
            if (inst.status != DoseInstanceStatus.TAKEN) return false
        }

        return database.withTransaction {
            doseDao.revokeLog(logId, now)
            if (instanceId != null) {
                doseDao.transit(
                    id = instanceId,
                    expected = DoseInstanceStatus.TAKEN,
                    next = DoseInstanceStatus.PENDING,
                    reason = null,
                    at = now,
                )
            }
            // Add the same amount back. Imported history has no medication_id.
            log.medicationId?.let { medDao.increaseStock(it, log.intakeUnits, now) }
            true
        }
    }

    override suspend fun resume(instanceId: Long): Boolean {
        val now = clock()
        val changed = doseDao.transit(
            id = instanceId,
            expected = DoseInstanceStatus.SKIPPED,
            next = DoseInstanceStatus.PENDING,
            reason = null,
            at = now,
        )
        return changed > 0
    }
}
