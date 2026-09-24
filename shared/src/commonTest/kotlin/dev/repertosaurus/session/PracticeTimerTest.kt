package dev.repertosaurus.session

import dev.repertosaurus.TestClock
import dev.repertosaurus.session.PracticeTimer.Running
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** timer TM1-TM12 on [PracticeTimer], with a clock the test moves. Every expected value is worked by hand. */
class PracticeTimerTest {

    /** 2026-09-24 20:00:00 UTC. */
    private val start = Instant.parse("2026-09-24T20:00:00Z")
    private val clock = TestClock(start)
    private var zone: TimeZone = TimeZone.UTC
    private val timer = PracticeTimer(clock) { zone }

    private fun after(seconds: Long) {
        clock.instant = Instant.fromEpochMilliseconds(start.toEpochMilliseconds() + seconds * 1_000L)
    }

    private fun stopAfter(seconds: Long): StopOutcome {
        val running = timer.start("song", "vocal")
        after(seconds)
        return timer.stop(running)
    }

    // ---- Transitions --------------------------------------------------------------------------

    /** TM1, TM3: start records the song, the instrument given at start, and now. */
    @Test
    fun startRecordsTheSongTheInstrumentAndNow() {
        assertEquals(Running("jolene", "guitar", 1_790_280_000_000L), timer.start("jolene", "guitar"))
    }

    /** TM7: stop at 24 min is one timed outcome of 1440 s, dated the day it started. */
    @Test
    fun stopIsTimedAndDated() {
        val running = timer.start("jolene", "guitar")
        after(24L * 60L)
        assertEquals(StopOutcome.Timed(1_440L), timer.stop(running))
        assertEquals("2026-09-24", timer.loggedOn(running))
    }

    /** TM2: a switch stops the first by TM7-TM8 and starts the second from the moment of the switch. */
    @Test
    fun switchStopsTheFirstAndStartsTheSecondNow() {
        val first = timer.start("jolene", "guitar")
        after(600L)
        assertEquals(StopOutcome.Timed(600L), timer.stop(first))
        val second = timer.start("wagon wheel", "vocal")
        assertEquals(Running("wagon wheel", "vocal", 1_790_280_600_000L), second)
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

    // ---- TM7, TM12: the date -------------------------------------------------------------------

    /** A timer started at 23:50 local and stopped at 00:20 the next day is logged on the day it began. */
    @Test
    fun aSessionPastMidnightBelongsToTheDayItBegan() {
        // 2026-09-24T11:50Z is 23:50 on 2026-09-24 in Auckland (NZST, +12:00; daylight saving starts 27 Sep).
        zone = TimeZone.of("Pacific/Auckland")
        clock.instant = Instant.parse("2026-09-24T11:50:00Z")
        val running = timer.start("song", "vocal")
        clock.instant = Instant.parse("2026-09-24T12:20:00Z")
        assertEquals(StopOutcome.Timed(1_800L), timer.stop(running))
        assertEquals("2026-09-24", timer.loggedOn(running))
    }

    /**
     * TM12: the zone is the one current at Stop, not at start. A start at 20:00 UTC is 2026-09-24 in UTC, and
     * 2026-09-25 (08:00) in Auckland.
     */
    @Test
    fun theZoneIsReadAtStop() {
        val running = timer.start("song", "vocal")
        after(600L)
        assertEquals("2026-09-24", timer.loggedOn(running))
        zone = TimeZone.of("Pacific/Auckland")
        assertEquals("2026-09-25", timer.loggedOn(running))
    }

    // ---- TM11: the clock went backwards ---------------------------------------------------------

    /** A negative elapsed time reads as zero, so Stop takes the under-10-s branch, and it is dated today. */
    @Test
    fun aClockThatWentBackwardsReadsZeroAndIsDatedToday() {
        val running = timer.start("song", "vocal")
        clock.instant = Instant.parse("2026-09-20T09:00:00Z")
        assertEquals(0L, timer.elapsed(running))
        assertEquals(StopOutcome.Untimed, timer.stop(running))
        assertEquals("2026-09-20", timer.loggedOn(running))
    }

    /** A stored start later than now is not restorable; now and earlier are. */
    @Test
    fun aStartInTheFutureIsNotRestorable() {
        val now = start.toEpochMilliseconds()
        assertFalse(timer.restorable(Running("s", "vocal", now + 1L)))
        assertTrue(timer.restorable(Running("s", "vocal", now)))
        assertTrue(timer.restorable(Running("s", "vocal", 0L)))
    }

    // ---- TM10 -------------------------------------------------------------------------------------

    /** views V20a: a removed instrument falls back to the first chip; with no chip, Stop cannot run yet. */
    @Test
    fun aRemovedInstrumentFallsBackToTheFirstChip() {
        val chips = listOf(InstrumentChip("vocal-id", "vocal"), InstrumentChip("guitar-id", "guitar"))
        assertEquals("vocal-id", timer.instrumentFor(Running("s", "removed-id", 0L), chips))
        assertEquals("guitar-id", timer.instrumentFor(Running("s", "guitar-id", 0L), chips))
        assertNull(timer.instrumentFor(Running("s", "guitar-id", 0L), emptyList()))
    }

    // ---- The words (Messages) ----------------------------------------------------------------

    @Test
    fun theClockReadsMinutesThenHours() {
        assertEquals(
            listOf("00:00", "00:09", "01:05", "59:59", "1:00:00", "5:12:03", "24:00:00"),
            listOf(0L, 9L, 65L, 3_599L, 3_600L, 18_723L, 86_400L).map(Messages::timerClock),
        )
    }

    @Test
    fun theSnackbarAndTheQuestionSayTheTime() {
        assertEquals("Logged Jolene · 24 min", Messages.logged("Jolene", null, LoggedTime.Timed(1_440L)))
        assertEquals("Logged Jolene · 45 s", Messages.logged("Jolene", null, LoggedTime.Timed(45L)))
        assertEquals("Logged Jolene (too short to time)", Messages.logged("Jolene", null, LoggedTime.TooShort))
        assertEquals("Logged Jolene", Messages.logged("Jolene", null))
        assertEquals("Log 5 h 12 min, or log without a time?", Messages.timerQuestion(18_723L))
        assertEquals("Log 24 h 0 min", Messages.timerLogWith(86_400L))
    }
}
