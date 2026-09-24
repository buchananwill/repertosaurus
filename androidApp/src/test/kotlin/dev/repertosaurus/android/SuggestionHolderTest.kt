package dev.repertosaurus.android

import dev.repertosaurus.data.Part
import dev.repertosaurus.data.RepertosaurusRepository.Performer
import dev.repertosaurus.session.SessionOrder
import dev.repertosaurus.session.SessionRow
import dev.repertosaurus.session.SessionState
import dev.repertosaurus.session.SessionView
import dev.repertosaurus.session.SuggestTuning
import dev.repertosaurus.session.SuggestionDeck
import dev.repertosaurus.session.ViewFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** suggest SG2-SG6 and SG12-SG14 on [SuggestionHolder] (journal F26 B1, N7; F27 B1), with no device. */
@OptIn(ExperimentalCoroutinesApi::class)
class SuggestionHolderTest {

    private val rows = listOf(
        SessionRow("a", "Autumn Leaves", "Kosma", 12L, 3L),
        SessionRow("b", "Blue Bossa", "Dorham", null, 0L),
    )

    // ---- SG2-SG6 ---------------------------------------------------------------------------------

    /** F26 B1: while the rows load they are not the View's, and nothing is dealt from them. */
    @Test
    fun openingWhileTheRowsLoadDealsNothing() = runTest {
        val session = MutableStateFlow(SessionState(rows = rows, loading = true))
        val holder = holder(session, Ledger(this))
        holder.open(SuggestTuning.DEFAULT)
        assertNull(holder.deck.value)
    }

    /** F26 B1: an empty-pool sheet deals as soon as there are rows. */
    @Test
    fun anEmptyPoolSheetDealsWhenRowsArrive() = runTest {
        val session = MutableStateFlow(SessionState(loading = false))
        val holder = holder(session, Ledger(this))
        runCurrent()
        holder.open(SuggestTuning.DEFAULT)
        assertEquals(SuggestionDeck.EmptyPool, holder.deck.value)

        session.update { it.copy(loading = true) }
        runCurrent()
        assertEquals(SuggestionDeck.EmptyPool, holder.deck.value, "not from rows still loading")

        session.update { it.withRows(rows) }
        runCurrent()
        assertIs<SuggestionDeck.Showing>(holder.deck.value)
    }

    /** A closed sheet stays closed when rows arrive: the redraw is only for an open, empty one. */
    @Test
    fun aClosedSheetIsNotOpenedByRowsArriving() = runTest {
        val session = MutableStateFlow(SessionState(loading = false))
        val holder = holder(session, Ledger(this))
        runCurrent()
        session.update { it.withRows(rows) }
        runCurrent()
        assertNull(holder.deck.value)
    }

    /** F26 N7, SG2: "Log it" takes only a dealt song, once, and closes the sheet. */
    @Test
    fun takeOnlyTakesADealtSongOnce() = runTest {
        val session = MutableStateFlow(SessionState(rows = rows, loading = false))
        val holder = holder(session, Ledger(this))
        assertFalse(holder.take("a"), "no sheet open")

        holder.open(SuggestTuning.DEFAULT)
        val dealt = holder.current()
        val other = rows.first { it.songId != dealt }.songId
        assertFalse(holder.take(other), "a song not dealt")
        assertIs<SuggestionDeck.Showing>(holder.deck.value)

        assertTrue(holder.take(dealt))
        assertNull(holder.deck.value, "the sheet closed")
        assertFalse(holder.take(dealt), "not twice")
    }

    /** SG3, SG6: "Another" deals the other song; close discards the deck; "Another" on a closed sheet is nothing. */
    @Test
    fun anotherAndClose() = runTest {
        val session = MutableStateFlow(SessionState(rows = rows, loading = false))
        val holder = holder(session, Ledger(this))
        holder.another(SuggestTuning.DEFAULT)
        assertNull(holder.deck.value)

        holder.open(SuggestTuning.DEFAULT)
        val first = holder.current()
        holder.another(SuggestTuning.DEFAULT)
        assertNotEquals(first, holder.current())
        holder.close()
        assertNull(holder.deck.value)
    }

    // ---- SG12: the skip window. Every test asserts the whole emitted sequence. -----------------------

    private val counting = SuggestTuning.DEFAULT.copy(countSkips = true)
    private val songs = listOf(
        SessionRow("a", "Autumn Leaves", "Kosma", 12L, 3L),
        SessionRow("b", "Blue Bossa", "Dorham", null, 0L),
        SessionRow("c", "Cherokee", "Noble", 3L, 9L),
        SessionRow("d", "Dolphin Dance", "Hancock", 30L, 2L),
    )
    private val view = SessionView("v", "Mine", ViewFilter.NONE, "vocal", SessionOrder.COLDEST_FIRST, position = 0L)

