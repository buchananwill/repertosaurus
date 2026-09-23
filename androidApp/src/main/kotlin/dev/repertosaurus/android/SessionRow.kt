package dev.repertosaurus.android

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.repertosaurus.android.theme.DisplayType
import dev.repertosaurus.android.theme.Motion
import dev.repertosaurus.android.theme.Tokens
import dev.repertosaurus.android.theme.hardShadow
import dev.repertosaurus.android.theme.inkBorder
import dev.repertosaurus.session.SessionRow
import kotlin.math.roundToInt

/**
 * visual-identity VI20: a row just logged out of the pending list, held where it was while its exit
 * runs, so the rows below stay put until it has gone and then spring up into the gap. The state holds
 * nothing of this: the row has already moved to the logged section there.
 *
 * Where it was is [before], the song the screen showed directly beneath it, live or itself leaving;
 * [index] is the fallback for when that one has gone too. Null [before]: it was the last row.
 */
@Immutable
internal class Leaving(val row: SessionRow, val before: String?, val index: Int)

/**
 * One row. Tap logs it — no dialog, no confirmation, no wait on the database. Long-press
 * opens the feel sheet (decision 46); it is an addition to the tap path, never the only way
 * to log.
 *
 * visual-identity VI20 beat 1: **pressed, the row's content sinks onto its rule.** The sink is placed
 * inside the click target, so the hit area is the row's, exactly as before.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SongRow(
    row: SessionRow,
    loggedCount: Int,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val sink = animateFloatAsState(if (pressed) 1f else 0f, Motion.spring(), label = "row sink")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onTap,
                onLongClick = onLongPress,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .offset { IntOffset(0, (Motion.RowSink.toPx() * sink.value).roundToInt()) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SongRowContent(row = row, loggedCount = loggedCount)
    }
}

/**
 * VI20 beats 2 and 3, for a row that has left the pending list. **The log is already written** —
 * this is composed only after the tap's write was dispatched — and nothing here can be tapped: the
 * row has been logged, and its live copy is in the logged section. Hidden from semantics for the
 * same reason, but for a tag the tests can watch.
 *
 * The badge stamps "DONE"; the row then gives a small anticipation the other way and travels out.
 * When it has gone, [onLeft] drops it and the rows below spring up (`animateItemPlacement`).
 */
@Composable
internal fun LeavingRow(leaving: Leaving, onLeft: (String) -> Unit, modifier: Modifier = Modifier) {
    val anticipation = remember { Animatable(0f) }
    val travel = remember { Animatable(0f) }
    LaunchedEffect(leaving) {
        // The hold is the anticipation's delay, not a `delay()`: an animation spec's delay is scaled
        // by the animator duration scale, so "Remove animations" removes it too (VI22).
        anticipation.animateTo(1f, tween(Motion.ANTICIPATION_MS, delayMillis = Motion.STAMP_HOLD_MS.toInt()))
        travel.animateTo(1f, Motion.leaving())
        onLeft(leaving.row.songId)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clearAndSetSemantics { testTag = SessionTags.leaving(leaving.row.songId) }
            .graphicsLayer {
                val back = Motion.Anticipation.toPx() * anticipation.value
                translationX = -back + travel.value * (size.width + back)
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SongRowContent(row = leaving.row, loggedCount = 1, stampOnEnter = true)
    }
}

@Composable
private fun RowScope.SongRowContent(row: SessionRow, loggedCount: Int, stampOnEnter: Boolean = false) {
    Column(modifier = Modifier.weight(1f)) {
        Text(
            text = row.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = row.artistName ?: "unknown artist",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Spacer(modifier = Modifier.width(12.dp))
    StalenessBadge(row = row, loggedCount = loggedCount, stampOnEnter = stampOnEnter)
}

/** visual-identity VI4, VI15: rows are separated by 2 dp `Ink` rules. */
@Composable
internal fun RowRule() {
    HorizontalDivider(thickness = Tokens.StrokeRule, color = Tokens.Ink)
}

/**
 * Days since the last live practice on the selected instrument, "NEVER" when there is none.
 *
 * visual-identity VI15: a square with a 2 dp border and a 3 dp hard shadow, in display type. Heat
 * wears the ramp (rating-scale RS12). **This session's taps are `Ink` with `Ochre` text** — "DONE",
 * "DONE ×2" — which keeps RS12's point, that a logged row stays distinct from heat, in VI1's palette
 * rather than `primary`'s.
 *
 * VI20 beat 2: **the badge stamps** — squashes, then springs back — when it becomes "DONE" on a
 * leaving row, and when a second pass takes "DONE" to "DONE ×2". The same stamp every time (VI19):
 * nothing about it grows with the count.
 */
@Composable
private fun StalenessBadge(row: SessionRow, loggedCount: Int, stampOnEnter: Boolean) {
    val logged = loggedCount > 0
    val container = if (logged) Tokens.Ink else heatColour(row.daysSince)
    val stamp = remember { Animatable(if (stampOnEnter) 1f else 0f) }
    var stamped by remember { mutableIntStateOf(loggedCount) }
    LaunchedEffect(loggedCount) {
        if (loggedCount > stamped) stamp.snapTo(1f)
        stamped = loggedCount
        if (stamp.value != 0f) stamp.animateTo(0f, Motion.spring())
    }
    Box(
        modifier = Modifier
            .semantics { badgeColour = container }
            .graphicsLayer {
                scaleX = 1f + (Motion.STAMP_SPREAD - 1f) * stamp.value
                scaleY = 1f - (1f - Motion.STAMP_SQUASH) * stamp.value
            }
            .hardShadow(Tokens.ShadowSmall)
            .background(container)
            .inkBorder(Tokens.StrokeRule)
            .widthIn(min = 56.dp)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = when {
                loggedCount > 1 -> "DONE ×$loggedCount"
                loggedCount == 1 -> "DONE"
                // VI6: display type is upper case, so "12d" reads "12D".
                else -> row.badge.uppercase()
            },
            style = DisplayType.Badge,
            color = if (logged) Tokens.Ochre else Tokens.Ink,
        )
    }
}
