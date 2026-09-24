package dev.repertosaurus.android.theme

import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * VI3, VI5: **the one dialog**, a square `Paper` panel in a 3 dp `Ink` border on a 5 dp hard shadow, in a
 * window of its own. [title] is the question, in sentence case because it quotes the user's names. The panel
 * scrolls, so a long body or a large font scale never clips.
 *
 * VI21 (amended, D96): the panel springs in, from 0.96 of its size and a fade; with animations off it is
 * simply there (VI22).
 */
@Composable
internal fun InkDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        val entry = remember { Animatable(0f) }
        LaunchedEffect(entry) { entry.animateTo(1f, Motion.spring()) }
        Column(
            modifier = modifier
                // D98 #7: read in the draw phase only, so the entry recomposes nothing.
                .graphicsLayer {
                    val shown = entry.value
                    alpha = shown.coerceIn(0f, 1f)
                    scaleX = ENTRY_SCALE + (1f - ENTRY_SCALE) * shown
                    scaleY = scaleX
                }
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

private const val ENTRY_SCALE = 0.96f

/** A dialog's body copy. */
@Composable
internal fun DialogText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = Tokens.Ink, modifier = modifier)
}
