package dev.repertosaurus.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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

/** Every tappable rating surface: a segment and a ramp row. */
private val RatingShape = RoundedCornerShape(12.dp)

/** A step shown as a swatch, inside a ramp row. */
private val SwatchShape = RoundedCornerShape(8.dp)

/**
 * rating-scale RS9: **the only 0-3 input in the app.** A tap on the selected segment clears it, so
 * unrated is one tap away. The segments shown run from [lowest] up (RS10).
 */
@Composable
internal fun RatingSegmentedControl(
    value: RatingLevel?,
    onValueChange: (RatingLevel?) -> Unit,
    modifier: Modifier = Modifier,
    lowest: RatingLevel = RatingLevel.NOT_AT_ALL,
    tagPrefix: String = "rating",
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (level in RatingLevel.entries) {
            if (level < lowest) continue
            val selected = value == level
            RatingSegment(
                level = level,
                selected = selected,
                onClick = { onValueChange(if (selected) null else level) },
                modifier = Modifier.weight(1f).testTag(RatingTags.segment(tagPrefix, level)),
            )
        }
    }
}

/** Filled with its step when selected, outlined in it otherwise, so the ramp reads before a pick. */
@Composable
private fun RatingSegment(level: RatingLevel, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val step = LocalColourRamp.current.colour(level)
    Surface(
        onClick = onClick,
        color = if (selected) step else MaterialTheme.colorScheme.surface,
        // RS6: every step is light enough for dark text.
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RatingShape,
        border = BorderStroke(if (selected) 2.dp else 3.dp, step),
        modifier = modifier.heightIn(min = 56.dp).semantics { this.selected = selected },
    ) {
        LevelText(level, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
    }
}

/** RS6: a level's number over its label, so no step relies on colour alone. */
@Composable
private fun LevelText(level: RatingLevel, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(level.value.toString(), style = MaterialTheme.typography.titleMedium)
        Text(
            level.label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
        )
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Colour ramp", style = MaterialTheme.typography.headlineSmall)
            for (ramp in ColourRamp.entries) {
                RampRow(ramp = ramp, current = ramp == current, onClick = { onSelect(ramp) })
            }
        }
    }
}

@Composable
private fun RampRow(ramp: ColourRamp, current: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        // The sheet's own colour shows through; only the border marks the row.
        color = Color.Transparent,
        shape = RatingShape,
        border = BorderStroke(if (current) 2.dp else 1.dp, if (current) scheme.primary else scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag(RatingTags.ramp(ramp)).semantics { selected = current },
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ramp.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (current) {
                    Text("✓ current", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (level in RatingLevel.entries) {
                    Surface(
                        color = ramp.colour(level),
                        contentColor = scheme.onSurface,
                        shape = SwatchShape,
                        modifier = Modifier.weight(1f),
                    ) {
                        LevelText(level, modifier = Modifier.padding(vertical = 6.dp, horizontal = 2.dp))
                    }
                }
            }
        }
    }
}
