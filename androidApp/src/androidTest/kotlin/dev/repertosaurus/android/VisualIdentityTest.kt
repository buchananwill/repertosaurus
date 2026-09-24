package dev.repertosaurus.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.repertosaurus.android.DatabaseFixtures.count
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * **visual-identity §3 on the composed Session screen**: VI8's font scale, and VI10's grain, which
 * must never stand between a thumb and the one-tap log. The sheets' font scale is [SheetFontScaleTest]'s.
 */
@RunWith(AndroidJUnit4::class)
class VisualIdentityTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val fixture = SessionScreenFixture(compose, "visual-identity-test")

    @After
    fun cleanUp() = fixture.cleanUp()

    /** VI8: at 1.3 no label on the screen — header, strips, rows, badges, Add song — is cut off. */
    @Test
    fun theSessionScreenClipsNoLabelAtFontScaleOnePointThree() {
        val screen = fixture.open("scale-session", fontScale = LARGE_FONT)
        val title = screen.title
        compose.assertNoTextClipped("the session screen")
        // And the row still logs in one tap at the larger type.
        val before = count(screen.holder, "practice_event")
        compose.onNodeWithText(title).performClick()
        compose.awaitUntil("the tap's insert") { count(screen.holder, "practice_event") == before + 1 }
    }

    /** VI10: the grain draws over the whole screen, and a tap through it still logs, once. */
    @Test
    fun aTapThroughTheGrainStillLogs() {
        val screen = fixture.open("grain")
        val before = count(screen.holder, "practice_event")

        compose.onNodeWithText(screen.title).performClick()
        compose.awaitUntil("the tap's insert") { count(screen.holder, "practice_event") == before + 1 }
        compose.waitForIdle()
        assertEquals(before + 1, count(screen.holder, "practice_event"), "one tap, one event")
    }

    /** VI15: the badge takes no touch of its own, so a thumb landing on it logs the row. */
    @Test
    fun aTapOnTheBadgeLogsTheRow() {
        val screen = fixture.open("badge")
        val before = count(screen.holder, "practice_event")

        compose.onAllNodesWithText("NEVER").onFirst().performClick()
        compose.awaitUntil("the badge tap's insert") { count(screen.holder, "practice_event") == before + 1 }
    }

    private companion object {
        const val LARGE_FONT = 1.3f
    }
}
