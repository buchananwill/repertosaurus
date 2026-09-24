package dev.repertosaurus.android

import dev.repertosaurus.data.Part
import dev.repertosaurus.session.ResolvedPart
import dev.repertosaurus.session.SessionState
import dev.repertosaurus.session.of
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * suggest SG13, schema-3 M14: one open sheet's skip counts for the resolved part, by song, from **one bulk
 * read** of the View's rows. A failed read is said once and counts as none for the rest of the opening.
 */
internal class SkipCounts(
    private val session: StateFlow<SessionState>,
    private val scope: CoroutineScope,
    private val counts: suspend (Collection<Part>) -> Map<Part, Long>,
    private val onFailure: (Throwable) -> Unit,
) {
    /** One part's read, landed or in flight, and the skips written since it landed. */
    private class Read(val part: ResolvedPart, val bySong: Deferred<Map<String, Long>>) {
        val written = mutableMapOf<String, Long>()

        @OptIn(ExperimentalCoroutinesApi::class)
        fun landed(): Map<String, Long> =
            bySong.getCompleted().let { read -> read + written.mapValues { (song, n) -> (read[song] ?: 0L) + n } }
    }

    private var read: Read? = null

    /**
     * [then] with [part]'s counts: none at once when [part] is null, at once when this opening has them,
     * and otherwise when the read lands. A read in flight is shared, never restarted or cancelled here.
     */
    fun withCounts(part: ResolvedPart?, then: (Map<String, Long>) -> Unit) {
        if (part == null) return then(emptyMap())
        val current = read?.takeIf { it.part == part } ?: start(part)
        if (current.bySong.isCompleted) {
            then(current.landed())
        } else {
            scope.launch {
                current.bySong.await()
                then(current.landed())
            }
        }
    }

    /**
     * What to do once a skip for [part] is written: count it, if this opening's read for its part has
     * landed and is still the current one. A read still in flight may already hold the skip, so it is not
     * counted twice.
     */
    fun recording(part: Part): () -> Unit {
        val at = read ?: return {}
        return {
            if (read === at && at.bySong.isCompleted && at.part.of(part.songId) == part) {
                at.written[part.songId] = (at.written[part.songId] ?: 0L) + 1L
            }
        }
    }

    /** The sheet closed: the next opening reads again. */
    fun forget() {
        read?.bySong?.cancel()
        read = null
    }

    /** A read for another part is left to land for whoever asked for it; their ticket decides its fate. */
    private fun start(part: ResolvedPart): Read {
        val parts = session.value.rows.map { part.of(it.songId) }
        val bySong = scope.async {
            catchingFailure { counts(parts) }.fold(
                onSuccess = { found -> found.entries.associate { (p, n) -> p.songId to n } },
                onFailure = { failure ->
                    onFailure(failure)
                    emptyMap()
                },
            )
        }
        return Read(part, bySong).also { read = it }
    }
}
