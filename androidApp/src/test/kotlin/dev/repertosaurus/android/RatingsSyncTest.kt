package dev.repertosaurus.android

import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.data.PartRatings
import dev.repertosaurus.data.RepertosaurusRepository
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.ResolvedPart
import dev.repertosaurus.session.SessionOrder
import dev.repertosaurus.session.SessionRow
import dev.repertosaurus.session.SessionState
import dev.repertosaurus.session.SessionView
import dev.repertosaurus.session.resolvedPart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * **triage T9's re-read, on the JVM** (safety review F35 B1, B2): [RatingsSync] over a state the test
 * owns, with every ratings read held until the test lets it land, or fails it. `SessionViewModel` wires
 * it with the same state flow, its `withContext(io)` read and `_state.update`.
 *
 * Expected values are literals: Will rates Dakota 3 and Valerie 1; Coralie rates them the other way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RatingsSyncTest {

    private val will = RepertosaurusRepository.Performer("p-will", "Will")
    private val coralie = RepertosaurusRepository.Performer("p-coralie", "Coralie")
    private val willVocal = ResolvedPart("p-will", "Will", "i-vocal")
    private val coralieVocal = ResolvedPart("p-coralie", "Coralie", "i-vocal")

    private val ratingsOf = mapOf(
        "p-will" to mapOf("s-dakota" to rated(RatingLevel.EXCEPTIONALLY), "s-valerie" to rated(RatingLevel.SOMEWHAT)),
        "p-coralie" to mapOf("s-dakota" to rated(RatingLevel.SOMEWHAT), "s-valerie" to rated(RatingLevel.EXCEPTIONALLY)),
    )

    private fun rated(priority: RatingLevel) = PartRatings(priority, null)

    /** Every read the sync asks for, in order, each held until the test completes it. */
    private class HeldReads {
        val asked = mutableListOf<Pair<ResolvedPart, CompletableDeferred<Map<String, PartRatings>>>>()

        suspend fun read(part: ResolvedPart): Map<String, PartRatings> {
            val answer = CompletableDeferred<Map<String, PartRatings>>()
            asked += part to answer
            return answer.await()
        }
    }

    private val loaded = SessionState(
        view = SessionView.unsaved("i-vocal", SessionOrder.TRIAGE_PRIORITY),
        rows = listOf(
            SessionRow("s-chelsea", "Chelsea Dagger", "The Fratellis", 45L, 2L),
            SessionRow("s-dakota", "Dakota", "Stereophonics", 5L, 5L),
            SessionRow("s-valerie", "Valerie", "The Zutons", 1L, 4L),
        ),
        loading = false,
    ).withPerformers(listOf(will, coralie))

    private fun TestScope.sync(state: MutableStateFlow<SessionState>, reads: HeldReads) {
        RatingsSync(state, backgroundScope, reads::read) { change -> state.update(change) }
        runCurrent()
    }

    private fun SessionState.titles(): List<String> = pending.map { it.title }

    /** Case 1: Will, then Coralie, then Will, all in flight. Only the last Will read lands; Coralie's late one never does. */
    @Test
    fun anOwnerChangedTwiceInFlightLandsOnlyTheLatestPart() = runTest {
        val state = MutableStateFlow(loaded)
        val reads = HeldReads()
        sync(state, reads)

        state.update { it.withOwnerPerformer("p-will") }
        runCurrent()
        state.update { it.withOwnerPerformer("p-coralie") }
        runCurrent()
        state.update { it.withOwnerPerformer("p-will") }
        runCurrent()
        assertEquals(listOf(willVocal, coralieVocal, willVocal), reads.asked.map { it.first })

        reads.asked[1].second.complete(ratingsOf.getValue("p-coralie"))
        runCurrent()
        assertNull(state.value.ratedFor, "Coralie's late read is dropped")
        assertEquals(listOf<RatingLevel?>(null, null, null), state.value.rows.map { it.priority })

        reads.asked[0].second.complete(ratingsOf.getValue("p-will"))
        reads.asked[2].second.complete(ratingsOf.getValue("p-will"))
        runCurrent()
        assertEquals(willVocal, state.value.ratedFor)
        assertEquals(listOf(null, RatingLevel.EXCEPTIONALLY, RatingLevel.SOMEWHAT), state.value.rows.map { it.priority })
    }

    /** Case 2: a read that lands while a reload is under way (`reloadAfter` sets loading) is dropped. */
    @Test
    fun aReadLandingDuringAReloadIsDropped() = runTest {
        val state = MutableStateFlow(loaded.withOwnerPerformer("p-will"))
        val reads = HeldReads()
        sync(state, reads)
        assertEquals(listOf(willVocal), reads.asked.map { it.first })

        state.update { it.copy(loading = true) }
        runCurrent()
        reads.asked[0].second.complete(ratingsOf.getValue("p-will"))
        runCurrent()

        assertNull(state.value.ratedFor)
        assertEquals(listOf<RatingLevel?>(null, null, null), state.value.rows.map { it.priority })
        assertEquals(1, reads.asked.size, "and a loading state asks for nothing")
    }

    /** Case 3: an owner change re-sorts a list already in a triage mode. */
    @Test
    fun anOwnerChangeReSortsATriageList() = runTest {
        val state = MutableStateFlow(loaded)
        val reads = HeldReads()
        sync(state, reads)
        assertEquals(listOf("Chelsea Dagger", "Dakota", "Valerie"), state.value.titles(), "nothing rated: staleness")

        state.update { it.withOwnerPerformer("p-will") }
        runCurrent()
        reads.asked.last().second.complete(ratingsOf.getValue("p-will"))
        runCurrent()
        assertEquals(listOf("Dakota", "Valerie", "Chelsea Dagger"), state.value.titles())

        state.update { it.withOwnerPerformer("p-coralie") }
        runCurrent()
        reads.asked.last().second.complete(ratingsOf.getValue("p-coralie"))
        runCurrent()
        assertEquals(listOf("Valerie", "Dakota", "Chelsea Dagger"), state.value.titles())
    }

    /**
     * Case 5, F35 B2: Will's ratings are on the rows, the owner becomes Coralie, and her read fails. The
     * rows fall to no statement (T8), never keep Will's under Coralie's name, and the failure is said.
     */
    @Test
    fun aFailedReadLeavesTheRowsUnratedNotThePreviousPerformers() = runTest {
        val state = MutableStateFlow(loaded.withOwnerPerformer("p-will"))
        val reads = HeldReads()
        sync(state, reads)
        reads.asked.last().second.complete(ratingsOf.getValue("p-will"))
        runCurrent()
        assertEquals(willVocal, state.value.ratedFor)

        state.update { it.withOwnerPerformer("p-coralie") }
        runCurrent()
        reads.asked.last().second.completeExceptionally(IllegalStateException("disk I/O error"))
        runCurrent()

        assertEquals(coralieVocal, state.value.resolvedPart)
        assertNull(state.value.ratedFor)
        assertEquals(listOf<RatingLevel?>(null, null, null), state.value.rows.map { it.priority })
        assertEquals(listOf("Chelsea Dagger", "Dakota", "Valerie"), state.value.titles(), "triage sorts as staleness")
        assertEquals(Messages.ratingsReadFailed(IllegalStateException("disk I/O error")), state.value.message)
        assertEquals(2, reads.asked.size, "no retry loop: the next trigger retries")
    }

    /** Nobody resolving clears the ratings at once, with no read. */
    @Test
    fun nobodyResolvingClearsTheRatingsWithoutARead() = runTest {
        val state = MutableStateFlow(loaded.withOwnerPerformer("p-will"))
        val reads = HeldReads()
        sync(state, reads)
        reads.asked.last().second.complete(ratingsOf.getValue("p-will"))
        runCurrent()

        state.update { it.withOwnerPerformer(null) }
        runCurrent()
        assertNull(state.value.ratedFor)
        assertEquals(listOf<RatingLevel?>(null, null, null), state.value.rows.map { it.priority })
        assertEquals(1, reads.asked.size)
    }
}
