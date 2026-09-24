package dev.repertosaurus.android

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
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
import dev.repertosaurus.session.InstrumentChip
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.TimedSong
import dev.repertosaurus.session.TimerQuestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

internal object TimerTags {
    const val BAR: String = "timer-bar"
    const val CLOCK: String = "timer-clock"
    const val FULL_SCREEN: String = "timer-full-screen"
    const val FULL_SCREEN_CLOCK: String = "timer-full-screen-clock"
    const val QUESTION: String = "timer-question"
}

/** One undo offer on the screen's snackbar host: true when Undo was taken. */
internal suspend fun SnackbarHostState.offerUndo(message: String): Boolean =
    showSnackbar(message, actionLabel = Messages.UNDO, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed

/**
 * timer TM4, TM9: the timer on the Session screen: the running timer, its clock's seconds, and whether the
 * full-screen clock is open. The holder owns the timer; this owns only what is on screen.
 */
@Stable
internal class TimerSurface(
    val holder: TimerHolder,
    running: State<TimedSong?>,
    private val elapsed: State<Long>,
    fullScreen: MutableState<Boolean>,
) {
    val running: TimedSong? by running
    var fullScreen: Boolean by fullScreen

    fun seconds(): Long = elapsed.value

    /** timer TM10, views V20a: Stop waits while no live instrument can take the running timer's log. */
    fun canStop(instruments: List<InstrumentChip>): Boolean = running?.let { holder.canStop(it, instruments) } ?: false
}

/**
 * timer TM9: "Timer cancelled · Undo" takes the snackbar from whatever holds it, and lives as long as the
 * snackbar does, as a tap's undo does.
 */
@Composable
internal fun rememberTimerSurface(holder: TimerHolder, snackbar: SnackbarHostState): TimerSurface {
    val running = holder.running.collectAsState()
    val fullScreen = rememberSaveable { mutableStateOf(false) }
    val elapsed = rememberElapsed(holder, running.value)
    val cancelled by holder.cancelled.collectAsState()
    LaunchedEffect(cancelled) {
        val offered = cancelled ?: return@LaunchedEffect
        snackbar.currentSnackbarData?.dismiss()
        if (snackbar.offerUndo(Messages.TIMER_CANCELLED)) holder.undoCancel(offered) else holder.dismissCancel(offered)
    }
    LaunchedEffect(running.value == null) { if (running.value == null) fullScreen.value = false }
    return remember(holder) { TimerSurface(holder, running, elapsed, fullScreen) }
}

/**
 * timer TM4, TM6: the clock's seconds, recomputed from the stored start instant a few times a second while the
 * screen is started. The ticks run on real time off the composition's dispatcher, so a test clock is not held
 * open by them.
 */
@Composable
private fun rememberElapsed(holder: TimerHolder, song: TimedSong?): State<Long> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(song?.let(holder::elapsed) ?: 0L, holder, song, lifecycle) {
        if (song == null) return@produceState
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            ticks().collect { value = holder.elapsed(song) }
        }
    }
}

private fun ticks(): Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(TICK_MS)
    }
}.flowOn(Dispatchers.Default)

/** Four reads a second, so the shown second is never more than a quarter late. Only a change recomposes. */
private const val TICK_MS: Long = 250L

/** The song last shown, so a leaving bar or clock keeps its words. A cache of the running timer, not state. */
private class LastShown {
    var song: TimedSong? = null
}

@Composable
private fun rememberShown(song: TimedSong?): TimedSong? {
    val last = remember { LastShown() }
    if (song != null) last.song = song
    return song ?: last.song
}

/**
 * timer TM4: the running bar, directly under the session header. The song, a large clock that opens the
 * full-screen one, Stop and Cancel. Stop waits while no live instrument can take the log. VI18: it springs in
 * and out; VI22: at once, with animations off.
 */
@Composable
internal fun ColumnScope.TimerBarSlot(
    surface: TimerSurface,
    stoppable: Boolean,
    onStop: () -> Unit,
) {
    val song = surface.running
    val shown = rememberShown(song)
    AnimatedVisibility(
        visible = song != null,
        enter = expandVertically(Motion.spring()) + fadeIn(Motion.spring()),
        exit = shrinkVertically(Motion.spring()) + fadeOut(Motion.spring()),
    ) {
        if (shown != null) {
            TimerBar(
                song = shown,
                seconds = surface::seconds,
                stoppable = stoppable,
                onStop = onStop,
                onCancel = surface.holder::cancel,
                onOpenClock = { surface.fullScreen = true },
            )
        }
    }
}

