package dev.repertosaurus.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.repertosaurus.android.EditingFixtures.onMain
import dev.repertosaurus.data.SampleData
import dev.repertosaurus.session.CapabilityCoordinator
import dev.repertosaurus.session.LookupKind
import dev.repertosaurus.session.Messages
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **P13's screenshots** (journal session 11, D90, D95): every screen the polish pass restyled, at a real
 * system font scale so the dialogs and sheets are at it too. The two subclasses run the same shots at 1.0
 * and 1.3; a file's name carries the scale when it is not 1. They assert only what they need to reach each
 * screen: the look is judged by eye.
 */
abstract class PolishScreenshots(scale: Float) {

    @get:Rule(order = 0)
    val fontScale = SystemFontScale(scale)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val songs = SongsRouteHarness(compose, "p13-shots-songs")
    private val app = HabitRouteHarness(compose, "p13-shots-app")
    private val sessions = SessionScreenFixture(compose, "p13-shots-session")

    @After
    fun cleanUp() {
        songs.cleanUp()
        app.cleanUp()
        sessions.cleanUp()
    }

    /** The Songs list, a song's detail and its capped practice, the remove dialog, and the line-up's dialog. */
    @Test
    fun theSongsRoute() {
        val route = songs.route("songs") { holder ->
            val valerie = EditingFixtures.song(holder, "Valerie").id
            EditingFixtures.timeTwelve(holder, valerie)
            CapabilityCoordinator(holder.repository).add(valerie, "Coralie", "vocal")
        }
        songs.show(route)
        compose.settleAndShoot(DIR, "songs-list")

        songs.openDetail(route, EditingFixtures.song(route.holder, "Valerie"))
        compose.settleAndShoot(DIR, "song-detail")
        compose.onNodeWithTag(SongDetailTags.TIMED_MORE).performScrollTo()
        compose.settleAndShoot(DIR, "song-detail-practice")

        compose.onNodeWithTag(SongDetailTags.REMOVE).performScrollTo().performClick()
        compose.settleAndShoot(DIR, "ink-dialog-remove-song")
        compose.onNodeWithText(Messages.CANCEL, ignoreCase = true).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(SongDetailTags.EDIT_LINE_UP).performScrollTo().performClick()
        compose.awaitUntil("the line-up") { route.session.capabilities.value.lineUp.isNotEmpty() }
        compose.settleAndShoot(DIR, "capability-sheet")
        // Coralie's line-up chip, the first "Vocal" in the sheet; the second is the instrument near-match.
        compose.onAllNodes(hasText("Vocal") and hasAnyAncestor(hasTestTag(CapabilityTags.SHEET))).onFirst().performClick()
        compose.settleAndShoot(DIR, "capability-dialog")
    }

    /** The merge overlay's two panes: the picker, then the preview. */
    @Test
    fun theMergeScreen() {
        // A logged event on Dakota, so the preview has a row with a checkbox.
        val route = songs.route("merge") { holder ->
            holder.repository.logPractice(EditingFixtures.song(holder, "Dakota").id, SampleData.VOCAL, loggedOn = "2026-09-20")
        }
        songs.openDetail(route, EditingFixtures.song(route.holder, "Valerie"))
        compose.onNodeWithTag(SongDetailTags.MERGE).performScrollTo().performClick()
        compose.awaitUntil("the picker") { route.songs.merge.value != null }
        compose.settleAndShoot(DIR, "merge-picker")

        val dakota = EditingFixtures.song(route.holder, "Dakota")
        compose.onNodeWithTag(MergeTags.candidate(dakota.id)).performClick()
        compose.awaitUntil("the preview") { route.songs.merge.value?.plan != null }
        compose.settleAndShoot(DIR, "merge-preview")
        // The foot of the preview: the child rows and events with their square checkboxes.
        compose.onNodeWithTag(MergeTags.PREVIEW_LIST).performScrollToNode(hasTestTag(MergeTags.CONFIRM))
        compose.settleAndShoot(DIR, "merge-preview-foot")
    }

