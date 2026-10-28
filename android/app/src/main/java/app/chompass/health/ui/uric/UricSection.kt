package app.chompass.health.ui.uric

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.chompass.AppContainer
import app.chompass.R
import app.chompass.health.domain.DictResolver
import app.chompass.health.domain.model.DictType
import app.chompass.health.domain.uric.LevelResolver
import app.chompass.health.domain.uric.UnitConverter
import app.chompass.health.domain.uric.UricPeriod
import app.chompass.health.domain.uric.UricRange
import app.chompass.health.model.UricLevel
import app.chompass.health.ui.HealthUnits
import app.chompass.health.ui.mgdlText
import app.chompass.health.ui.umolText
import app.chompass.health.ui.uricLevelColor
import app.chompass.health.ui.uricLevelLabel
import app.chompass.health.ui.uricPeriodLabel
import app.chompass.health.ui.uricRangeLabel
import app.chompass.health.util.TimeUtils
import app.chompass.ui.components.ChompassSegmentedTabs
import app.chompass.ui.components.ChompassSurface
import app.chompass.ui.components.ChompassSurfaceColumn
import app.chompass.ui.navigation.BottomNavScrollPadding
import java.time.LocalDate

/**
 * A1 — the uric-acid section of the Health tab.
 *
 * Reading order is deliberate: period → range → today's number → trend → how the
 * period went → the individual readings. The list is last because it is the
 * drill-down, not the summary.
 */
@Composable
fun UricSection(
    container: AppContainer,
    onOpenReading: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val vm: UricTrendViewModel = viewModel(
        factory = UricTrendViewModel.Factory(container.healthDiary),
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    val dict by container.healthDiary.dictResolver.collectAsStateWithLifecycle(
        initialValue = DictResolver(),
    )

    var detail by remember { mutableStateOf<UricRecordUi?>(null) }
    var showRangePicker by remember { mutableStateOf(false) }
    var showTargetDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ChompassSegmentedTabs(
                options = UricPeriodTabs,
                selected = ui.range.period,
                onSelect = { period ->
                    if (period == UricPeriod.CUSTOM) {
                        showRangePicker = true
                    } else {
                        vm.selectPeriod(period)
                    }
                },
                label = { uricPeriodLabel(it) },
            )
        }
        item {
            RangeNavRow(
                label = uricRangeLabel(ui.range),
                pageable = ui.range.pageable,
                canGoNext = ui.canGoNext,
                onPrevious = vm::goPrevious,
                onNext = vm::goNext,
                onPickRange = { showRangePicker = true },
            )
        }
        item {
            LatestCard(
                latest = ui.latest,
                targetUmol = ui.targetUmol,
                onChangeTarget = { showTargetDialog = true },
            )
        }
        item { TrendCard(ui) }
        item { StatsCard(ui) }
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.health_records_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (ui.hasRecords) {
                    Text(
                        stringResource(R.string.health_records_count, ui.records.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (ui.hasRecords) {
            item {
                ChompassSurfaceColumn(padding = 0.dp) {
                    ui.records.forEachIndexed { index, record ->
                        if (index > 0) HorizontalDivider()
                        ReadingRow(
                            record = record,
                            dict = dict,
                            onClick = { detail = record },
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(BottomNavScrollPadding)) }
    }

    detail?.let { record ->
        ReadingDetailSheet(
            record = record,
            dict = dict,
            onEdit = {
                detail = null
                onOpenReading(record.id)
            },
            onDelete = {
                detail = null
                vm.delete(record.id)
            },
            onDismiss = { detail = null },
        )
    }

    if (showRangePicker) {
        CustomRangeDialog(
            range = ui.range,
            onDismiss = { showRangePicker = false },
            onConfirm = { start, end ->
                showRangePicker = false
                vm.selectCustomRange(start, end)
            },
        )
    }

    if (showTargetDialog) {
        TargetDialog(
            currentUmol = ui.targetUmol,
            onDismiss = { showTargetDialog = false },
            onConfirm = { value ->
                showTargetDialog = false
                vm.setTarget(value)
            },
        )
    }
}

private val UricPeriodTabs = listOf(
    UricPeriod.WEEK,
    UricPeriod.MONTH,
    UricPeriod.QUARTER,
    UricPeriod.CUSTOM,
)

@Composable
private fun RangeNavRow(
    label: String,
    pageable: Boolean,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPickRange: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious, enabled = pageable) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.health_range_previous),
            )
        }
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onPickRange)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onNext, enabled = pageable && canGoNext) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.health_range_next),
            )
        }
    }
}

