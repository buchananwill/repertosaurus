package dev.repertosaurus.android

import dev.repertosaurus.session.SessionState
import dev.repertosaurus.session.SuggestTuning
import dev.repertosaurus.session.SuggestionDeck
import dev.repertosaurus.session.suggestionPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlin.random.Random

/**
 * suggest SG2-SG7: the open suggestion sheet's deck (null while closed), drawn from [session]'s pool.
 * **It writes nothing** (SG3). "Log it" is [take], then the session's own `log`, the plain tap's path.
 *
 * The tuning is passed in on each draw and the last one is kept for the empty-pool redraw.
 */
public class SuggestionHolder(
    private val session: StateFlow<SessionState>,
    scope: CoroutineScope,
    private val random: Random = Random.Default,
) {
    private val _deck = MutableStateFlow<SuggestionDeck?>(null)
    public val deck: StateFlow<SuggestionDeck?> = _deck.asStateFlow()

    private var tuning = SuggestTuning.DEFAULT

    /** SG2, SG6: a fresh deck. Refused while the rows are loading (F26 B1): they are not the View's yet. */
    public fun open(tuning: SuggestTuning) {
        val state = session.value
        if (state.loading) return
        this.tuning = tuning
        _deck.value = SuggestionDeck.open(state.suggestionPool, tuning, random)
    }

    /** SG6: "Another", from the pool as it is now. */
    public fun another(tuning: SuggestTuning) {
        val deck = _deck.value ?: return
        this.tuning = tuning
        _deck.value = deck.next(session.value.suggestionPool, tuning, random)
    }

    /** SG3: the deck is discarded and nothing is written. */
    public fun close() {
        _deck.value = null
    }

    /**
     * SG2's "Log it": true, and the sheet closed, when [songId] was dealt from the open deck; the caller
     * then logs it. False, and nothing changes, for any other song.
     */
    public fun take(songId: String): Boolean {
        if (_deck.value?.shown?.contains(songId) != true) return false
        _deck.value = null
        return true
    }

    init {
        // F26 B1: an empty-pool sheet deals as soon as there is something to deal.
        session
            .map { !it.loading && it.suggestionPool.isNotEmpty() }
            .distinctUntilChanged()
            .filter { it && _deck.value == SuggestionDeck.EmptyPool }
            .onEach { _deck.value = SuggestionDeck.open(session.value.suggestionPool, tuning, random) }
            .launchIn(scope)
    }
}
