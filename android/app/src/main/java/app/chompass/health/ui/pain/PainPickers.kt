package app.chompass.health.ui.pain

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.chompass.R
import app.chompass.health.util.TimeUtils
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * One timestamp, edited as date + time.
 *
 * Both halves write back through [onChange] as epoch millis: the pickers speak
 * their own conventions (Material3 dates are UTC midnight), so every crossing of
 * that boundary goes through [TimeUtils] — never inline arithmetic.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PainDateTimeRow(
    at: Long,
    onChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }

    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { showDate = true }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(TimeUtils.toMonthDay(at))
        }
        OutlinedButton(onClick = { showTime = true }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(TimeUtils.toTimeString(at))
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = TimeUtils.toPickerMillis(at),
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { millis ->
                            val date = TimeUtils.fromPickerMillis(millis)
                            val time = TimeUtils.toLocalDateTime(at).toLocalTime()
                            onChange(
                                LocalDateTime.of(date, time)
                                    .atZone(TimeUtils.zone)
                                    .toInstant()
                                    .toEpochMilli(),
                            )
                        }
                        showDate = false
                    },
                ) {
                    Text(stringResource(R.string.action_done))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDate = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        ) {
            DatePicker(state = state, showModeToggle = false)
        }
    }

    if (showTime) {
        val local = TimeUtils.toLocalDateTime(at)
        val state = rememberTimePickerState(
            initialHour = local.hour,
            initialMinute = local.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTime = false },
            title = { Text(stringResource(R.string.health_entry_time)) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val date = TimeUtils.toLocalDate(at)
                        onChange(
                            LocalDateTime.of(date, LocalTime.of(state.hour, state.minute))
                                .atZone(TimeUtils.zone)
                                .toInstant()
                                .toEpochMilli(),
                        )
                        showTime = false
                    },
                ) {
                    Text(stringResource(R.string.action_done))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTime = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** The one-line label above a picker row. */
@Composable
fun PainFieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Secondary line under a field ("running for 8 h · counted from the start"). */
@Composable
fun PainHelper(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
