package app.chompass.health.ui.med

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.chompass.health.HealthContainer
import app.chompass.health.data.db.entity.DoseInstanceEntity
import app.chompass.health.data.db.entity.DoseLogEntity
import app.chompass.health.data.db.entity.MedicationEntity
import app.chompass.health.data.repo.MedDraft
import app.chompass.health.data.repo.MedRepository
import app.chompass.health.data.repo.MedSaveResult
import app.chompass.health.domain.dose.AdherenceCalculator
import app.chompass.health.domain.dose.DoseStateResolver
import app.chompass.health.domain.dose.StockCalculator
import app.chompass.health.domain.model.DoseInstanceStatus
import app.chompass.health.domain.model.DoseLogKind
import app.chompass.health.domain.model.MealRelation
import app.chompass.health.domain.model.MedStatus
import app.chompass.health.domain.model.SkipReason
import app.chompass.health.model.DoseAction
import app.chompass.health.model.DoseState
import app.chompass.health.model.MedCategory
import app.chompass.health.util.TimeUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/* ============================================================
 * Shared
 * ============================================================ */

/**
 * The "just marked it missed" receipt.
 *
 * Marking a dose missed is a **hand-made conclusion**, and one mis-tap would
 * otherwise remove the chance to backfill it. Five seconds of undo, then the row
 * itself keeps an "undo missed" action.
 *
 * The label is assembled by the screen from [drug] and [planText]: a Chinese
 * receipt must not survive a language switch.
 */
data class DoseUndoNotice(
    val instanceId: Long,
    val drug: String,
    val planText: String,
)

/** One as-needed medication, offered as "record a dose of this" on the today card. */
data class PrnMedUi(
    val medicationId: Long,
    val name: String,
    val strengthValue: Double?,
    val strengthUnit: String?,
    val intakeUnits: Double,
    val unitLabel: String,
)

/** The most urgent restock warning; only one is shown at a time. */
data class LowStockUi(
    val medicationId: Long,
    val name: String,
    val remainingDays: Int,
)

/**
 * One cell of the week strip.
 *
 * `MISSED` exists as its own state: a fully missed day is the single thing the
 * strip must make obvious, and folding it into `PARTIAL` means the legend's
 * "missed" never appears and the user concludes the app never records misses.
 */
enum class DayCellState { FULL, PARTIAL, MISSED, NONE }

data class WeekCellUi(
    val date: LocalDate,
    val state: DayCellState,
)

/**
 * One row of the today list.
 *
 * Everything is a number, an enum or a timestamp: "plan 08:00 · actual 09:12"
 * needs a language and a meal relation, so it is built in `MedFormat`, not here.
 */
data class DoseRowUi(
    val instanceId: Long,
    val drug: String,
    val strengthValue: Double?,
    val strengthUnit: String?,
    val intakeUnits: Double,
    val unitLabel: String,
    val meal: MealRelation,
    val state: DoseState,
    val planAt: Long,
    val actualAt: Long?,
    val skipReason: SkipReason?,
    val actions: List<DoseAction>,
) {
    /** Only used to disambiguate the undo receipt when the same drug is dosed twice a day. */
    val planText: String get() = TimeUtils.toTimeString(planAt)
}

data class MedTodayUiState(
    val loading: Boolean = true,
    val weekFrom: LocalDate = LocalDate.EPOCH,
    val weekTo: LocalDate = LocalDate.EPOCH,
    val adherencePercent: Float? = null,
    val takenCount: Int = 0,
    val plannedCount: Int = 0,
    val missedCount: Int = 0,
    val pendingCount: Int = 0,
    val weekCells: List<WeekCellUi> = emptyList(),
    val rows: List<DoseRowUi> = emptyList(),
    val prnMeds: List<PrnMedUi> = emptyList(),
    val lowStock: LowStockUi? = null,
    val undo: DoseUndoNotice? = null,
    /** Clock value the state was built with; the UI never reads the system clock. */
    val now: Long = 0L,
) {
    val isEmpty: Boolean get() = !loading && rows.isEmpty()
}

