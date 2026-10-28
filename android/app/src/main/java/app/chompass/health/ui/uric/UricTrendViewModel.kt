package app.chompass.health.ui.uric

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.chompass.health.HealthContainer
import app.chompass.health.data.db.entity.UricRecordEntity
import app.chompass.health.data.repo.UricRepository
import app.chompass.health.domain.model.UricInputUnit
import app.chompass.health.domain.uric.AxisTick
import app.chompass.health.domain.uric.LevelResolver
import app.chompass.health.domain.uric.UricPeriod
import app.chompass.health.domain.uric.UricRange
import app.chompass.health.model.UricLevel
import app.chompass.health.util.TimeUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

/** One row of the reading list. */
data class UricRecordUi(
    val id: Long,
    val valueUmol: Int,
    val inputValue: Double,
    val inputUnit: UricInputUnit,
    val level: UricLevel,
    val measuredAt: Long,
    val methodCode: String,
    val fasting: Boolean,
    val note: String?,
)

/** One plotted reading. [fraction] is the normalized X position inside the range. */
data class UricPointUi(
    val fraction: Float,
    val valueUmol: Int,
    val level: UricLevel,
    val latest: Boolean,
)

data class UricTrendUiState(
    val range: UricRange,
    val targetUmol: Int = LevelResolver.DEFAULT_TARGET,
    /** Newest reading overall — not limited to the visible range. */
    val latest: UricRecordUi? = null,
    val records: List<UricRecordUi> = emptyList(),
    val points: List<UricPointUi> = emptyList(),
    val ticks: List<AxisTick> = emptyList(),
    /** Y-axis ceiling: at least [DEFAULT_AXIS_TOP], else the next hundred above the peak. */
    val axisTop: Int = DEFAULT_AXIS_TOP,
    /** Where "today" sits in the range; null when the range is in the past. */
    val todayFraction: Float? = null,
    val average: Int? = null,
    val highest: Int? = null,
    val lowest: Int? = null,
    val onTargetCount: Int = 0,
    val measureDays: Int = 0,
    val canGoNext: Boolean = false,
) {
    val totalDays: Int get() = range.days
    val hasRecords: Boolean get() = records.isNotEmpty()

    companion object {
        /**
         * Baseline ceiling. The clinical bands (420 / 480) have to be visible
         * without rescaling, otherwise a normal month looks like a flat line.
         */
        const val DEFAULT_AXIS_TOP = 600
    }
}

/**
 * Uric-acid trend screen state.
 *
 * Everything user-visible is derived here except the text itself: the UI layer
 * formats numbers and labels so this class stays free of `Context` and can be
 * unit-tested on the JVM.
 */
class UricTrendViewModel(
    private val repo: UricRepository,
    private val clock: () -> Long,
) : ViewModel() {

    private val today: LocalDate get() = TimeUtils.today(clock())

    private val range = MutableStateFlow(UricRange.currentMonth(today))

    private val _ui = MutableStateFlow(UricTrendUiState(range = range.value))
    val ui: StateFlow<UricTrendUiState> = _ui.asStateFlow()

    init {
        range
            .flatMapLatest { active ->
                combine(
                    repo.observeRecords(active),
                    repo.observeTarget(),
                ) { rows, target -> active to (rows to target) }
            }
            .onEach { (active, data) ->
                val (rows, target) = data
                _ui.value = toUi(active, rows, target)
            }
            .launchIn(viewModelScope)

        repo.observeLatest()
            .onEach { entity -> _ui.value = _ui.value.copy(latest = entity?.toUi(_ui.value.targetUmol)) }
            .launchIn(viewModelScope)
    }

    fun selectPeriod(period: UricPeriod) {
        if (period == UricPeriod.CUSTOM) return // opened through the date picker
        range.value = when (period) {
            UricPeriod.WEEK -> UricRange(UricPeriod.WEEK, today)
            UricPeriod.MONTH -> UricRange(UricPeriod.MONTH, today)
            UricPeriod.QUARTER -> UricRange(UricPeriod.QUARTER, today)
            UricPeriod.CUSTOM -> range.value
        }
    }

    fun selectCustomRange(start: LocalDate, end: LocalDate) {
        range.value = UricRange(
            period = UricPeriod.CUSTOM,
            anchor = end,
            customStart = start,
            customEnd = end,
        )
    }

    fun goPrevious() {
        range.value = range.value.previous()
    }

    /** Guarded by the same rule the arrow is disabled with. */
    fun goNext() {
        val current = range.value
        if (!current.pageable || !current.canGoNext(today)) return
        range.value = current.next()
    }

    fun setTarget(valueUmol: Int) {
        viewModelScope.launch { repo.setTarget(valueUmol) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repo.softDelete(id) }
    }

    private fun toUi(
        range: UricRange,
        rows: List<UricRecordEntity>,
        target: Int,
    ): UricTrendUiState {
        val records = rows.map { it.toUi(target) }
        val startMs = range.startMillis()
        val endMs = range.endMillis()
        val span = (endMs - startMs).coerceAtLeast(1L)

        val ordered = rows.sortedBy { it.measuredAt }
        val points = ordered.mapIndexed { index, row ->
            UricPointUi(
                fraction = ((row.measuredAt - startMs).toFloat() / span).coerceIn(0f, 1f),
                valueUmol = row.valueUmol,
                level = LevelResolver.resolve(row.valueUmol, target),
                latest = index == ordered.lastIndex,
            )
        }

        val values = rows.map { it.valueUmol }
        val peak = values.maxOrNull() ?: 0
        val now = clock()
        val todayFraction = if (now in startMs..endMs) {
            ((now - startMs).toFloat() / span).coerceIn(0f, 1f)
        } else {
            null
        }

        return UricTrendUiState(
            range = range,
            targetUmol = target,
            latest = _ui.value.latest,
            records = records,
            points = points,
            ticks = range.ticks(),
            axisTop = maxOf(UricTrendUiState.DEFAULT_AXIS_TOP, ((peak + 99) / 100) * 100),
            todayFraction = todayFraction,
            average = if (values.isEmpty()) null else values.average().roundToInt(),
            highest = values.maxOrNull(),
            lowest = values.minOrNull(),
            onTargetCount = values.count { it < target },
            measureDays = rows.map { it.measuredDate }.distinct().size,
            canGoNext = range.pageable && range.canGoNext(today),
        )
    }

    private fun UricRecordEntity.toUi(target: Int) = UricRecordUi(
        id = id,
        valueUmol = valueUmol,
        inputValue = inputValue,
        inputUnit = inputUnit,
        level = LevelResolver.resolve(valueUmol, target),
        measuredAt = measuredAt,
        methodCode = measureMethod.name,
        fasting = fasting,
        note = note,
    )

    class Factory(private val container: HealthContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            UricTrendViewModel(container.uricRepository, System::currentTimeMillis) as T
    }
}
