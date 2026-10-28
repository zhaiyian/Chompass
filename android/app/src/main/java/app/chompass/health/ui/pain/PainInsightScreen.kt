package app.chompass.health.ui.pain

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.chompass.AppContainer
import app.chompass.R
import app.chompass.health.domain.DictResolver
import app.chompass.health.domain.model.DictType
import app.chompass.ui.components.ChompassSurfaceColumn
import app.chompass.ui.navigation.BottomNavScrollPadding

/**
 * B6 — the patterns page.
 *
 * The page exists to answer "is there a pattern?" without pretending to answer
 * "why?". So the numbers are counts and shares, never a cause: a site appearing
 * four times out of six is a fact about the diary, not about the disease, and
 * the copy says so twice (the counting rule and the disclaimer).
 *
 * Below the minimum sample size it still shows the counts but refuses to
 * summarise them — with two records every bar is 50 %, which reads as a pattern
 * when it is just arithmetic.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PainInsightScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val vm: PainListViewModel = viewModel(
        factory = PainListViewModel.Factory(container.healthDiary),
    )
    val ui by vm.state.collectAsStateWithLifecycle()
    val dict by container.healthDiary.dictResolver.collectAsStateWithLifecycle(
        initialValue = DictResolver(),
    )
    val insight = ui.insight

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.health_pain_insight_title)) },
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
            OverviewCard(insight)
            if (!insight.hasAnything) {
                ChompassSurfaceColumn {
                    Text(
                        stringResource(
                            R.string.health_pain_insight_window_empty,
                            insight.windowDays,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.health_pain_insight_window_empty_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                if (!insight.sampleEnough) {
                    ChompassSurfaceColumn {
                        Text(
                            stringResource(R.string.health_pain_insight_sample_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(
                                R.string.health_pain_insight_sample_body,
                                insight.missingForSample,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                RowBars(
                    title = stringResource(R.string.health_pain_insight_sites),
                    rows = insight.siteRows,
                    labelOf = { row -> painSiteLabel(dict, row.code, row.side) },
                )
                RowBars(
                    title = stringResource(R.string.health_pain_insight_triggers),
                    rows = insight.triggerRows,
                    labelOf = { row -> dict.label(DictType.TRIGGER, row.code).text },
                )
                if (insight.noTriggerCount > 0) {
                    Text(
                        stringResource(R.string.health_pain_insight_no_trigger, insight.noTriggerCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RowBars(
                    title = stringResource(R.string.health_pain_insight_natures),
                    rows = insight.natureRows,
                    labelOf = { row -> dict.label(DictType.PAIN_NATURE, row.code).text },
                )
                ChompassSurfaceColumn {
                    Text(
                        stringResource(R.string.health_pain_insight_affect),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(
                            R.string.health_pain_insight_affect_value,
                            insight.walkAffected,
                            insight.sleepAffected,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.health_pain_insight_affect_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.health_pain_insight_count_rule),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.health_pain_insight_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(BottomNavScrollPadding))
        }
    }
}

@Composable
private fun OverviewCard(insight: PainInsightUi) {
    ChompassSurfaceColumn {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.health_pain_insight_window, insight.windowDays),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.health_pain_insight_total, insight.total),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        HorizontalDivider()
        StatRow(
            label = stringResource(R.string.health_pain_insight_avg),
            value = insight.avgDurationMinutes?.let { painDurationMinutesText(it) }
                ?: stringResource(R.string.health_pain_detail_none),
        )
        StatRow(
            label = stringResource(R.string.health_pain_insight_median),
            value = insight.medianNrs?.toString()
                ?: stringResource(R.string.health_pain_detail_none),
        )
        StatRow(
            label = stringResource(R.string.health_pain_insight_max),
            value = insight.maxNrs?.toString()
                ?: stringResource(R.string.health_pain_detail_none),
        )
        Text(
            // Running flares have no duration yet; saying so once is cheaper than
            // a footnote on every average that silently excluded them.
            if (insight.ongoing > 0) {
                stringResource(R.string.health_pain_insight_method_ongoing, insight.ongoing)
            } else {
                stringResource(R.string.health_pain_insight_method)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * One ranked block: label, share bar, "4 / 6".
 *
 * [labelOf] is a parameter rather than a dictionary lookup inside: sites need a
 * side prefix and their own dictionary type, triggers and qualities do not.
 */
@Composable
private fun RowBars(
    title: String,
    rows: List<InsightRow>,
    labelOf: @Composable (InsightRow) -> String,
) {
    if (rows.isEmpty()) return
    ChompassSurfaceColumn {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        rows.forEach { row ->
            BarRow(
                label = labelOf(row),
                share = row.share,
                count = row.count,
                total = row.total,
            )
        }
    }
}

@Composable
private fun BarRow(label: String, share: Float, count: Int, total: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(share)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.health_pain_insight_share, count, total),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