/* ============================================================
 * C1 · today
 * ============================================================ */

class MedTodayViewModel(
    private val repo: MedRepository,
    private val clock: () -> Long,
    /**
     * Re-arms the system alarms after every write. A recorded or skipped dose
     * must not leave the alarm behind: it would fire for a dose already answered.
     */
    private val onChanged: () -> Unit = {},
) : ViewModel() {

    init {
        // Roll over before generating: the other order would treat yesterday's
        // legitimate pending rows as if they had just been created.
        viewModelScope.launch {
            runCatching {
                repo.rollover()
                repo.ensureToday()
            }
        }
    }

    private val undoFlow = MutableStateFlow<DoseUndoNotice?>(null)
    private var undoJob: Job? = null

    /**
     * Exact "now" for the derived states.
     *
     * A ticker rather than a single clock read: "overdue by 2 h" on a screen that
     * has been open since this morning would otherwise stay at "overdue by 5 min".
     */
    private val clockFlow = MutableStateFlow(clock())

    val state: StateFlow<MedTodayUiState> = run {
        viewModelScope.launch {
            while (true) {
                clockFlow.value = clock()
                delay(60_000)
            }
        }
        val today = TimeUtils.today(clock())
        val from = today.minusDays(6)
        combine(
            repo.observeToday(),
            repo.observeBetween(from, today),
            undoFlow,
            clockFlow,
        ) { snapshot, weekInstances, undo, now ->
            buildState(snapshot.date, snapshot.instances, snapshot.logsByInstance, snapshot.medications, weekInstances, from, now, undo)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MedTodayUiState(),
        )
    }

    fun onAction(instanceId: Long, action: DoseAction) {
        viewModelScope.launch {
            runCatching {
                when (action) {
                    DoseAction.RECORD -> repo.record(instanceId, DoseLogKind.SCHEDULED)
                    DoseAction.BACKFILL -> repo.record(instanceId, DoseLogKind.BACKFILL)
                    DoseAction.SKIP -> repo.skip(instanceId)
                    DoseAction.MISS -> repo.markMissed(instanceId)
                    DoseAction.REOPEN_MISS -> repo.reopenMissed(instanceId)
                    DoseAction.UNDO -> repo.undo(instanceId)
                    DoseAction.RESUME -> repo.resume(instanceId)
                }
            }.onSuccess { ok ->
                onChanged()
                when {
                    // The receipt is the fast way back from a mis-tap; the real
                    // correction is REOPEN_MISS, and both take the same path.
                    action == DoseAction.MISS && ok -> showUndoMiss(instanceId)
                    action == DoseAction.REOPEN_MISS -> dismissUndo()
                }
            }
        }
    }

    /** The record sheet confirmed: the write carries the time the user picked. */
    fun recordAt(instanceId: Long, at: Long, backfill: Boolean) {
        viewModelScope.launch {
            runCatching {
                repo.record(
                    instanceId = instanceId,
                    kind = if (backfill) DoseLogKind.BACKFILL else DoseLogKind.SCHEDULED,
                    actualAt = at,
                )
            }
            onChanged()
        }
    }

    private fun showUndoMiss(instanceId: Long) {
        val row = state.value.rows.firstOrNull { it.instanceId == instanceId }
        undoJob?.cancel()
        undoFlow.value = DoseUndoNotice(
            instanceId = instanceId,
            drug = row?.drug.orEmpty(),
            planText = row?.planText.orEmpty(),
        )
        undoJob = viewModelScope.launch {
            delay(5_000)
            undoFlow.value = null
        }
    }

    fun dismissUndo() {
        undoJob?.cancel()
        undoFlow.value = null
    }

    private fun buildState(
        today: LocalDate,
        instances: List<DoseInstanceEntity>,
        logsByInstance: Map<Long, DoseLogEntity>,
        medications: List<MedicationEntity>,
        weekInstances: List<DoseInstanceEntity>,
        from: LocalDate,
        now: Long,
        undo: DoseUndoNotice?,
    ): MedTodayUiState {
        val rows = instances.map { inst ->
            val log = logsByInstance[inst.id]
            val state = DoseStateResolver.resolve(
                status = inst.status,
                planAt = inst.planAt,
                now = now,
                actualAt = log?.actualAt,
            )
            DoseRowUi(
                instanceId = inst.id,
                drug = inst.drugName,
                strengthValue = inst.strengthValue,
                strengthUnit = inst.strengthUnit,
                intakeUnits = inst.intakeUnits,
                unitLabel = inst.unitLabel,
                meal = inst.mealRelation,
                state = state,
                planAt = inst.planAt,
                actualAt = log?.actualAt,
                skipReason = inst.skipReason,
                actions = actionsFor(state),
            )
        }

        // Denominator = due and not skipped + **taken** (including early ones).
        // Filtering only on `planAt <= now` would make someone who takes their
        // pills early see "taken" rows next to an adherence of 0/2.
        val due = instances.filter {
            it.status != DoseInstanceStatus.SKIPPED &&
                (it.planAt <= now || it.status == DoseInstanceStatus.TAKEN)
        }
        val taken = due.count { it.status == DoseInstanceStatus.TAKEN }
        val missed = due.count { it.status == DoseInstanceStatus.MISSED }
        val pending = due.count { it.status == DoseInstanceStatus.PENDING }

        val byDate = weekInstances.groupBy { it.planDate }
        val cells = (0 until 7).map { offset ->
            val date = from.plusDays(offset.toLong())
            val list = byDate[TimeUtils.dateString(date)].orEmpty()
            val dayTaken = list.count { it.status == DoseInstanceStatus.TAKEN }
            val cellState = when {
                list.isEmpty() -> DayCellState.NONE
                list.all { it.status == DoseInstanceStatus.TAKEN } -> DayCellState.FULL
                // Nothing taken and everything concluded (missed / skipped) = a
                // wholly missed day.
                dayTaken == 0 && list.all {
                    it.status == DoseInstanceStatus.MISSED ||
                        it.status == DoseInstanceStatus.SKIPPED
                } -> DayCellState.MISSED

                else -> DayCellState.PARTIAL
            }
            WeekCellUi(date = date, state = cellState)
        }

        val prn = medications.filter { it.isPrn }.map {
            PrnMedUi(
                medicationId = it.id,
                name = it.name,
                strengthValue = it.strengthValue,
                strengthUnit = it.strengthUnit,
                intakeUnits = it.intakeUnits,
                unitLabel = it.unitLabel,
            )
        }

        val low = medications
            .mapNotNull { med ->
                val days = StockCalculator.remainingDays(
                    med.stockUnits, med.intakeUnits, med.freqPerDay,
                ) ?: return@mapNotNull null
                if (!StockCalculator.isLow(days, med.stockAlertDays)) return@mapNotNull null
                LowStockUi(med.id, med.name, days)
            }
            .minByOrNull { it.remainingDays }

        return MedTodayUiState(
            loading = false,
            weekFrom = from,
            weekTo = today,
            adherencePercent = AdherenceCalculator.percent(taken, due.size)?.let { it / 100f },
            takenCount = taken,
            plannedCount = due.size,
            missedCount = missed,
            pendingCount = pending,
            weekCells = cells,
            rows = rows,
            prnMeds = prn,
            lowStock = low,
            undo = undo,
            now = now,
        )
    }

    /**
     * Row actions. When a dose is overdue, "backfill" and "missed" carry equal
     * weight — the app does not decide on the user's behalf.
     */
    private fun actionsFor(state: DoseState): List<DoseAction> = when (state) {
        DoseState.PENDING -> listOf(DoseAction.RECORD, DoseAction.SKIP)
        DoseState.OVERDUE -> listOf(DoseAction.BACKFILL, DoseAction.MISS)
        DoseState.TAKEN, DoseState.LATE -> listOf(DoseAction.UNDO)
        DoseState.CANCELLED -> listOf(DoseAction.RESUME)
        DoseState.MISSED -> listOf(DoseAction.REOPEN_MISS)
        DoseState.PRN, DoseState.EXTRA -> emptyList()
    }

    class Factory(private val container: HealthContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MedTodayViewModel(
            repo = container.medRepository,
            clock = System::currentTimeMillis,
            onChanged = container.reminders::syncAsync,
        ) as T
    }
}

