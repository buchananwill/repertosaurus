package dev.repertosaurus.android

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.core.NoteSpelling
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * **The note spelling persists (repertoire-editing R40-R42)** through the real
 * `SharedPreferences`-backed [AndroidSessionPreferences]: a value written by one instance is read
 * back by a fresh one, and an install that never chose reads R41's default. The test writes a file
 * of its own, never the app's.
 */
@RunWith(AndroidJUnit4::class)
class NoteSpellingPreferenceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    @After
    fun clear() {
        // commit, not apply: the next test must not race a pending write.
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun anInstallThatNeverChoseReadsSimplified() {
        assertEquals(NoteSpelling.SIMPLIFIED, AndroidSessionPreferences(context, FILE).noteSpelling())
    }

    @Test
    fun aChosenSpellingIsReadBackByAFreshInstance() {
        AndroidSessionPreferences(context, FILE).rememberNoteSpelling(NoteSpelling.AS_WRITTEN)
        assertEquals(NoteSpelling.AS_WRITTEN, AndroidSessionPreferences(context, FILE).noteSpelling())

        AndroidSessionPreferences(context, FILE).rememberNoteSpelling(NoteSpelling.SIMPLIFIED)
        assertEquals(NoteSpelling.SIMPLIFIED, AndroidSessionPreferences(context, FILE).noteSpelling())
    }

    @Test
    fun aStoredValueThisBuildDoesNotKnowReadsAsTheDefault() {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("note_spelling", "ENHARMONIC").commit()
        assertEquals(NoteSpelling.DEFAULT, AndroidSessionPreferences(context, FILE).noteSpelling())
    }

    private companion object {
        const val FILE = "note-spelling-test"
    }
}
