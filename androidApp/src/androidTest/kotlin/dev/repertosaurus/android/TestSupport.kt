package dev.repertosaurus.android

import dev.repertosaurus.session.SessionPreferences

/** The device id every instrumented test writes with. */
internal const val TEST_DEVICE: String = "instrumented-test-device"

/** How long a boot is given to resolve before the test calls it hung. */
internal const val BOOT_TIMEOUT_MS: Long = 20_000L

/**
 * Preferences with no `SharedPreferences` behind them, so one test cannot leak a remembered
 * instrument, sort direction or home View into the next — and so a test never touches the real
 * app's preferences file.
 */
internal class InMemoryPreferences : SessionPreferences {
    private var instrument: String? = null
    private var order: String? = null
    private var homeView: String? = null

    override fun lastInstrumentId(): String? = instrument
    override fun rememberInstrument(instrumentId: String) { instrument = instrumentId }
    override fun lastOrder(): String? = order
    override fun rememberOrder(order: String) { this.order = order }
    override fun homeViewId(): String? = homeView
    override fun rememberHomeView(viewId: String) { homeView = viewId }
}
