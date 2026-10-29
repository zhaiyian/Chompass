package app.chompass.health.ui.pain

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import app.chompass.health.domain.DictEntry
import app.chompass.health.domain.DictResolver
import app.chompass.health.domain.model.DictType
import app.chompass.ui.components.ChompassPrimaryButton
import app.chompass.health.ui.HealthCard
import app.chompass.ui.components.ChompassTextField
import kotlin.math.roundToInt

/**
 * B2 / B4 — log a new flare, or edit an existing one.
 *
 * One screen for both, because the fields are identical and the differences are
 * two extra blocks (end time, delete). Notes on the shape of the form:
 *
 * - **No end time on create.** At the moment a flare is logged it cannot already
 *   be over, and offering the field only tempts the user into closing it early.
 * - **The site is a summary row**, not 39 chips: the cascade lives in
 *   [PainSiteSheet], which also owns the side step (spine has none).
 * - **The error sits at the very top.** Save is at the top bar, and a message
 *   under the button a screen away reads as "the tap did nothing".
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PainEntryScreen(
    container: AppContainer,
    episodeId: Long?,
    onDone: () -> Unit,
) {
    val vm: PainFormViewModel = viewModel(
        factory = PainFormViewModel.Factory(container.healthDiary, episodeId),
    )
    val ui by vm.state.collectAsStateWithLifecycle()
    val dict by container.healthDiary.dictResolver.collectAsStateWithLifecycle(
        initialValue = DictResolver(),
    )

    var siteSheetOpen by remember { mutableStateOf(false) }
    var customNatureOpen by remember { mutableStateOf(false) }
    var customTriggerOpen by remember { mutableStateOf(false) }
    var customRejected by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(saved) { if (saved) onDone() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (ui.isEditing) R.string.health_pain_entry_edit_title
                            else R.string.health_pain_entry_add_title,
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
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ui.error?.let { error ->
                Text(
                    painErrorMessage(error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            HealthCard {
                PainFieldLabel(stringResource(R.string.health_pain_entry_start))
                PainDateTimeRow(at = ui.startedAt, onChange = vm::setStartedAt)
                PainHelper(
                    stringResource(
                        R.string.health_pain_entry_running,
                        painDurationText(ui.startedAt, ui.endedAt ?: ui.now),
                    ),
                )
            }

            if (ui.isEditing) {
                HealthCard {
                    PainFieldLabel(stringResource(R.string.health_pain_entry_end))
                    val endAt = ui.endedAt
                    if (endAt == null) {
                        Text(
                            stringResource(R.string.health_pain_entry_not_ended),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        PainDateTimeRow(at = endAt, onChange = vm::setEndedAt)
                        if (endAt <= ui.startedAt) {
                            Text(
                                stringResource(R.string.health_pain_error_end_before_start),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (endAt != null) {
                        TextButton(onClick = { vm.setEndedAt(null) }) {
                            Text(stringResource(R.string.health_pain_entry_still_flaring))
                        }
                    }
                }
            }

            HealthCard {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PainFieldLabel(
                        stringResource(R.string.health_pain_entry_site),
                        Modifier.weight(1f),
                    )
                    TextButton(onClick = { siteSheetOpen = true }) {
                        Text(stringResource(R.string.health_pain_entry_change_site))
                    }
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clickable { siteSheetOpen = true },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline,
                    ),
                ) {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val label = siteSummary(ui, dict)
                        Text(
                            label ?: stringResource(R.string.health_pain_entry_pick_site),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (label == null) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f),
                        )
                        ui.joint?.anatomyOrNull?.let { anatomy ->
                            Text(
                                anatomy,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                PainHelper(stringResource(R.string.health_pain_entry_site_hint))
            }

            HealthCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PainFieldLabel(
                        stringResource(R.string.health_pain_entry_nrs),
                        Modifier.weight(1f),
                    )
                    Text(
                        stringResource(
                            R.string.health_pain_entry_nrs_value,
                            ui.nrs,
                            painNrsWord(ui.nrs),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = painNrsColor(ui.nrs),
                    )
                }
                Slider(
                    value = ui.nrs.toFloat(),
                    onValueChange = { vm.setNrs(it.roundToInt()) },
                    valueRange = 0f..10f,
                    steps = 9,
                )
                Text(
                    painNrsGuide(ui.nrs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (ui.natures.isNotEmpty()) {
                ChipBlock(
                    label = stringResource(R.string.health_pain_entry_nature),
                    entries = ui.natures,
                    selected = ui.natureCodes,
                    onToggle = vm::toggleNature,
                    onAddCustom = { customNatureOpen = true },
                )
            }
            if (ui.triggers.isNotEmpty()) {
                ChipBlock(
                    label = stringResource(R.string.health_pain_entry_trigger),
                    entries = ui.triggers,
                    selected = ui.triggerCodes,
                    onToggle = vm::toggleTrigger,
                    onAddCustom = { customTriggerOpen = true },
                )
            }

            HealthCard {
                SwitchRow(
                    label = stringResource(R.string.health_pain_entry_affect_walk),
                    checked = ui.affectWalk,
                    onChange = vm::setAffectWalk,
                )
                SwitchRow(
                    label = stringResource(R.string.health_pain_entry_affect_sleep),
                    checked = ui.affectSleep,
                    onChange = vm::setAffectSleep,
                )
            }

            HealthCard {
                PainFieldLabel(stringResource(R.string.health_pain_entry_note))
                ChompassTextField(
                    value = ui.note,
                    onValueChange = vm::setNote,
                    placeholder = stringResource(R.string.health_pain_entry_note_hint),
                    singleLine = false,
                    minLines = 3,
                )
            }

            ChompassPrimaryButton(
                text = stringResource(
                    if (ui.saving) R.string.health_pain_entry_saving
                    else R.string.health_pain_entry_save,
                ),
                onClick = { vm.save { saved = true } },
                enabled = ui.canSave,
            )
            if (ui.isEditing) {
                // Destructive, below the primary and un-filled: it must be
                // reachable without competing with "save" for the eye.
                TextButton(onClick = { confirmDelete = true }) {
                    Text(
                        stringResource(R.string.health_pain_entry_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(24.dp))
        }
    }

    if (siteSheetOpen) {
        PainSiteSheet(
            catalog = ui.catalog,
            recentSites = ui.recentSites,
            dict = dict,
            initialJointId = ui.siteCode.takeIf { ui.joint != null },
            initialSide = ui.side,
            onDismiss = { siteSheetOpen = false },
            onConfirm = { jointId, side ->
                siteSheetOpen = false
                vm.pickSite(jointId, side)
            },
            onAddCustomSite = { label, side ->
                siteSheetOpen = false
                vm.addCustomSite(label) { code ->
                    if (code != null) vm.pickSite(code, side)
                }
            },
        )
    }

    if (customNatureOpen) {
        PainCustomItemDialog(
            title = stringResource(R.string.health_pain_custom_nature_title),
            hint = stringResource(R.string.health_pain_custom_nature_hint),
            rejected = customRejected,
            onDismiss = {
                customNatureOpen = false
                customRejected = false
            },
            onConfirm = { label ->
                vm.addCustomNature(label) { ok ->
                    customRejected = !ok
                    if (ok) customNatureOpen = false
                }
            },
        )
    }

    if (customTriggerOpen) {
        PainCustomItemDialog(
            title = stringResource(R.string.health_pain_custom_trigger_title),
            hint = stringResource(R.string.health_pain_custom_trigger_hint),
            rejected = customRejected,
            onDismiss = {
                customTriggerOpen = false
                customRejected = false
            },
            onConfirm = { label ->
                vm.addCustomTrigger(label) { ok ->
                    customRejected = !ok
                    if (ok) customTriggerOpen = false
                }
            },
        )
    }

    if (confirmDelete) {
        val site = siteSummary(ui, dict) ?: stringResource(R.string.health_pain_entry_pick_site)
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.health_pain_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.health_pain_delete_body,
                        site,
                        painTimeLabel(ui.startedAt, ui.now),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        vm.delete { saved = true }
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

/**
 * "Left big toe · base" for a tree joint, the raw dictionary label for a
 * user-defined site, null when nothing has been picked yet.
 */