@Composable
private fun TimerBar(
    song: TimedSong,
    seconds: () -> Long,
    stoppable: Boolean,
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
        // The time is read with the action: one merged node.
        Column(
            modifier = Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) {}
                .clickable(onClickLabel = Messages.TIMER_OPEN_CLOCK, role = Role.Button, onClick = onOpenClock)
                .testTag(TimerTags.CLOCK),
        ) {
            Text(song.title, style = MaterialTheme.typography.titleMedium, color = Tokens.Ink, maxLines = 2)
            DisplayText(Messages.timerClock(seconds()), style = DisplayType.ScreenTitle, color = Tokens.Ink, maxLines = 1)
        }
        Column(modifier = Modifier.width(BarButtonWidth), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(Messages.TIMER_STOP, onClick = onStop, modifier = Modifier.fillMaxWidth(), enabled = stoppable)
            SecondaryButton(Messages.TIMER_CANCEL, onClick = onCancel, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Wide enough for "CANCEL" at a font scale of 1.3 beside the widest clock, `h:mm:ss`. */
private val BarButtonWidth = 136.dp

/** timer TM4, TM8: what the timer draws over the Session screen: the full-screen clock and the one question. */
@Composable
internal fun TimerOverlay(
    surface: TimerSurface,
    stoppable: Boolean,
    onStop: () -> Unit,
    onAnswer: (withTime: Boolean) -> Unit,
) {
    FullScreenClock(surface, stoppable, onStop)
    val question by surface.holder.question.collectAsState()
    question?.let { TimerQuestionDialog(question = it, onAnswer = onAnswer, onDismiss = surface.holder::dismissQuestion) }
}

/**
 * timer TM4: the full-screen clock, for a music stand across the room. Its own window, so nothing beneath it
 * takes a touch or is read aloud, and it keeps the screen on while open. Back, or a tap on the digits, returns.
 * VI18: its content springs in and out.
 */
@Composable
private fun FullScreenClock(surface: TimerSurface, stoppable: Boolean, onStop: () -> Unit) {
    val open = surface.fullScreen && surface.running != null
    val shown = rememberShown(surface.running)
    val visible = remember { MutableTransitionState(false) }
    SideEffect { visible.targetState = open }
    if (shown == null || !(open || visible.currentState || !visible.isIdle)) return
    val close = { surface.fullScreen = false }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        KeepScreenOn()
        AnimatedVisibility(
            visibleState = visible,
            enter = fadeIn(Motion.spring()) + scaleIn(Motion.spring(), initialScale = ENTRY_SCALE),
            exit = fadeOut(Motion.spring()) + scaleOut(Motion.spring(), targetScale = ENTRY_SCALE),
        ) {
            FullScreenFace(
                song = shown,
                seconds = surface::seconds,
                stoppable = stoppable,
                onStop = onStop,
                onCancel = surface.holder::cancel,
                onClose = close,
            )
        }
    }
}

private const val ENTRY_SCALE = 0.92f

/** D83: the window this is drawn in keeps the screen on for as long as it is shown. */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun FullScreenFace(
    song: TimedSong,
    seconds: () -> Long,
    stoppable: Boolean,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Tokens.Ground)
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
                .semantics(mergeDescendants = true) {}
                .clickable(onClickLabel = Messages.TIMER_CLOSE_CLOCK, role = Role.Button, onClick = onClose)
                .testTag(TimerTags.FULL_SCREEN_CLOCK),
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
            )
        }
        PrimaryButton(Messages.TIMER_STOP, onClick = onStop, modifier = Modifier.fillMaxWidth(), enabled = stoppable)
        SecondaryButton(Messages.TIMER_CANCEL, onClick = onCancel, modifier = Modifier.fillMaxWidth())
    }
}

/** The display face's digit, in ems, with room for the colons: a width-fitting size never clips. */
private const val DIGIT_WIDTH_EM = 0.62f

/** timer TM8: **the only question the timer asks.** The question and two buttons, nothing else. */
@Composable
private fun TimerQuestionDialog(question: TimerQuestion, onAnswer: (withTime: Boolean) -> Unit, onDismiss: () -> Unit) {
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
