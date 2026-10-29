package app.chompass.health.ui.med

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import app.chompass.health.ui.pain.PainDateTimeRow
import app.chompass.health.ui.pain.PainFieldLabel
import app.chompass.ui.components.ChompassPrimaryButton
import app.chompass.health.ui.HealthCard
import app.chompass.health.ui.HealthDisclosure
import app.chompass.ui.components.ChompassTextField

/**
 * C4 — record a single dose that belongs to no plan.
 *
 * Separate from the "record / backfill" sheet on a C1 row because the write is
 * different: no `instance_id`, no plan state change, no adherence impact. Sharing
 * one form would force the user to answer "does this count as the 08:00 dose?" —
 * a question two entry points answer better by simply existing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedExtraDoseScreen(
    container: AppContainer,
    presetMedId: Long?,
    onDone: () -> Unit,
) {
    val vm: MedExtraDoseViewModel = viewModel(
        factory = MedExtraDoseViewModel.Factory(container.healthDiary),
    )
    val ui by vm.state.collectAsStateWithLifecycle()
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(presetMedId, ui.meds) {
        // Arrives either from the FAB (no preset) or from an as-needed row.
        if (presetMedId != null && ui.meds.any { it.id == presetMedId } && ui.selectedId != presetMedId) {
            vm.select(presetMedId)
        }
    }
    LaunchedEffect(saved) { if (saved) onDone() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.health_med_extra_title)) },
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
            ui.error?.let { error ->
                Text(
                    stringResource(
                        when (error) {
                            ExtraDoseError.FutureTime -> R.string.health_med_time_future
                            ExtraDoseError.SaveFailed -> R.string.health_pain_error_save
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (ui.meds.isEmpty()) {
                HealthCard {
                    Text(
                        stringResource(R.string.health_med_extra_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.health_med_extra_empty_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                HealthCard {
                    PainFieldLabel(stringResource(R.string.health_med_extra_pick))
                    ui.meds.forEach { med ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = med.id == ui.selectedId,
                                onClick = { vm.select(med.id) },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(med.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    medSpecText(
                                        med.strengthValue,
                                        med.strengthUnit,
                                        med.intakeUnits,
                                        med.unitLabel,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                HealthCard {
                    PainFieldLabel(stringResource(R.string.health_med_actual_time))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = false,
                            onClick = vm::useNow,
                            label = { Text(stringResource(R.string.health_med_extra_quick_now)) },
                        )
                        FilterChip(
                            selected = false,
                            onClick = vm::useHourAgo,
                            label = { Text(stringResource(R.string.health_med_extra_quick_hour)) },
                        )
                    }
                    PainDateTimeRow(at = ui.actualAt, onChange = vm::setActualAt)
                }

                HealthCard {
                    PainFieldLabel(stringResource(R.string.health_med_extra_dose))
                    Text(
                        ui.selected?.let {
                            medDoseText(it.strengthValue, it.strengthUnit, it.intakeUnits, it.unitLabel)
                        } ?: "",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.health_med_extra_dose_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HealthCard {
                    PainFieldLabel(stringResource(R.string.health_med_extra_note))
                    ChompassTextField(
                        value = ui.note,
                        onValueChange = vm::setNote,
                        placeholder = stringResource(R.string.health_med_extra_note_hint),
                        singleLine = false,
                        minLines = 2,
                    )
                }

                Text(
                    // Spelling out which kind of record this becomes is the whole
                    // difference between C4 and a C1 backfill.
                    stringResource(
                        if (ui.selected?.isPrn == true) {
                            R.string.health_med_extra_prn_hint
                        } else {
                            R.string.health_med_extra_extra_hint
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                ChompassPrimaryButton(
                    text = stringResource(
                        if (ui.saving) R.string.health_pain_entry_saving
                        else R.string.health_med_extra_save,
                    ),
                    onClick = { vm.save { saved = true } },
                    enabled = ui.canSave,
                )
            }

            HealthDisclosure(stringResource(R.string.health_med_extra_disclaimer))
            Spacer(Modifier.navigationBarsPadding().height(24.dp))
        }
    }
}
