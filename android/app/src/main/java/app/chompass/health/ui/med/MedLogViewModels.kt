package app.chompass.health.ui.med

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.chompass.health.HealthContainer
import app.chompass.health.data.db.entity.DoseInstanceEntity
import app.chompass.health.data.db.entity.DoseLogEntity
import app.chompass.health.data.repo.LogWindow
import app.chompass.health.data.repo.MedRepository
import app.chompass.health.domain.dose.AdherenceCalculator
import app.chompass.health.domain.dose.DoseStateResolver
import app.chompass.health.domain.model.DoseInstanceStatus
import app.chompass.health.domain.model.DoseLogKind
import app.chompass.health.domain.model.MealRelation
import app.chompass.health.domain.model.MedStatus
import app.chompass.health.domain.model.SkipReason
import app.chompass.health.model.DoseState
import app.chompass.health.util.TimeUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/* ============================================================
 * C2 · dose log
 * ============================================================ */

/**
 * How far back the log screen looks.
 *
 * The three presets answer "how have the last few days been"; the reason a
 * chronic-condition record exists is more often "how was it last winter", so the
 * custom window is the exit hatch.
 *
 * `CUSTOM` carries `days = 0` on purpose: the range comes from the two dates the
 * user picked, and the zero makes it obvious in code that forgetting to handle
 * `CUSTOM` yields an empty window instead of silently degrading to a preset.
 */
enum class LogRange(val days: Int) { TODAY(1), WEEK(7), MONTH(30), CUSTOM(0) }

/**
 * Range -> first and last date, both inclusive.
 *
 * A pure function so it can be asserted in a JVM test: being off by one day does
 * not crash, it just makes the first record of a month disappear, which is
 * invisible on screen.
 *
 * `CUSTOM`'s two ends: swapped if given backwards, and both null falls back to
 * the last 30 days — `from > to` must never reach SQL, where it would quietly
 * return an empty list.
 */
internal fun resolveWindow(
    range: LogRange,
    start: LocalDate?,
    end: LocalDate?,
    today: LocalDate,
): Pair<LocalDate, LocalDate> = when (range) {
    LogRange.CUSTOM -> {
        val a = start ?: today.minusDays(29)
        val b = end ?: today
        if (a <= b) a to b else b to a
    }

    else -> today.minusDays((range.days - 1).toLong()) to today
}

/**
 * One log row. Times always come from what actually happened; a row that has no
 * actual time yet (pending / overdue / missed / cancelled) falls back to the plan.
 */
data class MedLogEntryUi(
    val key: String,
    val drug: String,
    val strengthValue: Double?,
    val strengthUnit: String?,
    /** The amount taken, from the log's own snapshot when there is one. */
    val intakeUnits: Double,
    val unitLabel: String,
    val state: DoseState,
    /** Plan time; null for as-needed / extra doses. */
    val planAt: Long?,
    val meal: MealRelation,
    val skipReason: SkipReason?,
    /** Sort key and edit-sheet initial value. */
    val actualAt: Long,
    val instanceId: Long? = null,
    /** `dose_log.id`; null means this row is only a plan. */
    val logId: Long? = null,
) {
    /**
     * Only a row backed by a real log can be edited or deleted.
     *
     * Overdue / missed / cancelled rows are **plans**, not records: they have no
     * `dose_log`, and deleting something that does not exist would make the user
     * believe they removed data. Those states are closed from the today list.
     */
    val canEdit: Boolean get() = logId != null
}

/**
 * A day's worth of rows.
 *
 * The counts are kept as numbers so the header line can be assembled in the UI:
 * "planned 3 · taken 2" vs "3 as-needed / extra doses".
 */
data class MedLogGroupUi(
    val date: LocalDate?,
    val entries: List<MedLogEntryUi>,
    /** Instances planned that day, skipped ones excluded. */
    val plannedCount: Int,
    val takenCount: Int,
    /** Entries with no instance behind them (as-needed / extra / imported history). */
    val extraCount: Int,
) {
    val hasPlan: Boolean get() = plannedCount > 0
}

