package dev.repertaurus.core

import kotlin.math.abs

/**
 * The near-match pass behind every type-ahead in the app — decision 16's "surfacing
 * near-matches while typing", over decision 17's [normalise].
 *
 * One implementation, deliberately. Artists, instruments and — when they are wired — tags,
 * venues, bands, performers and practice contexts are the same problem: a human typing a
 * value that already exists in a slightly different form. Decision 16 exists because
 * friction here is how you get `Party` and `party`; a second implementation of the matching
 * is how you get it back.
 *
 * Two kinds of near miss are handled, and they are different things:
 *
 * 1. **Spelling that normalises to the same thing.** `Fratellis` for `The Fratellis`,
 *    `AC DC` for `AC/DC`, `Florence and the Machine` for `Florence & the Machine`. These
 *    also derive the same id, so they converge whether or not the user notices.
 * 2. **A genuine typo.** `Ukelele` for `Ukulele`, `Beyonce` for `Beyoncé`. These normalise
 *    to *different* strings and would derive *different* ids, so surfacing them is the only
 *    thing standing between the user and a permanent duplicate. Hence the edit-distance
 *    pass.
 *
 * Matching may be lossy; derivation may not (decision 17c). Nothing here is ever used to
 * build an id.
 */
public object NearMatches {

    /** Ranked near-matches, best first. An empty or punctuation-only query matches nothing. */
    public fun <T> search(
        query: String,
        items: List<T>,
        limit: Int = 6,
        name: (T) -> String,
    ): List<T> {
        val needle = normalise(query)
        if (needle.isEmpty()) return emptyList()
        return items
            .mapNotNull { item -> rank(needle, normalise(name(item)))?.let { it to item } }
            .sortedWith(compareBy({ it.first }, { name(it.second) }))
            .take(limit)
            .map { it.second }
    }

    /**
     * How good a match [candidate] is for [needle] — lower is better, null is no match.
     * Both arguments must already be normalised.
     */
    public fun rank(needle: String, candidate: String): Int? = when {
        needle.isEmpty() -> null
        candidate == needle -> 0
        candidate.startsWith(needle) -> 1
        candidate.contains(needle) -> 2
        // Every word typed appears somewhere: "kaiser chiefs" finds "The Kaiser Chiefs",
        // and so does "chiefs kaiser".
        needle.split(' ').all { candidate.contains(it) } -> 3
        // Ignoring the spaces normalise put where punctuation was, so "acdc" finds "AC/DC".
        candidate.replace(" ", "").contains(needle.replace(" ", "")) -> 4
        // A typo: "ukelele" against "ukulele". One edit for short values, two once there
        // is enough text for a slip to be obvious rather than a different word.
        editDistance(needle, candidate, tolerance(needle, candidate)) <=
            tolerance(needle, candidate) -> 5
        else -> null
    }

    private fun tolerance(a: String, b: String): Int =
        if (minOf(a.length, b.length) >= 8) 2 else 1

    /**
     * Levenshtein distance, abandoned as soon as it exceeds [max] — which is what keeps
     * this cheap enough to run against every artist on every keystroke. Returns `max + 1`
     * to mean "further than you care about".
     */
    public fun editDistance(a: String, b: String, max: Int): Int {
        if (abs(a.length - b.length) > max) return max + 1
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            var best = current[0]
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
                if (current[j] < best) best = current[j]
            }
            if (best > max) return max + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
