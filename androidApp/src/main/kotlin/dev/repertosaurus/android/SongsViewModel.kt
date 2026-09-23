package dev.repertosaurus.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.data.RepertosaurusRepository
import dev.repertosaurus.data.Resolution
import dev.repertosaurus.data.SongCatalog
import dev.repertosaurus.data.SongCatalog.LookupChoice
import dev.repertosaurus.data.SongMerge
import dev.repertosaurus.session.CapabilityCoordinator
import dev.repertosaurus.session.DetailRequest
import dev.repertosaurus.session.InstrumentChip
import dev.repertosaurus.session.InstrumentEdit
import dev.repertosaurus.session.LookupItem
import dev.repertosaurus.session.LookupKind
import dev.repertosaurus.session.LookupStores
import dev.repertosaurus.session.MergeConfirmation
import dev.repertosaurus.session.MergeSide
import dev.repertosaurus.session.MergeState
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.Notice
import dev.repertosaurus.session.SessionInstruments
import dev.repertosaurus.session.SongDetail
import dev.repertosaurus.session.SongDetailRead
import dev.repertosaurus.session.SongDraft
import dev.repertosaurus.session.SongDraftValidation
import dev.repertosaurus.session.SongField
import dev.repertosaurus.session.SongsState
import dev.repertosaurus.session.songLabel
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
 * State holder for the Songs route — repertoire-editing R11-R23. The state and its pure changes
 * are the core's [SongsState] and [SongDetail] (style review F17 B1).
 *
 * What it owns (R28): the song list, the in-memory lookup lists the type-aheads match against
 * (E36), and the open detail — the stored row, the [SongDraft] being edited, the unsaved
 * `song_instrument` edits, and the song's tags, `song_instrument` rows, practice summary and
 * line-up. **The line-up is read here and edited through `SessionViewModel`'s capability state**
 * (session 09 ruling on R18): there is one capability editor and one E45 ticket in the app.
 *
 * **Every read and every write goes through [writes]** by [serialised], one queue for the screen
 * that lands each result inside the lock, so no write completes out of order against the screen
 * and no read overtakes a write tapped before it (F18 N3). The detail's controls are disabled
 * while one is in flight. F18 N2: every write binds to the database that was current when it was
 * tapped ([BoundDatabase]).
 *
 * **S8: nothing here can crash the app.** Every catalog call is inside [serialised]'s
 * `runCatching`, which is what catches the catalog's `require`s (a blank title, a difficulty
 * outside 1-5) and any SQLite `CHECK` failure that got past [SongDraft.validate]; each lands on the
 * error channel (E43).
 */