    /** Will is the owner, so triage T9 resolves `(will, vocal)` (SG14). */
    private fun willsSession(owner: String? = "will") = MutableStateFlow(
        SessionState(view = view, loading = false, ownerPerformerId = owner, performers = listOf(Performer("will", "Will")))
            .withRows(songs),
    )

    private fun skip(songId: String) = Part(songId, "will", "vocal")

    /** SG12: **Undo writes nothing**, ever, and restores the card before "Another". */
    @Test
    fun undoWritesNothing() = runTest {
        val ledger = Ledger(this)
        val holder = holder(willsSession(), ledger)
        val seen = record(holder)
        holder.open(counting)
        runCurrent()
        val first = holder.current()

        holder.another(counting)
        val second = holder.current()
        advanceTimeBy(4_000)
        holder.undo()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(listOf(null, skip(first), null), seen.staged)
        assertEquals(listOf(null, first, second, first), seen.cards)
        assertEquals(emptyList(), ledger.written, "nothing written")
    }

    /** SG12: the skip is written **when the window closes, at 5 s, once**, and not before. */
    @Test
    fun theWindowClosingWritesExactlyOneRow() = runTest {
        val ledger = Ledger(this)
        val holder = holder(willsSession(), ledger)
        val seen = record(holder)
        holder.open(counting)
        runCurrent()
        val first = holder.current()

        holder.another(counting)
        val second = holder.current()
        advanceTimeBy(SuggestionHolder.SKIP_WINDOW_MS - 1)
        runCurrent()
        assertEquals(emptyList(), ledger.written, "still inside the window")
        assertEquals(skip(first), holder.staged.value)

        advanceTimeBy(1)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(listOf(SuggestionHolder.SKIP_WINDOW_MS to skip(first)), ledger.written)
        assertEquals(listOf(null, skip(first), null), seen.staged)
        assertEquals(listOf(null, first, second), seen.cards, "Another's card stays")
    }

    /** SG3, SG12: **dismissing inside the window drops the skip**: nothing written. */
    @Test
    fun dismissingInsideTheWindowWritesNothing() = runTest {
        val ledger = Ledger(this)
        val holder = holder(willsSession(), ledger)
        val seen = record(holder)
        holder.open(counting)
        runCurrent()
        val first = holder.current()

        holder.another(counting)
        val second = holder.current()
        advanceTimeBy(2_000)
        holder.close()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(emptyList(), ledger.written)
        assertEquals(listOf(null, skip(first), null), seen.staged)
        assertEquals(listOf(null, first, second, null), seen.cards)
    }

