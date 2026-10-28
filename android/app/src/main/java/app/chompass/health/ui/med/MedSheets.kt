package app.chompass.health.ui.med

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.chompass.R
import app.chompass.health.ui.pain.PainDateTimeRow
import app.chompass.health.ui.pain.PainFieldLabel
import app.chompass.health.ui.pain.PainHelper
import app.chompass.health.ui.pain.PainSheetActions
import app.chompass.health.ui.pain.PainSheetFrame
import app.chompass.health.ui.pain.PainSheetSubtitle
import app.chompass.health.util.TimeUtils

/**
 * The "record / backfill this dose" sheet.
 *
 * Why the tap does not write directly: people open the app either right after
 * swallowing a pill or when they remember taking it two hours ago. Stamping
 * "now" for the second group invents an on-time dose; asking once costs almost
 * nothing because the default is already now.
 */
@Composable
fun DoseRecordSheet(
    row: DoseRowUi,
    backfill: Boolean,
    now: Long,
    onDismiss: () -> Unit,
    onConfirm: (actualAt: Long) -> Unit,
) {
    var actualAt by remember { mutableStateOf(now) }
    val inFuture = actualAt > now

    PainSheetFrame(
        title = stringResource(
            if (backfill) R.string.health_med_backfill_title else R.string.health_med_record_title,
        ),
        onDismiss = onDismiss,
    ) {
        PainSheetSubtitle(
            "${row.drug} · " +
                medDoseText(row.strengthValue, row.strengthUnit, row.intakeUnits, row.unitLabel),
        )
        Text(
            medTodayMeta(row.state, row.planAt, row.meal, row.actualAt, row.skipReason),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PainFieldLabel(stringResource(R.string.health_med_actual_time))
        PainDateTimeRow(at = actualAt, onChange = { actualAt = it })

        Text(
            if (inFuture) {
                stringResource(R.string.health_med_time_future)
            } else {
                stringResource(R.string.health_med_record_hint)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (inFuture) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            stringResource(R.string.health_med_record_stock_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PainSheetActions(
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = onDismiss,
            primaryText = stringResource(R.string.health_med_record_confirm),
            onPrimary = { onConfirm(actualAt) },
            primaryEnabled = !inFuture,
        )
    }
}

/**
 * Correct one log: when it was taken, and how much.
 *
 * Only those two fields, for the same reason the repository only allows those
 * two: the drug name and the per-unit strength belong to the medication, and
 * rewriting them here would move one history row onto a different drug.
 */
@Composable
fun DoseLogEditSheet(
    entry: MedLogEntryUi,
    now: Long,
    onDismiss: () -> Unit,
    onSave: (actualAt: Long, intakeUnits: Double) -> Unit,
    onDelete: () -> Unit,
) {
    var actualAt by remember(entry.key) { mutableStateOf(entry.actualAt) }
    var amount by remember(entry.key) { mutableStateOf(medNumber(entry.intakeUnits)) }
    var confirmDelete by remember { mutableStateOf(false) }

    val inFuture = actualAt > now
    val parsed = amount.trim().toDoubleOrNull()
    val amountOk = parsed != null && parsed > 0.0 && parsed <= 100.0
    val canSave = !inFuture && amountOk

    PainSheetFrame(
        title = stringResource(R.string.health_med_edit_log_title),
        onDismiss = onDismiss,
    ) {
        PainSheetSubtitle(
            "${entry.drug} · " +
                medDoseText(entry.strengthValue, entry.strengthUnit, entry.intakeUnits, entry.unitLabel),
        )

        PainFieldLabel(stringResource(R.string.health_med_actual_time))
        PainDateTimeRow(at = actualAt, onChange = { actualAt = it })
        val dayChanged = TimeUtils.toDateString(actualAt) != TimeUtils.toDateString(entry.actualAt)
        if (dayChanged) {
            PainHelper(
                stringResource(
                    R.string.health_med_moved_day,
                    TimeUtils.toMonthDay(actualAt),
                ),
            )
        }

        PainFieldLabel(stringResource(R.string.health_med_intake))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it.take(6) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                isError = !amountOk,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                entry.unitLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        medStrengthText(entry.strengthValue, entry.strengthUnit)?.let { strength ->
            PainHelper(
                stringResource(R.string.health_med_intake_per_unit, entry.unitLabel, strength),
            )
        }

        Text(
            when {
                inFuture -> stringResource(R.string.health_med_time_future)
                !amountOk -> stringResource(R.string.health_med_intake_invalid)
                else -> stringResource(R.string.health_med_edit_log_hint)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (inFuture || !amountOk) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = { confirmDelete = true }) {
                Text(
                    stringResource(R.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.weight(1f))
        }
        Text(
            stringResource(R.string.health_med_edit_log_delete_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(2.dp))
        PainSheetActions(
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = onDismiss,
            primaryText = stringResource(R.string.action_save),
            onPrimary = { if (canSave) onSave(actualAt, parsed) },
            primaryEnabled = canSave,
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.health_med_delete_log_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(
                            R.string.health_med_delete_log_body,
                            entry.drug,
                            medDoseText(
                                entry.strengthValue,
                                entry.strengthUnit,
                                entry.intakeUnits,
                                entry.unitLabel,
                            ),
                        ),
                    )
                    Text(
                        // Deleting a record that hangs off a plan puts that plan
                        // back to overdue — saying so prevents "where did the
                        // dose go?" the next morning.
                        stringResource(
                            if (entry.instanceId != null) {
                                R.string.health_med_delete_log_plan
                            } else {
                                R.string.health_med_delete_log_other
                            },
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
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
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
