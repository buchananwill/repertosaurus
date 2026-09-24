package dev.repertosaurus.android

import dev.repertosaurus.data.Part
import dev.repertosaurus.data.RepertosaurusRepository.Performer
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.SessionOrder
import dev.repertosaurus.session.SessionRow
import dev.repertosaurus.session.SessionState
import dev.repertosaurus.session.SessionView
import dev.repertosaurus.session.SuggestTuning
import dev.repertosaurus.session.SuggestionDeck
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
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** suggest SG2-SG6 and SG12-SG14 on [SuggestionHolder], with no device. */
@OptIn(ExperimentalCoroutinesApi::class)
class SuggestionHolderTest {

    /** The one fixture list. */
    private val songs = listOf(
        SessionRow("a", "Autumn Leaves", "Kosma", 12L, 3L),
        SessionRow("b", "Blue Bossa", "Dorham", null, 0L),
        SessionRow("c", "Cherokee", "Noble", 3L, 9L),
        SessionRow("d", "Dolphin Dance", "Hancock", 30L, 2L),
    )
    private val view = SessionView("v", "Mine", ViewFilter.NONE, "vocal", SessionOrder.COLDEST_FIRST, position = 0L)
    private val counting = SuggestTuning.DEFAULT.copy(countSkips = true)

    /** The View's rows, with Will and Coralie read and [owner] this device's (triage T9, SG14). */
    private fun session(owner: String? = "will", rows: List<SessionRow> = songs, loading: Boolean = false) = MutableStateFlow(
        SessionState(
            view = view,
            ownerPerformerId = owner,
            performers = listOf(Performer("will", "Will"), Performer("coralie", "Coralie")),
        ).withRows(rows).copy(loading = loading),
    )

    private fun skip(songId: String, performer: String = "will") = Part(songId, performer, "vocal")

    // ---- SG2-SG6 ---------------------------------------------------------------------------------

    /** While the rows load they are not the View's, and nothing is dealt from them. */
    @Test
    fun openingWhileTheRowsLoadDealsNothing() = runTest {
        val holder = Rig(this, session(loading = true)).holder
        holder.open()
        assertNull(holder.deck.value)
    }

    /** An empty-pool sheet deals as soon as there are rows. */
    @Test
    fun anEmptyPoolSheetDealsWhenRowsArrive() = runTest {
        val session = session(rows = emptyList())
        val holder = Rig(this, session).holder
        runCurrent()
        holder.open()
        assertEquals(SuggestionDeck.EmptyPool, holder.deck.value)

        session.update { it.copy(loading = true) }
        runCurrent()
        assertEquals(SuggestionDeck.EmptyPool, holder.deck.value, "not from rows still loading")

        session.update { it.withRows(songs) }
        runCurrent()
        assertIs<SuggestionDeck.Showing>(holder.deck.value)
    }

    /** A closed sheet stays closed when rows arrive: the redraw is only for an open, empty one. */
    @Test
    fun aClosedSheetIsNotOpenedByRowsArriving() = runTest {
        val session = session(rows = emptyList())
        val holder = Rig(this, session).holder
        runCurrent()
        session.update { it.withRows(songs) }
        runCurrent()
        assertNull(holder.deck.value)
    }

    /** SG2: "Log it" takes only a dealt song, once, and closes the sheet. */
    @Test
    fun takeOnlyTakesADealtSongOnce() = runTest {
        val holder = Rig(this, session()).holder
        assertFalse(holder.take("a"), "no sheet open")

        holder.open()
        val dealt = holder.current()
        val other = songs.first { it.songId != dealt }.songId
        assertFalse(holder.take(other), "a song not dealt")
        assertIs<SuggestionDeck.Showing>(holder.deck.value)

        assertTrue(holder.take(dealt))
        assertNull(holder.deck.value, "the sheet closed")
        assertFalse(holder.take(dealt), "not twice")
    }

    /** SG3, SG6: "Another" deals another song; close discards the deck; "Another" on a closed sheet is nothing. */
    @Test
    fun anotherAndClose() = runTest {
        val holder = Rig(this, session()).holder
        holder.another()
        assertNull(holder.deck.value)

        holder.open()
        val first = holder.current()
        holder.another()
        assertNotEquals(first, holder.current())
        holder.close()
        assertNull(holder.deck.value)
    }

    // ---- SG12: the skip window. Every test asserts the whole emitted sequence. -----------------------

    /** SG12: **Undo writes nothing**, ever, and restores the card before "Another". */
    @Test
    fun undoWritesNothing() = runTest {
        val rig = Rig(this, session(), counting)
        val holder = rig.holder
        holder.open()
        runCurrent()
        val first = holder.current()

        holder.another()
        val second = holder.current()
        advanceTimeBy(4_000)
        holder.undo()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(listOf(null, skip(first), null), rig.staged)
        assertEquals(listOf(null, first, second, first), rig.cards)
        assertEquals(emptyList(), rig.written, "nothing written")
    }

