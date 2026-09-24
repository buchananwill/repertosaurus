package dev.repertosaurus.habit

import dev.repertosaurus.core.enumByName

/** scorecards SC4: what the scorecards count. A device display preference, never synced. */
public enum class HabitScope {
    ALL_INSTRUMENTS,
    PRACTICE_INSTRUMENT,
    ;

    /**
     * The instrument to count, or null for every instrument. With no View there is no practice
     * instrument, and [PRACTICE_INSTRUMENT] falls back to every instrument (journal session 11, D63).
     */
    public fun instrumentId(practiceInstrumentId: String?): String? =
        if (this == PRACTICE_INSTRUMENT) practiceInstrumentId else null

    public companion object {
        /** SC4. */
        public val DEFAULT: HabitScope = ALL_INSTRUMENTS

        /** Unset or unknown reads as [DEFAULT], and never throws. */
        public fun fromStored(name: String?): HabitScope = enumByName(name, DEFAULT)
    }
}
