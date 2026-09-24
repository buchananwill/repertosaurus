package dev.repertosaurus.android

import android.graphics.Bitmap
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.ComposeTestRule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
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
 * An IO dispatcher a test can hold shut, so work can be caught **in flight** — dispatched, and not yet
 * run. Work dispatched while the gate is closed waits until [open]; work dispatched while it is open runs
 * at once, on `Dispatchers.IO`. [holdAfter] lets the next `n` dispatches through and holds the rest.
 */
internal class Gate : CoroutineDispatcher() {
    @Volatile
    private var latch = CountDownLatch(0)

    private val dispatched = AtomicInteger()

    @Volatile
    private var passing = 0

    fun close() {
        holdAfter(0)
    }

    /** The next [n] dispatches run; every one after them waits for [open]. */
    fun holdAfter(n: Int) {
        dispatched.set(0)
        passing = n
        latch = CountDownLatch(1)
    }

    fun open() {
        latch.countDown()
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val waitOn = if (dispatched.getAndIncrement() < passing) null else latch
        Dispatchers.IO.dispatch(context, Runnable {
            waitOn?.await()
            block.run()
        })
    }
}

/**
 * **The one screenshot**: the whole screen, sheets included, into the app's
 * external files under [dir], for a package's report. The font scale is in the name when it is not 1.
 */
internal fun ComposeTestRule.screenshot(dir: String, name: String) {
    waitForIdle()
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
    val context = instrumentation.targetContext
    val scale = context.resources.configuration.fontScale
    val suffix = if (scale == 1f) "" else "-fs$scale"
    val folder = File(context.getExternalFilesDir(null), dir).apply { mkdirs() }
    File(folder, "$name$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}
