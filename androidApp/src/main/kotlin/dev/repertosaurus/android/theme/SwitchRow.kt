package dev.repertosaurus.android.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * One on/off setting: [content], then an [InkSwitch]. **The whole row is the one control**: the switch has
 * no handler of its own, because a second tap target would be a second path to the same change.
 * [contentPadding] is inside the touch target.
 */
@Composable
internal fun SwitchRow(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
        Spacer(modifier = Modifier.width(12.dp))
        InkSwitch(checked = checked, enabled = enabled)
    }
}

/**
 * VI3, VI4, VI13 (journal session 11, D79): **the square switch**, a drawing only; the row it sits in is the
 * control ([SwitchRow], D95 N2). A `Paper` track inside a 3 dp `Ink` border with a square `Ink` block at its
 * start; on, the track is `Ink` and the block, now `Paper`, has moved to its end. The block springs across
 * (VI21).
 */
@Composable
internal fun InkSwitch(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val travel = animateFloatAsState(if (checked) 1f else 0f, Motion.spring(), label = "switch")
    Box(
        modifier = modifier
            .enabledLook(enabled)
            .size(SwitchWidth, SwitchHeight)
            .drawBehind {
                val on = travel.value > 0.5f
                drawRect(if (on) Tokens.Ink else Tokens.Paper)
                val inset = Tokens.StrokeHeavy.toPx() + SwitchInset.toPx()
                val block = size.height - inset * 2
                val x = inset + (size.width - inset * 2 - block) * travel.value
                drawRect(if (on) Tokens.Paper else Tokens.Ink, topLeft = Offset(x, inset), size = Size(block, block))
            }
            .inkBorder(),
    )
}

/**
 * VI3, VI4 (D95 B2): **the square checkbox**, a drawing only, the [InkSwitch] way: the row it sits in is the
 * control. A `Paper` square in a 2 dp `Ink` border; checked, an `Ink` block fills its middle.
 */
@Composable
internal fun InkCheckbox(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val fill = animateFloatAsState(if (checked) 1f else 0f, Motion.spring(), label = "checkbox")
    Box(
        modifier = modifier
            .enabledLook(enabled)
            .size(CheckboxSize)
            .drawBehind {
                drawRect(Tokens.Paper)
                val inset = Tokens.StrokeRule.toPx() + CheckboxInset.toPx()
                val full = size.width - inset * 2
                val side = full * fill.value
                if (side > 0f) {
                    val start = inset + (full - side) / 2f
                    drawRect(Tokens.Ink, topLeft = Offset(start, start), size = Size(side, side))
                }
            }
            .inkBorder(Tokens.StrokeRule),
    )
}

private val SwitchWidth = 52.dp
private val SwitchHeight = 30.dp
private val SwitchInset = 3.dp
private val CheckboxSize = 24.dp
private val CheckboxInset = 3.dp
