package dev.repertosaurus.android

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.repertosaurus.android.theme.InkChip
import dev.repertosaurus.android.theme.InkDialog
import dev.repertosaurus.android.theme.InkTextField
import dev.repertosaurus.android.theme.RepertosaurusTheme
import dev.repertosaurus.android.theme.Tokens
import dev.repertosaurus.core.NoteSpelling
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The semantics of P13's primitives (journal session 11, D94, D96): what TalkBack and a test see. */
@RunWith(AndroidJUnit4::class)
class PolishPrimitivesTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** D94: a filter chip is a checkbox that is on or off, and a tap turns it over. */
    @Test
    fun theToggleChipIsACheckbox() {
        var checked by mutableStateOf(false)
        compose.setContent {
            RepertosaurusTheme {
                InkChip(label = "Lead only", checked = checked, onCheckedChange = { checked = it }, modifier = Modifier.testTag(CHIP))
            }
        }
        compose.onNodeWithTag(CHIP).assert(role(Role.Checkbox)).assertIsOff()
        compose.onNodeWithTag(CHIP).performClick()
        compose.onNodeWithTag(CHIP).assertIsOn()
        assertTrue(checked, "the tap reached onCheckedChange with true")
    }

    /** D98 #1: a single-choice chip is a radio button, selected or not, and a tap chooses it. */
    @Test
    fun theChoiceChipIsARadioButton() {
        var chosen by mutableStateOf(false)
        compose.setContent {
            RepertosaurusTheme {
                InkChip(label = "2 days ago", selected = chosen, onSelect = { chosen = true }, modifier = Modifier.testTag(CHIP))
            }
        }
        compose.onNodeWithTag(CHIP)
            .assert(role(Role.RadioButton))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
            .assertIsNotSelected()
        compose.onNodeWithTag(CHIP).performClick()
        compose.onNodeWithTag(CHIP).assertIsSelected()
        assertTrue(chosen, "the tap reached onSelect")
    }

    /** D94: an action chip is a button, and has no on or off at all. */
    @Test
    fun theActionChipIsAButton() {
        var taps = 0
        compose.setContent {
            RepertosaurusTheme { InkChip(label = "+ role", onClick = { taps++ }, modifier = Modifier.testTag(CHIP)) }
        }
        compose.onNodeWithTag(CHIP)
            .assert(role(Role.Button))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
            .performClick()
        assertEquals(1, taps)
    }

    /** D94: an error under the box is the field's own error; a hint is its state description. */
    @Test
    fun theFieldCarriesItsErrorAndItsHint() {
        compose.setContent {
            RepertosaurusTheme {
                InkTextField(
                    value = "",
                    onValueChange = {},
                    label = "Name",
                    isError = true,
                    supportingText = NEEDS_NAME,
                    fieldModifier = Modifier.testTag(FIELD),
                )
                InkTextField(value = "", onValueChange = {}, label = "Sort name", supportingText = HINT, fieldModifier = Modifier.testTag(HINTED))
            }
        }
        compose.onNodeWithTag(FIELD).assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, NEEDS_NAME))
        compose.onNodeWithTag(HINTED)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, HINT))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
    }

    /**
     * D96, D98 #7: with animations on, the panel is still fading in two frames after it opens, and once
     * settled it is solid `Paper`. What is checked is the drawn pixel, so the entry needs no property of its own.
     */
    @Test
    fun theDialogSpringsInAndSettles() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RepertosaurusTheme { InkDialog(onDismiss = {}, title = "Remove Valerie?", modifier = Modifier.testTag(PANEL)) { Text("Body") } }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertFalse(compose.panelIsSolidPaper(PANEL), "two frames in, the panel is still entering")
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertTrue(compose.panelIsSolidPaper(PANEL), "settled, the panel is solid Paper")
    }

    private fun role(role: Role): SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private companion object {
        const val CHIP = "chip"
        const val FIELD = "field"
        const val HINTED = "hinted"
        const val PANEL = "panel"
        const val NEEDS_NAME = "An artist needs a name."
        const val HINT = "Leave blank to derive it from the name."
    }
}

/** VI22, D96: with animations off, the dialog's panel is simply there on its first frame. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class InkDialogReducedMotionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>(NoMotion)

    @Test
    fun theDialogIsInstantWithoutMotion() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RepertosaurusTheme { InkDialog(onDismiss = {}, title = "Remove Valerie?", modifier = Modifier.testTag(PANEL)) { Text("Body") } }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        assertTrue(compose.panelIsSolidPaper(PANEL), "with animations off, the panel is solid on its first frames")
        compose.mainClock.autoAdvance = true
    }

    private companion object {
        const val PANEL = "panel"
    }
}

/**
 * repertoire-editing R40-R42, D94, D95 N2: the drawer's spelling row is the one switch. It starts on (simplified,
 * the default); a tap on the row turns it off and stores `AS_WRITTEN`.
 */
@RunWith(AndroidJUnit4::class)
class DrawerSwitchTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val harness = HabitRouteHarness(compose, "drawer-switch")

    @After
    fun cleanUp() = harness.cleanUp()

    @Test
    fun theSpellingRowTurnsOff() {
        val route = harness.route("spelling")
        compose.openDrawer()
        compose.onNodeWithTag(DrawerTags.NOTE_SPELLING).performScrollTo().assert(role(Role.Switch)).assertIsOn()

        compose.onNodeWithTag(DrawerTags.NOTE_SPELLING).performClick()
        compose.awaitUntil("the stored spelling") { route.device.noteSpelling() == NoteSpelling.AS_WRITTEN }
        assertEquals(NoteSpelling.AS_WRITTEN, route.app.settings.noteSpelling.value)
        compose.onNodeWithTag(DrawerTags.NOTE_SPELLING).assertIsOff()
    }

    private fun role(role: Role): SemanticsMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)
}

/**
 * A pixel inside the dialog panel's bottom padding, clear of its text, border and shadow, is `Paper`: the panel is
 * drawn fully opaque. While the panel fades in, the captured pixel's alpha is below full.
 */
internal fun ComposeTestRule.panelIsSolidPaper(tag: String): Boolean {
    val image = onNodeWithTag(tag).captureToImage()
    val inset = (16 * density.density).toInt()
    val pixel = image.toPixelMap()[image.width / 2, image.height - inset].toArgb()
    val paper = Tokens.Paper.toArgb()
    // The capture keeps the layer's alpha, so an entering panel is Paper at partial alpha: all four channels count.
    return (0..3).all { channel ->
        val shift = channel * 8
        kotlin.math.abs(((pixel shr shift) and 0xFF) - ((paper shr shift) and 0xFF)) <= PAPER_TOLERANCE
    }
}

private const val PAPER_TOLERANCE = 4
