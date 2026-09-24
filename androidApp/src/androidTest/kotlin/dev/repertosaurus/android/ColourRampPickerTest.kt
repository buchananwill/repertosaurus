package dev.repertosaurus.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.core.ColourRamp
import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.session.InMemoryDevicePreferences
import kotlin.test.assertEquals
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **rating-scale RS8 and RS16 on the composed app**: the drawer item opens the picker, one tap
 * persists a ramp and closes it, and the badges recompose in the new ramp with no restart. The
 * badge colour is read from its `BadgeColour` semantics, not by sampling pixels.
 */
@RunWith(AndroidJUnit4::class)
class ColourRampPickerTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableListOf<String>()

    @After
    fun cleanUp() {
        for (name in names) DatabaseFixtures.delete(context, name)
    }

    @Test
    fun pickingColdToHotPersistsClosesAndRecoloursTheBadges() {
        val name = "colour-ramp-picker-test.db".also { names += it }
        val device = InMemoryDevicePreferences()
        val app = EditingFixtures.app(EditingFixtures.holder(context, name), device = device)
        EditingFixtures.awaitSession(app.session)
        compose.setApp(app)
        compose.waitForIdle()

        // The sample songs have never been practised on the opening instrument: the coldest step.
        compose.onAllNodesWithText("NEVER").onFirst().assertExists()
        assertEquals(coldest(ColourRamp.DEFAULT), badgeColour(), "the default ramp before any pick")

        compose.openDrawer()
        compose.onNodeWithTag(DrawerTags.COLOUR_RAMP).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(RatingTags.RAMP_PICKER).assertExists()

        compose.onNodeWithTag(RatingTags.ramp(ColourRamp.COLD_TO_HOT)).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(RatingTags.RAMP_PICKER).assertDoesNotExist()
        compose.awaitUntil("the ramp's write") { device.colourRamp() == ColourRamp.COLD_TO_HOT }
        assertEquals(ColourRamp.COLD_TO_HOT, app.settings.colourRamp.value)
        assertEquals(coldest(ColourRamp.COLD_TO_HOT), badgeColour(), "the badge recomposed in the new ramp")
    }

    private fun coldest(ramp: ColourRamp): Color = Color(ramp.step(RatingLevel.NOT_AT_ALL))

    /** The first badge's colour, read from its semantics. */
    private fun badgeColour(): Color =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(BadgeColour)).onFirst()
            .fetchSemanticsNode().config[BadgeColour]
}
