package dev.repertosaurus.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.repertosaurus.core.RatingKind
import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.data.Part
import dev.repertosaurus.data.PartRatings
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.PageFilter
import dev.repertosaurus.session.RatingsEditor
import dev.repertosaurus.session.RatingsSong
import dev.repertosaurus.session.RatingsSource
import dev.repertosaurus.session.RatingsTarget
import dev.repertosaurus.session.RepertoireCoordinator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * State holder for the ratings editor — triage T1-T5. The state and its pure changes are the core's
 * [RatingsEditor]; what is here is the scope, the queue and the dispatcher. **One editor for both
 * entry points** (T1): the Repertoire route and the View menu open this same ViewModel.
 *
 * **Nothing here reaches the practice path.** Every write is `repository.ratings.setRating`.
 *
 * **RS9, T2: a tap writes at once, off the main thread, with no Save.** The level shows on the tap;
 * the write then waits its turn on [writes] through [serialised], so two quick taps on one control
 * land in the order they were made. Each landing shows the level it wrote, or, when it failed, the
 * level it replaced, with the reason on the error channel (E43). A tap binds to the database that was
 * current when it was made (F18 N2).
 */
public class RatingsEditorViewModel(
    private val holder: DatabaseHolder,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    /** The open editor, or null when none is. Held here, so letter, search and filter survive rotation (T5). */
    private val _state = MutableStateFlow<RatingsEditor?>(null)
    public val state: StateFlow<RatingsEditor?> = _state.asStateFlow()

    private val writes = Mutex()

    /** Which editor a result belongs to. Main thread only, like `RepertoireViewModel`'s ticket. */
    private var ticket = 0L

    /** R26 and F18 B1: suspends until every rating tapped so far has landed. */
    public suspend fun awaitIdle() {
        writes.withLock { }
    }

    /**
     * Open the editor on [target] (T1). A second call for the target already open does nothing: that
     * is a rotation re-running the screen's effect, and the page must survive it. [after] is waited on
     * first — the Repertoire route's queue, so a toggle tapped just before cannot be missed by T1's
     * "enabled" read.
     */
    public fun open(target: RatingsTarget, after: suspend () -> Unit = {}) {
        if (_state.value?.target == target) return
        val opened = ++ticket
        _state.value = RatingsEditor(target, opened)
        viewModelScope.launch {
            after()
            serialised(writes, io, work = { read(target) }) { outcome ->
                _state.update { editor ->
                    if (editor?.ticket != opened) {
                        editor
                    } else {
                        outcome.fold(
                            onSuccess = { (songs, ratings) -> editor.loaded(songs, ratings) },
                            onFailure = { editor.copy(loading = false, error = Messages.couldNot("read the ratings", it)) },
                        )
                    }
                }
            }
        }
    }

    /** Close the editor. A write already tapped still runs; only its landing is dropped. */
    public fun close() {
        ticket++
        _state.value = null
    }

    public fun setLetter(letter: Char) {
        _state.update { it?.withLetter(letter) }
    }

    public fun setQuery(query: String) {
        _state.update { it?.withQuery(query) }
    }

    public fun setFilter(filter: PageFilter) {
        _state.update { it?.withFilter(filter) }
    }

    /** RS9, T2: set one rating, or clear it when [level] is null. */
    public fun rate(songId: String, kind: RatingKind, level: RatingLevel?) {
        val editor = _state.value ?: return
        if (editor.loading) return
        val was = editor.levelOf(songId, kind)
        if (was == level) return
        val part = Part(songId, editor.target.performerId, editor.target.instrumentId)
        val bound = BoundDatabase(holder)
        _state.update { it?.withLevel(songId, kind, level)?.copy(error = null) }
        viewModelScope.launch {
            serialised(writes, io, work = { bound.use { it.ratings.setRating(part, kind, level) } }) { outcome ->
                _state.update { state ->
                    if (state?.ticket != editor.ticket) {
                        state
                    } else {
                        outcome.fold(
                            onSuccess = { state.withLevel(songId, kind, level) },
                            onFailure = { failure ->
                                state.withLevel(songId, kind, was).copy(error = describeFailure(failure))
                            },
                        )
                    }
                }
            }
        }
    }

    /** T1: the songs, from the target's source, and the part's live ratings. Blocking; on [io]. */
    private fun read(target: RatingsTarget): Pair<List<RatingsSong>, Map<String, PartRatings>> {
        val repository = holder.repository
        val songs = when (val source = target.source) {
            RatingsSource.EnabledParts ->
                RepertoireCoordinator(repository).songs(target.performerId, target.instrumentId)
                    .filter { it.held }
                    .map { RatingsSong(it.songId, it.title, it.artistName) }
            is RatingsSource.ViewPool -> source.songs
        }
        return songs to repository.ratings.ratingsFor(target.performerId, target.instrumentId)
    }

    public companion object {
        /** F18 N2's dropped write says so in its own words. */
        private fun describeFailure(failure: Throwable): String =
            if (failure is DatabaseReplaced) Messages.DATABASE_REPLACED else Messages.couldNot("save that rating", failure)

        public fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { RatingsEditorViewModel(graph.holder) }
        }
    }
}
