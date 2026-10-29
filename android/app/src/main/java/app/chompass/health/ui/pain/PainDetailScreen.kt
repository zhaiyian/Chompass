package app.chompass.health.ui.pain

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.chompass.AppContainer
import app.chompass.R
import app.chompass.health.domain.DictResolver
import app.chompass.health.domain.model.DictType
import app.chompass.health.model.PainEpisode
import app.chompass.health.model.PainNrsPoint
import app.chompass.health.ui.HealthCard
import app.chompass.health.ui.HealthDisclosure

/**
 * B3 — one flare, in full.
 *
 * Read-only on purpose: correcting a value belongs to the form, and a screen
 * that both shows and edits turns every mis-tap into a write. The two actions it
 * does own are the irreversible ones — **delete** (which needs the whole summary
 * on screen to be sure) and **record end** for a running flare.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PainDetailScreen(
    container: AppContainer,
    episodeId: Long,
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    val vm: PainDetailViewModel = viewModel(
        factory = PainDetailViewModel.Factory(container.healthDiary, episodeId),
    )
    val ui by vm.state.collectAsStateWithLifecycle()
    val dict by container.healthDiary.dictResolver.collectAsStateWithLifecycle(
        initialValue = DictResolver(),
    )

    var confirmDelete by remember { mutableStateOf(false) }
    var endSheetOpen by remember { mutableStateOf(false) }
    var endError by remember { mutableStateOf<PainError?>(null) }
    var deleted by remember { mutableStateOf(false) }

    LaunchedEffect(deleted) { if (deleted) onBack() }

    val episode = ui.episode
    // Deleted from the edit form, or a stale link: leave instead of showing an
    // empty page with a back arrow. Waits for `loading` so the initial frame does
    // not pop before the first read lands.
    LaunchedEffect(ui.loading, episode) {
        if (!ui.loading && episode == null) onBack()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.health_pain_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_cancel),
                        )
                    }
                },
                actions = {
                    if (episode != null) {
                        TextButton(onClick = onEdit) {
                            Text(stringResource(R.string.health_pain_detail_edit))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        if (episode == null) {
            // Deleting or a stale link: the LaunchedEffect above pops the screen.
            Column(Modifier.fillMaxSize().padding(padding)) { }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                HeaderCard(episode = episode, dict = dict, now = ui.now)
                TimeCard(episode = episode, now = ui.now)
                TimelineCard(
                    episode = episode,
                    now = ui.now,
                    onRemove = vm::removeUpdate,
                )
                DetailsCard(episode = episode, dict = dict)
                HealthDisclosure(stringResource(R.string.health_pain_detail_disclaimer))
                if (episode.ongoing) {
                    TextButton(
                        onClick = {
                            endError = null
                            endSheetOpen = true
                        },
                    ) {
                        Text(stringResource(R.string.health_pain_record_end))
                    }
                }
                TextButton(onClick = { confirmDelete = true }) {
                    Text(
                        stringResource(R.string.health_pain_detail_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (episode != null && endSheetOpen) {
        PainEndSheet(
            episode = episode,
            siteLabel = painSiteLabel(dict, episode.siteCode, episode.side),
            now = ui.now,
            onDismiss = {
                endSheetOpen = false
                endError = null
            },
            onConfirm = { endedAt ->
                vm.endEpisode(endedAt) { failure ->
                    endError = failure
                    if (failure == null) endSheetOpen = false
                }
            },
            error = endError,
        )
    }

    if (episode != null && confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.health_pain_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.health_pain_delete_body,
                        painSiteLabel(dict, episode.siteCode, episode.side),
                        painTimeLabel(episode.startedAt, ui.now),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        vm.delete { deleted = true }
                    },
                ) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun HeaderCard(episode: PainEpisode, dict: DictResolver, now: Long) {
    HealthCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                painSiteLabel(dict, episode.siteCode, episode.side),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            StatusChip(ongoing = episode.ongoing)
        }
        Text(
            stringResource(R.string.health_pain_detail_since, painTimeLabel(episode.startedAt, now)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    R.string.health_pain_detail_start_peak,
                    episode.startNrs,
                    episode.peakNrs,
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.health_pain_row_nrs, episode.currentNrs),
                style = MaterialTheme.typography.bodyMedium,
                color = painNrsColor(episode.currentNrs),
            )
        }
    }
}

@Composable
private fun StatusChip(ongoing: Boolean) {
    val color = if (ongoing) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val ink = if (ongoing) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(14.dp), color = color) {
        Text(
            stringResource(
                if (ongoing) R.string.health_pain_status_ongoing_chip
                else R.string.health_pain_status_ended_chip,
            ),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = ink,
        )
    }
}

@Composable
private fun TimeCard(episode: PainEpisode, now: Long) {
    HealthCard {
        Text(
            stringResource(R.string.health_pain_detail_time),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DetailRow(
            label = stringResource(R.string.health_pain_detail_started_at),
            value = painTimeLabel(episode.startedAt, now),
        )
        DetailRow(
            label = stringResource(R.string.health_pain_detail_ended_at),
            value = episode.endedAt?.let { painTimeLabel(it, now) }
                ?: stringResource(R.string.health_pain_detail_still_ongoing),
        )
        DetailRow(
            label = stringResource(R.string.health_pain_detail_duration),
            value = painDurationText(episode.startedAt, episode.endedAt ?: now),
        )
    }
}

@Composable
private fun TimelineCard(
    episode: PainEpisode,
    now: Long,
    onRemove: (Long) -> Unit,
) {
    HealthCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.health_pain_timeline_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (episode.hasUpdates) {
                Text(
                    stringResource(R.string.health_pain_timeline_count, episode.timeline.size - 1),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        episode.timeline.forEachIndexed { index, point ->
            val previous = episode.timeline.getOrNull(index - 1)
            TimelineRow(
                point = point,
                previousNrs = previous?.nrs,
                now = now,
                // The starting point is the episode's own score, not a mark: it
                // cannot be removed without deleting the record.
                onRemove = if (point.isStart) null else ({ onRemove(point.updateId) }),
            )
        }
        Text(
            stringResource(if (episode.hasUpdates) R.string.health_pain_timeline_tip
            else R.string.health_pain_timeline_tip_flat),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TimelineRow(
    point: PainNrsPoint,
    previousNrs: Int?,
    now: Long,
    onRemove: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (point.isStart) {
                stringResource(R.string.health_pain_timeline_start)
            } else {
                painTimeLabel(point.at, now)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            stringResource(R.string.health_pain_row_nrs, point.nrs),
            style = MaterialTheme.typography.bodyMedium,
            color = painNrsColor(point.nrs),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            painNrsWord(point.nrs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (previousNrs != null) {
            Text(
                painDeltaText(previousNrs, point.nrs),
                style = MaterialTheme.typography.bodySmall,
                color = painDeltaColor(point.nrs - previousNrs),
            )
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.health_pain_timeline_remove),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DetailsCard(episode: PainEpisode, dict: DictResolver) {
    HealthCard {
        Text(
            stringResource(R.string.health_pain_detail_records),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DetailRow(
            label = stringResource(R.string.health_pain_detail_nature),
            value = episode.natureCodes
                .map { dict.label(DictType.PAIN_NATURE, it).text }
                .filter { it.isNotBlank() }
                .joinToString(" · ")
                .ifBlank { stringResource(R.string.health_pain_detail_none) },
        )
        DetailRow(
            label = stringResource(R.string.health_pain_detail_trigger),
            value = painTriggerLabel(dict, episode.triggerCodes),
        )
        DetailRow(
            label = stringResource(R.string.health_pain_detail_affect),
            value = affectText(episode),
        )
        if (episode.note.isNotBlank()) {
            DetailRow(
                label = stringResource(R.string.health_pain_detail_note),
                value = episode.note,
            )
        }
    }
}

/** both / walking only / sleep only / none — four cases, one line. */
@Composable
private fun affectText(episode: PainEpisode): String = when {
    episode.affectWalk && episode.affectSleep -> stringResource(
        R.string.health_pain_detail_affect_both,
        stringResource(R.string.health_pain_detail_affect_walk_short),
        stringResource(R.string.health_pain_detail_affect_sleep_short),
    )

    episode.affectWalk -> stringResource(R.string.health_pain_detail_affect_walk_short)
    episode.affectSleep -> stringResource(R.string.health_pain_detail_affect_sleep_short)
    else -> stringResource(R.string.health_pain_detail_affect_none)
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}
