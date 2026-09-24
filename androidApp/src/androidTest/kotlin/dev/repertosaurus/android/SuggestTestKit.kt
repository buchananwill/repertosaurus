package dev.repertosaurus.android

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import dev.repertosaurus.android.DatabaseFixtures.count
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.session.DevicePreferences
import dev.repertosaurus.session.InMemoryDevicePreferences
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.SuggestSpoke
import dev.repertosaurus.session.SuggestTuning
import dev.repertosaurus.session.SuggestionDeck
import dev.repertosaurus.session.resolvedPart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

/** The Suggest instrumented tests' shared harness. */

/** SG1: the top-bar action, then the sheet's deck. */
internal fun ComposeContentTestRule.suggest(screen: SessionScreenFixture.Screen) {
    onNodeWithContentDescription(Messages.SUGGEST).performClick()
    awaitUntil("the sheet's deck") { screen.session.suggestions.deck.value != null }
    waitForIdle()
}

internal fun ComposeContentTestRule.another() {
    onNodeWithText(Messages.SUGGEST_ANOTHER, ignoreCase = true).performClick()
    waitForIdle()
}

internal fun ComposeContentTestRule.openTune() {
    onNodeWithTag(SuggestTags.TUNE).performScrollTo().performClick()
    waitForIdle()
}

internal fun ComposeContentTestRule.handle(spoke: SuggestSpoke): SemanticsNodeInteraction =
    onNodeWithTag(SuggestTags.spoke(spoke), useUnmergedTree = true)

/** A drag from [spoke]'s handle outward along its spoke, by [distance] pixels. */
internal fun ComposeContentTestRule.dragOutward(spoke: SuggestSpoke, distance: Float = 120f) {
    val (x, y) = spoke.direction()
    val outward = Offset(x.toFloat(), y.toFloat()) * distance
    handle(spoke).performScrollTo().performTouchInput { swipe(start = center, end = center + outward, durationMillis = 400) }
    waitForIdle()
}

/** The song on the card, or null when the sheet shows none. */
internal fun SessionScreenFixture.Screen.current(): String? =
    (session.suggestions.deck.value as? SuggestionDeck.Showing)?.current?.songId

internal fun SessionScreenFixture.Screen.titleOf(songId: String?): String =
    session.state.value.rows.first { it.songId == songId }.title

/** Every table a suggestion could conceivably write to. */
internal fun writes(holder: DatabaseHolder): List<Long> =
    listOf("practice_event", "practice_event_void", "suggestion_skip").map { count(holder, it) }

/**
 * Run [body] with a session on **one** IO thread, and a `drain` that returns once every IO task queued
 * before it has run: the only honest way to say "nothing was written" of writes that are async.
 */
internal fun withOneIoThread(body: (io: CoroutineDispatcher, drain: () -> Unit) -> Unit) {
    val executor = Executors.newSingleThreadExecutor()
    val io = executor.asCoroutineDispatcher()
    try {
        body(io) { runBlocking(io) {} }
    } finally {
        io.close()
    }
}

/**
 * The sample songs with [owner] added as this device's owner performer, so triage T9 resolves their part
 * (suggest SG14), opened once the part's ratings are fresh. [device] holds the tuning and the owner.
 */
internal fun SessionScreenFixture.openWithOwner(
    suffix: String,
    owner: String = "Will",
    tuning: SuggestTuning = SuggestTuning.DEFAULT.copy(countSkips = true),
    device: DevicePreferences = InMemoryDevicePreferences(suggestTuning = tuning),
    io: CoroutineDispatcher = Dispatchers.IO,
): SessionScreenFixture.Screen {
    var ownerId: String? = null
    val screen = open(suffix, device = device, io = io, prepare = { holder ->
        ownerId = EditingFixtures.performer(holder, owner).also(device::rememberOwnerPerformer)
    })
    compose.awaitUntil("$owner's part") {
        screen.session.state.value.let { it.resolvedPart?.performerId == ownerId && it.ratedFor == it.resolvedPart }
    }
    compose.waitForIdle()
    return screen
}