@Composable
private fun siteSummary(ui: PainFormUiState, dict: DictResolver): String? {
    if (ui.siteCode.isBlank()) return null
    val fromTree = painJointPath(ui.catalog, ui.siteCode, ui.side)
    if (fromTree != null) return fromTree
    return dict.label(DictType.SITE, ui.siteCode).text.takeIf { it.isNotBlank() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipBlock(
    label: String,
    entries: List<DictEntry>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onAddCustom: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PainFieldLabel(label, Modifier.weight(1f))
            TextButton(onClick = onAddCustom) {
                Text(stringResource(R.string.health_pain_entry_custom))
            }
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            entries.forEach { entry ->
                FilterChip(
                    selected = entry.code in selected,
                    onClick = { onToggle(entry.code) },
                    // User-added entries are outlined and smaller, so "mine" is
                    // distinguishable from the built-in vocabulary at a glance.
                    label = {
                        Text(
                            entry.label,
                            style = if (entry.builtin) {
                                MaterialTheme.typography.labelLarge
                            } else {
                                MaterialTheme.typography.labelMedium
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Add a word of your own to nature / trigger. [rejected] marks a failed write. */
@Composable
private fun PainCustomItemDialog(
    title: String,
    hint: String,
    rejected: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val trimmed = text.trim()
    val lengthOk = trimmed.isNotEmpty() && trimmed.length <= 12

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChompassTextField(
                    value = text,
                    onValueChange = { text = it.take(20) },
                    placeholder = hint,
                )
                Text(
                    when {
                        rejected -> stringResource(R.string.health_pain_error_save)
                        lengthOk -> stringResource(R.string.health_pain_custom_helper)
                        else -> stringResource(R.string.health_pain_custom_invalid)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (lengthOk && !rejected) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (lengthOk) onConfirm(trimmed) }, enabled = lengthOk) {
                Text(stringResource(R.string.action_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
