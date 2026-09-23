package dev.repertosaurus.android

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.repertosaurus.android.DatabaseFixtures.count
import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.data.DatabaseHolder
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * **rating-scale RS10, RS13 and RS14 on the composed Session screen**, with feel widened to 0-3 by
 * schema 3. A plain tap still logs with
 * no sheet at all. Counts are read out of the file, as deltas over the sample data's own practice.
 */
@RunWith(AndroidJUnit4::class)
class FeelSheetTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableListOf<String>()

    @After
    fun cleanUp() {
        for (name in names) DatabaseFixtures.delete(context, name)
    }

    @Test
    fun longPressCertainlyAndLogWritesFeelTwo() {
        val (holder, title) = screen("certainly")
        // The sample data carries practice of its own, so every count here is a delta.
        val before = count(holder, "practice_event")
        val feelTwoBefore = count(holder, "practice_event WHERE feel = 2")

        compose.onNodeWithText(title).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag(RatingTags.segment(FeelSheetTags.FEEL, RatingLevel.CERTAINLY)).performClick()
        compose.onNodeWithText("Log it").performClick()

        compose.awaitUntil("the feel-2 insert") {
            count(holder, "practice_event WHERE feel = 2") == feelTwoBefore + 1
        }
        assertEquals(before + 1, count(holder, "practice_event"))

        // RS14: the undo snackbar names the feel in words. Written out, not built with
        // `Messages.logged`, so a change to the wording has to change this test too.
        val snackbar = "Logged $title · feel: certainly"
        compose.awaitUntil("the snackbar") {
            compose.onAllNodesWithText(snackbar).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * RS10 since schema 3: the sheet offers all four levels, and "not at all" is logged as
     * `feel = 0` and named in the snackbar.
     */
    @Test
    fun theFeelSheetOffersAllFourAndNotAtAllWritesFeelZero() {
        val (holder, title) = screen("not-at-all")
        val feelZeroBefore = count(holder, "practice_event WHERE feel = 0")

        compose.onNodeWithText(title).performTouchInput { longClick() }
        compose.waitForIdle()
        for (level in RatingLevel.entries) {
            compose.onNodeWithTag(RatingTags.segment(FeelSheetTags.FEEL, level)).assertExists()
        }
        compose.onNodeWithTag(RatingTags.segment(FeelSheetTags.FEEL, RatingLevel.NOT_AT_ALL)).performClick()
        compose.onNodeWithText("Log it").performClick()

        compose.awaitUntil("the feel-0 insert") {
            count(holder, "practice_event WHERE feel = 0") == feelZeroBefore + 1
        }
        val snackbar = "Logged $title · feel: not at all"
        compose.awaitUntil("the snackbar") {
            compose.onAllNodesWithText(snackbar).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The one-tap log is untouched: a plain tap writes one unrated event and opens nothing. */
    @Test
    fun aPlainTapStillLogsWithNoSheet() {
        val (holder, title) = screen("tap")
        val before = count(holder, "practice_event")
        val unratedBefore = count(holder, "practice_event WHERE feel IS NULL")

        compose.onNodeWithText(title).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(RatingTags.segment(FeelSheetTags.FEEL, RatingLevel.CERTAINLY)).assertDoesNotExist()

        compose.awaitUntil("the tap's insert") { count(holder, "practice_event") == before + 1 }
        assertEquals(unratedBefore + 1, count(holder, "practice_event WHERE feel IS NULL"))
    }

    /** A fresh database with the sample songs, the Session screen composed over it, and a title to press. */
    private fun screen(suffix: String): Pair<DatabaseHolder, String> {
        val name = "feel-sheet-test-$suffix.db".also { names += it }
        val holder = EditingFixtures.holder(context, name)
        val session = EditingFixtures.session(holder)
        val title = session.state.value.pending.first().title
        compose.setContent {
            MaterialTheme {
                SessionScreen(viewModel = session, onOpenDrawer = {}, onExport = {})
            }
        }
        compose.waitForIdle()
        return holder to title
    }
}
