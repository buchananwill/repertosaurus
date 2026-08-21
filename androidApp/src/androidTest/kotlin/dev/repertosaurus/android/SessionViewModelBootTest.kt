package dev.repertosaurus.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.data.DatabaseState
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Schema-compatibility S12: `SessionViewModel` gains a construction test.
 *
 * Two boot crashes in one session both landed in the one layer nothing instantiated — a
 * property initialisation-order NullPointerException, and `no such table: saved_view` thrown
 * from `RepertosaurusRepository.savedViews` on the start-up path. A test that merely constructs
 * this class against an empty, a stale and a current database and asserts the process survives
 * would have caught both, and neither had one. That is what this file is.
 *
 * The assertions are deliberately shallow — the class constructs, the first load resolves, the
 * process is still here. Depth belongs in the shared core's tests, which run without a device.
 */
@RunWith(AndroidJUnit4::class)
class SessionViewModelBootTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val names = mutableListOf<String>()

    @After
    fun cleanUp() {
        for (name in names) DatabaseFixtures.delete(context, name)
    }

    private fun name(suffix: String): String =
        "vm-boot-test-$suffix.db".also { names += it }

    @Test
    fun constructsAgainstAnEmptyDatabase() {
        val model = boot(name("empty").also { DatabaseFixtures.delete(context, it) })

        assertEquals(DatabaseState.Ready, model.databaseState.value)
        assertNotNull(model.state.value.view)
    }

    /** The pre-Views database, which is the state that crashed. It now migrates and opens. */
    @Test
    fun constructsAgainstAStaleDatabase() {
        val name = name("stale")
        DatabaseFixtures.writeVersionOne(context, name)

        val model = boot(name)

        assertEquals(DatabaseState.Ready, model.databaseState.value)
        assertNotNull(model.state.value.view)
        assertEquals(emptyList(), model.state.value.views)
    }

    @Test
    fun constructsAgainstACurrentDatabase() {
        val name = name("current")
        DatabaseFixtures.writeCurrent(context, name)

        val model = boot(name)

        assertEquals(DatabaseState.Ready, model.databaseState.value)
        assertNotNull(model.state.value.view)
    }

    /**
     * S8 and S9 together: an unreadable database resolves to a state, not to a dead process.
     *
     * S11 is asserted here too — the session list is not quietly presented as empty with the
     * failure swallowed. An unloadable database produces a reason the user can read, and
     * `RepertosaurusApp` routes on it.
     */
    @Test
    fun survivesAnUnreadableDatabaseAndSaysWhy() {
        val name = name("unreadable")
        DatabaseFixtures.writeFuture(context, name)

        val model = boot(name)

        val state = assertIs<DatabaseState.Unloadable>(model.databaseState.value)
        assertTrue("newer build" in state.reason, state.reason)
        assertTrue(state.file.endsWith(name), state.file)
    }

    @Test
    fun survivesAFileThatIsNotADatabase() {
        val name = name("garbage")
        DatabaseFixtures.writeGarbage(context, name)

        val model = boot(name)

        assertIs<DatabaseState.Unloadable>(model.databaseState.value)
    }

    /**
     * Construct on the main thread, because `viewModelScope` is `Dispatchers.Main.immediate` and
     * the initialisation-order hazard this test exists for only reproduces there, then wait for
     * the first load to resolve one way or the other.
     */
    private fun boot(name: String): SessionViewModel {
        val holder = DatabaseHolder(context, TEST_DEVICE, name)
        lateinit var model: SessionViewModel
        instrumentation.runOnMainSync {
            model = SessionViewModel(holder, InMemoryPreferences(), TEST_DEVICE)
        }
        awaitLoad(model)
        return model
    }

    private fun awaitLoad(model: SessionViewModel) {
        val deadline = System.currentTimeMillis() + BOOT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val settled = model.databaseState.value is DatabaseState.Unloadable ||
                !model.state.value.loading
            if (settled) return
            Thread.sleep(25)
        }
        error("the first load never resolved within ${BOOT_TIMEOUT_MS}ms")
    }

}
