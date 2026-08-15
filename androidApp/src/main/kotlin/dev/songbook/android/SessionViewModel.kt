package dev.songbook.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.songbook.core.Ids
import dev.songbook.core.Timestamps
import dev.songbook.data.DatabaseHolder
import dev.songbook.data.ImportPreview
import dev.songbook.data.ImportRejected
import dev.songbook.data.SampleData
import dev.songbook.data.SongbookRepository
import dev.songbook.session.InstrumentChip
import dev.songbook.session.LookupItem
import dev.songbook.session.LookupKind
import dev.songbook.session.LookupStore
import dev.songbook.session.LookupStores
import dev.songbook.session.SessionCoordinator
import dev.songbook.session.SessionOrder
import dev.songbook.session.SessionPreferences
import dev.songbook.session.SessionRow
import dev.songbook.session.SessionState
import dev.songbook.session.SessionTap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * State holder for the Session screen.
 *
 * The rules live in [SessionState] and [SessionCoordinator] in the shared core, where they
 * are tested without a device. What is here is the part that genuinely needs Android: a
 * lifecycle-scoped coroutine scope, and the optimistic-write discipline that keeps the tap
 * path off the database.
 *
 * **A tap never waits on SQLite.** [log] mutates UI state synchronously and returns; the
 * insert runs on [io]. The event id it produces is not known yet when the undo snackbar
 * appears, so it is held as a [Deferred] and the undo awaits it before appending the void
 * row (decision 8).
 */
