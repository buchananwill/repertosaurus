package dev.repertaurus.core

import java.text.Normalizer

/**
 * Android actual for decision 17's NFC step (decision 17a).
 *
 * `java.text.Normalizer` is the JDK's ICU-backed implementation and has been on Android
 * since API 9, well below this module's `minSdk` of 26.
 *
 * NFC, not NFD and not NFKC: composed is the interchange form the workbook and Windows
 * input already produce, and compatibility folding is lossy against an immutable id.
 */
public actual fun unicodeNormalise(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFC)

/**
 * Android actual for decision 4e's "letter or digit", asked of a code point (decision 41).
 *
 * `Character.isLetterOrDigit(int)` is the JDK's code-point overload — a Unicode letter
 * (categories Lu, Ll, Lt, Lm, Lo) or a decimal digit (Nd) — which is decision 4e's definition
 * exactly, and unlike the `Char` overload it is correct above U+FFFF.
 */
public actual fun isLetterOrDigitCodePoint(codePoint: Int): Boolean =
    Character.isLetterOrDigit(codePoint)
