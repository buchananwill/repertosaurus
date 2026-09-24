package dev.repertosaurus.android

import dev.repertosaurus.data.Part
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.SessionState
import dev.repertosaurus.session.SuggestTuning
import dev.repertosaurus.session.SuggestionCard
import dev.repertosaurus.session.SuggestionDeck
import dev.repertosaurus.session.of
import dev.repertosaurus.session.part
import dev.repertosaurus.session.ratingsFresh
import dev.repertosaurus.session.suggestionCard
import dev.repertosaurus.session.suggestionPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * suggest SG2-SG7, SG12-SG14: the open suggestion sheet's deck (null while closed) and its skip window,
 * which writes a skip only when it closes.
 *
 * [record] and [counts] do their own IO. [apply] is the state holder's atomic update.
 */
public class SuggestionHolder(
    private val session: StateFlow<SessionState>,
    private val scope: CoroutineScope,
    private val record: suspend (Part) -> Unit,
    counts: suspend (Collection<Part>) -> Map<Part, Long>,
    private val apply: ((SessionState) -> SessionState) -> Unit,
    private val random: Random = Random.Default,
) {
    private val _deck = MutableStateFlow<SuggestionDeck?>(null)
    public val deck: StateFlow<SuggestionDeck?> = _deck.asStateFlow()

    /** SG12: a skip in its undo window: its part, the deck Undo restores, and the window. */
    public class StagedSkip internal constructor(public val part: Part, internal val before: SuggestionDeck, internal val window: Job)

    private val _staged = MutableStateFlow<StagedSkip?>(null)
    public val staged: StateFlow<StagedSkip?> = _staged.asStateFlow()

    private var tuning = SuggestTuning.DEFAULT

    private val skipCounts = SkipCounts(session, scope, counts, onFailure = ::fail)

    /** Moves on at every deck change, and at Undo, a retune and a close, so a late deal never lands. */
    private var ticket = 0L

    /** The ticket of a deal waiting on a counts read; "Another" waits for it. */
    private var pending: Long? = null

    /** SG12, SG13: the tuning under "Tune". Counting turned off drops a staged skip. */
    public fun retune(tuning: SuggestTuning) {
        this.tuning = tuning
        if (tuning.skipPart(session.value.part) == null) drop()
        val shown = _deck.value as? SuggestionDeck.Showing ?: return invalidate()
        deal { bySong ->
            _deck.value = shown.copy(current = shown.current.copy(skips = bySong[shown.current.songId] ?: 0L))
        }
    }

    /** SG2, SG6: a fresh deck. Refused while the rows are loading: they are not the View's yet. */
    public fun open() {
        if (session.value.loading) return
        close()
        deal { bySong -> _deck.value = SuggestionDeck.open(pool(bySong), weighing(), random) }
    }

    /** SG6, SG12: "Another". It closes any open window and stages a skip for the card it replaces. */
    public fun another() {
        val deck = _deck.value ?: return
        if (pending != null) return
        deal { bySong ->
            val skipped = skippable(deck)
            _deck.value = deck.next(pool(bySong), weighing(), random)
            restage(skipped?.let { stage(it, before = deck) })
        }
    }

    /** SG12: the card before "Another" is back, and nothing is written. */
    public fun undo() {
        val skip = _staged.value ?: return
        skip.window.cancel()
        _staged.value = null
        invalidate()
        _deck.value = skip.before
    }

    /** SG3: dismissing is never a skip. A staged skip is dropped. */
    public fun close() {
        drop()
        invalidate()
        skipCounts.forget()
        _deck.value = null
    }

    /**
     * SG2's "Log it": true, and the sheet closed, when [songId] was dealt from the open deck. SG12: a skip
     * staged for another song is written; one for this song is dropped, since it is being played.
     */
    public fun take(songId: String): Boolean {
        if (_deck.value?.shown?.contains(songId) != true) return false
        val skip = _staged.value
        if (skip?.part?.songId == songId) drop() else restage(null)
        invalidate()
        skipCounts.forget()
        _deck.value = null
        return true
    }

    private fun pool(bySong: Map<String, Long>) = session.value.suggestionPool(bySong)

    private fun weighing(): SuggestTuning = session.value.let { tuning.effective(it.part, it.ratingsFresh) }

    /** The part to skip for [deck]'s card: counting on, a part resolved, and the card on screen the dealt one. */
    private fun skippable(deck: SuggestionDeck): Part? {
        val state = session.value
        val card = state.suggestionCard(deck) as? SuggestionCard.Showing ?: return null
        return tuning.skipPart(state.part)?.of(card.row.songId)
    }

    /**
     * One deck change: [move] runs with the part's counts, now or when they land, and only if nothing has
     * moved the deck since. While it waits, [pending] holds its ticket.
     */
    private fun deal(move: (Map<String, Long>) -> Unit) {
        val mine = ++ticket
        pending = mine
        skipCounts.withCounts(tuning.skipPart(session.value.part)) { bySong ->
            if (ticket == mine) {
                pending = null
                move(bySong)
            }
        }
    }

    private fun invalidate() {
        ticket++
        pending = null
    }

    private fun stage(part: Part, before: SuggestionDeck): StagedSkip {
        lateinit var skip: StagedSkip
        val window = scope.launch(start = CoroutineStart.LAZY) {
            delay(SKIP_WINDOW_MS)
            if (_staged.value === skip) restage(null)
        }
        skip = StagedSkip(part, before, window)
        return skip
    }

    /** SG12: the staged skip's window closes and it is written; [next], if any, takes its place. */
    private fun restage(next: StagedSkip?) {
        val closing = _staged.value
        _staged.value = next
        next?.window?.start()
        closing?.let(::write)
    }

    /** SG12: the staged skip, gone unwritten. */
    private fun drop() {
        _staged.value?.window?.cancel()
        _staged.value = null
    }

    private fun write(skip: StagedSkip) {
        skip.window.cancel()
        val counted = skipCounts.recording(skip.part)
        scope.launch {
            catchingFailure { record(skip.part) }.fold(onSuccess = { counted() }, onFailure = ::fail)
        }
    }

    private fun fail(failure: Throwable) {
        apply { it.withMessage(Messages.suggestSkipsFailed(failure)) }
    }

    init {
        // An empty-pool sheet deals as soon as there is something to deal.
        session
            .map { !it.loading && it.suggestionPool().isNotEmpty() }
            .distinctUntilChanged()
            .filter { it && _deck.value == SuggestionDeck.EmptyPool }
            .onEach { deal { bySong -> _deck.value = SuggestionDeck.open(pool(bySong), weighing(), random) } }
            .launchIn(scope)
    }

    public companion object {
        /** SG12: how long "Skipped · Undo" stays. */
        public const val SKIP_WINDOW_MS: Long = 5_000L
    }
}
