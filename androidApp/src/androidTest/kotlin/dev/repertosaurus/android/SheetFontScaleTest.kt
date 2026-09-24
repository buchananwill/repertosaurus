package dev.repertosaurus.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.android.DatabaseFixtures.count
import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.SuggestSpoke
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * visual-identity VI8 for **the sheets**, each its own window, at a real system font scale of 1.3: no
 * label is clipped. [SystemFontScale] runs before the Compose rule, so the activity starts at the scale.
 */
@RunWith(AndroidJUnit4::class)
class SheetFontScaleTest {

    @get:Rule(order = 0)
    val fontScale = SystemFontScale(LARGE_FONT)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fixture = SessionScreenFixture(compose, "sheet-font-scale-test")

    @After
    fun cleanUp() = fixture.cleanUp()

    /** The rule reached the sheet's window, or nothing below tests anything. */
    private fun assertTheScaleHolds() {
        assertEquals(LARGE_FONT, InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.fontScale)
        assertEquals(LARGE_FONT, compose.activity.resources.configuration.fontScale)
    }

    /**
     * suggest SG11: every spoke label, the two-line locked ones included, lies inside the radar, and no line of
     * it is lost.
     */
    @Test
    fun theSpokeLabelsFit() {
        val screen = fixture.open("spoke-labels")
        assertTheScaleHolds()
        compose.suggest(screen)
        compose.openTune()
        compose.onNodeWithTag(SuggestTags.RADAR).performScrollTo()
        val radar = compose.onNodeWithTag(SuggestTags.RADAR).fetchSemanticsNode().boundsInRoot
        for (spoke in SuggestSpoke.entries) {
            val bounds = compose.onNodeWithTag(SuggestTags.label(spoke), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue(
                bounds.left >= radar.left && bounds.right <= radar.right && bounds.top >= radar.top && bounds.bottom <= radar.bottom,
                "${spoke.name}'s label $bounds lies outside the radar $radar",
            )
            compose.assertNoTextClipped("${spoke.name}'s label", hasAnyAncestor(hasTestTag(SuggestTags.label(spoke))))
        }
    }

    /** suggest SG12, SG13: "Tune" with its two switches and five live spokes. */
    @Test
    fun tuneFits() {
        val screen = fixture.openWithOwner("tune")
        assertTheScaleHolds()
        compose.suggest(screen)
        compose.openTune()
        for (tag in listOf(SuggestTags.COUNT_SKIPS, SuggestTags.SHOW_SKIP_COUNT)) {
            compose.onNodeWithTag(tag).performScrollTo()
            compose.assertNoTextClipped(tag, hasAnyAncestor(hasTestTag(tag)))
        }
        compose.onNodeWithTag(SuggestTags.RADAR).performScrollTo()
        compose.screenshot("p9b", "tune")
    }

    /**
     * The feel sheet: "exceptionally" in a quarter-width segment may wrap or drop to the number, and may never
     * clip; every level is still offered and still one tap.
     */
    @Test
    fun theFeelSheetFits() {
        val screen = fixture.open("feel")
        assertTheScaleHolds()
        val feelThreeBefore = count(screen.holder, "practice_event WHERE feel = 3")

        compose.onNodeWithText(screen.title).performTouchInput { longClick() }
        compose.waitForIdle()
        for (level in RatingLevel.entries) {
            compose.onNodeWithTag(RatingTags.segment(FeelSheetTags.FEEL, level)).assertHeightIsAtLeast(48.dp)
        }
        compose.assertNoTextClipped("the feel sheet")

        compose.onNodeWithTag(RatingTags.segment(FeelSheetTags.FEEL, RatingLevel.EXCEPTIONALLY)).performClick()
        compose.onNodeWithText(Messages.LOG_IT, ignoreCase = true).performClick()
        compose.awaitUntil("the feel-3 insert") { count(screen.holder, "practice_event WHERE feel = 3") == feelThreeBefore + 1 }
    }

    /** timer TM1, TM2: the feel sheet's timer button beside Log, at its longer label, "Switch timer here". */
    @Test
    fun theFeelSheetsTimerButtonFits() {
        val screen = fixture.open("feel-timer")
        assertTheScaleHolds()
        val row = screen.session.state.value.pending.first()
        EditingFixtures.onMain { screen.session.startTimer(row.songId, row.title) }
        compose.onNode(hasText(row.title) and hasAnyAncestor(hasTestTag(SessionTags.LIST))).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText(Messages.TIMER_SWITCH, ignoreCase = true).performScrollTo().assertHeightIsAtLeast(48.dp)
        compose.assertNoTextClipped("the feel sheet with a timer running")
        compose.settleAndShoot("p11", "feel-sheet-switch")
    }

    private companion object {
        const val LARGE_FONT = 1.3f
    }
}
