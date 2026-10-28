package app.chompass.health.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.chompass.R
import app.chompass.health.domain.model.UricInputUnit
import app.chompass.health.domain.uric.UricPeriod
import app.chompass.health.domain.uric.UricRange
import app.chompass.health.model.UricLevel
import app.chompass.health.util.TimeUtils
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Text for the health diary that depends on runtime state.
 *
 * Kept out of the domain layer on purpose: `UricRange` / `UricLevel` must stay
 * pure Kotlin (JVM unit tests, no `Context`), so every user-visible string is
 * assembled here from resources.
 */
object HealthUnits {
    /** Units are symbols, not prose — never translated. */
    const val UMOL = "μmol/L"
    const val MGDL = "mg/dL"

    fun of(unit: UricInputUnit): String = when (unit) {
        UricInputUnit.UMOL_L -> UMOL
        UricInputUnit.MG_DL -> MGDL
    }
}

@Composable
fun uricPeriodLabel(period: UricPeriod): String = stringResource(
    when (period) {
        UricPeriod.WEEK -> R.string.health_period_week
        UricPeriod.MONTH -> R.string.health_period_month
        UricPeriod.QUARTER -> R.string.health_period_quarter
        UricPeriod.CUSTOM -> R.string.health_period_custom
    }
)

/** "2026-10-01 – 2026-10-31" (the joiner is localized). */
@Composable
fun uricRangeLabel(range: UricRange): String = stringResource(
    R.string.health_range_label,
    TimeUtils.dateString(range.start),
    TimeUtils.dateString(range.end),
)

/**
 * X-axis label for one tick.
 *
 * Week is a narrow weekday letter, quarter a month abbreviation, month/custom a
 * numeric day — always formatted from the real date so a custom range spanning
 * weeks cannot drift against a hardcoded list.
 */
fun uricTickLabel(period: UricPeriod, date: LocalDate, locale: Locale = Locale.getDefault()): String =
    when (period) {
        UricPeriod.WEEK -> date.format(DateTimeFormatter.ofPattern("EEEEE", locale))
        UricPeriod.QUARTER -> date.format(DateTimeFormatter.ofPattern("MMM", locale))
        UricPeriod.MONTH, UricPeriod.CUSTOM -> date.format(DateTimeFormatter.ofPattern("MM/dd", locale))
    }

@Composable
fun uricLevelLabel(level: UricLevel): String = stringResource(
    when (level) {
        UricLevel.BELOW_TARGET -> R.string.health_level_on_target
        UricLevel.NEAR -> R.string.health_level_near
        UricLevel.HIGH -> R.string.health_level_high
        UricLevel.VERY_HIGH -> R.string.health_level_very_high
    }
)

/** "452" — μmol/L values are always whole numbers in storage. */
fun umolText(valueUmol: Int): String = valueUmol.toString()

/** mg/dL with one decimal, matching `UnitConverter.umolToMgdl`. */
fun mgdlText(mgdl: Double): String = String.format(Locale.getDefault(), "%.1f", mgdl)
