package app.chompass.health.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.chompass.health.model.UricLevel
import app.chompass.ui.theme.success
import app.chompass.ui.theme.warning

/**
 * Status colours for uric-acid levels.
 *
 * Colour is a third redundant channel on top of position (the point sits above
 * or below the target line) and the label text — never the only signal. On
 * target and near target stay in the non-alarming pair (green / theme accent),
 * so "slightly above your own target" does not read as an alarm; only the two
 * clinical bands go warm.
 *
 * Badges paint this colour as a 14 % tint with the colour itself as the text, so
 * it stays legible in light, dark and OLED themes without a second palette.
 */
@Composable
fun uricLevelColor(level: UricLevel): Color = when (level) {
    UricLevel.BELOW_TARGET -> MaterialTheme.colorScheme.success
    UricLevel.NEAR -> MaterialTheme.colorScheme.primary
    UricLevel.HIGH -> MaterialTheme.colorScheme.warning
    UricLevel.VERY_HIGH -> MaterialTheme.colorScheme.error
}
