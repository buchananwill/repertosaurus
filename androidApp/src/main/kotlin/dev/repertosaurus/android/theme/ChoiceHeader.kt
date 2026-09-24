package dev.repertosaurus.android.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The label line of one choice in a single-choice list: the ramp picker's rows and the performer list.
 * Selected, it is `Ink` with `Paper` text and an `Ochre` tick, so the choice is not marked by colour
 * alone; [selectedNote] follows the tick. The caller owns the `selectable`, which may cover more than
 * this line.
 */
@Composable
internal fun ChoiceHeader(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    selectedNote: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) Tokens.Ink else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) Tokens.Paper else Tokens.Ink,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text(
                if (selectedNote == null) TICK else "$TICK $selectedNote",
                style = MaterialTheme.typography.labelLarge,
                color = Tokens.Ochre,
            )
        }
    }
}

/** A glyph, not a sentence. */
private const val TICK = "✓"
