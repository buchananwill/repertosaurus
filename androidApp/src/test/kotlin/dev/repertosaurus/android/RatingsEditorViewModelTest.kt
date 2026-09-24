package dev.repertosaurus.android

import dev.repertosaurus.core.RatingKind
import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.data.Part
import dev.repertosaurus.data.PartRatings
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.RatingsSong
import dev.repertosaurus.session.RatingsSource
import dev.repertosaurus.session.RatingsTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Safety review F20 B2: the ratings editor's queue and landings**, on the JVM, against a store the
 * test controls: it can fail a write, and it holds what was really written. Every expected value is a
 * literal, never one derived from the state's own functions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RatingsEditorViewModelTest {

    private val target = RatingsTarget("p-will", "i-guitar", "Will", "Guitar", RatingsSource.EnabledParts)
    private val dakota = "s-dakota"

    /** A store in memory: what is written, in order, and which writes fail. */
    private class FakeStore : RatingsAccess {
        val stored = mutableMapOf<Pair<String, RatingKind>, RatingLevel>()
        val written = mutableListOf<RatingLevel?>()
        val failing = ArrayDeque<Boolean>()
        var reads = 0

        override fun read(target: RatingsTarget): Pair<List<RatingsSong>, Map<String, PartRatings>> {
            reads++
            val ratings = stored.entries.groupBy({ it.key.first }) { it.key.second to it.value }
                .mapValues { (_, levels) -> PartRatings(levels.toMap()[RatingKind.PRIORITY], levels.toMap()[RatingKind.CONFIDENCE]) }
            return listOf(RatingsSong("s-dakota", "Dakota", "Stereophonics")) to ratings
        }

        override fun bindWrite(): (Part, RatingKind, RatingLevel?) -> Unit = { part, kind, level ->
            written += level
            if (failing.removeFirstOrNull() == true) throw IllegalStateException("disk full")
            val key = part.songId to kind
            if (level == null) stored.remove(key) else stored[key] = level
        }
    }

    private fun model(store: FakeStore, dispatcher: TestDispatcher): RatingsEditorViewModel {
        Dispatchers.setMain(dispatcher)
        return RatingsEditorViewModel(store, dispatcher)
    }

    private fun RatingsEditorViewModel.priority(): RatingLevel? = state.value!!.levelOf("s-dakota", RatingKind.PRIORITY)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun twoTapsBeforeTheFirstLandsEndOnTheSecond() = runTest {
        val store = FakeStore()
        val vm = model(store, StandardTestDispatcher(testScheduler))
        vm.open(target, after = {})
        advanceUntilIdle()

        vm.rate(dakota, RatingKind.PRIORITY, RatingLevel.SOMEWHAT)
        vm.rate(dakota, RatingKind.PRIORITY, RatingLevel.EXCEPTIONALLY)
        assertEquals(RatingLevel.EXCEPTIONALLY, vm.priority(), "the second tap shows at once")
        // Every state from here on, so a landing that flashes the first tap's level is seen.
        val shown = mutableListOf<RatingLevel?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.state.collect { shown += it?.levelOf(dakota, RatingKind.PRIORITY) }
        }
        advanceUntilIdle()

        assertTrue(shown.isNotEmpty() && shown.all { it == RatingLevel.EXCEPTIONALLY }, "a landing showed $shown")
        assertEquals(listOf<RatingLevel?>(RatingLevel.SOMEWHAT, RatingLevel.EXCEPTIONALLY), store.written, "in tap order")
        assertEquals(RatingLevel.EXCEPTIONALLY, store.stored[dakota to RatingKind.PRIORITY])
        assertEquals(RatingLevel.EXCEPTIONALLY, vm.priority(), "the first landing did not overwrite the second tap")
        assertNull(vm.state.value!!.error)
    }

    @Test
    fun setThenClearEndsUnrated() = runTest {
        val store = FakeStore()
        val vm = model(store, StandardTestDispatcher(testScheduler))
        vm.open(target, after = {})
        advanceUntilIdle()

        vm.rate(dakota, RatingKind.PRIORITY, RatingLevel.CERTAINLY)
        vm.rate(dakota, RatingKind.PRIORITY, null)
        advanceUntilIdle()

        assertEquals(listOf<RatingLevel?>(RatingLevel.CERTAINLY, null), store.written)
        assertNull(store.stored[dakota to RatingKind.PRIORITY])
        assertNull(vm.priority())
    }

    @Test
    fun aFailureThenAQueuedWriteShowsTheQueuedWrite() = runTest {
        val store = FakeStore().apply { stored[dakota to RatingKind.PRIORITY] = RatingLevel.CERTAINLY }
        val vm = model(store, StandardTestDispatcher(testScheduler))
        vm.open(target, after = {})
        advanceUntilIdle()
        store.failing += listOf(true, false)

        vm.rate(dakota, RatingKind.PRIORITY, RatingLevel.SOMEWHAT)
        vm.rate(dakota, RatingKind.PRIORITY, RatingLevel.NOT_AT_ALL)
        advanceUntilIdle()

        assertEquals(RatingLevel.NOT_AT_ALL, store.stored[dakota to RatingKind.PRIORITY])
        assertEquals(RatingLevel.NOT_AT_ALL, vm.priority(), "the failure did not roll back the later tap")
        assertEquals("Could not save that rating: disk full", vm.state.value!!.error)
    }

    @Test
    fun twoFailuresShowTheLevelLastStored() = runTest {
        val store = FakeStore().apply { stored[dakota to RatingKind.PRIORITY] = RatingLevel.CERTAINLY }
        val vm = model(store, StandardTestDispatcher(testScheduler))
        vm.open(target, after = {})
        advanceUntilIdle()
        store.failing += listOf(true, true)

        vm.rate(dakota, RatingKind.PRIORITY, RatingLevel.SOMEWHAT)
        vm.rate(dakota, RatingKind.PRIORITY, null)
        advanceUntilIdle()

        assertEquals(RatingLevel.CERTAINLY, store.stored[dakota to RatingKind.PRIORITY], "nothing was written")
        assertEquals(RatingLevel.CERTAINLY, vm.priority(), "the display matches the store")
        assertEquals("Could not save that rating: disk full", vm.state.value!!.error)
    }

    @Test
    fun aLandingAfterCloseOrReopenIsDropped() = runTest {
        val store = FakeStore()
        val vm = model(store, StandardTestDispatcher(testScheduler))
        vm.open(target, after = {})
        advanceUntilIdle()

        vm.rate(dakota, RatingKind.PRIORITY, RatingLevel.SOMEWHAT)
        vm.close()
        advanceUntilIdle()
        assertNull(vm.state.value, "a landing after close opened nothing")
        assertEquals(RatingLevel.SOMEWHAT, store.stored[dakota to RatingKind.PRIORITY], "the tapped write still ran")

        // Reopened while a write is queued: the new editor reads after the write and shows the store.
        vm.open(target, after = {})
        advanceUntilIdle()
        store.failing += true
        vm.rate(dakota, RatingKind.PRIORITY, RatingLevel.EXCEPTIONALLY)
        vm.close()
        vm.open(target, after = {})
        advanceUntilIdle()
        assertEquals(RatingLevel.SOMEWHAT, vm.priority(), "the old editor's failed landing did not reach the new one")
        assertEquals(0L, vm.state.value!!.lastTap)
        assertNull(vm.state.value!!.error)
    }

    /** T1: the Repertoire entry's read waits for the toggle queue it is given. */
    @Test
    fun openReadsOnlyAfterTheToggleQueueDrains() = runTest {
        val store = FakeStore()
        val vm = model(store, StandardTestDispatcher(testScheduler))
        val toggles = CompletableDeferred<Unit>()

        vm.open(target, after = { toggles.await() })
        advanceUntilIdle()
        assertEquals(0, store.reads, "read before the toggle landed")
        assertTrue(vm.state.value!!.loading)

        toggles.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, store.reads)
        assertEquals(listOf("s-dakota"), vm.state.value!!.songs.map { it.songId })
    }

    /** F20 B1 across kinds: a failure on priority leaves confidence's tap alone. */
    @Test
    fun theKindsLandIndependently() = runTest {
        val store = FakeStore()
        val vm = model(store, StandardTestDispatcher(testScheduler))
        vm.open(target, after = {})
        advanceUntilIdle()
        store.failing += listOf(true, false)

        vm.rate(dakota, RatingKind.PRIORITY, RatingLevel.SOMEWHAT)
        vm.rate(dakota, RatingKind.CONFIDENCE, RatingLevel.CERTAINLY)
        advanceUntilIdle()

        assertNull(vm.priority())
        assertEquals(RatingLevel.CERTAINLY, vm.state.value!!.levelOf(dakota, RatingKind.CONFIDENCE))
        assertEquals(Messages.ratingWriteFailed(IllegalStateException("disk full")), vm.state.value!!.error)
    }
}
