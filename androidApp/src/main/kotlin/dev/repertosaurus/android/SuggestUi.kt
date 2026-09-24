package dev.repertosaurus.android

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.repertosaurus.android.theme.ActionPair
import dev.repertosaurus.android.theme.Departure
import dev.repertosaurus.android.theme.DisplayText
import dev.repertosaurus.android.theme.DisplayType
import dev.repertosaurus.android.theme.Motion
import dev.repertosaurus.android.theme.MutedLine
import dev.repertosaurus.android.theme.SecondaryButton
import dev.repertosaurus.android.theme.SwitchRow
import dev.repertosaurus.android.theme.TextAction
import dev.repertosaurus.android.theme.Tokens
import dev.repertosaurus.android.theme.departing
import dev.repertosaurus.android.theme.hardShadow
import dev.repertosaurus.android.theme.inkBorder
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.PartResolution
import dev.repertosaurus.session.SessionRow
import dev.repertosaurus.session.SuggestSpoke
import dev.repertosaurus.session.SuggestTuning
import dev.repertosaurus.session.SuggestionCard

/** Stable handles for Suggest. */
internal object SuggestTags {
    const val SHEET: String = "suggest-sheet"
    const val CARD: String = "suggest-card"
    const val TUNE: String = "suggest-tune"
    const val RADAR: String = "suggest-radar"
    const val SKIP_COUNT: String = "suggest-skip-count"
    const val SKIPPED: String = "suggest-skipped"
    const val UNDO_SKIP: String = "suggest-undo-skip"
    const val COUNT_SKIPS: String = "suggest-count-skips"
    const val SHOW_SKIP_COUNT: String = "suggest-show-skip-count"

    fun spoke(spoke: SuggestSpoke): String = "suggest-spoke-${spoke.name}"
    fun label(spoke: SuggestSpoke): String = "suggest-label-${spoke.name}"
}

/** suggest SG2-SG4, SG11-SG13: the suggestion sheet. **Nothing here writes**: dismissing is never a skip (SG3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SuggestSheet(
    card: SuggestionCard,
    tuning: SuggestTuning,
    part: PartResolution,
    ratingsFresh: Boolean,
    skipStaged: Boolean,
    tuneOpen: Boolean,
    onTuneOpen: (Boolean) -> Unit,
    onLog: (SessionRow) -> Unit,
    /** timer TM1: "Time it" on the card's song. */
    onTime: (SessionRow) -> Unit,
    onAnother: () -> Unit,
    onUndoSkip: () -> Unit,
    onTune: (SuggestTuning) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Fully expanded: the radar, opened, does not fit a half-height sheet.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(SuggestTags.SHEET),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DisplayText(Messages.SUGGEST_TITLE, style = DisplayType.Heading)
            val dealer = rememberDealer(card as? SuggestionCard.Showing)
            val shown = dealer.shown
            if (shown == null) {
                // SG4: no draw, and no error styling.
                when (card) {
                    SuggestionCard.EmptyPool -> Text(Messages.SUGGEST_EMPTY_POOL, style = MaterialTheme.typography.bodyLarge)
                    SuggestionCard.Exhausted -> Text(Messages.SUGGEST_EXHAUSTED, style = MaterialTheme.typography.bodyLarge)
                    is SuggestionCard.Showing -> Unit
                }
            } else {
                SuggestCard(row = shown.row, skipCount = tuning.shownSkipCount(shown.skips), dealer = dealer)
                // VI12: the sheet's one primary action. timer TM1: "Time it" beside it, as on the feel sheet.
                ActionPair(
                    primary = Messages.LOG_IT,
                    onPrimary = { onLog(shown.row) },
                    secondary = Messages.TIMER_TIME_IT,
                    onSecondary = { onTime(shown.row) },
                    enabled = !dealer.swapping,
                )
            }
            if (card != SuggestionCard.EmptyPool) {
                SecondaryButton(
                    text = Messages.SUGGEST_ANOTHER,
                    onClick = onAnother,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !dealer.swapping,
                )
            }
            SkippedLine(visible = skipStaged, onUndo = onUndoSkip, enabled = !dealer.swapping)
            TuneDisclosure(open = tuneOpen, onOpen = onTuneOpen, tuning = tuning, part = part, ratingsFresh = ratingsFresh, onTune = onTune)
        }
    }
}

/** SG2: title, artist and the session row's own staleness badge (rating-scale RS12); SG13's count under them. */
@Composable
private fun SuggestCard(row: SessionRow, skipCount: Long?, dealer: Dealer) {
    Column(
        modifier = Modifier
            .departing(dealer.departure)
            .graphicsLayer { translationX = dealer.arrival.value * size.width }
            .fillMaxWidth()
            .hardShadow(Tokens.ShadowLarge)
            .background(Tokens.Paper)
            .inkBorder()
            .padding(16.dp)
            .testTag(SuggestTags.CARD),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SongTitleArtist(title = row.title, artistName = row.artistName, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(12.dp))
            StalenessBadge(row = row, loggedCount = 0)
        }
        // SG13: information, not a scolding, so muted and never red.
        skipCount?.let { MutedLine(Messages.suggestSkipCount(it), modifier = Modifier.testTag(SuggestTags.SKIP_COUNT)) }
    }
}

