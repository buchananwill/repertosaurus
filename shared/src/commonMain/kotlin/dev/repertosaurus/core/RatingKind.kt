package dev.repertosaurus.core

/**
 * Which of a part's two ratings a `part_rating` row holds; stored as [name]. Separate rows, never
 * two columns, so two devices editing one part's two ratings offline never overwrite each other
 * (schema-3 M4). The level is [RatingLevel].
 */
public enum class RatingKind {
    PRIORITY,
    CONFIDENCE,
    ;

    public companion object {
        /** A stored `kind` read back; null for a name this build does not know. */
        public fun fromStored(stored: String?): RatingKind? = enumByNameOrNull<RatingKind>(stored)
    }
}
