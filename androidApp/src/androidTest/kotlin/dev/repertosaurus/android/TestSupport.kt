package dev.repertosaurus.android

import android.content.res.Resources
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
import org.junit.rules.ExternalResource
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

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

/** visual-identity VI22: the animator duration scale at 0, for a rule's `effectContext`. */
internal object NoMotion : MotionDurationScale {
    override val scaleFactor: Float = 0f
}

/**
 * visual-identity VI8: **the system font scale** at [scale] for each test, restored after. A sheet is its
 * own window, which a `LocalDensity` override never reaches; only the system setting does. Order it
 * before the Compose rule, so the activity starts at the scale rather than being recreated into it.
 */
class SystemFontScale(private val scale: Float) : ExternalResource() {
    private var prior: String = "1.0"

    override fun before() {
        prior = shell("settings get system font_scale").trim().takeIf { it.toFloatOrNull() != null } ?: "1.0"
        apply(scale)
    }

    override fun after() {
        apply(prior.toFloat())
    }

    /**
     * The setting, then the configuration it drives. A change made while WindowManager is frozen between tests
     * can be lost, and writing the same value again delivers nothing, so a retry moves the setting off the value
     * and back: that is a change, and it is delivered.
     */
    private fun apply(value: Float) {
        repeat(ATTEMPTS) { attempt ->
            if (attempt > 0) {
                shell("settings put system font_scale ${value + NUDGE}")
                reaches(value + NUDGE, NUDGE_WAIT_MS)
            }
            shell("settings put system font_scale $value")
            if (reaches(value, ATTEMPT_WAIT_MS)) return
        }
        error("the system font scale never reached $value in $ATTEMPTS attempts")
    }

    private fun reaches(value: Float, withinMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + withinMs
        while (abs(Resources.getSystem().configuration.fontScale - value) > TOLERANCE) {
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(50)
        }
        return true
    }

    private fun shell(command: String): String = shellOutput(command)

    private companion object {
        const val ATTEMPTS = 4
        const val ATTEMPT_WAIT_MS = 8_000L
        const val NUDGE_WAIT_MS = 3_000L
        const val NUDGE = 0.05f
        const val TOLERANCE = 0.001f
    }
}

/** One shell command through the instrumentation, and what it printed. */
internal fun shellOutput(command: String): String =
    ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
        .bufferedReader()
        .use { it.readText() }

/** A wall clock the test moves (timer TM11). */
internal class MovableClock(@Volatile var instant: Instant) : Clock {
    override fun now(): Instant = instant

    fun forward(seconds: Long) {
        instant = Instant.fromEpochMilliseconds(instant.toEpochMilliseconds() + seconds * 1_000L)
    }
}

/**
 * VI8: no text node in any window is clipped: a line lost to the height, ellipsised, or wider than its box.
 * Not `hasVisualOverflow` alone: through the semantics action at this Compose version it reports a
 * wrap-content label as overflowing, because the paragraph was laid out at the full available width.
 */
internal fun ComposeTestRule.assertNoTextClipped(where: String, matcher: SemanticsMatcher = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)) {
    waitForIdle()
    val nodes = onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
    assertTrue(nodes.isNotEmpty(), "$where has no text to check")
    val scale = InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.fontScale
    for (node in nodes) {
        if (SemanticsActions.GetTextLayoutResult !in node.config) continue
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val text = node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString()
        for (layout in layouts) assertTrue(!clipped(layout), "$where: \"$text\" is clipped at font scale $scale")
    }
}

private fun clipped(layout: TextLayoutResult): Boolean {
    if (layout.didOverflowHeight) return true
    return (0 until layout.lineCount).any { line ->
        layout.isLineEllipsized(line) || layout.getLineRight(line) - layout.getLineLeft(line) > layout.size.width + 1f
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

/**
 * [screenshot] once the display has caught up. A sheet's expansion, a spring and a dialog's window are drawn on
 * real frames, which the test clock does not wait for; the sheets that hold an action open fully expanded.
 */
internal fun ComposeTestRule.settleAndShoot(dir: String, name: String) {
    waitForIdle()
    Thread.sleep(SETTLE_MS)
    screenshot(dir, name)
}

private const val SETTLE_MS = 1_000L
