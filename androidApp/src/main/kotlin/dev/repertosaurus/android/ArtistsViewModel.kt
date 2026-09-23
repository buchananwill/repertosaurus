package dev.repertosaurus.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.data.SongCatalog
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.Notice
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
 * State holder for the Artists route — repertoire-editing R24, R25, R25a.
 *
 * Every read and write goes through one [Mutex] by [serialised], and each write re-reads the list
 * inside it, so no write completes out of order against the screen (the property E45 and R8
 * name). R28: the catalog is reached per call through `holder.repository`, so an import's swap is
 * picked up; F18 N2: a write binds to the database that was current when it was tapped.
 *
 * S8: every `require` in the catalog — a blank name — and every SQLite failure is caught onto
 * the error channel. Nothing here can take the app down. The words are the core's [Messages].
 */
public class ArtistsViewModel(
    private val holder: DatabaseHolder,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(ArtistsState())
    public val state: StateFlow<ArtistsState> = _state.asStateFlow()

    private val writes = Mutex()

    /** R26 and F18 B1: suspends until every write queued so far has landed. */
    public suspend fun awaitIdle() {
        writes.withLock { }
    }

    public fun load() {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            serialised(writes, io, work = { holder.repository.catalog.artistsWithSongCounts() }) { outcome ->
                _state.update { state ->
                    outcome.fold(
                        onSuccess = { state.copy(artists = it, busy = false) },
                        onFailure = { failure ->
                            state.copy(busy = false, error = Messages.couldNot("load artists", failure))
                        },
                    )
                }
            }
        }
    }

    /** F16 #8: the search, in memory over the list already held (E36). */
    public fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    /**
     * R24: rename, and set the sort name; a blank sort name is derived from the name as create
     * derives it. The id is never re-derived (R22).
     */
    public fun rename(artistId: String, name: String, sortName: String) {
        if (name.isBlank()) {
            _state.update { it.copy(message = null, error = Messages.ARTIST_NEEDS_NAME) }
            return
        }
        write { catalog -> Messages.artistRename(name, catalog.renameArtist(artistId, name, sortName)) }
    }

    /** R25, R25a, R23d: each refusal says why, and a removal says what it removed. */
    public fun remove(artistId: String, name: String) {
        write { catalog -> Messages.artistRemoval(name, catalog.removeArtist(artistId)) }
    }

    public fun clearMessages() {
        _state.update { it.copy(message = null, error = null) }
    }

    /** One write, then the list again, both inside the queue. E43: two channels, cleared together. */
    private fun write(block: (SongCatalog) -> Notice) {
        val bound = BoundDatabase(holder)
        _state.update { it.copy(busy = true, message = null, error = null) }
        viewModelScope.launch {
            serialised(
                writes,
                io,
                work = {
                    bound.use { repository ->
                        val catalog = repository.catalog
                        block(catalog) to catalog.artistsWithSongCounts()
                    }
                },
            ) { outcome ->
                _state.update { state ->
                    outcome.fold(
                        onSuccess = { (notice, artists) ->
                            val said = WriteOutcome.of(notice)
                            state.copy(artists = artists, busy = false, message = said.message, error = said.error)
                        },
                        onFailure = { failure ->
                            state.copy(busy = false, error = WriteOutcome.failed(failure).error)
                        },
                    )
                }
            }
        }
    }

    public companion object {
        public fun factory(graph: AppGraph): androidx.lifecycle.ViewModelProvider.Factory = viewModelFactory {
            initializer { ArtistsViewModel(graph.holder) }
        }
    }
}

/** The Artists route's state. */
public data class ArtistsState(
    val artists: List<SongCatalog.ArtistEntry> = emptyList(),
    /** F16 #8: the search box's text. The list is filtered in memory with `ArtistSearch`. */
    val query: String = "",
    val busy: Boolean = false,
    /** E43: a confirmation, and only ever a confirmation. */
    val message: String? = null,
    /** E43: a refusal or failure, rendered in the error colour. */
    val error: String? = null,
)