data class MedLogUiState(
    val range: LogRange = LogRange.TODAY,
    val from: LocalDate = LocalDate.EPOCH,
    val to: LocalDate = LocalDate.EPOCH,
    val loading: Boolean = true,
    val plannedCount: Int = 0,
    val takenCount: Int = 0,
    val overdueCount: Int = 0,
    val pendingCount: Int = 0,
    val extraCount: Int = 0,
    val groups: List<MedLogGroupUi> = emptyList(),
    val adherencePercent: Int? = null,
    /** Clock value the rows were derived with; the UI never reads the system clock. */
    val now: Long = 0L,
) {
    val isEmpty: Boolean get() = !loading && groups.isEmpty()

    /** A custom range has no paging: it is re-picked in the calendar. */
    val isCustom: Boolean get() = range == LogRange.CUSTOM
}

/** Why an edit or delete was rejected; the screen owns the wording. */
sealed interface MedLogError {
    /** Time in the future, or an amount outside 0–100. */
    data object UpdateRejected : MedLogError

    /** The log was already revoked, or the instance is no longer in "taken". */
    data object DeleteFailed : MedLogError
}

/**
 * C2.
 *
 * The double view of the same data is deliberate: C1 sorts by **plan** time
 * ("what is still to do today"), this one by **actual** time ("what did I
 * actually take"). Sorting this screen by plan time would hide a dose taken four
 * hours late behind one taken on time but scheduled later.
 */
