package dev.repertosaurus.android

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.data.DatabaseState
import dev.repertosaurus.data.SampleData
import dev.repertosaurus.data.SongCatalog
import dev.repertosaurus.session.DevicePreferences
import dev.repertosaurus.session.InMemoryDevicePreferences
import dev.repertosaurus.session.InMemorySessionPreferences
import dev.repertosaurus.session.LookupKind
import dev.repertosaurus.session.LookupStores
import dev.repertosaurus.session.SessionPreferences
import kotlin.test.assertEquals

/**
 * The repertoire-editing arc's harness: a fresh database per test, holding the app's own eight
 * sample songs (the same `SampleData` the boot path installs) and nothing else — **no performers
 * and no `song_performer` rows**, which every test here starts from.
 */
internal object EditingFixtures {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    fun holder(context: Context, name: String): DatabaseHolder {
        DatabaseFixtures.delete(context, name)
        val holder = DatabaseHolder(context, TEST_DEVICE, name)
        assertEquals(DatabaseState.Ready, holder.load())
        SampleData.installIfEmpty(holder.open(), TEST_DEVICE)
        return holder
    }

    fun performer(holder: DatabaseHolder, name: String): String =
        LookupStores.of(LookupKind.PERFORMER, holder.repository).add(name)

    fun song(holder: DatabaseHolder, title: String): SongCatalog.SongListEntry =
        holder.repository.catalog.songs().firstOrNull { it.title == title }
            ?: error("no live song titled $title")

    fun onMain(block: () -> Unit) {
        instrumentation.runOnMainSync(block)
    }

    /**
     * Poll [settled] until it holds three times running, or fail after [BOOT_TIMEOUT_MS]. **Only for
     * a test with no Compose rule, or before anything is composed** (F27 N6): once a screen is up,
     * every wait is [awaitUntil], which pumps the frames the screen's effects run on.
     */
    fun await(what: String, settled: () -> Boolean) {
        val deadline = System.currentTimeMillis() + BOOT_TIMEOUT_MS
        var quiet = 0
        while (System.currentTimeMillis() < deadline) {
            quiet = if (settled()) quiet + 1 else 0
            if (quiet >= 3) return
            Thread.sleep(50)
        }
        error("$what never settled within ${BOOT_TIMEOUT_MS}ms")
    }

    /**
     * A logger ViewModel over [holder], built on the main thread — `viewModelScope` is
     * `Dispatchers.Main.immediate`, and the initialisation-order hazard that once crashed the
     * class only reproduces there — and waited on until its first load has settled.
     */
    fun session(holder: DatabaseHolder): SessionViewModel {
        lateinit var model: SessionViewModel
        onMain { model = SessionViewModel(holder, InMemorySessionPreferences(), TEST_DEVICE) }
        awaitSession(model)
        return model
    }

    /**
     * Settled means the capability editor is not writing and the session list is not reloading.
     * Both are checked because a capability edit reloads the list afterwards (a View's
     * eligibility reads `song_performer`), and a test that watched only one would race the other.
     */
    fun awaitSession(model: SessionViewModel) {
        await("the logger and the capability editor") {
            !model.capabilities.value.busy && !model.state.value.loading
        }
    }

    /**
     * **Every ViewModel [RepertosaurusApp] takes, over [holder]** (style review F9 N4), built on the
     * main thread. Not waited on: a boot against a broken database never settles into a loaded
     * logger, so each test waits for what it expects.
     */
    fun app(
        holder: DatabaseHolder,
        preferences: SessionPreferences = InMemorySessionPreferences(),
        device: DevicePreferences = InMemoryDevicePreferences(),
    ): AppModels {
        lateinit var app: AppModels
        onMain {
            app = AppModels(
                session = SessionViewModel(holder, preferences, TEST_DEVICE),
                // The editing routes' ViewModels read nothing until their screen is entered.
                repertoire = RepertoireViewModel(holder),
                songs = SongsViewModel(holder),
                artists = ArtistsViewModel(holder),
                settings = DeviceSettings(device),
            )
        }
        return app
    }
}

/** What [EditingFixtures.app] builds. */
internal class AppModels(
    val session: SessionViewModel,
    val repertoire: RepertoireViewModel,
    val songs: SongsViewModel,
    val artists: ArtistsViewModel,
    val settings: DeviceSettings,
)

/** Compose the whole app over [app], as `MainActivity` does. */
internal fun ComposeContentTestRule.setApp(app: AppModels) {
    setContent {
        MaterialTheme { RepertosaurusApp(app.session, app.repertoire, app.songs, app.artists, app.settings) }
    }
}
