package app.chompass.health.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.chompass.AppContainer
import app.chompass.BuildConfig
import app.chompass.R
import app.chompass.health.domain.uric.LevelResolver
import app.chompass.health.export.HealthBackupCounts
import app.chompass.health.util.TimeUtils
import app.chompass.ui.components.ChompassDialog
import app.chompass.ui.components.ChompassDialogActions
import app.chompass.ui.components.ChompassPrimaryButton
import app.chompass.ui.components.ChompassSurfaceColumn
import app.chompass.ui.components.ChompassTextField
import app.chompass.ui.theme.warning

/**
 * B7 — settings and backup for the health diary.
 *
 * Reachable from the Health tab's app bar rather than from the app-wide Settings
 * screen: the uric acid target only means something inside the diary, and the
 * backup covers the diary's own database. Keeping it here also means the health
 * module can grow its own settings without re-laying-out the main Settings page
 * every time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthDataScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val vm: HealthDataViewModel = viewModel(
        factory = HealthDataViewModel.Factory(container.healthDiary),
    )
    val ui by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var targetDialogOpen by remember { mutableStateOf(false) }

    // OpenDocument, not GetContent: the user is picking a document, and the URI
    // stays readable for the duration of the import.
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::import) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.health_data_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader(stringResource(R.string.health_data_personal))
            ChompassSurfaceColumn(padding = 0.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.health_data_uric_target),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(
                                R.string.health_data_uric_target_value,
                                ui.uricTarget,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = {
                        vm.clearTargetError()
                        targetDialogOpen = true
                    }) {
                        Text(stringResource(R.string.health_data_change))
                    }
                }
            }
            Text(
                stringResource(R.string.health_data_uric_target_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // A failed write has no dialog to live in: the dialog is already
            // closed by then, and silence would look like a successful save.
            ui.targetError?.let { error ->
                Text(
                    text = when (error) {
                        UricTargetError.OUT_OF_RANGE -> stringResource(
                            R.string.health_data_target_range,
                            LevelResolver.TARGET_MIN,
                            LevelResolver.TARGET_MAX_EXCLUSIVE - 1,
                        )

                        UricTargetError.SAVE_FAILED -> stringResource(R.string.health_data_save_failed)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.warning,
                )
            }

            SectionHeader(
                title = stringResource(R.string.health_data_backup),
                meta = ui.lastExportAt?.let {
                    stringResource(R.string.health_data_last_export, TimeUtils.toMonthDayTime(it))
                },
            )
            ChompassSurfaceColumn {
                Text(
                    stringResource(R.string.health_data_backup_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    countsText(ui.counts),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChompassPrimaryButton(
                    text = stringResource(
                        if (ui.exporting) R.string.health_data_exporting else R.string.health_data_export,
                    ),
                    onClick = {
                        vm.export { intent ->
                            runCatching { context.startActivity(intent) }
                        }
                    },
                    enabled = !ui.exporting && !ui.importing,
                )
                if (ui.exportOverdue) {
                    Text(
                        text = ui.daysSinceExport?.let { days ->
                            stringResource(R.string.health_data_export_overdue_days, days)
                        } ?: stringResource(R.string.health_data_export_overdue_never),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.warning,
                    )
                }

                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.health_data_import_title),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.health_data_import_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(
                        onClick = {
                            if (!ui.importing) {
                                importLauncher.launch(
                                    arrayOf(
                                        "application/zip",
                                        "application/octet-stream",
                                        "*/*",
                                    )
                                )
                            }
                        },
                    ) {
                        Text(
                            stringResource(
                                if (ui.importing) {
                                    R.string.health_data_importing
                                } else {
                                    R.string.health_data_import_action
                                },
                            )
                        )
                    }
                }
                // Must be stated: the fear is "importing wipes what I have", and
                // an unstated fear means the user never taps it.
                Text(
                    stringResource(R.string.health_data_import_merge_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionHeader(stringResource(R.string.health_data_app))
            ChompassSurfaceColumn(padding = 0.dp) {
                SettingRow(
                    title = stringResource(R.string.health_data_version),
                    value = BuildConfig.VERSION_NAME,
                )
                HorizontalDivider()
                SettingRow(
                    title = stringResource(R.string.health_data_storage),
                    value = stringResource(R.string.health_data_storage_value),
                )
            }

            Text(
                stringResource(R.string.health_med_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.navigationBarsPadding().height(24.dp))
        }
    }

    if (targetDialogOpen) {
        UricTargetDialog(
            current = ui.uricTarget,
            onDismiss = {
                targetDialogOpen = false
                vm.clearTargetError()
            },
            onConfirm = { value ->
                vm.setTarget(value)
                targetDialogOpen = false
            },
        )
    }

    ui.notice?.let { notice ->
        BackupNoticeDialog(notice = notice, onDismiss = vm::dismissNotice)
    }
}

