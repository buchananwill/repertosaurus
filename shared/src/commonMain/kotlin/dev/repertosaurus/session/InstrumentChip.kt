package dev.repertosaurus.session

/**
 * **The one title-case helper (E46).** `backing vocal` reads as `Backing Vocal`; the stored
 * name is never touched (decision 5).
 *
 * It lives in the shared core because the capability sheet had re-implemented it **character
 * for character** in a composable, which is one of the two forks this project has already paid
 * for. Every surface that spells a stored lower-case name for a human calls this one: the chip
 * row, the capability chips, the capability dialog, the suggestion rows — and the desktop UI
 * when it arrives.
 */
public fun titleCase(name: String): String =
    name.split(' ').joinToString(" ") { word ->
        if (word.isEmpty()) word else word.replaceFirstChar { it.uppercaseChar() }
    }

/** One instrument chip, read from the `instrument` table — never a hardcoded enum. */
public data class InstrumentChip(val id: String, val name: String) {
    /** `backing vocal` reads as `Backing Vocal` on a chip; the stored name is untouched. */
    public val label: String
        get() = titleCase(name)
}
