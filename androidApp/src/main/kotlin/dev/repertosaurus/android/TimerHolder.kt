package dev.repertosaurus.android

import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.PracticeTimer
import dev.repertosaurus.session.SessionState
import dev.repertosaurus.session.StopOutcome
import dev.repertosaurus.session.TimedSong
import dev.repertosaurus.session.TimerQuestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * timer TM1-TM11: the one timer (TM2), the over-3-h question (TM8) and the Cancel undo window (TM9).
 *
 * **A decision to log is returned, never made here** (journal F40 N4): [start], [stop] and [answer] hand back a
 * [TimerLog] for the ViewModel to log as a tap. The timer is a stored start instant (TM6); [elapsed] is computed
 * from it whenever the clock is drawn, and nothing here ticks.
 *
 * [read], [write] and [title] do their own IO; [title] is a live song's title, null once it is removed. [apply]
 * is the state holder's atomic update.
 */
public class TimerHolder(
    private val session: StateFlow<SessionState>,
    private val scope: CoroutineScope,
    private val read: suspend () -> PracticeTimer.State.Running?,
    private val write: suspend (PracticeTimer.State.Running?) -> Unit,
    private val title: suspend (songId: String) -> String?,
    private val apply: ((SessionState) -> SessionState) -> Unit,
    private val timer: PracticeTimer = PracticeTimer(),
) {
    private val _running = MutableStateFlow<TimedSong?>(null)

    /** TM4: the running timer, null when there is none. */
    public val running: StateFlow<TimedSong?> = _running.asStateFlow()

    private val _question = MutableStateFlow<TimerQuestion?>(null)

    /** TM8: the one question, open. The timer runs on while it is. */
    public val question: StateFlow<TimerQuestion?> = _question.asStateFlow()

    private val _cancelled = MutableStateFlow<TimedSong?>(null)

    /** TM9: the cancelled timer, while Undo can restore it. */
    public val cancelled: StateFlow<TimedSong?> = _cancelled.asStateFlow()

    /** TM2: the timer a switch starts once the question it raised is answered. */
    private var switchingTo: TimedSong? = null

    private var cancelWindow: Job? = null

    /** Moves at every change of the running timer, so a TM10 check that read before it never lands. */
    private var ticket = 0L

    /** TM10: the store is read once, at the first load; after that the timer in memory is the one checked. */
    private var storeRead = false

    private val writes = Mutex()

    /** TM4, TM11: whole seconds on [song]'s clock, now. */
    public fun elapsed(song: TimedSong): Long = timer.elapsed(song.timer)

    /**
     * TM1-TM3: a timer on [songId] and the View's practice instrument now. **TM2:** a running timer is stopped by
     * TM7-TM8 first and its log returned; one that needs TM8's question asks it, and this one starts on the answer.
     */
    public fun start(songId: String, title: String): TimerLog? {
        if (_question.value != null) return null
        val instrumentId = session.value.selectedInstrumentId ?: return null
        closeCancelWindow()
        val started = TimedSong(timer.start(songId, instrumentId), title)
        val current = _running.value
        if (current == null) {
            run(started)
            return null
        }
        return stopping(current, then = started)
    }

    /** TM7, TM8: Stop. Its log, or null when there is no timer or the question is asked instead. */
    public fun stop(): TimerLog? {
        if (_question.value != null) return null
        val current = _running.value ?: return null
        return stopping(current, then = null)
    }

    /** TM8: the question's answer. The timer stops, and a switch waiting on it starts. */
    public fun answer(withTime: Boolean): TimerLog? {
        val asked = _question.value ?: return null
        _question.value = null
        run(switchingTo.also { switchingTo = null })
        return logOf(asked.song, if (withTime) StopOutcome.Timed(asked.seconds) else null)
    }

    /** TM8: the question put away unanswered. Nothing was stopped, and no switch happens. */
    public fun dismissQuestion() {
        _question.value = null
        switchingTo = null
    }

    /** TM9: Cancel writes nothing, and keeps the timer for Undo for [UNDO_WINDOW_MS]. */
    public fun cancel() {
        if (_question.value != null) return
        val current = _running.value ?: return
        run(null)
        closeCancelWindow()
        _cancelled.value = current
        cancelWindow = scope.launch {
            delay(UNDO_WINDOW_MS)
            if (_cancelled.value === current) _cancelled.value = null
        }
    }

    /** TM9: the same timer, original start instant and all. */
    public fun undoCancel() {
        val cancelled = _cancelled.value ?: return
        closeCancelWindow()
        if (_running.value == null) run(cancelled)
    }

    private fun stopping(current: TimedSong, then: TimedSong?): TimerLog? {
        val stop = timer.stop(current.timer)
        val outcome = stop.outcome
        if (outcome is StopOutcome.NeedsChoice) {
            switchingTo = then
            _question.value = TimerQuestion(current, outcome.seconds)
            return null
        }
        run(then)
        return logOf(current, outcome)
    }

    /** TM7: [timing] is `Timed`, `Untimed` (too short), or null for "without a time". */
    private fun logOf(song: TimedSong, timing: StopOutcome?): TimerLog = TimerLog(
        songId = song.timer.songId,
        instrumentId = timer.instrumentFor(song.timer, session.value.instruments) ?: song.timer.instrumentId,
        title = song.title,
        loggedOn = timer.loggedOn(song.timer),
        durationSeconds = (timing as? StopOutcome.Timed)?.seconds,
        timing = timing,
    )

    /** The running timer is now [song], in memory at once and in the store behind it (TM10: written at start). */
    private fun run(song: TimedSong?) {
        ticket++
        _running.value = song
        // Each change is written, in order: the lock is fair, so the store ends on the last.
        val value = song?.timer
        scope.launch {
            writes.withLock {
                catchingFailure { write(value) }.onFailure { failure -> say(Messages.timerWriteFailed(failure)) }
            }
        }
    }

    private fun closeCancelWindow() {
        cancelWindow?.cancel()
        cancelWindow = null
        _cancelled.value = null
    }

    /**
     * TM10, at every load that completes: the first reads the store, and each one checks the timer's song is
     * still live, since a route may have removed or merged it. A removed song's timer is dropped quietly.
     */
    private suspend fun check() {
        val mine = ticket
        val candidate = if (storeRead) {
            _running.value?.timer
        } else {
            storeRead = true
            catchingFailure { read() }.getOrElse { failure -> return say(Messages.timerReadFailed(failure)) }
        } ?: return
        val live = catchingFailure { title(candidate.songId) }.getOrElse { failure -> return say(Messages.timerReadFailed(failure)) }
        if (ticket != mine) return
        when (val restored = timer.restore(candidate, songLive = live != null)) {
            is PracticeTimer.State.Running -> _running.value = TimedSong(restored, checkNotNull(live))
            PracticeTimer.State.Idle -> {
                run(null)
                say(Messages.TIMER_SONG_REMOVED)
            }
        }
    }

    private fun say(message: String) {
        apply { it.withMessage(message) }
    }

    init {
        scope.launch {
            session.map { it.loading }.distinctUntilChanged().filter { loading -> !loading }.collect { check() }
        }
    }

    public companion object {
        /** TM9: how long "Timer cancelled · Undo" can restore it: the tap snackbar's short window. */
        public const val UNDO_WINDOW_MS: Long = 4_000L
    }
}

/** timer TM7: what a timer's stop logs, as a tap on [instrumentId], the stored instrument or its V20a fallback. */
public data class TimerLog(
    val songId: String,
    val instrumentId: String,
    val title: String,
    val loggedOn: String,
    val durationSeconds: Long?,
    val timing: StopOutcome?,
)
