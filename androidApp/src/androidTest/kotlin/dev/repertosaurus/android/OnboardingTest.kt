package dev.repertosaurus.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.core.ColourRamp
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.data.DatabaseState
import dev.repertosaurus.session.DevicePreferences
import dev.repertosaurus.session.InMemoryDevicePreferences
import dev.repertosaurus.session.Messages
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

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
        context.getSharedPreferences(file, android.content.Context.MODE_PRIVATE).edit().clear().commit()
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
        compose.onNodeWithContentDescription("Menu").assertDoesNotExist()
    }

    /** OB1, OB3: a file with onboarding marked done opens on the logger. */
    @Test
    fun aDonePreferenceFileDoesNot() {
        val device = freshPreferenceFile("done")
        device.markOnboardingDone()
        assertEquals(true, AndroidSessionPreferences(context, preferenceFiles.last()).onboardingDone(), "the mark is stored")

        start(name("done"), device)

        compose.onNodeWithTag(OnboardingTags.SCREEN).assertDoesNotExist()
        compose.onNodeWithContentDescription("Menu").assertIsDisplayed()
    }

    /** OB3: one tap on "Skip setup" on the welcome ends it for good, and the ramp stays the default. */
    @Test
    fun skipOnTheWelcomeMarksItDoneAndKeepsTheDefaultRamp() {
        val device = InMemoryDevicePreferences()
        val app = start(name("skip"), device)

        compose.onNodeWithTag(OnboardingTags.SKIP).performClick()
        compose.awaitUntil("the done mark") { device.onboardingDone() }
        compose.waitForIdle()

        compose.onNodeWithTag(OnboardingTags.SCREEN).assertDoesNotExist()
        compose.onNodeWithContentDescription("Menu").assertIsDisplayed()
        assertEquals(ColourRamp.DEFAULT, device.colourRamp())
        assertEquals(ColourRamp.DEFAULT, app.settings.colourRamp.value)
        assertNull(device.ownerPerformer())
    }

    /**
     * OB2, OB4: Pastel, then a performer, then Done. Each answer is stored **before** the done mark,
     * which is written last.
     */
    @Test
    fun pastelThenAPerformerThenDonePersistsBoth() {
        val name = name("answers")
        val holder = EditingFixtures.holder(context, name)
        val coralie = EditingFixtures.performer(holder, "Coralie")
        val device = InMemoryDevicePreferences()
        val app = start(holder, device, awaitPerformers = true)

        compose.onNodeWithTag(OnboardingTags.PRIMARY).assert(hasText(Messages.ONBOARDING_PICK_COLOURS.uppercase()))
        compose.onNodeWithTag(OnboardingTags.PRIMARY).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(OnboardingTags.step(OnboardingStep.RAMP)).assertIsDisplayed()

        compose.onNodeWithTag(RatingTags.ramp(ColourRamp.PASTEL_RED_BLUE)).performScrollTo().performClick()
        compose.awaitUntil("the ramp's write") { device.colourRamp() == ColourRamp.PASTEL_RED_BLUE }
        compose.waitForIdle()
        compose.onNodeWithTag(RatingTags.ramp(ColourRamp.PASTEL_RED_BLUE)).assertIsSelected()
        assertFalse(device.onboardingDone(), "an answer is not the end of onboarding")

        compose.onNodeWithTag(OnboardingTags.PRIMARY).assert(hasText(Messages.ONBOARDING_NEXT.uppercase()))
        compose.onNodeWithTag(OnboardingTags.PRIMARY).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(OnboardingTags.step(OnboardingStep.PERFORMER)).assertIsDisplayed()

        compose.onNodeWithTag(OnboardingTags.owner(coralie)).performScrollTo().performClick()
        compose.awaitUntil("the owner's write") { device.ownerPerformer() == coralie }
        compose.waitForIdle()
        compose.onNodeWithTag(OnboardingTags.owner(coralie)).assertIsSelected()
        assertFalse(device.onboardingDone(), "done is written last")

        compose.onNodeWithTag(OnboardingTags.PRIMARY).assert(hasText(Messages.ONBOARDING_DONE.uppercase()))
        compose.onNodeWithTag(OnboardingTags.PRIMARY).performClick()
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
        val app = start(name("no-performers"), device)
        assertEquals(emptyList(), app.session.performers.value, "the fixture has no performers")

        compose.onNodeWithTag(OnboardingTags.PRIMARY).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(OnboardingTags.step(OnboardingStep.RAMP)).assertIsDisplayed()
        compose.onNodeWithTag(OnboardingTags.PRIMARY).assert(hasText(Messages.ONBOARDING_DONE.uppercase()))

        compose.onNodeWithTag(OnboardingTags.PRIMARY).performClick()
        compose.awaitUntil("the done mark") { device.onboardingDone() }
        compose.waitForIdle()

        compose.onNodeWithTag(OnboardingTags.step(OnboardingStep.PERFORMER)).assertDoesNotExist()
        compose.onNodeWithTag(OnboardingTags.SCREEN).assertDoesNotExist()
        compose.onNodeWithContentDescription("Menu").assertIsDisplayed()
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

    /** OB6, triage T10: the drawer's "Who you are" marks the current owner, and one tap changes it. */
    @Test
    fun theDrawersWhoYouAreChangesTheOwner() {
        val name = name("drawer")
        val holder = EditingFixtures.holder(context, name)
        val coralie = EditingFixtures.performer(holder, "Coralie")
        val will = EditingFixtures.performer(holder, "Will")
        val device = InMemoryDevicePreferences(ownerPerformer = coralie)
        val app = EditingFixtures.app(holder, device = device)
        compose.awaitUntil("the logger and its performers") {
            !app.session.state.value.loading && app.session.performers.value.size == 2
        }
        compose.setApp(app)
        compose.waitForIdle()

        openWhoYouAre()
        compose.onNodeWithTag(OnboardingTags.owner(coralie)).assertIsSelected()
        compose.onNodeWithTag(OnboardingTags.owner(will)).performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(OnboardingTags.OWNER_PICKER).assertDoesNotExist()
        compose.awaitUntil("the owner's write") { device.ownerPerformer() == will }
        assertEquals(will, app.settings.ownerPerformer.value)

        // "None" clears it (T10: unset is null).
        openWhoYouAre()
        compose.onNodeWithTag(OnboardingTags.owner(will)).assertIsSelected()
        compose.onNodeWithTag(OnboardingTags.OWNER_NONE).performScrollTo().performClick()
        compose.waitForIdle()
        compose.awaitUntil("the owner's clearing") { device.ownerPerformer() == null }
        assertNull(app.settings.ownerPerformer.value)
    }

    private fun openWhoYouAre() {
        compose.onNodeWithContentDescription("Menu").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(DrawerTags.WHO_YOU_ARE).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(OnboardingTags.OWNER_PICKER).assertExists()
    }

    private fun start(name: String, device: DevicePreferences): AppModels =
        start(EditingFixtures.holder(context, name), device, awaitPerformers = false)

    /**
     * The app over [holder] with onboarding not done on [device], composed once the logger has loaded
     * and, with [awaitPerformers], once its performers have: the performer step is skipped on an empty
     * list, so a test that means to reach it waits for the list first.
     */
    private fun start(holder: DatabaseHolder, device: DevicePreferences, awaitPerformers: Boolean): AppModels {
        val app = EditingFixtures.app(holder, device = device, onboarded = false)
        compose.awaitUntil("the logger's first load") {
            !app.session.state.value.loading && (!awaitPerformers || app.session.performers.value.isNotEmpty())
        }
        compose.setApp(app)
        compose.waitForIdle()
        return app
    }
}
