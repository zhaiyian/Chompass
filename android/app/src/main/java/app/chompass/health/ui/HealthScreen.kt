package app.chompass.health.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.chompass.AppContainer
import app.chompass.R
import app.chompass.health.ui.med.MedSection
import app.chompass.health.ui.pain.PainSection
import app.chompass.health.ui.uric.UricSection
import app.chompass.ui.components.ChompassSegmentedTabs
import app.chompass.ui.navigation.BottomOverlayPadding

/** Which module the Health tab is showing. */
private enum class HealthSection { MED, URIC, PAIN }

/**
 * The Health tab — home of the modules ported from the standalone UricDiary app.
 *
 * A segmented row rather than more bottom tabs: the bottom bar is already at five
 * destinations, and these are three readings of one idea ("my body this week"),
 * not three places in the app.
 *
 * The FAB is per-section because the primary action differs: "log a reading" and
 * "log a flare" both create a row, but in different tables and with different
 * forms. One FAB that guesses from the current segment is still one obvious tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthScreen(
    container: AppContainer,
    onOpenReading: (Long?) -> Unit,
    onAddPain: () -> Unit,
    onOpenPainEpisode: (Long) -> Unit,
    onOpenPainInsight: () -> Unit,
    onOpenMedLog: () -> Unit,
    onOpenMedManage: () -> Unit,
    onRecordExtra: (medicationId: Long?) -> Unit,
    onOpenData: () -> Unit,
) {
    // Medication first: it is the daily action; uric acid is periodic and pain is episodic.
    var section by rememberSaveable { mutableStateOf(HealthSection.MED) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        // Fixed, not the current section: the segmented control one
                        // line below already names the section, and a title that
                        // changes under it made the header jump on every switch.
                        // Reuses the nav label rather than adding a second string
                        // for the same word.
                        stringResource(R.string.nav_health),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    // One entry for the diary's own settings and backup: the uric
                    // target only means something in here, and so does the archive.
                    IconButton(onClick = onOpenData) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.health_data_title),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                ChompassSegmentedTabs(
                    options = HealthSection.entries,
                    selected = section,
                    onSelect = { section = it },
                    label = { stringResource(it.labelRes()) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = HealthLayout.ScreenPad,
                            end = HealthLayout.ScreenPad,
                            top = HealthLayout.TightSpacing,
                            // Pairs with the content's top inset below: the tabs sit
                            // one card-gap away from the first card.
                            bottom = HealthLayout.CardSpacing,
                        ),
                )
                val contentPadding = PaddingValues(
                    start = HealthLayout.ScreenPad,
                    top = HealthLayout.TightSpacing,
                    end = HealthLayout.ScreenPad,
                    // Room for the FAB: without it the last row ends under it.
                    bottom = 64.dp,
                )
                when (section) {
                    HealthSection.MED -> MedSection(
                        container = container,
                        onOpenLog = onOpenMedLog,
                        onManageMeds = onOpenMedManage,
                        onRecordExtra = onRecordExtra,
                        modifier = Modifier.weight(1f),
                        contentPadding = contentPadding,
                    )

                    HealthSection.URIC -> UricSection(
                        container = container,
                        onOpenReading = onOpenReading,
                        modifier = Modifier.weight(1f),
                        contentPadding = contentPadding,
                    )

                    HealthSection.PAIN -> PainSection(
                        container = container,
                        onOpenEpisode = onOpenPainEpisode,
                        onOpenInsight = onOpenPainInsight,
                        modifier = Modifier.weight(1f),
                        contentPadding = contentPadding,
                    )
                }
            }
            // Overlaid rather than passed to the Scaffold FAB slot: the outer nav
            // host renders content full-screen behind the docked bottom bar, so a
            // slotted FAB would end up underneath it.
            FloatingActionButton(
                onClick = {
                    when (section) {
                        HealthSection.MED -> onRecordExtra(null)
                        HealthSection.URIC -> onOpenReading(null)
                        HealthSection.PAIN -> onAddPain()
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    // Right-aligned with the cards rather than 8 dp inside them.
                    .padding(end = HealthLayout.ScreenPad, bottom = BottomOverlayPadding),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(section.addRes()),
                )
            }
        }
    }
}

@androidx.annotation.StringRes
private fun HealthSection.labelRes(): Int = when (this) {
    HealthSection.MED -> R.string.health_tab_med
    HealthSection.URIC -> R.string.health_tab_uric
    HealthSection.PAIN -> R.string.health_tab_pain
}

@androidx.annotation.StringRes
private fun HealthSection.addRes(): Int = when (this) {
    HealthSection.MED -> R.string.health_med_record_one
    HealthSection.URIC -> R.string.health_uric_add
    HealthSection.PAIN -> R.string.health_pain_add
}
