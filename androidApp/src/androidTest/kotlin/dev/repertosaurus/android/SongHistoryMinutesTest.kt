package dev.repertosaurus.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.repertosaurus.data.SampleData
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.TimedHistory
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

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

        val timed = route.songs.state.value.detail!!.timed
        assertEquals(listOf("2026-09-20" to 1_440L, "2026-09-18" to 3_000L), timed.events.map { it.loggedOn to it.seconds })
        assertEquals(4_440L, timed.totalSeconds)

        compose.onNodeWithTag(SongDetailTags.TIMED_TOTAL).performScrollTo().assertTextEquals("Timed total: 1 h 14 min")
        for ((event, minutes) in timed.events.zip(listOf("24 min", "50 min"))) {
            val line = Messages.timedEventLine(event)
            assertTrue(line.startsWith(event.loggedOn) && line.endsWith(": $minutes"), line)
            compose.onNodeWithTag(SongDetailTags.timedEvent(event.id)).performScrollTo().assertTextEquals(line)
        }

        compose.screenshot(SCREENSHOTS, "song-history-durations")
    }

    @Test
    fun aSongWithNoTimedEventShowsNoTimedLines() {
        val route = harness.route("untimed")
        val valerie = EditingFixtures.song(route.holder, "Valerie")

        harness.openDetail(route, valerie)

        assertSame(TimedHistory.NONE, route.songs.state.value.detail!!.timed)
        compose.onNodeWithTag(SongDetailTags.TIMED_TOTAL).assertDoesNotExist()
    }

    private companion object {
        const val SCREENSHOTS = "p12"
    }
}
