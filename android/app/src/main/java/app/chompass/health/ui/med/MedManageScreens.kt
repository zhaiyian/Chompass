package app.chompass.health.ui.med

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.chompass.AppContainer
import app.chompass.R
import app.chompass.health.domain.model.MealRelation
import app.chompass.health.model.MedCategory
import app.chompass.health.util.TimeUtils
import app.chompass.ui.components.ChompassPrimaryButton
import app.chompass.ui.components.ChompassSegmentedTabs
import app.chompass.health.ui.HealthCard
import app.chompass.health.ui.HealthDisclosure
import app.chompass.health.ui.HealthSectionHeader
import app.chompass.ui.components.ChompassTextField
import app.chompass.ui.theme.warning

/* ============================================================
 * My medications
 *
 * Exists because C3 edits *one* medication and without a list there is no way
 * in: pointing the medication icon straight at the edit form assumes the user
 * has exactly one drug.
 * ============================================================ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedListScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
) {
    val vm: MedListViewModel = viewModel(factory = MedListViewModel.Factory(container.healthDiary))
    val ui by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.health_med_manage)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_cancel),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onAdd) {
                        Text(stringResource(R.string.health_med_add))
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
            if (ui.isEmpty) {
                HealthCard {
                    Text(
                        stringResource(R.string.health_med_empty_list_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.health_med_empty_list_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ChompassPrimaryButton(
                        text = stringResource(R.string.health_med_empty_list_action),
                        onClick = onAdd,
                    )
                }
            } else {
                HealthSectionHeader(
                    title = stringResource(R.string.health_med_active_title),
                    meta = stringResource(R.string.health_med_active_count, ui.activeCount),
                )
                HealthCard(padding = 0.dp, spacing = 0.dp) {
                    if (ui.active.isEmpty()) {
                        Text(
                            stringResource(R.string.health_med_active_empty),
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        ui.active.forEachIndexed { index, row ->
                            MedRow(row = row, onOpen = onOpen)
                            if (index != ui.active.lastIndex) HorizontalDivider()
                        }
                    }
                }
                if (ui.stopped.isNotEmpty()) {
                    HealthSectionHeader(
                        title = stringResource(R.string.health_med_stopped_title),
                        meta = stringResource(R.string.health_med_stopped_hint),
                    )
                    HealthCard(padding = 0.dp, spacing = 0.dp) {
                        ui.stopped.forEachIndexed { index, row ->
                            MedRow(row = row, onOpen = onOpen)
                            if (index != ui.stopped.lastIndex) HorizontalDivider()
                        }
                    }
                }
            }
            // Only with something to remind about. The card carries the permission
            // state too: a switch that is on while notifications are blocked is the
            // one failure the list itself can never show.
            if (!ui.isEmpty) {
                MedReminderCard(container = container.healthDiary)
            }
            HealthDisclosure(stringResource(R.string.health_med_list_disclaimer))
            Spacer(Modifier.navigationBarsPadding().height(24.dp))
        }
    }
}

@Composable
private fun MedRow(row: MedListRowUi, onOpen: (Long) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(row.id) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (row.active) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.isPrn) {
                    Text(
                        stringResource(R.string.health_med_tag_prn),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                medSpecText(row.strengthValue, row.strengthUnit, row.intakeUnits, row.unitLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                medPlanLine(row.times, row.meal),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            row.stockUnits?.let { stock ->
                Text(
                    medStockLine(stock, row.unitLabel, row.remainingDays),
                    style = MaterialTheme.typography.bodySmall,
                    color = when (row.stockTone) {
                        StockTone.NORMAL -> MaterialTheme.colorScheme.onSurfaceVariant
                        StockTone.LOW -> MaterialTheme.colorScheme.warning
                        StockTone.EMPTY -> MaterialTheme.colorScheme.error
                    },
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun medStockLine(stockUnits: Double, unitLabel: String, days: Int?): String {
    val left = stringResource(R.string.health_med_stock_left, medNumber(stockUnits), unitLabel)
    return if (days == null) left else "$left · ${stringResource(R.string.health_med_stock_days, days)}"
}

/* ============================================================
 * C3 · add / edit a medication
 *
 * One screen for both: the fields are identical, and splitting them guarantees
 * the two validation paths drift apart.
 * ============================================================ */

private val STRENGTH_UNITS = listOf("mg", "g", "ml")