/** SG12: "Skipped · Undo" while the skip can be taken back. VI18: it springs in and out. */
@Composable
private fun ColumnScope.SkippedLine(visible: Boolean, onUndo: () -> Unit, enabled: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(Motion.spring()) + slideInVertically(Motion.spring()) { -it / 2 },
        exit = fadeOut(Motion.spring()) + slideOutVertically(Motion.spring()) { -it / 2 },
    ) {
        Row(modifier = Modifier.fillMaxWidth().testTag(SuggestTags.SKIPPED), verticalAlignment = Alignment.CenterVertically) {
            MutedLine(Messages.SUGGEST_SKIPPED)
            TextAction(Messages.UNDO, onClick = onUndo, enabled = enabled, modifier = Modifier.testTag(SuggestTags.UNDO_SKIP))
        }
    }
}

/**
 * VI18 on "Another": the card on show departs as a logged row does, with no stamp to hold for, and the
 * next springs in from the left. [shown] trails the deck meanwhile, and [swapping] disables "Log it"
 * and "Another" until the new card has settled, so no card is logged or dealt unseen.
 */
@Stable
private class Dealer(initial: SuggestionCard.Showing?) {
    var shown: SuggestionCard.Showing? by mutableStateOf(initial)
    var swapping: Boolean by mutableStateOf(false)
    val departure = Departure(holdMs = 0)

    /** 0 in place, -1 a card's width to the left. */
    val arrival = Animatable(0f)
}

@Composable
private fun rememberDealer(target: SuggestionCard.Showing?): Dealer {
    val dealer = remember { Dealer(target) }
    // The same song with a new row or count updates in place, with no swap and no cancelled one.
    LaunchedEffect(target) {
        if (target?.row?.songId == dealer.shown?.row?.songId) dealer.shown = target
    }
    LaunchedEffect(target?.row?.songId) {
        if (target?.row?.songId == dealer.shown?.row?.songId) return@LaunchedEffect
        dealer.swapping = true
        try {
            if (dealer.shown != null) dealer.departure.leave()
            dealer.shown = target
            dealer.departure.reset()
            if (target != null) {
                dealer.arrival.snapTo(-1f)
                dealer.arrival.animateTo(0f, Motion.spring())
            }
        } finally {
            dealer.swapping = false
        }
    }
    return dealer
}

/** SG11-SG13: "Tune". [open] is the host's, outside the sheet's popup, so it survives a rotation. */
@Composable
private fun TuneDisclosure(
    open: Boolean,
    onOpen: (Boolean) -> Unit,
    tuning: SuggestTuning,
    part: PartResolution,
    ratingsFresh: Boolean,
    onTune: (SuggestTuning) -> Unit,
) {
    SecondaryButton(
        text = Messages.SUGGEST_TUNE + if (open) " ▴" else " ▾",
        onClick = { onOpen(!open) },
        modifier = Modifier.fillMaxWidth().testTag(SuggestTags.TUNE),
    )
    if (open) {
        val shuffle = tuning.spokes(part, ratingsFresh).values.all { it.radius == 0.0 }
        Text(
            text = if (shuffle) Messages.SUGGEST_SHUFFLE_HINT else Messages.SUGGEST_TUNED_HINT,
            style = MaterialTheme.typography.bodyMedium,
        )
        SuggestRadar(tuning = tuning, part = part, ratingsFresh = ratingsFresh, onTune = onTune, modifier = Modifier.fillMaxWidth())
        SecondaryButton(text = Messages.SUGGEST_SHUFFLE, onClick = { onTune(tuning.shuffled()) }, modifier = Modifier.fillMaxWidth())
        // SG12: off by default. SG13: offered only while skips are counted.
        Setting(
            text = Messages.SUGGEST_COUNT_SKIPS,
            hint = Messages.SUGGEST_COUNT_SKIPS_HINT,
            checked = tuning.countSkips,
            onChange = { onTune(tuning.copy(countSkips = it)) },
            tag = SuggestTags.COUNT_SKIPS,
        )
        if (tuning.countSkips) {
            Setting(
                text = Messages.SUGGEST_SHOW_SKIP_COUNT,
                hint = null,
                checked = tuning.showSkipCount,
                onChange = { onTune(tuning.copy(showSkipCount = it)) },
                tag = SuggestTags.SHOW_SKIP_COUNT,
            )
        }
    }
}

@Composable
private fun Setting(text: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    SwitchRow(
        checked = checked,
        onChange = onChange,
        modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.TouchMin).testTag(tag),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            hint?.let { MutedLine(it) }
        }
    }
}
