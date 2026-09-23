package dev.repertosaurus.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.android.DatabaseFixtures.count
import dev.repertosaurus.android.EditingFixtures.onMain
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.data.DatabaseState
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.PerformerLineUp
import dev.repertosaurus.session.SongIdentity
import dev.repertosaurus.session.VocalRange
import dev.repertosaurus.session.identity
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The song-capability editor's state holder, against a real database on a real device —
 * editing E1, E3, E6, E7, E8, E10.
 *
 * **E1 is the reason this file leads with it.** The editor is one long-press from the tap that
 * *is* a practice event, and the two must never blur. The proof here is a **count of
 * `practice_event` before and after a full editing session**, read straight out of the file
 * rather than inferred from the code — a test that asserted "no call to `log`" would agree with
 * its own author and would not notice a second path opening later.
 *
 * The assertions are about the ViewModel's observable state and the rows on disk. The rules
 * themselves live in `CapabilityCoordinator` and are tested on the JVM without a device.
 */
@RunWith(AndroidJUnit4::class)
class SongCapabilityEditingTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val names = mutableListOf<String>()

    @After
    fun cleanUp() {
        for (name in names) DatabaseFixtures.delete(context, name)
    }

    /**
     * E1: **recording a capability is not logging practice.** Add two rows, edit one, remove
     * one, re-add it — and `practice_event` must hold exactly what it held before.
     */
    @Test
    fun aFullEditingSessionWritesNoPracticeEvent() {
        val fixture = fixture("no-practice")
        val song = firstSong(fixture.model)
        val before = practiceEventCount(fixture.holder)

        openOn(fixture.model, song)
        add(fixture.model, "Coralie", "vocal")
        add(fixture.model, "Coralie", "backing vocal")

        val backing = capability(fixture.model, "Coralie", "backing vocal")
        onMain { fixture.model.updateCapability(backing.copy(isLead = true, vocalRange = VocalRange.HIGH)) }
        awaitIdle(fixture.model)

        onMain { fixture.model.removeCapability(capability(fixture.model, "Coralie", "vocal")) }
        awaitIdle(fixture.model)
        add(fixture.model, "Coralie", "vocal")

        assertEquals(before, practiceEventCount(fixture.holder), "the editor wrote a practice event")
        assertEquals(0L, count(fixture.holder, "practice_event_void"), "the editor voided an event")
    }

    /**
     * E3 and E10: one row per `(song, performer, instrument)`, **grouped by performer**. Coralie
     * on `vocal` and on `backing vocal` is one entry with two chips, not two entries — the shape
     * the user's own example asks for.
     */
    @Test
    fun onePerformerOnTwoInstrumentsIsOneEntryWithTwoCapabilities() {
        val fixture = fixture("line-up")
        val song = firstSong(fixture.model)

        openOn(fixture.model, song)
        add(fixture.model, "Coralie", "vocal")
        add(fixture.model, "Coralie", "backing vocal")
        add(fixture.model, "Will", "guitar")

        val lineUp = fixture.model.capabilities.value.lineUp
        assertEquals(2, lineUp.size, "one entry per performer: ${lineUp.map { it.performerName }}")

        val coralie = entry(lineUp, "Coralie")
        assertEquals(
            listOf("vocal", "backing vocal"),
            coralie.capabilities.map { it.instrumentName },
            "chip order follows the chip row (decision 18)",
        )
        assertEquals(1, entry(lineUp, "Will").capabilities.size)
    }

    /**
     * E6 and E7: removal is a **tombstone**, and re-adding the same triple derives the same
     * primary key, so it **revives that row rather than inserting a second**. This is the part of
     * the arc most likely to be got wrong, because a plain insert throws and an
     * `INSERT OR REPLACE` silently discards the row's notes.
     */
    @Test
    fun removingIsATombstoneAndReAddingRevivesTheSameRow() {
        val fixture = fixture("revive")
        val song = firstSong(fixture.model)

        openOn(fixture.model, song)
        add(fixture.model, "Coralie", "vocal")
        val added = capability(fixture.model, "Coralie", "vocal")

        onMain { fixture.model.removeCapability(added) }
        awaitIdle(fixture.model)
        assertTrue(fixture.model.capabilities.value.lineUp.isEmpty(), "the removed row is still listed")
        assertEquals(1L, count(fixture.holder, "song_performer"), "remove was a DELETE, not a tombstone")

        add(fixture.model, "Coralie", "vocal")
        val revived = capability(fixture.model, "Coralie", "vocal")

        assertEquals(added.id, revived.id, "the re-add derived a different id")
        assertEquals(1L, count(fixture.holder, "song_performer"), "the re-add inserted a second row")
    }

    /**
     * E8: the range is meaningful only for a voice, and the core enforces it on the instrument's
     * derived **id** rather than its name. The editor consults [dev.repertosaurus.session
     * .SongCapability.rangeApplies] to decide whether to draw the control at all; this pins that
     * the flag says what the editor needs and that the core refuses the write rather than
     * dropping it silently.
     */
    @Test
    fun theRangeAppliesToVoicesOnlyAndTheCoreRefusesItElsewhere() {
        val fixture = fixture("range")
        val song = firstSong(fixture.model)

        openOn(fixture.model, song)
        add(fixture.model, "Coralie", "vocal")
        add(fixture.model, "Coralie", "backing vocal")
        add(fixture.model, "Coralie", "guitar")

        assertTrue(capability(fixture.model, "Coralie", "vocal").rangeApplies)
        assertTrue(capability(fixture.model, "Coralie", "backing vocal").rangeApplies)
        assertFalse(capability(fixture.model, "Coralie", "guitar").rangeApplies)

        val guitar = capability(fixture.model, "Coralie", "guitar")
        onMain { fixture.model.updateCapability(guitar.copy(vocalRange = VocalRange.LOW)) }
        awaitIdle(fixture.model)

        // **E43, and the reason this assertion changed.** It used to read `message != null`,
        // which is what the defect produced too: success and failure shared one field and one
        // colour, so the test agreed with the bug rather than catching it. A refusal now lands
        // on its own channel and the confirmation channel must be empty, which is the only
        // form of the assertion a screen can render differently.
        assertNotNull(
            fixture.model.capabilities.value.error,
            "a range on a guitar row was accepted in silence",
        )
        assertTrue(
            fixture.model.capabilities.value.error.orEmpty().startsWith("That did not work"),
            "the refusal must say it is one",
        )
        assertNull(
            fixture.model.capabilities.value.message,
            "a refused write left a confirmation on screen beside it",
        )
        assertNull(
            capability(fixture.model, "Coralie", "guitar").vocalRange,
            "the editor kept a range the core refused",
        )
        assertEquals(
            0L,
            count(fixture.holder, "song_performer WHERE vocal_range IS NOT NULL"),
            "a range on a guitar row reached the database",
        )
    }

    /**
     * **E44: the add form clears on confirmed success and never on dispatch.**
     *
     * The composable cannot see the write, so the one fact it needs is a count of adds the
     * ViewModel has seen **land** — and it must not move for anything else. A failed write used
     * to leave the user with two empty boxes, a message they could read as success, and no
     * record of what they had typed.
     */
    @Test
    fun onlyAConfirmedAddMovesTheCounterTheFormClearsOn() {
        val fixture = fixture("clears-on-success")
        val song = firstSong(fixture.model)

        openOn(fixture.model, song)
        assertEquals(0L, fixture.model.capabilities.value.addsCommitted)

        add(fixture.model, "Coralie", "vocal")
        assertEquals(1L, fixture.model.capabilities.value.addsCommitted)
        assertNotNull(fixture.model.capabilities.value.message, "a confirmed add says so")
        assertNull(fixture.model.capabilities.value.error)

        // An edit is not an add, and neither is a removal.
        val added = capability(fixture.model, "Coralie", "vocal")
        onMain { fixture.model.updateCapability(added.copy(isLead = true)) }
        awaitIdle(fixture.model)
        assertEquals(1L, fixture.model.capabilities.value.addsCommitted)

        onMain { fixture.model.removeCapability(capability(fixture.model, "Coralie", "vocal")) }
        awaitIdle(fixture.model)
        assertEquals(1L, fixture.model.capabilities.value.addsCommitted)

        // A refused write must not move it either — that is the whole point of E44.
        add(fixture.model, "Coralie", "guitar")
        assertEquals(2L, fixture.model.capabilities.value.addsCommitted)
        val guitar = capability(fixture.model, "Coralie", "guitar")
        onMain { fixture.model.updateCapability(guitar.copy(vocalRange = VocalRange.HIGH)) }
        awaitIdle(fixture.model)
        assertEquals(2L, fixture.model.capabilities.value.addsCommitted)
        assertNotNull(fixture.model.capabilities.value.error)
    }

    /**
     * **E45: a result that is not the newest is dropped**, including after close-then-reopen on
     * the **same song** — which the old guard could not see, because it compared song identity
     * and identity is not freshness.
     */
    @Test
    fun aStaleReadCannotLandOnAReopenedEditor() {
        val fixture = fixture("sequence")
        val song = firstSong(fixture.model)

        openOn(fixture.model, song)
        add(fixture.model, "Coralie", "vocal")

        // Close and reopen the *same* song without waiting: the first read is still in flight
        // and carries a sequence the reopened state no longer holds.
        onMain {
            fixture.model.openCapabilities(song)
            fixture.model.closeCapabilities()
            fixture.model.openCapabilities(song)
        }
        awaitIdle(fixture.model)

        assertEquals(song.songId, fixture.model.capabilities.value.song?.songId)
        assertEquals(
            1,
            fixture.model.capabilities.value.lineUp.size,
            "the reopened editor must show the line-up as it is, once",
        )
        assertNull(fixture.model.capabilities.value.error)
    }

    /**
     * **R23c on the capability path: a write to a removed song is refused, and says so.** The
     * editor is now reachable from the Songs detail too (R18), so a song removed underneath the
     * open sheet is a real state. The add must land on the error channel, not claim "Added", and
     * must not move E44's counter — or the form would clear over a write that never happened.
     */
    @Test
    fun anAddOnARemovedSongIsRefusedOnTheErrorChannel() {
        val fixture = fixture("song-gone")
        val song = firstSong(fixture.model)

        openOn(fixture.model, song)
        assertTrue(fixture.holder.repository.catalog.removeSong(song.songId))
        add(fixture.model, "Coralie", "vocal")

        val state = fixture.model.capabilities.value
        assertEquals(Messages.SONG_GONE, state.error)
        assertNull(state.message, "a refused add left a confirmation beside the refusal")
        assertEquals(0L, state.addsCommitted, "a refused add moved the counter the form clears on")
        assertEquals(0L, count(fixture.holder, "song_performer"), "an add on a removed song wrote a row")
    }

    /**
     * R23c / R23d: a removal that removed nothing — the row was already gone — says so rather than
     * "Removed", and an edit to that row says nothing was saved, on the error channel.
     */
    @Test
    fun aNoOpRemovalAndANoOpEditAreSaidTruthfully() {
        val fixture = fixture("no-op")
        val song = firstSong(fixture.model)

        openOn(fixture.model, song)
        add(fixture.model, "Coralie", "vocal")
        val row = capability(fixture.model, "Coralie", "vocal")

        onMain { fixture.model.removeCapability(row) }
        awaitIdle(fixture.model)
        assertEquals("Removed Coralie", fixture.model.capabilities.value.message)

        onMain { fixture.model.removeCapability(row) }
        awaitIdle(fixture.model)
        val again = fixture.model.capabilities.value
        assertTrue(
            again.message.orEmpty().contains("already removed"),
            "a removal that wrote nothing claimed to have removed: ${again.message}",
        )
        assertNull(again.error)

        onMain { fixture.model.updateCapability(row.copy(isLead = true)) }
        awaitIdle(fixture.model)
        val edited = fixture.model.capabilities.value
        assertEquals(Messages.NOTHING_WRITTEN, edited.error)
        assertNull(edited.message, "an edit that saved nothing said Saved")
    }

    /** Closing the editor drops the song, which is how the sheet knows it is shut. */
    @Test
    fun closingTheEditorClearsItsState() {
        val fixture = fixture("close")
        val song = firstSong(fixture.model)

        openOn(fixture.model, song)
        add(fixture.model, "Coralie", "vocal")

        onMain { fixture.model.closeCapabilities() }

        assertNull(fixture.model.capabilities.value.song)
        assertTrue(fixture.model.capabilities.value.lineUp.isEmpty())
    }

    // ---- Harness -------------------------------------------------------------------------

    private class Fixture(val holder: DatabaseHolder, val model: SessionViewModel)

    /**
     * A fresh database per test, seeded by the app's own boot path — eight sample songs and
     * nine practice events, and **no `song_performer` rows at all**, which is the state every
     * test here starts from.
     */
    private fun fixture(suffix: String): Fixture {
        val name = "capability-test-$suffix.db".also { names += it }
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

    private fun openOn(model: SessionViewModel, song: SongIdentity) {
        onMain { model.openCapabilities(song) }
        awaitIdle(model)
        assertTrue(
            model.capabilities.value.lineUp.isEmpty(),
            "the sample data is meant to carry no capability rows",
        )
    }

    private fun add(model: SessionViewModel, performer: String, instrument: String) {
        onMain { model.addCapability(performer, instrument) }
        awaitIdle(model)
    }

    private fun entry(lineUp: List<PerformerLineUp>, performer: String): PerformerLineUp =
        lineUp.firstOrNull { it.performerName == performer }
            ?: error("$performer is not in the line-up: ${lineUp.map { it.performerName }}")

    private fun capability(model: SessionViewModel, performer: String, instrument: String) =
        entry(model.capabilities.value.lineUp, performer)
            .capabilities.firstOrNull { it.instrumentName == instrument }
            ?: error("$performer holds no $instrument row")

    private fun awaitIdle(model: SessionViewModel) = EditingFixtures.awaitSession(model)

    private fun practiceEventCount(holder: DatabaseHolder): Long = count(holder, "practice_event")
}
