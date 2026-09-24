package dev.repertosaurus.session

import dev.repertosaurus.data.RepertosaurusRepository.PracticeEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** scorecards SC19: a song's timed history, from its live history. Every expected value is worked by hand. */
class TimedHistoryTest {

    private fun entry(id: String, loggedOn: String, instrument: String?, seconds: Long?): PracticeEntry =
        PracticeEntry(id, loggedOn, "i-$instrument", instrument, contextName = null, feel = null, note = null, durationSeconds = seconds)

    /** 1 440 + 3 000 = 4 440 s = 74 min = "1 h 14 min". The untimed taps are skipped and add nothing. */
    @Test
    fun onlyTimedEventsAreListedAndTotalled() {
        val history = listOf(
            entry("e4", "2026-09-22", "vocal", 1_440L),
            entry("e3", "2026-09-21", "guitar", null),
            entry("e2", "2026-09-20", null, 3_000L),
            entry("e1", "2026-09-19", "vocal", null),
        )

        val timed = TimedHistory.of(history)

        assertEquals(
            TimedHistory(
                listOf(TimedEvent("e4", "2026-09-22", "vocal", 1_440L), TimedEvent("e2", "2026-09-20", null, 3_000L)),
                totalSeconds = 4_440L,
            ),
            timed,
        )
        assertEquals("Timed total: 1 h 14 min", Messages.timedTotalLine(timed.totalSeconds))
        assertEquals("2026-09-22, Vocal: 24 min", Messages.timedEventLine(timed.events[0]))
        assertEquals("2026-09-20, Removed instrument: 50 min", Messages.timedEventLine(timed.events[1]))
    }

    @Test
    fun aSongWithNothingTimedHasNoTimedHistory() {
        assertSame(TimedHistory.NONE, TimedHistory.of(listOf(entry("e1", "2026-09-19", "vocal", null))))
        assertSame(TimedHistory.NONE, TimedHistory.of(emptyList()))
    }
}
