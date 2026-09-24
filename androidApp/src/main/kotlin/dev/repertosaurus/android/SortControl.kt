package dev.repertosaurus.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.repertosaurus.android.theme.DirectionGlyph
import dev.repertosaurus.android.theme.InkIconButton
import dev.repertosaurus.android.theme.MutedLine
import dev.repertosaurus.android.theme.Segment
import dev.repertosaurus.android.theme.SegmentLabel
import dev.repertosaurus.android.theme.SegmentStrip
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.SessionOrder
import dev.repertosaurus.session.SortMode

/** Stable handles for the sort control. */
internal object SortTags {
    fun mode(mode: SortMode): String = "sort-mode-${mode.name}"

    const val DIRECTION: String = "sort-direction"

    const val LINE: String = "sort-line"
}

/**
 * **triage T6: the sort, as two controls**, a mode strip and one direction flip, resolving to the one
 * [SessionOrder] that is stored (views V13b). The line beneath reads the order aloud (T7), or says why
 * Priority and Confidence are disabled when no performer resolves (T9).
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
                        SegmentLabel(Messages.sortMode(mode))
                    }
                }
            }
            InkIconButton(
                onClick = { onOrder(order.flipped) },
                contentDescription = Messages.sortFlip(order),
                modifier = Modifier.testTag(SortTags.DIRECTION),
            ) {
                DirectionGlyph(up = order.reversed)
            }
        }
        MutedLine(
            text = if (triageAvailable) Messages.sortOrder(order) else Messages.SORT_NEEDS_PERFORMER,
            modifier = Modifier.testTag(SortTags.LINE),
        )
    }
}
