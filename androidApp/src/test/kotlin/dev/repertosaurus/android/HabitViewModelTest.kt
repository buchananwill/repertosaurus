package dev.repertosaurus.android

import dev.repertosaurus.habit.HabitCard
import dev.repertosaurus.habit.HabitStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * **Safety review F23 N4: the scorecards' loads**, on the JVM, against a read the test controls.
 * Every state the ViewModel emits is recorded, not only the last (journal session 11, D62), so a
 * stale card or error that flashes and is then replaced is still caught.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HabitViewModelTest {

    /** One emitted state, reduced to what the screen shows: whose card, loading, and the error. */
    private data class Shown(val card: String, val loading: Boolean, val error: String?)

    private class FakeRead {
        val asked = mutableListOf<String?>()
        val failing = ArrayDeque<Boolean>()

        fun read(instrumentId: String?): HabitCard {
            asked += instrumentId
            if (failing.removeFirstOrNull() == true) throw IllegalStateException("disk full")
            return HabitStats.build(emptyMap(), LocalDate.parse("2026-09-24"), instrumentId)
        }
    }

    private fun TestScope.model(read: FakeRead): Pair<HabitViewModel, MutableList<Shown>> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val vm = HabitViewModel(read::read, dispatcher)
        val shown = mutableListOf<Shown>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.state.collect { state ->
                shown += Shown(state.card?.let { it.instrumentId ?: "all" } ?: "none", state.loading, state.error)
            }
        }
        return vm to shown
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Two scopes asked for before either lands: both reads run in order, and only the second lands. */
    @Test
    fun queuedLoadsLandOnlyTheLatest() = runTest {
        val read = FakeRead()
        val (vm, shown) = model(read)

        vm.load(null)
        vm.load("i-vocal")
        advanceUntilIdle()

        assertEquals(listOf(null, "i-vocal"), read.asked)
        assertEquals(
            listOf(Shown("none", false, null), Shown("none", true, null), Shown("i-vocal", false, null)),
            shown,
        )
    }

    /** A failed read says so on the error channel and keeps the card it had; the next load clears it. */
    @Test
    fun aFailureShowsItsErrorAndTheNextLoadClearsIt() = runTest {
        val read = FakeRead()
        val (vm, shown) = model(read)
        vm.load(null)
        advanceUntilIdle()

        read.failing += true
        vm.load(null)
        advanceUntilIdle()
        vm.load(null)
        advanceUntilIdle()

        val error = "Could not load your practice days: disk full"
        assertEquals(
            listOf(
                Shown("none", false, null), Shown("none", true, null), Shown("all", false, null),
                Shown("all", true, null), Shown("all", false, error),
                Shown("all", true, null), Shown("all", false, null),
            ),
            shown,
        )
    }

    /** A superseded load's failure is dropped with it: the error never shows. */
    @Test
    fun aSupersededFailureIsNeverShown() = runTest {
        val read = FakeRead()
        val (vm, shown) = model(read)
        read.failing += true

        vm.load(null)
        vm.load("i-vocal")
        advanceUntilIdle()

        assertEquals(
            listOf(Shown("none", false, null), Shown("none", true, null), Shown("i-vocal", false, null)),
            shown,
        )
    }

    /** F23 N1: a card built for one scope is not offered under another. */
    @Test
    fun aCardIsOfferedOnlyForTheScopeItWasBuiltFor() = runTest {
        val read = FakeRead()
        val (vm, _) = model(read)
        vm.load("i-vocal")
        advanceUntilIdle()

        assertEquals("i-vocal", vm.state.value.cardFor("i-vocal")?.instrumentId)
        assertNull(vm.state.value.cardFor(null))
        assertNull(vm.state.value.cardFor("i-guitar"))
    }
}
