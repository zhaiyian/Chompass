package app.chompass.health.ui.med

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDateRangePickerState
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
import app.chompass.health.model.DoseState
import app.chompass.health.util.TimeUtils
import app.chompass.health.ui.HealthCard
import app.chompass.health.ui.HealthSectionHeader
import app.chompass.ui.navigation.BottomNavScrollPadding
import app.chompass.ui.theme.success
import app.chompass.ui.theme.warning

/**
 * C2 — the dose log.
 *
 * The mirror image of C1 on purpose: this screen sorts by **actual** time and
 * answers "what did I take", while the today list sorts by plan time and answers
 * "what is left". Same rows, two questions.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MedLogScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onRecordExtra: () -> Unit,
) {
    val vm: MedLogViewModel = viewModel(
        factory = MedLogViewModel.Factory(container.healthDiary),
    )
    val ui by vm.state.collectAsStateWithLifecycle()

    var showRangePicker by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MedLogEntryUi?>(null) }
    var editError by remember { mutableStateOf<MedLogError?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.health_med_log_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_cancel),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onRecordExtra) {
                        Text(stringResource(R.string.health_med_record_one))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            editError?.let { error ->
                // Shown at the top: a failure after the sheet closed would
                // otherwise be invisible.
                Text(
                    stringResource(
                        when (error) {
                            MedLogError.UpdateRejected -> R.string.health_med_log_error_update
                            MedLogError.DeleteFailed -> R.string.health_med_log_error_delete
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // Wrapping rather than one row: "Letzte 30 Tage" plus "Eigene Auswahl"
            // does not fit a narrow screen and would clip the last chip.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LogRange.entries.forEach { range ->
                    FilterChip(
                        selected = range == ui.range,
                        onClick = {
                            // "Custom" is an action, not a range: open the
                            // calendar first, so cancelling cannot leave the
                            // screen on an empty window.
                            if (range == LogRange.CUSTOM) showRangePicker = true else vm.selectRange(range)
                        },
                        label = { Text(medRangeLabel(range)) },
                    )
                }
            }

            // In the custom state the chip only says "custom"; the actual span has
            // to be visible, with a way to re-pick it.
            if (ui.isCustom) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        medRangeText(ui.from, ui.to),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showRangePicker = true }) {
                        Text(stringResource(R.string.health_med_range_repick))
                    }
                }
            }

            if (ui.plannedCount == 0 && ui.extraCount > 0) {
                // Four zeros above dozens of rows looks like a arithmetic bug; it
                // is almost always as-needed doses or imported history.
                HealthCard {
                    Text(
                        stringResource(R.string.health_med_no_plan_title),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.health_med_no_plan_body, ui.extraCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                HealthCard {
                    Row(Modifier.fillMaxWidth()) {
                        SummaryCell(
                            ui.plannedCount.toString(),
                            stringResource(R.string.health_med_summary_due),
                            MaterialTheme.colorScheme.onSurface,
                            Modifier.weight(1f),
                        )
                        SummaryCell(
                            ui.takenCount.toString(),
                            stringResource(R.string.health_med_summary_taken),
                            MaterialTheme.colorScheme.success,
                            Modifier.weight(1f),
                        )
                        SummaryCell(
                            ui.overdueCount.toString(),
                            stringResource(R.string.health_med_summary_overdue),
                            MaterialTheme.colorScheme.warning,
                            Modifier.weight(1f),
                        )
                        SummaryCell(
                            ui.pendingCount.toString(),
                            stringResource(R.string.health_med_summary_pending),
                            MaterialTheme.colorScheme.primary,
                            Modifier.weight(1f),
                        )
                    }
                }
            }

            if (ui.isEmpty) {
                HealthCard {
                    Text(
                        stringResource(R.string.health_med_log_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.health_med_log_empty_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                ui.groups.forEach { group ->
                    HealthSectionHeader(
                        title = group.date?.let { medDayLabel(it, TimeUtils.toLocalDate(ui.now)) }
                            ?: stringResource(R.string.health_med_date_unknown),
                        meta = if (group.hasPlan) {
                            stringResource(
                                R.string.health_med_group_plan,
                                group.plannedCount,
                                group.takenCount,
                            )
                        } else {
                            stringResource(R.string.health_med_group_extra, group.extraCount)
                        },
                    )
                    HealthCard(padding = 0.dp, spacing = 0.dp) {
                        group.entries.forEachIndexed { index, entry ->
                            LogRow(
                                entry = entry,
                                now = ui.now,
                                onClick = { editing = entry },
                            )
                            if (index != group.entries.lastIndex) HorizontalDivider()
                        }
                    }
                }
            }

            HealthCard {
                Text(
                    stringResource(
                        R.string.health_med_log_footer,
                        medRangeLabel(ui.range),
                        ui.plannedCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        ui.adherencePercent?.let { "$it%" }
                            ?: stringResource(R.string.health_med_none_yet),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(
                            R.string.health_med_log_adherence_detail,
                            ui.takenCount,
                            ui.overdueCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    stringResource(R.string.health_med_log_footer_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(BottomNavScrollPadding))
        }
    }

    if (showRangePicker) {
        MedRangePicker(
            initialStart = ui.from,
            initialEnd = ui.to,
            onDismiss = { showRangePicker = false },
            onPicked = { start, end ->
                showRangePicker = false
                vm.selectCustomRange(start, end)
            },
        )
    }

    editing?.let { entry ->
        DoseLogEditSheet(
            entry = entry,
            now = ui.now,
            onDismiss = {
                editing = null
                editError = null
            },
            onSave = { at, units ->
                val logId = entry.logId
                if (logId != null) {
                    vm.updateLog(logId, at, units) { error ->
                        editError = error
                        if (error == null) editing = null
                    }
                }
            },
            onDelete = {
                val logId = entry.logId
                editing = null
                if (logId != null) vm.deleteLog(logId) { error -> editError = error }
            },
        )
    }
}

@Composable
private fun medRangeLabel(range: LogRange): String = stringResource(
    when (range) {
        LogRange.TODAY -> R.string.health_med_range_today
        LogRange.WEEK -> R.string.health_med_range_week
        LogRange.MONTH -> R.string.health_med_range_month
        LogRange.CUSTOM -> R.string.health_med_range_custom
    }
)

@Composable
private fun SummaryCell(
    value: String,
    label: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = color)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LogRow(entry: MedLogEntryUi, now: Long, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            // Only rows backed by a real log open the edit sheet: pending /
            // overdue / missed rows are plans, and opening "nothing" reads as
            // having deleted something.
            .then(
                if (entry.canEdit) {
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            TimeUtils.toTimeString(entry.actualAt),
            modifier = Modifier.width(44.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (entry.state == DoseState.PENDING) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(entry.drug, style = MaterialTheme.typography.bodyMedium)
                Text(
                    medDoseText(entry.strengthValue, entry.strengthUnit, entry.intakeUnits, entry.unitLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                medLogMeta(
                    state = entry.state,
                    planAt = entry.planAt,
                    meal = entry.meal,
                    actualAt = entry.actualAt,
                    skipReason = entry.skipReason,
                    now = now,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MedStateChip(entry.state)
        if (entry.canEdit) {
            // The chevron is the only visible hint that a row opens something:
            // the badge already owns the right edge.
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MedRangePicker(
    initialStart: java.time.LocalDate,
    initialEnd: java.time.LocalDate,
    onDismiss: () -> Unit,
    onPicked: (java.time.LocalDate, java.time.LocalDate) -> Unit,
) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = TimeUtils.toPickerMillis(initialStart),
        initialSelectedEndDateMillis = TimeUtils.toPickerMillis(initialEnd),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val start = state.selectedStartDateMillis?.let(TimeUtils::fromPickerMillis)
                    val end = state.selectedEndDateMillis?.let(TimeUtils::fromPickerMillis)
                    if (start != null && end != null) onPicked(start, end) else onDismiss()
                },
            ) {
                Text(stringResource(R.string.action_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    ) {
        DateRangePicker(state = state, showModeToggle = false)
    }
}
