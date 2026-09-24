package dev.repertosaurus.session

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** timer TM1-TM11 on [PracticeTimer], with a clock the test moves. Every expected value is worked by hand. */
class PracticeTimerTest {

    private class MovableClock(var instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    /** 2026-09-24 20:00:00 UTC. */
    private val start = Instant.parse("2026-09-24T20:00:00Z")
    private val clock = MovableClock(start)
    private val timer = PracticeTimer(clock, TimeZone.UTC)

    private fun after(seconds: Long) {
        clock.instant = Instant.fromEpochMilliseconds(start.toEpochMilliseconds() + seconds * 1_000L)
    }

    private fun stopAfter(seconds: Long): StopOutcome {
        val running = timer.start("song", "vocal")
        after(seconds)
        return timer.stop(running).outcome
    }

    // ---- Transitions --------------------------------------------------------------------------

    /** TM1, TM3: start records the song, the instrument given at start, and now. */
    @Test
    fun startRecordsTheSongTheInstrumentAndNow() {
        val running = timer.start("jolene", "guitar")
        assertEquals(PracticeTimer.State.Running("jolene", "guitar", 1_790_280_000_000L), running)
    }

    /** TM7: stop at 24 min is one timed outcome of 1440 s, dated the day it started. */
    @Test
    fun stopIsTimedAndDated() {
        val running = timer.start("jolene", "guitar")
        after(24L * 60L)
        assertEquals(PracticeTimer.Stop(running, StopOutcome.Timed(1_440L), "2026-09-24"), timer.stop(running))
    }

    /** TM9: cancel is Idle; the value Undo restores is the same timer, start instant included. */
    @Test
    fun cancelThenRestoreKeepsTheStartInstant() {
        val running = timer.start("jolene", "guitar")
        after(90L)
        assertEquals(PracticeTimer.State.Idle, timer.cancel(running))
        assertEquals(running, timer.restore(running, songLive = true))
        assertEquals(90L, timer.elapsed(running))
    }

    /** TM2: a switch stops the first by TM7-TM8 and starts the second from the moment of the switch. */
    @Test
    fun switchStopsTheFirstAndStartsTheSecondNow() {
        val first = timer.start("jolene", "guitar")
        after(600L)
        val stopped = timer.stop(first)
        val second = timer.start("wagon wheel", "vocal")
        assertEquals(StopOutcome.Timed(600L), stopped.outcome)
        assertEquals(PracticeTimer.State.Running("wagon wheel", "vocal", 1_790_280_600_000L), second)
        assertEquals(0L, timer.elapsed(second))
    }

    /** TM4: elapsed is whole seconds, rounded down. */
    @Test
    fun elapsedIsWholeSeconds() {
        val running = timer.start("song", "vocal")
        clock.instant = Instant.fromEpochMilliseconds(start.toEpochMilliseconds() + 59_999L)
        assertEquals(59L, timer.elapsed(running))
    }

    // ---- TM8's boundaries ---------------------------------------------------------------------

    @Test
    fun nineSecondsIsUntimed() = assertEquals(StopOutcome.Untimed, stopAfter(9L))

    @Test
    fun tenSecondsIsTimed() = assertEquals(StopOutcome.Timed(10L), stopAfter(10L))

    @Test
    fun threeHoursLessASecondIsTimed() = assertEquals(StopOutcome.Timed(10_799L), stopAfter(10_799L))

    @Test
    fun exactlyThreeHoursIsTimed() = assertEquals(StopOutcome.Timed(10_800L), stopAfter(10_800L))

    @Test
    fun threeHoursAndASecondAsks() = assertEquals(StopOutcome.NeedsChoice(10_801L), stopAfter(10_801L))

    /** Exactly a day asks, and carries the day. */
    @Test
    fun aDayAsks() = assertEquals(StopOutcome.NeedsChoice(86_400L), stopAfter(86_400L))

    /** Over a day always asks, and "with a time" is capped at 86400 (schema-3 M10's CHECK). */
    @Test
    fun overADayAsksCapped() {
        assertEquals(StopOutcome.NeedsChoice(86_400L), stopAfter(86_401L))
        assertEquals(StopOutcome.NeedsChoice(86_400L), stopAfter(3L * 86_400L))
    }

    // ---- TM7: the date is the start's -----------------------------------------------------------

    /** A timer started at 23:50 local and stopped at 00:20 the next day is logged on the day it began. */
    @Test
    fun aSessionPastMidnightBelongsToTheDayItBegan() {
        val auckland = PracticeTimer(clock, TimeZone.of("Pacific/Auckland"))
        // 2026-09-24T11:50Z is 23:50 on 2026-09-24 in Auckland (NZST, +12:00; daylight saving starts 27 Sep).
        clock.instant = Instant.parse("2026-09-24T11:50:00Z")
        val running = auckland.start("song", "vocal")
        clock.instant = Instant.parse("2026-09-24T12:20:00Z")
        assertEquals(PracticeTimer.Stop(running, StopOutcome.Timed(1_800L), "2026-09-24"), auckland.stop(running))
    }

    // ---- TM11: the clock went backwards ---------------------------------------------------------

    /** A negative elapsed time reads as zero, so Stop takes the under-10-s branch, and nothing throws. */
    @Test
    fun aClockThatWentBackwardsReadsZero() {
        val running = timer.start("song", "vocal")
        after(-3_600L)
        assertEquals(0L, timer.elapsed(running))
        assertEquals(StopOutcome.Untimed, timer.stop(running).outcome)
    }

    // ---- TM10 -------------------------------------------------------------------------------------

    /** A stored timer whose song was removed restores to Idle; a live one restores as it was stored. */
    @Test
    fun aRemovedSongsTimerIsDropped() {
        val stored = PracticeTimer.State.Running("gone", "vocal", 1_000L)
        assertEquals(PracticeTimer.State.Idle, timer.restore(stored, songLive = false))
        assertEquals(stored, timer.restore(stored, songLive = true))
        assertEquals(PracticeTimer.State.Idle, timer.restore(null, songLive = true))
    }

    /** views V20a: a removed instrument falls back to the first chip; a live one is kept. */
    @Test
    fun aRemovedInstrumentFallsBackToTheFirstChip() {
        val chips = listOf(InstrumentChip("vocal-id", "vocal"), InstrumentChip("guitar-id", "guitar"))
        assertEquals("vocal-id", timer.instrumentFor(PracticeTimer.State.Running("s", "removed-id", 0L), chips))
        assertEquals("guitar-id", timer.instrumentFor(PracticeTimer.State.Running("s", "guitar-id", 0L), chips))
        assertNull(timer.instrumentFor(PracticeTimer.State.Running("s", "guitar-id", 0L), emptyList()))
    }

    // ---- The words (Messages) ----------------------------------------------------------------

    @Test
    fun theClockReadsMinutesThenHours() {
        assertEquals(listOf("00:00", "00:09", "01:05", "59:59", "1:00:00", "5:12:03", "24:00:00"),
            listOf(0L, 9L, 65L, 3_599L, 3_600L, 18_723L, 86_400L).map(Messages::timerClock))
    }

    @Test
    fun theSnackbarAndTheQuestionSayTheTime() {
        assertEquals("Logged Jolene · 24 min", Messages.logged("Jolene", null, StopOutcome.Timed(1_440L)))
        assertEquals("Logged Jolene · 45 s", Messages.logged("Jolene", null, StopOutcome.Timed(45L)))
        assertEquals("Logged Jolene (too short to time)", Messages.logged("Jolene", null, StopOutcome.Untimed))
        assertEquals("Logged Jolene", Messages.logged("Jolene", null))
        assertEquals("Log 5 h 12 min, or log without a time?", Messages.timerQuestion(18_723L))
        assertEquals("Log 24 h 0 min", Messages.timerLogWith(86_400L))
    }
}
