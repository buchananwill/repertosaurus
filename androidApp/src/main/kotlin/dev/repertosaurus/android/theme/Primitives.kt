package dev.repertosaurus.android.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.math.abs

/**
 * visual-identity VI5: a solid `Ink` rectangle, no blur, down and right by [offset]. The modifier pads
 * the element's end and bottom by the offset and draws into that padding, so the layout reserves the
 * room and whatever follows (the click target included) stays clear of the shadow.
 */
internal fun Modifier.hardShadow(offset: Dp): Modifier =
    padding(end = offset, bottom = offset).drawBehind {
        val shift = offset.toPx()
        drawRect(Tokens.Ink, topLeft = Offset(shift, shift), size = size)
    }

/** VI4: an `Ink` stroke, square, inside the element's bounds. */
internal fun Modifier.inkBorder(width: Dp = Tokens.StrokeHeavy): Modifier = border(width, Tokens.Ink, RectangleShape)

/** Which side of an element an [inkRule] closes. */
internal enum class InkEdge { Top, Bottom }

/** VI4: an `Ink` rule along one [edge], with the room for it reserved inside the element. */
internal fun Modifier.inkRule(edge: InkEdge, width: Dp = Tokens.StrokeHeavy): Modifier =
    drawBehind {
        val rule = width.toPx()
        val top = if (edge == InkEdge.Top) 0f else size.height - rule
        drawRect(Tokens.Ink, topLeft = Offset(0f, top), size = Size(size.width, rule))
    }.padding(top = if (edge == InkEdge.Top) width else 0.dp, bottom = if (edge == InkEdge.Bottom) width else 0.dp)

/**
 * VI12, VI20, VI21: while [interaction] is pressed the content sinks by ([x], [y]) and springs back on
 * release. Applied inside a click target, it moves what is drawn and never the hit area. The travel is
 * read at placement, so the spring recomposes nothing.
 */
@Composable
internal fun Modifier.sinkOnPress(interaction: MutableInteractionSource, x: Dp, y: Dp): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val travel = animateFloatAsState(if (pressed) 1f else 0f, Motion.spring(), label = "sink on press")
    return offset { IntOffset((x.toPx() * travel.value).roundToInt(), (y.toPx() * travel.value).roundToInt()) }
}

/** A control that cannot be used right now is drawn at [Tokens.DisabledAlpha]. */
internal fun Modifier.enabledLook(enabled: Boolean): Modifier = if (enabled) this else alpha(Tokens.DisabledAlpha)

/** VI12: the primary button, at most one a screen. */
@Composable
internal fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .enabledLook(enabled)
            .hardShadow(Tokens.ShadowLarge)
            .sinkOnPress(interaction, Tokens.ShadowLarge, Tokens.ShadowLarge)
            .background(Tokens.Ochre)
            .inkBorder()
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, enabled = enabled, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        DisplayText(text, style = DisplayType.Button, color = Tokens.Ink, textAlign = TextAlign.Center)
    }
}

/**
 * VI12: `Ground`, the same border, no shadow. With no shadow to sink onto, a press shows as the inked
 * fill of a selected segment (VI13).
 */
@Composable
internal fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .enabledLook(enabled)
            .background(if (pressed) Tokens.Ink else Tokens.Ground)
            .inkBorder()
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, enabled = enabled, onClick = onClick)
            .heightIn(min = Tokens.TouchMin)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        DisplayText(
            text,
            style = DisplayType.Button,
            color = if (pressed) Tokens.Paper else Tokens.Ink,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * VI12: a sheet's one primary action and a secondary beside it, sharing the width. The primary's face ends
 * above its shadow, so the secondary is inset by the shadow to end level with it.
 */
@Composable
internal fun ActionPair(
    primary: String,
    onPrimary: () -> Unit,
    secondary: String,
    onSecondary: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PrimaryButton(primary, onClick = onPrimary, modifier = Modifier.weight(1f).fillMaxHeight(), enabled = enabled)
        SecondaryButton(
            secondary,
            onClick = onSecondary,
            modifier = Modifier.weight(1f).fillMaxHeight().padding(bottom = Tokens.ShadowLarge),
            enabled = enabled,
        )
    }
}

/** VI12: a square icon button with a border and a hard shadow. [contentDescription] names it. */
@Composable
internal fun InkIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    containerColour: Color = Tokens.Ground,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .enabledLook(enabled)
            .hardShadow(Tokens.ShadowSmall)
            .sinkOnPress(interaction, Tokens.ShadowSmall, Tokens.ShadowSmall)
            .size(Tokens.IconButtonSize)
            .background(containerColour)
            .inkBorder()
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, enabled = enabled, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides Tokens.Ink) { content() }
    }
}

/** Three square-ended bars, drawn rather than taken from an icon set (VI3). */
@Composable
internal fun MenuGlyph(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(22.dp, 18.dp).drawBehind {
            val bar = Tokens.StrokeHeavy.toPx()
            val gap = (size.height - 3 * bar) / 2
            for (i in 0..2) {
                drawRect(Tokens.Ink, topLeft = Offset(0f, i * (bar + gap)), size = Size(size.width, bar))
            }
        },
    )
}

