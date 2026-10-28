package app.chompass.health.ui.med

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import app.chompass.R
import app.chompass.health.domain.dose.DoseStateResolver
import app.chompass.health.domain.model.MealRelation
import app.chompass.health.domain.model.SkipReason
import app.chompass.health.model.DoseAction
import app.chompass.health.model.DoseState
import app.chompass.health.ui.pain.painDurationMinutesText
import app.chompass.health.util.TimeUtils
import app.chompass.ui.theme.success
import app.chompass.ui.theme.warning
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs

/**
 * Text for the medication module.
 *
 * Same rule as `HealthFormat` and `PainFormat`: the ViewModels carry numbers,
 * enums and timestamps only, and every string is assembled here from resources.
 * The unit labels ("tablet", "capsule") are the exception in the other direction:
 * they are **stored** with each medication and each log, because changing the app
 * language must not rewrite the amount the user actually took.
 */

/** 0.5 -> "0.5", 2.0 -> "2". Never localized: these are numerals, not prose. */
fun medNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

/** "0.5 g"; null when the medication has no strength set. */
fun medStrengthText(value: Double?, unit: String?): String? =
    if (value != null && unit != null) "${medNumber(value)} $unit" else null

/**
 * The amount line on a log row.
 *
 * With a strength the per-unit content leads and the count is a suffix —
 * "0.5 g" (one tablet) / "0.5 g × 2" — because changing the count has to be
 * visible on the row. One unit gets no suffix: that is the common case, and
 * "× 1" on every line is noise.
 */
fun medDoseText(
    strengthValue: Double?,
    strengthUnit: String?,
    intakeUnits: Double,
    unitLabel: String,
): String {
    val strength = medStrengthText(strengthValue, strengthUnit)
    return if (strength != null) {
        if (intakeUnits == 1.0) strength else "$strength × ${medNumber(intakeUnits)}"
    } else {
        "${medNumber(intakeUnits)} $unitLabel"
    }
}

/** "40 mg · each 1 tablet" for the medication list. */
@Composable
fun medSpecText(
    strengthValue: Double?,
    strengthUnit: String?,
    intakeUnits: Double,
    unitLabel: String,
): String {
    val perDose = stringResource(R.string.health_med_per_dose, medNumber(intakeUnits), unitLabel)
    val strength = medStrengthText(strengthValue, strengthUnit)
    return if (strength == null) perDose else "$strength · $perDose"
}

@Composable
fun medMealLabel(relation: MealRelation): String? = when (relation) {
    MealRelation.BEFORE -> stringResource(R.string.health_med_meal_before)
    MealRelation.AFTER -> stringResource(R.string.health_med_meal_after)
    MealRelation.NONE -> null
}

/* ============================================================
 * States
 * ============================================================ */

@Composable
fun medStateLabel(state: DoseState): String = stringResource(
    when (state) {
        DoseState.PENDING -> R.string.health_med_state_pending
        DoseState.TAKEN -> R.string.health_med_state_taken
        DoseState.LATE -> R.string.health_med_state_late
        DoseState.OVERDUE -> R.string.health_med_state_overdue
        DoseState.MISSED -> R.string.health_med_state_missed
        DoseState.PRN -> R.string.health_med_state_prn
        DoseState.CANCELLED -> R.string.health_med_state_cancelled
        DoseState.EXTRA -> R.string.health_med_state_extra
    }
)

/**
 * Colour carries the meaning of the state only: amber = a time problem,
 * red = a missed dose, green = taken, blue = next up / as needed.
 */
@Composable
fun medStateColor(state: DoseState): Color = when (state) {
    DoseState.TAKEN -> MaterialTheme.colorScheme.success
    DoseState.LATE -> MaterialTheme.colorScheme.warning
    DoseState.OVERDUE -> MaterialTheme.colorScheme.warning
    DoseState.MISSED -> MaterialTheme.colorScheme.error
    DoseState.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    DoseState.PENDING, DoseState.PRN, DoseState.EXTRA -> MaterialTheme.colorScheme.primary
}

@Composable
fun medActionLabel(action: DoseAction): String = stringResource(
    when (action) {
        DoseAction.RECORD -> R.string.health_med_action_record
        DoseAction.SKIP -> R.string.health_med_action_skip
        DoseAction.BACKFILL -> R.string.health_med_action_backfill
        DoseAction.MISS -> R.string.health_med_action_miss
        DoseAction.UNDO -> R.string.action_undo
        DoseAction.RESUME -> R.string.health_med_action_resume
        DoseAction.REOPEN_MISS -> R.string.health_med_action_reopen
    }
)

@Composable
private fun medSkipReason(skipReason: SkipReason?, isPrn: Boolean): String = when (skipReason) {
    SkipReason.SCHEDULE_REMOVED -> stringResource(R.string.health_med_skip_schedule_removed)
    SkipReason.DOCTOR_STOP -> stringResource(R.string.health_med_skip_doctor_stop)
    else -> stringResource(
        if (isPrn) R.string.health_med_meta_prn else R.string.health_med_skip_user,
    )
}

/* ============================================================
 * Meta lines
 * ============================================================ */

/** "08:00 · after meals" — the plan time with its meal relation, when there is one. */
@Composable
fun medPlanTimeText(planAt: Long, meal: MealRelation): String {
    val time = TimeUtils.toTimeString(planAt)
    val mealLabel = medMealLabel(meal)
    return if (mealLabel == null) time else "$time · $mealLabel"
}

/**
 * C1's secondary line: the plan side of the dose.
 *
 * A taken dose reports **both** times: "plan 08:00 · actual 09:12". Reporting
 * only the actual time hides that it was late, and reporting only the plan hides
 * what was actually recorded.
 */
