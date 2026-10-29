package app.chompass.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.chompass.ChompassApp
import app.chompass.R
import app.chompass.models.PurineLevel
import app.chompass.services.grounding.PurineRecord
import app.chompass.ui.theme.AppRadii
import app.chompass.ui.theme.AppTextOpacity
import app.chompass.ui.theme.success
import app.chompass.ui.theme.warning
import java.util.Locale

/**
 * Resolves the bundled purine table for the composition, so a row can ask for a
 * food's band while it renders. Null (no badge) when the asset is absent — the
 * screenshot previews and JVM tests compose without the app container.
 */
@Composable
internal fun rememberPurineLookup(): (String) -> PurineRecord? {
    val context = LocalContext.current
    val index = remember(context) {
        runCatching {
            (context.applicationContext as? ChompassApp)?.container?.purineFoodIndex
        }.getOrNull()
    }
    return remember(index) { { name: String -> index?.findFor(name) } }
}

/**
 * The purine row of a food: the sheet's own band (low / medium / high) plus the
 * value it was derived from. Shown wherever an entry's nutrition is reviewed;
 * nothing renders when the table has no convincing match for the name, which is
 * most branded and AI-invented foods.
 */
@Composable
internal fun PurineInfoRow(name: String) {
    val lookup = rememberPurineLookup()
    val record = remember(name, lookup) { lookup(name) } ?: return
    SheetPillRow {
        Text(
            stringResource(R.string.purine_label),
            fontSize = 16.sp,
            modifier = Modifier.weight(1f),
        )
        PurineLevelChip(record.level)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.purine_per_100g, formatPurineMg(record.purineMgPer100g)),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppTextOpacity.Muted),
        )
    }
}

/** Small capsule: "Low purine" / "中嘌呤" / … in the band's own color. */
@Composable
internal fun PurineLevelChip(
    level: PurineLevel,
    modifier: Modifier = Modifier,
) {
    val accent = purineLevelColor(level)
    Text(
        text = stringResource(level.labelRes()),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = accent,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(AppRadii.Field))
            .background(accent.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** Rows with room for the chip put it on the trailing edge of the name line. */
@Composable
internal fun PurineLevelChipFor(name: String, modifier: Modifier = Modifier) {
    val lookup = rememberPurineLookup()
    val record = remember(name, lookup) { lookup(name) } ?: return
    PurineLevelChip(record.level, modifier)
}

/**
 * Green / amber / red, using the semantic tones the rest of the app already
 * resolves against the active theme (success, warning, error).
 */
@Composable
internal fun purineLevelColor(level: PurineLevel): Color = when (level) {
    PurineLevel.LOW -> MaterialTheme.colorScheme.success
    PurineLevel.MEDIUM -> MaterialTheme.colorScheme.warning
    PurineLevel.HIGH -> MaterialTheme.colorScheme.error
}

private fun PurineLevel.labelRes(): Int = when (this) {
    PurineLevel.LOW -> R.string.purine_level_low
    PurineLevel.MEDIUM -> R.string.purine_level_medium
    PurineLevel.HIGH -> R.string.purine_level_high
}

/** One decimal, device locale — the same rule [FoodEntryEditMath.displayD] uses. */
internal fun formatPurineMg(mg: Double): String = String.format(Locale.getDefault(), "%.1f", mg)
