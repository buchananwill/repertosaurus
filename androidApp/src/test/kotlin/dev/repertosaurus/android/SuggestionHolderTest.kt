package dev.repertosaurus.android

import dev.repertosaurus.session.SessionRow
import dev.repertosaurus.session.SessionState
import dev.repertosaurus.session.SuggestTuning
import dev.repertosaurus.session.SuggestionDeck
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** suggest SG2-SG6 on [SuggestionHolder] (journal F26 B1, N7; F27 B1), with no device. */
@OptIn(ExperimentalCoroutinesApi::class)
class SuggestionHolderTest {

    private val rows = listOf(
        SessionRow("a", "Autumn Leaves", "Kosma", 12L, 3L),
        SessionRow("b", "Blue Bossa", "Dorham", null, 0L),
    )

    /** F26 B1: while the rows load they are not the View's, and nothing is dealt from them. */
    @Test
    fun openingWhileTheRowsLoadDealsNothing() = runTest {
        val session = MutableStateFlow(SessionState(rows = rows, loading = true))
        val holder = SuggestionHolder(session, backgroundScope, Random(1))
        holder.open(SuggestTuning.DEFAULT)
        assertNull(holder.deck.value)
    }

    /** F26 B1: an empty-pool sheet deals as soon as there are rows. */
    @Test
    fun anEmptyPoolSheetDealsWhenRowsArrive() = runTest {
        val session = MutableStateFlow(SessionState(loading = false))
        val holder = SuggestionHolder(session, backgroundScope, Random(1))
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
        val holder = SuggestionHolder(session, backgroundScope, Random(1))
        runCurrent()
        session.update { it.withRows(rows) }
        runCurrent()
        assertNull(holder.deck.value)
    }

    /** F26 N7, SG2: "Log it" takes only a dealt song, once, and closes the sheet. */
    @Test
    fun takeOnlyTakesADealtSongOnce() = runTest {
        val session = MutableStateFlow(SessionState(rows = rows, loading = false))
        val holder = SuggestionHolder(session, backgroundScope, Random(1))
        assertFalse(holder.take("a"), "no sheet open")

        holder.open(SuggestTuning.DEFAULT)
        val dealt = (holder.deck.value as SuggestionDeck.Showing).current.songId
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
        val holder = SuggestionHolder(session, backgroundScope, Random(1))
        holder.another(SuggestTuning.DEFAULT)
        assertNull(holder.deck.value)

        holder.open(SuggestTuning.DEFAULT)
        val first = (holder.deck.value as SuggestionDeck.Showing).current.songId
        holder.another(SuggestTuning.DEFAULT)
        val second = (holder.deck.value as SuggestionDeck.Showing).current.songId
        assertTrue(first != second)
        holder.close()
        assertNull(holder.deck.value)
    }
}
