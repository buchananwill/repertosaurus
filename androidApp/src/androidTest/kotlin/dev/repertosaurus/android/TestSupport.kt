package dev.repertosaurus.android

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.ComposeTestRule
import dev.repertosaurus.core.NoteSpelling
import dev.repertosaurus.session.SessionPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.CoroutineContext

/** The device id every instrumented test writes with. */
internal const val TEST_DEVICE: String = "instrumented-test-device"

/** How long a boot is given to resolve before the test calls it hung. */
internal const val BOOT_TIMEOUT_MS: Long = 20_000L

/**
 * **The one wait for any test that has a Compose rule** (style review F17 N7, F27 N6): through the
 * Compose test clock, not `Thread.sleep`, because a screen's `LaunchedEffect`s only run when the
 * rule pumps a frame. A timeout names what never settled. [EditingFixtures.await] exists only for
 * the ViewModel tests that compose nothing and so have no rule to wait through, and for building a
 * fixture before anything is composed ([EditingFixtures.session]); once a screen is up, every wait
 * is this one.
 */
internal fun ComposeTestRule.awaitUntil(what: String, settled: () -> Boolean) {
    try {
        waitUntil(BOOT_TIMEOUT_MS, settled)
    } catch (timeout: ComposeTimeoutException) {
        throw AssertionError("$what never settled within ${BOOT_TIMEOUT_MS}ms", timeout)
    }
}

/**
 * An IO dispatcher a test can hold shut, so a write can be caught **in flight** — dispatched,
 * holding its route's queue, and not yet run. Work dispatched while the gate is closed waits
 * until [open]; work dispatched while it is open runs at once, on `Dispatchers.IO`.
 */
internal class Gate : CoroutineDispatcher() {
    @Volatile
    private var latch = CountDownLatch(0)

    fun close() {
        latch = CountDownLatch(1)
    }

    fun open() {
        latch.countDown()
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val waitOn = latch
        Dispatchers.IO.dispatch(context, Runnable {
            waitOn.await()
            block.run()
        })
    }
}

/**
 * Preferences with no `SharedPreferences` behind them, so one test cannot leak a remembered
 * instrument, sort direction or home View into the next — and so a test never touches the real
 * app's preferences file.
 */
internal class InMemoryPreferences : SessionPreferences {
    private var instrument: String? = null
    private var order: String? = null
    private var homeView: String? = null
    private var spelling: NoteSpelling = NoteSpelling.DEFAULT

    override fun lastInstrumentId(): String? = instrument
    override fun rememberInstrument(instrumentId: String) { instrument = instrumentId }
    override fun lastOrder(): String? = order
    override fun rememberOrder(order: String) { this.order = order }
    override fun homeViewId(): String? = homeView
    override fun rememberHomeView(viewId: String) { homeView = viewId }
    override fun noteSpelling(): NoteSpelling = spelling
    override fun rememberNoteSpelling(spelling: NoteSpelling) { this.spelling = spelling }
}
