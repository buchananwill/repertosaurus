package dev.repertosaurus.android

import dev.repertosaurus.data.PartRatings
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.ResolvedPart
import dev.repertosaurus.session.SessionState
import dev.repertosaurus.session.ratingsStale
import dev.repertosaurus.session.resolvedPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * **triage T9: stale ratings are read again, and this is the only place that does it.** It watches
 * [state] for rows whose ratings are not the resolved part's (the performers were read, the owner
 * changed, a loader landed without them) and lays that part's ratings on them.
 *
 * - A newer part, or a reload starting, cancels the read in flight, and a read that lands after the
 *   part has moved on or while rows are reloading is dropped anyway: the late read is never another
 *   performer's ratings under this one's name.
 * - A read that fails leaves the rows unrated (T8's "no statement", so triage sorts as staleness) and
 *   says so. It never keeps the previous part's ratings.
 * - Nobody resolving clears the ratings at once, with no read.
 *
 * [read] does its own IO. [apply] is the state holder's atomic update.
 */
internal class RatingsSync(
    private val state: StateFlow<SessionState>,
    scope: CoroutineScope,
    private val read: suspend (ResolvedPart) -> Map<String, PartRatings>,
    private val apply: ((SessionState) -> SessionState) -> Unit,
) {
    init {
        scope.launch {
            state.map { it.ratingsStale to it.resolvedPart }
                .distinctUntilChanged()
                .collectLatest { (stale, part) -> if (stale) sync(part) }
        }
    }

    private suspend fun sync(part: ResolvedPart?) {
        if (part == null) {
            apply { it.withRatings(null, emptyMap()) }
            return
        }
        val ratings = catchingFailure { read(part) }
        apply { current ->
            if (current.loading || current.resolvedPart != part) {
                current
            } else {
                ratings.fold(
                    onSuccess = { current.withRatings(part, it) },
                    onFailure = { failure ->
                        current.withRatings(null, emptyMap()).withMessage(Messages.ratingsReadFailed(failure))
                    },
                )
            }
        }
    }
}
