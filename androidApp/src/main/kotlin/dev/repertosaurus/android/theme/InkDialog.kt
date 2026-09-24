package dev.repertosaurus.android.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.repertosaurus.session.Messages

/**
 * VI3, VI5 (journal session 11, F43 N3): **the one dialog**, a square `Paper` panel inside a 3 dp `Ink`
 * border on a 5 dp hard shadow, in a window of its own. [title] is the question, in sentence case because
 * it quotes the user's names. The panel scrolls, so a long body or a large font scale never clips.
 */
@Composable
internal fun InkDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .hardShadow(Tokens.ShadowLarge)
                .background(Tokens.Paper)
                .inkBorder()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            title?.let { Text(it, style = MaterialTheme.typography.titleLarge, color = Tokens.Ink) }
            content()
        }
    }
}

/**
 * A dialog's two answers, stacked full width so neither is squeezed at a large font: [confirm] as the
 * dialog's one primary (VI12), and the way out beneath it, [Messages.CANCEL] unless [dismiss] says otherwise.
 */
@Composable
internal fun DialogActions(
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismiss: String = Messages.CANCEL,
    confirmEnabled: Boolean = true,
    confirmModifier: Modifier = Modifier,
    dismissModifier: Modifier = Modifier,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton(confirm, onClick = onConfirm, modifier = confirmModifier.fillMaxWidth(), enabled = confirmEnabled)
        // Inset by the primary's shadow, so the two faces end level.
        SecondaryButton(dismiss, onClick = onDismiss, modifier = dismissModifier.fillMaxWidth().padding(end = Tokens.ShadowLarge))
    }
}

/** A dialog's body copy. */
@Composable
internal fun DialogText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = Tokens.Ink, modifier = modifier)
}
