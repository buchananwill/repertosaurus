package dev.repertosaurus.session

import dev.repertosaurus.data.RepertosaurusRepository
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * timer TM1-TM12: the practice timer's rules, with no device and no coroutine.
 *
 * The timer is a stored start instant, never a ticking process (TM6): every answer here is computed from
 * [Running.startedAtEpochMs] and the injected [clock], whenever it is asked for. The clock is wall-clock time
 * because the timer must survive a reboot (TM11). The zone is read when a log is dated, never captured (TM12).
 */
public class PracticeTimer(
    private val clock: Clock = Clock.System,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {

    /** timer TM3, TM10: the song, the View's practice instrument at the start, and the start instant. */
    public data class Running(val songId: String, val instrumentId: String, val startedAtEpochMs: Long)

    private fun now(): Long = clock.now().toEpochMilliseconds()

    /** timer TM1, TM3: a timer from now, on [instrumentId], the View's practice instrument at this moment. */
    public fun start(songId: String, instrumentId: String): Running = Running(songId, instrumentId, now())

    /** timer TM4, TM11: whole seconds since [running] started. A clock that went backwards reads as zero. */
    public fun elapsed(running: Running): Long = ((now() - running.startedAtEpochMs) / 1_000L).coerceAtLeast(0L)

    /** timer TM8: what Stop does now. */
    public fun stop(running: Running): StopOutcome = outcome(elapsed(running))

    /** timer TM11: a stored start later than now is corrupt, and is dropped as a removed song's is. */
    public fun restorable(stored: Running): Boolean = stored.startedAtEpochMs <= now()

    /**
     * timer TM10, views V20a: the instrument Stop logs to: the stored one while it is live, otherwise the first
     * chip. Null while there is no live instrument, which means Stop cannot run yet.
     */
    public fun instrumentFor(running: Running, chips: List<InstrumentChip>): String? =
        SessionInstruments.resolve(running.instrumentId, chips)

    /**
     * timer TM7, TM12: the start instant's date in the zone current now, so a session past midnight belongs to
     * the day it began. timer TM11: a start later than now dates the log today.
     */
    public fun loggedOn(running: Running): String {
        val now = now()
        val dated = if (running.startedAtEpochMs > now) now else running.startedAtEpochMs
        return Instant.fromEpochMilliseconds(dated).toLocalDateTime(zone()).date.toString()
    }

    public companion object {
        /** timer TM8: below this, Stop logs untimed. */
        public const val MIN_TIMED_SECONDS: Long = 10L

        /** timer TM8: above this, Stop asks. Exactly three hours is still a timed log. */
        public const val ASK_ABOVE_SECONDS: Long = 3L * 60L * 60L

        /** timer TM8, schema-3 M10: the CHECK's ceiling, and the cap on "with a time". */
        public const val MAX_SECONDS: Long = RepertosaurusRepository.MAX_DURATION_SECONDS

        /** timer TM8's boundaries over [elapsedSeconds]. */
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

/**
 * timer TM7, TM8: what a timer's log says of its time. A log answered "without a time" carries none, and says
 * nothing of one.
 */
public sealed interface LoggedTime {
    public data class Timed(val seconds: Long) : LoggedTime

    public data object TooShort : LoggedTime
}

/** timer TM4: a running timer and its song's title, as the bar, the full-screen clock and the drawer show it. */
public data class TimedSong(val timer: PracticeTimer.Running, val title: String)

/** timer TM8: the over-3-h question, open. [seconds] is what "with a time" logs. */
public data class TimerQuestion(val song: TimedSong, val seconds: Long)
