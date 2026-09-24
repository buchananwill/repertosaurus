package dev.repertosaurus.android

import dev.repertosaurus.session.InstrumentChip
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.PracticeTimer
import dev.repertosaurus.session.PracticeTimer.State.Running
import dev.repertosaurus.session.SessionOrder
import dev.repertosaurus.session.SessionRow
import dev.repertosaurus.session.SessionState
import dev.repertosaurus.session.SessionView
import dev.repertosaurus.session.StopOutcome
import dev.repertosaurus.session.TimedSong
import dev.repertosaurus.session.TimerQuestion
import dev.repertosaurus.session.ViewFilter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * timer TM1-TM11 on [TimerHolder], with no device. Every test asserts the whole sequence the running timer took
 * and every store write, with values worked by hand.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimerHolderTest {

    private val vocal = InstrumentChip("vocal", "vocal")
    private val guitar = InstrumentChip("guitar", "guitar")
    private val songs = listOf(
        SessionRow("jolene", "Jolene", "Dolly Parton", 3L, 4L),
        SessionRow("wagon", "Wagon Wheel", "Old Crow", null, 0L),
    )

    private fun view(instrument: String) = SessionView("v", "Mine", ViewFilter.NONE, instrument, SessionOrder.COLDEST_FIRST, position = 0L)

    private fun session(loading: Boolean = false, instrument: String? = "vocal") = MutableStateFlow(
        SessionState(view = instrument?.let(::view), instruments = listOf(vocal, guitar)).withRows(songs).copy(loading = loading),
    )

    /** 2026-09-24T20:00:00Z. */
    private val t0 = 1_790_280_000_000L

    private fun timer(song: String, instrument: String, at: Long) = Running(song, instrument, at)
    private fun jolene(at: Long = t0) = TimedSong(timer("jolene", "vocal", at), "Jolene")

    // ---- TM10: the store at launch ----------------------------------------------------------------

    /** A stored timer on a live song comes back at the first load, and nothing is written. */
    @Test
    fun aStoredTimerIsRestoredAtTheFirstLoad() = runTest {
        val session = session(loading = true)
        val rig = Rig(this, session, stored = timer("jolene", "vocal", t0 - 60_000L))
        runCurrent()
        assertEquals(0, rig.reads, "not while the first load runs")

        session.update { it.copy(loading = false) }
        runCurrent()

        assertEquals(listOf(null, jolene(t0 - 60_000L)), rig.running)
        assertEquals(1, rig.reads)
        assertEquals(emptyList(), rig.written)
        assertEquals(60L, rig.holder.elapsed(rig.holder.running.value!!))
    }

    /** A stored timer whose song is gone is dropped quietly, with the one line. */
    @Test
    fun aRemovedSongsStoredTimerIsDropped() = runTest {
        val rig = Rig(this, session(), stored = timer("gone", "vocal", t0))
        runCurrent()
        assertEquals(listOf<TimedSong?>(null), rig.running)
        assertEquals(listOf<Running?>(null), rig.written)
        assertEquals(listOf<String?>(Messages.TIMER_SONG_REMOVED), rig.messages)
    }

    /** A song removed while its timer runs is found at the next load, as a route's return reloads. */
    @Test
    fun aSongRemovedWhileRunningIsDroppedAtTheNextLoad() = runTest {
        val session = session()
        val rig = Rig(this, session)
        runCurrent()
        rig.holder.start("wagon", "Wagon Wheel")
        runCurrent()
        rig.titles.remove("wagon")
        session.update { it.copy(loading = true) }
        runCurrent()
        session.update { it.copy(loading = false) }
        runCurrent()

        val wagon = TimedSong(timer("wagon", "vocal", t0), "Wagon Wheel")
        assertEquals(listOf(null, wagon, null), rig.running)
        assertEquals(listOf(wagon.timer, null), rig.written)
        assertEquals(listOf<String?>(Messages.TIMER_SONG_REMOVED), rig.messages)
    }

    /** A restore whose read lands after a start never replaces the timer the user just started. */
    @Test
    fun aLateRestoreNeverReplacesANewTimer() = runTest {
        val rig = Rig(this, session(), stored = timer("wagon", "vocal", t0 - 3_600_000L))
        rig.gate = CompletableDeferred()
        runCurrent()
        rig.holder.start("jolene", "Jolene")
        rig.gate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf(null, jolene()), rig.running)
        assertEquals<List<Running?>>(listOf(jolene().timer), rig.written)
    }

    // ---- TM1-TM3, TM7, TM8: start and stop ----------------------------------------------------------

    /** TM1, TM3, TM10: start is written at once, on the View's instrument, from now. */
    @Test
    fun startIsWrittenAtStart() = runTest {
        val rig = Rig(this, session())
        runCurrent()
        assertNull(rig.holder.start("jolene", "Jolene"))
        runCurrent()
        assertEquals(listOf(null, jolene()), rig.running)
        assertEquals<List<Running?>>(listOf(jolene().timer), rig.written)
    }

    /** With no View there is no practice instrument, and nothing starts. */
    @Test
    fun noViewStartsNothing() = runTest {
        val rig = Rig(this, session(instrument = null))
        runCurrent()
        assertNull(rig.holder.start("jolene", "Jolene"))
        runCurrent()
        assertEquals(listOf<TimedSong?>(null), rig.running)
        assertEquals(emptyList(), rig.written)
    }

    /** TM7: 24 minutes is one timed log, on the start's date, and the store is cleared. */
    @Test
    fun stopLogsTheTimeOnTheStartDate() = runTest {
        val rig = Rig(this, session())
        runCurrent()
        rig.holder.start("jolene", "Jolene")
        rig.now = t0 + 24L * 60_000L
        val log = rig.holder.stop()
        runCurrent()
        assertEquals(TimerLog("jolene", "vocal", "Jolene", "2026-09-24", 1_440L, StopOutcome.Timed(1_440L)), log)
        assertEquals(listOf(null, jolene(), null), rig.running)
        assertEquals(listOf(jolene().timer, null), rig.written)
        assertNull(rig.holder.stop(), "nothing left to stop")
    }

    /** TM8: 9 s logs untimed; 10 s is timed. */
    @Test
    fun theTenSecondBoundary() = runTest {
        val rig = Rig(this, session())
        runCurrent()
        rig.holder.start("jolene", "Jolene")
        rig.now = t0 + 9_999L
        assertEquals(TimerLog("jolene", "vocal", "Jolene", "2026-09-24", null, StopOutcome.Untimed), rig.holder.stop())

        rig.now = t0
        rig.holder.start("jolene", "Jolene")
        rig.now = t0 + 10_000L
        assertEquals(TimerLog("jolene", "vocal", "Jolene", "2026-09-24", 10L, StopOutcome.Timed(10L)), rig.holder.stop())
    }

    /** TM11: a clock gone backwards stops as under 10 s. */
    @Test
    fun aClockGoneBackwardsLogsUntimed() = runTest {
        val rig = Rig(this, session())
        runCurrent()
        rig.holder.start("jolene", "Jolene")
        rig.now = t0 - 86_400_000L
        assertEquals(TimerLog("jolene", "vocal", "Jolene", "2026-09-24", null, StopOutcome.Untimed), rig.holder.stop())
    }

    /** TM3: the log lands on the instrument at start, though the View has moved to guitar since. */
    @Test
    fun theInstrumentIsTheOneAtStart() = runTest {
        val session = session()
        val rig = Rig(this, session)
        runCurrent()
        rig.holder.start("jolene", "Jolene")
        session.update { it.copy(view = view("guitar")) }
        rig.now = t0 + 60_000L
        assertEquals("vocal", rig.holder.stop()?.instrumentId)
    }

    /** TM10, views V20a: an instrument removed meanwhile falls back to the first live chip. */
    @Test
    fun aRemovedInstrumentFallsBack() = runTest {
        val session = session(instrument = "guitar")
        val rig = Rig(this, session)
        runCurrent()
        rig.holder.start("jolene", "Jolene")
        session.update { it.withInstruments(listOf(vocal)).copy(view = view("vocal")) }
        rig.now = t0 + 60_000L
        assertEquals("vocal", rig.holder.stop()?.instrumentId)
    }

    /** TM8: over 3 h, Stop asks and the timer runs on; "with a time" logs the time asked about. */
    @Test
    fun overThreeHoursAsksThenLogsWithTheTime() = runTest {
        val rig = Rig(this, session())
        runCurrent()
        rig.holder.start("jolene", "Jolene")
        rig.now = t0 + 10_801_000L
        assertNull(rig.holder.stop())
        assertEquals(TimerQuestion(jolene(), 10_801L), rig.holder.question.value)
        assertNull(rig.holder.stop(), "one question at a time")

        rig.now += 30_000L
        val log = rig.holder.answer(withTime = true)
        runCurrent()

        assertEquals(TimerLog("jolene", "vocal", "Jolene", "2026-09-24", 10_801L, StopOutcome.Timed(10_801L)), log)
        assertEquals(listOf(null, TimerQuestion(jolene(), 10_801L), null), rig.questions)
        assertEquals(listOf(null, jolene(), null), rig.running)
        assertEquals(listOf(jolene().timer, null), rig.written)
    }

    /** TM8: "without a time" logs untimed and says nothing of a time. */
    @Test
    fun overThreeHoursWithoutATime() = runTest {
        val rig = Rig(this, session())
        runCurrent()
        rig.holder.start("jolene", "Jolene")
        rig.now = t0 + 5L * 86_400_000L
        rig.holder.stop()
        assertEquals(TimerQuestion(jolene(), 86_400L), rig.holder.question.value, "capped at a day")
        assertEquals(TimerLog("jolene", "vocal", "Jolene", "2026-09-24", null, null), rig.holder.answer(withTime = false))
    }

    /** TM8: the question put away stops nothing. */
    @Test
    fun theQuestionDismissedStopsNothing() = runTest {
        val rig = Rig(this, session())
        runCurrent()
        rig.holder.start("jolene", "Jolene")
        rig.now = t0 + 4L * 3_600_000L
        rig.holder.stop()
        rig.holder.dismissQuestion()
        runCurrent()
        assertEquals(listOf(null, jolene()), rig.running)
        assertEquals<List<Running?>>(listOf(jolene().timer), rig.written)
        assertNull(rig.holder.answer(withTime = true))
    }

    // ---- TM2: switch ------------------------------------------------------------------------------

    /** One tap stops the first by TM7 and starts the second from that moment. */
    @Test
    fun switchStopsAndStarts() = runTest {
        val rig = Rig(this, session())
        runCurrent()
        rig.holder.start("jolene", "Jolene")
        rig.now = t0 + 600_000L
        val log = rig.holder.start("wagon", "Wagon Wheel")
        runCurrent()
        val wagon = TimedSong(timer("wagon", "vocal", t0 + 600_000L), "Wagon Wheel")
        assertEquals(TimerLog("jolene", "vocal", "Jolene", "2026-09-24", 600L, StopOutcome.Timed(600L)), log)
        assertEquals(listOf(null, jolene(), wagon), rig.running)
        assertEquals<List<Running?>>(listOf(jolene().timer, wagon.timer), rig.written)
    }

    /** A switch from a timer over 3 h asks first; the new timer starts on the answer, from the switch's tap. */
    @Test
    fun switchFromAForgottenTimerAsksFirst() = runTest {
        val rig = Rig(this, session())
        runCurrent()
        rig.holder.start("jolene", "Jolene")
        rig.now = t0 + 4L * 3_600_000L
        assertNull(rig.holder.start("wagon", "Wagon Wheel"))
        rig.now += 20_000L
        val log = rig.holder.answer(withTime = true)
        runCurrent()
        val wagon = TimedSong(timer("wagon", "vocal", t0 + 4L * 3_600_000L), "Wagon Wheel")
        assertEquals(TimerLog("jolene", "vocal", "Jolene", "2026-09-24", 14_400L, StopOutcome.Timed(14_400L)), log)
        assertEquals(listOf(null, jolene(), wagon), rig.running)
        assertEquals<List<Running?>>(listOf(jolene().timer, wagon.timer), rig.written)
        assertEquals(20L, rig.holder.elapsed(wagon))
    }

    // ---- TM9: cancel ------------------------------------------------------------------------------

    /** Cancel writes no log; Undo inside the window restores the same timer, start instant and all. */
    @Test
    fun cancelThenUndoRestoresTheSameTimer() = runTest {
        val rig = Rig(this, session())
        runCurrent()
        rig.holder.start("jolene", "Jolene")
        rig.now = t0 + 90_000L
        rig.holder.cancel()
        runCurrent()
        advanceTimeBy(TimerHolder.UNDO_WINDOW_MS - 1)
        rig.holder.undoCancel()
        runCurrent()

        assertEquals(listOf(null, jolene(), null, jolene()), rig.running)
        assertEquals(listOf(null, jolene(), null), rig.cancelled)
        assertEquals(listOf(jolene().timer, null, jolene().timer), rig.written)
        assertEquals(90L, rig.holder.elapsed(rig.holder.running.value!!))
    }

    /** After the window Undo restores nothing. */
    @Test
    fun cancelsWindowCloses() = runTest {
        val rig = Rig(this, session())
        runCurrent()
        rig.holder.start("jolene", "Jolene")
        rig.holder.cancel()
        advanceTimeBy(TimerHolder.UNDO_WINDOW_MS)
        runCurrent()
        rig.holder.undoCancel()
        runCurrent()
        assertEquals(listOf(null, jolene(), null), rig.running)
        assertEquals(listOf(null, jolene(), null), rig.cancelled)
        assertEquals(listOf(jolene().timer, null), rig.written)
    }

    /** A timer started inside the window closes it: Undo never replaces the new timer. */
    @Test
    fun aNewTimerClosesTheCancelWindow() = runTest {
        val rig = Rig(this, session())
        runCurrent()
        rig.holder.start("jolene", "Jolene")
        rig.holder.cancel()
        rig.holder.start("wagon", "Wagon Wheel")
        rig.holder.undoCancel()
        runCurrent()
        val wagon = TimedSong(timer("wagon", "vocal", t0), "Wagon Wheel")
        assertEquals(listOf(null, jolene(), null, wagon), rig.running)
        assertEquals(listOf(null, jolene(), null), rig.cancelled)
    }

    // ---- S11 --------------------------------------------------------------------------------------

    /** A failed store write is said, and the timer in memory runs on. */
    @Test
    fun aFailedWriteIsSaid() = runTest {
        val rig = Rig(this, session(), failWrites = true)
        runCurrent()
        rig.holder.start("jolene", "Jolene")
        runCurrent()
        assertEquals(listOf(null, jolene()), rig.running)
        assertEquals(listOf<String?>(Messages.timerWriteFailed(IllegalStateException("disk full"))), rig.messages)
    }

    /**
     * A holder over [session] with its ports recorded: each store write, each message, and every value the
     * running timer, the question and the cancelled timer take, collected unconfined and in order. [titles] are
     * the live songs. A store read waits on [gate] while one is set.
     */
    private inner class Rig(
        scope: TestScope,
        private val session: MutableStateFlow<SessionState>,
        stored: Running? = null,
        private val failWrites: Boolean = false,
    ) {
        var now = t0
        val titles = mutableMapOf("jolene" to "Jolene", "wagon" to "Wagon Wheel")
        val written = mutableListOf<Running?>()
        val messages = mutableListOf<String?>()
        val running = mutableListOf<TimedSong?>()
        val questions = mutableListOf<TimerQuestion?>()
        val cancelled = mutableListOf<TimedSong?>()
        var reads = 0
        var gate: CompletableDeferred<Unit>? = null
        private val clock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(this@Rig.now)
        }

        val holder = TimerHolder(
            session = session,
            scope = scope.backgroundScope,
            read = {
                reads++
                gate?.await()
                stored
            },
            write = { value ->
                if (failWrites) error("disk full")
                written += value
            },
            title = { songId -> titles[songId] },
            apply = { change ->
                session.update(change)
                messages += session.value.message
            },
            timer = PracticeTimer(clock, TimeZone.UTC),
        )

        init {
            val unconfined = UnconfinedTestDispatcher(scope.testScheduler)
            scope.backgroundScope.launch(unconfined) { holder.running.collect { running += it } }
            scope.backgroundScope.launch(unconfined) { holder.question.collect { questions += it } }
            scope.backgroundScope.launch(unconfined) { holder.cancelled.collect { cancelled += it } }
        }
    }
}
