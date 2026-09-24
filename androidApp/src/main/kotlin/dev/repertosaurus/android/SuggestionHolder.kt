package dev.repertosaurus.android

import dev.repertosaurus.data.Part
import dev.repertosaurus.session.ResolvedPart
import dev.repertosaurus.session.SessionState
import dev.repertosaurus.session.SuggestTuning
import dev.repertosaurus.session.SuggestionDeck
import dev.repertosaurus.session.part
import dev.repertosaurus.session.resolvedPart
import dev.repertosaurus.session.suggestionPool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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

/** schema-3 M13, M14 for Suggest: the one skip write and the one bulk count read, each off the main thread. */
public interface SkipLedger {
    public suspend fun record(part: Part)

    public suspend fun counts(parts: Collection<Part>): Map<Part, Long>
}

/**
 * suggest SG2-SG7, SG12-SG14: the open suggestion sheet's deck (null while closed), drawn from [session]'s
 * pool. "Log it" is [take], then the session's own `log`, the plain tap's path.
 *
 * **SG12's skip window lives here.** With counting on and a part resolved (SG14), "Another" stages a skip
 * for the card it replaces, and [staged] holds it for [SKIP_WINDOW_MS]. **It is written only when the
 * window closes**, or when "Another" or "Log it" on a different card cuts the window short. Undo, closing
 * the sheet, the part's song being logged, and the scope ending (the process dying) all drop it unwritten.
 * The window is a `delay` in [scope], so a test's scheduler owns the five seconds.
 *
 * The tuning is passed in on each call and the last one is kept for the empty-pool redraw and the window.
 */
