package dev.repertosaurus.android

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.repertosaurus.android.theme.ChoiceHeader
import dev.repertosaurus.android.theme.DisplayText
import dev.repertosaurus.android.theme.DisplayType
import dev.repertosaurus.android.theme.InkSheet
import dev.repertosaurus.android.theme.Segment
import dev.repertosaurus.android.theme.SegmentStrip
import dev.repertosaurus.android.theme.Tokens
import dev.repertosaurus.android.theme.inkBorder
import dev.repertosaurus.core.ColourRamp
import dev.repertosaurus.core.Heat
import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.session.Messages

/**
 * rating-scale RS8: provided once, at the app root, and **no composable takes a ramp parameter**,
 * so no later screen can thread a different one. Static because it changes only on an explicit
 * pick, and a whole-tree recompose then is the behaviour wanted.
 */
internal val LocalColourRamp: ProvidableCompositionLocal<ColourRamp> =
    staticCompositionLocalOf { ColourRamp.DEFAULT }

internal fun ColourRamp.colour(level: RatingLevel): Color = Color(step(level))

/** rating-scale RS12: the one heat derivation, for every surface that shows staleness. */
@Composable
internal fun heatColour(daysSince: Long?): Color = LocalColourRamp.current.colour(Heat.level(daysSince))

/** Test tags for the rating controls. */
internal object RatingTags {
    /** RS9. */
    fun segment(prefix: String, level: RatingLevel): String = "$prefix-${level.value}"

    /** RS16. */
    const val RAMP_PICKER: String = "colour-ramp-picker"
    fun ramp(ramp: ColourRamp): String = "colour-ramp-${ramp.name}"
}

/** RS9: an unselected segment shows its step as a band along its foot, so the ramp reads before a pick. */
private val SwatchBand = 5.dp

/**
 * rating-scale RS9: **the only 0-3 input in the app.** A tap on the selected segment clears it, so
 * unrated is one tap away. The segments shown run from [lowest] up (RS10). visual-identity VI14: the
 * segments are joined in one [SegmentStrip], and the selected one is filled with its ramp step.
 *
 * [labelsFit] is [rememberRatingLabelsFit]'s answer, taken once for a whole list and passed down
 * (Compose review F22 B2). Null, a lone control (the feel sheet) measures its own width for it.
 */
@Composable
internal fun RatingSegmentedControl(
    value: RatingLevel?,
    onValueChange: (RatingLevel?) -> Unit,
    modifier: Modifier = Modifier,
    labelsFit: Boolean? = null,
    lowest: RatingLevel = RatingLevel.NOT_AT_ALL,
    tagPrefix: String = "rating",
) {
    if (labelsFit == null) {
        BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
            RatingSegmentedControl(value, onValueChange, Modifier, rememberRatingLabelsFit(maxWidth, lowest), lowest, tagPrefix)
        }
        return
    }
    SegmentStrip(modifier = modifier.fillMaxWidth()) {
        for (level in RatingLevel.entries) {
            if (level < lowest) continue
            val selected = value == level
            RatingSegment(
                level = level,
                selected = selected,
                labelFits = labelsFit,
                onClick = { onValueChange(if (selected) null else level) },
                modifier = Modifier.testTag(RatingTags.segment(tagPrefix, level)),
            )
        }
    }
}

@Composable
private fun RatingSegment(level: RatingLevel, selected: Boolean, labelFits: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val step = LocalColourRamp.current.colour(level)
    Segment(
        selected = selected,
        onClick = onClick,
        selectedFill = step,
        modifier = modifier.drawWithContent {
            drawContent()
            if (!selected) {
                val band = SwatchBand.toPx()
                drawRect(step, topLeft = Offset(0f, size.height - band), size = Size(size.width, band))
            }
        },
    ) {
        LevelText(level, labelFits, modifier = Modifier.padding(bottom = SwatchBand))
    }
}

/** `Segment`'s own horizontal padding, which a label has to fit inside. */
private val SegmentInset = 4.dp

