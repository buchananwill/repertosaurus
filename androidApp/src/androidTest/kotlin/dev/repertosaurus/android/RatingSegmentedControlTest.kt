package dev.repertosaurus.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.repertosaurus.android.theme.RepertosaurusTheme
import dev.repertosaurus.core.RatingLevel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * **Rating-scale RS9 on the composed control**: one tap sets a value, a tap on the selected
 * segment clears it to null, and every change costs exactly one tap and one callback.
 */
@RunWith(AndroidJUnit4::class)
class RatingSegmentedControlTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var value by mutableStateOf<RatingLevel?>(null)
    private val changes = mutableListOf<RatingLevel?>()

    private fun compose(lowest: RatingLevel = RatingLevel.NOT_AT_ALL) {
        compose.setContent {
            RepertosaurusTheme {
                RatingSegmentedControl(
                    value = value,
                    onValueChange = {
                        changes += it
                        value = it
                    },
                    lowest = lowest,
                    tagPrefix = PREFIX,
                )
            }
        }
    }

    private fun tap(level: RatingLevel) {
        compose.onNodeWithTag(RatingTags.segment(PREFIX, level)).performClick()
        compose.waitForIdle()
    }

    @Test
    fun oneTapSetsAndATapOnTheSelectedSegmentClears() {
        compose()

        tap(RatingLevel.CERTAINLY)
        assertEquals(RatingLevel.CERTAINLY, value)
        assertEquals(listOf<RatingLevel?>(RatingLevel.CERTAINLY), changes, "one tap, one change")

        tap(RatingLevel.NOT_AT_ALL)
        assertEquals(RatingLevel.NOT_AT_ALL, value)
        assertEquals(2, changes.size, "a second value is one more tap")

        tap(RatingLevel.NOT_AT_ALL)
        assertNull(value, "a tap on the selected segment clears to unrated")
        assertEquals(listOf(RatingLevel.CERTAINLY, RatingLevel.NOT_AT_ALL, null), changes)
    }

    @Test
    fun everySegmentShowsItsNumberAndLabelAndIsATallEnoughTarget() {
        compose()
        for (level in RatingLevel.entries) {
            compose.onNodeWithText(level.label).assertIsDisplayed()
            compose.onNodeWithTag(RatingTags.segment(PREFIX, level)).assertHeightIsAtLeast(48.dp)
        }
    }

    /** RS9, RS10: nothing below `lowest` is offered. */
    @Test
    fun aRaisedLowestHidesTheLevelsBelowIt() {
        compose(lowest = RatingLevel.SOMEWHAT)
        compose.onNodeWithTag(RatingTags.segment(PREFIX, RatingLevel.NOT_AT_ALL)).assertDoesNotExist()
        compose.onNodeWithText(RatingLevel.NOT_AT_ALL.label).assertDoesNotExist()
        tap(RatingLevel.EXCEPTIONALLY)
        assertEquals(RatingLevel.EXCEPTIONALLY, value)
    }

    private companion object {
        const val PREFIX = "test-rating"
    }
}