    /** SG12: the skip is written **when the window closes, at 5 s, once**, and not before. */
    @Test
    fun theWindowClosingWritesExactlyOneRow() = runTest {
        val rig = Rig(this, session(), counting)
        val holder = rig.holder
        holder.open()
        runCurrent()
        val first = holder.current()

        holder.another()
        val second = holder.current()
        advanceTimeBy(SuggestionHolder.SKIP_WINDOW_MS - 1)
        runCurrent()
        assertEquals(emptyList(), rig.written, "still inside the window")
        assertEquals(skip(first), holder.staged.value?.part)

        advanceTimeBy(1)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(listOf(SuggestionHolder.SKIP_WINDOW_MS to skip(first)), rig.written)
        assertEquals(listOf(null, skip(first), null), rig.staged)
        assertEquals(listOf(null, first, second), rig.cards, "Another's card stays")
    }

    /** SG3, SG12: **dismissing inside the window drops the skip**: nothing written. */
    @Test
    fun dismissingInsideTheWindowWritesNothing() = runTest {
        val rig = Rig(this, session(), counting)
        val holder = rig.holder
        holder.open()
        runCurrent()
        val first = holder.current()

        holder.another()
        val second = holder.current()
        advanceTimeBy(2_000)
        holder.close()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(emptyList(), rig.written)
        assertEquals(listOf(null, skip(first), null), rig.staged)
        assertEquals(listOf(null, first, second, null), rig.cards)
    }

