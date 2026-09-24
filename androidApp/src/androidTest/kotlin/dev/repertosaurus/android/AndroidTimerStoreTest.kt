package dev.repertosaurus.android

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.session.PracticeTimer
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** timer TM10, schema-compatibility S8: the stored timer reads back, and anything else reads as none, never a throw. */
@RunWith(AndroidJUnit4::class)
class AndroidTimerStoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val store = AndroidTimerStore(context, FILE)

    @After
    fun cleanUp() {
        context.deleteSharedPreferences(FILE)
    }

    @Test
    fun aWrittenTimerReadsBackAndAClearReadsNone() {
        val running = PracticeTimer.Running("song", "vocal", 1_790_280_000_000L)
        store.write(running)
        assertEquals(running, AndroidTimerStore(context, FILE).read())
        store.write(null)
        assertNull(AndroidTimerStore(context, FILE).read())
    }

    @Test
    fun nothingStoredReadsNone() = assertNull(store.read())

    /** A write cut short: the song and the instrument, no start. */
    @Test
    fun aTruncatedValueReadsNone() {
        raw.edit().putString("timer_song_id", "song").putString("timer_instrument_id", "vocal").commit()
        assertNull(store.read())
    }

    /** The start stored as text, and the song as a number: the wrong types read as none. */
    @Test
    fun garbageReadsNone() {
        raw.edit()
            .putInt("timer_song_id", 7)
            .putString("timer_instrument_id", "vocal")
            .putString("timer_started_at_epoch_ms", "yesterday")
            .commit()
        assertNull(store.read())

        raw.edit().clear().putString("timer_song_id", "song").putString("timer_instrument_id", "vocal")
            .putString("timer_started_at_epoch_ms", "yesterday").commit()
        assertNull(store.read())
    }

    private companion object {
        const val FILE = "android-timer-store-test"
    }
}
