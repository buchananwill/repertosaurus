package dev.repertosaurus.android

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.repertosaurus.android.theme.Departure
import dev.repertosaurus.android.theme.DisplayText
import dev.repertosaurus.android.theme.DisplayType
import dev.repertosaurus.android.theme.Motion
import dev.repertosaurus.android.theme.Tokens
import dev.repertosaurus.android.theme.departing
import dev.repertosaurus.android.theme.hardShadow
import dev.repertosaurus.android.theme.inkBorder
import dev.repertosaurus.android.theme.sinkOnPress
import dev.repertosaurus.session.SessionRow

/** The colour a staleness badge wears, so a test can see a ramp change without sampling pixels. */
internal val BadgeColour: SemanticsPropertyKey<Color> = SemanticsPropertyKey("BadgeColour")
internal var SemanticsPropertyReceiver.badgeColour: Color by BadgeColour

/** A session row's floor: the thumb's target on a music stand. */
private val SongRowMinHeight: Dp = 76.dp

/**
 * One row. Tap logs it — no dialog, no confirmation, no wait on the database. Long-press
 * opens the feel sheet (decision 46); it is an addition to the tap path, never the only way
 * to log.
 *
 * visual-identity VI20 beat 1: pressed, the content sinks onto its rule, inside the click target, so
 * the hit area is the row's. The sink is the press feedback, so there is no ripple.
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
    SongRowFrame(
        outer = modifier.combinedClickable(
            interactionSource = interaction,
            indication = null,
            onClick = onTap,
            onLongClick = onLongPress,
        ),
        inner = Modifier.sinkOnPress(interaction, x = 0.dp, y = Motion.RowSink),
    ) {
        SongRowContent(row = row, loggedCount = loggedCount)
    }
}

/**
 * VI20 beats 2 and 3, for a row that has left the pending list. **The log is already written**, and
 * nothing here can be tapped: its live copy is in the logged section. Hidden from semantics for the
 * same reason, but for a tag the tests watch.
 *
 * The badge stamps "DONE"; the row gives a small anticipation the other way and travels out. [onLeft]
 * then drops it, and the rows below spring up (`animateItemPlacement`).
 */
@Composable
internal fun LeavingRow(leaving: Leaving, onLeft: (String) -> Unit, modifier: Modifier = Modifier) {
    val departure = remember { Departure(Motion.STAMP_HOLD_MS) }
    LaunchedEffect(leaving) {
        departure.leave()
        onLeft(leaving.row.songId)
    }
    SongRowFrame(
        outer = modifier
            .clearAndSetSemantics { testTag = SessionTags.leaving(leaving.row.songId) }
            .departing(departure),
    ) {
        SongRowContent(row = leaving.row, loggedCount = 1, stampOnEnter = true)
    }
}

/**
 * The one row frame, so a leaving row is the live row's exact size. [outer] sits on the row's full
 * bounds (the click target); [inner] sits inside its padding (what moves when pressed).
 */
@Composable
private fun SongRowFrame(outer: Modifier, inner: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SongRowMinHeight)
            .then(outer)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .then(inner),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun RowScope.SongRowContent(row: SessionRow, loggedCount: Int, stampOnEnter: Boolean = false) {
    SongTitleArtist(title = row.title, artistName = row.artistName, modifier = Modifier.weight(1f))
    Spacer(modifier = Modifier.width(12.dp))
    StalenessBadge(row = row, loggedCount = loggedCount, stampOnEnter = stampOnEnter)
}

/**
 * Days since the last live practice on the selected instrument, "NEVER" when there is none.
 *
 * visual-identity VI15: a square with a 2 dp border and a 3 dp hard shadow. Heat wears the ramp
 * (rating-scale RS12); this session's taps are `Ink` with `Ochre` text, "DONE" or "DONE ×2", which keeps
 * RS12's distinction from heat in VI1's palette.
 *
 * VI20 beat 2: the badge **stamps** — squashes, then springs back — when it becomes "DONE" on a leaving
 * row, and when a second pass counts up. The same stamp every time (VI19).
 */
@Composable
internal fun StalenessBadge(row: SessionRow, loggedCount: Int, stampOnEnter: Boolean = false) {
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
        DisplayText(
            text = when {
                loggedCount > 1 -> "Done ×$loggedCount"
                loggedCount == 1 -> "Done"
                else -> row.badge
            },
            style = DisplayType.Badge,
            color = if (logged) Tokens.Ochre else Tokens.Ink,
        )
    }
}
