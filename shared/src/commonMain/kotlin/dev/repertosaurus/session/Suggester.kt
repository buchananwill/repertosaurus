package dev.repertosaurus.session

import dev.repertosaurus.core.RatingLevel
import kotlin.math.pow
import kotlin.random.Random

/**
 * suggest SG9: one song the suggester may propose. [priority] and [confidence] are the resolved part's
 * (SG14), null when unrated; [skips] is schema-3 M14's count since the part was last practised.
 */
public data class SuggestCandidate(
    val songId: String,
    val daysSince: Long?,
    val priority: RatingLevel? = null,
    val confidence: RatingLevel? = null,
    val skips: Int = 0,
)

/** suggest SG5: the View's rows not logged this session, **ignoring the search query**. */
public val SessionState.suggestionPool: List<SuggestCandidate>
    get() = suggestionPool(skips = emptyMap())

/**
 * SG5 with SG9's inputs: [skips] by song id, for the resolved part (SG14). **The ratings count only while
 * the rows carry the resolved part's** (journal F35 N3): between a part change and its ratings landing
 * they are the previous part's, and read as unrated.
 */
public fun SessionState.suggestionPool(skips: Map<String, Int>): List<SuggestCandidate> {
    val fresh = ratedFor == resolvedPart
    return rows.filter { it.songId !in loggedCounts }.map {
        SuggestCandidate(
            songId = it.songId,
            daysSince = it.daysSince,
            priority = it.priority.takeIf { fresh },
            confidence = it.confidence.takeIf { fresh },
            skips = skips[it.songId] ?: 0,
        )
    }
}

/** What the suggestion sheet shows: [SuggestionDeck] resolved against the rows on screen. */
public sealed interface SuggestionCard {
    /** SG4: "Everything here is logged today". */
    public data object EmptyPool : SuggestionCard

    /** SG4: every song shown once. */
    public data object Exhausted : SuggestionCard

    /** [skips] is the dealt candidate's, for SG13's count. */
    public data class Showing(val row: SessionRow, val skips: Int = 0) : SuggestionCard
}

/**
 * The card for [deck]. A dealt song whose row has gone since (a reload) is never a blank card: it
 * reads as exhausted, or as the empty pool when nothing is left to deal.
 */
public fun SessionState.suggestionCard(deck: SuggestionDeck): SuggestionCard = when (deck) {
    SuggestionDeck.EmptyPool -> SuggestionCard.EmptyPool
    is SuggestionDeck.Exhausted -> SuggestionCard.Exhausted
    is SuggestionDeck.Showing ->
        rows.firstOrNull { it.songId == deck.current.songId }?.let { SuggestionCard.Showing(it, deck.current.skips) }
            ?: if (suggestionPool.isEmpty()) SuggestionCard.EmptyPool else SuggestionCard.Exhausted
}

/** suggest SG7-SG10: the weighting and the draw. Pure: the random source is the caller's. */
public object Suggester {

    /** SG8: the most one full spoke can favour its neediest candidate over its least. Provisional. */
    public const val WEIGHT_BASE: Double = 16.0

    /**
     * SG9 coldness, per candidate: **the share of the candidates it is not tied with that are hotter.**
     * The coldest is 1, the hottest 0, ties share a value, and never practised is the coldest there
     * is. A pool with nothing to compare is all 1.
     */
    public fun coldness(pool: List<SuggestCandidate>): List<Double> {
        val keys = pool.map(::staleness)
        val byKey = HashMap<Long, Double>()
        var hotter = 0
        for ((key, tied) in keys.groupingBy { it }.eachCount().entries.sortedBy { it.key }) {
            val others = keys.size - tied
            byKey[key] = if (others == 0) 1.0 else hotter.toDouble() / others
            hotter += tied
        }
        return keys.map(byKey::getValue)
    }

    /**
     * SG8's `exp(ln 16 × Σ rₖ · fₖ)`, as `16^Σ` so zero is exactly 1. SG10's staleness term is written
     * `(r_cold − r_hot) · f + r_hot`, so equal radii cancel exactly. [tuning] is the one to weigh by,
     * locks applied ([SuggestTuning.effective]).
     */
    public fun weights(pool: List<SuggestCandidate>, tuning: SuggestTuning): List<Double> {
        val cold = tuning.radius(SuggestSpoke.COLDNESS)
        val hot = tuning.radius(SuggestSpoke.HOTNESS)
        val priority = tuning.radius(SuggestSpoke.PRIORITY)
        val confidence = tuning.radius(SuggestSpoke.CONFIDENCE)
        val skips = tuning.radius(SuggestSpoke.SKIPS)
        return coldness(pool).zip(pool) { f, candidate ->
            WEIGHT_BASE.pow(
                (cold - hot) * f + hot +
                    priority * priorityNeed(candidate.priority) +
                    confidence * confidenceNeed(candidate.confidence) +
                    skips * skipsNeed(candidate.skips),
            )
        }
    }

    /** SG9: `level / 3`. **Unrated is 0.** */
    public fun priorityNeed(level: RatingLevel?): Double = level?.let { it.value / MAX_LEVEL } ?: 0.0

    /** SG9: `(3 − level) / 3`, lower confidence being more need. **Unrated is 0.** */
    public fun confidenceNeed(level: RatingLevel?): Double = level?.let { (MAX_LEVEL - it.value) / MAX_LEVEL } ?: 0.0

    /** SG9: `min(skips, 10) / 10`. */
    public fun skipsNeed(skips: Int): Double = skips.coerceIn(0, SKIPS_CAP) / SKIPS_CAP.toDouble()

    private const val MAX_LEVEL: Double = 3.0

    /** SG9: past this many skips a song is no needier. */
    private const val SKIPS_CAP: Int = 10

    /**
     * SG7: one weighted draw from [pool], skipping [excluding]. The weights are the whole pool's, so
     * an exclusion never reweighs the rest. Null when nothing is left.
     */
    public fun draw(
        pool: List<SuggestCandidate>,
        tuning: SuggestTuning,
        random: Random,
        excluding: Set<String> = emptySet(),
    ): SuggestCandidate? {
        val weights = weights(pool, tuning)
        val open = pool.indices.filter { pool[it].songId !in excluding }
        if (open.isEmpty()) return null
        var remaining = random.nextDouble() * open.sumOf(weights::get)
        for (i in open) {
            remaining -= weights[i]
            if (remaining < 0.0) return pool[i]
        }
        return pool[open.last()] // rounding can leave a sliver past the last weight
    }

    private fun staleness(candidate: SuggestCandidate): Long = candidate.daysSince ?: Long.MAX_VALUE
}
