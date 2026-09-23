package dev.repertosaurus.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.android.DatabaseFixtures.count
import dev.repertosaurus.android.theme.RepertosaurusTheme
import dev.repertosaurus.android.theme.paperGrain
import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.data.DatabaseHolder
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **visual-identity §3 on the composed Session screen**: VI8's font scale, and VI10's grain, which
 * must never stand between a thumb and the one-tap log.
 *
 * The font scale is set through `LocalDensity` rather than the system setting, so the test owns it
 * and leaves nothing behind; the feel sheet's window inherits it from the composition.
 */
@RunWith(AndroidJUnit4::class)
class VisualIdentityTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableListOf<String>()

    @After
    fun cleanUp() {
        for (name in names) DatabaseFixtures.delete(context, name)
    }

    /** VI8: at 1.3 no label on the screen — header, strips, rows, badges, Add song — is cut off. */
    @Test
    fun theSessionScreenClipsNoLabelAtFontScaleOnePointThree() {
        val (holder, title) = screen("scale-session", fontScale = LARGE_FONT)
        assertNoTextOverflows("the session screen")
        // And the row still logs in one tap at the larger type.
        val before = count(holder, "practice_event")
        compose.onNodeWithText(title).performClick()
        compose.awaitUntil("the tap's insert") { count(holder, "practice_event") == before + 1 }
    }

    /**
     * VI8, F16: the feel sheet at 1.3. "exceptionally" in a quarter-width segment may wrap or drop to
     * the number, and may never clip; every level is still offered and still one tap.
     */
    @Test
    fun theFeelSheetClipsNoLabelAtFontScaleOnePointThree() {
        val (holder, title) = screen("scale-feel", fontScale = LARGE_FONT)
        val feelThreeBefore = count(holder, "practice_event WHERE feel = 3")

        compose.onNodeWithText(title).performTouchInput { longClick() }
        compose.waitForIdle()
        for (level in RatingLevel.entries) {
            compose.onNodeWithTag(RatingTags.segment(FeelSheetTags.FEEL, level)).assertHeightIsAtLeast(48.dp)
        }
        assertNoTextOverflows("the feel sheet")

        compose.onNodeWithTag(RatingTags.segment(FeelSheetTags.FEEL, RatingLevel.EXCEPTIONALLY)).performClick()
        compose.onNodeWithText("Log it", ignoreCase = true).performClick()
        compose.awaitUntil("the feel-3 insert") {
            count(holder, "practice_event WHERE feel = 3") == feelThreeBefore + 1
        }
    }

    /** VI10: the grain draws over the whole screen, and a tap through it still logs, once. */
    @Test
    fun aTapThroughTheGrainStillLogs() {
        val (holder, title) = screen("grain")
        val before = count(holder, "practice_event")

        compose.onNodeWithText(title).performClick()
        compose.awaitUntil("the tap's insert") { count(holder, "practice_event") == before + 1 }
        compose.waitForIdle()
        assertEquals(before + 1, count(holder, "practice_event"), "one tap, one event")
    }

    /**
     * VI15: the badge is drawn inside the row and takes no touch of its own, so a thumb landing on
     * it logs the row — the hit area is the whole row, as it was.
     */
    @Test
    fun aTapOnTheBadgeLogsTheRow() {
        val (holder, _) = screen("badge")
        val before = count(holder, "practice_event")

        compose.onAllNodesWithText("NEVER").onFirst().performClick()
        compose.awaitUntil("the badge tap's insert") { count(holder, "practice_event") == before + 1 }
    }

    /** Every text node's own layout, unmerged, across every window: none overflows its box. */
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
     * Is any glyph cut off: a line lost to the height, a line ellipsised, or a line wider than the
     * box it was given. `hasVisualOverflow` alone is not used: through the semantics action, at this
     * Compose version, it reports a short wrap-content label ("Sort") as overflowing because the
     * paragraph was laid out at the full available width while the node took only the text's own.
     * These three are that property's parts, measured against what is actually drawn.
     */
    private fun clipped(layout: TextLayoutResult): Boolean {
        if (layout.didOverflowHeight) return true
        return (0 until layout.lineCount).any { line ->
            layout.isLineEllipsized(line) ||
                layout.getLineRight(line) - layout.getLineLeft(line) > layout.size.width + 1f
        }
    }

    /** The app's own theme and grain around the Session screen, over a fresh sample database. */
    private fun screen(suffix: String, fontScale: Float? = null): Pair<DatabaseHolder, String> {
        val name = "visual-identity-test-$suffix.db".also { names += it }
        val holder = EditingFixtures.holder(context, name)
        val session = EditingFixtures.session(holder)
        val title = session.state.value.pending.first().title
        compose.setContent {
            Scaled(fontScale) {
                RepertosaurusTheme {
                    Surface(modifier = Modifier.fillMaxSize().paperGrain()) {
                        SessionScreen(viewModel = session, onOpenDrawer = {}, onExport = {})
                    }
                }
            }
        }
        compose.waitForIdle()
        return holder to title
    }

    @Composable
    private fun Scaled(fontScale: Float?, content: @Composable () -> Unit) {
        if (fontScale == null) return content()
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), content = content)
    }

    private companion object {
        const val LARGE_FONT = 1.3f
    }
}
