package dev.repertosaurus.android

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.android.EditingFixtures.onMain
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.data.SongCatalog

/** One Songs route under test: its database and the two ViewModels the screen is driven by (R18). */
internal class SongsRoute(
    val holder: DatabaseHolder,
    val songs: SongsViewModel,
    val session: SessionViewModel,
)

/**
 * **The Songs route's instrumented harness** (style review F27 N6), shared by `SongsEditingTest`
 * and `SongMergeFlowTest`, which had each grown a private copy of it: a fresh database per route,
 * the real [SongsScreen] over a real [SongsViewModel] and the logger's [SessionViewModel], and the
 * one way to open a song's detail by tapping its row.
 *
 * Every wait here is [awaitUntil] on [compose] — the one wait for a test that has a Compose rule
 * (see `TestSupport`).
 */
internal class SongsRouteHarness(private val compose: ComposeContentTestRule, private val prefix: String) {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableListOf<String>()
    private var shown: SongsRoute? = null

    /**
     * A fresh database named for [suffix], with [setUp] applied to it, and both ViewModels over it,
     * built on the main thread and waited on until the logger has loaded. Not yet composed.
     */
    fun route(suffix: String, setUp: (DatabaseHolder) -> Unit = {}): SongsRoute {
        val name = "$prefix-$suffix.db".also { names += it }
        val holder = EditingFixtures.holder(context, name)
        setUp(holder)
        lateinit var songs: SongsViewModel
        lateinit var session: SessionViewModel
        onMain {
            songs = SongsViewModel(holder)
            session = SessionViewModel(holder, InMemoryPreferences(), TEST_DEVICE)
        }
        compose.awaitUntil("the logger") { !session.state.value.loading }
        return SongsRoute(holder, songs, session)
    }

    /** Compose the Songs screen over [route] and wait for its list. */
    fun show(route: SongsRoute) {
        compose.setContent {
            MaterialTheme { SongsScreen(viewModel = route.songs, session = route.session, onBack = {}) }
        }
        shown = route
        compose.awaitUntil("the list") { route.songs.state.value.songs.isNotEmpty() }
        compose.waitForIdle()
    }

    /** Open [song]'s detail by tapping its row — composing [route] first if it is not on screen. */
    fun openDetail(route: SongsRoute, song: SongCatalog.SongListEntry) {
        if (shown !== route) show(route)
        compose.onNodeWithTag(SongsTags.LIST).performScrollToNode(hasTestTag(SongsTags.row(song.id)))
        compose.onNodeWithTag(SongsTags.row(song.id)).performClick()
        awaitDetail(route)
        compose.waitForIdle()
    }

    /** The detail is open, read, and has nothing in flight. */
    fun awaitDetail(route: SongsRoute) {
        compose.awaitUntil("the detail") {
            val detail = route.songs.state.value.detail
            detail != null && detail.record != null && !detail.busy
        }
    }

    /** Delete every database this harness created. Call from the test's `@After`. */
    fun cleanUp() {
        for (name in names) DatabaseFixtures.delete(context, name)
    }
}