@Composable
private fun intakeUnitOptions(): List<String> = listOf(
    stringResource(R.string.health_med_unit_tablet),
    stringResource(R.string.health_med_unit_capsule),
    "ml",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MedEditScreen(
    container: AppContainer,
    medId: Long?,
    onDone: () -> Unit,
) {
    val tablet = stringResource(R.string.health_med_unit_tablet)
    val vm: MedEditViewModel = viewModel(
        factory = MedEditViewModel.Factory(container.healthDiary, tablet, medId),
    )
    val ui by vm.state.collectAsStateWithLifecycle()

    var timePickerOpen by remember { mutableStateOf(false) }
    var stopConfirm by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<MedEditError?>(null) }
    var saved by remember { mutableStateOf(false) }
    var backfilled by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(saved) { if (saved) onDone() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (ui.isEditing) R.string.health_med_edit_edit_title
                            else R.string.health_med_edit_add_title,
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The error belongs at the top: save is a deliberate action, and a
            // message below the fold after scrolling reads as "nothing happened".
            val shownError = error ?: ui.error
            shownError?.let { err ->
                Text(
                    medEditErrorText(err),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (shownError == null && ui.isEditing) {
                Text(
                    stringResource(R.string.health_med_edit_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (backfilled.isNotEmpty()) {
                Text(
                    stringResource(R.string.health_med_backfill_receipt, backfilled.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.warning,
                )
            }

            HealthCard {
                Text(
                    stringResource(R.string.health_med_name),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChompassTextField(
                    value = ui.name,
                    onValueChange = vm::setName,
                    placeholder = stringResource(R.string.health_med_name_hint),
                )
            }

            HealthCard {
                Text(
                    stringResource(R.string.health_med_category),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChompassSegmentedTabs(
                    options = MedCategory.entries,
                    selected = ui.category,
                    onSelect = vm::setCategory,
                    label = { medCategoryLabel(it) },
                )
                Text(
                    stringResource(R.string.health_med_category_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SwitchRow(
                    label = stringResource(R.string.health_med_prn_switch),
                    hint = stringResource(R.string.health_med_prn_switch_hint),
                    checked = ui.isPrn,
                    onChange = vm::setPrn,
                )
            }

            HealthCard {
                Text(
                    stringResource(R.string.health_med_strength, ui.unitLabel),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ValueWithUnitField(
                    value = ui.strengthValue,
                    onValueChange = vm::setStrengthValue,
                    unit = ui.strengthUnit,
                    placeholder = "40",
                )
                UnitChips(
                    options = STRENGTH_UNITS,
                    selected = ui.strengthUnit,
                    onSelect = vm::setStrengthUnit,
                )
            }

            HealthCard {
                Text(
                    stringResource(R.string.health_med_intake_per_dose),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ValueWithUnitField(
                    value = ui.intakeUnits,
                    onValueChange = vm::setIntakeUnits,
                    unit = ui.unitLabel,
                    placeholder = "1",
                )
                UnitChips(
                    options = intakeUnitOptions(),
                    selected = ui.unitLabel,
                    onSelect = vm::setUnitLabel,
                )
            }

            if (!ui.isPrn) {
                HealthCard {
                    Text(
                        stringResource(R.string.health_med_times_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.health_med_times_per_day),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        IconButton(
                            onClick = { vm.setTimesPerDay(ui.timesPerDay - 1) },
                            enabled = ui.timesPerDay > 1,
                        ) {
                            Icon(Icons.Filled.Remove, contentDescription = null)
                        }
                        Text(
                            ui.timesPerDay.toString(),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        IconButton(
                            onClick = { vm.setTimesPerDay(ui.timesPerDay + 1) },
                            enabled = ui.timesPerDay < 6,
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                        }
                    }
                    // The gap between the stepper and the chips is deliberate:
                    // when they sit flush, aiming for a chip's × hits "+", which
                    // silently changes the daily dose count.
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ui.times.forEach { time ->
                            TimeChip(text = time, onRemove = { vm.removeTime(time) })
                        }
                    }
                    TextButton(onClick = { timePickerOpen = true }) {
                        Text(stringResource(R.string.health_med_add_time))
                    }
                    Text(
                        stringResource(R.string.health_med_times_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SwitchRow(
                        label = stringResource(R.string.health_med_reminder),
                        hint = stringResource(R.string.health_med_reminder_hint),
                        checked = ui.reminderEnabled,
                        onChange = vm::setReminder,
                    )
                }
                HealthCard {
                    Text(
                        stringResource(R.string.health_med_meal),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ChompassSegmentedTabs(
                        options = MealRelation.entries,
                        selected = ui.mealRelation,
                        onSelect = vm::setMealRelation,
                        label = { medMealSegmentLabel(it) },
                    )
                }
            }

            HealthCard {
                Text(
                    stringResource(R.string.health_med_stock),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ValueWithUnitField(
                    value = ui.stockUnits,
                    onValueChange = vm::setStockUnits,
                    unit = ui.unitLabel,
                    placeholder = stringResource(R.string.health_med_stock_hint),
                )
                ui.stockDays?.let { days ->
                    Text(
                        stringResource(
                            R.string.health_med_stock_days_hint,
                            ui.timesPerDay,
                            ui.intakeUnits.ifBlank { "1" },
                            ui.unitLabel,
                            days,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.health_med_alert_days),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChompassSegmentedTabs(
                    options = listOf(7, 14, 30),
                    selected = ui.stockAlertDays,
                    onSelect = vm::setStockAlertDays,
                    label = { stringResource(R.string.health_med_days, it) },
                )
            }

            ChompassPrimaryButton(
                text = stringResource(
                    when {
                        ui.saving -> R.string.health_pain_entry_saving
                        ui.isEditing -> R.string.health_med_save_edit
                        else -> R.string.health_med_save_add
                    },
                ),
                onClick = {
                    error = null
                    vm.save { _, past -> backfilled = past; saved = true }
                },
                enabled = ui.canSave,
            )
            if (ui.isEditing) {
                TextButton(onClick = { stopConfirm = true }) {
                    Text(
                        stringResource(R.string.health_med_stop),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    stringResource(R.string.health_med_stop_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.navigationBarsPadding().height(24.dp))
        }
    }

    if (timePickerOpen) {
        MedTimePickerDialog(
            onDismiss = { timePickerOpen = false },
            onPicked = { time ->
                timePickerOpen = false
                vm.addTime(time)
            },
        )
    }

    if (stopConfirm) {
        AlertDialog(
            onDismissRequest = { stopConfirm = false },
            title = { Text(stringResource(R.string.health_med_stop)) },
            text = { Text(stringResource(R.string.health_med_stop_hint)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        stopConfirm = false
                        vm.stop { err ->
                            if (err == null) onDone() else error = err
                        }
                    },
                ) {
                    Text(
                        stringResource(R.string.action_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { stopConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun medCategoryLabel(category: MedCategory): String = stringResource(
    when (category) {
        MedCategory.LONG_TERM -> R.string.health_med_category_long
        MedCategory.ACUTE -> R.string.health_med_category_acute
        MedCategory.OTHER -> R.string.health_med_category_other
    }
)

@Composable
private fun medMealSegmentLabel(relation: MealRelation): String = when (relation) {
    MealRelation.BEFORE -> stringResource(R.string.health_med_meal_before)
    MealRelation.AFTER -> stringResource(R.string.health_med_meal_after)
    MealRelation.NONE -> stringResource(R.string.health_med_meal_none)
}

@Composable
private fun medEditErrorText(error: MedEditError): String = stringResource(
    when (error) {
        MedEditError.NameRequired -> R.string.health_med_err_name_required
        MedEditError.TimesRequired -> R.string.health_med_err_times_required
        MedEditError.DuplicateTime -> R.string.health_med_err_duplicate_time
        MedEditError.LastTime -> R.string.health_med_err_last_time
        MedEditError.IntakeInvalid -> R.string.health_med_intake_invalid
        MedEditError.StrengthInvalid -> R.string.health_med_err_strength
        MedEditError.SaveFailed -> R.string.health_pain_error_save
        MedEditError.StopFailed -> R.string.health_med_err_stop
        MedEditError.LoadFailed -> R.string.health_med_err_load
    },
)

@Composable
private fun SwitchRow(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ValueWithUnitField(
    value: String,
    onValueChange: (String) -> Unit,
    unit: String,
    placeholder: String,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it.take(6)) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            placeholder = { Text(placeholder) },
        )
        Spacer(Modifier.size(10.dp))
        Text(
            unit,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UnitChips(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { unit ->
            Box(Modifier.weight(1f)) {
                FilterChip(
                    selected = unit == selected,
                    onClick = { onSelect(unit) },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            unit,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun TimeChip(text: String, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.health_med_remove_time),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/** Time-of-day picker for adding a schedule slot; only the clock matters here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MedTimePickerDialog(onDismiss: () -> Unit, onPicked: (String) -> Unit) {
    val state = rememberTimePickerState(initialHour = 8, initialMinute = 0, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.health_med_add_time)) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(
                onClick = {
                    onPicked(String.format("%02d:%02d", state.hour, state.minute))
                },
            ) {
                Text(stringResource(R.string.action_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
