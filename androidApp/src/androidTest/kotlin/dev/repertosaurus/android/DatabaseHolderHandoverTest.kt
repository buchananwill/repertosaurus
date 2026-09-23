package dev.repertosaurus.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.data.DatabaseState
import dev.repertosaurus.data.ImportRejected
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * **The handover import, proven before any APK goes to the user** (session 09 ruling; naming.md's
 * follow-up on `DatabaseHolder`).
 *
 * The next APK installs beside the user's current one (`applicationId` changed in the rename), and
 * the user's month of practice logs exists only in the old app's export — a file the old build
 * named `repertaurus-YYYY-MM-DD.db` (its `exportFileName` at `68c25f7`). This pins that
 * `stageImport` accepts a current-schema file **under that name**, carrying songs and practice
 * logs, and that the commit lands them; and that it refuses a file that is not SQLite at all.
 *
 * **Instrumented, not JVM, and why.** The follow-up asked for a JVM test with a temp-dir `Context`
 * double. `DatabaseHolder` cannot run on the JVM in this project: `stageImport` opens the staged
 * file with `android.database.sqlite.SQLiteDatabase` (the framework's SQLite, deliberately, so the
 * file is inspected before any driver can migrate it), and `commitImport` reopens through
 * `AndroidSqliteDriver`. The unit-test classpath has only `android.jar`'s stubs, which throw on
 * every call, and no Robolectric. A `Context` double would get the test as far as the header
 * check and no further — the acceptance half, which is the half the user's logs depend on, needs
 * a real SQLite. No seam was added to `DatabaseHolder` for this.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseHolderHandoverTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableListOf<String>()
    private val files = mutableListOf<File>()

    @After
    fun cleanUp() {
        for (name in names) DatabaseFixtures.delete(context, name)
        for (file in files) file.delete()
    }

    /**
     * A current-schema export with the old brand's name, holding the sample repertoire and its
     * practice log, is accepted, previewed truthfully, and committed with every row intact.
     */
    @Test
    fun anExportNamedByTheOldBrandIsAcceptedAndItsLogsLand() {
        val source = "handover-source.db".also { names += it }
        val sourceHolder = EditingFixtures.holder(context, source)
        val songs = sourceHolder.repository.catalog.songs().size.toLong()
        val events = DatabaseFixtures.count(sourceHolder, "practice_event")
        assertTrue(events > 0L, "the fixture must carry practice logs, or the test proves nothing about them")
        sourceHolder.close()

        // Byte for byte what the old app's export writes: a copy of the database file, under the
        // name the old build suggested.
        val export = File(context.filesDir, OLD_EXPORT_NAME).also { files += it }
        context.getDatabasePath(source).copyTo(export, overwrite = true)

        val live = "handover-live.db".also { names += it }
        val holder = DatabaseHolder(context, TEST_DEVICE, live)
        assertEquals(DatabaseState.Ready, holder.load())

        val preview = export.inputStream().use { holder.stageImport(it) }
        assertEquals(songs, preview.songs, "the preview names every song in the export")
        assertEquals(events, preview.practiceEvents, "the preview names every practice log in the export")

        assertEquals(DatabaseState.Ready, holder.commitImport())
        assertEquals(songs, holder.repository.catalog.songs().size.toLong(), "every song landed")
        assertEquals(events, DatabaseFixtures.count(holder, "practice_event"), "every practice log landed")
        assertEquals(
            0L,
            DatabaseFixtures.count(holder, "practice_event WHERE song_id NOT IN (SELECT id FROM song)"),
            "every log still points at its song",
        )
        holder.close()
    }

    /** The same name on a file that is not a database is refused, and nothing is overwritten. */
    @Test
    fun aFileByThatNameThatIsNotSqliteIsRefused() {
        val export = File(context.filesDir, OLD_EXPORT_NAME).also { files += it }
        export.writeBytes("not a database, whatever the name says".toByteArray())

        val live = "handover-live-garbage.db".also { names += it }
        val holder = DatabaseHolder(context, TEST_DEVICE, live)
        assertEquals(DatabaseState.Ready, holder.load())

        val rejected = assertFailsWith<ImportRejected> { export.inputStream().use { holder.stageImport(it) } }
        assertTrue("not a SQLite database" in rejected.message.orEmpty(), rejected.message.orEmpty())
        assertEquals(DatabaseState.Ready, holder.load(), "the live database is untouched")
        holder.close()
    }

    private companion object {
        /** The old brand's export name — `"repertaurus-$today.db"` at `68c25f7`, mirrored exactly. */
        const val OLD_EXPORT_NAME: String = "repertaurus-2026-09-20.db"
    }
}
