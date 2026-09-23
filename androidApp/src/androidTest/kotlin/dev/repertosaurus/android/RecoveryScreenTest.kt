package dev.repertosaurus.android

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.data.DatabaseState
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Schema-compatibility S10: **`RecoveryScreen` is reachable, not theoretical.**
 *
 * The app is booted against a broken database — the whole tree, `RepertosaurusApp` and all, not
 * the screen in isolation — and the assertion is that the process survives and the screen
 * appears with something the user can act on. The failure this spec exists to prevent is
 * precisely the one nobody had a test for.
 *
 * The counterpart assertion is here too: booted against a **pre-Views** database, the app does
 * *not* show recovery. That file is now migrated in place (S2), and a build that showed the
 * recovery screen for it would be refusing a database it can read.
 */
@RunWith(AndroidJUnit4::class)
class RecoveryScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val names = mutableListOf<String>()

    @After
    fun cleanUp() {
        for (name in names) DatabaseFixtures.delete(context, name)
    }

    private fun name(suffix: String): String =
        "recovery-test-$suffix.db".also { names += it }

    @Test
    fun anUnreadableDatabaseShowsRecoveryWithBothActions() {
        val name = name("future")
        DatabaseFixtures.writeFuture(context, name)

        val model = show(name)
        assertIs<DatabaseState.Unloadable>(model.databaseState.value)

        compose.onNodeWithTag(RecoveryTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithTag(RecoveryTags.IMPORT).assertIsDisplayed()
        compose.onNodeWithTag(RecoveryTags.START_FRESH).assertIsDisplayed()
        compose.onNodeWithText("Repertosaurus can't open your database").assertIsDisplayed()
    }

    /** The refusal has to say what is wrong, or it is a dead end with a nicer background. */
    @Test
    fun theRecoveryScreenNamesTheProblem() {
        val name = name("pre-views-zero")
        DatabaseFixtures.writeVersionOne(context, name, userVersion = 0)

        show(name)

        compose.onNodeWithTag(RecoveryTags.REASON).assertIsDisplayed()
        compose.onNodeWithText("saved_view", substring = true).assertIsDisplayed()
    }

    /**
     * S9's second action, followed all the way through. Asserting the button is *displayed* would
     * not have caught what this does catch: that the "empty database" the screen promises twice
     * is actually empty, rather than eight sample songs with invented practice dates that a user
     * who has just lost their repertoire cannot tell from survivors.
     */
    @Test
    fun startFreshLeavesAnEmptyDatabaseAndTheSessionScreen() {
        val name = name("start-fresh")
        DatabaseFixtures.writeFuture(context, name)
        val model = show(name)
        assertIs<DatabaseState.Unloadable>(model.databaseState.value)

        compose.onNodeWithTag(RecoveryTags.START_FRESH).performClick()
        compose.onNodeWithText("Delete and start fresh").performClick()
        compose.waitUntil(BOOT_TIMEOUT_MS) { model.databaseState.value == DatabaseState.Ready }
        compose.waitForIdle()

        compose.onNodeWithTag(RecoveryTags.SCREEN).assertDoesNotExist()
        assertEquals(DatabaseState.Ready, model.databaseState.value)
        assertTrue(model.state.value.pending.isEmpty(), "start fresh must not seed sample songs")
    }

    /** S2: a pre-Views database is upgraded, so recovery must not appear for it. */
    @Test
    fun aPreViewsDatabaseGoesStraightToTheSessionScreen() {
        val name = name("pre-views")
        DatabaseFixtures.writeVersionOne(context, name)

        val model = show(name)

        compose.onNodeWithTag(RecoveryTags.SCREEN).assertDoesNotExist()
        assert(model.databaseState.value == DatabaseState.Ready)
    }

    private fun show(name: String): SessionViewModel {
        val holder = DatabaseHolder(context, TEST_DEVICE, name)
        lateinit var model: SessionViewModel
        lateinit var repertoire: RepertoireViewModel
        lateinit var songs: SongsViewModel
        lateinit var artists: ArtistsViewModel
        instrumentation.runOnMainSync {
            model = SessionViewModel(holder, InMemoryPreferences(), TEST_DEVICE)
            // The three editing routes' ViewModels read nothing until their screen is entered,
            // so building them over an unloadable database must not disturb the gate.
            repertoire = RepertoireViewModel(holder)
            songs = SongsViewModel(holder)
            artists = ArtistsViewModel(holder)
        }
        compose.setContent { MaterialTheme { RepertosaurusApp(model, repertoire, songs, artists) } }
        compose.waitUntil(BOOT_TIMEOUT_MS) {
            model.databaseState.value is DatabaseState.Unloadable || !model.state.value.loading
        }
        compose.waitForIdle()
        return model
    }

}