@Composable
fun medTodayMeta(
    state: DoseState,
    planAt: Long,
    meal: MealRelation,
    actualAt: Long?,
    skipReason: SkipReason?,
): String {
    val plan = medPlanTimeText(planAt, meal)
    return when (state) {
        DoseState.TAKEN, DoseState.LATE -> {
            val actual = actualAt?.let(TimeUtils::toTimeString) ?: "--:--"
            stringResource(R.string.health_med_meta_plan_actual, plan, actual)
        }

        DoseState.PENDING -> stringResource(R.string.health_med_meta_not_due, plan)
        DoseState.MISSED -> stringResource(R.string.health_med_meta_not_recorded, plan)
        DoseState.OVERDUE -> stringResource(R.string.health_med_meta_overdue_now, plan)
        DoseState.CANCELLED -> stringResource(R.string.health_med_meta_cancelled, plan, medSkipReason(skipReason, false))
        DoseState.PRN, DoseState.EXTRA -> plan
    }
}

/**
 * The third line on a C1 row: how far off the clock this dose is.
 *
 * `null` when there is nothing to add — a dose taken on time needs no "0 min
 * late" badge.
 */
@Composable
fun medTodayExtra(
    state: DoseState,
    planAt: Long,
    actualAt: Long?,
    now: Long,
): String? = when (state) {
    DoseState.LATE -> medDeviationText(DoseStateResolver.deviationMinutes(planAt, actualAt))
    DoseState.TAKEN -> {
        val deviation = DoseStateResolver.deviationMinutes(planAt, actualAt)
        // A clearly early dose is worth stating, otherwise the record looks wrong.
        if (deviation != null && deviation < -DoseStateResolver.ON_TIME_TOLERANCE_MIN) {
            medDeviationText(deviation)
        } else {
            null
        }
    }

    DoseState.OVERDUE -> {
        val minutes = DoseStateResolver.overdueMinutes(planAt, now)
        stringResource(
            R.string.health_med_meta_overdue,
            painDurationMinutesText(minutes),
        )
    }

    else -> null
}

/** "late by 44 min" / "early by 35 min"; null when there is no deviation. */
@Composable
fun medDeviationText(deviationMinutes: Int?): String? {
    if (deviationMinutes == null) return null
    val amount = painDurationMinutesText(abs(deviationMinutes))
    return if (deviationMinutes > 0) {
        stringResource(R.string.health_med_meta_late, amount)
    } else {
        stringResource(R.string.health_med_meta_early, amount)
    }
}

/**
 * C2's secondary line: always the **plan** first, so the two views of the same
 * data stay comparable, then whatever happened.
 */
@Composable
fun medLogMeta(
    state: DoseState,
    planAt: Long?,
    meal: MealRelation,
    actualAt: Long?,
    skipReason: SkipReason?,
    now: Long,
): String = when (state) {
    DoseState.PRN -> stringResource(R.string.health_med_meta_prn)
    DoseState.EXTRA -> stringResource(R.string.health_med_meta_extra)
    else -> if (planAt == null) {
        stringResource(R.string.health_med_meta_actual_only, actualAt?.let(TimeUtils::toTimeString) ?: "--:--")
    } else {
        val plan = medPlanTimeText(planAt, meal)
        when (state) {
            DoseState.TAKEN, DoseState.LATE -> {
                val actual = actualAt?.let(TimeUtils::toTimeString) ?: "--:--"
                val base = stringResource(R.string.health_med_meta_plan_actual, plan, actual)
                val deviation = medDeviationText(DoseStateResolver.deviationMinutes(planAt, actualAt))
                if (state == DoseState.LATE && deviation != null) "$base · $deviation" else base
            }

            DoseState.OVERDUE -> stringResource(
                R.string.health_med_meta_overdue,
                painDurationMinutesText(DoseStateResolver.overdueMinutes(planAt, now)),
            ).let { stringResource(R.string.health_med_meta_plan_overdue, plan, it) }

            DoseState.PENDING -> stringResource(R.string.health_med_meta_not_due, plan)
            DoseState.MISSED -> stringResource(R.string.health_med_meta_not_recorded, plan)
            DoseState.CANCELLED -> stringResource(
                R.string.health_med_meta_cancelled,
                plan,
                medSkipReason(skipReason, false),
            )

            DoseState.PRN, DoseState.EXTRA -> plan
        }
    }
}

/* ============================================================
 * Dates and ranges
 * ============================================================ */

private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)

fun medDateText(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    date.format(SHORT_DATE.withLocale(locale))

/** Single-letter weekday for the week strip (M / T / W … — localized). */
fun medWeekdayLetter(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    date.format(DateTimeFormatter.ofPattern("EEEEE", locale))

/** "10/28 · today" / "10/27 · yesterday" / "10/26". */
@Composable
fun medDayLabel(date: LocalDate, today: LocalDate): String {
    val text = medDateText(date)
    return when (date) {
        today -> stringResource(R.string.health_med_date_today, text)
        today.minusDays(1) -> stringResource(R.string.health_med_date_yesterday, text)
        else -> text
    }
}

/** The window in numbers: "10/22 – 10/28" (a joiner, not prose). */
fun medRangeText(from: LocalDate, to: LocalDate): String =
    "${medDateText(from)} – ${medDateText(to)}"

@Composable
fun medPlanLine(times: List<String>, meal: MealRelation): String {
    if (times.isEmpty()) return stringResource(R.string.health_med_plan_prn)
    val daily = stringResource(R.string.health_med_plan_daily, times.size)
    val mealLabel = medMealLabel(meal)
    return buildString {
        append(daily)
        append(" · ")
        append(times.joinToString(" / "))
        if (mealLabel != null) append(" · $mealLabel")
    }
}
