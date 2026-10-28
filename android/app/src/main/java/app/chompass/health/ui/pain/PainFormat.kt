package app.chompass.health.ui.pain

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import app.chompass.R
import app.chompass.health.domain.DictResolver
import app.chompass.health.domain.model.DictType
import app.chompass.health.domain.model.PainSide
import app.chompass.health.util.TimeUtils
import app.chompass.ui.theme.success
import app.chompass.ui.theme.warning

/**
 * Text for the pain module that depends on runtime state.
 *
 * Same rule as [app.chompass.health.ui.HealthFormat]: `PainEpisode` carries
 * numbers and codes only, and every user-visible string is assembled here from
 * resources. A site label needs the dictionary, a duration needs a language, and
 * "today / yesterday" needs a clock — none of that belongs in a data class that
 * outlives a language switch.
 */

@Composable
fun painSideLabel(side: PainSide): String = when (side) {
    PainSide.LEFT -> stringResource(R.string.health_side_left)
    PainSide.RIGHT -> stringResource(R.string.health_side_right)
    PainSide.BOTH -> stringResource(R.string.health_side_both)
    /** Spine / "can't say" record no side; an empty prefix keeps the label honest. */
    PainSide.NA -> ""
}

/** "Left big toe · base" — the side prefix is only added where the joint records one. */
@Composable
fun painSiteLabel(dict: DictResolver, siteCode: String, side: PainSide): String {
    val base = dict.label(DictType.SITE, siteCode).text
    if (base.isBlank()) return ""
    if (side == PainSide.NA) return base
    return stringResource(R.string.health_site_side, painSideLabel(side), base)
}

/**
 * "Today 08:15" / "Yesterday 22:10" / "09/16 22:10".
 *
 * [now] is passed in rather than read here: a composable that reads the system
 * clock cannot be previewed or tested, and the ViewModel already owns one.
 */
@Composable
fun painTimeLabel(at: Long, now: Long): String {
    val date = TimeUtils.toLocalDate(at)
    val today = TimeUtils.toLocalDate(now)
    return when (date) {
        today -> stringResource(R.string.health_time_today, TimeUtils.toTimeString(at))
        today.minusDays(1) -> stringResource(
            R.string.health_time_yesterday,
            TimeUtils.toTimeString(at),
        )
        else -> TimeUtils.toMonthDayTime(at)
    }
}

/** "8 h" / "45 min" — the coarse form used in rows and status lines. */
@Composable
fun painDurationText(from: Long, to: Long): String {
    val minutes = ((to - from).coerceAtLeast(0L) / 60_000L).toInt()
    return if (minutes >= 60) {
        stringResource(R.string.health_duration_hours, minutes / 60)
    } else {
        stringResource(R.string.health_duration_minutes, minutes)
    }
}

/**
 * "8 h 53 min" / "2 days 4 h".
 *
 * Minutes are dropped when they are zero: an average of exactly two hours reads
 * "2 h", not "2 h 0 min" — the zero is noise in a number whose whole job is to
 * be roughly right.
 */
@Composable
fun painDurationMinutesText(minutes: Int): String = when {
    minutes < 60 -> stringResource(R.string.health_duration_minutes, minutes)
    minutes < 24 * 60 -> {
        val hours = minutes / 60
        val rest = minutes % 60
        if (rest == 0) {
            stringResource(R.string.health_duration_hours, hours)
        } else {
            stringResource(R.string.health_duration_hours_minutes, hours, rest)
        }
    }
    else -> {
        val days = minutes / (24 * 60)
        val rest = (minutes % (24 * 60)) / 60
        if (rest == 0) {
            stringResource(R.string.health_duration_days, days)
        } else {
            stringResource(R.string.health_duration_days_hours, days, rest)
        }
    }
}

/** The four NRS bands, as words. */
@Composable
fun painNrsWord(nrs: Int): String = when {
    nrs == 0 -> stringResource(R.string.health_nrs_none)
    nrs <= 3 -> stringResource(R.string.health_nrs_mild)
    nrs <= 6 -> stringResource(R.string.health_nrs_moderate)
    else -> stringResource(R.string.health_nrs_severe)
}

/**
 * One action line per band. Shown in the score card only, never repeated in list
 * rows: a diary that lectures on every row gets swiped past.
 */
@Composable
fun painNrsGuide(nrs: Int): String = when {
    nrs == 0 -> stringResource(R.string.health_nrs_guide_none)
    nrs <= 3 -> stringResource(R.string.health_nrs_guide_mild)
    nrs <= 6 -> stringResource(R.string.health_nrs_guide_moderate)
    else -> stringResource(R.string.health_nrs_guide_severe)
}

/** Warm pair only: the bands are about severity, not about the theme accent. */
@Composable
fun painNrsColor(nrs: Int): Color =
    if (nrs >= 6) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.warning

/** Easing is the one good direction in this module, so it gets the success colour. */
@Composable
fun painDeltaColor(delta: Int): Color = when {
    delta > 0 -> MaterialTheme.colorScheme.error
    delta < 0 -> MaterialTheme.colorScheme.success
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** "Trigger: beer + seafood" / "Trigger: no clear cause" / "No trigger recorded". */
@Composable
fun painTriggerLabel(dict: DictResolver, codes: List<String>): String = when {
    codes.isEmpty() -> stringResource(R.string.health_pain_trigger_none_recorded)
    codes == listOf(TRIGGER_NONE) -> stringResource(R.string.health_pain_trigger_explicit_none)
    else -> stringResource(
        R.string.health_pain_trigger_prefix,
        codes.map { dict.label(DictType.TRIGGER, it).text }.joinToString(" + "),
    )
}

/** Both endpoints are printed, not just the arrow: "6 → 4" is unambiguous, "−2" is not. */
@Composable
fun painDeltaText(from: Int, to: Int): String = if (from == to) {
    stringResource(R.string.health_pain_delta_same, from, to)
} else {
    stringResource(R.string.health_pain_delta_pair, from, to)
}

/** Dictionary code for "no clear trigger"; it is excluded from the trigger ranking. */
const val TRIGGER_NONE = "NONE"
