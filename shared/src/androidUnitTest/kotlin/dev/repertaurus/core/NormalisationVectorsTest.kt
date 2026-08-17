package dev.repertaurus.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Decision 41: the Kotlin core and the Python migration read the same vectors.**
 *
 * `normalise` is decision 17's one function with three callers, and two of them are in two
 * different languages: the shared core derives every id the app writes, and
 * `tools/import/extract.py` derives every id the migration writes. If they disagree on one
 * character, the same name yields two UUIDv5 ids on two devices and **they never converge** —
 * decision 5 makes an id immutable, so nothing downstream repairs it and nothing looks wrong
 * on either screen.
 *
 * That agreement has forked three times in this project, and each time it was asserted in
 * prose or in a test whose author had also written the implementation. So it is asserted here
 * against a file neither implementation owns:
 * `shared/src/androidUnitTest/resources/normalisation-vectors.tsv`, read by this test and by
 * `extract.py :: verify_normalisation_vectors()`, which `build.py` calls before it emits a
 * single row. Adding a vector obliges both sides; deleting one to make a build pass is the
 * failure this file exists to prevent.
 *
 * [NormaliseTest] keeps its own cases and is not replaced. This test proves *agreement*; that
 * one proves the rule.
 */
class NormalisationVectorsTest {

    @Test
    fun everySharedVectorNormalisesAsTheFileStates() {
        val vectors = readVectors()
        assertTrue(vectors.size >= 30, "the shared file lost vectors: ${vectors.size}")

        for ((input, expected) in vectors) {
            assertEquals(
                expected,
                normalise(input),
                "normalise(${escape(input)}) — the shared vector file says ${escape(expected)}",
            )
        }
    }

    /**
     * Decision 41's measured case, stated on its own so the reason survives a reshuffle of the
     * file. `U+1D400 MATHEMATICAL BOLD CAPITAL A` is a letter, and it is two UTF-16 units.
     * A per-`Char` pass finds neither half to be a letter or a digit, drops both, and returns
     * `ndy` — while Python's `\w` keeps the character.
     */
    @Test
    fun aNonBmpLetterSurvivesNormalisation() {
        val astralA = "𝐀"
        assertEquals(2, astralA.length, "this test is worthless if the character is one Char")

        assertEquals("${astralA}ndy", normalise("${astralA}ndy"))
        assertEquals("andy $astralA", normalise("Andy $astralA"))

        // And a non-BMP NON-letter still collapses to exactly one separator, not two.
        assertEquals("smile now", normalise("smile 😀 now"))
    }

    // ---- Reading the shared file ---------------------------------------------------------

    private fun readVectors(): List<Pair<String, String>> =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(VECTORS)) {
            "$VECTORS is not on the test classpath"
        }.bufferedReader().use { reader -> reader.readText() }
            .lineSequence()
            // A vector is any line carrying a tab. Blank lines and comments have none — and
            // neither does anything else, which is why "no tab" is the skip rule rather than
            // "blank": the empty-input vector is a line consisting of one tab, and a
            // blank-line rule silently swallowed it.
            .filterNot { it.startsWith("#") || !it.contains('\t') }
            .map { line ->
                val separator = line.indexOf('\t')
                unescape(line.substring(0, separator)) to unescape(line.substring(separator + 1))
            }
            .toList()

    /** `\\`, `\t`, `\n` and `\uXXXX`, where `\uXXXX` is one UTF-16 code unit. */
    private fun unescape(field: String): String {
        val out = StringBuilder(field.length)
        var index = 0
        while (index < field.length) {
            val ch = field[index]
            if (ch != '\\') {
                out.append(ch)
                index++
                continue
            }
            require(index + 1 < field.length) { "dangling backslash in: $field" }
            when (val marker = field[index + 1]) {
                '\\' -> { out.append('\\'); index += 2 }
                't' -> { out.append('\t'); index += 2 }
                'n' -> { out.append('\n'); index += 2 }
                'u' -> {
                    require(index + 6 <= field.length) { "truncated \\u escape in: $field" }
                    out.append(field.substring(index + 2, index + 6).toInt(16).toChar())
                    index += 6
                }
                else -> throw IllegalArgumentException("unknown escape \\$marker in: $field")
            }
        }
        return out.toString()
    }

    /** The file's own escaping, so a failure message names the character rather than hiding it. */
    private fun escape(value: String): String = value.map { ch ->
        when {
            ch == '\\' -> "\\\\"
            ch == '\t' -> "\\t"
            ch == '\n' -> "\\n"
            ch.code in 0x21..0x7E -> ch.toString()
            else -> "\\u" + ch.code.toString(16).padStart(4, '0')
        }
    }.joinToString("", prefix = "\"", postfix = "\"")

    private companion object {
        const val VECTORS = "normalisation-vectors.tsv"
    }
}
