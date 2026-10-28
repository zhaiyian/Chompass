package app.chompass.health.ui.pain

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import app.chompass.R
import app.chompass.health.domain.DictResolver
import app.chompass.health.domain.model.DictType
import app.chompass.health.domain.model.PainSide
import app.chompass.health.domain.pain.JointOption
import app.chompass.health.domain.pain.PainSiteCatalog
import app.chompass.health.domain.pain.RegionGroup
import app.chompass.ui.components.ChompassTextField

/**
 * B8 — pick the site, as a cascade.
 *
 * Why a sheet instead of three chip rows in the form: the entry screen is
 * already a long form, a cascade adds up to four more rows, and "tap a parent,
 * children appear" makes every field below jump. The sheet has the room to show
 * region → toe/finger → joint → side as one path.
 *
 * The cascade does **not** always have three steps:
 * - only toes and fingers ask "which one?" (`RegionGroup.hasDigits`);
 * - wrist / ankle / knee / other go straight from region to joint;
 * - a joint that needs no side (spine, "can't say") hides the side step entirely
 *   and records `NA`.
 *
 * "Recently used" pays back the taps the cascade costs: the most common site
 * goes from 2 taps to 4, so one tap here must reach it directly.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PainSiteSheet(
    catalog: List<RegionGroup>,
    recentSites: List<RecentSiteUi>,
    dict: DictResolver,
    initialJointId: String?,
    initialSide: PainSide,
    onDismiss: () -> Unit,
    onConfirm: (jointId: String, side: PainSide) -> Unit,
    /** Custom sites land in the dictionary and are confirmed immediately, since they are not in the tree. */
    onAddCustomSite: (label: String, side: PainSide) -> Unit,
) {
    val initial = PainSiteCatalog.find(catalog, initialJointId)
    var regionId by remember { mutableStateOf(initial?.first?.id) }
    var digitId by remember {
        mutableStateOf(
            initial?.first?.digits
                ?.firstOrNull { group -> group.joints.any { it.id == initial.second.id } }?.id,
        )
    }
    var jointId by remember { mutableStateOf(initial?.second?.id) }
    var side by remember { mutableStateOf(initialSide) }
    var customOpen by remember { mutableStateOf(false) }

    val region = catalog.firstOrNull { it.id == regionId }
    val digit = region?.digits?.firstOrNull { it.id == digitId }
    val joint = when {
        digit != null -> digit.joints.firstOrNull { it.id == jointId }
        region != null -> region.joints.firstOrNull { it.id == jointId }
        else -> null
    }
    // The joint decides whether a side is asked for: a leftover "right" on the
    // spine would record something the user never chose.
    val sideRequired = joint?.sideRequired ?: true
    val effectiveSide = if (sideRequired) side else PainSide.NA

    PainSheetFrame(
        title = stringResource(R.string.health_pain_site_sheet_title),
        onDismiss = onDismiss,
        scrollable = true,
    ) {
        PainSheetSubtitle(stringResource(R.string.health_pain_site_sheet_body))

        if (recentSites.isNotEmpty()) {
            PainFieldLabel(stringResource(R.string.health_pain_site_recent))
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                recentSites.forEach { recent ->
                    val inTree = PainSiteCatalog.find(catalog, recent.siteCode) != null
                    FilterChip(
                        selected = recent.siteCode == jointId,
                        onClick = {
                            if (!inTree) {
                                // Custom site (a `U_SITE_*` row): not in the tree, so confirm now.
                                onConfirm(recent.siteCode, recent.side)
                            } else {
                                val hit = PainSiteCatalog.find(catalog, recent.siteCode)!!
                                // Fill all three levels at once, otherwise the selected
                                // state below would contradict the chip that was tapped.
                                regionId = hit.first.id
                                digitId = hit.first.digits
                                    .firstOrNull { group -> group.joints.any { it.id == recent.siteCode } }?.id
                                jointId = recent.siteCode
                                side = recent.side
                            }
                        },
                        label = { Text(recentLabel(catalog, dict, recent), maxLines = 1) },
                    )
                }
            }
        }

        PainFieldLabel(stringResource(R.string.health_pain_site_step_region))
        PainChipGrid(
            items = catalog.map { it.id to it.name },
            selectedId = regionId,
            onPick = { id ->
                regionId = id
                digitId = null
                jointId = null
            },
        )

        if (region != null && region.hasDigits) {
            PainFieldLabel(stringResource(R.string.health_pain_site_step_digit))
            PainChipGrid(
                items = region.digits.map { it.id to it.name },
                selectedId = digitId,
                onPick = { id ->
                    digitId = id
                    jointId = null
                },
            )
        }

        if (region != null) {
            val joints = digit?.joints ?: region.joints
            if (joints.isNotEmpty()) {
                PainFieldLabel(
                    stringResource(
                        if (region.hasDigits) R.string.health_pain_site_step_joint
                        else R.string.health_pain_site_step_joint_short,
                    ),
                )
                PainChipGrid(
                    items = joints.map { it.id to it.displayName },
                    selectedId = jointId,
                    onPick = { jointId = it },
                )
            }
        }

        if (joint != null && sideRequired) {
            PainFieldLabel(
                stringResource(
                    if (region?.hasDigits == true) R.string.health_pain_site_step_side
                    else R.string.health_pain_site_step_side_short,
                ),
            )
            PainChipGrid(
                items = PainSide.entries
                    .filter { it != PainSide.NA }
                    .map { it.name to painSideLabel(it) },
                selectedId = side.name,
                onPick = { id -> side = PainSide.valueOf(id) },
            )
        }

        if (region?.id == REGION_OTHER) {
            TextButton(onClick = { customOpen = true }) {
                Text(stringResource(R.string.health_pain_site_custom))
            }
        }

        joint?.let { selected ->
            val path = PainSiteCatalog.pathOf(catalog, selected.id) ?: selected.displayName
            val head = if (selected.sideRequired && effectiveSide != PainSide.NA) {
                stringResource(R.string.health_site_side, painSideLabel(effectiveSide), path)
            } else {
                path
            }
            val summary = selected.anatomyOrNull
                ?.let { stringResource(R.string.health_pain_site_summary, head, it) }
                ?: head
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    summary,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        PainHelper(stringResource(R.string.health_pain_site_helper))

        PainSheetActions(
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = onDismiss,
            primaryText = stringResource(R.string.health_pain_site_confirm),
            onPrimary = { joint?.let { onConfirm(it.id, effectiveSide) } },
            primaryEnabled = joint != null,
        )
    }

    if (customOpen) {
        CustomSiteDialog(
            onDismiss = { customOpen = false },
            onConfirm = { label ->
                customOpen = false
                onAddCustomSite(label, if (sideRequired) side else PainSide.NA)
            },
        )
    }
}

