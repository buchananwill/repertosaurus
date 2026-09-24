package dev.repertosaurus.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.repertosaurus.android.theme.DisplayText
import dev.repertosaurus.android.theme.DisplayType
import dev.repertosaurus.android.theme.InkEdge
import dev.repertosaurus.android.theme.Motion
import dev.repertosaurus.android.theme.PrimaryButton
import dev.repertosaurus.android.theme.SecondaryButton
import dev.repertosaurus.android.theme.Tokens
import dev.repertosaurus.android.theme.hardShadow
import dev.repertosaurus.android.theme.inkBorder
import dev.repertosaurus.android.theme.inkRule
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.TimedSong
import dev.repertosaurus.session.TimerQuestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Stable handles for the timer. */
internal object TimerTags {
    const val BAR: String = "timer-bar"
    const val CLOCK: String = "timer-clock"
    const val FULL_SCREEN: String = "timer-full-screen"
    const val FULL_SCREEN_CLOCK: String = "timer-full-screen-clock"
    const val QUESTION: String = "timer-question"
}

/**
 * timer TM4, TM6: the clock's seconds, recomputed from the stored start instant a few times a second. It owns
 * nothing but the value it last drew. The ticks run on real time off the composition's dispatcher, so a test
 * clock is not held open by them.
 */
@Composable
internal fun rememberElapsed(timer: TimerHolder, song: TimedSong): State<Long> =
    produceState(timer.elapsed(song), timer, song) {
        ticks().collect { value = timer.elapsed(song) }
    }

private fun ticks(): Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(TICK_MS)
    }
}.flowOn(Dispatchers.Default)

/** Four reads a second, so the shown second is never more than a quarter late. Only a change recomposes. */
private const val TICK_MS: Long = 250L

/**
 * timer TM4: the running bar, directly under the session header. The song, a large clock that opens the
 * full-screen one, Stop and Cancel. VI18: it springs in and out; VI22: at once, with animations off.
 */
@Composable
internal fun ColumnScope.TimerBarSlot(
    song: TimedSong?,
    seconds: () -> Long,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onOpenClock: () -> Unit,
) {
    // The song last shown, so the bar keeps its words while it leaves. A cache of [song], not state.
    val last = remember { LastShown() }
    if (song != null) last.song = song
    AnimatedVisibility(
        visible = song != null,
        enter = expandVertically(Motion.spring()) + fadeIn(Motion.spring()),
        exit = shrinkVertically(Motion.spring()) + fadeOut(Motion.spring()),
    ) {
        val shown = song ?: last.song ?: return@AnimatedVisibility
        TimerBar(shown, seconds, onStop, onCancel, onOpenClock)
    }
}

private class LastShown {
    var song: TimedSong? = null
}

@Composable
private fun TimerBar(
    song: TimedSong,
    seconds: () -> Long,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onOpenClock: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tokens.Paper)
            .inkRule(InkEdge.Bottom)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(TimerTags.BAR),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(role = Role.Button, onClick = onOpenClock)
                .semantics { contentDescription = Messages.TIMER_OPEN_CLOCK },
        ) {
            Text(song.title, style = MaterialTheme.typography.titleMedium, color = Tokens.Ink, maxLines = 2)
            DisplayText(
                Messages.timerClock(seconds()),
                style = DisplayType.ScreenTitle,
                color = Tokens.Ink,
                maxLines = 1,
                modifier = Modifier.testTag(TimerTags.CLOCK),
            )
        }
        Column(modifier = Modifier.width(IntrinsicButtonWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(Messages.TIMER_STOP, onClick = onStop, modifier = Modifier.fillMaxWidth())
            SecondaryButton(Messages.TIMER_CANCEL, onClick = onCancel, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Wide enough for "CANCEL" at a font scale of 1.3 beside the widest clock, `h:mm:ss`. */
private val IntrinsicButtonWidth = 136.dp

/**
 * timer TM4: the full-screen clock, for a music stand across the room. Huge digits sized to the screen's width,
 * Stop and Cancel. Back, or a tap on the digits, returns to the list.
 */
@Composable
internal fun FullScreenClock(
    song: TimedSong,
    seconds: () -> Long,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.Ground)
            // Nothing below it is reachable while it covers the list: a pointer target is the only one hit.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag(TimerTags.FULL_SCREEN),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(song.title, style = MaterialTheme.typography.headlineSmall, color = Tokens.Ink, textAlign = TextAlign.Center)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClose)
                .semantics { contentDescription = Messages.TIMER_CLOSE_CLOCK },
            contentAlignment = Alignment.Center,
        ) {
            val text = Messages.timerClock(seconds())
            // Sized to the width, not scaled again by the font scale: it already fills the screen.
            val size = with(LocalDensity.current) { (maxWidth / (text.length * DIGIT_WIDTH_EM)).toSp() }
            DisplayText(
                text,
                style = DisplayType.ScreenTitle.copy(fontSize = size, lineHeight = size * 1.05f),
                color = Tokens.Ink,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(TimerTags.FULL_SCREEN_CLOCK),
            )
        }
        PrimaryButton(Messages.TIMER_STOP, onClick = onStop, modifier = Modifier.fillMaxWidth())
        SecondaryButton(Messages.TIMER_CANCEL, onClick = onCancel, modifier = Modifier.fillMaxWidth())
    }
}

/** The display face's digit, in ems, with room for the colons: a width-fitting size never clips. */
private const val DIGIT_WIDTH_EM = 0.62f

/** timer TM8: **the only dialog in the timer.** The question and two buttons, nothing else. */
@Composable
internal fun TimerQuestionDialog(question: TimerQuestion, onAnswer: (withTime: Boolean) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .hardShadow(Tokens.ShadowLarge)
                .background(Tokens.Paper)
                .inkBorder()
                .padding(20.dp)
                .testTag(TimerTags.QUESTION),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(Messages.timerQuestion(question.seconds), style = MaterialTheme.typography.titleLarge, color = Tokens.Ink)
            PrimaryButton(Messages.timerLogWith(question.seconds), onClick = { onAnswer(true) }, modifier = Modifier.fillMaxWidth())
            SecondaryButton(Messages.TIMER_LOG_WITHOUT, onClick = { onAnswer(false) }, modifier = Modifier.fillMaxWidth())
        }
    }
}
