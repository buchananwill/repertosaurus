package dev.repertosaurus.core

/**
 * rating-scale RS1-RS3. [value] is a `Long` because that is SQLDelight's `INTEGER`; a stored feel
 * is read on this scale unchanged, because `practice_event` is immutable (data-model 7).
 */
public enum class RatingLevel(public val value: Long, public val label: String) {
    NOT_AT_ALL(0L, "not at all"),
    SOMEWHAT(1L, "somewhat"),
    CERTAINLY(2L, "certainly"),
    EXCEPTIONALLY(3L, "exceptionally"),
    ;

    public companion object {
        /** RS2: [value] is the inverse. Out of range reads as unrated, never a throw (schema-compatibility S8). */
        public fun fromStored(value: Long?): RatingLevel? =
            entries.firstOrNull { it.value == value }
    }
}

/**
 * rating-scale RS4-RS7. ARGB `Long`s in the core so a desktop or web UI reads the same values; the
 * values are provisional and owned by roadmap P13a.
 */
public enum class ColourRamp(public val label: String, public val steps: List<Long>) {
    PASTEL_RED_BLUE("Pastel red to blue", listOf(0xFFF2A7A7, 0xFFF9D6D6, 0xFFD3E5F7, 0xFF93BDE8)),
    DANGER_TO_SAFE("Danger to safe", listOf(0xFFF2A7A7, 0xFFF6D7A4, 0xFFD5EBB4, 0xFF9FD3A6)),
    COLD_TO_HOT("Cold to hot", listOf(0xFF93BDE8, 0xFFD3E5F7, 0xFFF9D2C2, 0xFFF09A7E)),
    ;

    public fun step(level: RatingLevel): Long = steps[level.value.toInt()]

    public companion object {
        /** RS5, as amended by the user on 2026-09-23. */
        public val DEFAULT: ColourRamp = DANGER_TO_SAFE

        public fun fromStored(stored: String?): ColourRamp = enumByName(stored, DEFAULT)
    }
}

/** rating-scale RS11. A negative day count (clock skew) reads as the hottest (journal D35). */
public object Heat {

    public fun level(daysSince: Long?): RatingLevel = when {
        daysSince == null || daysSince >= 30L -> RatingLevel.NOT_AT_ALL
        daysSince >= 14L -> RatingLevel.SOMEWHAT
        daysSince >= 4L -> RatingLevel.CERTAINLY
        else -> RatingLevel.EXCEPTIONALLY
    }
}