@Composable
private fun LatestCard(
    latest: UricRecordUi?,
    targetUmol: Int,
    onChangeTarget: () -> Unit,
) {
    ChompassSurfaceColumn {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.health_latest),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onChangeTarget) {
                Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.health_target_value, targetUmol),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (latest == null) {
            Text(
                stringResource(R.string.health_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.health_empty_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    umolText(latest.valueUmol),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    HealthUnits.UMOL,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Spacer(Modifier.weight(1f))
                UricLevelBadge(latest.level, modifier = Modifier.padding(bottom = 8.dp))
            }
            Text(
                TimeUtils.toMonthDayTime(latest.measuredAt) + "  " +
                    mgdlText(UnitConverter.umolToMgdl(latest.valueUmol)) +
                    " " + HealthUnits.MGDL,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrendCard(ui: UricTrendUiState) {
    ChompassSurfaceColumn {
        Text(
            stringResource(R.string.health_chart_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(8.dp))
        if (ui.hasRecords) {
            UricTrendChart(
                points = ui.points,
                ticks = ui.ticks,
                period = ui.range.period,
                targetUmol = ui.targetUmol,
                axisTop = ui.axisTop,
                todayFraction = ui.todayFraction,
            )
        } else {
            UricChartEmpty(stringResource(R.string.health_chart_empty))
        }
    }
}

@Composable
private fun StatsCard(ui: UricTrendUiState) {
    if (!ui.hasRecords) return
    ChompassSurfaceColumn {
        Text(
            stringResource(R.string.health_stats_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            StatCell(
                label = stringResource(R.string.health_stat_average),
                value = ui.average?.let(::umolText) ?: "—",
                modifier = Modifier.weight(1f),
            )
            StatCell(
                label = stringResource(R.string.health_stat_max),
                value = ui.highest?.let(::umolText) ?: "—",
                modifier = Modifier.weight(1f),
            )
            StatCell(
                label = stringResource(R.string.health_stat_min),
                value = ui.lowest?.let(::umolText) ?: "—",
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            StatCell(
                label = stringResource(R.string.health_stat_in_target),
                value = "${ui.onTargetCount}/${ui.records.size}",
                tint = uricLevelColor(UricLevel.BELOW_TARGET),
                modifier = Modifier.weight(1f),
            )
            StatCell(
                label = stringResource(R.string.health_stat_days),
                value = "${ui.measureDays}/${ui.totalDays}",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    Column(modifier) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = tint ?: MaterialTheme.colorScheme.onSurface,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ReadingRow(
    record: UricRecordUi,
    dict: DictResolver,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                TimeUtils.toMonthDayTime(record.measuredAt),
                style = MaterialTheme.typography.bodyMedium,
            )
            val fastingLabel = stringResource(R.string.health_reading_fasting)
            val meta = buildList {
                add(dict.label(DictType.URIC_METHOD, record.methodCode, short = true).text)
                if (record.fasting) add(fastingLabel)
                record.note?.let { add(it) }
            }.filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            umolText(record.valueUmol),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(8.dp))
        UricLevelBadge(record.level)
    }
}

@Composable
private fun UricLevelBadge(level: UricLevel, modifier: Modifier = Modifier) {
    val color = uricLevelColor(level)
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            uricLevelLabel(level),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadingDetailSheet(
    record: UricRecordUi,
    dict: DictResolver,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmingDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.health_detail_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                UricLevelBadge(record.level)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    umolText(record.valueUmol),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    HealthUnits.UMOL,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            DetailRow(
                stringResource(R.string.health_detail_recorded),
                TimeUtils.toMonthDayTime(record.measuredAt),
            )
            DetailRow(
                stringResource(R.string.health_entry_method),
                dict.label(DictType.URIC_METHOD, record.methodCode).text,
            )
            if (record.fasting) {
                DetailRow(
                    stringResource(R.string.health_entry_fasting),
                    stringResource(R.string.health_reading_fasting),
                )
            }
            record.note?.takeIf { it.isNotBlank() }?.let { note ->
                DetailRow(stringResource(R.string.health_entry_note), note)
            }
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { confirmingDelete = true }) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.health_edit))
                }
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.health_delete_title)) },
            text = { Text(stringResource(R.string.health_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        onDelete()
                    },
                ) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetDialog(
    currentUmol: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(currentUmol.toString()) }
    val value = text.toIntOrNull()
    val valid = value != null &&
        value >= LevelResolver.TARGET_MIN &&
        value < LevelResolver.TARGET_MAX_EXCLUSIVE

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.health_target_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { input -> text = input.filter { it.isDigit() }.take(3) },
                    singleLine = true,
                    suffix = { Text(HealthUnits.UMOL) },
                    isError = !valid,
                )
                Text(
                    if (valid) {
                        stringResource(R.string.health_target_dialog_hint)
                    } else {
                        stringResource(R.string.health_target_invalid)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (valid) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { value?.let(onConfirm) },
                enabled = valid,
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomRangeDialog(
    range: UricRange,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, LocalDate) -> Unit,
) {
    val initialStart = range.start
    val initialEnd = range.end
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = TimeUtils.toPickerMillis(initialStart),
        initialSelectedEndDateMillis = TimeUtils.toPickerMillis(initialEnd),
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val start = state.selectedStartDateMillis
                    val end = state.selectedEndDateMillis
                    if (start != null && end != null) {
                        onConfirm(
                            TimeUtils.fromPickerMillis(start),
                            TimeUtils.fromPickerMillis(end),
                        )
                    } else {
                        onDismiss()
                    }
                },
                enabled = state.selectedStartDateMillis != null &&
                    state.selectedEndDateMillis != null,
            ) {
                Text(stringResource(R.string.action_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    ) {
        DateRangePicker(
            state = state,
            modifier = Modifier.height(500.dp),
            title = {
                Text(
                    stringResource(R.string.health_range_pick),
                    modifier = Modifier.padding(start = 24.dp, top = 16.dp),
                )
            },
            showModeToggle = false,
        )
    }
}