/* ============================================================
 * My medications
 * ============================================================ */

/** Discrete stock level; the colour comes from "is it past the threshold", not a gradient. */
enum class StockTone { NORMAL, LOW, EMPTY }

data class MedListRowUi(
    val id: Long,
    val name: String,
    val category: MedCategory,
    val isPrn: Boolean,
    val active: Boolean,
    val strengthValue: Double?,
    val strengthUnit: String?,
    val intakeUnits: Double,
    val unitLabel: String,
    val times: List<String>,
    val meal: MealRelation,
    /** Null when the medication does not track stock. */
    val stockUnits: Double? = null,
    val remainingDays: Int? = null,
    val stockTone: StockTone = StockTone.NORMAL,
)

data class MedListUiState(
    val loading: Boolean = true,
    val active: List<MedListRowUi> = emptyList(),
    val stopped: List<MedListRowUi> = emptyList(),
) {
    val isEmpty: Boolean get() = !loading && active.isEmpty() && stopped.isEmpty()
    val activeCount: Int get() = active.size
}

class MedListViewModel(repo: MedRepository) : ViewModel() {

    val state: StateFlow<MedListUiState> = combine(
        repo.observeMeds(),
        repo.observeSchedules(),
    ) { meds, schedules ->
        val timesByMed = schedules.groupBy({ it.medicationId }, { it.timeOfDay })
        val rows = meds.map { med -> toRow(med, timesByMed[med.id].orEmpty()) }
        MedListUiState(
            loading = false,
            active = rows.filter { it.active },
            stopped = rows.filterNot { it.active },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MedListUiState(),
    )

    private fun toRow(med: MedicationEntity, times: List<String>): MedListRowUi {
        val days = if (med.isPrn) {
            null
        } else {
            StockCalculator.remainingDays(
                stockUnits = med.stockUnits,
                intakeUnits = med.intakeUnits,
                freqPerDay = times.size.coerceAtLeast(med.freqPerDay),
            )
        }
        val tone = when {
            days == null -> StockTone.NORMAL
            days <= 0 -> StockTone.EMPTY
            StockCalculator.isLow(days, med.stockAlertDays) -> StockTone.LOW
            else -> StockTone.NORMAL
        }
        return MedListRowUi(
            id = med.id,
            name = med.name,
            category = med.category,
            isPrn = med.isPrn,
            active = med.status == MedStatus.ACTIVE,
            strengthValue = med.strengthValue,
            strengthUnit = med.strengthUnit,
            intakeUnits = med.intakeUnits,
            unitLabel = med.unitLabel,
            times = times,
            meal = med.mealRelation,
            stockUnits = med.stockUnits,
            remainingDays = days,
            stockTone = tone,
        )
    }

    class Factory(private val container: HealthContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MedListViewModel(
            repo = container.medRepository,
        ) as T
    }
}

/* ============================================================
 * C3 · add / edit a medication
 * ============================================================ */

/** Why a save (or a form edit) was rejected, without a message. */
sealed interface MedEditError {
    data object NameRequired : MedEditError
    data object TimesRequired : MedEditError
    data object DuplicateTime : MedEditError
    data object LastTime : MedEditError
    data object IntakeInvalid : MedEditError
    data object StrengthInvalid : MedEditError
    data object SaveFailed : MedEditError
    data object StopFailed : MedEditError
    data object LoadFailed : MedEditError
}

/**
 * Form state.
 *
 * Numbers are held as **strings**: while the user types "0." the intermediate
 * state must survive, and storing a Double would zero it on every keystroke and
 * move the caret. Validation happens once, on save.
 */
data class MedEditUiState(
    val id: Long = 0L,
    val name: String = "",
    val category: MedCategory = MedCategory.LONG_TERM,
    val isPrn: Boolean = false,
    val strengthValue: String = "",
    val strengthUnit: String = "mg",
    val intakeUnits: String = "1",
    val unitLabel: String = "",
    val mealRelation: MealRelation = MealRelation.AFTER,
    val times: List<String> = listOf("08:00"),
    val reminderEnabled: Boolean = true,
    val stockUnits: String = "",
    val stockAlertDays: Int = 14,
    val saving: Boolean = false,
    val error: MedEditError? = null,
    /** True when editing an existing medication; drives the title and "stop". */
    val isEditing: Boolean = false,
) {
    val timesPerDay: Int get() = times.size

    val canSave: Boolean
        get() = name.isNotBlank() && !saving && (isPrn || times.isNotEmpty())

    /** Expected daily use in smallest units, for the "days left" hint. */
    val dailyUsage: Double
        get() = (intakeUnits.toDoubleOrNull() ?: 0.0) * times.size.coerceAtLeast(1)

    val stockDays: Int?
        get() {
            if (isPrn) return null
            val stock = stockUnits.toDoubleOrNull() ?: return null
            val daily = dailyUsage
            if (daily <= 0.0) return null
            return (stock / daily).toInt().coerceAtLeast(0)
        }
}

private val SUGGESTED_TIMES = listOf("08:00", "12:00", "20:00", "09:00", "15:00", "22:00")

class MedEditViewModel(
    private val repo: MedRepository,
    /** Localized smallest-unit name ("tablet"), used as the form's starting value. */
    private val defaultUnitLabel: String,
    private val editingId: Long?,
    /** Re-arms alarms: new times, a stopped medication, or a new reminder switch. */
    private val onChanged: () -> Unit = {},
) : ViewModel() {

    private val _state = MutableStateFlow(MedEditUiState(unitLabel = defaultUnitLabel))
    val state: StateFlow<MedEditUiState> = _state.asStateFlow()

    init {
        if (editingId != null) {
            viewModelScope.launch {
                val detail = repo.detail(editingId)
                if (detail == null) {
                    _state.value = MedEditUiState(
                        isEditing = true,
                        error = MedEditError.LoadFailed,
                    )
                    return@launch
                }
                val med = detail.med
                _state.value = MedEditUiState(
                    id = med.id,
                    name = med.name,
                    category = med.category,
                    isPrn = med.isPrn,
                    strengthValue = med.strengthValue?.let(::medNumber).orEmpty(),
                    strengthUnit = med.strengthUnit ?: "mg",
                    intakeUnits = medNumber(med.intakeUnits),
                    unitLabel = med.unitLabel,
                    mealRelation = med.mealRelation,
                    times = if (detail.times.isEmpty()) listOf("08:00") else detail.times,
                    reminderEnabled = med.reminderEnabled && !med.isPrn,
                    stockUnits = med.stockUnits?.let(::medNumber).orEmpty(),
                    stockAlertDays = med.stockAlertDays,
                    isEditing = true,
                )
            }
        }
    }

    fun setName(v: String) = update { it.copy(name = v.take(30), error = null) }

    fun setCategory(v: MedCategory) = update { it.copy(category = v) }

    /**
     * As-needed switch. It changes **only** "has fixed times", never the
     * category: turning "long term" into "acute" is usually just a correction,
     * and auto-enabling as-needed would drop a daily medication from the list.
     */
    fun setPrn(v: Boolean) = update { s ->
        if (v) {
            s.copy(isPrn = true, reminderEnabled = false)
        } else {
            s.copy(isPrn = false, times = s.times.ifEmpty { listOf("08:00") })
        }
    }

    fun setStrengthValue(v: String) = update { it.copy(strengthValue = v) }
    fun setStrengthUnit(v: String) = update { it.copy(strengthUnit = v) }
    fun setIntakeUnits(v: String) = update { it.copy(intakeUnits = v) }
    fun setUnitLabel(v: String) = update { it.copy(unitLabel = v) }
    fun setMealRelation(v: MealRelation) = update { it.copy(mealRelation = v) }
    fun setReminder(v: Boolean) = update { it.copy(reminderEnabled = v) }
    fun setStockUnits(v: String) = update { it.copy(stockUnits = v) }
    fun setStockAlertDays(v: Int) = update { it.copy(stockAlertDays = v) }

    fun addTime(time: String) = update { s ->
        if (s.times.contains(time)) {
            s.copy(error = MedEditError.DuplicateTime)
        } else {
            s.copy(times = (s.times + time).sorted(), error = null)
        }
    }

    fun removeTime(time: String) = update { s ->
        s.copy(
            times = s.times - time,
            error = if (s.times.size <= 1) MedEditError.LastTime else null,
        )
    }

    /** Doses per day and the times move together: adding one appends a suggested time. */
    fun setTimesPerDay(count: Int) = update { s ->
        val target = count.coerceIn(1, 6)
        val next = when {
            target == s.times.size -> s.times
            target > s.times.size -> {
                val add = SUGGESTED_TIMES.filter { it !in s.times }.take(target - s.times.size)
                (s.times + add).sorted()
            }

            else -> s.times.sorted().take(target)
        }
        s.copy(times = next, error = null)
    }

    /**
     * @param onSaved medication id plus any times already past today that got a
     *   backfilled instance — the screen acknowledges those, otherwise the user
     *   meets a surprise overdue row.
     */
    fun save(onSaved: (Long, List<String>) -> Unit) {
        val cur = _state.value
        if (!cur.canSave) return
        val strength = cur.strengthValue.trim().toDoubleOrNull()
        val intake = cur.intakeUnits.trim().toDoubleOrNull()
        if (intake == null || intake <= 0.0) {
            update { it.copy(error = MedEditError.IntakeInvalid) }
            return
        }
        if (cur.strengthValue.isNotBlank() && (strength == null || strength <= 0.0)) {
            update { it.copy(error = MedEditError.StrengthInvalid) }
            return
        }
        update { it.copy(saving = true, error = null) }

        viewModelScope.launch {
            val draft = MedDraft(
                id = cur.id,
                name = cur.name,
                category = cur.category,
                isPrn = cur.isPrn,
                strengthValue = strength,
                strengthUnit = cur.strengthUnit.ifBlank { null },
                intakeUnits = intake,
                unitLabel = cur.unitLabel.ifBlank { defaultUnitLabel },
                mealRelation = cur.mealRelation,
                times = if (cur.isPrn) emptyList() else cur.times,
                reminderEnabled = !cur.isPrn && cur.reminderEnabled,
                stockUnits = cur.stockUnits.trim().toDoubleOrNull(),
                stockAlertDays = cur.stockAlertDays,
            )
            when (val result = repo.saveMed(draft)) {
                is MedSaveResult.Success -> {
                    _state.update { it.copy(saving = false) }
                    onChanged()
                    onSaved(result.id, result.backfilledPastTimes)
                }

                MedSaveResult.NameRequired -> fail(MedEditError.NameRequired)
                MedSaveResult.TimesRequired -> fail(MedEditError.TimesRequired)
                MedSaveResult.SaveFailed -> fail(MedEditError.SaveFailed)
            }
        }
    }

    fun stop(onDone: (MedEditError?) -> Unit) {
        val id = _state.value.id
        if (id == 0L) {
            onDone(MedEditError.SaveFailed)
            return
        }
        viewModelScope.launch {
            val ok = runCatching { repo.stopMed(id) }.getOrDefault(false)
            if (ok) onChanged()
            onDone(if (ok) null else MedEditError.StopFailed)
        }
    }

    private fun fail(error: MedEditError) {
        _state.update { it.copy(saving = false, error = error) }
    }

    private inline fun update(block: (MedEditUiState) -> MedEditUiState) {
        _state.value = block(_state.value)
    }

    class Factory(
        private val container: HealthContainer,
        private val defaultUnitLabel: String,
        private val editingId: Long?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MedEditViewModel(
            repo = container.medRepository,
            defaultUnitLabel = defaultUnitLabel,
            editingId = editingId,
            onChanged = container.reminders::syncAsync,
        ) as T
    }
}
