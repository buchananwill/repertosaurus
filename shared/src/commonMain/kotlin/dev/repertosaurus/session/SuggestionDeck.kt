package dev.repertosaurus.session

import kotlin.random.Random

/**
 * suggest SG4, SG6: one open suggestion sheet. **A deck, not a die**: a song shown is not shown again
 * until every song in the pool has been. Closing the sheet discards it.
 */
public sealed interface SuggestionDeck {
    /** The songs dealt from this deck so far. */
    public val shown: Set<String>

    /** SG4: nothing to deal. */
    public data object EmptyPool : SuggestionDeck {
        override val shown: Set<String> get() = emptySet()
    }

    public data class Showing(val current: SuggestCandidate, override val shown: Set<String>) : SuggestionDeck

    /** SG4: every song in the pool shown once; the next draw starts a fresh deck. */
    public data class Exhausted(override val shown: Set<String>) : SuggestionDeck

    /** SG6: "Another". It writes nothing. */
    public fun next(pool: List<SuggestCandidate>, tuning: SuggestTuning, random: Random): SuggestionDeck =
        deal(pool, tuning, random, if (this is Exhausted) emptySet() else shown)

    public companion object {
        /** SG2, SG6: the sheet opening, on a fresh deck. */
        public fun open(pool: List<SuggestCandidate>, tuning: SuggestTuning, random: Random): SuggestionDeck =
            deal(pool, tuning, random, emptySet())

        private fun deal(pool: List<SuggestCandidate>, tuning: SuggestTuning, random: Random, shown: Set<String>): SuggestionDeck {
            if (pool.isEmpty()) return EmptyPool
            val drawn = Suggester.draw(pool, tuning, random, excluding = shown) ?: return Exhausted(shown)
            return Showing(drawn, shown + drawn.songId)
        }
    }
}