/** The other-large-joints region id; only there can a user add a site of their own. */
private const val REGION_OTHER = "OTHER"

@Composable
private fun CustomSiteDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val trimmed = text.trim()
    val valid = trimmed.isNotEmpty() && trimmed.length <= 12

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.health_pain_custom_site_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChompassTextField(
                    value = text,
                    onValueChange = { text = it.take(20) },
                    placeholder = stringResource(R.string.health_pain_custom_site_hint),
                )
                Text(
                    if (valid) {
                        stringResource(R.string.health_pain_custom_site_helper)
                    } else {
                        stringResource(R.string.health_pain_custom_invalid)
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
            TextButton(onClick = { if (valid) onConfirm(trimmed) }, enabled = valid) {
                Text(stringResource(R.string.action_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Equal-width three-column grid.
 *
 * Fixed columns rather than `FlowRow`: with FlowRow the wrap point follows the
 * text length, so "second toe" and "forefoot / instep" push the third column to
 * the next line and the block looks broken. Short rows are padded with spacers
 * so column width never changes.
 */
@Composable
private fun PainChipGrid(
    items: List<Pair<String, String>>,
    selectedId: String?,
    onPick: (String) -> Unit,
) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (id, label) ->
                    Box(Modifier.weight(1f)) {
                        FilterChip(
                            selected = id == selectedId,
                            onClick = { onPick(id) },
                            modifier = Modifier.fillMaxWidth(),
                            label = {
                                Text(
                                    label,
                                    maxLines = 2,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                        )
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * Label for a "recently used" chip.
 *
 * Reads the path out of the catalogue (already in the current language thanks to
 * the repository) and falls back to the dictionary for user-added sites, which
 * the tree does not contain.
 */
@Composable
private fun recentLabel(
    catalog: List<RegionGroup>,
    dict: DictResolver,
    recent: RecentSiteUi,
): String {
    val found = PainSiteCatalog.find(catalog, recent.siteCode)
    if (found == null) return dict.label(DictType.SITE, recent.siteCode).text
    val path = PainSiteCatalog.pathOf(catalog, recent.siteCode) ?: found.second.displayName
    val needsSide = found.second.sideRequired && recent.side != PainSide.NA
    return if (needsSide) {
        stringResource(R.string.health_site_side, painSideLabel(recent.side), path)
    } else {
        path
    }
}

/** Kept for the entry screen's summary line: "left big toe · base" for a joint id. */
@Composable
fun painJointPath(
    catalog: List<RegionGroup>,
    jointId: String?,
    side: PainSide,
): String? {
    val joint: JointOption = PainSiteCatalog.find(catalog, jointId)?.second ?: return null
    val path = PainSiteCatalog.pathOf(catalog, jointId) ?: joint.displayName
    return if (joint.sideRequired && side != PainSide.NA) {
        stringResource(R.string.health_site_side, painSideLabel(side), path)
    } else {
        path
    }
}
