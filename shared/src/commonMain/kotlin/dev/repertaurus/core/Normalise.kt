package dev.repertaurus.core

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
 * Decision 4e's "letter or digit", asked of a **Unicode code point** rather than a UTF-16
 * unit — decision 41.
 *
 * `Char.isLetterOrDigit()` is the obvious call and it is wrong above U+FFFF: a non-BMP letter
 * is two surrogate `Char`s and each half is neither a letter nor a digit, so the character is
 * dropped. The Python migration's `\w` keeps it, so the same name yielded different canonical
 * keys and therefore **different UUIDv5 ids that never converge** — measured on
 * `normalise("𝐀ndy")`, which gave `ndy` here and kept the astral letter there.
 *
 * `kotlin.text` has no code-point predicate in `commonMain`, so this is an `expect`/`actual`
 * pair like [unicodeNormalise] beside it. **iOS and JS actuals will be needed at phases 4 and
 * 5**; whatever they delegate to must agree with the shared vector file, which is what the
 * agreement is now mechanised by.
 */
public expect fun isLetterOrDigitCodePoint(codePoint: Int): Boolean

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
 *
 * **The punctuation pass iterates code points, not UTF-16 units** (decision 41). See
 * [isLetterOrDigitCodePoint]: a per-`Char` test drops every non-BMP letter, because each
 * surrogate half fails it, and the Python migration keeps that letter — which forks the id
 * permanently and silently. The two implementations are held together by the shared vector
 * file `normalisation-vectors.tsv`, which both read.
 */
public fun normalise(value: String): String {
    var text = unicodeNormalise(value).lowercase().trim()

    // Fold before punctuation stripping — `&` is punctuation and would otherwise vanish.
    text = text.replace("&", " and ")

    if (text.startsWith("the ")) {
        text = text.substring(4)
    }

    val builder = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val high = text[index]
        val low = text.getOrNull(index + 1)
        val paired = high.isHighSurrogate() && low != null && low.isLowSurrogate()
        val width = if (paired) 2 else 1
        val codePoint = if (paired) {
            0x10000 + ((high.code - 0xD800) shl 10) + (text[index + 1].code - 0xDC00)
        } else {
            high.code
        }
        if (isLetterOrDigitCodePoint(codePoint)) {
            builder.append(text, index, index + width)
        } else {
            // One space per code point, not per UTF-16 unit — an astral punctuation mark
            // collapses like any other and cannot leave a doubled separator behind.
            builder.append(' ')
        }
        index += width
    }

    return builder.toString().split(' ').filter { it.isNotEmpty() }.joinToString(" ")
}
