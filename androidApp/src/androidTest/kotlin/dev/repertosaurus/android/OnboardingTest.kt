package dev.repertosaurus.android

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.android.DatabaseFixtures.count
import dev.repertosaurus.android.theme.RepertosaurusWindow
import dev.repertosaurus.core.ColourRamp
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.data.DatabaseState
import dev.repertosaurus.session.DevicePreferences
import dev.repertosaurus.session.InMemoryDevicePreferences
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.OnboardingStep
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **onboarding §3 on the composed app**: the whole of [RepertosaurusApp] over a real database, with
 * onboarding not yet done (every other whole-app test starts with it done; see [EditingFixtures.app]).
 * Every write goes through `DeviceSettings` on IO, so each is awaited on the preferences themselves.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableListOf<String>()
    private val preferenceFiles = mutableListOf<String>()

    @After
    fun cleanUp() {
        for (name in names) DatabaseFixtures.delete(context, name)
        for (file in preferenceFiles) context.deleteSharedPreferences(file)
    }

    private fun name(suffix: String): String = "onboarding-test-$suffix.db".also { names += it }

    /** A preference file of the test's own, emptied, stored as the app stores its own. */
    private fun freshPreferenceFile(suffix: String): AndroidSessionPreferences {
        val file = "onboarding-test-$suffix".also { preferenceFiles += it }
        context.getSharedPreferences(file, Context.MODE_PRIVATE).edit().clear().commit()
        return AndroidSessionPreferences(context, file)
    }

    /** OB1: a fresh preference file reads as not done, and the app opens on the welcome. */
    @Test
    fun aFreshPreferenceFileShowsOnboarding() {
        val device = freshPreferenceFile("fresh")
        assertFalse(device.onboardingDone(), "unset reads as not done")

        start(name("fresh"), device)

        compose.onNodeWithTag(OnboardingTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithTag(OnboardingTags.step(OnboardingStep.WELCOME)).assertIsDisplayed()
        compose.onNodeWithContentDescription(MENU).assertDoesNotExist()
        Thread.sleep(HAND_RENDER_MS) // the hands are sprayed off the main thread, which the clock does not wait on
        compose.screenshot("p14", "welcome")
    }

    /** OB1, OB3: a file with onboarding marked done opens on the logger. */
    @Test
    fun aDonePreferenceFileDoesNot() {
        val device = freshPreferenceFile("done")
        device.markOnboardingDone()
        assertTrue(AndroidSessionPreferences(context, preferenceFiles.last()).onboardingDone(), "the mark is stored")

        start(name("done"), device)

        compose.onNodeWithTag(OnboardingTags.SCREEN).assertDoesNotExist()
        compose.onNodeWithContentDescription(MENU).assertIsDisplayed()
    }

    /**
     * Journal F30 B1: `Ready` is the flow's starting value, not a load. With the first load held, the
     * app shows neither onboarding nor the logger, so Skip cannot be spent; released, onboarding shows.
     */
    @Test
    fun onboardingWaitsForTheFirstLoad() {
        val gate = Gate().apply { close() }
        val app = EditingFixtures.app(
            EditingFixtures.holder(context, name("gated")),
            device = InMemoryDevicePreferences(),
            onboarded = false,
            io = gate,
        )
        compose.setApp(app)
        compose.waitForIdle()

        assertFalse(app.session.firstLoadDone.value, "the first load is held")
        compose.onNodeWithTag(OnboardingTags.SCREEN).assertDoesNotExist()
        compose.onNodeWithTag(OnboardingTags.SKIP).assertDoesNotExist()
        compose.onNodeWithContentDescription(MENU).assertDoesNotExist()

        gate.open()
        compose.awaitUntil("the first load") { app.session.firstLoadDone.value }
        compose.waitForIdle()
        compose.onNodeWithTag(OnboardingTags.SCREEN).assertIsDisplayed()
    }

    /** OB3: one tap on "Skip setup" on the welcome ends it for good, and the ramp stays the default. */
    @Test
    fun skipOnTheWelcomeMarksItDoneAndKeepsTheDefaultRamp() {
        val device = InMemoryDevicePreferences()
        val app = start(name("skip"), device)

        skip(device)

        compose.onNodeWithTag(OnboardingTags.SCREEN).assertDoesNotExist()
        compose.onNodeWithContentDescription(MENU).assertIsDisplayed()
        assertEquals(ColourRamp.DEFAULT, device.colourRamp())
        assertEquals(ColourRamp.DEFAULT, app.settings.colourRamp.value)
        assertNull(device.ownerPerformer())
    }

    /** F30 N2, OB3: Skip keeps what was chosen so far. */
    @Test
    fun pastelThenSkipKeepsPastelAndMarksDone() {
        val device = InMemoryDevicePreferences()
        start(name("pastel-skip"), device)

        primary(Messages.ONBOARDING_PICK_COLOURS)
        pickPastel(device)
        skip(device)

        compose.onNodeWithTag(OnboardingTags.SCREEN).assertDoesNotExist()
        assertEquals(ColourRamp.PASTEL_RED_BLUE, device.colourRamp())
    }

    /**
     * OB2, OB4: Pastel, then a performer, then Done. Each answer is stored **before** the done mark,
     * which is written last.
     */
    @Test
    fun pastelThenAPerformerThenDonePersistsBoth() {
        val holder = EditingFixtures.holder(context, name("answers"))
        val coralie = EditingFixtures.performer(holder, "Coralie")
        val device = InMemoryDevicePreferences()
        val app = start(holder, device) { app -> app.session.performersLoaded.value }

        compose.onNodeWithTag(OnboardingTags.step(OnboardingStep.WELCOME)).assertIsDisplayed()
        primary(Messages.ONBOARDING_PICK_COLOURS)
        compose.onNodeWithTag(OnboardingTags.step(OnboardingStep.RAMP)).assertIsDisplayed()
        compose.screenshot("p14", "ramp-step")

        pickPastel(device)
        assertFalse(device.onboardingDone(), "an answer is not the end of onboarding")

        primary(Messages.ONBOARDING_NEXT)
        compose.onNodeWithTag(OnboardingTags.step(OnboardingStep.PERFORMER)).assertIsDisplayed()
        compose.screenshot("p14", "performer-step")

        compose.onNodeWithTag(OwnerPerformerTags.owner(coralie)).performScrollTo().performClick()
        compose.awaitUntil("the owner's write") { device.ownerPerformer() == coralie }
        compose.waitForIdle()
        compose.onNodeWithTag(OwnerPerformerTags.owner(coralie)).assertIsSelected()
        assertFalse(device.onboardingDone(), "done is written last")

        primary(Messages.ONBOARDING_DONE)
        compose.awaitUntil("the done mark") { device.onboardingDone() }
        compose.waitForIdle()

        compose.onNodeWithTag(OnboardingTags.SCREEN).assertDoesNotExist()
        assertEquals(ColourRamp.PASTEL_RED_BLUE, device.colourRamp())
        assertEquals(coralie, device.ownerPerformer())
        assertEquals(coralie, app.settings.ownerPerformer.value)
    }

    /** OB2: with no performers at all, the ramp step's primary action is Done and finishes it. */
    @Test
    fun withNoPerformersThePerformerStepIsSkipped() {
        val device = InMemoryDevicePreferences()
        val app = start(EditingFixtures.holder(context, name("no-performers")), device) { it.session.performersLoaded.value }
        assertEquals(emptyList(), app.session.performers.value, "the fixture has no performers")

        primary(Messages.ONBOARDING_PICK_COLOURS)
        compose.onNodeWithTag(OnboardingTags.step(OnboardingStep.RAMP)).assertIsDisplayed()
        primary(Messages.ONBOARDING_DONE)
        compose.awaitUntil("the done mark") { device.onboardingDone() }
        compose.waitForIdle()

        compose.onNodeWithTag(OnboardingTags.step(OnboardingStep.PERFORMER)).assertDoesNotExist()
        compose.onNodeWithTag(OnboardingTags.SCREEN).assertDoesNotExist()
        compose.onNodeWithContentDescription(MENU).assertIsDisplayed()
    }

    /**
     * F30 N1, OB4 as amended: the performer step, reached and then restored before the performers were
     * read, keeps its step, and skips itself once the read finds none.
     */
    @Test
    fun aRestoredPerformerStepSkipsItselfOnceNoPerformersAreRead() {
        val loaded = mutableStateOf(false)
        var finished = false
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            RepertosaurusWindow {
                OnboardingScreen(
                    performers = emptyList(),
                    performersLoaded = loaded.value,
                    ownerPerformerId = null,
                    onColourRamp = {},
                    onOwnerPerformer = {},
                    onFinish = { finished = true },
                )
            }
        }
        compose.waitForIdle()
        primary(Messages.ONBOARDING_PICK_COLOURS)
        primary(Messages.ONBOARDING_NEXT)
        compose.onNodeWithTag(OnboardingTags.step(OnboardingStep.PERFORMER)).assertIsDisplayed()
        assertFalse(finished, "an unread list is not an empty one")

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithTag(OnboardingTags.step(OnboardingStep.PERFORMER)).assertIsDisplayed()
        assertFalse(finished)

        loaded.value = true
        compose.waitForIdle()
        assertTrue(finished, "no performers: the step skips itself")
    }

    /** OB1: an unreadable database shows recovery, never onboarding, even with onboarding not done. */
    @Test
    fun recoveryTakesPrecedenceOverOnboarding() {
        val name = name("recovery")
        DatabaseFixtures.writeFuture(context, name)
        val device = InMemoryDevicePreferences()
        val app = EditingFixtures.app(DatabaseHolder(context, TEST_DEVICE, name), device = device, onboarded = false)
        compose.setApp(app)
        compose.awaitUntil("the database gate") { app.session.databaseState.value is DatabaseState.Unloadable }
        compose.waitForIdle()

        assertIs<DatabaseState.Unloadable>(app.session.databaseState.value)
        compose.onNodeWithTag(RecoveryTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithTag(OnboardingTags.SCREEN).assertDoesNotExist()
        assertFalse(device.onboardingDone(), "recovery does not consume onboarding")
    }

    /** F30 N5: onboarding is never a precondition for logging. Skip, tap a row, and the event is stored. */
    @Test
    fun skipThenATapLogs() {
        val holder = EditingFixtures.holder(context, name("skip-log"))
        val device = InMemoryDevicePreferences()
        val app = start(holder, device)
        skip(device)

        val row = app.session.state.value.pending.first()
        val before = count(holder, "practice_event")
        compose.onNodeWithText(row.title).performClick()
        compose.awaitUntil("the tap's insert") { count(holder, "practice_event") == before + 1 }
    }

    /** OB6, triage T10: the drawer's "Who you are" marks the current owner, and one tap changes it. */
    @Test
    fun theDrawersWhoYouAreChangesTheOwner() {
        val holder = EditingFixtures.holder(context, name("drawer"))
        val coralie = EditingFixtures.performer(holder, "Coralie")
        val will = EditingFixtures.performer(holder, "Will")
        val device = InMemoryDevicePreferences(ownerPerformer = coralie)
        val app = start(holder, device, onboarded = true) { it.session.performers.value.size == 2 }

        openWhoYouAre()
        compose.onNodeWithTag(OwnerPerformerTags.owner(coralie)).assertIsSelected()
        compose.screenshot("p14", "who-you-are-sheet")
        compose.onNodeWithTag(OwnerPerformerTags.owner(will)).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(OwnerPerformerTags.OWNER_PICKER).assertDoesNotExist()
        compose.awaitUntil("the owner's write") { device.ownerPerformer() == will }
        assertEquals(will, app.settings.ownerPerformer.value)

        // "None" clears it (T10: unset is null).
        openWhoYouAre()
        compose.onNodeWithTag(OwnerPerformerTags.owner(will)).assertIsSelected()
        compose.onNodeWithTag(OwnerPerformerTags.OWNER_NONE).performScrollTo().performClick()
        compose.waitForIdle()
        compose.awaitUntil("the owner's clearing") { device.ownerPerformer() == null }
        assertNull(app.settings.ownerPerformer.value)
    }

    private fun primary(label: String) {
        compose.onNodeWithTag(OnboardingTags.PRIMARY).assert(hasText(label.uppercase())).performClick()
        compose.waitForIdle()
    }

    private fun skip(device: DevicePreferences) {
        compose.onNodeWithTag(OnboardingTags.SKIP).performClick()
        compose.awaitUntil("the done mark") { device.onboardingDone() }
        compose.waitForIdle()
    }

    private fun pickPastel(device: DevicePreferences) {
        compose.onNodeWithTag(RatingTags.ramp(ColourRamp.PASTEL_RED_BLUE)).performScrollTo().performClick()
        compose.awaitUntil("the ramp's write") { device.colourRamp() == ColourRamp.PASTEL_RED_BLUE }
        compose.waitForIdle()
        compose.onNodeWithTag(RatingTags.ramp(ColourRamp.PASTEL_RED_BLUE)).assertIsSelected()
    }

    private fun menu() {
        compose.onNodeWithContentDescription(MENU).performClick()
        compose.waitForIdle()
    }

    private fun openWhoYouAre() {
        menu()
        compose.onNodeWithTag(DrawerTags.WHO_YOU_ARE).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(OwnerPerformerTags.OWNER_PICKER).assertExists()
    }

    private fun start(name: String, device: DevicePreferences): AppModels =
        start(EditingFixtures.holder(context, name), device)

    /**
     * The app over [holder], composed once the logger has loaded and [until] holds. Onboarding is not
     * done unless [onboarded].
     */
    private fun start(
        holder: DatabaseHolder,
        device: DevicePreferences,
        onboarded: Boolean = false,
        until: (AppModels) -> Boolean = { true },
    ): AppModels {
        val app = EditingFixtures.app(holder, device = device, onboarded = onboarded)
        EditingFixtures.awaitSession(app.session)
        EditingFixtures.await("the fixture's condition") { until(app) }
        compose.setApp(app)
        compose.waitForIdle()
        return app
    }

    private companion object {
        const val MENU = "Menu"
        const val HAND_RENDER_MS = 1_000L
    }
}