class MedLogViewModel(
    private val repo: MedRepository,
    private val clock: () -> Long,
    /**
     * Re-arms the alarms. Deleting a log puts its instance back to pending, so
     * the reminder has to exist again; without this the dose goes unannounced.
     */
    private val onChanged: () -> Unit = {},
) : ViewModel() {

    /** One query = the preset plus the optional custom ends, so the two never disagree mid-update. */
    private data class Query(
        val range: LogRange,
        val start: LocalDate? = null,
        val end: LocalDate? = null,
    )

    private val queryFlow = MutableStateFlow(Query(LogRange.TODAY))

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<MedLogUiState> = queryFlow.flatMapLatest { query ->
        val today = TimeUtils.today(clock())
        val (from, to) = resolveWindow(query.range, query.start, query.end, today)
        repo.observeWindow(from, to).map { window ->
            build(query.range, window, today, from, to)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MedLogUiState(),
    )

    /**
     * Switch preset.
     *
     * `CUSTOM` intentionally does nothing here: it is not a range, it is the
     * action "go pick dates", which the screen runs before calling
     * [selectCustomRange]. Switching first would leave the user on an empty
     * custom window after cancelling — indistinguishable from lost data.
     */
    fun selectRange(range: LogRange) {
        if (range == LogRange.CUSTOM) return
        queryFlow.value = Query(range)
    }

    fun selectCustomRange(start: LocalDate, end: LocalDate) {
        queryFlow.value = Query(LogRange.CUSTOM, start, end)
    }

    fun updateLog(logId: Long, actualAt: Long, intakeUnits: Double, onDone: (MedLogError?) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching { repo.updateLog(logId, actualAt, intakeUnits) }
                .getOrDefault(false)
            if (ok) onChanged()
            onDone(if (ok) null else MedLogError.UpdateRejected)
        }
    }

    fun deleteLog(logId: Long, onDone: (MedLogError?) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching { repo.deleteLog(logId) }.getOrDefault(false)
            if (ok) onChanged()
            onDone(if (ok) null else MedLogError.DeleteFailed)
        }
    }

    private fun build(
        range: LogRange,
        window: LogWindow,
        today: LocalDate,
        from: LocalDate,
        to: LocalDate,
    ): MedLogUiState {
        val now = clock()
        val logsByInstance = window.logs
            .filter { it.instanceId != null }
            .associateBy { it.instanceId!! }

        // Summary, an exclusive split: taken + overdue + pending = due.
        val due = window.instances.filter {
            it.status != DoseInstanceStatus.SKIPPED &&
                (it.planAt <= now || it.status == DoseInstanceStatus.TAKEN)
        }
        val taken = due.count { it.status == DoseInstanceStatus.TAKEN }
        val overdue = due.count { inst ->
            inst.status == DoseInstanceStatus.MISSED ||
                (
                    inst.status == DoseInstanceStatus.PENDING &&
                        now > inst.planAt + DoseStateResolver.ON_TIME_TOLERANCE_MIN * 60_000L
                    )
        }
        val pending = due.size - taken - overdue
        val extraLogs = window.logs.filter { it.instanceId == null }

        data class Row(val dateKey: String, val sortKey: Long, val ui: MedLogEntryUi)

        val rows = mutableListOf<Row>()
        window.instances.forEach { inst ->
            val log = logsByInstance[inst.id]
            val state = DoseStateResolver.resolve(
                status = inst.status,
                planAt = inst.planAt,
                now = now,
                actualAt = log?.actualAt,
            )
            rows += Row(
                dateKey = log?.actualDate ?: inst.planDate,
                sortKey = log?.actualAt ?: inst.planAt,
                ui = MedLogEntryUi(
                    key = "i${inst.id}",
                    drug = inst.drugName,
                    strengthValue = log?.strengthValue ?: inst.strengthValue,
                    strengthUnit = log?.strengthUnit ?: inst.strengthUnit,
                    // The log's snapshot wins: after a strength change the history
                    // must still show the amount that was actually swallowed.
                    intakeUnits = log?.intakeUnits ?: inst.intakeUnits,
                    unitLabel = log?.unitLabel ?: inst.unitLabel,
                    state = state,
                    planAt = inst.planAt,
                    meal = inst.mealRelation,
                    skipReason = inst.skipReason,
                    actualAt = log?.actualAt ?: inst.planAt,
                    instanceId = inst.id,
                    logId = log?.id,
                ),
            )
        }
        extraLogs.forEach { log ->
            val prn = log.kind == DoseLogKind.PRN
            rows += Row(
                dateKey = log.actualDate,
                sortKey = log.actualAt,
                ui = MedLogEntryUi(
                    key = "l${log.id}",
                    drug = log.drugName,
                    strengthValue = log.strengthValue,
                    strengthUnit = log.strengthUnit,
                    intakeUnits = log.intakeUnits,
                    unitLabel = log.unitLabel,
                    state = if (prn) DoseState.PRN else DoseState.EXTRA,
                    planAt = null,
                    meal = MealRelation.NONE,
                    skipReason = null,
                    actualAt = log.actualAt,
                    instanceId = null,
                    logId = log.id,
                ),
            )
        }

        val groups = rows
            .groupBy { it.dateKey }
            .toSortedMap(compareByDescending { it })
            .map { (dateKey, list) ->
                val date = runCatching { LocalDate.parse(dateKey) }.getOrNull()
                val dayInstances = window.instances.filter { it.planDate == dateKey }
                MedLogGroupUi(
                    date = date,
                    entries = list.sortedByDescending { it.sortKey }.map { it.ui },
                    plannedCount = dayInstances.count { it.status != DoseInstanceStatus.SKIPPED },
                    takenCount = dayInstances.count { it.status == DoseInstanceStatus.TAKEN },
                    extraCount = list.count { it.ui.instanceId == null },
                )
            }

        return MedLogUiState(
            range = range,
            from = from,
            to = to,
            loading = false,
            plannedCount = due.size,
            takenCount = taken,
            overdueCount = overdue,
            pendingCount = pending,
            extraCount = extraLogs.size,
            groups = groups,
            adherencePercent = AdherenceCalculator.percent(taken, due.size),
            now = now,
        )
    }

    class Factory(private val container: HealthContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MedLogViewModel(
            repo = container.medRepository,
            clock = System::currentTimeMillis,
            onChanged = container.reminders::syncAsync,
        ) as T
    }
}

/* ============================================================
 * C4 · record a single dose (no plan behind it)
 * ============================================================ */

