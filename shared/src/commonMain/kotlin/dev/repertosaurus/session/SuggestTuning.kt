package dev.repertosaurus.session

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * suggest SG9, SG11: the radar's five spokes, in the order it draws them clockwise from the top.
 * Coldness and hotness are opposite (SG10). [storedKey] is the spoke's name in the stored tuning.
 */
public enum class SuggestSpoke(public val label: String, public val angleDegrees: Double, internal val storedKey: String) {
    PRIORITY("Priority", 0.0, "priority"),
    HOTNESS("Hotness", 90.0, "hotness"),
    CONFIDENCE("Confidence", 150.0, "confidence"),
    SKIPS("Skips", 210.0, "skips"),
    COLDNESS("Coldness", 270.0, "coldness"),
    ;

    /** The unit vector from the centre, x right and y down, as a screen draws it. */
    public fun direction(): Pair<Double, Double> {
        val radians = angleDegrees * PI / 180.0
        return sin(radians) to -cos(radians)
    }
}

/** SG11, SG12, SG14: why [spoke] is locked at zero, or null. The one place the lock is decided. */
public fun lockOf(spoke: SuggestSpoke, countSkips: Boolean, part: PartResolution, ratingsFresh: Boolean): String? =
    when (spoke) {
        SuggestSpoke.COLDNESS, SuggestSpoke.HOTNESS -> null
        SuggestSpoke.PRIORITY, SuggestSpoke.CONFIDENCE -> when (part) {
            is PartResolution.Resolved -> if (ratingsFresh) null else Messages.SUGGEST_LOCKED_RATINGS
            PartResolution.Pending -> Messages.SUGGEST_LOCKED_RATINGS
            PartResolution.None -> Messages.SUGGEST_LOCKED_NO_PERFORMER
        }
        SuggestSpoke.SKIPS -> if (!countSkips) {
            Messages.SUGGEST_LOCKED_SKIPS
        } else {
            when (part) {
                is PartResolution.Resolved -> null
                PartResolution.Pending -> Messages.SUGGEST_LOCKED_SKIPS_PENDING
                PartResolution.None -> Messages.SUGGEST_LOCKED_NO_PERFORMER
            }
        }
    }

/** SG11: one spoke as drawn and weighed: its radius, zero while locked, and the lock's reason. */
public data class SpokeState(val radius: Double, val reason: String?)

/**
 * suggest SG8, SG11, SG15, SG16: one radius per spoke in `[0, 1]`, snapped to [STEP], plus the two skip
 * settings (SG12, SG13).
 *
 * The radii are what the musician set. A spoke's lock ([lockOf]) depends on the View, so it is applied
 * where the tuning is used ([effective]), not stored: a View with no performer does not wipe the rating
 * radii.
 */
