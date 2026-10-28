package app.chompass.health.ui.pain

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.chompass.ui.components.ChompassPrimaryButton
import app.chompass.ui.components.ChompassTextButton

/**
 * Chrome shared by the pain sheets.
 *
 * The Material3 drag handle is kept (the user can swipe it away) and every sheet
 * gets the same paddings, so "cancel" sits at the same height in all three.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PainSheetFrame(
    title: String,
    onDismiss: () -> Unit,
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** Cancel on the left, filled primary taking the rest — same shape as the source sheets. */
@Composable
fun PainSheetActions(
    dismissText: String,
    onDismiss: () -> Unit,
    primaryText: String,
    onPrimary: () -> Unit,
    primaryEnabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChompassTextButton(text = dismissText, onClick = onDismiss)
        ChompassPrimaryButton(
            text = primaryText,
            onClick = onPrimary,
            enabled = primaryEnabled,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Indented second line under a sheet title: "left big toe · started 10/21 08:15". */
@Composable
fun PainSheetSubtitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