public class SongsViewModel(
    private val holder: DatabaseHolder,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(SongsState())
    public val state: StateFlow<SongsState> = _state.asStateFlow()

    private val writes = Mutex()

    /** The merge overlay (R38): null when closed; the picker, then the preview. */
    private val _merge = MutableStateFlow<MergeState?>(null)
    public val merge: StateFlow<MergeState?> = _merge.asStateFlow()

    /** R26 and F18 B1: suspends until every write queued so far has landed. */
    public suspend fun awaitIdle() {
        writes.withLock { }
    }

    // ---- The list and the lookups (R11, E36) ----------------------------------------------

    /** The route's entry: the songs and every list a type-ahead matches against — behind any queued write (F18 N3). */
    public fun load() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            serialised(writes, io, work = { readLists(holder.repository) }) { outcome ->
                _state.update { state ->
                    outcome.fold(
                        onSuccess = { lists -> lists.applyTo(state).copy(loading = false) },
                        onFailure = { failure ->
                            state.copy(loading = false, error = Messages.couldNot("load the songs", failure))
                        },
                    )
                }
            }
        }
    }

    public fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    private class Lists(
        val songs: List<SongCatalog.SongListEntry>,
        val artists: List<RepertosaurusRepository.Artist>,
        val grooves: List<LookupItem>,
        val tags: List<LookupItem>,
        val instruments: List<InstrumentChip>,
    ) {
        fun applyTo(state: SongsState): SongsState = state.copy(
            songs = songs,
            artists = artists,
            grooves = grooves,
            tags = tags,
            instruments = instruments,
        )
    }

    private fun readLists(repository: RepertosaurusRepository): Lists = Lists(
        songs = repository.catalog.songs(),
        artists = repository.catalog.artists(),
        grooves = LookupStores.of(LookupKind.GROOVE, repository).items(),
        tags = LookupStores.of(LookupKind.TAG, repository).items(),
        instruments = SessionInstruments.chips(repository.instruments()),
    )

    // ---- Adding (R15, R21-R23a) -----------------------------------------------------------

    /**
     * The shared add sheet's commit. **E44: the sheet closes on confirmed success** — the screen
     * watches [SongsState.addsCommitted] — and a failure leaves it open with what was typed.
     *
     * R23: an add that lands on an existing row says so and **opens that row**; a created song
     * stays on the list.
     */
    public fun addSong(title: String, artistName: String, artistId: String?) {
        if (title.isBlank()) return
        val artist = LookupChoice.of(artistId, artistName)
        val bound = BoundDatabase(holder)
        _state.update { it.copy(adding = true, message = null, error = null) }
        viewModelScope.launch {
            serialised(
                writes,
                io,
                work = {
                    bound.use { repository -> repository.catalog.addSong(title, artist) to readLists(repository) }
                },
            ) { outcome ->
                _state.update { state ->
                    outcome.fold(
                        onSuccess = { (added, lists) ->
                            lists.applyTo(state).copy(
                                adding = false,
                                addsCommitted = state.addsCommitted + 1,
                                message = Messages.songAdd(added),
                                detailRequest = if (added.song.resolution == Resolution.CREATED) {
                                    state.detailRequest
                                } else {
                                    DetailRequest.Open(added.song.songId)
                                },
                            )
                        },
                        onFailure = { failure ->
                            state.copy(adding = false, error = WriteOutcome.failed(failure).error)
                        },
                    )
                }
            }
        }
    }

    // ---- The detail (R12-R21) -------------------------------------------------------------

    /**
     * Open one song's detail. Opening the song that is already open does nothing — that is a
     * rotation re-running the screen's effect, and an unsaved draft must survive it.
     */
    public fun openDetail(songId: String) {
        if (_state.value.detail?.songId == songId) return
        _state.update { it.copy(detail = SongDetail(songId = songId, busy = true)) }
        viewModelScope.launch {
            serialised(writes, io, work = { SongDetailRead.of(holder.repository, songId) }) { outcome ->
                _state.update { state ->
                    val detail = state.detail?.takeIf { it.songId == songId } ?: return@update state
                    outcome.fold(
                        onSuccess = { read ->
                            if (read.record == null) {
                                state.copy(
                                    detail = null,
                                    detailRequest = DetailRequest.Close,
                                    error = Messages.SONG_NOT_FOUND,
                                )
                            } else {
                                state.copy(detail = detail.saved(read).copy(busy = false))
                            }
                        },
                        onFailure = { failure ->
                            state.copy(
                                detail = detail.copy(busy = false),
                                error = Messages.couldNot("read that song", failure),
                            )
                        },
                    )
                }
            }
        }
    }

    /** Closing drops the draft: the screen has already asked whether to discard it (R14). */
    public fun closeDetail() {
        _state.update { it.copy(detail = null) }
    }

    /** Edit the draft. Pure and instant; nothing is written until [save] (R14). */
    public fun editDraft(change: (SongDraft) -> SongDraft) {
        _state.update { state ->
            val detail = state.detail ?: return@update state
            state.copy(detail = detail.copy(draft = change(detail.draft)))
        }
    }

    /**
     * Edit one `song_instrument` row's fields. Pure and instant; nothing is written until
     * [saveSongInstrument]. Held in the detail so R14's discard prompt covers it (F16 #6).
     */
    public fun editSongInstrument(rowId: String, edit: InstrumentEdit) {
        _state.update { state ->
            val detail = state.detail ?: return@update state
            val row = detail.instruments.firstOrNull { it.id == rowId } ?: return@update state
            state.copy(detail = detail.withInstrumentEdit(row, edit))
        }
    }

    /**
     * R14: an explicit Save writing the whole row. An invalid draft is refused **here as well
     * as** by the disabled button, so no path can send a `CHECK` violation to SQLite.
     */
    public fun save() {
        val detail = _state.value.detail ?: return
        if (detail.record == null || detail.busy) return
        val fields = when (val validation = detail.draft.validate()) {
            is SongDraftValidation.Invalid -> {
                _state.update { it.copy(message = null, error = Messages.SAVE_INVALID) }
                return
            }
            is SongDraftValidation.Valid -> validation.fields
        }
        val songId = detail.songId
        val bound = BoundDatabase(holder)
        setBusy(saving = true)
        viewModelScope.launch {
            serialised(
                writes,
                io,
                work = {
                    bound.use { repository ->
                        val saved = repository.catalog.updateSong(songId, fields)
                        Triple(saved, SongDetailRead.of(repository, songId), readLists(repository))
                    }
                },
            ) { outcome ->
                _state.update { state ->
                    outcome.fold(
                        onSuccess = { (saved, read, lists) ->
                            val said = WriteOutcome.of(Messages.songSave(fields, saved))
                            lists.applyTo(state).withDetail(songId) { open ->
                                val landed = open.copy(busy = false, saving = false)
                                // Gone keeps the draft (package 2 ruling #7): what was typed stays.
                                if (saved is SongCatalog.SongSave.Saved) landed.saved(read) else landed
                            }.copy(message = said.message, error = said.error)
                        },
                        onFailure = { failure ->
                            // The draft is kept (E44): what the user typed is still on screen.
                            state.withDetail(songId) { it.copy(busy = false, saving = false) }
                                .copy(error = WriteOutcome.failed(failure).error)
                        },
                    )
                }
            }
        }
    }

    /**
     * R21: a soft delete behind the screen's confirmation. The practice history is kept and
     * hidden; adding the same title and artist again restores the row.
     *
     * F18 N5: the result closes the detail **only if it is still open on this song** — the user
     * may have moved on while the removal waited in the queue.
     */
    public fun removeSong() {
        val detail = _state.value.detail ?: return
        val record = detail.record ?: return
        val songId = detail.songId
        val label = songLabel(record.title, record.artistName)
        val bound = BoundDatabase(holder)
        setBusy()
        viewModelScope.launch {
            serialised(
                writes,
                io,
                work = {
                    bound.use { repository -> repository.catalog.removeSong(songId) to readLists(repository) }
                },
            ) { outcome ->
                _state.update { state ->
                    val stillOpen = state.detail?.songId == songId
                    outcome.fold(
                        onSuccess = { (removed, lists) ->
                            // R23d: an already-removed song is said on the message channel.
                            val said = WriteOutcome.of(Messages.songRemoval(label, removed))
                            lists.applyTo(state).copy(
                                detail = if (stillOpen) null else state.detail,
                                detailRequest = if (stillOpen) DetailRequest.Close else state.detailRequest,
                                message = said.message,
                                error = said.error,
                            )
                        },
                        onFailure = { failure ->
                            state.withDetail(songId) { it.copy(busy = false) }
                                .copy(error = WriteOutcome.failed(failure).error)
                        },
                    )
                }
            }
        }
    }

    // ---- Tags (R17) -----------------------------------------------------------------------

    /** Tap a tag chip: add when it is off, remove when it is on. */
    public fun toggleTag(tagId: String) {
        val detail = _state.value.detail ?: return
        val live = detail.tags.firstOrNull { it.tagId == tagId }
        writeChild { catalog ->
            if (live != null) {
                Messages.junctionWrite(catalog.removeTag(live.id))
            } else {
                Messages.junctionWrite(catalog.addTag(detail.songId, tagId))
            }
        }
    }

    /** Create on enter (E5): a tag that does not exist yet is created with the junction row. */
    public fun addTagNamed(name: String) {
        val detail = _state.value.detail ?: return
        if (name.isBlank()) return
        writeChild { catalog -> Messages.junctionWrite(catalog.addTagNamed(detail.songId, name.trim())) }
    }

    // ---- song_instrument (R19) ------------------------------------------------------------

    public fun addSongInstrument(instrumentId: String) {
        val detail = _state.value.detail ?: return
        writeChild { catalog -> Messages.junctionWrite(catalog.addSongInstrument(detail.songId, instrumentId)) }
    }

    /**
     * Save one row's edit — the one held in the detail (F16 #6). Difficulty 1-5 or null is
     * picked, never typed; the catalog's `require` is caught all the same.
     */
    public fun saveSongInstrument(rowId: String) {
        val detail = _state.value.detail ?: return
        val row = detail.instruments.firstOrNull { it.id == rowId } ?: return
        val edit = detail.instrumentEdit(row)
        writeChild { catalog ->
            Messages.junctionUpdate(catalog.updateSongInstrument(rowId, edit.difficulty, edit.patch, edit.notes))
        }
    }

    public fun removeSongInstrument(id: String) {
        writeChild { catalog -> Messages.junctionWrite(catalog.removeSongInstrument(id)) }
    }

    // ---- The line-up (R18) ----------------------------------------------------------------

    /**
     * Read the line-up again. The screen calls this when the capability editor reports a write
     * landed (F18 N6: a revision counter, not the sheet's dismissal). A read, and only a read.
     */
    public fun refreshLineUp() {
        val songId = _state.value.detail?.songId ?: return
        viewModelScope.launch {
            serialised(writes, io, work = { CapabilityCoordinator(holder.repository).lineUp(songId) }) { outcome ->
                _state.update { state ->
                    outcome.fold(
                        onSuccess = { lineUp -> state.withDetail(songId) { it.copy(lineUp = lineUp) } },
                        onFailure = { failure -> state.copy(error = Messages.couldNot("read the line-up", failure)) },
                    )
                }
            }
        }
    }

    // ---- Merging two songs (R31-R39) ------------------------------------------------------
    //
    // Here rather than in a sibling ViewModel because a merge is a Songs-route write: it must
    // share [writes], the route's one queue, so no read overtakes it and R26's `awaitIdle` covers
    // it (F18 B1, N3); and it binds its database like every other write here (F18 N2, F23 N1). It
    // does not go near `SessionViewModel` (F22 N8, F25 N4). The overlay's state is its own type,
    // [MergeState], with its pure changes in the core; only the dispatching is here.

    /**
     * "Merge with…" on the detail (R38): opens the picker on the open song. Refused while the
     * detail has unsaved changes or a write in flight — the merge re-reads the song, and an unsaved
     * draft would be silently dropped by that read.
     */
    public fun startMerge() {
        val detail = _state.value.detail ?: return
        if (!detail.canMerge) return
        _merge.value = MergeState(songId = detail.songId)
    }

    public fun setMergeQuery(query: String) {
        _merge.update { it?.copy(query = query) }
    }

    /**
     * The other song is picked: read both sides, in the queue, and open the preview
     * ([MergeState.read]). F28 N4: the read is bound to the database current at the tap, and the
     * preview remembers which one, so the confirm writes to it or to none.
     */
    public fun chooseMergeWith(otherId: String) {
        val started = _merge.value ?: return
        _merge.value = started.choosing(otherId) ?: return
        val bound = BoundDatabase(holder)
        viewModelScope.launch {
            serialised(
                writes,
                io,
                work = { bound.use { repository -> repository.merge.let { it.side(started.songId) to it.side(otherId) } } },
            ) { outcome ->
                _merge.update { merge ->
                    val open = merge?.takeIf { it.songId == started.songId } ?: return@update merge
                    outcome.fold(
                        onSuccess = { (survivor, loser) -> open.read(survivor, loser, bound.generation) },
                        onFailure = { failure -> open.failed(Messages.couldNot("read the two songs", failure)) },
                    )
                }
            }
        }
    }

    public fun swapMerge() {
        _merge.update { merge -> merge?.withPlan { it.swapped() } }
    }

    public fun pickMergeField(field: SongField, side: MergeSide) {
        _merge.update { merge -> merge?.withPlan { it.withPick(field, side) } }
    }

    public fun toggleMergeChild(key: SongMerge.ChildKey) {
        _merge.update { merge -> merge?.withPlan { it.toggleChild(key) } }
    }

    public fun toggleMergeEvent(eventId: String) {
        _merge.update { merge -> merge?.withPlan { it.toggleEvent(eventId) } }
    }

    /** E24's overlays ruling: back closes the preview to the picker first, then the picker. */
    public fun backFromMerge() {
        _merge.update { it?.back() }
    }

    public fun cancelMerge() {
        _merge.update { it?.cancelled() }
    }

    /**
     * R36, R38: the confirmed merge, as one transaction in the route's queue, **bound to the
     * database the preview was read from** (F28 N4) — an import since then drops it, and it says
     * so. On success the overlay closes, **the detail shows the survivor** — whichever song that
     * is — and the list reloads. A refusal or failure wrote nothing (R38a) and keeps the preview
     * open with the reason.
     */
    public fun confirmMerge() {
        val merge = _merge.value ?: return
        val start = when (val confirmation = merge.confirm()) {
            MergeConfirmation.Ignored -> return
            is MergeConfirmation.Invalid -> {
                _merge.value = confirmation.state
                return
            }
            is MergeConfirmation.Start -> confirmation
        }
        val request = start.request
        val bound = BoundDatabase(holder, start.generation)
        _merge.value = start.state
        _state.update { it.copy(message = null, error = null) }
        viewModelScope.launch {
            serialised(
                writes,
                io,
                work = {
                    bound.use { repository ->
                        val outcome = repository.merge.merge(request)
                        Triple(outcome, SongDetailRead.of(repository, request.survivorId), readLists(repository))
                    }
                },
            ) { result ->
                result.fold(
                    onSuccess = { (outcome, read, lists) -> landMerge(request.survivorId, outcome, read, lists) },
                    onFailure = { failure -> _merge.update { it?.failed(WriteOutcome.failed(failure).error) } },
                )
            }
        }
    }

    private fun landMerge(survivorId: String, outcome: SongMerge.Outcome, read: SongDetailRead, lists: Lists) {
        val label = read.record?.let { songLabel(it.title, it.artistName) }.orEmpty()
        val said = WriteOutcome.of(Messages.mergeOutcome(outcome, label))
        if (outcome !is SongMerge.Outcome.Merged) {
            _merge.update { it?.failed(said.error) }
            _state.update { lists.applyTo(it) }
            return
        }
        _merge.value = null
        _state.update { state ->
            lists.applyTo(state).copy(
                detail = SongDetail(songId = survivorId).saved(read),
                detailRequest = DetailRequest.Open(survivorId),
                message = said.message,
                error = said.error,
            )
        }
    }

    // ---- Channels -------------------------------------------------------------------------

    public fun consumeDetailRequest() {
        _state.update { it.copy(detailRequest = null) }
    }

    public fun clearMessages() {
        _state.update { it.copy(message = null, error = null) }
    }

    // ---- Internals ------------------------------------------------------------------------

    /**
     * One child write (tag, `song_instrument`), then the detail's children and the lookup lists
     * again — a typed tag may have been created. [block] says what the write did, on its channel
     * (the core's [Messages]). The song's own draft is never touched by a child write.
     */
    private fun writeChild(block: (SongCatalog) -> Notice?) {
        val songId = _state.value.detail?.songId ?: return
        if (_state.value.detail?.busy == true) return
        val bound = BoundDatabase(holder)
        setBusy()
        viewModelScope.launch {
            serialised(
                writes,
                io,
                work = {
                    bound.use { repository ->
                        val said = block(repository.catalog)
                        Triple(said, SongDetailRead.of(repository, songId), readLists(repository))
                    }
                },
            ) { outcome ->
                _state.update { state ->
                    outcome.fold(
                        onSuccess = { (notice, read, lists) ->
                            val said = WriteOutcome.of(notice)
                            lists.applyTo(state).withDetail(songId) { it.withChildren(read).copy(busy = false) }
                                .copy(message = said.message, error = said.error)
                        },
                        onFailure = { failure ->
                            state.withDetail(songId) { it.copy(busy = false) }
                                .copy(error = WriteOutcome.failed(failure).error)
                        },
                    )
                }
            }
        }
    }

    private fun setBusy(saving: Boolean = false) {
        _state.update { state ->
            val detail = state.detail ?: return@update state
            state.copy(detail = detail.copy(busy = true, saving = saving), message = null, error = null)
        }
    }

    public companion object {
        public fun factory(graph: AppGraph): androidx.lifecycle.ViewModelProvider.Factory = viewModelFactory {
            initializer { SongsViewModel(graph.holder) }
        }
    }
}
