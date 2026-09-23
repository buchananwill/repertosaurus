package dev.repertosaurus.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.data.JunctionWrite
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.RepertoireCoordinator
import dev.repertosaurus.session.RepertoireState
import dev.repertosaurus.session.ToggleList
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
 * State holder for the Repertoire route — repertoire-editing R1-R10. The state and its pure
 * changes are the core's [RepertoireState] and [ToggleList] (style review F17 B1); what is here is
 * the part that needs Android: the scope, the queue, and the dispatcher.
 *
 * **R5 / E1: nothing here reaches the practice path.** Every write is
 * [RepertoireCoordinator.setHeld], which reaches `song_performer` and nothing else.
 *
 * **R8: optimistic per row, rollback on failure, serialised through one queue.** A toggle
 * shows its new state at once and disables its row; the write then waits its turn on [writes]
 * through [serialised], so writes run — and their results land — in the order they were tapped.
 * **Every read that should reflect a toggle queues behind it too** (safety review F18 B1): the
 * list's reads, and the performer re-read on close. The E45 ticket is not reused: it drops stale
 * results for one song, and this screen writes across many.
 *
 * F18 N2: a toggle binds to the database that was current when it was tapped ([BoundDatabase]).
 *
 * R28: the coordinator is built per call against `holder.repository`, so an import's database
 * swap is picked up.
 */
public class RepertoireViewModel(
    private val holder: DatabaseHolder,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow(RepertoireState())
    public val state: StateFlow<RepertoireState> = _state.asStateFlow()

    /** R8's one queue for the screen. Fair, so the lock is granted in tap order. */
    private val writes = Mutex()

    /**
     * Which toggle list a result belongs to. Only touched on the main thread (`viewModelScope`
     * is `Dispatchers.Main.immediate`), like `SessionViewModel`'s tickets.
     */
    private var listTicket = 0L

    private fun coordinator(): RepertoireCoordinator = RepertoireCoordinator(holder.repository)

    /**
     * R26 and F18 B1: suspends until every write queued so far has landed. The logger's reload
     * waits on this, so it cannot read the database before a toggle made on the way out does.
     */
    public suspend fun awaitIdle() {
        writes.withLock { }
    }

    // ---- The performer list (R1, R2, R10) -------------------------------------------------

    /**
     * The route was entered (F18 N8): the previous visit's messages go, the previous list is
     * dropped — so opening the same pair again reads it again rather than showing it as it was
     * left — and the performers are read.
     */
    public fun enter() {
        listTicket++
        _state.update { it.copy(list = null, message = null, error = null) }
        loadPerformers()
    }

    /** Read the performers, **behind any queued toggle** (F18 B1): E15's roles are derived from them. */
    public fun loadPerformers() {
        _state.update { it.copy(loadingPerformers = true) }
        viewModelScope.launch {
            serialised(writes, io, work = { coordinator().performers() }) { outcome ->
                _state.update { state ->
                    outcome.fold(
                        onSuccess = { state.copy(performers = it, loadingPerformers = false) },
                        onFailure = { failure ->
                            state.copy(
                                loadingPerformers = false,
                                error = Messages.couldNot("load performers", failure),
                            )
                        },
                    )
                }
            }
        }
    }

    // ---- The toggle list (R3-R9) ----------------------------------------------------------

    /**
     * Open the toggle list for one `(performer, instrument)`. A second call for the pair that is
     * already open does nothing — that is a rotation re-running the screen's effect, and the
     * list (with any optimistic rows) must survive it.
     */
    public fun openList(performerId: String, instrumentId: String) {
        val open = _state.value.list
        if (open != null && open.performerId == performerId && open.instrumentId == instrumentId) {
            return
        }
        val ticket = ++listTicket
        _state.update {
            it.copy(
                list = ToggleList(performerId, instrumentId, ticket = ticket, loading = true),
                message = null,
                error = null,
            )
        }
        viewModelScope.launch {
            serialised(writes, io, work = { coordinator().songs(performerId, instrumentId) }) { outcome ->
                _state.update { state -> state.withRead(ticket, outcome) }
            }
        }
    }

    /**
     * Close the list, and read the performers again: a toggle can have given someone their first
     * song on a role or taken away their last (E15: roles are derived).
     *
     * **The re-read queues behind every toggle already tapped** (F18 B1, N1), so the roles it
     * reads include them. Those writes still run — the user tapped them — and a failure still
     * reaches the error channel; only their row update is dropped, because the list it belongs
     * to is gone.
     */
    public fun closeList() {
        _state.update { it.copy(list = null) }
        loadPerformers()
    }

    /** R3: the search, in memory, and R7's second moment the order is fixed. */
    public fun setQuery(query: String) {
        _state.update { state ->
            val list = state.list ?: return@update state
            state.copy(list = list.withQuery(query))
        }
    }

    /**
     * R4, R8: flip one row. The row shows its new state at once and is disabled until its
     * write lands; a failed or refused write puts it back. **A second tap on a row whose write
     * is in flight is ignored** — the row is disabled, and this is the guard behind that.
     */
    public fun toggle(songId: String) {
        val list = _state.value.list ?: return
        if (list.loading || songId in list.inFlight) return
        val row = list.rows.firstOrNull { it.songId == songId } ?: return
        val wanted = !row.held
        val bound = BoundDatabase(holder)
        _state.update { state ->
            state.withList(list.ticket) { it.toggling(songId, wanted) }.copy(message = null, error = null)
        }
        viewModelScope.launch {
            serialised(
                writes,
                io,
                work = {
                    bound.use { repository ->
                        val coordinator = RepertoireCoordinator(repository)
                        val write = coordinator.setHeld(songId, list.performerId, list.instrumentId, wanted)
                        // R23c: the song was removed elsewhere, so the list is read again — here,
                        // in the same turn of the queue, so the re-read cannot overtake a later toggle.
                        val reread = if (write is JunctionWrite.SongGone) {
                            coordinator.songs(list.performerId, list.instrumentId)
                        } else {
                            null
                        }
                        write to reread
                    }
                },
            ) { outcome ->
                _state.update { state ->
                    state.withToggle(list.ticket, row, wanted, outcome, ::describeToggleFailure)
                }
            }
        }
    }

    public fun clearMessages() {
        _state.update { it.copy(message = null, error = null) }
    }

    public companion object {
        /** F18 N2's dropped write says so in its own words; every other failure is R8's sentence. */
        private fun describeToggleFailure(label: String, failure: Throwable): String =
            if (failure is DatabaseReplaced) Messages.DATABASE_REPLACED else Messages.toggleFailed(label, failure)

        public fun factory(graph: AppGraph): androidx.lifecycle.ViewModelProvider.Factory = viewModelFactory {
            initializer { RepertoireViewModel(graph.holder) }
        }
    }
}
