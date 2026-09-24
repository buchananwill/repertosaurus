package dev.repertosaurus.android

import dev.repertosaurus.core.Ids
import dev.repertosaurus.session.InstrumentChip
import dev.repertosaurus.session.LoggedTime
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.PracticeTimer
import dev.repertosaurus.session.SessionState
import dev.repertosaurus.session.SessionTap
import dev.repertosaurus.session.StopOutcome
import dev.repertosaurus.session.TimedSong
import dev.repertosaurus.session.TimerQuestion
import kotlinx.coroutines.CoroutineScope
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
 * timer TM1-TM12: the one timer (TM2), the over-3-h question (TM8) and a cancelled timer while Undo can
 * restore it (TM9).
 *
 * **A decision to log is returned, never made here:** [start], [stop] and [answer] hand back a [TimerLog] for
 * the ViewModel to log as a tap. The timer is a stored start instant (TM6); [elapsed] is computed from it
 * whenever the clock is drawn, and nothing here ticks.
 *
 * [read], [write] and [title] do their own IO; [title] is a live song's title, null once it is removed. [apply]
 * is the state holder's atomic update.
 */
public class TimerHolder(
    private val session: StateFlow<SessionState>,
    private val scope: CoroutineScope,
    private val read: suspend () -> PracticeTimer.Running?,
    private val write: suspend (PracticeTimer.Running?) -> Unit,
    private val title: suspend (songId: String) -> String?,
    private val apply: ((SessionState) -> SessionState) -> Unit,
    private val timer: PracticeTimer,
) {
    private val _running = MutableStateFlow<TimedSong?>(null)
    public val running: StateFlow<TimedSong?> = _running.asStateFlow()

    /** timer TM8: the question, and the timer a switch starts once it is answered (TM2). */
    private class Asked(val question: TimerQuestion, val then: TimedSong?)

    private var asked: Asked? = null
    private val _question = MutableStateFlow<TimerQuestion?>(null)

    /** timer TM8: the one question, open. The timer runs on while it is. */
    public val question: StateFlow<TimerQuestion?> = _question.asStateFlow()

    /** timer TM9: a cancelled timer, while its snackbar offers Undo. Each Cancel is a new one. */
    public class Cancelled internal constructor(internal val song: TimedSong)

    private val _cancelled = MutableStateFlow<Cancelled?>(null)
    public val cancelled: StateFlow<Cancelled?> = _cancelled.asStateFlow()

    /** Moves at every change of the running timer, so a TM10 check that read before it never lands. */
    private var ticket = 0L

    /** timer TM10: true once a restore from the store has completed, whatever it found. */
    private var storeRead = false

    /**
     * A clear that failed twice: the start instant of the timer the store may still hold. It is never restored,
     * or a stopped timer would come back and log twice.
     */
    private var pendingClear: Long? = null

    private val writes = Mutex()

    /** timer TM4, TM11: whole seconds on [song]'s clock, now. */
    public fun elapsed(song: TimedSong): Long = timer.elapsed(song.timer)

    /** timer TM10, views V20a: false while no live instrument can take [song]'s log, so Stop waits. */
    public fun canStop(song: TimedSong, instruments: List<InstrumentChip>): Boolean =
        timer.instrumentFor(song.timer, instruments) != null

    /**
     * timer TM1-TM3: a timer on [songId] and the View's practice instrument now. **TM2:** a running timer is
     * stopped by TM7-TM8 first and its log returned; one that needs TM8's question asks it, and this one starts
     * on the answer.
     */
    public fun start(songId: String, title: String): TimerLog? {
        if (asked != null) return null
        val instrumentId = session.value.selectedInstrumentId ?: return null
        _cancelled.value = null
        val started = TimedSong(timer.start(songId, instrumentId), title)
        val current = _running.value
        if (current == null) {
            become(started)
            return null
        }
        return stopping(current, then = started)
    }

    /** timer TM7, TM8: Stop. Its log, or null when there is nothing to log yet. */
    public fun stop(): TimerLog? {
        if (asked != null) return null
        val current = _running.value ?: return null
        return stopping(current, then = null)
    }

    /** timer TM8: the question's answer. The timer stops, and a switch waiting on it starts. */
    public fun answer(withTime: Boolean): TimerLog? {
        val open = asked ?: return null
        val song = open.question.song
        val instrumentId = timer.instrumentFor(song.timer, session.value.instruments) ?: return null
        ask(null)
        become(open.then)
        return logOf(song, instrumentId, if (withTime) LoggedTime.Timed(open.question.seconds) else null)
    }

    /** timer TM8: the question put away unanswered. Nothing was stopped, and no switch happens. */
    public fun dismissQuestion() {
        ask(null)
    }

    /** timer TM9: Cancel writes nothing, and offers the timer back. */
    public fun cancel() {
        if (asked != null) return
        val current = _running.value ?: return
        become(null)
        _cancelled.value = Cancelled(current)
    }

    /** timer TM9: [cancelled]'s timer again, original start instant and all. */
    public fun undoCancel(cancelled: Cancelled) {
        if (_cancelled.value !== cancelled) return
        _cancelled.value = null
        if (_running.value == null) become(cancelled.song)
    }

    /** timer TM9: [cancelled]'s snackbar went unanswered, and the timer is gone for good. */
    public fun dismissCancel(cancelled: Cancelled) {
        if (_cancelled.value === cancelled) _cancelled.value = null
    }

    /** An import or a fresh database: its songs are not this timer's, so the timer goes without a word. */
    public fun clear() {
        ask(null)
        _cancelled.value = null
        become(null)
    }

    private fun stopping(current: TimedSong, then: TimedSong?): TimerLog? {
        val instrumentId = timer.instrumentFor(current.timer, session.value.instruments) ?: return null
        val time = when (val outcome = timer.stop(current.timer)) {
            is StopOutcome.NeedsChoice -> {
                ask(Asked(TimerQuestion(current, outcome.seconds), then))
                return null
            }
            is StopOutcome.Timed -> LoggedTime.Timed(outcome.seconds)
            StopOutcome.Untimed -> LoggedTime.TooShort
        }
        become(then)
        return logOf(current, instrumentId, time)
    }

    private fun ask(open: Asked?) {
        asked = open
        _question.value = open?.question
    }

    /** timer TM7, TM12: the tap a stop logs, dated now. [time] is null for "without a time". */
    private fun logOf(song: TimedSong, instrumentId: String, time: LoggedTime?): TimerLog = TimerLog(
        tap = SessionTap(
            tapId = Ids.random(),
            songId = song.timer.songId,
            instrumentId = instrumentId,
            feel = null,
            note = null,
            loggedOn = timer.loggedOn(song.timer),
            durationSeconds = (time as? LoggedTime.Timed)?.seconds,
        ),
        title = song.title,
        time = time,
    )

    /** The running timer is now [song], in memory at once and in the store behind it (TM10: written at start). */
    private fun become(song: TimedSong?) {
        ticket++
        val replaced = _running.value?.timer
        _running.value = song
        val value = song?.timer
        scope.launch {
            writes.withLock { persist(value, replaced) }
        }
    }

    /**
     * One store write, tried twice. A write that still fails may leave [replaced] in the store, so its start
     * instant is never restored.
     */
    private suspend fun persist(value: PracticeTimer.Running?, replaced: PracticeTimer.Running?) {
        var written = catchingFailure { write(value) }
        if (written.isFailure) written = catchingFailure { write(value) }
        written.fold(
            onSuccess = { pendingClear = null },
            onFailure = { failure ->
                if (replaced != null) pendingClear = replaced.startedAtEpochMs
                say(Messages.timerWriteFailed(failure))
            },
        )
    }

    /** timer TM10, TM11: the stored timer, unless a failed clear or a start later than now rules it out. */
    private suspend fun stored(): PracticeTimer.Running? {
        val value = read() ?: return null
        if (value.startedAtEpochMs == pendingClear) return null
        if (!timer.restorable(value)) {
            persist(null, value)
            return null
        }
        return value
    }

    /** timer TM10: a removed song's timer, dropped with the one line. */
    private fun discard() {
        become(null)
        say(Messages.TIMER_SONG_REMOVED)
    }

    /**
     * timer TM10, at every load that completes: until a restore has completed, the store is read; after it, the
     * running timer's song is checked still live, since a route may have removed or merged it.
     */
    private suspend fun check() {
        val mine = ticket
        val candidate = _running.value?.timer ?: restoring() ?: return
        val live = catchingFailure { title(candidate.songId) }.getOrElse { failure -> return say(Messages.timerReadFailed(failure)) }
        if (ticket != mine) return
        storeRead = true
        if (live == null) discard() else _running.value = TimedSong(candidate, live)
    }

    /** The store's timer, while no restore has completed; a read that finds none completes it. */
    private suspend fun restoring(): PracticeTimer.Running? {
        if (storeRead) return null
        val stored = catchingFailure { writes.withLock { stored() } }
            .getOrElse { failure -> return null.also { say(Messages.timerReadFailed(failure)) } }
        if (stored == null) storeRead = true
        return stored
    }

    private fun say(message: String) {
        apply { it.withMessage(message) }
    }

    init {
        scope.launch {
            session.map { it.loading }.distinctUntilChanged().filter { loading -> !loading }.collect { check() }
        }
    }
}

/** timer TM7: what a timer's stop logs: a tap on the stored instrument or its V20a fallback. */
public data class TimerLog(val tap: SessionTap, val title: String, val time: LoggedTime?)