public class SessionViewModel(
    private val holder: DatabaseHolder,
    private val preferences: SessionPreferences,
    private val deviceId: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(SessionState())
    public val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _transfer = MutableStateFlow(TransferState())
    public val transfer: StateFlow<TransferState> = _transfer.asStateFlow()

    /** tap id -> the in-flight insert, so an undo offered before it lands still works. */
    private val writes = mutableMapOf<String, Deferred<Result<String>>>()

    private var bootstrapped = false

    init {
        reload()
    }

    private fun coordinator(): SessionCoordinator =
        SessionCoordinator(holder.repository, preferences)

    // ---- Loading ----------------------------------------------------------------------

    public fun reload() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val loaded = withContext(io) {
                if (!bootstrapped) {
                    // Test data so the app has something to show before the phase 0 import
                    // lands. Idempotent, and only ever on the first load of a process — an
                    // import that legitimately arrives empty must stay empty.
                    SampleData.installIfEmpty(holder.open(), deviceId)
                    bootstrapped = true
                }
                val coordinator = coordinator()
                val chips = coordinator.instruments()
                val keep = _state.value.selectedInstrumentId
                    ?.takeIf { id -> chips.any { it.id == id } }
                val selected = keep ?: coordinator.initialInstrument(chips)
                val rows = selected?.let { coordinator.rows(it) } ?: emptyList()
                Loaded(chips, selected, rows, coordinator.initialOrder())
            }
            _state.update {
                it.withInstruments(loaded.chips, loaded.selected)
                    .withOrder(loaded.order)
                    .withRows(loaded.rows)
            }
        }
    }

    private data class Loaded(
        val chips: List<InstrumentChip>,
        val selected: String?,
        val rows: List<SessionRow>,
        val order: SessionOrder,
    )

    /**
     * The sort toggle. Instant — it reorders rows already in memory, with no round trip —
     * and remembered for the next launch exactly as the instrument chip is.
     */
    public fun setOrder(order: SessionOrder) {
        if (_state.value.order == order) return
        _state.update { it.withOrder(order) }
        viewModelScope.launch { withContext(io) { coordinator().rememberOrder(order) } }
    }

    public fun selectInstrument(instrumentId: String) {
        if (_state.value.selectedInstrumentId == instrumentId) return
        _state.update { it.selecting(instrumentId) }
        viewModelScope.launch {
            val rows = withContext(io) {
                val coordinator = coordinator()
                coordinator.rememberInstrument(instrumentId)
                coordinator.rows(instrumentId)
            }
            _state.update {
                if (it.selectedInstrumentId == instrumentId) it.withRows(rows) else it
            }
        }
    }

    public fun setQuery(query: String) {
        _state.update { it.withQuery(query) }
    }

    // ---- The tap path -----------------------------------------------------------------

    /**
     * One tap, no dialog, no confirmation, no database wait. A second tap on the same song
     * in the same session is a second event and is not deduplicated (decision 47).
     */
    public fun log(
        songId: String,
        feel: Long? = null,
        note: String? = null,
        loggedOn: String = Timestamps.today(),
    ) {
        val instrumentId = _state.value.selectedInstrumentId ?: return
        val tap = SessionTap(
            tapId = Ids.random(),
            songId = songId,
            instrumentId = instrumentId,
            feel = feel,
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            loggedOn = loggedOn,
        )
        _state.update { it.plusTap(tap) }

        val write = viewModelScope.async(io) { runCatching { coordinator().persist(tap) } }
        writes[tap.tapId] = write
        viewModelScope.launch {
            write.await().onFailure { failure ->
                writes.remove(tap.tapId)
                _state.update {
                    it.minusTap(tap.tapId).withMessage("Could not save that log: ${failure.message}")
                }
            }
        }
    }

    /**
     * Undo (decision 8): an append to `practice_event_void`, never a delete or an update.
     * The UI half is instant — dropping the tap restores the row to exactly the staleness
     * position it had, including "never" when that was its only event.
     */
    public fun undo(tapId: String) {
        _state.update { it.minusTap(tapId) }
        val write = writes.remove(tapId) ?: return
        viewModelScope.launch {
            val failure = withContext(io) {
                val eventId = write.await().getOrNull() ?: return@withContext null
                runCatching { coordinator().voidEvent(eventId) }.exceptionOrNull()
            }
            if (failure != null) {
                _state.update { it.withMessage("Could not undo that log: ${failure.message}") }
            }
        }
    }

    public fun dismissUndo() {
        _state.update { it.withoutUndo() }
    }

    public fun clearMessage() {
        _state.update { it.withMessage(null) }
    }

    // ---- Adding a song ----------------------------------------------------------------

    /**
     * Every live artist, held in memory while the add-song sheet is open so the type-ahead
     * filters on each keystroke with no database round trip and no chance of an older
     * query's results landing after a newer one's. The repertoire has a few hundred
     * artists; this costs nothing and makes decision 16's "surface near-matches while
     * typing" literally true.
     */
    private val _artists = MutableStateFlow<List<SongbookRepository.Artist>>(emptyList())
    public val artists: StateFlow<List<SongbookRepository.Artist>> = _artists.asStateFlow()

    public fun loadArtists() {
        viewModelScope.launch {
            _artists.value = withContext(io) { runCatching { coordinator().artists() } }
                .getOrDefault(emptyList())
        }
    }

    /**
     * Title and artist, nothing else (decisions 27, 37 — no key may be required). The song
     * appears immediately as never-practised, at the top of a coldest-first list.
     *
     * [artistId] is set only when the user picked an existing artist out of the
     * suggestions; otherwise the typed name is resolved by normalisation, which reuses an
     * existing row whenever one normalises the same (decisions 2, 4, 17).
     */
    public fun addSong(title: String, artistName: String, artistId: String? = null) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val outcome = withContext(io) {
                runCatching {
                    val coordinator = coordinator()
                    if (artistId != null) {
                        coordinator.addSongWithArtistId(trimmed, artistId)
                    } else {
                        coordinator.addSong(trimmed, artistName)
                    }
                    _state.value.selectedInstrumentId?.let { coordinator.rows(it) } ?: emptyList()
                }
            }
            _state.update { state ->
                outcome.fold(
                    onSuccess = { rows -> state.withRows(rows).withMessage("Added $trimmed") },
                    onFailure = { failure ->
                        state.withMessage("Could not add that song: ${failure.message}")
                    },
                )
            }
            loadArtists()
        }
    }

    // ---- Managing a lookup table (instruments today) -----------------------------------

    private val _lookups = MutableStateFlow(LookupsState())
    public val lookups: StateFlow<LookupsState> = _lookups.asStateFlow()

    private fun store(kind: LookupKind) = LookupStores.of(kind, holder.repository)

    public fun loadLookups(kind: LookupKind) {
        _lookups.update { it.copy(kind = kind, busy = true) }
        viewModelScope.launch {
            val outcome = withContext(io) { runCatching { store(kind).items() } }
            _lookups.update { state ->
                outcome.fold(
                    onSuccess = { state.copy(items = it, busy = false) },
                    onFailure = { failure ->
                        state.copy(busy = false, message = "Could not load: ${failure.message}")
                    },
                )
            }
        }
    }

    /**
     * Create on enter (decision 16). Every mutation reloads the session list as well: the
     * chip row *is* the instrument table and must not lag behind it.
     */
    public fun addLookup(kind: LookupKind, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        mutate(kind, "Added $trimmed") { it.add(trimmed) }
    }

    public fun renameLookup(kind: LookupKind, id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        mutate(kind, "Renamed to $trimmed") { it.rename(id, trimmed) }
    }

    public fun removeLookup(kind: LookupKind, id: String, name: String) {
        mutate(kind, "Removed $name") { it.remove(id) }
    }

    private fun mutate(kind: LookupKind, done: String, block: (LookupStore) -> Unit) {
        _lookups.update { it.copy(busy = true) }
        viewModelScope.launch {
            val outcome = withContext(io) {
                runCatching {
                    val store = store(kind)
                    block(store)
                    store.items()
                }
            }
            _lookups.update { state ->
                outcome.fold(
                    onSuccess = { state.copy(items = it, busy = false, message = done) },
                    onFailure = { failure ->
                        state.copy(busy = false, message = "That did not work: ${failure.message}")
                    },
                )
            }
            if (outcome.isSuccess) reload()
        }
    }

    public fun clearLookupMessage() {
        _lookups.update { it.copy(message = null) }
    }

    /** The manage screen's state. It does not know which lookup it is showing. */
    public data class LookupsState(
        val kind: LookupKind = LookupKind.INSTRUMENT,
        val items: List<LookupItem> = emptyList(),
        val busy: Boolean = false,
        val message: String? = null,
    )

    // ---- Import and export ------------------------------------------------------------

    /** The suggested backup file name for the system save sheet. */
    public fun exportFileName(): String = holder.exportFileName(holder.repository.today())

    public fun export(target: () -> OutputStream?) {
        _transfer.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val outcome = withContext(io) {
                runCatching {
                    val stream = target() ?: error("the file could not be opened for writing")
                    stream.use { holder.exportTo(it) }
                }
            }
            _transfer.update {
                it.copy(
                    busy = false,
                    message = outcome.fold(
                        onSuccess = { bytes -> "Exported ${kilobytes(bytes)}." },
                        onFailure = { failure -> "Export failed: ${failure.message}" },
                    ),
                )
            }
        }
    }

    /** Step one of import: validate and describe. Nothing is overwritten yet. */
    public fun stageImport(source: () -> InputStream?) {
        _transfer.update { it.copy(busy = true, message = null, pending = null) }
        viewModelScope.launch {
            val outcome = withContext(io) {
                runCatching {
                    val stream = source() ?: error("the file could not be opened")
                    stream.use { holder.stageImport(it) }
                }
            }
            _transfer.update {
                outcome.fold(
                    onSuccess = { preview -> it.copy(busy = false, pending = preview) },
                    onFailure = { failure ->
                        it.copy(
                            busy = false,
                            pending = null,
                            message = if (failure is ImportRejected) {
                                failure.message
                            } else {
                                "Import failed: ${failure.message}"
                            },
                        )
                    },
                )
            }
        }
    }

    /** Step two: the destructive half, only after the user has confirmed. */
    public fun confirmImport() {
        _transfer.update { it.copy(busy = true, pending = null) }
        viewModelScope.launch {
            val outcome = withContext(io) { runCatching { holder.commitImport() } }
            if (outcome.isSuccess) {
                // Everything the old database backed is gone: taps, rows, selection.
                _state.value = SessionState()
                bootstrapped = true
                reload()
            }
            _transfer.update {
                it.copy(
                    busy = false,
                    message = outcome.fold(
                        onSuccess = { "Database replaced." },
                        onFailure = { failure -> "Import failed: ${failure.message}" },
                    ),
                )
            }
        }
    }

    public fun cancelImport() {
        _transfer.update { it.copy(pending = null) }
        viewModelScope.launch { withContext(io) { holder.discardStagedImport() } }
    }

    public fun clearTransferMessage() {
        _transfer.update { it.copy(message = null) }
    }

    /** Import and export state. Separate from [SessionState], which is the list itself. */
    public data class TransferState(
        val busy: Boolean = false,
        val pending: ImportPreview? = null,
        val message: String? = null,
    )

    public companion object {
        private fun kilobytes(bytes: Long): String = "${(bytes + 1023) / 1024} kB"

        public fun factory(graph: AppGraph): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SessionViewModel(graph.holder, graph.preferences, graph.deviceId) as T
            }
    }
}
