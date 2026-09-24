package dev.repertosaurus.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.session.InMemoryTimerStore
import dev.repertosaurus.android.EditingFixtures.await
import dev.repertosaurus.android.EditingFixtures.onMain
import dev.repertosaurus.data.SampleData
import dev.repertosaurus.data.SongCatalog
import dev.repertosaurus.session.InMemorySessionPreferences
import dev.repertosaurus.session.SongIdentity
import dev.repertosaurus.session.ViewCoordinator
import dev.repertosaurus.session.ViewFilter
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **R26 against R8's queue** (safety review F18 B1): returning to the logger reloads it, and the
 * reload must **wait for the route's queued writes** — a toggle tapped just before back that has
 * not run yet would otherwise be missing from the reloaded list, with nothing to reload it again.
 *
 * The toggle is held in flight by a closed [Gate] while the logger is asked to reload, so the
 * order is forced rather than hoped for: a reload that did not wait reads the database before the
 * write and settles on the old list.
 */
@RunWith(AndroidJUnit4::class)
class ReloadAfterWritesTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableListOf<String>()

    @After
    fun cleanUp() {
        for (name in names) DatabaseFixtures.delete(context, name)
    }

    @Test
    fun theLoggersReloadWaitsForTheRoutesQueuedToggle() {
        val name = "reload-after-writes.db".also { names += it }
        val holder = EditingFixtures.holder(context, name)
        val coralie = EditingFixtures.performer(holder, "Coralie")
        val shake = EditingFixtures.song(holder, "Shake It Off")
        val preferences = InMemorySessionPreferences()
        // V20: with no home set, the logger opens on the first saved View — this one, which
        // holds nothing until Coralie is given a song on vocal.
        ViewCoordinator(holder.repository, preferences).createView(
            name = "Coralie sings",
            filter = ViewFilter(performerId = coralie, instrumentId = SampleData.VOCAL),
            practiceInstrumentId = SampleData.VOCAL,
        )

        val gate = Gate()
        lateinit var session: SessionViewModel
        lateinit var repertoire: RepertoireViewModel
        onMain {
            session = SessionViewModel(holder, preferences, TEST_DEVICE, InMemoryTimerStore())
            repertoire = RepertoireViewModel(holder, gate)
        }
        await("the logger") { !session.state.value.loading && session.state.value.view != null }
        assertTrue(session.state.value.pending.isEmpty(), "the filtered View starts empty")

        onMain { repertoire.openList(coralie, SampleData.VOCAL) }
        await("the toggle list") { repertoire.state.value.list?.loading == false }

        // The toggle is tapped and held in flight; then the user goes back to the logger.
        gate.close()
        onMain { repertoire.toggle(shake.id) }
        onMain { session.reloadAfter(repertoire::awaitIdle) }

        // Give a reload that did not wait every chance to run, and to finish, on the old data.
        Thread.sleep(RACE_WINDOW_MS)
        gate.open()

        await("the toggle") { repertoire.state.value.list?.inFlight?.isEmpty() == true }
        await("the logger's reload") { !session.state.value.loading }
        assertTrue(
            session.state.value.pending.any { it.songId == shake.id },
            "R26 / F18 B1: the logger reloaded before the queued toggle landed, and kept the old list",
        )
    }

    /**
     * **F28 N7: returning to the logger clears a pending undo.** A tap logged on the typo, then the
     * typo merged away on the Songs route: the logged event is voided and copied to the survivor, so
     * the offer's undo would void an event that is already out of every history and leave the copy
     * standing — an undo that silently does nothing. The reload takes the offer away.
     */
    @Test
    fun returningAfterAMergeClearsThePendingUndo() {
        val name = "reload-clears-undo.db".also { names += it }
        val holder = EditingFixtures.holder(context, name)
        holder.repository.catalog.addSong("Shake If Off", SongCatalog.LookupChoice.Typed("Taylor Swift"))
        val itOff = EditingFixtures.song(holder, "Shake It Off")
        val typo = EditingFixtures.song(holder, "Shake If Off")
        val carriedBefore = holder.repository.timesPractised(itOff.id)
        val session = EditingFixtures.session(holder)
        assertNotNull(session.state.value.selectedInstrumentId, "the logger has an instrument to log on")

        onMain { session.log(typo.id) }
        await("the log") { holder.repository.timesPractised(typo.id) == 1L }
        assertNotNull(session.state.value.undo, "the tap offers an undo")

        lateinit var songs: SongsViewModel
        onMain {
            songs = SongsViewModel(holder)
            songs.load()
            songs.openDetail(itOff.id)
        }
        await("the detail") { songs.state.value.detail?.canMerge == true }
        onMain {
            songs.startMerge()
            songs.chooseMergeWith(typo.id)
        }
        await("the preview") { songs.merge.value?.plan != null }
        onMain {
            songs.confirmMerge()
            session.reloadAfter(songs::awaitIdle)
        }
        await("the merge and the reload") { songs.merge.value == null && !session.state.value.loading }

        assertEquals(carriedBefore + 1, holder.repository.timesPractised(itOff.id), "the tap was carried to the survivor")
        assertNull(session.state.value.undo, "F28 N7: the undo offer survived the reload after a merge")
    }

    /**
     * **F30 #4: a plain reload keeps a pending undo.** A capability edit from the logger reloads it
     * (a line-up is what a View's eligibility reads), but nothing on that path can merge a song away,
     * and undo is on the highest-frequency path: a one-tap log must stay one-tap undoable. Only the
     * return from a route ([SessionViewModel.reloadAfter]) takes the offer away.
     */
    @Test
    fun aCapabilityEditsReloadKeepsThePendingUndo() {
        val name = "reload-keeps-undo.db".also { names += it }
        val holder = EditingFixtures.holder(context, name)
        val shake = EditingFixtures.song(holder, "Shake It Off")
        val practisedBefore = holder.repository.timesPractised(shake.id)
        val session = EditingFixtures.session(holder)
        assertNotNull(session.state.value.selectedInstrumentId, "the logger has an instrument to log on")

        onMain { session.log(shake.id) }
        await("the log") { holder.repository.timesPractised(shake.id) == practisedBefore + 1 }
        val offer = assertNotNull(session.state.value.undo, "the tap offers an undo")

        onMain {
            session.openCapabilities(SongIdentity(shake.id, shake.title, null))
            session.addCapability("Coralie", "vocal")
        }
        await("the capability write and the reload after it") {
            session.capabilities.value.revision == 1L &&
                !session.capabilities.value.busy &&
                !session.state.value.loading
        }
        assertTrue(session.capabilities.value.lineUp.isNotEmpty(), "the capability edit wrote a line-up")

        assertEquals(offer, session.state.value.undo, "F30 #4: a plain reload took the pending undo away")
    }

    private companion object {
        const val RACE_WINDOW_MS: Long = 1_500L
    }
}
