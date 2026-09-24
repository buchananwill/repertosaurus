package dev.repertosaurus.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.repertosaurus.data.SampleData
import dev.repertosaurus.session.Messages
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **scorecards SC19 on the Songs route**: the song detail's practice section lists each timed event's
 * duration and a timed total, and a song with no timed event shows neither.
 *
 * By hand: Valerie gains a 1 440 s (24 min) vocal event on 2026-09-20 and a 3 000 s (50 min) guitar
 * event on 2026-09-18, logged in that (out of date) order, plus one untimed tap. The total is
 * 4 440 s = 74 min = "1 h 14 min". `SampleData`'s own history is untimed.
 */
@RunWith(AndroidJUnit4::class)
class SongHistoryMinutesTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val harness = SongsRouteHarness(compose, "song-history-minutes")

    @After
    fun cleanUp() {
        harness.cleanUp()
    }

    @Test
    fun theHistoryShowsEachDurationAndTheTimedTotal() {
        val route = harness.route("timed") { holder ->
            val valerie = EditingFixtures.song(holder, "Valerie").id
            holder.repository.logPractice(valerie, SampleData.VOCAL, loggedOn = "2026-09-20", durationSeconds = 1_440L)
            holder.repository.logPractice(valerie, SampleData.GUITAR, loggedOn = "2026-09-17")
            holder.repository.logPractice(valerie, SampleData.GUITAR, loggedOn = "2026-09-18", durationSeconds = 3_000L)
        }
        val valerie = EditingFixtures.song(route.holder, "Valerie")

        harness.openDetail(route, valerie)

        val timed = route.songs.state.value.detail!!.timed!!
        assertEquals(listOf("2026-09-20" to 1_440L, "2026-09-18" to 3_000L), timed.events.map { it.loggedOn to it.seconds })
        assertEquals(4_440L, timed.totalSeconds)

        compose.onNodeWithTag(SongDetailTags.TIMED_TOTAL).performScrollTo().assertTextEquals("Timed total: 1 h 14 min")
        for ((event, minutes) in timed.events.zip(listOf("24 min", "50 min"))) {
            val line = Messages.timedEventLine(event)
            assertTrue(line.startsWith("${event.loggedOn} · ") && line.endsWith(": $minutes"), line)
            compose.onNodeWithTag(SongDetailTags.timedEvent(event.id)).performScrollTo().assertTextEquals(line)
        }

        compose.screenshot(SCREENSHOTS, "song-history-durations")
    }

    /**
     * D85 #1: twelve timed events on Valerie, 1 to 12 min on 1 to 12 Sep, logged oldest first. The detail lists
     * the latest ten (12 Sep back to 3 Sep), then "and 2 more"; the 1st and 2nd are not listed; and the total is
     * over all twelve: 60 × (1 + … + 12) = 4 680 s = 78 min = "1 h 18 min", not the 75 min the ten shown add to.
     */
    @Test
    fun theTimedListShowsTheLatestTenAndCountsTheRest() {
        val route = harness.route("capped") { holder -> EditingFixtures.timeTwelve(holder, EditingFixtures.song(holder, "Valerie").id) }
        harness.openDetail(route, EditingFixtures.song(route.holder, "Valerie"))

        val events = route.songs.state.value.detail!!.timed!!.events
        assertEquals((12 downTo 1).map { "2026-09-%02d".format(it) }, events.map { it.loggedOn })

        compose.onNodeWithTag(SongDetailTags.TIMED_TOTAL).performScrollTo().assertTextEquals("Timed total: 1 h 18 min")
        for (event in events.take(10)) {
            compose.onNodeWithTag(SongDetailTags.timedEvent(event.id)).performScrollTo().assertTextEquals(Messages.timedEventLine(event))
        }
        for (event in events.drop(10)) {
            compose.onNodeWithTag(SongDetailTags.timedEvent(event.id)).assertDoesNotExist()
        }
        compose.onNodeWithTag(SongDetailTags.TIMED_MORE).performScrollTo().assertTextEquals("and 2 more")
    }

    /**
     * D94 (the cap's boundary): eleven timed events, 1 to 11 min on 1 to 11 Sep. Ten are listed (11 Sep back to
     * 2 Sep), 1 Sep is not, "and 1 more" follows, and the total is all eleven: 60 × 66 = 3 960 s = "1 h 6 min".
     */
    @Test
    fun elevenTimedEventsListTenAndOneMore() {
        val route = harness.route("eleven") { holder ->
            EditingFixtures.timeDays(holder, EditingFixtures.song(holder, "Valerie").id, 1..11)
        }
        harness.openDetail(route, EditingFixtures.song(route.holder, "Valerie"))

        val timed = route.songs.state.value.detail!!.timed!!
        assertEquals(1L, timed.olderCount)
        compose.onNodeWithTag(SongDetailTags.TIMED_TOTAL).performScrollTo().assertTextEquals("Timed total: 1 h 6 min")
        for (event in timed.events.take(10)) {
            compose.onNodeWithTag(SongDetailTags.timedEvent(event.id)).performScrollTo().assertTextEquals(Messages.timedEventLine(event))
        }
        val oldest = timed.events.last()
        assertEquals("2026-09-01", oldest.loggedOn)
        compose.onNodeWithTag(SongDetailTags.timedEvent(oldest.id)).assertDoesNotExist()
        compose.onNodeWithTag(SongDetailTags.TIMED_MORE).performScrollTo().assertTextEquals("and 1 more")
    }

    @Test
    fun aSongWithNoTimedEventShowsNoTimedLines() {
        val route = harness.route("untimed")
        val valerie = EditingFixtures.song(route.holder, "Valerie")

        harness.openDetail(route, valerie)

        assertNull(route.songs.state.value.detail!!.timed)
        compose.onNodeWithTag(SongDetailTags.TIMED_TOTAL).assertDoesNotExist()
        compose.onNodeWithTag(SongDetailTags.TIMED_MORE).assertDoesNotExist()
    }

    private companion object {
        const val SCREENSHOTS = "p12"
    }
}
