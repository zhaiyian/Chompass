package app.chompass.health.ui.med

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.chompass.R
import app.chompass.health.HealthContainer
import app.chompass.health.reminder.HealthReminderCenter
import app.chompass.ui.components.ChompassSurfaceColumn
import app.chompass.ui.theme.warning
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Switch plus the two system permissions the reminders depend on. */
data class MedReminderUiState(
    val masterEnabled: Boolean = true,
    val canPost: Boolean = true,
    val canExact: Boolean = true,
)

/**
 * State for the reminders card.
 *
 * The permission probes are re-read on every resume: both can be changed from
 * the system settings app while this screen is alive, and a stale "allowed"
 * would send the user hunting for a bug the app doesn't have.
 */
class MedReminderViewModel(private val center: HealthReminderCenter) : ViewModel() {

    private val _state = MutableStateFlow(MedReminderUiState())
    val state: StateFlow<MedReminderUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            center.masterEnabled.collect { enabled ->
                _state.update { it.copy(masterEnabled = enabled) }
            }
        }
        refresh()
    }

    fun refresh() {
        _state.update {
            it.copy(
                canPost = center.canPostNotifications(),
                canExact = center.canScheduleExact(),
            )
        }
    }

    /**
     * Optimistic on, then persist: waiting for the write before the switch moves
     * makes the control feel stuck. A failed write flips it back.
     */
    fun setMaster(enabled: Boolean) {
        if (_state.value.masterEnabled == enabled) return
        _state.update { it.copy(masterEnabled = enabled) }
        viewModelScope.launch {
            runCatching { center.setMaster(enabled) }
                .onFailure { _state.update { s -> s.copy(masterEnabled = !enabled) } }
            refresh()
        }
    }

    fun notificationSettingsIntent() = center.notificationSettingsIntent()

    fun exactAlarmSettingsIntent() = center.exactAlarmSettingsIntent()

    class Factory(private val container: HealthContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MedReminderViewModel(container.reminders) as T
    }
}

/**
 * The medication module's reminder switch, on the "my medications" screen.
 *
 * The two permission rows are not decoration: on Android 13+ a fresh install has
 * no notification permission, and on 14+ exact alarms start denied. Without
 * stating that here, "I turned reminders on and nothing happens" is the only
 * conclusion the user can reach.
 */
@Composable
fun MedReminderCard(
    container: HealthContainer,
    modifier: Modifier = Modifier,
) {
    val vm: MedReminderViewModel = viewModel(factory = MedReminderViewModel.Factory(container))
    val ui by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) vm.setMaster(true) else vm.refresh() }

    ChompassSurfaceColumn(modifier = modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.health_med_reminder),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(
                        if (ui.masterEnabled) {
                            R.string.health_med_reminder_on
                        } else {
                            R.string.health_med_reminder_off
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = ui.masterEnabled,
                onCheckedChange = { enabled ->
                    if (!enabled) {
                        vm.setMaster(false)
                    } else if (needsNotificationPermission(context)) {
                        // Ask in the same gesture that turns the feature on;
                        // telling the user afterwards is a dead end.
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        vm.setMaster(true)
                    }
                },
            )
        }

        if (!ui.masterEnabled) {
            Text(
                stringResource(R.string.health_med_reminder_off_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            HorizontalDivider()
            PermissionRow(
                title = stringResource(R.string.health_med_reminder_perm_title),
                value = stringResource(
                    if (ui.canPost) {
                        R.string.health_med_reminder_perm_ok
                    } else {
                        R.string.health_med_reminder_perm_blocked
                    },
                ),
                ok = ui.canPost,
                actionText = if (ui.canPost) null else stringResource(R.string.health_med_reminder_open),
                onAction = {
                    runCatching { context.startActivity(vm.notificationSettingsIntent()) }
                },
            )

            // Below Android 12 the permission does not exist and alarms are exact,
            // so the row would only ever say "allowed".
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                HorizontalDivider()
                PermissionRow(
                    title = stringResource(R.string.health_med_reminder_exact_title),
                    value = stringResource(
                        if (ui.canExact) {
                            R.string.health_med_reminder_exact_ok
                        } else {
                            R.string.health_med_reminder_exact_blocked
                        },
                    ),
                    ok = ui.canExact,
                    actionText = if (ui.canExact) null else stringResource(R.string.health_med_reminder_open),
                    onAction = {
                        vm.exactAlarmSettingsIntent()?.let { intent ->
                            runCatching { context.startActivity(intent) }
                        }
                    },
                )
            }

            Text(
                stringResource(R.string.health_med_reminder_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun needsNotificationPermission(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED

@Composable
private fun PermissionRow(
    title: String,
    value: String,
    ok: Boolean,
    actionText: String?,
    onAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodySmall)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.warning
                },
            )
        }
        if (actionText != null) {
            TextButton(onClick = onAction) {
                Text(actionText, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