public class SuggestionHolder(
    private val session: StateFlow<SessionState>,
    private val scope: CoroutineScope,
    private val ledger: SkipLedger,
    /** S11: a skip that could not be written, or counts that could not be read, is said. */
    private val onFailure: (Throwable) -> Unit,
    private val random: Random = Random.Default,
) {
    private val _deck = MutableStateFlow<SuggestionDeck?>(null)
    public val deck: StateFlow<SuggestionDeck?> = _deck.asStateFlow()

    /** SG12: the skip in its undo window, or null. */
    private val _staged = MutableStateFlow<Part?>(null)
    public val staged: StateFlow<Part?> = _staged.asStateFlow()

    private var tuning = SuggestTuning.DEFAULT

    /** What Undo restores: the deck as it was before "Another". */
    private var beforeSkip: SuggestionDeck? = null
    private var window: Job? = null

    /** M14's counts for this sheet, by song, for [Counts.part]; read once per opening (SG13). */
    private class Counts(val part: ResolvedPart, val bySong: Map<String, Int>)

    private var counts: Counts? = null
    private var reading: Job? = null

    /** SG2, SG6: a fresh deck. Refused while the rows are loading (F26 B1): they are not the View's yet. */
    public fun open(tuning: SuggestTuning) {
        if (session.value.loading) return
        this.tuning = tuning
        forget()
        withCounts { bySong -> _deck.value = SuggestionDeck.open(pool(bySong), weighing(), random) }
    }

    /** SG6: "Another", from the pool as it is now. SG12: it closes any open window and stages the next. */
    public fun another(tuning: SuggestTuning) {
        val deck = _deck.value ?: return
        this.tuning = tuning
        commit()
        val shown = (deck as? SuggestionDeck.Showing)?.current
        val part = session.value.resolvedPart
        if (tuning.countSkips && part != null && shown != null) {
            stage(Part(shown.songId, part.performerId, part.instrumentId), before = deck)
        }
        withCounts { bySong ->
            if (_deck.value === deck) _deck.value = deck.next(pool(bySong), weighing(), random)
        }
    }

    /** SG12: the card before "Another" is back, and nothing is written. */
    public fun undo() {
        val before = beforeSkip ?: return
        drop()
        _deck.value = before
    }

    /**
     * The tuning changed under "Tune". SG12: **counting turned off drops a staged skip**; turned on, the
     * card on show gains its count (SG13).
     */
    public fun retune(tuning: SuggestTuning) {
        this.tuning = tuning
        if (!tuning.countSkips) drop()
        val shown = _deck.value as? SuggestionDeck.Showing ?: return
        withCounts { bySong ->
            val now = _deck.value
            if (now is SuggestionDeck.Showing && now.current.songId == shown.current.songId) {
                _deck.value = now.copy(current = now.current.copy(skips = bySong[now.current.songId] ?: 0))
            }
        }
    }

    /** SG3: the deck is discarded and nothing is written. **A staged skip is dropped** (SG12). */
    public fun close() {
        drop()
        forget()
        _deck.value = null
    }

    /**
     * SG2's "Log it": true, and the sheet closed, when [songId] was dealt from the open deck; the caller
     * then logs it. False, and nothing changes, for any other song. SG12: a skip staged for a different
     * song is written; one for this song is dropped, since it is being played.
     */
    public fun take(songId: String): Boolean {
        if (_deck.value?.shown?.contains(songId) != true) return false
        if (_staged.value?.songId == songId) drop() else commit()
        forget()
        _deck.value = null
        return true
    }

    private fun pool(bySong: Map<String, Int>) = session.value.suggestionPool(bySong)

    /** SG11: what the draw weighs, locks applied for the View's part. */
    private fun weighing(): SuggestTuning = tuning.effective(session.value.part)

    private fun stage(part: Part, before: SuggestionDeck) {
        beforeSkip = before
        _staged.value = part
        window = scope.launch {
            delay(SKIP_WINDOW_MS)
            window = null
            commit()
        }
    }

    /** SG12: the window closes and the staged skip is written, if counting is still on. */
    private fun commit() {
        val part = _staged.value ?: return
        drop()
        if (!tuning.countSkips) return
        scope.launch {
            try {
                ledger.record(part)
                counts?.takeIf { it.part.performerId == part.performerId && it.part.instrumentId == part.instrumentId }?.let {
                    counts = Counts(it.part, it.bySong + (part.songId to (it.bySong[part.songId] ?: 0) + 1))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                onFailure(failure)
            }
        }
    }

    /** SG12: the staged skip, gone unwritten. */
    private fun drop() {
        window?.cancel()
        window = null
        beforeSkip = null
        _staged.value = null
    }

    /** This sheet's counts, and any read of them, discarded: the next opening reads again. */
    private fun forget() {
        reading?.cancel()
        reading = null
        counts = null
    }

    /**
     * [then] with M14's counts for the resolved part (SG13, SG14): at once when skips are not counted or
     * no part resolves (none), or when this sheet has read them; otherwise after **one bulk read** of the
     * View's rows. A failed read is said and weighs as none.
     */
    private fun withCounts(then: (Map<String, Int>) -> Unit) {
        val part = session.value.resolvedPart
        if (!tuning.countSkips || part == null) return then(emptyMap())
        counts?.takeIf { it.part == part }?.let { return then(it.bySong) }
        reading?.cancel()
        reading = scope.launch {
            val parts = session.value.rows.map { Part(it.songId, part.performerId, part.instrumentId) }
            val bySong = try {
                ledger.counts(parts).entries.associate { (p, n) -> p.songId to n.toInt() }.also { counts = Counts(part, it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                onFailure(failure)
                emptyMap()
            }
            reading = null
            then(bySong)
        }
    }

    init {
        // F26 B1: an empty-pool sheet deals as soon as there is something to deal.
        session
            .map { !it.loading && it.suggestionPool.isNotEmpty() }
            .distinctUntilChanged()
            .filter { it && _deck.value == SuggestionDeck.EmptyPool }
            .onEach {
                withCounts { bySong ->
                    if (_deck.value == SuggestionDeck.EmptyPool) _deck.value = SuggestionDeck.open(pool(bySong), weighing(), random)
                }
            }
            .launchIn(scope)
    }

    public companion object {
        /** SG12: how long "Skipped · Undo" stays. */
        public const val SKIP_WINDOW_MS: Long = 5_000L
    }
}