    /** The drawer's square switch, on (the default, simplified spelling) and tapped off. */
    @Test
    fun theDrawerSwitch() {
        app.route("drawer")
        compose.openDrawer()
        compose.settleAndShoot(DIR, "drawer-switch-on")
        compose.onNodeWithTag(DrawerTags.NOTE_SPELLING).performClick()
        compose.settleAndShoot(DIR, "drawer-switch-off")
    }

    @Test
    fun theArtists() {
        app.route("artists")
        app.open(Messages.DRAWER_ARTISTS)
        compose.onNodeWithTag(ArtistTags.LIST).assertExists()
        compose.settleAndShoot(DIR, "artists")
    }

    /** D97: a real error line, the rename dialog with its name cleared: a Madder border and bold Ink words. */
    @Test
    fun anErrorLine() {
        val route = app.route("artists-error")
        app.open(Messages.DRAWER_ARTISTS)
        compose.awaitUntil("the artists") { route.app.artists.state.value.artists.isNotEmpty() }
        val artist = route.app.artists.state.value.artists.first()
        compose.onNodeWithTag(ArtistTags.rename(artist.id)).performClick()
        compose.waitForIdle()
        // The name field is the first of the two that hold the name; the sort name holds it too.
        compose.onAllNodes(hasSetTextAction() and hasText(artist.name)).onFirst().performTextClearance()
        compose.onNode(hasSetTextAction() and SemanticsMatcher.expectValue(SemanticsProperties.Error, Messages.ARTIST_NEEDS_NAME)).assertExists()
        compose.settleAndShoot(DIR, "error-artist-rename")
    }

    /** The performers with their role chips, then one role's toggle list with a song switched on. */
    @Test
    fun theRepertoireToggleList() {
        val route = app.route("repertoire") { holder -> EditingFixtures.performer(holder, "Coralie") }
        val repertoire = route.app.repertoire
        app.open(Messages.DRAWER_REPERTOIRE)
        compose.awaitUntil("the performers") { repertoire.state.value.performers.isNotEmpty() }
        compose.settleAndShoot(DIR, "repertoire-performers")

        val coralie = repertoire.state.value.performers.first { it.performerName == "Coralie" }.performerId
        compose.onNodeWithTag(RepertoireTags.addRole(coralie)).performClick()
        compose.onNodeWithText("Vocal").performClick()
        compose.awaitUntil("the toggle list") { repertoire.state.value.list?.loading == false }
        val first = repertoire.state.value.list!!.visible().first().songId
        onMain { repertoire.toggle(first) }
        compose.awaitUntil("the toggle") { repertoire.state.value.list?.inFlight?.isEmpty() == true }
        compose.settleAndShoot(DIR, "repertoire-toggle-list")
    }

    @Test
    fun theLookupManagement() {
        app.route("lookups")
        app.open(LookupKind.INSTRUMENT.plural)
        compose.settleAndShoot(DIR, "lookup-instruments")
    }

    /** F29, D93: nothing tapped, and no empty band under the grid. */
    @Test
    fun theScorecards() {
        app.route("scorecards")
        app.open()
        compose.settleAndShoot(DIR, "scorecards")
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

    /** The View menu, then its editor sheet: square chips, fields and switches' kin. */
    @Test
    fun theViewEditor() {
        sessions.open("views")
        compose.onNodeWithText("ALL SONGS ▾").performClick()
        compose.settleAndShoot(DIR, "view-switcher")
        compose.onNodeWithText("Make the first view", ignoreCase = true).performClick()
        compose.settleAndShoot(DIR, "view-editor")
    }

    /** timer TM4, D95 B6: Stop over Cancel in the running bar, their faces level. */
    @Test
    fun theTimerBar() {
        val screen = sessions.open("timer")
        val row = screen.session.state.value.pending.first()
        onMain { screen.session.startTimer(row.songId, row.title) }
        compose.onNodeWithTag(TimerTags.BAR).assertExists()
        compose.settleAndShoot(DIR, "timer-bar")
    }

    private companion object {
        const val DIR = "p13"
    }
}

@RunWith(AndroidJUnit4::class)
class PolishScreenshotsAtOneTest : PolishScreenshots(1.0f)

@RunWith(AndroidJUnit4::class)
class PolishScreenshotsAtOnePointThreeTest : PolishScreenshots(1.3f)
