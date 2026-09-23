package dev.repertosaurus.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.repertosaurus.android.theme.DisplayText
import dev.repertosaurus.android.theme.DisplayType
import dev.repertosaurus.android.theme.Segment
import dev.repertosaurus.android.theme.SegmentStrip
import dev.repertosaurus.android.theme.Tokens
import dev.repertosaurus.android.theme.inkBorder
import dev.repertosaurus.core.ColourRamp
import dev.repertosaurus.core.Heat
import dev.repertosaurus.core.RatingLevel

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
 */
@Composable
internal fun RatingSegmentedControl(
    value: RatingLevel?,
    onValueChange: (RatingLevel?) -> Unit,
    modifier: Modifier = Modifier,
    lowest: RatingLevel = RatingLevel.NOT_AT_ALL,
    tagPrefix: String = "rating",
) {
    SegmentStrip(modifier = modifier.fillMaxWidth()) {
        for (level in RatingLevel.entries) {
            if (level < lowest) continue
            val selected = value == level
            RatingSegment(
                level = level,
                selected = selected,
                onClick = { onValueChange(if (selected) null else level) },
                modifier = Modifier.testTag(RatingTags.segment(tagPrefix, level)),
            )
        }
    }
}

@Composable
private fun RatingSegment(level: RatingLevel, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
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
        LevelText(level, modifier = Modifier.padding(bottom = SwatchBand))
    }
}

/**
 * RS6: a level's number over its label, so no step relies on colour alone.
 *
 * visual-identity VI8: **a label never clips.** "exceptionally" in a quarter-width segment does not
 * fit at a large font scale, and a single word has nowhere to wrap, so a label that overflows drops
 * to the shorter form the spec allows — the number alone — and the label stays as the number's
 * content description. Position and number still carry the level.
 */
@Composable
private fun LevelText(level: RatingLevel, modifier: Modifier = Modifier) {
    var labelFits by remember(level) { mutableStateOf(true) }
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
                onTextLayout = { if (it.hasVisualOverflow) labelFits = false },
            )
        }
    }
}

/** rating-scale RS16: one tap on a ramp is the whole interaction; [onSelect] persists and closes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ColourRampPicker(onSelect: (ColourRamp) -> Unit, onDismiss: () -> Unit) {
    val current = LocalColourRamp.current
    // Fully expanded: at half height the third ramp's swatches sit under the screen's edge.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(RatingTags.RAMP_PICKER),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DisplayText("Colour ramp", style = DisplayType.Heading)
            for (ramp in ColourRamp.entries) {
                RampRow(ramp = ramp, current = ramp == current, onClick = { onSelect(ramp) })
            }
        }
    }
}

/**
 * One ramp: its label over its four steps, joined as a [SegmentStrip] shows them. The current ramp
 * is marked in words and by an `Ink` fill behind its label, not by colour alone.
 */
@Composable
private fun RampRow(ramp: ColourRamp, current: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tokens.Ground)
            .inkBorder()
            .clickable(onClick = onClick)
            .testTag(RatingTags.ramp(ramp))
            .semantics { selected = current },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (current) Tokens.Ink else Color.Transparent)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val text = if (current) Tokens.Paper else Tokens.Ink
            Text(ramp.label, style = MaterialTheme.typography.titleMedium, color = text, modifier = Modifier.weight(1f))
            if (current) {
                Text("✓ current", style = MaterialTheme.typography.labelLarge, color = Tokens.Ochre)
            }
        }
        SegmentStrip(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
            for (level in RatingLevel.entries) {
                Box(
                    modifier = Modifier.background(ramp.colour(level)).padding(vertical = 6.dp, horizontal = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    LevelText(level)
                }
            }
        }
    }
}
