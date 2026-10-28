package app.chompass.health.ui.pain

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.chompass.AppContainer
import app.chompass.R
import app.chompass.health.domain.DictResolver
import app.chompass.health.domain.model.PainSide
import app.chompass.health.model.PainEpisode
import app.chompass.ui.components.ChompassSurfaceColumn
import app.chompass.ui.navigation.BottomNavScrollPadding
import app.chompass.ui.navigation.BottomOverlayPadding
import app.chompass.ui.theme.success
import app.chompass.ui.theme.warning

/**
 * B1 — the pain section of the Health tab.
 *
 * Reading order matches the uric section: current status → the way into the
 * patterns page → the individual records. Two things are specific to pain:
 *
 * - **"Record end" lives on the row**, not on a global button. Polyarticular gout
 *   can run two flares at once, and a screen-level button would have to ask
 *   "which one?" — the row already answers it.
 * - **"Better / worse" opens a sheet** instead of writing straight away. Over a
 *   three-day flare "yesterday evening was the worst" is a fact worth recording,
 *   and a tap that stamps the clock throws it away.
 */
@Composable
fun PainSection(
    container: AppContainer,
    onOpenEpisode: (Long) -> Unit,
    onOpenInsight: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val vm: PainListViewModel = viewModel(
        factory = PainListViewModel.Factory(container.healthDiary),
    )
    val ui by vm.state.collectAsStateWithLifecycle()
    val dict by container.healthDiary.dictResolver.collectAsStateWithLifecycle(
        initialValue = DictResolver(),
    )

    // Two overlays, both attached to one episode: which row was tapped is what
    // decides which record changes.
    var changing by remember { mutableStateOf<NrsRequest?>(null) }
    var ending by remember { mutableStateOf<PainEpisode?>(null) }
    var endError by remember { mutableStateOf<PainError?>(null) }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ui.error?.let { error ->
                item {
                    ErrorRow(text = painErrorMessage(error), onDismiss = vm::dismissError)
                }
            }
            item { StatusCard(ui = ui, dict = dict) }
            item {
                InsightCard(
                    ui = ui,
                    dict = dict,
                    onOpen = onOpenInsight,
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.health_pain_records_title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (!ui.isEmpty) {
                        Text(
                            stringResource(R.string.health_pain_records_count, ui.ongoing.size + ui.history.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (ui.isEmpty) {
                item {
                    ChompassSurfaceColumn {
                        Text(
                            stringResource(R.string.health_pain_records_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.health_pain_records_empty_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                item {
                    ChompassSurfaceColumn(padding = 0.dp) {
                        ui.ongoing.forEachIndexed { index, episode ->
                            OngoingRow(
                                episode = episode,
                                dict = dict,
                                now = ui.now,
                                onOpen = { onOpenEpisode(episode.id) },
                                onRequestNrs = { preset ->
                                    changing = NrsRequest(episode, preset, null)
                                },
                                onEnd = {
                                    endError = null
                                    ending = episode
                                },
                            )
                            if (index != ui.ongoing.lastIndex || ui.history.isNotEmpty()) {
                                HorizontalDivider()
                            }
                        }
                        ui.history.forEachIndexed { index, episode ->
                            HistoryRow(
                                episode = episode,
                                dict = dict,
                                now = ui.now,
                                onOpen = { onOpenEpisode(episode.id) },
                            )
                            if (index != ui.history.lastIndex) HorizontalDivider()
                        }
                    }
                }
                item {
                    Text(
                        stringResource(R.string.health_pain_end_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    Text(
                        stringResource(R.string.health_pain_disclaimer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { Spacer(Modifier.height(BottomNavScrollPadding)) }
        }

        // Floating, not an item: inserting it into the column would push the whole
        // page up and drop it back five seconds later.
        ui.undo?.let { undo ->
            PainUndoBar(
                text = stringResource(
                    R.string.health_pain_undo_message,
                    undo.nrs,
                    painTimeLabel(undo.at, ui.now),
                ),
                actionText = stringResource(R.string.action_undo),
                onAction = vm::undoLastChange,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        // Above the FAB's band, otherwise the pill covers the button.
                        bottom = BottomOverlayPadding + 64.dp,
                    ),
            )
        }
    }

    changing?.let { request ->
        PainNrsSheet(
            episode = request.episode,
            siteLabel = painSiteLabel(dict, request.episode.siteCode, request.episode.side),
            initialNrs = request.presetNrs,
            initialAt = request.at,
            now = ui.now,
            onDismiss = { changing = null },
            onConfirm = { at, nrs ->
                changing = null
                vm.recordNrs(request.episode.id, nrs, at)
            },
        )
    }

    ending?.let { episode ->
        PainEndSheet(
            episode = episode,
            siteLabel = painSiteLabel(dict, episode.siteCode, episode.side),
            now = ui.now,
            onDismiss = {
                ending = null
                endError = null
            },
            onConfirm = { endedAt ->
                // Stay open on failure: closing the sheet would leave the user
                // with a row that still says "flaring" and no explanation.
                vm.endEpisode(episode.id, endedAt) { failure ->
                    endError = failure
                    if (failure == null) ending = null
                }
            },
            error = endError,
        )
    }
}

/** One "record a change" request: which episode, which preselected score, when. */
private data class NrsRequest(val episode: PainEpisode, val presetNrs: Int, val at: Long?)

/* ============================================================
 * Cards
 * ============================================================ */

@Composable
private fun StatusCard(ui: PainListUiState, dict: DictResolver) {
    ChompassSurfaceColumn {
        Text(
            stringResource(R.string.health_pain_status_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            ui.ongoing.isEmpty() -> {
                Text(
                    stringResource(R.string.health_pain_status_none),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(R.string.health_pain_status_none_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.health_pain_status_none_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ui.ongoing.size == 1 -> {
                val episode = ui.ongoing.first()
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        stringResource(R.string.health_pain_status_ongoing),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(
                            R.string.health_pain_status_ongoing_for,
                            painDurationText(episode.startedAt, ui.now),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
                Text(
                    stringResource(
                        R.string.health_pain_status_start,
                        painTimeLabel(episode.startedAt, ui.now),
                        painSiteLabel(dict, episode.siteCode, episode.side),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // The **current** score, not the starting one: the card answers
                // "how is it now", and a frozen start value reads as "the app
                // never noticed the changes I logged".
                Text(
                    stringResource(
                        R.string.health_pain_status_nrs,
                        episode.currentNrs,
                        painTriggerLabel(dict, episode.triggerCodes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (episode.hasUpdates) {
                    Text(
                        stringResource(
                            R.string.health_pain_status_delta,
                            painDeltaText(episode.startNrs, episode.currentNrs),
                            episode.timeline.size - 1,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> {
                // Several flares at once: the card reports how many and how long,
                // the per-site detail stays in the list below.
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        stringResource(R.string.health_pain_status_ongoing),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.health_pain_status_sites, ui.ongoing.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    stringResource(
                        R.string.health_pain_status_longest,
                        ui.longestOngoing?.let { painDurationText(it.startedAt, ui.now) } ?: "",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                // Site labels are built first: `joinToString` takes a non-inline
                // transform, so a `stringResource` cannot live inside it.
                val siteNames = ui.ongoing
                    .map { painSiteLabel(dict, it.siteCode, it.side) }
                    .joinToString(" · ")
                Text(
                    stringResource(R.string.health_pain_status_sites_list, siteNames),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.health_pain_status_peak, ui.ongoingPeakNrs ?: 0),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The way into the patterns page, with the site breakdown on the card itself.
 *
 * Shown even with no data: the page used to be reachable only when records
 * existed, so a new user never learned it was there.
 */
@Composable
private fun InsightCard(ui: PainListUiState, dict: DictResolver, onOpen: () -> Unit) {
    ChompassSurfaceColumn {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.health_pain_insight_card_title),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpen) {
                Text(stringResource(R.string.health_pain_insight_open))
            }
        }
        if (ui.siteStats.isEmpty()) {
            Text(
                stringResource(R.string.health_pain_insight_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            SiteBarChart(
                stats = ui.siteStats,
                scaleMax = ui.siteStatsMax,
                dict = dict,
            )
        }
    }
}

/** Horizontal bars, all scaled to the busiest site so the ranking is readable. */
@Composable
private fun SiteBarChart(stats: List<SiteStat>, scaleMax: Int, dict: DictResolver) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        stats.forEach { stat ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    painSiteLabel(dict, stat.siteCode, PainSide.NA),
                    modifier = Modifier.width(112.dp),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(stat.count.toFloat() / scaleMax)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(stat.count.toString(), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/* ============================================================
 * Rows
 * ============================================================ */

/**
 * A running flare: tinted, with its own three actions.
 *
 * "Record end" is the row's primary action (filled), "better / worse" are the
 * process marks (outlined). The hierarchy is carried by the fill, not by the
 * position: the filled block on the right is what the eye lands on first.
 */
@Composable
private fun OngoingRow(
    episode: PainEpisode,
    dict: DictResolver,
    now: Long,
    onOpen: () -> Unit,
    onRequestNrs: (preset: Int) -> Unit,
    onEnd: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    painSiteLabel(dict, episode.siteCode, episode.side),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    painTriggerLabel(dict, episode.triggerCodes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            if (episode.hasUpdates) {
                stringResource(
                    R.string.health_pain_row_ongoing_changes,
                    episode.currentNrs,
                    painDurationText(episode.startedAt, now),
                    episode.timeline.size - 1,
                )
            } else {
                stringResource(
                    R.string.health_pain_row_ongoing,
                    episode.currentNrs,
                    painDurationText(episode.startedAt, now),
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NrsStepButton(
                icon = Icons.Filled.Remove,
                text = stringResource(R.string.health_pain_better),
                tint = MaterialTheme.colorScheme.success,
                // At 0 or 10 the button greys out: a tap that does nothing reads
                // as a broken button, so the boundary has to be visible.
                enabled = episode.currentNrs > 0,
                onClick = { onRequestNrs(episode.currentNrs - 1) },
            )
            NrsStepButton(
                icon = Icons.Filled.Add,
                text = stringResource(R.string.health_pain_worse),
                tint = MaterialTheme.colorScheme.warning,
                enabled = episode.currentNrs < 10,
                onClick = { onRequestNrs(episode.currentNrs + 1) },
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.heightIn(min = 48.dp).clickable(onClick = onEnd),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.health_pain_record_end),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/** 48 dp of touch target around a 34 dp pill. */
@Composable
private fun NrsStepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    tint: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val ink = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val stroke = if (enabled) {
        MaterialTheme.colorScheme.outline
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Box(
        Modifier.heightIn(min = 48.dp).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
                .border(1.dp, stroke, RoundedCornerShape(18.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = ink)
            Text(text, style = MaterialTheme.typography.labelMedium, color = ink)
        }
    }
}

/**
 * A finished flare.
 *
 * Shows the **peak**, not the starting score: a flare that ran 6 → 9 must not be
 * summarised as a 6, that erases the worst hour of it.
 */
@Composable
private fun HistoryRow(
    episode: PainEpisode,
    dict: DictResolver,
    now: Long,
    onOpen: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                painSiteLabel(dict, episode.siteCode, episode.side),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(
                    R.string.health_pain_row_ended,
                    painTimeLabel(episode.startedAt, now),
                    painTriggerLabel(dict, episode.triggerCodes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.health_pain_row_nrs, episode.peakNrs),
                style = MaterialTheme.typography.bodyMedium,
                color = painNrsColor(episode.peakNrs),
            )
            Text(
                painDurationText(episode.startedAt, episode.endedAt ?: now),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/* ============================================================
 * Small pieces
 * ============================================================ */

@Composable
private fun ErrorRow(text: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_ok))
            }
        }
    }
}

/** The receipt for a just-recorded mark; it carries the action, so it never auto-hides alone. */
@Composable
private fun PainUndoBar(
    text: String,
    actionText: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAction) { Text(actionText) }
        }
    }
}

/** One message per business-rule failure; the ViewModel only carries the enum. */
@Composable
internal fun painErrorMessage(error: PainError): String = when (error) {
    PainError.EndBeforeStart -> stringResource(R.string.health_pain_error_end_before_start)
    PainError.EndInFuture -> stringResource(R.string.health_pain_error_end_future)
    PainError.SaveFailed -> stringResource(R.string.health_pain_error_save)
}