data class MedPickUi(
    val id: Long,
    val name: String,
    val strengthValue: Double?,
    val strengthUnit: String?,
    /** Amount per dose; taken from the medication and not editable here. */
    val intakeUnits: Double,
    val unitLabel: String,
    val isPrn: Boolean,
)

sealed interface ExtraDoseError {
    data object FutureTime : ExtraDoseError
    data object SaveFailed : ExtraDoseError
}

data class ExtraDoseUiState(
    val meds: List<MedPickUi> = emptyList(),
    val selectedId: Long? = null,
    val actualAt: Long = 0L,
    val note: String = "",
    val saving: Boolean = false,
    val error: ExtraDoseError? = null,
    val now: Long = 0L,
) {
    val selected: MedPickUi? get() = meds.firstOrNull { it.id == selectedId }
    val canSave: Boolean get() = selectedId != null && !saving

    /** As-needed medications record PRN; a scheduled one taken again records EXTRA. */
    val kind: DoseLogKind
        get() = if (selected?.isPrn == true) DoseLogKind.PRN else DoseLogKind.EXTRA
}

/**
 * C4.
 *
 * Unlike "record / backfill" on a C1 row, this writes a log with **no**
 * `instance_id`: it changes no plan state and never enters the adherence
 * denominator. Merging the two into one form would force the user to answer
 * "does this count as that scheduled dose?" — a question the app can ask by
 * showing two separate entry points instead.
 */
class MedExtraDoseViewModel(
    private val repo: MedRepository,
    private val clock: () -> Long,
) : ViewModel() {

    private val _state = MutableStateFlow(ExtraDoseUiState(actualAt = clock(), now = clock()))
    val state: StateFlow<ExtraDoseUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repo.observeMeds().collect { meds ->
                val active = meds.filter { it.status == MedStatus.ACTIVE }.map { med ->
                    MedPickUi(
                        id = med.id,
                        name = med.name,
                        strengthValue = med.strengthValue,
                        strengthUnit = med.strengthUnit,
                        intakeUnits = med.intakeUnits,
                        unitLabel = med.unitLabel,
                        isPrn = med.isPrn,
                    )
                }
                _state.update { s ->
                    val keep = s.selectedId?.takeIf { id -> active.any { it.id == id } }
                    s.copy(
                        meds = active,
                        selectedId = keep ?: active.firstOrNull()?.id,
                    )
                }
            }
        }
    }

    fun select(id: Long) {
        _state.update { it.copy(selectedId = id, error = null) }
    }

    /**
     * Set the intake time.
     *
     * A future time is **rejected with a message**, never clamped to now: once
     * the date can be changed, "I picked tomorrow" is the easiest slip, and
     * silently rewriting the value would leave the user believing it was stored.
     */
    fun setActualAt(at: Long) {
        val now = clock()
        _state.update {
            if (at > now) {
                it.copy(error = ExtraDoseError.FutureTime)
            } else {
                it.copy(actualAt = at, error = null)
            }
        }
    }

    fun setNote(note: String) {
        _state.update { it.copy(note = note.take(200)) }
    }

    /** The two quick presets resolve "now" through the injected clock, not the UI. */
    fun useNow() {
        val now = clock()
        _state.update { it.copy(actualAt = now, now = now, error = null) }
    }

    fun useHourAgo() {
        val now = clock()
        _state.update { it.copy(actualAt = now - 60 * 60 * 1000L, now = now, error = null) }
    }

    fun save(onSaved: () -> Unit) {
        val cur = _state.value
        val medId = cur.selectedId ?: return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val ok = runCatching {
                repo.recordExtra(
                    medicationId = medId,
                    actualAt = cur.actualAt,
                    kind = cur.kind,
                    note = cur.note.ifBlank { null },
                )
            }.getOrDefault(false)
            if (ok) {
                _state.update { it.copy(saving = false) }
                onSaved()
            } else {
                _state.update { it.copy(saving = false, error = ExtraDoseError.SaveFailed) }
            }
        }
    }

    class Factory(private val container: HealthContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MedExtraDoseViewModel(
            repo = container.medRepository,
            clock = System::currentTimeMillis,
        ) as T
    }
}