/** suggest SG1: a die face, five square pips, drawn as [MenuGlyph] is (VI3). */
@Composable
internal fun DieGlyph(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(22.dp).drawBehind {
            val border = Tokens.StrokeRule.toPx()
            drawRect(Tokens.Ink, style = Stroke(width = border))
            val pip = 4.dp.toPx()
            val near = border + 2.dp.toPx()
            val far = size.width - near - pip
            val mid = (size.width - pip) / 2f
            for ((x, y) in listOf(near to near, far to near, mid to mid, near to far, far to far)) {
                drawRect(Tokens.Ink, topLeft = Offset(x, y), size = Size(pip, pip))
            }
        },
    )
}

/** The direction glyph's turn, for a test: 1 is pointing down, -1 up, and in between it is mid-flip. */
internal val DirectionTurn: SemanticsPropertyKey<Float> = SemanticsPropertyKey("DirectionTurn")
internal var SemanticsPropertyReceiver.directionTurn: Float by DirectionTurn

/**
 * triage T6's direction: a square-ended arrow, drawn as [MenuGlyph] is (VI3), pointing down for "need
 * first" and up when [up]. **It never rotates** (VI3): the way it points is in its geometry. A flip
 * squashes it to nothing and springs it open the other way (VI18); with animations off it simply points.
 */
@Composable
internal fun DirectionGlyph(up: Boolean, modifier: Modifier = Modifier) {
    val turn by animateFloatAsState(if (up) -1f else 1f, Motion.spring(), label = "direction")
    // Read here, so the test's property follows the flip frame by frame; a small glyph, and only while it turns.
    val shown = turn
    Box(
        modifier = modifier
            .size(18.dp, 22.dp)
            .semantics { directionTurn = shown }
            .graphicsLayer { scaleY = abs(turn) }
            .drawBehind { drawArrow(pointsUp = turn < 0f) },
    )
}

private fun DrawScope.drawArrow(pointsUp: Boolean) {
    val stem = Tokens.StrokeHeavy.toPx()
    val head = size.width / 2f
    val stemTop = if (pointsUp) head else 0f
    drawRect(Tokens.Ink, topLeft = Offset((size.width - stem) / 2f, stemTop), size = Size(stem, size.height - head))
    val base = if (pointsUp) head else size.height - head
    val tip = if (pointsUp) 0f else size.height
    val arrow = Path().apply {
        moveTo(0f, base)
        lineTo(size.width, base)
        lineTo(size.width / 2f, tip)
        close()
    }
    drawPath(arrow, Tokens.Ink)
}

/**
 * VI12: an inline text action, underlined `Ink`, with no container. Its target is at least
 * [Tokens.TouchMin] square however short the word.
 */
@Composable
internal fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier = modifier
            .enabledLook(enabled)
            .sizeIn(minWidth = Tokens.TouchMin, minHeight = Tokens.TouchMin)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = Tokens.Ink, textDecoration = TextDecoration.Underline)
    }
}

/** A quiet line under a control: body-small, in `InkMuted`. */
@Composable
internal fun MutedLine(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Tokens.InkMuted, modifier = modifier)
}

/**
 * VI1, VI4: **the one input**, `Field` white inside a 3 dp `Ink` outline. A filled field with its indicator
 * hidden, because the outlined one's stroke is fixed at 1-2 dp. The [label] rides inside the box; the
 * [supportingText] sits under it, outside the outline, in the error colour when [isError].
 *
 * [modifier] places and sizes the whole (a weight, a width, padding); the input fills its width.
 * [fieldModifier] is the input's own, where a test tag goes, because the input is the node that holds the text.
 */
@Composable
internal fun InkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
    placeholder: String? = null,
    label: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    // Always the one Column, so a supporting line coming or going never recreates the input under the keyboard.
    Column(modifier = modifier) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            placeholder = placeholder?.let { text -> @Composable { Text(text) } },
            label = label?.let { text -> @Composable { Text(text) } },
            isError = isError,
            singleLine = singleLine,
            minLines = minLines,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            trailingIcon = trailingIcon,
            shape = RectangleShape,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Tokens.Field,
                unfocusedContainerColor = Tokens.Field,
                errorContainerColor = Tokens.Field,
                disabledContainerColor = Tokens.Ground,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                errorIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            modifier = fieldModifier.fillMaxWidth().inkBorder(),
        )
        supportingText?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else Tokens.InkMuted,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp),
            )
        }
    }
}

/** VI4: a list-row rule. */
@Composable
internal fun RowRule(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, thickness = Tokens.StrokeRule, color = Tokens.Ink)
}

/** VI15: one list item and the rule under it, so the two always move together. */
@Composable
internal fun RuledItem(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = modifier) {
        content()
        RowRule()
    }
}