public data class SuggestTuning(
    val radii: Map<SuggestSpoke, Double> = ZERO,
    val countSkips: Boolean = false,
    val showSkipCount: Boolean = true,
) {
    init {
        require(radii.keys == SuggestSpoke.entries.toSet()) { "every spoke has a radius: $radii" }
        require(radii.values.all { it in 0.0..1.0 }) { "radii are in [0, 1]: $radii" }
    }

    public fun radius(spoke: SuggestSpoke): Double = radii.getValue(spoke)

    /** SG11: one spoke, snapped. */
    public fun withRadius(spoke: SuggestSpoke, radius: Double): SuggestTuning =
        copy(radii = radii + (spoke to snap(radius)))

    /** SG11: every spoke's [SpokeState], which the radar and its hint read. */
    public fun spokes(part: PartResolution, ratingsFresh: Boolean): Map<SuggestSpoke, SpokeState> =
        SuggestSpoke.entries.associateWith { spoke ->
            val reason = lockOf(spoke, countSkips, part, ratingsFresh)
            SpokeState(if (reason != null) 0.0 else radius(spoke), reason)
        }

    /** SG11: what the suggester weighs: each locked spoke at zero. */
    public fun effective(part: PartResolution, ratingsFresh: Boolean): SuggestTuning =
        copy(radii = spokes(part, ratingsFresh).mapValues { it.value.radius })

    /** SG12, SG14: the part a skip is counted for, or null when skips are not counted or nobody resolves. */
    public fun skipPart(part: PartResolution): ResolvedPart? =
        if (countSkips) part.resolvedOrNull else null

    /** SG13: the count the card shows, or null: counting on, the count shown, and above zero. */
    public fun shownSkipCount(skips: Long): Long? = skips.takeIf { countSkips && showSkipCount && it > 0 }

    /** SG11's "Shuffle": every radius to zero; the skip settings are kept. */
    public fun shuffled(): SuggestTuning = copy(radii = ZERO)

    /** SG8: pure shuffle. */
    public val isShuffle: Boolean get() = radii.values.all { it == 0.0 }

    /** SG15: `key=value` pairs joined by `;`, radii first in [STORED_ORDER]. */
    public fun encode(): String =
        (STORED_ORDER.map { it.storedKey to radius(it).toString() } +
            listOf(KEY_COUNT_SKIPS to countSkips.toString(), KEY_SHOW_SKIP_COUNT to showSkipCount.toString()))
            .joinToString(";") { (key, value) -> "$key=$value" }

    public companion object {
        private val ZERO: Map<SuggestSpoke, Double> = SuggestSpoke.entries.associateWith { 0.0 }

        /** SG16: pure shuffle, skips not counted, the count shown when they are. */
        public val DEFAULT: SuggestTuning = SuggestTuning()

        /** SG11: the handle's steps per unit radius. */
        public const val STEPS: Int = 20

        public const val STEP: Double = 1.0 / STEPS

        /** A tuning with the given radii and every other at zero, each through [withRadius]. */
        public fun of(vararg radii: Pair<SuggestSpoke, Double>): SuggestTuning =
            radii.fold(DEFAULT) { tuning, (spoke, r) -> tuning.withRadius(spoke, r) }

        /** SG11: the nearest step inside `[0, 1]`. Not-a-number is zero. */
        public fun snap(radius: Double): Double =
            if (radius.isNaN()) 0.0 else (radius.coerceIn(0.0, 1.0) * STEPS).roundToInt().toDouble() / STEPS

        /**
         * SG15: **unreadable reads as the default, and this never throws.** A pair without `=`, a radius
         * outside `[0, 1]` or a flag that is not a boolean makes the whole value unreadable. A pair splits
         * on its first `=`. Stored radii are snapped. An unknown key is
         * ignored and a missing one keeps its default.
         */
        public fun fromStored(stored: String?): SuggestTuning =
            stored?.takeIf { it.isNotBlank() }?.let { runCatching { parse(it) }.getOrNull() } ?: DEFAULT

        private fun parse(stored: String): SuggestTuning? {
            var tuning = DEFAULT
            for (pair in stored.split(';')) {
                if ('=' !in pair) return null
                val key = pair.substringBefore('=')
                val value = pair.substringAfter('=')
                val spoke = SuggestSpoke.entries.firstOrNull { it.storedKey == key }
                tuning = when {
                    spoke != null -> tuning.withRadius(spoke, value.toDoubleOrNull()?.takeIf { it in 0.0..1.0 } ?: return null)
                    key == KEY_COUNT_SKIPS -> tuning.copy(countSkips = value.toBooleanStrictOrNull() ?: return null)
                    key == KEY_SHOW_SKIP_COUNT -> tuning.copy(showSkipCount = value.toBooleanStrictOrNull() ?: return null)
                    else -> tuning
                }
            }
            return tuning
        }

        /** The stored order, which predates [SuggestSpoke]'s drawing order and is kept byte for byte. */
        private val STORED_ORDER = listOf(
            SuggestSpoke.COLDNESS,
            SuggestSpoke.HOTNESS,
            SuggestSpoke.PRIORITY,
            SuggestSpoke.CONFIDENCE,
            SuggestSpoke.SKIPS,
        )

        private const val KEY_COUNT_SKIPS = "countSkips"
        private const val KEY_SHOW_SKIP_COUNT = "showSkipCount"
    }
}
