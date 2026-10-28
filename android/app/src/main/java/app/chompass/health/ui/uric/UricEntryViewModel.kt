package app.chompass.health.ui.uric

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.chompass.health.HealthContainer
import app.chompass.health.data.repo.UricInput
import app.chompass.health.data.repo.UricRepository
import app.chompass.health.domain.model.UricInputUnit
import app.chompass.health.domain.model.UricMethod
import app.chompass.health.domain.uric.UnitConverter
import app.chompass.health.ui.HealthUnits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Validation bounds, per input unit.
 *
 * μmol/L uses the storage domain directly; mg/dL is the same window converted
 * and rounded to one decimal, so the two ranges cannot drift apart.
 */
object UricEntryRules {
    private val UMOL_RANGE = 100.0..1500.0
    private val MGDL_RANGE = 1.7..25.5

    fun range(unit: UricInputUnit): ClosedFloatingPointRange<Double> = when (unit) {
        UricInputUnit.UMOL_L -> UMOL_RANGE
        UricInputUnit.MG_DL -> MGDL_RANGE
    }

    /** Endpoint text for the "enter a value between…" message. */
    fun bounds(unit: UricInputUnit): Pair<String, String> = when (unit) {
        UricInputUnit.UMOL_L -> "100" to "1500"
        UricInputUnit.MG_DL -> "1.7" to "25.5"
    }

    fun isValid(text: String, unit: UricInputUnit): Boolean =
        text.toDoubleOrNull()?.let { it in range(unit) } == true

    /** μmol/L is stored as a whole number, so it is also typed that way. */
    fun format(valueUmol: Int, inputValue: Double, unit: UricInputUnit): String = when (unit) {
        UricInputUnit.UMOL_L -> valueUmol.toString()
        UricInputUnit.MG_DL -> String.format(Locale.US, "%.1f", inputValue)
    }
}

sealed interface UricEntryError {
    data object ValueRequired : UricEntryError
    data class OutOfRange(val min: String, val max: String, val unit: String) : UricEntryError
    data object SaveFailed : UricEntryError
}

data class UricEntryUiState(
    val valueText: String = "",
    val unit: UricInputUnit = UricInputUnit.UMOL_L,
    val measuredAt: Long,
    val methodCode: String = UricMethod.FINGER.name,
    val fasting: Boolean = false,
    val note: String = "",
    /** True when editing an existing row (the title and the save path differ). */
    val editing: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: UricEntryError? = null,
) {
    val canSave: Boolean
        get() = !saving && !loading && UricEntryRules.isValid(valueText, unit)
}

/**
 * Add / edit one uric-acid reading.
 *
 * The unit is only an input affordance: whatever the user types is converted to
 * μmol/L before it reaches the repository, because the database stores one
 * canonical value per row. `inputValue` / `inputUnit` are kept so the original
 * entry can be shown back (`452 μmol/L` typed as `7.6 mg/dL`).
 */
class UricEntryViewModel(
    private val repo: UricRepository,
    private val clock: () -> Long,
    private val recordId: Long?,
) : ViewModel() {

    private val _ui = MutableStateFlow(UricEntryUiState(measuredAt = clock()))
    val ui: StateFlow<UricEntryUiState> = _ui.asStateFlow()

    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    init {
        viewModelScope.launch {
            // Unit / sample defaults come from the last saved reading, not from
            // the tap that opened this screen.
            val unit = repo.observeUnit().first()
            val method = repo.observeLastMethod().first()
            _ui.update { it.copy(unit = unit, methodCode = method.name) }
            val id = recordId?.takeIf { it > 0 }
            if (id != null) {
                _ui.update { it.copy(loading = true) }
                val entity = repo.byId(id)
                if (entity == null) {
                    _ui.update { it.copy(loading = false) }
                } else {
                    _ui.update {
                        it.copy(
                            valueText = UricEntryRules.format(
                                entity.valueUmol,
                                entity.inputValue,
                                entity.inputUnit,
                            ),
                            unit = entity.inputUnit,
                            measuredAt = entity.measuredAt,
                            methodCode = entity.measureMethod.name,
                            fasting = entity.fasting,
                            note = entity.note.orEmpty(),
                            editing = true,
                            loading = false,
                        )
                    }
                }
            }
        }
    }

    fun setValue(raw: String) {
        // Digits plus at most one decimal separator, and no runaway length.
        val filtered = buildString {
            var dotSeen = false
            for (ch in raw) {
                when {
                    ch.isDigit() -> append(ch)
                    (ch == '.' || ch == ',') && !dotSeen && isNotEmpty() -> {
                        dotSeen = true
                        append('.')
                    }
                }
            }
        }.take(7)
        _ui.update { it.copy(valueText = filtered, error = null) }
    }

    fun setUnit(unit: UricInputUnit) {
        _ui.update { state ->
            if (state.unit == unit) return@update state
            val converted = state.valueText.toDoubleOrNull()?.let { value ->
                when (unit) {
                    UricInputUnit.UMOL_L -> UnitConverter.mgdlToUmol(value).toString()
                    UricInputUnit.MG_DL ->
                        String.format(Locale.US, "%.1f", UnitConverter.umolToMgdl(value.roundToInt()))
                }
            }.orEmpty()
            state.copy(unit = unit, valueText = converted, error = null)
        }
    }

    fun setMeasuredAt(at: Long) {
        _ui.update { it.copy(measuredAt = at, error = null) }
    }

    fun setMethod(code: String) {
        _ui.update { it.copy(methodCode = code) }
    }

    fun setFasting(fasting: Boolean) {
        _ui.update { it.copy(fasting = fasting) }
    }

    fun setNote(note: String) {
        _ui.update { it.copy(note = note.take(200)) }
    }

    fun save() {
        val state = _ui.value
        if (state.saving) return
        val value = state.valueText.toDoubleOrNull()
        if (value == null || value <= 0.0) {
            _ui.update { it.copy(error = UricEntryError.ValueRequired) }
            return
        }
        if (!UricEntryRules.isValid(state.valueText, state.unit)) {
            val (min, max) = UricEntryRules.bounds(state.unit)
            _ui.update {
                it.copy(
                    error = UricEntryError.OutOfRange(min, max, HealthUnits.of(state.unit)),
                )
            }
            return
        }

        val method = UricMethod.entries.firstOrNull { it.name == state.methodCode } ?: UricMethod.FINGER
        val input = UricInput(
            value = value,
            unit = state.unit,
            measuredAt = state.measuredAt,
            method = method,
            fasting = state.fasting,
            note = state.note.takeIf { it.isNotBlank() },
        )

        _ui.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val id = recordId?.takeIf { it > 0 }
            val ok = if (id == null) {
                repo.add(input)
                true
            } else {
                repo.update(id, input)
            }
            if (ok) {
                repo.rememberMethod(method)
                _ui.update { it.copy(saving = false) }
                _done.value = true
            } else {
                _ui.update { it.copy(saving = false, error = UricEntryError.SaveFailed) }
            }
        }
    }

    class Factory(
        private val container: HealthContainer,
        private val recordId: Long?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            UricEntryViewModel(container.uricRepository, System::currentTimeMillis, recordId) as T
    }
}