/**
 * **visual-identity VI22 for the step slide** (F33 N4): with the animator duration scale at 0, the next
 * step is in place on the first frame that shows it, with the welcome already gone.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class OnboardingReducedMotionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>(NoMotion)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "onboarding-reduced-motion-test.db"

    @After
    fun cleanUp() = DatabaseFixtures.delete(context, name)

    @Test
    fun withAnimationsOffTheNextStepShowsWithNoIntermediateFrame() {
        val app = EditingFixtures.app(EditingFixtures.holder(context, name), onboarded = false)
        EditingFixtures.awaitSession(app.session)
        compose.setApp(app)
        compose.waitForIdle()

        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(OnboardingTags.PRIMARY).performClick()
        val ramp = OnboardingTags.step(OnboardingStep.RAMP)
        var frames = 0
        while (compose.onAllNodesWithTag(ramp).fetchSemanticsNodes().isEmpty() && frames < 10) {
            compose.mainClock.advanceTimeByFrame()
            frames++
        }

        compose.onNodeWithTag(ramp).assertExists("the ramp step never appeared within ten frames")
        compose.onNodeWithTag(OnboardingTags.step(OnboardingStep.WELCOME)).assertDoesNotExist()
        assertEquals(0.dp, compose.onNodeWithTag(ramp).getBoundsInRoot().left, "the step is in place, not sliding")
        compose.mainClock.autoAdvance = true
    }

}
