package dev.songbook.core

/**
 * The normalisation function (decision 17). One implementation, three callers: the
 * type-ahead's near-duplicate matching, derived id generation (decision 2), and the
 * migration.
 *
 * Lowercase, trim, fold `&` to `and`, strip a leading `The `, **replace** punctuation with
 * a space, collapse whitespace.
 *
 * Punctuation becomes a space rather than being deleted, which is what makes the seeded
 * tags key the way decision 4c says they do: `cw-duet` keys on `cw duet`, not `cwduet`,
 * so a user typing "CW Duet" matches the existing tag.
 */
public fun normalise(value: String): String {
    var text = value.lowercase().trim()

    // Fold before punctuation stripping — `&` is punctuation and would otherwise vanish.
    text = text.replace("&", " and ")

    if (text.startsWith("the ")) {
        text = text.substring(4)
    }

    val builder = StringBuilder(text.length)
    for (ch in text) {
        if (ch.isLetterOrDigit()) builder.append(ch) else builder.append(' ')
    }

    return builder.toString().split(' ').filter { it.isNotEmpty() }.joinToString(" ")
}
