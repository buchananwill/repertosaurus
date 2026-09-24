package dev.repertosaurus.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.android.theme.RepertosaurusWindow
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.session.DevicePreferences
import dev.repertosaurus.session.InMemoryDevicePreferences
import dev.repertosaurus.session.InMemorySessionPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * **The one Session-screen harness** (style review F17 B14): a fresh database of the sample songs, its
 * logger, and the screen composed in the app's own window — theme and grain — as `MainActivity` does.
 *
 * [fontScale], when given, is set through `LocalDensity` rather than the system setting, so the test
 * owns it and leaves nothing behind; a sheet's window inherits it from the composition.
 */
internal class SessionScreenFixture(private val compose: ComposeContentTestRule, private val prefix: String) {

    class Screen(val holder: DatabaseHolder, val session: SessionViewModel, val settings: DeviceSettings) {
        /** The first pending song's title: what a test taps. */
        val title: String get() = session.state.value.pending.first().title
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableListOf<String>()

    /** The database and ViewModels, uncomposed. [device] backs the suggester's tuning (suggest SG15). */
    fun build(
        suffix: String,
        device: DevicePreferences = InMemoryDevicePreferences(),
        io: CoroutineDispatcher = Dispatchers.IO,
    ): Screen {
        val name = "$prefix-$suffix.db".also { names += it }
        val holder = EditingFixtures.holder(context, name)
        lateinit var session: SessionViewModel
        lateinit var settings: DeviceSettings
        EditingFixtures.onMain {
            session = SessionViewModel(holder, InMemorySessionPreferences(), TEST_DEVICE, io)
            settings = DeviceSettings(device)
        }
        EditingFixtures.awaitSession(session)
        return Screen(holder, session, settings)
    }

    fun open(
        suffix: String,
        fontScale: Float? = null,
        device: DevicePreferences = InMemoryDevicePreferences(),
        io: CoroutineDispatcher = Dispatchers.IO,
    ): Screen {
        val screen = build(suffix, device, io)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
                RepertosaurusWindow { SessionContent(screen) }
            }
        }
        compose.waitForIdle()
        return screen
    }

    /** Poll a database condition on wall-clock time, pumping no frames: for a test holding the clock. */
    fun awaitWithoutFrames(what: String, settled: () -> Boolean) {
        val deadline = System.currentTimeMillis() + BOOT_TIMEOUT_MS
        while (!settled()) {
            check(System.currentTimeMillis() < deadline) { "$what never settled within ${BOOT_TIMEOUT_MS}ms" }
            Thread.sleep(20)
        }
    }

    /** Delete every database this fixture made. Call from the test's `@After`. */
    fun cleanUp() {
        for (name in names) DatabaseFixtures.delete(context, name)
    }
}

/** The Session screen wired to [screen]'s ViewModels as `RepertosaurusApp` wires it. */
@Composable
internal fun SessionContent(screen: SessionScreenFixture.Screen) {
    SessionScreen(
        viewModel = screen.session,
        suggestTuning = screen.settings.suggestTuning.collectAsState().value,
        onTune = screen.settings::setSuggestTuning,
        onOpenDrawer = {},
        onExport = {},
    )
}
