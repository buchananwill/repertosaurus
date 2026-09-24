package dev.repertosaurus.android.theme

import androidx.compose.ui.graphics.toArgb
import dev.repertosaurus.core.ColourRamp
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * visual-identity VI2: every text pairing the theme uses meets WCAG AA. Normal text needs 4.5:1. The
 * luminance is computed here from the sRGB definition rather than through Compose, so the test cannot
 * agree with a colour-space bug in the thing it checks.
 */
class TokenContrastTest {

    @Test
    fun inkOnEveryGroundTheThemeUses() {
        for ((name, ground) in listOf("Ground" to Tokens.Ground, "Paper" to Tokens.Paper, "Field" to Tokens.Field)) {
            assertAa("Ink on $name", Tokens.Ink.toArgb(), ground.toArgb())
        }
        assertAa("InkMuted on Ground", Tokens.InkMuted.toArgb(), Tokens.Ground.toArgb())
        assertAa("InkMuted on Paper", Tokens.InkMuted.toArgb(), Tokens.Paper.toArgb())
    }

    @Test
    fun theButtonsTheBadgesAndTheSelectedSegment() {
        assertAa("Ink on Ochre", Tokens.Ink.toArgb(), Tokens.Ochre.toArgb())
        assertAa("Ochre on Ink, the DONE badge", Tokens.Ochre.toArgb(), Tokens.Ink.toArgb())
        assertAa("Paper on Ink", Tokens.Paper.toArgb(), Tokens.Ink.toArgb())
    }

    /** VI9: both ends of the header's gradient, since the text crosses the whole of it. */
    @Test
    fun paperOnTheHeaderGradient() {
        for (indigo in listOf(Tokens.Indigo, Tokens.IndigoLight, Tokens.IndigoDark)) {
            assertAa("Paper on ${Integer.toHexString(indigo.toArgb())}", Tokens.Paper.toArgb(), indigo.toArgb())
        }
    }

    /** rating-scale RS6: every step of every ramp carries `Ink` text. */
    @Test
    fun inkOnEveryRampStep() {
        for (ramp in ColourRamp.entries) {
            for (step in ramp.steps) {
                assertAa("Ink on ${ramp.name} ${java.lang.Long.toHexString(step)}", Tokens.Ink.toArgb(), step.toInt())
            }
        }
    }

    /**
     * VI10: the grain darkens by at most a fifth. The darkest grain pixel over the darkest ground text
     * sits on must still pass.
     */
    @Test
    fun inkOnTheDarkestGroundUnderTheDarkestGrain() {
        val darkest = ColourRamp.entries.flatMap { it.steps }.map { it.toInt() } + Tokens.Ground.toArgb()
        for (ground in darkest) {
            assertAa("Ink on ${Integer.toHexString(ground)} under grain", Tokens.Ink.toArgb(), scaled(ground, 0.8))
        }
    }

    /**
     * D97: an error line is `Ink` text, read at full contrast, and its state is a `Madder` mark (a rule, a
     * field's border), which as a non-text element needs 3:1 (WCAG 1.4.11).
     */
    @Test
    fun theErrorLineAndItsMark() {
        assertAa("Ink error text on Ground", Tokens.Ink.toArgb(), Tokens.Ground.toArgb())
        for ((name, ground) in listOf("Ground" to Tokens.Ground, "Paper" to Tokens.Paper, "Field" to Tokens.Field)) {
            val ratio = contrast(Tokens.Madder.toArgb(), ground.toArgb())
            assertTrue(ratio >= NON_TEXT, "the Madder error mark on $name is ${"%.2f".format(ratio)}:1, below $NON_TEXT:1")
        }
    }

    private fun assertAa(what: String, text: Int, ground: Int) {
        val ratio = contrast(text, ground)
        assertTrue(ratio >= AA_NORMAL, "$what is ${"%.2f".format(ratio)}:1, below AA's $AA_NORMAL:1")
    }

    private companion object {
        const val AA_NORMAL = 4.5
        const val NON_TEXT = 3.0

        fun contrast(a: Int, b: Int): Double {
            val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
            return (hi + 0.05) / (lo + 0.05)
        }

        fun luminance(argb: Int): Double {
            fun channel(shift: Int): Double {
                val c = ((argb shr shift) and 0xFF) / 255.0
                return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }

        fun scaled(argb: Int, factor: Double): Int {
            fun channel(shift: Int): Int = (((argb shr shift) and 0xFF) * factor).toInt()
            return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }
}
