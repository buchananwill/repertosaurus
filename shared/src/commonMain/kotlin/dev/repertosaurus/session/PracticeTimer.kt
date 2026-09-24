package dev.repertosaurus.session

import dev.repertosaurus.data.RepertosaurusRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * timer TM1-TM11: the practice timer's rules, with no device and no coroutine.
 *
 * The timer is a stored start instant, never a ticking process (TM6): every answer here is computed from
 * [State.Running.startedAtEpochMs] and the injected [clock], whenever it is asked for. The clock is wall-clock
 * time because the timer must survive a reboot (TM11).
 */
public class PracticeTimer(
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) {

    public sealed interface State {
        public data object Idle : State

        /** TM3, TM10: the song, the View's practice instrument at the start, and the start instant. */
        public data class Running(val songId: String, val instrumentId: String, val startedAtEpochMs: Long) : State
    }

    /** What Stop does (TM7, TM8): the outcome, and the `logged_on` of the event it writes. */
    public data class Stop(val running: State.Running, val outcome: StopOutcome, val loggedOn: String)

    public fun now(): Long = clock.now().toEpochMilliseconds()

    /** TM1, TM3: a timer from now, on [instrumentId], the View's practice instrument at this moment. */
    public fun start(songId: String, instrumentId: String): State.Running = State.Running(songId, instrumentId, now())

    /** TM4, TM11: whole seconds since [running] started. A clock that went backwards reads as zero. */
    public fun elapsed(running: State.Running, nowEpochMs: Long = now()): Long =
        ((nowEpochMs - running.startedAtEpochMs) / 1_000L).coerceAtLeast(0L)

    /** TM7, TM8: Stop, now. The timer is Idle after it, unless the outcome is a question. */
    public fun stop(running: State.Running): Stop =
        Stop(running, outcome(elapsed(running)), loggedOn(running))

    /** TM9: Cancel writes nothing. [running] is what an Undo restores, start instant and all. */
    @Suppress("UNUSED_PARAMETER")
    public fun cancel(running: State.Running): State.Idle = State.Idle

    /**
     * TM10: a stored timer at launch. It runs on if its song is live, and is dropped quietly if the song was
     * soft-deleted or merged away ([songLive] false).
     */
    public fun restore(stored: State.Running?, songLive: Boolean): State =
        if (stored != null && songLive) stored else State.Idle

    /**
     * TM10, views V20a: the instrument Stop logs to: the stored one while it is live, otherwise the first chip,
     * by `SessionInstruments.resolve`. Null only when there is no live instrument at all.
     */
    public fun instrumentFor(running: State.Running, chips: List<InstrumentChip>): String? =
        SessionInstruments.resolve(running.instrumentId, chips)

    /** TM7: the local date the timer started. A session that runs past midnight belongs to the day it began. */
    public fun loggedOn(running: State.Running): String =
        Instant.fromEpochMilliseconds(running.startedAtEpochMs).toLocalDateTime(timeZone).date.toString()

    public companion object {
        /** TM8: below this, Stop logs untimed. */
        public const val MIN_TIMED_SECONDS: Long = 10L

        /** TM8: above this, Stop asks. Exactly three hours is still a timed log. */
        public const val ASK_ABOVE_SECONDS: Long = 3L * 60L * 60L

        /** TM8, schema-3 M10: the CHECK's ceiling, and the cap on "with a time". */
        public const val MAX_SECONDS: Long = RepertosaurusRepository.MAX_DURATION_SECONDS

        /** TM8's boundaries over [elapsedSeconds]. */
        public fun outcome(elapsedSeconds: Long): StopOutcome = when {
            elapsedSeconds < MIN_TIMED_SECONDS -> StopOutcome.Untimed
            elapsedSeconds <= ASK_ABOVE_SECONDS -> StopOutcome.Timed(elapsedSeconds)
            else -> StopOutcome.NeedsChoice(elapsedSeconds.coerceAtMost(MAX_SECONDS))
        }
    }
}

/** timer TM8: the three ways a Stop can end. */
public sealed interface StopOutcome {
    /** A timed log of [seconds], 10 s to 3 h. */
    public data class Timed(val seconds: Long) : StopOutcome

    /** Under 10 s: an untimed log, "too short to time". */
    public data object Untimed : StopOutcome

    /** Over 3 h: the one question. [seconds] is already capped at [PracticeTimer.MAX_SECONDS]. */
    public data class NeedsChoice(val seconds: Long) : StopOutcome
}

/** timer TM4: a running timer and its song's title, as the bar, the full-screen clock and the drawer show it. */
public data class TimedSong(val timer: PracticeTimer.State.Running, val title: String)

/** timer TM8: the over-3-h question, open. [seconds] is what "with a time" logs. */
public data class TimerQuestion(val song: TimedSong, val seconds: Long)
