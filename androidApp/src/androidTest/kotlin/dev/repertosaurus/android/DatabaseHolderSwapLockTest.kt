package dev.repertosaurus.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.data.DatabaseState
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **Repertoire-editing F23 N1: an import cannot swap the database while a route's write is
 * running.** A write holds `DatabaseHolder.whileCurrent` — the swap lock, shared — for its whole
 * body; `commitImport` takes it exclusively, so it waits for the write, and the write finishes
 * against the database it started on.
 *
 * Instrumented because `commitImport` is framework SQLite and `AndroidSqliteDriver`
 * (see `DatabaseHolderHandoverTest`).
 */
@RunWith(AndroidJUnit4::class)
class DatabaseHolderSwapLockTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableListOf<String>()
    private val files = mutableListOf<File>()

    @After
    fun cleanUp() {
        for (name in names) DatabaseFixtures.delete(context, name)
        for (file in files) file.delete()
    }

    @Test
    fun anImportWaitsForAWriteThatIsRunning() {
        val live = "swap-lock-live.db".also { names += it }
        val holder = EditingFixtures.holder(context, live)
        val songsBefore = holder.repository.catalog.songs().size

        // Something to import: a copy of the live file, staged.
        val export = File(context.filesDir, "swap-lock-export.db").also { files += it }
        holder.databaseFile().copyTo(export, overwrite = true)
        export.inputStream().use { holder.stageImport(it) }

        val generation = holder.generation
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var writeSaw: Int? = null
        val writer = thread {
            holder.whileCurrent(generation) { repository ->
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
                // The body still runs against the database it started on.
                writeSaw = assertNotNull(repository).catalog.songs().size
            }
        }
        assertTrue(entered.await(10, TimeUnit.SECONDS), "the write started")

        var committed: DatabaseState? = null
        val importer = thread { committed = holder.commitImport() }

        importer.join(500)
        assertTrue(importer.isAlive, "F23 N1: the import must wait for the running write")
        assertNull(committed)
        assertEquals(generation, holder.generation, "the database was not swapped under the write")

        release.countDown()
        writer.join(10_000)
        importer.join(10_000)

        assertEquals(songsBefore, writeSaw, "the write finished against its own database")
        assertEquals(DatabaseState.Ready, committed)
        assertTrue(holder.generation > generation, "the import then swapped")
        assertNull(holder.whileCurrent(generation) { it }, "a write bound to the old database is dropped (F18 N2)")
        holder.close()
    }

    /**
     * **F25 N5: `startFresh` waits for a running write too** — it takes the same exclusive half of
     * the swap lock, and since F25 N3 closes through `closeLocked` rather than the lock-taking
     * `close`. The write finishes against the database it started on; only then is the file thrown
     * away and an empty one created.
     */
    @Test
    fun startFreshWaitsForAWriteThatIsRunning() {
        val live = "swap-lock-fresh.db".also { names += it }
        val holder = EditingFixtures.holder(context, live)
        val songsBefore = holder.repository.catalog.songs().size
        assertTrue(songsBefore > 0)

        val generation = holder.generation
        val running = RunningWrite(holder, generation)

        var fresh: DatabaseState? = null
        val fresher = thread { fresh = holder.startFresh() }

        fresher.join(500)
        assertTrue(fresher.isAlive, "F25 N5: start-fresh must wait for the running write")
        assertNull(fresh)
        assertEquals(generation, holder.generation, "the database was not thrown away under the write")

        assertEquals(songsBefore, running.finish(), "the write finished against its own database")
        fresher.join(10_000)
        assertEquals(DatabaseState.Ready, fresh)
        assertTrue(holder.generation > generation)
        assertTrue(holder.repository.catalog.songs().isEmpty(), "and then the database was replaced by an empty one")
        holder.close()
    }

    /**
     * **F25 N1: an export waits for a running write**, so the copy — the user's only backup — is
     * never taken in the middle of one. The export then completes, a byte copy of the whole file.
     */
    @Test
    fun anExportWaitsForAWriteThatIsRunning() {
        val live = "swap-lock-export-wait.db".also { names += it }
        val holder = EditingFixtures.holder(context, live)
        val songsBefore = holder.repository.catalog.songs().size
        val running = RunningWrite(holder, holder.generation)

        val out = ByteArrayOutputStream()
        var bytes: Long? = null
        val exporter = thread { bytes = holder.exportTo(out) }

        exporter.join(500)
        assertTrue(exporter.isAlive, "F25 N1: the export must wait for the running write")
        assertNull(bytes)

        assertEquals(songsBefore, running.finish())
        exporter.join(10_000)
        assertEquals(holder.databaseFile().length(), bytes)
        assertEquals(bytes, out.size().toLong())
        holder.close()
    }

    /** A route write held open under `whileCurrent` until [finish]; it returns what the write read. */
    private class RunningWrite(holder: DatabaseHolder, generation: Long) {
        private val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private var saw: Int? = null
        private val writer = thread {
            holder.whileCurrent(generation) { repository ->
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
                saw = assertNotNull(repository).catalog.songs().size
            }
        }

        init {
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the write started")
        }

        fun finish(): Int? {
            release.countDown()
            writer.join(10_000)
            return saw
        }
    }
}
