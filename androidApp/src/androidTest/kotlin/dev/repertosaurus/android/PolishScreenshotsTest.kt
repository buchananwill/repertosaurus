package dev.repertosaurus.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.android.EditingFixtures.onMain
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.data.SampleData
import dev.repertosaurus.session.LookupKind
import dev.repertosaurus.session.Messages
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **P13's screenshots** (journal session 11, D90): every screen the polish pass restyled, in the app's own
 * window, at a real system font scale, so the dialogs and sheets are at the scale too. The two subclasses
 * run the same shots at 1.0 and 1.3; the file names carry the scale when it is not 1. They assert only
 * what they need to reach each screen: the look is judged by eye.
 */
abstract class PolishScreenshots(scale: Float) {

    @get:Rule(order = 0)
    val fontScale = SystemFontScale(scale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableListOf<String>()
    private val sessions = SessionScreenFixture(compose, "p13-shots-session")
    private val habits = HabitRouteHarness(compose, "p13-shots-habit")

    @After
    fun cleanUp() {
        for (name in names) DatabaseFixtures.delete(context, name)
        sessions.cleanUp()
        habits.cleanUp()
    }

    /** The drawer and its square switch, on (the default, simplified spelling) and tapped off; then the Songs list, a song's detail, its practice and the remove dialog. */
    @Test
    fun theDrawerAndTheSongsRoute() {
        val app = app("songs") { holder -> timeValerie(holder) }
        openDrawer()
        compose.settleAndShoot(DIR, "drawer-switch-on")
        compose.onNodeWithTag(DrawerTags.NOTE_SPELLING_SWITCH).performClick()
        compose.settleAndShoot(DIR, "drawer-switch-off")

        compose.onNodeWithText(Messages.DRAWER_SONGS).performClick()
        compose.awaitUntil("the list") { app.songs.state.value.songs.isNotEmpty() }
        compose.settleAndShoot(DIR, "songs-list")

        val valerie = EditingFixtures.song(app.holder, "Valerie")
        compose.onNodeWithTag(SongsTags.LIST).performScrollToNode(hasTestTag(SongsTags.row(valerie.id)))
        compose.onNodeWithTag(SongsTags.row(valerie.id)).performClick()
        compose.awaitUntil("the detail") { app.songs.state.value.detail?.let { it.record != null && !it.busy } == true }
        compose.settleAndShoot(DIR, "song-detail")

        compose.onNodeWithTag(SongDetailTags.TIMED_MORE).performScrollTo()
        compose.settleAndShoot(DIR, "song-detail-practice")

        compose.onNodeWithTag(SongDetailTags.REMOVE).performScrollTo().performClick()
        compose.settleAndShoot(DIR, "ink-dialog-remove-song")
    }

    @Test
    fun theArtists() {
        app("artists")
        openRoute(Messages.DRAWER_ARTISTS)
        compose.onNodeWithTag(ArtistTags.LIST).assertExists()
        compose.settleAndShoot(DIR, "artists")
    }

    /** The performers with their role chips, then one role's toggle list with a song switched on. */
    @Test
    fun theRepertoireToggleList() {
        val app = app("repertoire") { holder -> EditingFixtures.performer(holder, "Coralie") }
        openRoute(Messages.DRAWER_REPERTOIRE)
        compose.awaitUntil("the performers") { app.repertoire.state.value.performers.isNotEmpty() }
        compose.settleAndShoot(DIR, "repertoire-performers")

        val coralie = app.repertoire.state.value.performers.first { it.performerName == "Coralie" }.performerId
        compose.onNodeWithTag(RepertoireTags.addRole(coralie)).performClick()
        compose.onNodeWithText("Vocal").performClick()
        compose.awaitUntil("the toggle list") { app.repertoire.state.value.list?.loading == false }
        val first = app.repertoire.state.value.list!!.visible().first().songId
        onMain { app.repertoire.toggle(first) }
        compose.awaitUntil("the toggle") { app.repertoire.state.value.list?.inFlight?.isEmpty() == true }
        compose.settleAndShoot(DIR, "repertoire-toggle-list")
    }

    @Test
    fun theLookupManagement() {
        app("lookups")
        openRoute(LookupKind.INSTRUMENT.plural)
        compose.settleAndShoot(DIR, "lookup-instruments")
    }

    /** suggest SG12, SG13: Tune's two square switches, both on. */
    @Test
    fun theTuneSwitches() {
        val screen = sessions.openWithOwner("tune")
        compose.suggest(screen)
        compose.openTune()
        compose.onNodeWithTag(SuggestTags.SHOW_SKIP_COUNT).performScrollTo()
        compose.settleAndShoot(DIR, "tune-switches")
    }

    /** F29: nothing tapped, and no empty band under the grid. */
    @Test
    fun theScorecards() {
        habits.route("scorecards")
        habits.open()
        compose.settleAndShoot(DIR, "scorecards")
    }

    private fun app(suffix: String, setUp: (DatabaseHolder) -> Unit = {}): Shot {
        val name = "p13-shots-$suffix.db".also { names += it }
        val holder = EditingFixtures.holder(context, name)
        setUp(holder)
        val app = EditingFixtures.app(holder)
        compose.awaitUntil("the logger") { !app.session.state.value.loading }
        compose.setApp(app)
        compose.waitForIdle()
        return Shot(holder, app.songs, app.repertoire)
    }

    /** D85 #1 on screen: twelve timed events on Valerie, so the detail lists ten and says "and 2 more". */
    private fun timeValerie(holder: DatabaseHolder) {
        val valerie = EditingFixtures.song(holder, "Valerie").id
        for (day in 1..12) {
            holder.repository.logPractice(valerie, SampleData.VOCAL, loggedOn = "2026-09-%02d".format(day), durationSeconds = day * 60L)
        }
    }

    private fun openDrawer() {
        compose.onNodeWithContentDescription(LOGGER_MENU).performClick()
        compose.waitForIdle()
    }

    private fun openRoute(label: String) {
        openDrawer()
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
    }

    private class Shot(val holder: DatabaseHolder, val songs: SongsViewModel, val repertoire: RepertoireViewModel)

    private companion object {
        const val DIR = "p13"
        const val LOGGER_MENU = "Menu"
    }
}

@RunWith(AndroidJUnit4::class)
class PolishScreenshotsAtOneTest : PolishScreenshots(1.0f)

@RunWith(AndroidJUnit4::class)
class PolishScreenshotsAtOnePointThreeTest : PolishScreenshots(1.3f)
