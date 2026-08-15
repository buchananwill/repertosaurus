package dev.songbook.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.songbook.core.Ids
import dev.songbook.data.SampleData
import dev.songbook.data.SongbookRepository
import dev.songbook.data.createDatabase

/**
 * Phase 1 foundation.
 *
 * This exists to prove the app launches and the database opens. It is **not** the Session
 * screen, and no part of it should be treated as a starting point for one: there is no
 * state management, no view model and no navigation here on purpose.
 *
 * The database work runs on the main thread in [onCreate] because the data set is a
 * handful of rows and the alternative is the state plumbing this dispatch deliberately
 * does not build.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val database = createDatabase(applicationContext)
        SampleData.installIfEmpty(database, deviceId = deviceId())
        val repository = SongbookRepository(database, deviceId())

        val counts = listOf(
            "instrument" to database.instrumentQueries.selectAllLive().executeAsList().size,
            "tag" to database.tagQueries.selectAllLive().executeAsList().size,
            "practice_context" to
                database.practice_contextQueries.selectAllLive().executeAsList().size,
            "artist" to database.artistQueries.selectAllLive().executeAsList().size,
            "song" to database.songQueries.selectAllLive().executeAsList().size,
        )

        val today = repository.today()
        val staleness = repository.songsByStaleness(SampleData.GUITAR, today)
        val liveEvents = staleness.sumOf { it.timesPractised }

        setContent {
            FoundationScreen(
                today = today,
                counts = counts + ("practice_event (live, guitar)" to liveEvents.toInt()),
                songs = staleness,
            )
        }
    }

    /**
     * A per-install device id. Every table carries `device_id` for the merge total order
     * of decisions 11 and 12; a real one arrives with the sync layer in phase 2.
     */
    private fun deviceId(): String {
        val preferences = getSharedPreferences("songbook", MODE_PRIVATE)
        preferences.getString(KEY_DEVICE_ID, null)?.let { return it }
        val generated = Ids.random()
        preferences.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
    }
}
