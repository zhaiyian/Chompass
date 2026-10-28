package app.chompass.health.ui.uric

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.chompass.AppContainer
import app.chompass.R
import app.chompass.health.domain.DictResolver
import app.chompass.health.domain.model.DictType
import app.chompass.health.domain.model.UricInputUnit
import app.chompass.health.ui.HealthUnits
import app.chompass.health.util.TimeUtils
import app.chompass.ui.components.ChompassDialogActions
import app.chompass.ui.components.ChompassPrimaryButton
import app.chompass.ui.components.ChompassSegmentedTabs
import app.chompass.ui.components.ChompassSurfaceColumn
import app.chompass.ui.components.ChompassTextField
import java.time.LocalDateTime

/**
 * A2 — log or edit one reading.
 *
 * The value field is the whole point of the screen, so it gets the largest type
 * on the page and opens focused on the number; everything else is a secondary
 * row. The unit toggle sits directly under it and converts what is typed, so
 * switching units never silently changes what will be stored.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UricEntryScreen(
    container: AppContainer,
    recordId: Long?,
    onDone: () -> Unit,
) {
    val vm: UricEntryViewModel = viewModel(
        factory = UricEntryViewModel.Factory(container.healthDiary, recordId),
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    val dict by container.healthDiary.dictResolver.collectAsStateWithLifecycle(
        initialValue = DictResolver(),
    )

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    LaunchedEffect(done) { if (done) onDone() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (ui.editing) R.string.health_entry_edit_title
                            else R.string.health_entry_add_title,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_cancel),
                        )
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
                .imePadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ChompassSurfaceColumn {
                Text(
                    stringResource(R.string.health_entry_value_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = ui.valueText,
                        onValueChange = vm::setValue,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.displaySmall.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        isError = ui.error != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        placeholder = { Text("0") },
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        HealthUnits.of(ui.unit),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                ChompassSegmentedTabs(
                    options = UricInputUnit.entries,
                    selected = ui.unit,
                    onSelect = vm::setUnit,
                    label = { HealthUnits.of(it) },
                )
                ui.error?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        entryErrorText(error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            ChompassSurfaceColumn {
                PickerRow(
                    label = stringResource(R.string.health_entry_date),
                    value = TimeUtils.toDateString(ui.measuredAt),
                    onClick = { showDatePicker = true },
                )
                PickerRow(
                    label = stringResource(R.string.health_entry_time),
                    value = TimeUtils.toTimeString(ui.measuredAt),
                    onClick = { showTimePicker = true },
                )
            }

            ChompassSurfaceColumn {
                Text(
                    stringResource(R.string.health_entry_method),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                val methods = dict.enabledOf(DictType.URIC_METHOD)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    methods.forEach { entry ->
                        FilterChip(
                            selected = entry.code == ui.methodCode,
                            onClick = { vm.setMethod(entry.code) },
                            label = {
                                Text(entry.shortLabel ?: entry.label, maxLines = 1)
                            },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.health_entry_fasting),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = ui.fasting, onCheckedChange = vm::setFasting)
                }
            }

            ChompassSurfaceColumn {
                ChompassTextField(
                    value = ui.note,
                    onValueChange = vm::setNote,
                    placeholder = stringResource(R.string.health_entry_note_hint),
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                )
            }

            ChompassPrimaryButton(
                text = stringResource(R.string.action_save),
                onClick = vm::save,
                enabled = ui.canSave,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = TimeUtils.toPickerMillis(ui.measuredAt),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { millis ->
                            val date = TimeUtils.fromPickerMillis(millis)
                            val time = TimeUtils.toLocalDateTime(ui.measuredAt).toLocalTime()
                            vm.setMeasuredAt(
                                LocalDateTime.of(date, time)
                                    .atZone(TimeUtils.zone)
                                    .toInstant()
                                    .toEpochMilli(),
                            )
                        }
                        showDatePicker = false
                    },
                ) {
                    Text(stringResource(R.string.action_done))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        ) {
            DatePicker(state = state, showModeToggle = false)
        }
    }

    if (showTimePicker) {
        val local = TimeUtils.toLocalDateTime(ui.measuredAt)
        val state = rememberTimePickerState(
            initialHour = local.hour,
            initialMinute = local.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text(stringResource(R.string.health_entry_time)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TimePicker(state = state)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val date = TimeUtils.toLocalDate(ui.measuredAt)
                        vm.setMeasuredAt(
                            LocalDateTime.of(date, java.time.LocalTime.of(state.hour, state.minute))
                                .atZone(TimeUtils.zone)
                                .toInstant()
                                .toEpochMilli(),
                        )
                        showTimePicker = false
                    },
                ) {
                    Text(stringResource(R.string.action_done))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun entryErrorText(error: UricEntryError): String = when (error) {
    UricEntryError.ValueRequired -> stringResource(R.string.health_entry_error_value)
    is UricEntryError.OutOfRange -> stringResource(
        R.string.health_entry_error_range,
        error.min,
        error.max,
        error.unit,
    )
    UricEntryError.SaveFailed -> stringResource(R.string.health_save_failed)
}

@Composable
private fun PickerRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClick) {
            Text(value, textAlign = TextAlign.End)
        }
    }
}
