package dev.repertosaurus.android

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.repertosaurus.android.theme.InkIconButton
import dev.repertosaurus.android.theme.Motion
import dev.repertosaurus.android.theme.Segment
import dev.repertosaurus.android.theme.SegmentStrip
import dev.repertosaurus.android.theme.Tokens
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.SessionOrder
import dev.repertosaurus.session.SortMode

/** Stable handles for the sort control. */
internal object SortTags {
    fun mode(mode: SortMode): String = "sort-mode-${mode.name}"

    const val DIRECTION: String = "sort-direction"

    /** The line under the control: the order in words, or triage T9's reason for the disabled modes. */
    const val LINE: String = "sort-line"
}

/**
 * **triage T6: the sort, as two controls.** A mode strip (Cold / Priority / Confidence) and one direction
 * flip; the pair resolves to the one [SessionOrder] that is stored (views V13b), through
 * [SessionOrder.withMode] and [SessionOrder.flipped]. The line beneath reads the order aloud (T7).
 *
 * Not [triageAvailable] (T9: no performer resolves), Priority and Confidence are disabled and the line is
 * [Messages.SORT_NEEDS_PERFORMER]. Nothing is guessed.
 */
@Composable
internal fun SortControl(
    order: SessionOrder,
    triageAvailable: Boolean,
    onOrder: (SessionOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SegmentStrip(modifier = Modifier.weight(1f)) {
                for (mode in SortMode.entries) {
                    Segment(
                        selected = order.mode == mode,
                        onClick = { onOrder(order.withMode(mode)) },
                        enabled = triageAvailable || !mode.readsRatings,
                        modifier = Modifier.testTag(SortTags.mode(mode)),
                    ) {
                        Text(
                            Messages.sortMode(mode),
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }
            InkIconButton(
                onClick = { onOrder(order.flipped) },
                contentDescription = Messages.sortFlip(order),
                modifier = Modifier.testTag(SortTags.DIRECTION),
            ) {
                DirectionGlyph(reversed = order.reversed)
            }
        }
        Text(
            text = if (triageAvailable) Messages.sortOrder(order) else Messages.SORT_NEEDS_PERFORMER,
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.InkMuted,
            modifier = Modifier.testTag(SortTags.LINE),
        )
    }
}

/**
 * The direction: a square-ended arrow, down for "need first" and up when flipped, drawn as the other
 * glyphs are (VI3). A flip turns it with the one spring (VI18); at rest it is always upright.
 */
@Composable
private fun DirectionGlyph(reversed: Boolean) {
    val turn by animateFloatAsState(if (reversed) 180f else 0f, Motion.spring(), label = "sort direction")
    Box(
        modifier = Modifier
            .size(18.dp, 22.dp)
            .graphicsLayer { rotationZ = turn }
            .drawBehind {
                val stem = Tokens.StrokeHeavy.toPx()
                val head = size.width / 2f
                drawRect(
                    Tokens.Ink,
                    topLeft = Offset((size.width - stem) / 2f, 0f),
                    size = Size(stem, size.height - head),
                )
                val arrow = Path().apply {
                    moveTo(0f, size.height - head)
                    lineTo(size.width, size.height - head)
                    lineTo(size.width / 2f, size.height)
                    close()
                }
                drawPath(arrow, Tokens.Ink)
            },
    )
}
