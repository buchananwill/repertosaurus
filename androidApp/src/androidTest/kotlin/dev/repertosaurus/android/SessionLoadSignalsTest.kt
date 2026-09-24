package dev.repertosaurus.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.session.InMemoryTimerStore
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.data.DatabaseState
import dev.repertosaurus.session.InMemorySessionPreferences
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * **Journal F30's two load signals on `SessionViewModel`**, for onboarding (P14) to gate on:
 * `firstLoadDone` is false until the first load has completed, whatever it found, and
 * `performersLoaded` is false until the performers have been read.
 */
@RunWith(AndroidJUnit4::class)
class SessionLoadSignalsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableListOf<String>()

    @After
    fun cleanUp() {
        for (name in names) DatabaseFixtures.delete(context, name)
    }

    /** Held in flight, neither signal is up, though `databaseState` already reads `Ready`; released, both rise. */
    @Test
    fun bothSignalsWaitForTheirReads() {
        val holder = EditingFixtures.holder(context, name("ready"))
        val gate = Gate().apply { close() }
        val model = boot(holder, gate)

        Thread.sleep(300)
        assertIs<DatabaseState.Ready>(model.databaseState.value, "the flow's initial value, not a load")
        assertFalse(model.firstLoadDone.value, "the first load is still in flight")
        assertFalse(model.performersLoaded.value)

        gate.open()
        EditingFixtures.await("the first load") { model.firstLoadDone.value }
        EditingFixtures.await("the performer read") { model.performersLoaded.value }
        assertTrue(!model.state.value.loading)
    }

    /** An unreadable database completes the first load too, as `Unloadable`; performers are never read. */
    @Test
    fun anUnreadableDatabaseStillCompletesTheFirstLoad() {
        val name = name("future")
        DatabaseFixtures.writeFuture(context, name)
        val model = boot(DatabaseHolder(context, TEST_DEVICE, name), Gate())

        EditingFixtures.await("the first load") { model.firstLoadDone.value }
        assertIs<DatabaseState.Unloadable>(model.databaseState.value)
        Thread.sleep(300)
        assertFalse(model.performersLoaded.value, "no performer read on an unreadable database")
    }

    private fun boot(holder: DatabaseHolder, io: Gate): SessionViewModel {
        lateinit var model: SessionViewModel
        EditingFixtures.onMain { model = SessionViewModel(holder, InMemorySessionPreferences(), TEST_DEVICE, InMemoryTimerStore(), io) }
        return model
    }

    private fun name(suffix: String): String = "load-signals-test-$suffix.db".also { names += it }
}
