package dev.repertosaurus.android

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.core.ColourRamp
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * **The colour ramp persists (rating-scale RS5, RS7)** through the real
 * `SharedPreferences`-backed [AndroidSessionPreferences], in the shape of
 * `NoteSpellingPreferenceTest`. The test writes a file of its own, never the app's.
 */
@RunWith(AndroidJUnit4::class)
class ColourRampPreferenceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    @After
    fun clear() {
        // commit, not apply: the next test must not race a pending write.
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** RS5 as amended 2026-09-23. */
    @Test
    fun anInstallThatNeverChoseReadsDangerToSafe() {
        assertEquals(ColourRamp.DANGER_TO_SAFE, AndroidSessionPreferences(context, FILE).colourRamp())
    }

    @Test
    fun everyChosenRampIsReadBackByAFreshInstance() {
        for (ramp in ColourRamp.entries) {
            AndroidSessionPreferences(context, FILE).rememberColourRamp(ramp)
            assertEquals(ramp, AndroidSessionPreferences(context, FILE).colourRamp())
        }
    }

    @Test
    fun aStoredValueThisBuildDoesNotKnowReadsAsTheDefault() {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("colour_ramp", "SEPIA").commit()
        assertEquals(ColourRamp.DANGER_TO_SAFE, AndroidSessionPreferences(context, FILE).colourRamp())
    }

    private companion object {
        const val FILE = "colour-ramp-test"
    }
}
