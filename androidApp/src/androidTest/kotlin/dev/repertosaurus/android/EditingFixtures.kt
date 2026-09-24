package dev.repertosaurus.android

import android.content.Context
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.android.theme.RepertosaurusWindow
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.data.DatabaseState
import dev.repertosaurus.data.SampleData
import dev.repertosaurus.data.SongCatalog
import dev.repertosaurus.session.DevicePreferences
import dev.repertosaurus.session.InMemoryDevicePreferences
import dev.repertosaurus.session.InMemorySessionPreferences
import dev.repertosaurus.session.InMemoryTimerStore
import dev.repertosaurus.session.LookupKind
import dev.repertosaurus.session.LookupStores
import dev.repertosaurus.session.SessionPreferences
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

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

    /**
     * scorecards SC19, D85 #1: twelve timed vocal events on [songId], 1 to 12 min on 1 to 12 Sep 2026, logged
     * oldest first. Their total is 60 × (1 + … + 12) = 4 680 s, "1 h 18 min".
     */
    fun timeTwelve(holder: DatabaseHolder, songId: String) {
        timeDays(holder, songId, 1..12)
    }

    /** One timed vocal event on [songId] for each day of September 2026 in [days], `day` minutes long. */
    fun timeDays(holder: DatabaseHolder, songId: String, days: IntRange) {
        for (day in days) {
            holder.repository.logPractice(songId, SampleData.VOCAL, loggedOn = "2026-09-%02d".format(day), durationSeconds = day * 60L)
        }
    }

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
        onMain { model = SessionViewModel(holder, InMemorySessionPreferences(), TEST_DEVICE, InMemoryTimerStore()) }
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
     *
     * **[onboarded] marks first-run onboarding done on [device] before anything reads it**, so the
     * whole-app tests land on the logger as they did before onboarding existed (onboarding OB1). Only
     * the onboarding tests pass false. [io] is the logger's, so a test can hold its first load (a [Gate]).
     */
    fun app(
        holder: DatabaseHolder,
        preferences: SessionPreferences = InMemorySessionPreferences(),
        device: DevicePreferences = InMemoryDevicePreferences(),
        onboarded: Boolean = true,
        io: CoroutineDispatcher = Dispatchers.IO,
    ): AppModels {
        if (onboarded) device.markOnboardingDone()
        lateinit var app: AppModels
        onMain {
            app = AppModels(
                session = SessionViewModel(holder, preferences, TEST_DEVICE, InMemoryTimerStore(), io),
                // The editing routes' ViewModels read nothing until their screen is entered.
                repertoire = RepertoireViewModel(holder),
                songs = SongsViewModel(holder),
                artists = ArtistsViewModel(holder),
                settings = DeviceSettings(device),
                ratings = RatingsEditorViewModel(holder),
                habit = HabitViewModel(holder),
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
    val ratings: RatingsEditorViewModel,
    val habit: HabitViewModel,
)

/**
 * Compose the whole app over [app], as `MainActivity` does — the theme and the grain included, so
 * every test through here also taps through the grain (visual-identity VI10).
 */
internal fun ComposeContentTestRule.setApp(app: AppModels) {
    setContent {
        RepertosaurusWindow {
            RepertosaurusApp(app.session, app.repertoire, app.songs, app.artists, app.settings, app.ratings, app.habit)
        }
    }
}
