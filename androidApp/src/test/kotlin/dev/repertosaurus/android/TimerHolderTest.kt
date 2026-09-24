package dev.repertosaurus.android

import dev.repertosaurus.session.InstrumentChip
import dev.repertosaurus.session.LoggedTime
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.PracticeTimer
import dev.repertosaurus.session.PracticeTimer.Running
import dev.repertosaurus.session.SessionOrder
import dev.repertosaurus.session.SessionRow
import dev.repertosaurus.session.SessionState
import dev.repertosaurus.session.SessionTap
import dev.repertosaurus.session.SessionView
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * timer TM1-TM12 on [TimerHolder], with no device. Every test asserts the whole sequence the running timer took
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
    private fun wagon(at: Long = t0) = TimedSong(timer("wagon", "vocal", at), "Wagon Wheel")

    /** A log as expected, with the random tap id set aside. */
    private fun log(song: String, instrument: String, title: String, on: String, time: LoggedTime?) = TimerLog(
        SessionTap("-", song, instrument, feel = null, note = null, loggedOn = on, durationSeconds = (time as? LoggedTime.Timed)?.seconds),
        title,
        time,
    )

    private fun TimerLog?.ignoringId(): TimerLog? = this?.copy(tap = tap.copy(tapId = "-"))

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

    /** TM11: a stored start later than now is corrupt, and is dropped and cleared, not restored. */
    @Test
    fun aStartInTheFutureIsDropped() = runTest {
        val rig = Rig(this, session(), stored = timer("jolene", "vocal", t0 + 1_000L))
        runCurrent()
        assertEquals(listOf<TimedSong?>(null), rig.running)
        assertEquals(listOf<Running?>(null), rig.written)
    }

    /** A first load whose song read fails does not lose the restore: the next load restores it. */
    @Test
    fun aFailedFirstRestoreIsTriedAgain() = runTest {
        val session = session()
        val rig = Rig(this, session, stored = timer("jolene", "vocal", t0))
        rig.failTitles = true
        runCurrent()
        assertEquals(listOf<TimedSong?>(null), rig.running)
        assertEquals(listOf<String?>(Messages.timerReadFailed(IllegalStateException("disk gone"))), rig.messages)

        rig.failTitles = false
        session.update { it.copy(loading = true) }
        runCurrent()
        session.update { it.copy(loading = false) }
        runCurrent()

        assertEquals(listOf(null, jolene()), rig.running)
        assertEquals(2, rig.reads)
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

        assertEquals(listOf(null, wagon(), null), rig.running)
        assertEquals(listOf(wagon().timer, null), rig.written)
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
        assertEquals(listOf<Running?>(jolene().timer), rig.written)
    }

    /**
     * A clear that fails twice keeps its start instant from ever being restored, so a stopped timer cannot come
     * back and log twice. The store still holds it, and the next load, which reads the store because the first
     * read failed, does not bring it back.
     */
    @Test
    fun aFailedClearIsNeverRestored() = runTest {
        val session = session()
        val rig = Rig(this, session)
        rig.failReads = true
        runCurrent()
        rig.failReads = false
        rig.holder.start("jolene", "Jolene")
        runCurrent()
        rig.failClears = true
        rig.now = t0 + 60_000L
        assertNotNull(rig.holder.stop())
        runCurrent()
        assertEquals(jolene().timer, rig.store, "the clear never landed")
        assertEquals(2, rig.clearAttempts, "tried twice")

        session.update { it.copy(loading = true) }
        runCurrent()
        session.update { it.copy(loading = false) }
        runCurrent()

        assertEquals(2, rig.reads, "the store was read again")
        assertEquals(listOf(null, jolene(), null), rig.running)
        assertEquals(
            listOf(Messages.timerReadFailed(IllegalStateException("disk gone")), Messages.timerWriteFailed(IllegalStateException("disk full"))),
            rig.messages.filterNotNull(),
        )
    }

    /** A clear that fails once and lands on the retry is not pending. */
    @Test
    fun aClearIsRetriedOnce() = runTest {
        val rig = Rig(this, session())
        runCurrent()
        rig.holder.start("jolene", "Jolene")
        runCurrent()
        rig.failClearsOnce = true
        rig.holder.stop()
        runCurrent()
        assertEquals(2, rig.clearAttempts)
        assertNull(rig.store)
        assertEquals(emptyList(), rig.messages.filterNotNull())
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
        assertEquals(listOf<Running?>(jolene().timer), rig.written)
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
        val stopped = rig.holder.stop()
        runCurrent()
        assertEquals(log("jolene", "vocal", "Jolene", "2026-09-24", LoggedTime.Timed(1_440L)), stopped.ignoringId())
        assertEquals(listOf(null, jolene(), null), rig.running)
        assertEquals(listOf(jolene().timer, null), rig.written)
        assertNull(rig.holder.stop(), "nothing left to stop")
    }

    /** TM12: the zone is the one current at Stop. 20:00 UTC is the 25th in Auckland. */
    @Test
    fun theLogIsDatedInTheZoneAtStop() = runTest {
        val rig = Rig(this, session())
        runCurrent()
        rig.holder.start("jolene", "Jolene")
        rig.now = t0 + 600_000L
        rig.zone = TimeZone.of("Pacific/Auckland")
        assertEquals("2026-09-25", rig.holder.stop()?.tap?.loggedOn)
    }

    /** TM8: 9 s logs untimed; 10 s is timed. */
    @Test
    fun theTenSecondBoundary() = runTest {
        val rig = Rig(this, session())
        runCurrent()
        rig.holder.start("jolene", "Jolene")
        rig.now = t0 + 9_999L
        assertEquals(log("jolene", "vocal", "Jolene", "2026-09-24", LoggedTime.TooShort), rig.holder.stop().ignoringId())

        rig.now = t0
        rig.holder.start("jolene", "Jolene")
        rig.now = t0 + 10_000L
        assertEquals(log("jolene", "vocal", "Jolene", "2026-09-24", LoggedTime.Timed(10L)), rig.holder.stop().ignoringId())
    }

    /** TM11: a clock gone backwards stops as under 10 s, dated today. */
    @Test
    fun aClockGoneBackwardsLogsUntimedToday() = runTest {
        val rig = Rig(this, session())
        runCurrent()
        rig.holder.start("jolene", "Jolene")
        rig.now = t0 - 3L * 86_400_000L
        assertEquals(log("jolene", "vocal", "Jolene", "2026-09-21", LoggedTime.TooShort), rig.holder.stop().ignoringId())
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
        assertEquals("vocal", rig.holder.stop()?.tap?.instrumentId)
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
        assertEquals("vocal", rig.holder.stop()?.tap?.instrumentId)
    }

    /** With no live instrument at all, Stop cannot run yet: nothing is logged and the timer runs on. */
    @Test
    fun noInstrumentMeansStopWaits() = runTest {
        val session = session()
        val rig = Rig(this, session)
        runCurrent()
        rig.holder.start("jolene", "Jolene")
        session.update { it.withInstruments(emptyList()) }
        rig.now = t0 + 60_000L
        assertFalse(rig.holder.canStop(jolene(), emptyList()))
        assertNull(rig.holder.stop())
        runCurrent()
        assertEquals(listOf(null, jolene()), rig.running)

        session.update { it.withInstruments(listOf(vocal)) }
        assertTrue(rig.holder.canStop(jolene(), listOf(vocal)))
        assertEquals("vocal", rig.holder.stop()?.tap?.instrumentId)
    }

    /** An import or a fresh database clears the timer without a word. */
    @Test
    fun clearDropsTheTimerQuietly() = runTest {
        val rig = Rig(this, session())
        runCurrent()
        rig.holder.start("jolene", "Jolene")
        rig.holder.clear()
        runCurrent()
        assertEquals(listOf(null, jolene(), null), rig.running)
        assertEquals(listOf(jolene().timer, null), rig.written)
        assertEquals(emptyList(), rig.messages)
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
        val answered = rig.holder.answer(withTime = true)
        runCurrent()

        assertEquals(log("jolene", "vocal", "Jolene", "2026-09-24", LoggedTime.Timed(10_801L)), answered.ignoringId())
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
        assertEquals(log("jolene", "vocal", "Jolene", "2026-09-24", null), rig.holder.answer(withTime = false).ignoringId())
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
        assertEquals(listOf<Running?>(jolene().timer), rig.written)
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
        val stopped = rig.holder.start("wagon", "Wagon Wheel")
        runCurrent()
        assertEquals(log("jolene", "vocal", "Jolene", "2026-09-24", LoggedTime.Timed(600L)), stopped.ignoringId())
        assertEquals(listOf(null, jolene(), wagon(t0 + 600_000L)), rig.running)
        assertEquals<List<Running?>>(listOf(jolene().timer, wagon(t0 + 600_000L).timer), rig.written)
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
        val answered = rig.holder.answer(withTime = true)
        runCurrent()
        val wagon = wagon(t0 + 4L * 3_600_000L)
        assertEquals(log("jolene", "vocal", "Jolene", "2026-09-24", LoggedTime.Timed(14_400L)), answered.ignoringId())
        assertEquals(listOf(null, jolene(), wagon), rig.running)
        assertEquals<List<Running?>>(listOf(jolene().timer, wagon.timer), rig.written)
        assertEquals(20L, rig.holder.elapsed(wagon))
    }

    // ---- TM9: cancel ------------------------------------------------------------------------------

    /** Cancel writes no log; Undo restores the same timer, start instant and all, however late it comes. */
    @Test
    fun cancelThenUndoRestoresTheSameTimer() = runTest {
        val rig = Rig(this, session())
        runCurrent()
        rig.holder.start("jolene", "Jolene")
        rig.now = t0 + 90_000L
        rig.holder.cancel()
        runCurrent()
        val offered = assertNotNull(rig.holder.cancelled.value)
        rig.now = t0 + 600_000L
        rig.holder.undoCancel(offered)
        runCurrent()

        assertEquals(listOf(null, jolene(), null, jolene()), rig.running)
        assertEquals(listOf(null, jolene(), null), rig.cancelled)
        assertEquals(listOf(jolene().timer, null, jolene().timer), rig.written)
        assertEquals(600L, rig.holder.elapsed(rig.holder.running.value!!))
    }

    /** The snackbar going unanswered ends the offer, and Undo afterwards restores nothing. */
    @Test
    fun anUnansweredCancelIsGone() = runTest {
        val rig = Rig(this, session())
        runCurrent()
        rig.holder.start("jolene", "Jolene")
        rig.holder.cancel()
        val offered = assertNotNull(rig.holder.cancelled.value)
        rig.holder.dismissCancel(offered)
        rig.holder.undoCancel(offered)
        runCurrent()
        assertEquals(listOf(null, jolene(), null), rig.running)
        assertEquals(listOf(null, jolene(), null), rig.cancelled)
        assertEquals(listOf(jolene().timer, null), rig.written)
    }

    /** A timer started while Undo is offered ends the offer: Undo never replaces the new timer. */
    @Test
    fun aNewTimerEndsTheCancelOffer() = runTest {
        val rig = Rig(this, session())
        runCurrent()
        rig.holder.start("jolene", "Jolene")
        rig.holder.cancel()
        val offered = assertNotNull(rig.holder.cancelled.value)
        rig.holder.start("wagon", "Wagon Wheel")
        rig.holder.undoCancel(offered)
        runCurrent()
        assertEquals(listOf(null, jolene(), null, wagon()), rig.running)
        assertEquals(listOf(null, jolene(), null), rig.cancelled)
    }

    /** A stale snackbar's dismissal never ends a newer offer. */
    @Test
    fun anOlderOfferNeverEndsANewerOne() = runTest {
        val rig = Rig(this, session())
        runCurrent()
        rig.holder.start("jolene", "Jolene")
        rig.holder.cancel()
        val first = assertNotNull(rig.holder.cancelled.value)
        rig.holder.undoCancel(first)
        rig.holder.cancel()
        val second = assertNotNull(rig.holder.cancelled.value)
        rig.holder.dismissCancel(first)
        assertEquals(second, rig.holder.cancelled.value)
    }

    // ---- S11 --------------------------------------------------------------------------------------

    /** A failed store write is said, and the timer in memory runs on. */
    @Test
    fun aFailedWriteIsSaid() = runTest {
        val rig = Rig(this, session())
        rig.failStarts = true
        runCurrent()
        rig.holder.start("jolene", "Jolene")
        runCurrent()
        assertEquals(listOf(null, jolene()), rig.running)
        assertEquals(listOf<String?>(Messages.timerWriteFailed(IllegalStateException("disk full"))), rig.messages)
    }

    /**
     * A holder over [session] with its ports recorded: the store, each write that landed, each message, and every
     * value the running timer, the question and the cancelled timer take, collected unconfined and in order.
     * [titles] are the live songs. A store read waits on [gate] while one is set.
     */
    private inner class Rig(
        scope: TestScope,
        private val session: MutableStateFlow<SessionState>,
        stored: Running? = null,
    ) {
        var now = t0
        var zone: TimeZone = TimeZone.UTC
        var store: Running? = stored
        val titles = mutableMapOf("jolene" to "Jolene", "wagon" to "Wagon Wheel")
        val written = mutableListOf<Running?>()
        val messages = mutableListOf<String?>()
        val running = mutableListOf<TimedSong?>()
        val questions = mutableListOf<TimerQuestion?>()
        val cancelled = mutableListOf<TimedSong?>()
        var reads = 0
        var clearAttempts = 0
        var failStarts = false
        var failClears = false
        var failClearsOnce = false
        var failTitles = false
        var failReads = false
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
                if (failReads) error("disk gone")
                store
            },
            write = { value ->
                if (value == null) {
                    clearAttempts++
                    if (failClears) error("disk full")
                    if (failClearsOnce) {
                        failClearsOnce = false
                        error("disk full")
                    }
                } else if (failStarts) {
                    error("disk full")
                }
                store = value
                written += value
            },
            title = { songId ->
                if (failTitles) error("disk gone")
                titles[songId]
            },
            apply = { change ->
                session.update(change)
                messages += session.value.message
            },
            timer = PracticeTimer(clock) { zone },
        )

        init {
            val unconfined = UnconfinedTestDispatcher(scope.testScheduler)
            scope.backgroundScope.launch(unconfined) { holder.running.collect { running += it } }
            scope.backgroundScope.launch(unconfined) { holder.question.collect { questions += it } }
            scope.backgroundScope.launch(unconfined) { holder.cancelled.collect { cancelled += it?.song } }
        }
    }
}
