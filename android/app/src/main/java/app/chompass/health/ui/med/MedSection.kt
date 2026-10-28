package app.chompass.health.ui.med

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.chompass.health.model.DoseAction
import app.chompass.health.model.DoseState
import app.chompass.ui.components.ChompassSurfaceColumn
import app.chompass.ui.navigation.BottomNavScrollPadding
import app.chompass.ui.navigation.BottomOverlayPadding
import app.chompass.ui.theme.success
import app.chompass.ui.theme.warning

/**
 * C1 — today's medication plan.
 *
 * Reading order: what needs buying → how the day (and week) is going → what is
 * left to take → the as-needed shelf. The adherence card comes before the list
 * because it answers the question the list raises ("am I keeping up?").
 *
 * Actions are per row, never global: "record end / mark missed" without the row
 * would have to ask which dose, and the row already answers it.
 */
@Composable
fun MedSection(
    container: AppContainer,
    onOpenLog: () -> Unit,
    onManageMeds: () -> Unit,
    onRecordExtra: (medicationId: Long?) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val vm: MedTodayViewModel = viewModel(
        factory = MedTodayViewModel.Factory(container.healthDiary),
    )
    val ui by vm.state.collectAsStateWithLifecycle()

    // Opening the tab is the one moment the diary database is certainly warm, so
    // this is where a missed re-arm (process killed between writes, app updated
    // without a reboot broadcast) gets healed. Idempotent, and it also tops up
    // today's instances and tomorrow's day tick.
    LaunchedEffect(Unit) { container.healthDiary.reminders.syncAsync() }

    // Which row's record sheet is open; `backfill` only changes the sheet's title.
    var recording by remember { mutableStateOf<RecordingRequest?>(null) }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ui.lowStock?.let { low ->
                item { LowStockBanner(low = low, onRestock = onManageMeds) }
            }
            item { AdherenceCard(ui = ui, onOpenLog = onOpenLog) }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.health_med_list_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            stringResource(R.string.health_med_list_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // The only permanent way into the medication list: the stock
                    // banner and the empty card appear only in their own states.
                    TextButton(onClick = onManageMeds) {
                        Text(stringResource(R.string.health_med_manage))
                    }
                }
            }
            if (ui.rows.isEmpty()) {
                item {
                    ChompassSurfaceColumn {
                        Text(
                            stringResource(R.string.health_med_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.health_med_empty_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.health_med_empty_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onManageMeds) {
                            Text(stringResource(R.string.health_med_manage))
                        }
                    }
                }
            } else {
                item {
                    ChompassSurfaceColumn(padding = 0.dp) {
                        ui.rows.forEachIndexed { index, row ->
                            DoseRow(
                                row = row,
                                now = ui.now,
                                onAction = { action ->
                                    when (action) {
                                        // Record / backfill need the actual time
                                        // confirmed; the rest apply immediately.
                                        DoseAction.RECORD -> recording = RecordingRequest(row, false)
                                        DoseAction.BACKFILL -> recording = RecordingRequest(row, true)
                                        else -> vm.onAction(row.instanceId, action)
                                    }
                                },
                            )
                            if (index != ui.rows.lastIndex) HorizontalDivider()
                        }
                    }
                }
            }
            if (ui.prnMeds.isNotEmpty()) {
                item {
                    ChompassSurfaceColumn {
                        Text(
                            stringResource(R.string.health_med_prn_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ui.prnMeds.forEach { prn ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        prn.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        stringResource(
                                            R.string.health_med_prn_hint,
                                            medStrengthText(prn.strengthValue, prn.strengthUnit)
                                                ?: medDoseText(
                                                    null,
                                                    null,
                                                    prn.intakeUnits,
                                                    prn.unitLabel,
                                                ),
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { onRecordExtra(prn.medicationId) }) {
                                    Text(stringResource(R.string.health_med_prn_action))
                                }
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.health_med_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { Spacer(Modifier.height(BottomNavScrollPadding)) }
        }

        // Floats above the FAB's band instead of being an item: inserting it would
        // push the whole page up and drop it five seconds later.
        ui.undo?.let { undo ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = BottomOverlayPadding + 64.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                tonalElevation = 3.dp,
            ) {
                Row(
                    Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        // Naming the drug and the planned time matters when the
                        // same medication is taken twice a day.
                        if (undo.drug.isBlank()) {
                            stringResource(R.string.health_med_undo_missed_plain)
                        } else {
                            stringResource(
                                R.string.health_med_undo_missed,
                                undo.drug,
                                undo.planText,
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.onAction(undo.instanceId, DoseAction.REOPEN_MISS) }) {
                        Text(stringResource(R.string.action_undo))
                    }
                }
            }
        }
    }

    recording?.let { request ->
        DoseRecordSheet(
            row = request.row,
            backfill = request.backfill,
            now = ui.now,
            onDismiss = { recording = null },
            onConfirm = { at ->
                recording = null
                vm.recordAt(request.row.instanceId, at, request.backfill)
            },
        )
    }
}

private data class RecordingRequest(val row: DoseRowUi, val backfill: Boolean)

/* ============================================================
 * Cards
 * ============================================================ */

@Composable
private fun LowStockBanner(low: LowStockUi, onRestock: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.warning.copy(alpha = 0.14f),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (low.remainingDays <= 0) {
                    stringResource(R.string.health_med_low_stock_empty, low.name)
                } else {
                    stringResource(R.string.health_med_low_stock_days, low.name, low.remainingDays)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRestock) {
                Text(stringResource(R.string.health_med_low_stock_action))
            }
        }
    }
}

@Composable
private fun AdherenceCard(ui: MedTodayUiState, onOpenLog: () -> Unit) {
    ChompassSurfaceColumn {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.health_med_adherence_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenLog) {
                Text(stringResource(R.string.health_med_adherence_details))
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                ui.adherencePercent?.let { "${(it * 100).toInt()}%" }
                    ?: stringResource(R.string.health_med_none_yet),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                // Deliberately "doses taken", not "doses taken on time": a dose
                // taken late still counts, and the row below says how late.
                stringResource(R.string.health_med_adherence_count, ui.takenCount, ui.plannedCount),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            stringResource(
                R.string.health_med_adherence_breakdown,
                ui.takenCount,
                ui.missedCount,
                ui.pendingCount,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.health_med_adherence_denominator, ui.plannedCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        WeekStrip(cells = ui.weekCells)
        WeekLegend()
        HorizontalDivider()
        Text(
            stringResource(R.string.health_med_week_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Seven cells, oldest first, each a weekday letter over a state-coloured block. */
@Composable
private fun WeekStrip(cells: List<WeekCellUi>) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        cells.forEach { cell ->
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    medWeekdayLetter(cell.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(weekCellColor(cell.state)),
                )
            }
        }
    }
}

@Composable
private fun weekCellColor(state: DayCellState) = when (state) {
    DayCellState.FULL -> MaterialTheme.colorScheme.success
    DayCellState.PARTIAL -> MaterialTheme.colorScheme.warning.copy(alpha = 0.55f)
    DayCellState.MISSED -> MaterialTheme.colorScheme.error
    DayCellState.NONE -> MaterialTheme.colorScheme.surfaceVariant
}

@Composable
private fun WeekLegend() {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendSwatch(weekCellColor(DayCellState.FULL), stringResource(R.string.health_med_week_legend_full))
        LegendSwatch(
            weekCellColor(DayCellState.PARTIAL),
            stringResource(R.string.health_med_week_legend_partial),
        )
        LegendSwatch(
            weekCellColor(DayCellState.MISSED),
            stringResource(R.string.health_med_week_legend_missed),
        )
    }
}

@Composable
private fun LegendSwatch(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/* ============================================================
 * Rows
 * ============================================================ */

@Composable
private fun DoseRow(row: DoseRowUi, now: Long, onAction: (DoseAction) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MedStateChip(row.state)
            Spacer(Modifier.width(8.dp))
            Text(
                row.drug,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                medDoseText(row.strengthValue, row.strengthUnit, row.intakeUnits, row.unitLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val extra = medTodayExtra(row.state, row.planAt, row.actualAt, now)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                medTodayMeta(row.state, row.planAt, row.meal, row.actualAt, row.skipReason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (extra != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    extra,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (row.state) {
                        DoseState.OVERDUE, DoseState.MISSED -> MaterialTheme.colorScheme.warning
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        if (row.actions.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                row.actions.forEach { action ->
                    InlineAction(action = action, onClick = { onAction(action) })
                }
            }
        }
    }
}

/** State chip: colour + word, never colour alone. */
@Composable
fun MedStateChip(state: DoseState) {
    val color = medStateColor(state)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.14f),
    ) {
        Text(
            medStateLabel(state),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

/**
 * Row action.
 *
 * "Record" is filled and "backfill" tonal so the primary of each state is
 * obvious, while "skip / missed / undo" stay as text: they are corrections, not
 * the next step.
 */
@Composable
private fun InlineAction(action: DoseAction, onClick: () -> Unit) {
    val filled = action == DoseAction.RECORD
    val tonal = action == DoseAction.BACKFILL
    val label = medActionLabel(action)
    if (filled || tonal) {
        Surface(
            modifier = Modifier
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(20.dp))
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(20.dp),
            color = if (filled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (filled) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                )
            }
        }
    } else {
        TextButton(onClick = onClick) { Text(label) }
    }
}