/**
 * visual-identity VI8: **does every level's label fit a segment of a strip [stripWidth] wide?** Measured
 * for each label once, keyed on the density, the font scale and the width, so a list asks once rather
 * than once per segment (Compose review F22 B2). [inset] is each segment's horizontal padding.
 */
@Composable
internal fun rememberRatingLabelsFit(
    stripWidth: Dp,
    lowest: RatingLevel = RatingLevel.NOT_AT_ALL,
    inset: Dp = SegmentInset,
): Boolean {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.labelSmall
    return remember(density, stripWidth, lowest, inset, style) {
        val levels = RatingLevel.entries.filter { it >= lowest }
        val inner = stripWidth - Tokens.StrokeHeavy * 2 - Tokens.StrokeRule * (levels.size - 1)
        val segment = with(density) { (inner / levels.size - inset * 2).toPx() }
        levels.all { measurer.measure(it.label, style, maxLines = 1, softWrap = false).size.width <= segment }
    }
}

/**
 * RS6: a level's number over its label, so no step relies on colour alone.
 *
 * VI8: **a label never clips.** Where [labelFits] is false the label drops to the shorter form the
 * spec allows, the number alone, and stays as the number's content description.
 */
@Composable
private fun LevelText(level: RatingLevel, labelFits: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        DisplayText(
            level.value.toString(),
            style = DisplayType.Number,
            modifier = if (labelFits) Modifier else Modifier.semantics { contentDescription = level.label },
        )
        if (labelFits) {
            Text(
                level.label,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** rating-scale RS16: one tap on a ramp is the whole interaction; [onSelect] persists and closes. */
@Composable
internal fun ColourRampPicker(onSelect: (ColourRamp) -> Unit, onDismiss: () -> Unit) {
    InkSheet(title = Messages.COLOUR_RAMP, onDismiss = onDismiss, modifier = Modifier.testTag(RatingTags.RAMP_PICKER)) {
        ColourRampChoices(onSelect = onSelect)
    }
}

/**
 * RS16's ramp rows, the current one (`LocalColourRamp`) marked. The picker's sheet and onboarding's
 * first question (onboarding OB2, OB5) both show exactly these; neither builds a ramp card of its own.
 */
@Composable
internal fun ColourRampChoices(onSelect: (ColourRamp) -> Unit, modifier: Modifier = Modifier) {
    val current = LocalColourRamp.current
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // F22 B2: one decision for every ramp's strip, which sits inside the row's margins.
        val labelsFit = rememberRatingLabelsFit(maxWidth - RampStripMargin * 2, inset = RampSwatchInset)
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RampRowGap)) {
            for (ramp in ColourRamp.entries) {
                RampRow(ramp = ramp, current = ramp == current, labelsFit = labelsFit, onClick = { onSelect(ramp) })
            }
        }
    }
}

private val RampRowGap = 12.dp

/**
 * One ramp: its label over its four steps, joined as a [SegmentStrip] shows them. The current ramp
 * is marked by [ChoiceHeader]. The whole card is one radio choice, with no ripple (F17 B15).
 */
@Composable
private fun RampRow(ramp: ColourRamp, current: Boolean, labelsFit: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tokens.Ground)
            .inkBorder()
            .selectable(
                selected = current,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .testTag(RatingTags.ramp(ramp)),
    ) {
        ChoiceHeader(label = ramp.label, selected = current, selectedNote = Messages.CHOICE_CURRENT)
        SegmentStrip(modifier = Modifier.padding(start = RampStripMargin, end = RampStripMargin, bottom = 12.dp)) {
            for (level in RatingLevel.entries) {
                Box(
                    modifier = Modifier.background(ramp.colour(level)).padding(vertical = 6.dp, horizontal = RampSwatchInset),
                    contentAlignment = Alignment.Center,
                ) {
                    LevelText(level, labelsFit)
                }
            }
        }
    }
}

/** A ramp row's strip margins, and each swatch's own padding (F22 B2 measures inside both). */
private val RampStripMargin = 12.dp
private val RampSwatchInset = 2.dp