    /** SG12: "Another" inside the window **writes the earlier skip** at once and stages the next in its place. */
    @Test
    fun anotherInsideTheWindowWritesTheEarlierSkipAndStagesTheNext() = runTest {
        val rig = Rig(this, session(), counting)
        val holder = rig.holder
        holder.open()
        runCurrent()
        val first = holder.current()

        holder.another()
        val second = holder.current()
        advanceTimeBy(1_000)
        holder.another()
        val third = holder.current()
        runCurrent()
        assertEquals(listOf(1_000L to skip(first)), rig.written, "the earlier skip, at the second Another")

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf(1_000L to skip(first), 6_000L to skip(second)), rig.written, "the second at its own window's close")
        assertEquals(listOf(null, skip(first), skip(second), null), rig.staged)
        assertEquals(listOf(null, first, second, third), rig.cards)
    }

    /** SG12: "Log it" on a **different** card writes the staged skip; the sheet closes. */
    @Test
    fun logItOnADifferentCardWritesTheStagedSkip() = runTest {
        val rig = Rig(this, session(), counting)
        val holder = rig.holder
        holder.open()
        runCurrent()
        val first = holder.current()

        holder.another()
        val second = holder.current()
        advanceTimeBy(1_500)
        assertTrue(holder.take(second))
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(listOf(1_500L to skip(first)), rig.written)
        assertEquals(listOf(null, skip(first), null), rig.staged)
        assertEquals(listOf(null, first, second, null), rig.cards)
    }

    /** SG12: "Log it" on the staged skip's own song drops the skip: it is being played. */
    @Test
    fun logItOnTheSkippedSongDropsTheSkip() = runTest {
        val rig = Rig(this, session(), counting)
        val holder = rig.holder
        holder.open()
        runCurrent()
        val first = holder.current()
        holder.another()
        assertTrue(holder.take(first), "a dealt song")
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(emptyList(), rig.written)
    }

    /** SG12: **with counting off, "Another" writes nothing and reads nothing.** */
    @Test
    fun countingOffWritesNothing() = runTest {
        val rig = Rig(this, session())
        val holder = rig.holder
        holder.open()
        repeat(3) {
            holder.another()
            advanceTimeBy(1_000)
        }
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(emptyList(), rig.written)
        assertEquals(listOf<Part?>(null), rig.staged, "nothing ever staged")
        assertEquals(0, rig.reads.size)
        assertEquals(5, rig.cards.size, "null, then the four cards")
    }

    /** SG12: counting turned off under "Tune" inside the window drops the staged skip. */
    @Test
    fun countingTurnedOffInsideTheWindowDropsTheSkip() = runTest {
        val rig = Rig(this, session(), counting)
        val holder = rig.holder
        holder.open()
        runCurrent()
        val first = holder.current()
        holder.another()
        advanceTimeBy(1_000)
        holder.retune(SuggestTuning.DEFAULT)
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(emptyList(), rig.written)
        assertEquals(listOf(null, skip(first), null), rig.staged)
    }

    /** SG14: **with no performer resolving, nothing is staged, written or read**, counting on or not. */
    @Test
    fun noPartWritesNothing() = runTest {
        val rig = Rig(this, session(owner = null), counting)
        val holder = rig.holder
        holder.open()
        runCurrent()
        repeat(3) {
            holder.another()
            advanceTimeBy(1_000)
        }
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(emptyList(), rig.written)
        assertEquals(listOf<Part?>(null), rig.staged)
        assertEquals(0, rig.reads.size)
    }

    // ---- A counts read in flight: exactly once. The read waits on a gate. --------------------------

    /**
     * The part changes under an open sheet (Coralie becomes the owner), so "Another" must read her counts
     * first, and the read is held. A second "Another" meanwhile is ignored: **one skip, written once.**
     */
    @Test
    fun aSecondAnotherWhileTheReadIsPendingIsIgnored() = runTest {
        val session = session()
        val rig = Rig(this, session, counting)
        val holder = rig.holder
        holder.open()
        runCurrent()
        val first = holder.current()

        session.update { it.withOwnerPerformer("coralie") }
        rig.gate = CompletableDeferred()
        holder.another()
        holder.another()
        runCurrent()
        assertEquals(first, holder.current(), "the deck waits for the read")
        assertEquals(listOf<Part?>(null), rig.staged, "nothing staged before the deck moves")

        rig.gate!!.complete(Unit)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(listOf(SuggestionHolder.SKIP_WINDOW_MS to skip(first, "coralie")), rig.written, "exactly one")
        assertEquals(listOf(null, skip(first, "coralie"), null), rig.staged)
        assertEquals(3, rig.cards.size, "null, the first card, one more")
    }

    /** A retune while "Another"'s read is held drops that deal: the card stays, and nothing is written. */
    @Test
    fun aRetuneWhileTheReadIsPendingDropsTheDeal() = runTest {
        val session = session()
        val rig = Rig(this, session, counting)
        val holder = rig.holder
        holder.open()
        runCurrent()
        val first = holder.current()

        session.update { it.withOwnerPerformer("coralie") }
        rig.gate = CompletableDeferred()
        holder.another()
        holder.retune(counting.copy(showSkipCount = false))
        rig.gate!!.complete(Unit)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(first, holder.current(), "the card on screen is still the first")
        assertEquals(emptyList(), rig.written, "no skip for a card that never left")
        assertEquals(listOf<Part?>(null), rig.staged)
        assertEquals(2, rig.reads.size, "the opening's read and Coralie's, which the retune shared rather than restarted")
    }

    /** Undo while "Another"'s read is held brings the earlier card back, and the read's landing moves nothing. */
    @Test
    fun anUndoWhileTheReadIsPendingIsNotOverridden() = runTest {
        val session = session()
        val rig = Rig(this, session, counting)
        val holder = rig.holder
        holder.open()
        runCurrent()
        val first = holder.current()
        holder.another()
        val second = holder.current()

        session.update { it.withOwnerPerformer("coralie") }
        rig.gate = CompletableDeferred()
        holder.another()
        holder.undo()
        rig.gate!!.complete(Unit)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(first, holder.current(), "Undo's card stays")
        assertEquals(emptyList(), rig.written, "the undone skip is not written, and no new one staged")
        assertEquals(listOf(null, skip(first), null), rig.staged)
        assertEquals(listOf(null, first, second, first), rig.cards)
    }

    // ---- SG13: the counts ------------------------------------------------------------------------

    /** SG13, schema-3 M14: opening reads the part's counts **once, in bulk, for every row**, and the cards carry them. */
    @Test
    fun theCountsAreOneBulkReadAndCarried() = runTest {
        val rig = Rig(this, session(), counting, counts = mapOf("a" to 4L, "b" to 0L, "c" to 12L, "d" to 1L))
        val holder = rig.holder
        holder.open()
        runCurrent()
        assertEquals(listOf(songs.map { skip(it.songId) }), rig.reads, "one read, of every row's part")

        val dealt = mutableListOf<Pair<String, Long>>()
        repeat(songs.size) {
            val card = holder.deck.value as SuggestionDeck.Showing
            dealt += card.current.songId to card.current.skips
            holder.another()
        }
        runCurrent()
        assertEquals(mapOf("a" to 4L, "b" to 0L, "c" to 12L, "d" to 1L), dealt.toMap())
        assertEquals(1, rig.reads.size, "Another reads nothing more")
        assertEquals(3, rig.written.size, "three written by the next Another; the fourth still staged")
    }

    /** SG13: a written skip counts on a later deal of the same song: the read's value plus one. */
    @Test
    fun aWrittenSkipCountsOnTheNextDealOfItsSong() = runTest {
        val rig = Rig(this, session(), counting, counts = mapOf("a" to 4L, "b" to 4L, "c" to 4L, "d" to 4L))
        val holder = rig.holder
        holder.open()
        runCurrent()
        val first = holder.current()
        holder.another()
        advanceTimeBy(SuggestionHolder.SKIP_WINDOW_MS + 1)
        runCurrent()
        assertEquals(listOf(skip(first)), rig.written.map { it.second })

        // Deal on until the first returns; each skip staged on the way is dropped by turning counting off
        // and on again, so the first's is the only one written.
        repeat(songs.size * 2) {
            if (holder.showing() != first) holder.another()
            holder.retune(SuggestTuning.DEFAULT)
            holder.retune(counting)
        }
        runCurrent()
        assertEquals(first, holder.showing())
        assertEquals(1, rig.written.size)
        assertEquals(5L, (holder.deck.value as SuggestionDeck.Showing).current.skips, "4 read, 1 written since")
        assertEquals(1, rig.reads.size, "no read again")
    }

    /** SG13: a failed counts read is said **once**, counts as none, and stages one skip for one "Another". */
    @Test
    fun aFailedCountsReadIsSaidOnceAndCountsAsNone() = runTest {
        val session = session()
        val rig = Rig(this, session, counting, failCounts = true)
        val holder = rig.holder
        holder.open()
        runCurrent()
        val first = holder.current()
        assertEquals(0L, (holder.deck.value as SuggestionDeck.Showing).current.skips)

        holder.another()
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals<List<String?>>(listOf(Messages.suggestSkipsFailed(IllegalStateException("no counts"))), rig.messages, "said once")
        assertEquals(1, rig.reads.size, "not read again this opening")
        assertEquals(listOf(SuggestionHolder.SKIP_WINDOW_MS to skip(first)), rig.written, "one skip, once")
        assertEquals(listOf(null, skip(first), null), rig.staged)
    }

    /** SG13: counting turned on under "Tune" gives the card on show its count. */
    @Test
    fun countingTurnedOnGivesTheCardItsCount() = runTest {
        val rig = Rig(this, session(), counts = mapOf("a" to 7L, "b" to 7L, "c" to 7L, "d" to 7L))
        val holder = rig.holder
        holder.open()
        assertEquals(0L, (holder.deck.value as SuggestionDeck.Showing).current.skips)
        val first = holder.current()

        holder.retune(counting)
        runCurrent()
        assertEquals(first, holder.current(), "the same card")
        assertEquals(7L, (holder.deck.value as SuggestionDeck.Showing).current.skips)
    }

    /** S11: a skip that cannot be written is said, not swallowed. */
    @Test
    fun aFailedWriteIsSaid() = runTest {
        val rig = Rig(this, session(), counting, failWrites = true)
        val holder = rig.holder
        holder.open()
        runCurrent()
        holder.another()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals<List<String?>>(listOf(Messages.suggestSkipsFailed(IllegalStateException("disk full"))), rig.messages)
    }

    // ---- Harness -------------------------------------------------------------------------------------

    private fun SuggestionHolder.current(): String = (deck.value as SuggestionDeck.Showing).current.songId

    private fun SuggestionHolder.showing(): String? = (deck.value as? SuggestionDeck.Showing)?.current?.songId

    /**
     * A holder over [session], tuned to [tuning], with its ports recorded: each write with the virtual time
     * it landed at, each read, and each message. Every value [SuggestionHolder.staged] and the card on show
     * take is collected unconfined, in order. A read waits on [gate] while one is set.
     */
    private class Rig(
        private val scope: TestScope,
        private val session: MutableStateFlow<SessionState>,
        tuning: SuggestTuning = SuggestTuning.DEFAULT,
        private val counts: Map<String, Long> = emptyMap(),
        private val failWrites: Boolean = false,
        private val failCounts: Boolean = false,
    ) {
        val written = mutableListOf<Pair<Long, Part>>()
        val reads = mutableListOf<List<Part>>()
        val messages = mutableListOf<String?>()
        val staged = mutableListOf<Part?>()
        val cards = mutableListOf<String?>()
        var gate: CompletableDeferred<Unit>? = null

        val holder = SuggestionHolder(
            session = session,
            scope = scope.backgroundScope,
            record = { part ->
                if (failWrites) error("disk full")
                written += scope.testScheduler.currentTime to part
            },
            counts = { parts ->
                reads += parts.toList()
                gate?.await()
                if (failCounts) error("no counts")
                parts.associateWith { counts[it.songId] ?: 0L }
            },
            apply = { change ->
                session.update(change)
                messages += session.value.message
            },
            random = Random(1),
        )

        init {
            holder.retune(tuning)
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                holder.staged.collect { staged += it?.part }
            }
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                holder.deck.collect { cards += (it as? SuggestionDeck.Showing)?.current?.songId }
            }
        }
    }
}
