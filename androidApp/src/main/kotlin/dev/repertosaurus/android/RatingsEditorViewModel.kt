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
 * **What the ratings editor reads and writes** — the database behind a seam, so the ViewModel's queue
 * and landings are JVM-tested against a store that can fail (safety review F20 B2). Blocking; the
 * ViewModel calls both on its IO dispatcher.
 */
internal interface RatingsAccess {
    /** T1: the target's songs and the part's live ratings. */
    fun read(target: RatingsTarget): Pair<List<RatingsSong>, Map<String, PartRatings>>

    /** A write bound, now, to the database that is current (F18 N2); run later, off the main thread. */
    fun bindWrite(): (Part, RatingKind, RatingLevel?) -> Unit
}

/** [RatingsAccess] over the app's database. */
private class HolderRatingsAccess(private val holder: DatabaseHolder) : RatingsAccess {
    override fun read(target: RatingsTarget): Pair<List<RatingsSong>, Map<String, PartRatings>> {
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

    override fun bindWrite(): (Part, RatingKind, RatingLevel?) -> Unit {
        val bound = BoundDatabase(holder)
        return { part, kind, level -> bound.use { it.ratings.setRating(part, kind, level) } }
    }
}

/**
 * State holder for the ratings editor (triage T1-T5): one for both entry points. The state and its
 * pure changes are the core's [RatingsEditor]; here are the scope, the queue and the dispatcher.
 *
 * **RS9, T2: a tap shows at once and writes at once, off the main thread, with no Save.** Writes wait
 * their turn on [writes], so they land in tap order; what each landing shows is
 * [RatingsEditor.landed]'s rule (F20 B1). Nothing here reaches the practice path.
 */
public class RatingsEditorViewModel internal constructor(
    private val access: RatingsAccess,
    private val io: CoroutineDispatcher,
) : ViewModel() {

    public constructor(holder: DatabaseHolder, io: CoroutineDispatcher = Dispatchers.IO) :
        this(HolderRatingsAccess(holder), io)

    /** The open editor, or null. Held here, so the page survives rotation (journal session 11, D59 #4). */
    private val _state = MutableStateFlow<RatingsEditor?>(null)
    public val state: StateFlow<RatingsEditor?> = _state.asStateFlow()

    private val writes = Mutex()

    /** Which editor a result belongs to. Main thread only. */
    private var ticket = 0L

    /** R26, F18 B1: suspends until every rating tapped so far has landed. */
    public suspend fun awaitIdle() {
        writes.withLock { }
    }

    /**
     * Open the editor on [target] (T1); a no-op when it is already open, as after a rotation. The read
     * waits for [after] first: the Repertoire route's toggle queue, so a song just switched on is read.
     */
    public fun open(target: RatingsTarget, after: suspend () -> Unit) {
        if (_state.value?.target == target) return
        val opened = ++ticket
        _state.value = RatingsEditor(target, opened)
        viewModelScope.launch {
            after()
            serialised(writes, io, work = { access.read(target) }) { outcome ->
                _state.update { editor ->
                    if (editor?.ticket != opened) {
                        editor
                    } else {
                        outcome.fold(
                            onSuccess = { (songs, ratings) -> editor.loaded(songs, ratings) },
                            onFailure = { editor.copy(loading = false, error = Messages.ratingsReadFailed(it)) },
                        )
                    }
                }
            }
        }
    }

    /** A write already tapped still runs; only its landing is dropped. */
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
        if (editor.loading || editor.levelOf(songId, kind) == level) return
        val tapped = editor.tapped(songId, kind, level)
        val tap = tapped.lastTap
        val write = access.bindWrite()
        val part = Part(songId, editor.target.performerId, editor.target.instrumentId)
        _state.value = tapped
        viewModelScope.launch {
            serialised(writes, io, work = { write(part, kind, level) }) { outcome ->
                _state.update { state ->
                    if (state?.ticket != editor.ticket) {
                        state
                    } else {
                        val landed = state.landed(songId, kind, tap, level, wrote = outcome.isSuccess)
                        outcome.exceptionOrNull()
                            ?.let { landed.copy(error = describeWriteFailure(it, Messages::ratingWriteFailed)) }
                            ?: landed
                    }
                }
            }
        }
    }

    public companion object {
        public fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { RatingsEditorViewModel(graph.holder) }
        }
    }
}
