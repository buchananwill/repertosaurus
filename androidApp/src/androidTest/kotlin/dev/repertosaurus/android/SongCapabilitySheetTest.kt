package dev.repertosaurus.android

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.android.DatabaseFixtures.count
import dev.repertosaurus.android.EditingFixtures.onMain
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.data.DatabaseState
import dev.repertosaurus.session.PerformerLineUp
import dev.repertosaurus.session.SongCapability
import dev.repertosaurus.session.SongIdentity
import dev.repertosaurus.session.identity
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The capability sheet itself, composed** — editing E1, E11, E43, E45.
 *
 * The five tests in `SongCapabilityEditingTest` drive the ViewModel directly and never compose
 * anything, so nothing in this arc pinned E1 *at the Compose layer*: a `combinedClickable` added
 * to a chip row later, or a row made tappable-to-log by a well-meaning change, would have passed
 * every one of them. This file composes the real sheet, clicks every clickable thing on it, and
 * counts `practice_event` out of the file afterwards.
 *
 * The other three tests pin the E45 and E43 defences that are properties of the *composable*
 * rather than of the state holder: the dialog resolving its row from the live line-up, the
 * controls being dead while a write is in flight, and a refusal rendering somewhere a
 * confirmation does not.
 */
@RunWith(AndroidJUnit4::class)
class SongCapabilitySheetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val names = mutableListOf<String>()

    @After
    fun cleanUp() {
        for (name in names) DatabaseFixtures.delete(context, name)
    }

    /**
     * **E1 and E11, at the Compose layer.** Every clickable thing on the real sheet is clicked —
     * the chips, the add button, the dialog and everything in it — with the sheet wired to a
     * real [SessionViewModel] over a real database, exactly as `SessionScreen` wires it. The
     * assertion is a **count of `practice_event` read out of the file**, not an absence of a
     * call in the source: a test that asserted "there is no call to `log` in this file" would
     * agree with its own author and would not notice a gesture added here later.
     */
    @Test
    fun noGestureOnTheComposedSheetReachesThePracticeLog() {
        val fixture = fixture("e1")
        val song = firstSong(fixture.model)
        val before = count(fixture.holder, "practice_event")

        onMain { fixture.model.openCapabilities(song) }
        settle(fixture.model)
        onMain { fixture.model.addCapability("Coralie", "vocal") }
        settle(fixture.model)
        onMain { fixture.model.addCapability("Will", "guitar") }
        settle(fixture.model)

        compose.setContent {
            MaterialTheme {
                val capabilities by fixture.model.capabilities.collectAsState()
                val session by fixture.model.state.collectAsState()
                val performers by fixture.model.performers.collectAsState()
                SongCapabilitySheet(
                    song = song,
                    lineUp = capabilities.lineUp,
                    performers = performers,
                    instruments = session.instruments,
                    busy = capabilities.busy,
                    message = capabilities.message,
                    error = capabilities.error,
                    addsCommitted = capabilities.addsCommitted,
                    onAdd = fixture.model::addCapability,
                    onUpdate = fixture.model::updateCapability,
                    onRemove = fixture.model::removeCapability,
                    // Deliberately not `closeCapabilities`: a sweep that clicked the scrim would
                    // otherwise take the sheet off screen and end the test half done.
                    onDismiss = {},
                )
            }
        }
        compose.waitForIdle()

        // Three sweeps, because clicking changes the tree: the first opens the row dialog, the
        // second reaches its chips, its Save and its Remove, the third whatever those leave.
        repeat(3) {
            clickEverything()
            settle(fixture.model)
        }

        assertEquals(
            before,
            count(fixture.holder, "practice_event"),
            "a gesture on the capability sheet wrote a practice event (E1)",
        )
        assertEquals(
            0L,
            count(fixture.holder, "practice_event_void"),
            "a gesture on the capability sheet voided a practice event",
        )
        assertTrue(
            fixture.model.state.value.logged.isEmpty(),
            "the sheet produced an optimistic tap, which is a log by another name",
        )
        assertNull(fixture.model.state.value.undo, "the sheet offered an undo, so something logged")
    }

    /**
     * **E45: the dialog resolves its row from the live line-up, on every composition.**
     *
     * It used to hold the whole [SongCapability] by value, captured when the chip was tapped,
     * and `remember(capability.id)` could never refresh it because the id is the one field that
     * never changes. Here the row is taken out of the line-up underneath the open dialog — the
     * shape a concurrent removal takes. A dialog editing a snapshot carries on offering an
     * editor for a row that is gone, and its Save writes the vanished values back.
     */
    @Test
    fun theDialogFollowsTheLiveLineUpRatherThanTheRowItWasOpenedOn() {
        var lineUp by mutableStateOf(listOf(entry(vocal, guitar)))
        sheet(lineUp = { lineUp })
        compose.waitForIdle()

        compose.onNodeWithText("Vocal").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Coralie · Vocal").assertIsDisplayed()
        compose.onNodeWithText("Leads on Vocal").assertIsDisplayed()

        lineUp = listOf(entry(guitar))
        compose.waitForIdle()

        compose.onNodeWithText("Coralie · Vocal").assertDoesNotExist()
        compose.onNodeWithTag(CapabilityTags.SAVE).assertDoesNotExist()
    }

    /**
     * **E45: nothing is live while a write is in flight.** `busy` used to be rendered as a
     * progress bar and gate no control at all, which is the half that made the stale snapshot
     * lethal rather than merely wrong: a write plus its reload takes hundreds of milliseconds on
     * a 479-song database, and re-tapping the still-stale chip and saving wrote the pre-edit
     * values back over the user's edit, silently.
     */
    @Test
    fun everyControlIsDeadWhileAWriteIsInFlight() {
        sheet(lineUp = { listOf(entry(vocal)) }, busy = true)
        compose.waitForIdle()

        compose.onNodeWithText("Vocal").assertIsNotEnabled()
        compose.onNodeWithTag(CapabilityTags.ADD).assertIsNotEnabled()
        // And the dead chip cannot open the dialog whose Save would write the stale row.
        compose.onNodeWithText("Vocal").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(CapabilityTags.SAVE).assertDoesNotExist()
    }

    /**
     * **E43: a refusal and a confirmation are different things and are rendered as such.** They
     * shared one field painted in the accent colour, so "That did not work: …" appeared exactly
     * where "Added Coralie on vocal" had appeared a moment before and read as one.
     */
    @Test
    fun aRefusalIsRenderedSomewhereAConfirmationIsNot() {
        sheet(
            lineUp = { emptyList() },
            error = "That did not work: vocal_range is offered on vocal and backing vocal only",
        )
        compose.waitForIdle()

        compose.onNodeWithTag(CapabilityTags.ERROR).assertIsDisplayed()
        compose.onNodeWithTag(CapabilityTags.MESSAGE).assertDoesNotExist()
    }

    // ---- Harness -------------------------------------------------------------------------

    /**
     * The sheet with no ViewModel behind it, for the three tests that are about the composable.
     *
     * Both suggestion lists are empty on purpose: their chips carry the same words as the
     * capability chips — `Vocal`, `Coralie` — and a matcher that found two nodes would fail for
     * a reason that has nothing to do with what is being asserted.
     */
    private fun sheet(
        lineUp: () -> List<PerformerLineUp>,
        busy: Boolean = false,
        message: String? = null,
        error: String? = null,
    ) {
        compose.setContent {
            MaterialTheme {
                SongCapabilitySheet(
                    song = SONG,
                    lineUp = lineUp(),
                    performers = emptyList(),
                    instruments = emptyList(),
                    busy = busy,
                    message = message,
                    error = error,
                    addsCommitted = 0L,
                    onAdd = { _, _ -> },
                    onUpdate = {},
                    onRemove = {},
                    onDismiss = {},
                )
            }
        }
    }

    private val vocal = SongCapability(
        id = "cap-vocal",
        performerId = "p1",
        performerName = "Coralie",
        instrumentId = "6409e4f3-5881-5293-8918-9dbafe572ac6",
        instrumentName = "vocal",
    )

    private val guitar = SongCapability(
        id = "cap-guitar",
        performerId = "p1",
        performerName = "Coralie",
        instrumentId = "f6b6f826-8eeb-5acc-9314-d2c096f780f6",
        instrumentName = "guitar",
    )

    private fun entry(vararg held: SongCapability) =
        PerformerLineUp("p1", "Coralie", held.toList())

    /** Click every clickable node currently on screen, tolerating a tree that moves under it. */
    private fun clickEverything() {
        val total = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        for (index in 0 until total) {
            runCatching {
                compose.onAllNodes(hasClickAction())[index].performClick()
                compose.waitForIdle()
            }
        }
    }

    private class Fixture(val holder: DatabaseHolder, val model: SessionViewModel)

    private fun fixture(suffix: String): Fixture {
        val name = "capability-sheet-$suffix.db".also { names += it }
        DatabaseFixtures.delete(context, name)
        val holder = DatabaseHolder(context, TEST_DEVICE, name)
        val model = EditingFixtures.session(holder)
        assertEquals(DatabaseState.Ready, model.databaseState.value)
        return Fixture(holder, model)
    }

    private fun firstSong(model: SessionViewModel): SongIdentity {
        val rows = model.state.value.pending
        assertTrue(rows.isNotEmpty(), "the sample data produced no rows to edit")
        return rows.first().identity()
    }

    /** The logger and the capability editor both idle — through the rule, the one wait here (F27 N6). */
    private fun settle(model: SessionViewModel) =
        compose.awaitUntil("the logger and the capability editor") {
            !model.capabilities.value.busy && !model.state.value.loading
        }

    private companion object {
        val SONG = SongIdentity(
            songId = "song-1",
            title = "Jolene",
            artistName = "Dolly Parton",
        )
    }
}