    /** SG12: "Another" inside the window **writes the earlier skip** at once and stages the next. */
    @Test
    fun anotherInsideTheWindowWritesTheEarlierSkipAndStagesTheNext() = runTest {
        val ledger = Ledger(this)
        val holder = holder(willsSession(), ledger)
        val seen = record(holder)
        holder.open(counting)
        runCurrent()
        val first = holder.current()

        holder.another(counting)
        val second = holder.current()
        advanceTimeBy(1_000)
        holder.another(counting)
        val third = holder.current()
        runCurrent()
        assertEquals(listOf(1_000L to skip(first)), ledger.written, "the earlier skip, at the second Another")

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf(1_000L to skip(first), 6_000L to skip(second)), ledger.written, "the second at its own window's close")
        assertEquals(listOf(null, skip(first), null, skip(second), null), seen.staged)
        assertEquals(listOf(null, first, second, third), seen.cards)
    }

    /** SG12: "Log it" on a **different** card writes the staged skip; the sheet closes. */
    @Test
    fun logItOnADifferentCardWritesTheStagedSkip() = runTest {
        val ledger = Ledger(this)
        val holder = holder(willsSession(), ledger)
        val seen = record(holder)
        holder.open(counting)
        runCurrent()
        val first = holder.current()

        holder.another(counting)
        val second = holder.current()
        advanceTimeBy(1_500)
        assertTrue(holder.take(second))
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(listOf(1_500L to skip(first)), ledger.written)
        assertEquals(listOf(null, skip(first), null), seen.staged)
        assertEquals(listOf(null, first, second, null), seen.cards)
    }

    /** SG12: "Log it" on the staged skip's own song drops the skip: it is being played. */
    @Test
    fun logItOnTheSkippedSongDropsTheSkip() = runTest {
        val ledger = Ledger(this)
        val holder = holder(willsSession(), ledger)
        holder.open(counting)
        runCurrent()
        val first = holder.current()
        holder.another(counting)
        assertTrue(holder.take(first), "a dealt song")
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(emptyList(), ledger.written)
    }

    /** SG12: **with counting off, "Another" writes nothing and reads nothing.** */
    @Test
    fun countingOffWritesNothing() = runTest {
        val ledger = Ledger(this)
        val holder = holder(willsSession(), ledger)
        val seen = record(holder)
        holder.open(SuggestTuning.DEFAULT)
        repeat(3) {
            holder.another(SuggestTuning.DEFAULT)
            advanceTimeBy(1_000)
        }
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(emptyList(), ledger.written)
        assertEquals(listOf<Part?>(null), seen.staged, "nothing ever staged")
        assertEquals(0, ledger.reads.size)
        assertEquals(5, seen.cards.size, "null, then the four cards")
    }

    /** SG12: counting turned off under "Tune" inside the window drops the staged skip. */
    @Test
    fun countingTurnedOffInsideTheWindowDropsTheSkip() = runTest {
        val ledger = Ledger(this)
        val holder = holder(willsSession(), ledger)
        val seen = record(holder)
        holder.open(counting)
        runCurrent()
        val first = holder.current()
        holder.another(counting)
        advanceTimeBy(1_000)
        holder.retune(SuggestTuning.DEFAULT)
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(emptyList(), ledger.written)
        assertEquals(listOf(null, skip(first), null), seen.staged)
    }

    /** SG14: **with no performer resolving, nothing is staged, written or read**, counting on or not. */
    @Test
    fun noPartWritesNothing() = runTest {
        val ledger = Ledger(this)
        val holder = holder(willsSession(owner = null), ledger)
        val seen = record(holder)
        holder.open(counting)
        runCurrent()
        repeat(3) {
            holder.another(counting)
            advanceTimeBy(1_000)
        }
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(emptyList(), ledger.written)
        assertEquals(listOf<Part?>(null), seen.staged)
        assertEquals(0, ledger.reads.size)
    }

    /**
     * SG13, schema-3 M14: opening reads the part's counts **once, in bulk, for every row**, and the dealt
     * candidates carry them. A written skip counts on a later deal of the same song.
     */
    @Test
    fun theCountsAreOneBulkReadAndCarried() = runTest {
        val ledger = Ledger(this, counts = mapOf("a" to 4L, "b" to 0L, "c" to 12L, "d" to 1L))
        val holder = holder(willsSession(), ledger)
        holder.open(counting)
        runCurrent()
        assertEquals(listOf(songs.map { skip(it.songId) }), ledger.reads, "one read, of every row's part")

        val dealt = mutableListOf<Pair<String, Int>>()
        repeat(songs.size) {
            val card = holder.deck.value as SuggestionDeck.Showing
            dealt += card.current.songId to card.current.skips
            holder.another(counting)
        }
        runCurrent()
        assertEquals(mapOf("a" to 4, "b" to 0, "c" to 12, "d" to 1), dealt.toMap())
        assertEquals(1, ledger.reads.size, "Another reads nothing more")
        assertEquals(3, ledger.written.size, "three skips written by the next Another; the fourth still staged")
    }

    /** S11: a skip that cannot be written is said, not swallowed. */
    @Test
    fun aFailedWriteIsSaid() = runTest {
        val failures = mutableListOf<Throwable>()
        val ledger = Ledger(this, failWrites = true)
        val holder = SuggestionHolder(willsSession(), backgroundScope, ledger, onFailure = { failures += it }, random = Random(1))
        holder.open(counting)
        runCurrent()
        holder.another(counting)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf("disk full"), failures.map { it.message })
    }

    // ---- Harness -------------------------------------------------------------------------------------

    private fun TestScope.holder(session: MutableStateFlow<SessionState>, ledger: SkipLedger) =
        SuggestionHolder(session, backgroundScope, ledger, onFailure = { throw AssertionError("unexpected failure", it) }, random = Random(1))

    private fun SuggestionHolder.current(): String = (deck.value as SuggestionDeck.Showing).current.songId

    /** Every value [SuggestionHolder.staged] and the card on show take, in order, collected unconfined. */
    private class Seen {
        val staged = mutableListOf<Part?>()
        val cards = mutableListOf<String?>()
    }

    private fun TestScope.record(holder: SuggestionHolder): Seen {
        val seen = Seen()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { holder.staged.toList(seen.staged) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            holder.deck.collect { seen.cards += (it as? SuggestionDeck.Showing)?.current?.songId }
        }
        return seen
    }

    /** The ledger, recording each write with the virtual time it landed at. */
    private class Ledger(
        private val scope: TestScope,
        private val counts: Map<String, Long> = emptyMap(),
        private val failWrites: Boolean = false,
    ) : SkipLedger {
        val written = mutableListOf<Pair<Long, Part>>()
        val reads = mutableListOf<List<Part>>()

        override suspend fun record(part: Part) {
            if (failWrites) error("disk full")
            written += scope.testScheduler.currentTime to part
        }

        override suspend fun counts(parts: Collection<Part>): Map<Part, Long> {
            reads += parts.toList()
            return parts.associateWith { counts[it.songId] ?: 0L }
        }
    }
}
