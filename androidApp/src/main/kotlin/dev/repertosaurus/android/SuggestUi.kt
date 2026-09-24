package dev.repertosaurus.android

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import dev.repertosaurus.android.theme.Departure
import dev.repertosaurus.android.theme.DisplayText
import dev.repertosaurus.android.theme.DisplayType
import dev.repertosaurus.android.theme.Motion
import dev.repertosaurus.android.theme.PrimaryButton
import dev.repertosaurus.android.theme.SecondaryButton
import dev.repertosaurus.android.theme.Tokens
import dev.repertosaurus.android.theme.departing
import dev.repertosaurus.android.theme.hardShadow
import dev.repertosaurus.android.theme.inkBorder
import dev.repertosaurus.session.Messages
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

    fun spoke(spoke: SuggestSpoke): String = "suggest-spoke-${spoke.name}"
    fun label(spoke: SuggestSpoke): String = "suggest-label-${spoke.name}"
}

/**
 * suggest SG2-SG4, SG11: the suggestion sheet. **Nothing here writes but [onLog]**: dismissing is
 * never a skip (SG3). [onLog] is given the card on screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SuggestSheet(
    card: SuggestionCard,
    tuning: SuggestTuning,
    tuneOpen: Boolean,
    onTuneOpen: (Boolean) -> Unit,
    onLog: (SessionRow) -> Unit,
    onAnother: () -> Unit,
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
            val dealer = rememberDealer((card as? SuggestionCard.Showing)?.row)
            val shown = dealer.shown
            if (shown == null) {
                // SG4: no draw, and no error styling.
                when (card) {
                    SuggestionCard.EmptyPool -> Text(Messages.SUGGEST_EMPTY_POOL, style = MaterialTheme.typography.bodyLarge)
                    SuggestionCard.Exhausted -> Text(Messages.SUGGEST_EXHAUSTED, style = MaterialTheme.typography.bodyLarge)
                    is SuggestionCard.Showing -> Unit
                }
            } else {
                SuggestCard(row = shown, dealer = dealer)
                // VI12: the sheet's one primary action.
                PrimaryButton(
                    text = Messages.SUGGEST_LOG,
                    onClick = { onLog(shown) },
                    modifier = Modifier.fillMaxWidth(),
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
            TuneDisclosure(open = tuneOpen, onOpen = onTuneOpen, tuning = tuning, onTune = onTune)
        }
    }
}

/** SG2: title, artist and the session row's own staleness badge (rating-scale RS12). */
@Composable
private fun SuggestCard(row: SessionRow, dealer: Dealer) {
    Row(
        modifier = Modifier
            .departing(dealer.departure)
            .graphicsLayer { translationX = dealer.arrival.value * size.width }
            .fillMaxWidth()
            .hardShadow(Tokens.ShadowLarge)
            .background(Tokens.Paper)
            .inkBorder()
            .padding(16.dp)
            .testTag(SuggestTags.CARD),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SongTitleArtist(title = row.title, artistName = row.artistName, modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(12.dp))
        StalenessBadge(row = row, loggedCount = 0)
    }
}

/**
 * VI18 on "Another": the card on show departs as a logged row does, with no stamp to hold for, and the
 * next springs in from the left. [shown] trails the deck meanwhile, and [swapping] disables "Log it"
 * and "Another" until the new card has settled (F26 N6, F31 N2), so no card is logged or dealt unseen.
 */
@Stable
private class Dealer(initial: SessionRow?) {
    var shown: SessionRow? by mutableStateOf(initial)
    var swapping: Boolean by mutableStateOf(false)
    val departure = Departure(holdMs = 0)

    /** 0 in place, -1 a card's width to the left. */
    val arrival = Animatable(0f)
}

@Composable
private fun rememberDealer(target: SessionRow?): Dealer {
    val dealer = remember { Dealer(target) }
    LaunchedEffect(target?.songId) {
        if (target?.songId == dealer.shown?.songId) {
            dealer.shown = target
            return@LaunchedEffect
        }
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

/**
 * SG11: "Tune", opening onto the radar and "Shuffle". [open] is the host's, outside the sheet's popup,
 * so it survives a rotation (F26 N7).
 */
@Composable
private fun TuneDisclosure(open: Boolean, onOpen: (Boolean) -> Unit, tuning: SuggestTuning, onTune: (SuggestTuning) -> Unit) {
    SecondaryButton(
        text = Messages.SUGGEST_TUNE + if (open) " ▴" else " ▾",
        onClick = { onOpen(!open) },
        modifier = Modifier.fillMaxWidth().testTag(SuggestTags.TUNE),
    )
    if (open) {
        Text(
            text = if (tuning.isShuffle) Messages.SUGGEST_SHUFFLE_HINT else Messages.SUGGEST_TUNED_HINT,
            style = MaterialTheme.typography.bodyMedium,
        )
        SuggestRadar(tuning = tuning, onTune = onTune, modifier = Modifier.fillMaxWidth())
        SecondaryButton(text = Messages.SUGGEST_SHUFFLE, onClick = { onTune(tuning.shuffled()) }, modifier = Modifier.fillMaxWidth())
    }
}
