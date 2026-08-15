package dev.songbook.core

/**
 * Unicode normalisation to **NFC** — the first step of decision 17, and the whole of
 * decision 17a.
 *
 * `kotlin.text` has no Unicode normaliser in `commonMain` and `java.text.Normalizer` is
 * JVM-only, so this is an `expect`/`actual` pair. Android is the only target today and its
 * actual delegates to `java.text.Normalizer`. **iOS and JS actuals will be needed at
 * phases 4 and 5** — `NSString.precomposedStringWithCanonicalMapping` and
 * `String.prototype.normalize("NFC")` respectively. Do not hand-roll a normaliser; if a
 * target ever cannot reach a platform API, take a multiplatform library instead.
 *
 * NFC, not NFKC. NFKC additionally applies compatibility folding — the ligature `ﬁ` to
 * `fi`, `½` to `1⁄2`, full-width forms to ASCII — which is lossy, and decision 5 makes an
 * id immutable once written, so a lossy fold cannot be walked back. Measured against the
 * source workbook, the two forms agree on every one of its 24,705 text cells; the Python
 * migration currently applies NFKC and must be moved to NFC to match this.
 */
public expect fun unicodeNormalise(value: String): String

/**
 * The normalisation function (decision 17). One implementation, three callers: the
 * type-ahead's near-duplicate matching, derived id generation (decision 2), and the
 * migration.
 *
 * Unicode-normalise to NFC, lowercase, trim, fold `&` to `and`, strip a leading `The `,
 * **replace** punctuation with a space, collapse whitespace.
 *
 * **The NFC step comes first and is not optional** (decision 17a). `é` is either one code
 * point (U+00E9) or `e` plus a combining acute (U+0065 U+0301), and macOS and iOS input
 * methods routinely emit the decomposed form. Without it the two spellings of *Michael
 * Bublé* derive different ids on different devices, permanently — decision 5 makes an id
 * immutable once written — and nothing would look wrong on either screen.
 *
 * Punctuation becomes a space rather than being deleted, which is what makes the seeded
 * tags key the way decision 4c says they do: `cw-duet` keys on `cw duet`, not `cwduet`,
 * so a user typing "CW Duet" matches the existing tag.
 */
public fun normalise(value: String): String {
    var text = unicodeNormalise(value).lowercase().trim()

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
