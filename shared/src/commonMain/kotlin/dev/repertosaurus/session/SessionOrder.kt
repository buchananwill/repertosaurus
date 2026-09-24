package dev.repertosaurus.session

import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.core.enumByName

/**
 * How the session list is ordered: a [mode] and a direction ([reversed]), resolved to the one constant
 * `saved_view.sort_order` stores (views V13b, schema-3 M15).
 *
 * Coldest first is the default and stays the default. A never-practised song is not *hot*: it has no
 * last practice at all, so it leads in one direction and trails in the other. Nulls are ordered
 * explicitly in every comparator rather than being left to fall wherever a missing value happens to
 * sort, which is how "never" ends up at the top of a hottest-first list and makes the direction useless.
 * The triage orders treat an unrated value the same way (triage T8).
 */
public enum class SessionOrder(
    /** triage T6: which key leads. */
    public val mode: SortMode,
    /** triage T6: the direction button's state; false is "need first", the default. */
    public val reversed: Boolean,
) {

    /** Never practised, then longest ago. The order the product exists to produce. */
    COLDEST_FIRST(SortMode.STALENESS, reversed = false),

    /** Most recently practised, with never-practised last. */
    HOTTEST_FIRST(SortMode.STALENESS, reversed = true),

    /** triage T7: priority high to low, then confidence low to high, then [COLDEST_FIRST]. */
    TRIAGE_PRIORITY(SortMode.TRIAGE_PRIORITY, reversed = false),

    /** triage T7: [TRIAGE_PRIORITY]'s exact reverse, ending in [HOTTEST_FIRST]. */
    TRIAGE_PRIORITY_REVERSED(SortMode.TRIAGE_PRIORITY, reversed = true),

    /** triage T7: confidence low to high, then priority high to low, then [COLDEST_FIRST]. */
    TRIAGE_CONFIDENCE(SortMode.TRIAGE_CONFIDENCE, reversed = false),

    /** triage T7: [TRIAGE_CONFIDENCE]'s exact reverse, ending in [HOTTEST_FIRST]. */
    TRIAGE_CONFIDENCE_REVERSED(SortMode.TRIAGE_CONFIDENCE, reversed = true),

    ;

    public val comparator: Comparator<SessionRow>
        get() = when (mode) {
            SortMode.STALENESS -> staleness(reversed)
            SortMode.TRIAGE_PRIORITY -> triage(TriageKey.PRIORITY, TriageKey.CONFIDENCE, reversed)
            SortMode.TRIAGE_CONFIDENCE -> triage(TriageKey.CONFIDENCE, TriageKey.PRIORITY, reversed)
        }

    /** triage T6: the direction button. */
    public val flipped: SessionOrder
        get() = of(mode, !reversed)

    /** triage T6: the mode strip. The direction is kept. */
    public fun withMode(mode: SortMode): SessionOrder = of(mode, reversed)

    public companion object {

        /** triage T6's table: a mode and a direction resolve to the one stored [SessionOrder]. */
        public fun of(mode: SortMode, reversed: Boolean): SessionOrder =
            entries.first { it.mode == mode && it.reversed == reversed }

        /**
         * The one place a stored order is read back — `saved_view.sort_order` (V17) and
         * `SessionPreferences.lastOrder()` alike. Written twice, verbatim, before this
         * existed.
         *
         * V17a: an unreadable value is a row a later build wrote, and coldest first is the
         * default that never surprises. So a new constant needs the SQL `CHECK` widened with it:
         * the constant alone throws at insert, and the `CHECK` alone makes the unknown value
         * silently downgrade here and be written back on the next update. Both halves move
         * together, and a test pins them together.
         */
        public fun parse(name: String?): SessionOrder = enumByName(name, COLDEST_FIRST)
    }
}

/** triage T6: the sort's mode strip, Cold / Priority / Confidence. The direction is [SessionOrder.reversed]. */
public enum class SortMode {
    STALENESS,
    TRIAGE_PRIORITY,
    TRIAGE_CONFIDENCE,
    ;

    /** triage T9: a mode that reads the part's ratings, and is disabled when no performer resolves. */
    public val readsRatings: Boolean
        get() = this != STALENESS
}

/** triage T7's two keys, and which end of each is "need first". */
private enum class TriageKey(val level: (SessionRow) -> RatingLevel?, val needHighFirst: Boolean) {
    PRIORITY({ it.priority }, needHighFirst = true),
    CONFIDENCE({ it.confidence }, needHighFirst = false),
}

private val COLDEST: Comparator<SessionRow> = compareBy(
    { row: SessionRow -> if (row.daysSince == null) 0 else 1 },
    { row: SessionRow -> -(row.daysSince ?: 0L) },
    { row: SessionRow -> row.title },
)

private val HOTTEST: Comparator<SessionRow> = compareBy(
    { row: SessionRow -> if (row.daysSince == null) 1 else 0 },
    { row: SessionRow -> row.daysSince ?: 0L },
    { row: SessionRow -> row.title },
)

private fun staleness(reversed: Boolean): Comparator<SessionRow> = if (reversed) HOTTEST else COLDEST

/** triage T7: [lead], then [follow], each "need first" unless [reversed], then staleness in the same direction. */
private fun triage(lead: TriageKey, follow: TriageKey, reversed: Boolean): Comparator<SessionRow> =
    rated(lead, reversed) then rated(follow, reversed) then staleness(reversed)

/** One triage key. **triage T8: unrated sorts after every rated value, in both directions**: it is no statement, never 0. */
private fun rated(key: TriageKey, reversed: Boolean): Comparator<SessionRow> {
    val highFirst = key.needHighFirst != reversed
    return compareBy<SessionRow> { row -> if (key.level(row) == null) 1 else 0 }
        .thenBy { row -> key.level(row)?.value?.let { if (highFirst) -it else it } ?: 0L }
}