@Composable
private fun SectionHeader(title: String, meta: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (meta != null) {
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingRow(title: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "12 records · uric 5 · pain 3 · doses 4" — the counts, not a history. */
@Composable
private fun countsText(counts: HealthBackupCounts): String = stringResource(
    R.string.health_data_counts,
    counts.records,
    counts.uric,
    counts.pain,
    counts.dose,
)

/**
 * The target is a threshold, not a goal to chase: it decides the on-target badge
 * and the "how far off" number. The range is the same one the repository
 * enforces, so the dialog cannot accept a value the write would reject.
 */
@Composable
private fun UricTargetDialog(
    current: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(current.toString()) }
    val parsed = text.trim().toIntOrNull()
    val valid = parsed != null && LevelResolver.isValidTarget(parsed)

    ChompassDialog(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.health_data_uric_target),
            style = MaterialTheme.typography.titleMedium,
        )
        ChompassTextField(
            value = text,
            onValueChange = { text = it.filter { c -> c.isDigit() }.take(3) },
            placeholder = current.toString(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Text(
            stringResource(
                R.string.health_data_target_range,
                LevelResolver.TARGET_MIN,
                LevelResolver.TARGET_MAX_EXCLUSIVE - 1,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChompassDialogActions(
            primaryText = stringResource(R.string.action_save),
            onPrimary = { parsed?.let(onConfirm) },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = onDismiss,
            primaryEnabled = valid,
        )
    }
}

@Composable
private fun BackupNoticeDialog(notice: HealthBackupNotice, onDismiss: () -> Unit) {
    ChompassDialog(onDismissRequest = onDismiss) {
        Text(
            stringResource(
                when (notice) {
                    is HealthBackupNotice.Imported -> R.string.health_data_import_done_title
                    is HealthBackupNotice.ImportedNothing -> R.string.health_data_import_empty
                    HealthBackupNotice.ExportEmpty -> R.string.health_data_export_empty
                    HealthBackupNotice.ExportFailed -> R.string.health_data_export_failed
                    HealthBackupNotice.ImportUnreadable -> R.string.health_data_import_unreadable
                    HealthBackupNotice.ImportNoTables -> R.string.health_data_import_no_tables
                    HealthBackupNotice.ImportFailed -> R.string.health_data_import_failed
                },
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        when (notice) {
            is HealthBackupNotice.Imported -> {
                Text(
                    stringResource(
                        R.string.health_data_import_done,
                        notice.uric,
                        notice.pain,
                        notice.painUpdates,
                        notice.dose,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (notice.duplicates > 0) {
                    Text(
                        stringResource(R.string.health_data_import_dup, notice.duplicates),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (notice.skipped > 0) {
                    Text(
                        stringResource(R.string.health_data_import_skipped, notice.skipped),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.warning,
                    )
                }
                // Up to three offending lines: enough to recognise the file, not
                // enough to turn the dialog into a log.
                notice.issues.forEach { issue ->
                    Text(
                        stringResource(R.string.health_data_import_issue, issue.file, issue.line),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is HealthBackupNotice.ImportedNothing -> Text(
                stringResource(R.string.health_data_import_skipped, notice.skipped),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> Unit
        }
        ChompassDialogActions(
            primaryText = stringResource(R.string.action_ok),
            onPrimary = onDismiss,
        )
    }
}
