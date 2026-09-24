package dev.repertosaurus.session

import dev.repertosaurus.data.InsertIgnored
import dev.repertosaurus.data.JunctionWrite
import dev.repertosaurus.data.RepertosaurusRepository.HeldSong
import dev.repertosaurus.data.Resolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Repertoire route's pure state — R7 and R8's rules, moved into the core by style review
 * F17 B1 and tested here on the JVM rather than only through a device.
 */
class RepertoireStateTest {

    // One letter page (triage T3, T5a), so R7's order is seen whole on it.
    private val rows = listOf(
        HeldSong("s-valerie", "Valerie", "The Zutons", held = false),
        HeldSong("s-vienna", "Vienna", "Ultravox", held = true),
        HeldSong("s-vertigo", "Vertigo", "U2", held = false),
    )

    private fun loaded(): ToggleList = ToggleList("p", "i", ticket = 1L, loading = true).reread(rows)

    /** R7 at load: held first, then title. */
    @Test
    fun aLoadFixesTheOrderHeldFirst() {
        assertEquals(listOf("s-vienna", "s-valerie", "s-vertigo"), loaded().page.order)
        assertFalse(loaded().loading)
    }

    /** **R7: a toggle changes the row's flag and never its position.** */
    @Test
    fun aToggleDoesNotMoveTheRow() {
        val list = loaded()
        val toggled = list.toggling("s-valerie", held = true)

        assertEquals(list.page, toggled.page, "a toggle re-sorted the list")
        assertTrue(toggled.visible().single { it.songId == "s-valerie" }.held)
        assertEquals(setOf("s-valerie"), toggled.inFlight)
        assertEquals(2, toggled.heldCount)
    }

    /** R7's second moment: a search change fixes the order again, now with the new flag. */
    @Test
    fun aSearchChangeReFixesTheOrder() {
        val toggled = loaded().toggling("s-valerie", held = true).landed("s-valerie", rollBackTo = null)
        assertEquals(listOf("s-valerie", "s-vienna", "s-vertigo"), toggled.withQuery("").page.order)
        assertEquals(listOf("s-valerie"), toggled.withQuery("zutons").page.order)
    }

    /** **R8: a refused or failed write restores the row**, and clears its in-flight mark. */
    @Test
    fun aRefusedWriteRollsTheRowBack() {
        val list = loaded().toggling("s-valerie", held = true)

        val rolledBack = list.landed("s-valerie", rollBackTo = false)
        assertFalse(rolledBack.rows.single { it.songId == "s-valerie" }.held)
        assertTrue(rolledBack.inFlight.isEmpty())

        val stood = list.landed("s-valerie", rollBackTo = null)
        assertTrue(stood.rows.single { it.songId == "s-valerie" }.held, "a write that landed keeps its flag")
    }

    /** R8: a re-read cannot know what a queued write will do, so an in-flight row keeps its optimistic flag. */
    @Test
    fun aRereadKeepsAnInFlightRowsOptimisticFlag() {
        val list = loaded().toggling("s-valerie", held = true)
        val reread = list.reread(rows)
        assertTrue(reread.rows.single { it.songId == "s-valerie" }.held)
        assertFalse(reread.rows.single { it.songId == "s-vertigo" }.held)
    }

    /** A result for a list that has since been closed or reopened never lands. */
    @Test
    fun aResultForAnotherTicketIsDropped() {
        val state = RepertoireState(list = loaded())
        assertSame(state, state.withList(ticket = 2L) { it.toggling("s-valerie", true) })
        assertTrue(state.withList(ticket = 1L) { it.toggling("s-valerie", true) }.list!!.inFlight.isNotEmpty())
    }

    /**
     * **F22 B4: R8's landing, in the core.** A write that landed leaves the optimistic flag; a
     * refusal (`SongGone`) rolls it back and applies the re-read that came with it; a thrown
     * failure — an ignored insert (R4a) included — rolls it back and is worded by the caller.
     */
    @Test
    fun aToggleLandsRollsBackOrRereads() {
        val valerie = rows.first { it.songId == "s-valerie" }
        val state = RepertoireState(list = loaded().toggling("s-valerie", held = true))

        val wrote = state.withToggle(1L, valerie, wanted = true, Result.success(JunctionWrite.Added("x", Resolution.CREATED) to null))
        assertTrue(wrote.list!!.rows.single { it.songId == "s-valerie" }.held)
        assertNull(wrote.error)

        val fresh = rows.filterNot { it.songId == "s-valerie" }
        val gone = state.withToggle(1L, valerie, wanted = true, Result.success(JunctionWrite.SongGone("x") to fresh))
        assertEquals(Messages.toggleSongGone("Valerie — The Zutons"), gone.error)
        assertEquals(listOf("s-vienna", "s-vertigo"), gone.list!!.page.order, "the re-read landed")

        val failed = state.withToggle(1L, valerie, wanted = true, Result.failure(InsertIgnored())) { label, _ -> "no: $label" }
        assertFalse(failed.list!!.rows.single { it.songId == "s-valerie" }.held, "rolled back")
        assertTrue(failed.list!!.inFlight.isEmpty())
        assertEquals("no: Valerie — The Zutons", failed.error)
    }

    /** A failed read of the open list stops its spinner and says so on the error channel. */
    @Test
    fun aFailedReadSaysSo() {
        val state = RepertoireState(list = ToggleList("p", "i", ticket = 1L, loading = true))
        val read = state.withRead(1L, Result.failure(IllegalStateException("disk")))
        assertFalse(read.list!!.loading)
        assertEquals(Messages.couldNot("read the songs", IllegalStateException("disk")), read.error)
    }

    /** `PerformerRoles.instrument` finds a held role or an unheld instrument, and nothing else. */
    @Test
    fun aPerformersInstrumentIsFoundHeldOrNot() {
        val vocal = InstrumentChip("i-vocal", "vocal")
        val keys = InstrumentChip("i-keys", "keys")
        val roles = PerformerRoles("p", "Coralie", roles = listOf(vocal), unheldInstruments = listOf(keys))
        assertEquals(vocal, roles.instrument("i-vocal"))
        assertEquals(keys, roles.instrument("i-keys"))
        assertNull(roles.instrument("i-removed"))
        assertEquals(roles, RepertoireState(performers = listOf(roles)).performer("p"))
    }
}
