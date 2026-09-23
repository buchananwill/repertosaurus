package dev.repertosaurus.android.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The narrowest a letter cell may be: a display letter at font scale 1.3 still fits (VI8). */
private val MinCell: Dp = 36.dp

/**
 * **triage T3, visual-identity VI13: the letter strip**, a [SegmentStrip] laid out as a
 * [SegmentLayout.Grid], so it wraps to fit the phone and never scrolls sideways.
 *
 * A letter in [enabled] is a [Segment], `Ink`-filled when it is [selected]. Any other letter is
 * greyed and not tappable, and says so to TalkBack and tests. Tags are `"$tagPrefix-$letter"`.
 */
@Composable
internal fun LetterStrip(
    letters: List<Char>,
    selected: Char?,
    enabled: Set<Char>,
    onSelect: (Char) -> Unit,
    modifier: Modifier = Modifier,
    tagPrefix: String = "letter",
) {
    SegmentStrip(modifier = modifier, layout = SegmentLayout.Grid(MinCell)) {
        for (letter in letters) {
            val cell = Modifier.testTag("$tagPrefix-$letter")
            if (letter in enabled) {
                Segment(selected = letter == selected, onClick = { onSelect(letter) }, modifier = cell) {
                    DisplayText(letter.toString(), style = DisplayType.Badge, maxLines = 1)
                }
            } else {
                GreyedLetter(letter, cell)
            }
        }
    }
}

/** T3: a letter with no songs. `Ground`, its letter faded, and no click handler at all. */
@Composable
private fun GreyedLetter(letter: Char, modifier: Modifier) {
    Box(
        modifier = modifier
            .background(Tokens.Ground)
            .semantics { disabled() }
            .heightIn(min = Tokens.TouchMin),
        contentAlignment = Alignment.Center,
    ) {
        DisplayText(
            letter.toString(),
            style = DisplayType.Badge,
            color = Tokens.InkMuted.copy(alpha = GREYED_ALPHA),
            maxLines = 1,
        )
    }
}

private const val GREYED_ALPHA = 0.35f
