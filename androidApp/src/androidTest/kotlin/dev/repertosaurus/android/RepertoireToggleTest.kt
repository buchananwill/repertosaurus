package dev.repertosaurus.android

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.android.DatabaseFixtures.count
import dev.repertosaurus.android.EditingFixtures.await
import dev.repertosaurus.android.EditingFixtures.onMain
import dev.repertosaurus.core.Ids
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.data.SampleData
import dev.repertosaurus.session.CapabilityCoordinator
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.VocalRange
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Repertoire route's state holder against a real database — repertoire-editing R3, R4, R7,
 * R8, R23c. The rules are `RepertoireCoordinator`'s and are JVM-tested in the shared core; what
 * is pinned here is the screen's half: the optimistic row, the fixed order, and what a refused
 * write does to both.
 */
@RunWith(AndroidJUnit4::class)
class RepertoireToggleTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableListOf<String>()

    @After
    fun cleanUp() {
        for (name in names) DatabaseFixtures.delete(context, name)
    }

    /** R4: a toggle writes the `song_performer` row, and a fresh read sees it held. */
    @Test
    fun aToggleWritesAndReadsBack() {
        val fixture = fixture("reads-back")
        val song = EditingFixtures.song(fixture.holder, "Shake It Off")

        open(fixture)
        toggle(fixture.model, song.id)

        assertEquals(
            1L,
            count(
                fixture.holder,
                "song_performer WHERE song_id = '${song.id}' AND performer_id = '${fixture.coralie}' " +
                    "AND instrument_id = '${SampleData.VOCAL}' AND deleted_at IS NULL",
            ),
            "the toggle did not write a live song_performer row",
        )

        // A second ViewModel, so nothing optimistic can stand in for the read.
        val reread = model(fixture.holder)
        onMain { reread.openList(fixture.coralie, SampleData.VOCAL) }
        awaitList(reread)
        val row = reread.state.value.list!!.rows.first { it.songId == song.id }
        assertTrue(row.held, "a fresh read does not see the toggled song as held")
        assertEquals(1, reread.state.value.list!!.heldCount)
    }

    /**
     * R4 / E6: toggling off and on again revives the same row and keeps its facts — lead, range
     * and notes, which the toggle list never edits (R6).
     */
    @Test
    fun togglingOffAndOnKeepsTheRowsFacts() {
        val fixture = fixture("lossless")
        val song = EditingFixtures.song(fixture.holder, "Valerie")

        open(fixture)
        toggle(fixture.model, song.id)

        val capabilities = CapabilityCoordinator(fixture.holder.repository)
        val row = capabilities.capabilities(song.id).single()
        assertTrue(
            capabilities.update(row.copy(isLead = true, vocalRange = VocalRange.HIGH, notes = "the high harmony")),
        )

        toggle(fixture.model, song.id)
        assertFalse(heldIn(fixture.model, song.id), "toggle off did not show as off")
        toggle(fixture.model, song.id)
        assertTrue(heldIn(fixture.model, song.id), "toggle on did not show as on")

        val revived = capabilities.capabilities(song.id).single()
        assertEquals(Ids.songPerformer(song.id, fixture.coralie, SampleData.VOCAL), revived.id)
        assertTrue(revived.isLead, "off-then-on lost the lead flag")
        assertEquals(VocalRange.HIGH, revived.vocalRange, "off-then-on lost the range")
        assertEquals("the high harmony", revived.notes, "off-then-on lost the notes")
        assertEquals(1L, count(fixture.holder, "song_performer"), "off-then-on inserted a second row")
    }

    /**
     * **R7: a row does not move on the tap that toggles it.** Held songs sort first, so a
     * re-sort after the toggle would lift *Shake It Off* from fifth to first — out from under the
     * user's thumb. The order is fixed at load and at search change, and the test proves the
     * toggle is one the comparator *would* move by changing the search afterwards.
     */
    @Test
    fun aToggledRowDoesNotMove() {
        val fixture = fixture("no-move")
        val song = EditingFixtures.song(fixture.holder, "Shake It Off")

        open(fixture)
        val before = visibleIds(fixture.model)
        val index = before.indexOf(song.id)
        assertTrue(index > 0, "the test needs a song that is not already first: $index")

        toggle(fixture.model, song.id)

        assertEquals(before, visibleIds(fixture.model), "a toggle moved a row (R7)")
        assertTrue(heldIn(fixture.model, song.id))

        // The search changing is R7's other moment the order is fixed: now the held song leads.
        onMain { fixture.model.setQuery("") }
        assertEquals(song.id, visibleIds(fixture.model).first(), "a search change did not re-fix the order")
    }

    /**
     * R8 + R23c: a toggle on a song removed elsewhere writes nothing, **rolls the row back**,
     * says so on the error channel, and the list is read again without it.
     */
    @Test
    fun aToggleOnARemovedSongRollsBackAndReports() {
        val fixture = fixture("song-gone")
        val song = EditingFixtures.song(fixture.holder, "Dakota")

        open(fixture)
        assertTrue(fixture.holder.repository.catalog.removeSong(song.id))

        onMain { fixture.model.toggle(song.id) }
        awaitList(fixture.model)
        await("the re-read") { fixture.model.state.value.list?.rows?.none { it.songId == song.id } == true }

        val state = fixture.model.state.value
        assertNotNull(state.error, "a refused toggle said nothing")
        assertTrue(state.error!!.contains("removed"), "the refusal must say why: ${state.error}")
        assertNull(state.message)
        assertEquals(0, state.list!!.heldCount, "the refused toggle stayed on")
        assertEquals(0L, count(fixture.holder, "song_performer"), "a toggle on a removed song wrote a row")
    }

    /**
     * **F18 B2: R8's rollback, on a row that survives the re-read.** The write fails — a trigger
     * aborts every `song_performer` insert — on a song that is still live, so nothing re-reads
     * the row away: the only thing that can put it back to "not held" is the rollback. The
     * refusal is on the error channel and nothing reached the file.
     */
    @Test
    fun aFailedToggleOnALiveSongRollsTheRowBack() {
        val fixture = fixture("rollback")
        val song = EditingFixtures.song(fixture.holder, "Shake It Off")
        open(fixture)
        SQLiteDatabase.openDatabase(fixture.holder.databaseFile().path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                "CREATE TRIGGER refuse_capability BEFORE INSERT ON song_performer " +
                    "BEGIN SELECT RAISE(ABORT, 'refused by the test'); END",
            )
        }

        onMain { fixture.model.toggle(song.id) }
        awaitList(fixture.model)
        await("the refusal") { fixture.model.state.value.error != null }

        val state = fixture.model.state.value
        assertTrue(state.list!!.rows.any { it.songId == song.id }, "the row must survive — this is not the re-read case")
        assertFalse(heldIn(fixture.model, song.id), "R8: a failed write must roll the row back")
        assertEquals(0, state.list!!.heldCount)
        assertTrue(state.error!!.startsWith("Could not change Shake It Off"), state.error)
        assertNull(state.message)
        assertEquals(0L, count(fixture.holder, "song_performer"), "the failed toggle wrote a row")
    }

    /**
     * **F18 B2: a second tap on a row whose write is in flight is ignored.** The write is held
     * in flight by a closed [Gate]; the second tap must not queue a second write, or the pair
     * would land on-then-off and the user's one tap would be undone behind their back.
     */
    @Test
    fun aSecondTapWhileTheWriteIsInFlightIsIgnored() {
        val gate = Gate()
        val fixture = fixture("in-flight", gate)
        val song = EditingFixtures.song(fixture.holder, "Shake It Off")
        open(fixture)

        gate.close()
        onMain { fixture.model.toggle(song.id) }
        assertTrue(song.id in fixture.model.state.value.list!!.inFlight, "the write is in flight")
        onMain { fixture.model.toggle(song.id) }
        gate.open()
        awaitList(fixture.model)

        assertTrue(heldIn(fixture.model, song.id), "the second tap undid the first")
        assertNull(fixture.model.state.value.error)
        assertEquals(
            1L,
            count(fixture.holder, "song_performer WHERE song_id = '${song.id}' AND deleted_at IS NULL"),
            "exactly the one tap's write landed",
        )
    }

    /**
     * **F18 N2: a toggle binds to the database that was current when it was tapped.** The
     * database is swapped (the holder lets go of it, as an import does) while the write waits; the
     * write is dropped rather than landing in the new file, the row rolls back, and it says so.
     */
    @Test
    fun aToggleQueuedAcrossADatabaseSwapIsDroppedAndSaid() {
        val gate = Gate()
        val fixture = fixture("swap", gate)
        val song = EditingFixtures.song(fixture.holder, "Shake It Off")
        open(fixture)

        gate.close()
        onMain { fixture.model.toggle(song.id) }
        fixture.holder.close()
        gate.open()
        awaitList(fixture.model)
        await("the refusal") { fixture.model.state.value.error != null }

        assertEquals(Messages.DATABASE_REPLACED, fixture.model.state.value.error)
        assertFalse(heldIn(fixture.model, song.id), "the dropped write rolled back")
        assertEquals(0L, count(fixture.holder, "song_performer"), "the write landed in the swapped-in database")
    }

    // ---- Harness -------------------------------------------------------------------------

    private class Fixture(val holder: DatabaseHolder, val model: RepertoireViewModel, val coralie: String)

    private fun fixture(suffix: String, io: CoroutineDispatcher = Dispatchers.IO): Fixture {
        val name = "repertoire-test-$suffix.db".also { names += it }
        val holder = EditingFixtures.holder(context, name)
        val coralie = EditingFixtures.performer(holder, "Coralie")
        return Fixture(holder, model(holder, io), coralie)
    }

    private fun model(holder: DatabaseHolder, io: CoroutineDispatcher = Dispatchers.IO): RepertoireViewModel {
        lateinit var model: RepertoireViewModel
        onMain { model = RepertoireViewModel(holder, io) }
        return model
    }

    /** R2: Coralie holds nothing yet, so this is the "+ role" path — every song, none held. */
    private fun open(fixture: Fixture) {
        onMain { fixture.model.openList(fixture.coralie, SampleData.VOCAL) }
        awaitList(fixture.model)
        assertEquals(8, fixture.model.state.value.list!!.rows.size, "the sample data has eight songs")
        assertEquals(0, fixture.model.state.value.list!!.heldCount)
    }

    private fun toggle(model: RepertoireViewModel, songId: String) {
        onMain { model.toggle(songId) }
        awaitList(model)
        assertNull(model.state.value.error, "the toggle was refused: ${model.state.value.error}")
    }

    private fun awaitList(model: RepertoireViewModel) {
        await("the toggle list") {
            val list = model.state.value.list
            list != null && !list.loading && list.inFlight.isEmpty()
        }
    }

    private fun visibleIds(model: RepertoireViewModel): List<String> =
        model.state.value.list!!.visible().map { it.songId }

    private fun heldIn(model: RepertoireViewModel, songId: String): Boolean =
        model.state.value.list!!.rows.first { it.songId == songId }.held
}
