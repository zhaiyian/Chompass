package app.chompass.health.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.chompass.ui.components.ChompassSurfaceColumn

/**
 * The health module's spacing scale, in one place.
 *
 * The three sections used to disagree: the uric screen threaded its own 8 and
 * 10 dp spacers inside cards, the medication screen had none at all, and the
 * disclosure line sat exactly one card-gap away from the content above it. One
 * scale means a new card cannot invent a fourth rhythm.
 */
object HealthLayout {
    /** Left/right inset of every scrollable health screen. */
    val ScreenPad = 16.dp

    /** Between two cards, and around section headers. */
    val CardGap = 12.dp

    /** Between the rows of one card: title, value, caption. */
    val CardSpacing = 8.dp

    /** A caption that belongs to the line above it rather than to the card. */
    val TightSpacing = 4.dp

    /** Top inset that separates the disclosure from the last card. */
    val DisclosureGap = 4.dp
}

/**
 * A card in the health module.
 *
 * Wraps [ChompassSurfaceColumn] only to supply the vertical rhythm — a card that
 * stacks a label, a headline number and two captions needs the gaps to be part
 * of the card, not a `Spacer` between every pair of lines.
 *
 * Pass `spacing = 0.dp` for a card whose children bring their own padding, such
 * as the per-dose list where each row already has 12 dp inside.
 */
@Composable
fun HealthCard(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    spacing: Dp = HealthLayout.CardSpacing,
    cornerRadius: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    ChompassSurfaceColumn(
        modifier = modifier,
        cornerRadius = cornerRadius,
        padding = padding,
        verticalSpacing = spacing,
        content = content,
    )
}

/**
 * Section heading: a short title, an optional caption under it, and either a
 * count on the right or one action.
 *
 * No top padding: the list's own `CardGap` already separates this row from the
 * card above, and adding a second inset here is what made the header sit 16 dp
 * from one card and 12 dp from the next.
 */
@Composable
fun HealthSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    meta: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            action != null -> action()
            meta != null -> Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = HealthLayout.TightSpacing),
            )
        }
    }
}

/**
 * The "not a diagnosis" line at the bottom of every health screen.
 *
 * Pure text on the background, never inside a card: it is the one thing on the
 * page that must not read as part of the data.
 */
@Composable
fun HealthDisclosure(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(top = HealthLayout.DisclosureGap),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
