package dev.songbook.core

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
