package dev.repertosaurus.android.theme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * VI13: a [SegmentFace] standing alone in its own 2 dp `Ink` border. **The toggle form** (journal session 11,
 * D94): a filter or a pick that is on or off, announced as a checkbox. It fills while [checked].
 */
@Composable
internal fun InkChip(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    SegmentFace(
        filled = checked,
        control = Modifier.toggleable(
            value = checked,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            enabled = enabled,
            role = Role.Checkbox,
            onValueChange = onCheckedChange,
        ),
        modifier = modifier.inkBorder(Tokens.StrokeRule),
        enabled = enabled,
    ) {
        ChipLabel(label)
    }
}

/**
 * **The choice form** (D98 #1): one of a set of which exactly one is chosen (a key, a date, a side), announced
 * as a radio button. It fills while [selected]; a tap chooses it.
 */
@Composable
internal fun InkChip(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    SegmentFace(
        filled = selected,
        control = Modifier.selectable(
            selected = selected,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onSelect,
        ),
        modifier = modifier.inkBorder(Tokens.StrokeRule),
        enabled = enabled,
    ) {
        ChipLabel(label)
    }
}

/**
 * **The action form** (D94): a chip that does something rather than holds a state, announced as a button. It
 * inks only while pressed, as [SecondaryButton] does.
 */
@Composable
internal fun InkChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    SegmentFace(
        filled = pressed,
        control = Modifier.clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        ),
        modifier = modifier.inkBorder(Tokens.StrokeRule),
        enabled = enabled,
    ) {
        ChipLabel(label)
    }
}

@Composable
private fun ChipLabel(label: String) {
    Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 3, modifier = Modifier.padding(horizontal = 8.dp))
}
