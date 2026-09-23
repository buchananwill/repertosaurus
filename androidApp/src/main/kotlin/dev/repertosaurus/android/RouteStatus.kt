package dev.repertosaurus.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.data.RepertosaurusRepository
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.Notice
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Stable handles for the two channels, shared by the three repertoire-editing routes. */
internal object StatusTags {
    const val ERROR: String = "route-error"
    const val MESSAGE: String = "route-message"
}

/**
 * **E43 / R29: success and failure are separate channels, rendered differently.** The error is
 * in the error colour and sits above the confirmation; a route never paints both in one field.
 *
 * Inline rather than a snackbar, as the capability sheet does it: an error that times out is an
 * error the user may never read, and every write on these routes can be refused.
 */
@Composable
internal fun StatusLines(message: String?, error: String?, modifier: Modifier = Modifier) {
    if (message == null && error == null) return
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (error != null) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(StatusTags.ERROR),
            )
        }
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag(StatusTags.MESSAGE),
            )
        }
    }
}

/**
 * **What one write says, on its two channels** (style review F17 B3) — the one type every
 * editing ViewModel lands, replacing `Said`, `Outcome` and a bare `String?`. At most one of the
 * two is set; both null is a write that landed quietly (a tag chip shows its own result).
 *
 * The words and the channel come from the core's [Messages] as a [Notice]; this is only their
 * Android-side carrier into a state's `message` / `error` pair.
 */
internal data class WriteOutcome(val message: String? = null, val error: String? = null) {

    /** E44: a refused write must not clear a form or move a success counter. */
    val refused: Boolean get() = error != null

    companion object {
        val QUIET: WriteOutcome = WriteOutcome()

        fun of(notice: Notice?): WriteOutcome = when (notice?.channel) {
            null -> QUIET
            Notice.Channel.MESSAGE -> WriteOutcome(message = notice.text)
            Notice.Channel.ERROR -> WriteOutcome(error = notice.text)
        }

        /** A write that threw (S8), caught and said. F18 N2's dropped write says so in its own words. */
        fun failed(failure: Throwable): WriteOutcome =
            WriteOutcome(error = if (failure is DatabaseReplaced) failure.message else Messages.failed(failure))
    }
}

/**
 * **One serialised write, stated once** (style review F17 B3; R8's and E45's property): [work]
 * waits its turn on [lock], runs on [io], and its result is [land]ed **while the lock is still
 * held** — so no later write, and no read queued behind this one, can land on the screen before
 * this one does. [land] runs on the caller's dispatcher (the main thread, from
 * `viewModelScope`), and [work]'s exceptions arrive in it as a failed [Result]; nothing here can
 * crash the app (S8).
 *
 * `kotlinx.coroutines.sync.Mutex` is fair, so the lock is granted in the order the writes were
 * dispatched — which is tap order, because every dispatch happens on the main thread.
 */
internal suspend fun <T> serialised(
    lock: Mutex,
    io: CoroutineDispatcher,
    work: () -> T,
    land: (Result<T>) -> Unit,
) {
    lock.withLock {
        val result = withContext(io) { runCatching(work) }
        land(result)
    }
}

/**
 * F18 N2: the write was dispatched against a database an import has since replaced. It is
 * dropped — nothing is written to the imported file — and it lands as a failure that says so.
 */
internal class DatabaseReplaced : IllegalStateException(Messages.DATABASE_REPLACED)

/**
 * **A write bound to the database that was current when it was tapped** (repertoire-editing
 * F18 N2). Capture one at dispatch, on the main thread — it reads a counter and touches no file —
 * and run the write's body through [use] from inside the write's `work`, which runs once the write
 * holds its queue. If an import swapped the database in between, [use] throws [DatabaseReplaced]
 * and the write never reaches the new file.
 *
 * **F23 N1: and no import can swap it while [use]'s body runs** — the holder's
 * [DatabaseHolder.whileCurrent] holds its swap lock shared for the duration, so `commitImport`
 * waits for the write to finish.
 *
 * [generation] defaults to the current one. A write that was *previewed* passes the generation the
 * preview was read from instead (F28 N4: a merge), so it lands in the database the user looked at
 * or in none.
 */
internal class BoundDatabase(
    private val holder: DatabaseHolder,
    val generation: Long = holder.generation,
) {
    fun <T> use(body: (RepertosaurusRepository) -> T): T =
        holder.whileCurrent(generation) { repository -> body(repository ?: throw DatabaseReplaced()) }
}
