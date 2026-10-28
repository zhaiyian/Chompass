package app.chompass.health.ui.pain

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.chompass.R
import app.chompass.health.model.PainEpisode
import kotlin.math.roundToInt

/**
 * B7 — record one change in pain level.
 *
 * The entry point is the "better / worse" button on a running flare's row; those
 * buttons only decide the **preselected score** (current ∓ 1). Time and score are
 * both confirmed here.
 *
 * Why this has to be a form and not "tap, write, done": a flare can run for two
 * or three days, and people remember "yesterday afternoon was the worst" after
 * the fact. Stamping the system clock turns that into "it got bad this morning" —
 * a shifted timeline, which is worse than no timeline, because the doctor reads
 * the order.
 */
@Composable
fun PainNrsSheet(
    episode: PainEpisode,
    siteLabel: String,
    /** Preselected score: better passes current − 1, worse current + 1, backfill current. */
    initialNrs: Int,
    /**
     * Preselected time; null = now.
     *
     * A finished flare must pass its `endedAt`: "now" is already past the end, so
     * the sheet would open with a greyed-out save button and read as broken.
     */
    initialAt: Long?,
    now: Long,
    onDismiss: () -> Unit,
    onConfirm: (at: Long, nrs: Int) -> Unit,
) {
    var at by remember(initialAt) { mutableStateOf(initialAt ?: now) }
    var nrs by remember { mutableStateOf(initialNrs.coerceIn(0, 10)) }

    val upper = episode.endedAt ?: now
    val rangeError: String? = when {
        at < episode.startedAt ->
            stringResource(R.string.health_pain_nrs_before_start, painTimeLabel(episode.startedAt, now))
        at > upper && episode.endedAt != null ->
            stringResource(R.string.health_pain_nrs_after_end, painTimeLabel(episode.endedAt, now))
        at > upper -> stringResource(R.string.health_pain_nrs_after_now)
        else -> null
    }

    // Compared against the **last point before the chosen time**, not against the
    // current score: when backfilling yesterday, yesterday's value is the one that
    // answers "did it get better since then".
    val previous = episode.timeline.lastOrNull { it.at < at }
        ?: episode.timeline.firstOrNull()
    val previousNrs = previous?.nrs ?: episode.startNrs
    val previousLabel = previous?.let { painTimeLabel(it.at, now) }
        ?: stringResource(R.string.health_pain_nrs_trend_start)
    val delta = nrs - previousNrs
    val trendText = when {
        delta > 0 -> stringResource(R.string.health_pain_nrs_trend_worse, delta, previousLabel)
        delta < 0 -> stringResource(R.string.health_pain_nrs_trend_better, -delta, previousLabel)
        else -> stringResource(R.string.health_pain_nrs_trend_same, previousLabel)
    }
    val trendColor = painDeltaColor(delta)

    PainSheetFrame(
        title = stringResource(R.string.health_pain_nrs_sheet_title),
        onDismiss = onDismiss,
    ) {
        PainSheetSubtitle(
            stringResource(
                R.string.health_pain_nrs_sheet_subtitle,
                siteLabel,
                painTimeLabel(episode.startedAt, now),
            ),
        )

        PainFieldLabel(stringResource(R.string.health_time_label))
        PainDateTimeRow(at = at, onChange = { at = it })

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PainFieldLabel(stringResource(R.string.health_pain_entry_nrs), Modifier.weight(1f))
            Text(
                stringResource(R.string.health_pain_entry_nrs_value, nrs, painNrsWord(nrs)),
                style = MaterialTheme.typography.bodyMedium,
                color = painNrsColor(nrs),
            )
        }
        Slider(
            value = nrs.toFloat(),
            onValueChange = { nrs = it.roundToInt() },
            valueRange = 0f..10f,
            steps = 9,
        )
        Text(
            painNrsGuide(nrs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // The trend is a derived preview, not one more thing to pick.
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = trendColor.copy(alpha = 0.12f),
        ) {
            Text(
                trendText,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = trendColor,
            )
        }

        Text(
            rangeError ?: stringResource(R.string.health_pain_nrs_sheet_hint),
            style = MaterialTheme.typography.bodySmall,
            color = if (rangeError != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        PainSheetActions(
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = onDismiss,
            primaryText = stringResource(R.string.action_save),
            onPrimary = { onConfirm(at, nrs) },
            primaryEnabled = rangeError == null,
        )
    }
}

/**
 * "Record end": default to now, but let it be corrected.
 *
 * The end time is a required field, and one mis-tap costs more than one extra
 * confirmation, so this never writes on open.
 */
@Composable
fun PainEndSheet(
    episode: PainEpisode,
    siteLabel: String,
    now: Long,
    onDismiss: () -> Unit,
    onConfirm: (endedAt: Long) -> Unit,
    /** Write failure from the repository; the sheet stays open so the fix is one edit away. */
    error: PainError? = null,
) {
    var endedAt by remember { mutableStateOf(now) }

    PainSheetFrame(
        title = stringResource(R.string.health_pain_end_sheet_title),
        onDismiss = onDismiss,
    ) {
        PainSheetSubtitle(
            stringResource(
                R.string.health_pain_end_sheet_site,
                siteLabel,
                painTimeLabel(episode.startedAt, now),
            ),
        )

        PainFieldLabel(stringResource(R.string.health_pain_entry_end))
        PainDateTimeRow(at = endedAt, onChange = { endedAt = it })

        PainHelper(
            stringResource(
                R.string.health_pain_end_sheet_running,
                painDurationText(episode.startedAt, endedAt),
            ),
        )
        Text(
            stringResource(R.string.health_pain_end_sheet_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // A migrated or imported row can have an end before its start; the end
        // sheet must not be the place that writes one.
        val invalid = endedAt <= episode.startedAt
        if (invalid) {
            Text(
                stringResource(R.string.health_pain_error_end_before_start),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else if (error != null) {
            Text(
                painErrorMessage(error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(2.dp))
        PainSheetActions(
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = onDismiss,
            primaryText = stringResource(R.string.health_pain_end_confirm),
            onPrimary = { onConfirm(endedAt) },
            primaryEnabled = !invalid,
        )
    }
}
