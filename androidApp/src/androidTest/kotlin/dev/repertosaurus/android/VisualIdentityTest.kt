package dev.repertosaurus.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.repertosaurus.android.DatabaseFixtures.count
import dev.repertosaurus.core.RatingLevel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **visual-identity §3 on the composed Session screen**: VI8's font scale, and VI10's grain, which
 * must never stand between a thumb and the one-tap log.
 */
@RunWith(AndroidJUnit4::class)
class VisualIdentityTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fixture = SessionScreenFixture(compose, "visual-identity-test")

    @After
    fun cleanUp() = fixture.cleanUp()

    /** VI8: at 1.3 no label on the screen — header, strips, rows, badges, Add song — is cut off. */
    @Test
    fun theSessionScreenClipsNoLabelAtFontScaleOnePointThree() {
        val screen = fixture.open("scale-session", fontScale = LARGE_FONT)
        val title = screen.title
        assertNoTextOverflows("the session screen")
        // And the row still logs in one tap at the larger type.
        val before = count(screen.holder, "practice_event")
        compose.onNodeWithText(title).performClick()
        compose.awaitUntil("the tap's insert") { count(screen.holder, "practice_event") == before + 1 }
    }

    /**
     * VI8, F16: the feel sheet at 1.3. "exceptionally" in a quarter-width segment may wrap or drop to
     * the number, and may never clip; every level is still offered and still one tap.
     */
    @Test
    fun theFeelSheetClipsNoLabelAtFontScaleOnePointThree() {
        val screen = fixture.open("scale-feel", fontScale = LARGE_FONT)
        val feelThreeBefore = count(screen.holder, "practice_event WHERE feel = 3")

        compose.onNodeWithText(screen.title).performTouchInput { longClick() }
        compose.waitForIdle()
        for (level in RatingLevel.entries) {
            compose.onNodeWithTag(RatingTags.segment(FeelSheetTags.FEEL, level)).assertHeightIsAtLeast(48.dp)
        }
        assertNoTextOverflows("the feel sheet")

        compose.onNodeWithTag(RatingTags.segment(FeelSheetTags.FEEL, RatingLevel.EXCEPTIONALLY)).performClick()
        compose.onNodeWithText("Log it", ignoreCase = true).performClick()
        compose.awaitUntil("the feel-3 insert") {
            count(screen.holder, "practice_event WHERE feel = 3") == feelThreeBefore + 1
        }
    }

    /** VI10: the grain draws over the whole screen, and a tap through it still logs, once. */
    @Test
    fun aTapThroughTheGrainStillLogs() {
        val screen = fixture.open("grain")
        val before = count(screen.holder, "practice_event")

        compose.onNodeWithText(screen.title).performClick()
        compose.awaitUntil("the tap's insert") { count(screen.holder, "practice_event") == before + 1 }
        compose.waitForIdle()
        assertEquals(before + 1, count(screen.holder, "practice_event"), "one tap, one event")
    }

    /** VI15: the badge takes no touch of its own, so a thumb landing on it logs the row. */
    @Test
    fun aTapOnTheBadgeLogsTheRow() {
        val screen = fixture.open("badge")
        val before = count(screen.holder, "practice_event")

        compose.onAllNodesWithText("NEVER").onFirst().performClick()
        compose.awaitUntil("the badge tap's insert") { count(screen.holder, "practice_event") == before + 1 }
    }

    /** Every text node's own layout, unmerged, across every window: none is clipped. */
    private fun assertNoTextOverflows(where: String) {
        compose.waitForIdle()
        val nodes = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty(), "$where has no text to check")
        for (node in nodes) {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val text = node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString()
            for (layout in layouts) {
                assertTrue(!clipped(layout), "$where: \"$text\" is clipped at font scale $LARGE_FONT")
            }
        }
    }

    /**
     * A line lost to the height, ellipsised, or wider than its box. Not `hasVisualOverflow` alone:
     * through the semantics action at this Compose version it reports a wrap-content label ("Sort") as
     * overflowing, because the paragraph was laid out at the full available width.
     */
    private fun clipped(layout: TextLayoutResult): Boolean {
        if (layout.didOverflowHeight) return true
        return (0 until layout.lineCount).any { line ->
            layout.isLineEllipsized(line) ||
                layout.getLineRight(line) - layout.getLineLeft(line) > layout.size.width + 1f
        }
    }

    private companion object {
        const val LARGE_FONT = 1.3f
    }
}
