package dev.repertosaurus.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.repertosaurus.core.Ids
import dev.repertosaurus.core.RatingLevel
import dev.repertosaurus.core.Timestamps
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.data.DatabaseState
import dev.repertosaurus.data.DatabaseUnloadable
import dev.repertosaurus.data.ImportPreview
import dev.repertosaurus.data.ImportRejected
import dev.repertosaurus.data.SampleData
import dev.repertosaurus.data.RepertosaurusRepository
import dev.repertosaurus.data.SongCatalog
import dev.repertosaurus.session.CapabilityCoordinator
import dev.repertosaurus.session.LookupItem
import dev.repertosaurus.session.LookupKind
import dev.repertosaurus.session.LookupStore
import dev.repertosaurus.session.LookupStores
import dev.repertosaurus.session.Messages
import dev.repertosaurus.session.Notice
import dev.repertosaurus.session.PerformerLineUp
import dev.repertosaurus.session.SessionCoordinator
import dev.repertosaurus.session.SessionOrder
import dev.repertosaurus.session.SessionPreferences
import dev.repertosaurus.session.SessionRow
import dev.repertosaurus.session.SessionStart
import dev.repertosaurus.session.SessionState
import dev.repertosaurus.session.SessionTap
import dev.repertosaurus.session.SessionView
import dev.repertosaurus.session.SongCapability
import dev.repertosaurus.session.SongIdentity
import dev.repertosaurus.session.ViewCoordinator
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
import kotlin.random.Random

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
    /** Suggest SG7: the draw's random source. */
    private val random: Random = Random.Default,
) : ViewModel() {

    private val _state = MutableStateFlow(SessionState())
    public val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _transfer = MutableStateFlow(TransferState())
    public val transfer: StateFlow<TransferState> = _transfer.asStateFlow()

    /** tap id -> the in-flight insert, so an undo offered before it lands still works. */
    private val writes = mutableMapOf<String, Deferred<Result<String>>>()

    // Volatile: written from Dispatchers.IO inside `loadSession` and from Main in `startFresh`
    // and `confirmImport`. `withContext` gives happens-before for one reload at a time, but two
    // overlapping reloads — start-fresh racing a lookup edit's reload — could otherwise both see
    // false and run `SampleData.installIfEmpty` concurrently.
    @Volatile
    private var bootstrapped = false

    private fun coordinator(): SessionCoordinator =
        SessionCoordinator(holder.repository, preferences)

    private fun viewCoordinator(): ViewCoordinator =
        ViewCoordinator(holder.repository, preferences)

    // ---- Loading ----------------------------------------------------------------------

    /**
     * Which View the app opens on, held here rather than in [SessionState] because it is a
     * device preference and not part of the list (views V19). Null until the first load, and
     * null forever on an install that has never saved a View.
     */
    private val _homeViewId = MutableStateFlow<String?>(null)
    public val homeViewId: StateFlow<String?> = _homeViewId.asStateFlow()

    /**
     * Whether the database opened at all (schema-compatibility S9).
     *
     * `Ready` while it did, and while nothing has yet said otherwise; the moment the boot path
     * cannot read the database this carries the reason and `RepertosaurusApp` renders
     * `RecoveryScreen` in place of the Session screen. **This flow is the whole of S8's
     * guarantee at this layer**: start-up used to have exactly one failure mode, which was
     * killing the process before any screen was drawn — including the import screen that would
     * have fixed it.
     *
     * S11: nothing here degrades a broken database to an empty one. An unreadable database
     * shows a screen that says so and offers two actions; it never shows an intact repertoire
     * as zero songs, which would invite the user to start typing them back in.
     */
    private val _databaseState = MutableStateFlow<DatabaseState>(DatabaseState.Ready)
    public val databaseState: StateFlow<DatabaseState> = _databaseState.asStateFlow()

    /**
     * Journal F30: true once the first load has completed, whatever its result, and never before.
     * [databaseState] starts at `Ready` before anything has been read, so it alone cannot say the
     * database is known to be readable; this, set where the load confirms it, can.
     */
    private val _firstLoadDone = MutableStateFlow(false)
    public val firstLoadDone: StateFlow<Boolean> = _firstLoadDone.asStateFlow()

    /** What one successful load produced. Null when the database could not be opened. */
    private class Loaded(val start: SessionStart, val rows: List<SessionRow>)

    /**
     * R26, **after the route's writes have landed** (safety review F18 B1). Returning to the
     * logger reloads it, but a toggle tapped on the way out may still be queued behind the
     * route's lock; a reload that ran first would read the database without it and keep the old
     * list until the next restart. [idle] is the route's `awaitIdle`, and the reload waits on it.
     *
     * Launched in this ViewModel's scope, not the composition's, so a rotation mid-wait does not
     * lose the reload.
     *
     * **This is the one place the pending undo offer is cleared by a reload** (F28 N7, amended by
     * F30 #4). A route is the only place a merge can happen, and a merge may have voided the very
     * event the offer would void and moved its copy to another song — so an undo tapped afterwards
     * could void nothing visible and still look as if it worked. It is cleared twice: on the way
     * back, and again once [idle] has returned, because the stale list stays tappable while the
     * route's queue drains and a tap on the merge's loser in that window is voided by the merge
     * too. The taps themselves are kept, as a View switch keeps them.
     */
    public fun reloadAfter(idle: suspend () -> Unit) {
        _state.update { it.copy(loading = true).withoutUndo() }
        viewModelScope.launch {
            idle()
            _state.update { it.withoutUndo() }
            reload()
        }
    }

    /**
     * Read the logger again. **It keeps the pending undo offer** (F30 #4): undo is on the
     * highest-frequency path, and the reloads that come through here — after a capability edit or
     * a lookup edit — cannot merge a song away. Only [reloadAfter] clears it.
     */
    public fun reload() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val outcome = withContext(io) { runCatching { loadSession() } }
            outcome.fold(
                onSuccess = { loaded ->
                    if (loaded == null) {
                        _state.update { it.copy(loading = false) }
                    } else {
                        _homeViewId.value = loaded.start.homeViewId
                        _state.update {
                            it.withInstruments(loaded.start.instruments)
                                .withViews(loaded.start.views)
                                .copy(view = loaded.start.view)
                                .withRows(loaded.rows)
                        }
                    }
                },
                onFailure = { failure ->
                    // Not a swallow (S11): the reason becomes the screen the user is looking
                    // at. Anything thrown past the holder's own gate lands here — a query that
                    // fails on a schema the gate accepted, a disk error mid-read — and the
                    // answer is the same, because the alternative is the crash.
                    _databaseState.value = unloadable(failure)
                    _state.update { it.copy(loading = false) }
                },
            )
            _firstLoadDone.value = true
            if (_databaseState.value is DatabaseState.Ready) loadPerformers()
        }
    }

    /**
     * The blocking half of [reload]. Returns null when the holder refused to open the database,
     * having already published the reason.
     */
    private fun loadSession(): Loaded? {
        val opened = holder.load()
        if (opened is DatabaseState.Unloadable) {
            _databaseState.value = opened
            return null
        }
        if (!bootstrapped) {
            // Test data so the app has something to show before the phase 0 import
            // lands. Idempotent, and only ever on the first load of a process — an
            // import that legitimately arrives empty must stay empty.
            SampleData.installIfEmpty(holder.open(), deviceId)
            bootstrapped = true
        }
        // One call: V20's home resolution, V20a's instrument repair and V21's empty
        // state are one rule and live in the shared core. Whatever is on screen wins
        // if it survived, so a reload triggered by an unrelated edit — adding an
        // instrument, renaming one — does not throw the musician back to their home
        // View mid-practice.
        val start = viewCoordinator().start(_state.value.view)
        val rows = start.view?.let { coordinator().rows(it) } ?: emptyList()
        _databaseState.value = DatabaseState.Ready
        return Loaded(start, rows)
    }

    /**
     * A failure that reached this layer, as a state. The wording comes from
     * [DatabaseState.Unloadable.from] rather than from here, so the user is not told the file
     * "could not be opened" or "could not be read" depending on which layer caught it (S6).
     */
    private fun unloadable(failure: Throwable): DatabaseState.Unloadable = when (failure) {
        is DatabaseUnloadable -> failure.state
        else -> DatabaseState.Unloadable.from(holder.databaseFile().absolutePath, failure)
    }

    // ---- Recovery (schema-compatibility S9) ----------------------------------------------

    /**
     * Try the database again — for a failure that might not be permanent, and for the user who
     * has just put the right file in place from outside the app.
     *
     * It goes through [DatabaseHolder.retry] and not [reload], because the holder caches its
     * refusal and a plain reload would be handed the cached verdict. That distinction is the
     * difference between a button and a button that does nothing.
     */
    public fun retryDatabase() {
        viewModelScope.launch {
            val state = withContext(io) { runCatching { holder.retry() } }
                .getOrElse { failure -> unloadable(failure) }
            _databaseState.value = state
            if (state is DatabaseState.Ready) reload()
        }
    }

    /**
     * The second recovery action: discard the unreadable file and create an empty
     * current-schema database. Destructive, and the screen asks first.
     *
     * S4: a re-import is always the supported recovery, so this is the fallback for the user
     * who has no file to import, not the recommended route.
     */
    public fun startFresh() {
        viewModelScope.launch {
            val outcome = withContext(io) { runCatching { holder.startFresh() } }
            outcome.fold(
                onSuccess = { state ->
                    _databaseState.value = state
                    if (state is DatabaseState.Ready) {
                        _state.value = SessionState()
                        // **Empty means empty.** S9 says this action creates an empty
                        // current-schema database and the screen says so twice. Leaving
                        // `bootstrapped` false would let `SampleData.installIfEmpty` write eight
                        // songs and invented practice dates into it, and a user who has just been
                        // told their repertoire is unreadable cannot tell placeholder rows from
                        // survivors. `confirmImport` sets this for the same reason.
                        bootstrapped = true
                        reload()
                    }
                },
                onFailure = { failure -> _databaseState.value = unloadable(failure) },
            )
        }
    }

    /**
     * The sort toggle. Instant — it reorders rows already in memory, with no round trip.
     *
     * Where the direction is *persisted* is V13b's two-branch rule and lives in
     * [ViewCoordinator.rememberOrder]: `saved_view.sort_order` for a saved View,
     * [SessionPreferences] for V21's unsaved one. With no View at all — the initial load — the
     * tap is still not lost: the state carries it and it goes to preferences, which is where
     * the View that is about to arrive would have read it from anyway.
     */
    public fun setOrder(order: SessionOrder) {
        val state = _state.value
        if (state.order == order) return
        _state.update { it.withOrder(order) }
        val turned = state.view?.copy(order = order)
        viewModelScope.launch {
            val outcome = withContext(io) {
                runCatching {
                    if (turned == null) {
                        coordinator().rememberOrder(order)
                        null
                    } else {
                        viewCoordinator().rememberOrder(turned)
                    }
                }
            }
            outcome.fold(
                onSuccess = { refreshed ->
                    if (refreshed != null) _state.update { it.withViews(refreshed) }
                },
                onFailure = { failure ->
                    _state.update {
                        it.withMessage(Messages.couldNot("remember that order", failure))
                    }
                },
            )
        }
    }

    /**
     * The instrument chip: the practice instrument, which is both the log target and the
     * staleness scope (V13). It is **not** the capability filter — that is a field of the
     * View and lives in the View editor — and merging the two is the exact bug Views exist
     * to fix.
     *
     * V13a: this **forks to an unsaved View and never edits the saved row.** The forked View
     * keeps the active View's filter and direction and takes the tapped instrument. Editing a
     * saved View is an explicit action in the switcher, never a side effect of practising.
     */
    public fun selectInstrument(instrumentId: String) {
        val view = _state.value.view ?: return
        if (view.practiceInstrumentId == instrumentId) return
        switchView(view.forkedTo(instrumentId))
    }

    /**
     * V22: switching View is a **full reload** of the filter, the practice instrument and the
     * sort, and it clears the pending undo offer — which refers to a tap the user is no longer
     * looking at. It does **not** clear this session's optimistic taps: `tapsHere` already
     * scopes the logged section by practice instrument and `logged` resolves each tap against
     * the current View's rows, so a song absent from the new View drops out on its own, and
     * the ordinary flick of guitar → bass → guitar returns you to your logged list.
     *
     * Nothing here writes to `saved_view`. Switching and chip-tapping are read paths, which is
     * what keeps two rapid taps from racing each other into a synced row.
     */
    public fun switchView(view: SessionView) {
        _state.update { it.switchingTo(view) }
        viewModelScope.launch {
            val loaded = withContext(io) {
                runCatching {
                    val coordinator = coordinator()
                    // The chip is remembered whichever View asked for it, so V21's empty
                    // state opens on the instrument last actually practised on.
                    coordinator.rememberInstrument(view.practiceInstrumentId)
                    coordinator.rows(view)
                }
            }
            _state.update { state ->
                if (!view.sameQuery(state.view)) {
                    state
                } else {
                    loaded.fold(
                        onSuccess = { rows -> state.withRows(rows) },
                        onFailure = { failure ->
                            state.withRows(emptyList())
                                .withMessage(Messages.couldNot("open that view", failure))
                        },
                    )
                }
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
        feel: RatingLevel? = null,
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
                    it.minusTap(tap.tapId).withMessage(Messages.couldNot("save that log", failure))
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
                _state.update { it.withMessage(Messages.couldNot("undo that log", failure)) }
            }
        }
    }

    public fun dismissUndo() {
        _state.update { it.withoutUndo() }
    }

    /** suggest SG2-SG7: the suggestion sheet's deck. It writes nothing; "Log it" is [log]. */
    public val suggestions: SuggestionHolder = SuggestionHolder(state, viewModelScope, random)

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
    private val _artists = MutableStateFlow<List<RepertosaurusRepository.Artist>>(emptyList())
    public val artists: StateFlow<List<RepertosaurusRepository.Artist>> = _artists.asStateFlow()

    /**
     * S11: **not** `getOrDefault(emptyList())`. An empty artist list is a legitimate state, so a
     * failed read that produced one would be indistinguishable from a real empty table — and in
     * the add-song sheet that silently defeats decisions 2, 16 and 17, because a type-ahead with
     * no suggestions makes the user create a duplicate artist by hand. The previous list stays
     * and the failure is said out loud.
     */
    public fun loadArtists() {
        viewModelScope.launch {
            val outcome = withContext(io) { runCatching { coordinator().artists() } }
            outcome.fold(
                onSuccess = { artists -> _artists.value = artists },
                onFailure = { failure ->
                    _state.update {
                        it.withMessage(Messages.couldNot("load artists", failure))
                    }
                },
            )
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
                    val added = coordinator.addSong(trimmed, SongCatalog.LookupChoice.of(artistId, artistName))
                    added to (_state.value.view?.let { coordinator.rows(it) } ?: emptyList())
                }
            }
            _state.update { state ->
                outcome.fold(
                    onSuccess = { (added, rows) ->
                        // R23 in the logger (session 09 ruling): the message only. The logger
                        // has no detail to open, and the song may be outside the active View —
                        // which V9 makes worth saying, and the core's wording says it.
                        val visible = rows.any { it.songId == added.song.songId }
                        state.withRows(rows).withMessage(Messages.songAddInView(added, visible))
                    },
                    onFailure = { failure ->
                        state.withMessage(Messages.couldNot("add that song", failure))
                    },
                )
            }
            loadArtists()
        }
    }

    // ---- Views (views V15-V24) ----------------------------------------------------------

    /**
     * Live performers, for the View editor's filter type-ahead.
     *
     * The table is a few rows, so it is held whole and matched in memory — the same reason
     * [artists] is, and what makes decision 16's "near-matches while typing" literally true
     * with no query per keystroke.
     */
    private val _performers =
        MutableStateFlow<List<RepertosaurusRepository.Performer>>(emptyList())
    public val performers: StateFlow<List<RepertosaurusRepository.Performer>> =
        _performers.asStateFlow()

    /**
     * S11, and this one is on the boot path — [reload] calls it. `getOrDefault(emptyList())`
     * here would give the View editor an empty performer picker and a title that cannot name its
     * own filter performer, with nothing said anywhere; and per V9 an empty performer list is a
     * real state, so the user could not tell the two apart.
     */
    public fun loadPerformers() {
        viewModelScope.launch {
            val outcome = withContext(io) { runCatching { coordinator().performers() } }
            outcome.fold(
                onSuccess = { performers -> _performers.value = performers },
                onFailure = { failure ->
                    _state.update {
                        it.withMessage(Messages.couldNot("load performers", failure))
                    }
                },
            )
            _performersLoaded.value = true
        }
    }

    /**
     * Journal F30: true once a performer read has completed, whatever its result, so a caller deciding
     * on "no performers" waits for [performers] to have been read rather than taking its empty start.
     */
    private val _performersLoaded = MutableStateFlow(false)
    public val performersLoaded: StateFlow<Boolean> = _performersLoaded.asStateFlow()

    /**
     * Save the View the editor produced. A View with no row behind it yet — id
     * [SessionView.UNSAVED_ID] — is a create; anything else is an update, because
     * [SessionView.saved] is exactly that distinction and the editor needs no second flag.
     *
     * Either way the app switches to the result, which is V22's full reload: the filter that
     * was just edited decides which songs are eligible and the rows must be re-queried.
     */
    public fun commitView(view: SessionView) {
        viewModelScope.launch {
            val outcome = withContext(io) {
                runCatching {
                    val views = viewCoordinator()
                    val saved = if (view.saved) {
                        views.updateView(view)
                        view
                    } else {
                        views.createView(
                            name = view.name,
                            filter = view.filter,
                            practiceInstrumentId = view.practiceInstrumentId,
                            order = view.order,
                            notes = view.notes,
                        )
                    }
                    saved to views.views()
                }
            }
            outcome.fold(
                onSuccess = { (saved, views) ->
                    _state.update { it.withViews(views) }
                    switchView(saved)
                },
                onFailure = { failure ->
                    _state.update { it.withMessage(Messages.couldNot("save that view", failure)) }
                },
            )
        }
    }

    /**
     * V23: a soft delete, like every other mutable row in this schema.
     *
     * Deleting the View that is on screen — or the one set as home — leaves the app with
     * nowhere to open, so [ViewCoordinator.start] re-resolves afterwards. That is V20: the
     * first View by `(position, id)`, or V21's unsaved stand-in when the last one has gone,
     * with the home preference written back either way.
     */
    public fun deleteView(viewId: String) {
        val wasActive = _state.value.view?.id == viewId
        viewModelScope.launch {
            val outcome = withContext(io) {
                runCatching {
                    val views = viewCoordinator()
                    val deleted = views.deleteView(viewId)
                    deleted to views.start()
                }
            }
            outcome.fold(
                onSuccess = { (deleted, start) ->
                    _homeViewId.value = start.homeViewId
                    // R23d (F15 B2): a View that was already removed is not re-stamped, and the
                    // screen says so rather than going quiet over a delete that did nothing.
                    _state.update { state ->
                        val refreshed = state.withViews(start.views)
                        if (deleted) refreshed else refreshed.withMessage(Messages.viewAlreadyRemoved().text)
                    }
                    start.view?.let { next -> if (wasActive) switchView(next) }
                },
                onFailure = { failure ->
                    _state.update {
                        it.withMessage(Messages.couldNot("delete that view", failure))
                    }
                },
            )
        }
    }

    /**
     * V19: the View the app opens on, remembered on this device only. A phone and a desktop
     * are free to open on different Views, which is the behaviour we want rather than a
     * compromise forced by the absence of a column.
     */
    public fun setHomeView(viewId: String) {
        _homeViewId.value = viewId
        viewModelScope.launch {
            val outcome = withContext(io) {
                runCatching { viewCoordinator().rememberHomeView(viewId) }
            }
            outcome.onFailure { failure ->
                _state.update {
                    it.withMessage(Messages.couldNot("set that as home", failure))
                }
            }
        }
    }

    // ---- Managing a lookup table (instruments today) -----------------------------------

    private val _lookups = MutableStateFlow(LookupsState())
    public val lookups: StateFlow<LookupsState> = _lookups.asStateFlow()

    /**
     * E45's ordering guard, on the lookup path.
     *
     * The capability path's defect is here in identical shape and with **no guard at all**: each
     * action is its own coroutine, so two writes can complete out of order and the earlier
     * read wins the screen. `setLookupNotes` is new in this arc and rides the same helper, so it
     * arrived with the fault already in it.
     *
     * Only ever read and written from the main thread — `viewModelScope` is
     * `Dispatchers.Main.immediate` and every increment is on the caller's side of a
     * `withContext(io)` — so a plain `Long` is enough and a mutex would buy nothing.
     */
    private var lookupTicket = 0L

    private fun store(kind: LookupKind) = LookupStores.of(kind, holder.repository)

    public fun loadLookups(kind: LookupKind) {
        val ticket = ++lookupTicket
        _lookups.update { it.copy(kind = kind, busy = true, sequence = ticket) }
        viewModelScope.launch {
            val outcome = withContext(io) { runCatching { store(kind).items() } }
            _lookups.update { state ->
                if (state.sequence != ticket) {
                    state
                } else {
                    outcome.fold(
                        onSuccess = { state.copy(items = it, busy = false) },
                        onFailure = { failure ->
                            state.copy(busy = false, message = Messages.couldNot("load", failure))
                        },
                    )
                }
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
        mutate(kind) {
            it.add(trimmed)
            "Added $trimmed"
        }
    }

    public fun renameLookup(kind: LookupKind, id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        mutate(kind) {
            it.rename(id, trimmed)
            "Renamed to $trimmed"
        }
    }

    /**
     * R23d (F15 B2): a row that was already removed is not re-stamped, and the screen says it was
     * already removed rather than claiming a removal that wrote nothing.
     */
    public fun removeLookup(kind: LookupKind, id: String, name: String) {
        mutate(kind) { Messages.lookupRemoval(name, it.remove(id)).text }
    }

    /** One lookup write; [block] writes and returns what to say about it. */
    private fun mutate(kind: LookupKind, block: (LookupStore) -> String) {
        val ticket = ++lookupTicket
        _lookups.update { it.copy(busy = true, sequence = ticket) }
        viewModelScope.launch {
            val outcome = withContext(io) {
                runCatching {
                    val store = store(kind)
                    val said = block(store)
                    said to store.items()
                }
            }
            _lookups.update { state ->
                // E45: a result that is not the newest is dropped. Two renames dispatched in
                // quick succession otherwise complete in either order, and the earlier read
                // paints the screen with the row as it was before the second write.
                if (state.sequence != ticket) {
                    state
                } else {
                    outcome.fold(
                        onSuccess = { (said, items) -> state.copy(items = items, busy = false, message = said) },
                        onFailure = { failure ->
                            state.copy(
                                busy = false,
                                message = Messages.failed(failure),
                            )
                        },
                    )
                }
            }
            if (outcome.isSuccess) reload()
        }
    }

    /**
     * E16: the notes column, for the kinds that declare one. The screen offers the field only
     * where [LookupKind.hasNotes] is true and the store fails loudly anywhere else, which is
     * how `venue` — which has no such column (E16a) — cannot be given one by a screen that
     * guessed.
     */
    public fun setLookupNotes(kind: LookupKind, id: String, notes: String?) {
        val trimmed = notes?.trim()?.takeIf { it.isNotEmpty() }
        mutate(kind) {
            it.setNotes(id, trimmed)
            if (trimmed == null) "Notes cleared" else "Notes saved"
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
        /** E45: which request this state belongs to. A stale result never lands. */
        val sequence: Long = 0L,
    )

    // ---- The song-capability editor (editing E1-E11) -------------------------------------
    //
    // **E1: nothing in this section writes `practice_event`.** Every write below goes through
    // `CapabilityCoordinator`, which reaches `song_performer` and nothing else; the only method
    // on this class that logs practice is `log`, and nothing here calls it. "Coralie can sing
    // this" is a fact about the repertoire, true whether or not anyone practised today, and the
    // editor is now one long-press from the tap that *is* a practice event.

    private val _capabilities = MutableStateFlow(CapabilityState())
    public val capabilities: StateFlow<CapabilityState> = _capabilities.asStateFlow()

    /**
     * **E45's sequence number, and the whole of the ordering guarantee on this path.**
     *
     * Every capability action was its own `launch` with no ordering between them, and the only
     * guard compared **song identity** — which is not freshness. Two writes on the same song
     * could therefore complete out of order and the earlier read would win the screen: a chip
     * the user had just added vanished, or a lead flag reverted. The same missing number let a
     * result land after close-then-reopen on the *same* song, because identity matched.
     *
     * A monotonic counter is the smallest thing that fixes both. Every request takes the next
     * value before it goes near IO, stamps it into the state, and applies its result only if the
     * state still carries it. Only ever touched from the main thread — `viewModelScope` is
     * `Dispatchers.Main.immediate` and each increment happens on the caller's side of the
     * `withContext(io)` — so a plain `Long` is correct and a lock would buy nothing.
     */
    private var capabilityTicket = 0L

    private fun capabilityCoordinator(): CapabilityCoordinator =
        CapabilityCoordinator(holder.repository)

    /**
     * Open the editor on one song and read its line-up (E10). A read, and only a read.
     *
     * R18: the logger's feel sheet and the Songs detail both open it here, so there is one
     * capability editor and one E45 ticket — the Songs route does not grow a second.
     */
    public fun openCapabilities(song: SongIdentity) {
        val ticket = ++capabilityTicket
        _capabilities.value = CapabilityState(
            song = song,
            busy = true,
            sequence = ticket,
            revision = _capabilities.value.revision,
        )
        viewModelScope.launch {
            val outcome = withContext(io) {
                runCatching { capabilityCoordinator().lineUp(song.songId) }
            }
            _capabilities.update { state ->
                // The sheet may already have been closed, reopened on another song, or reopened
                // on *this* one while the read was in flight. The sequence covers all three;
                // song identity covered only the middle case (E45).
                if (state.sequence != ticket) {
                    state
                } else {
                    outcome.fold(
                        onSuccess = { state.copy(lineUp = it, busy = false) },
                        onFailure = { failure ->
                            state.copy(
                                busy = false,
                                error = Messages.couldNot("read the line-up", failure),
                            )
                        },
                    )
                }
            }
        }
    }

    /**
     * Closing resets the state, and the fresh [CapabilityState] carries sequence `0` — which no
     * in-flight request can hold, so nothing that was already running can paint a closed sheet.
     * The revision (F18 N6) carries over: it counts writes, not sheets.
     */
    public fun closeCapabilities() {
        _capabilities.value = CapabilityState(revision = _capabilities.value.revision)
    }

    /**
     * E5, E6: create on enter, and **insert-or-revive**. A pair that already exists as a
     * tombstone derives the same primary key, so the repository restores it rather than
     * inserting a second row — the whole reason `add` takes no facts.
     */
    public fun addCapability(performerName: String, instrumentName: String) {
        val performer = performerName.trim()
        val instrument = instrumentName.trim()
        if (performer.isEmpty() || instrument.isEmpty()) return
        val songId = _capabilities.value.song?.songId ?: return
        // E44: `isAdd` is what lets the form clear on **confirmed success** rather than on
        // dispatch. It used to blank both typed names the instant the button was pressed, so a
        // failed write left two empty boxes and no record of what the user had entered.
        mutateCapabilities(isAdd = true) {
            Messages.junctionWrite(
                it.add(songId, performer, instrument),
                done = Messages.capabilityAdded(performer, instrument),
                unchanged = Messages.capabilityPresent(performer, instrument),
            )
        }
    }

    /**
     * E4, E8: the three editable facts. The range is rejected by the core on any row that is
     * not a voice, which is why the editor never offers the control there.
     *
     * R23c: `false` means nothing was saved — the row or its song was removed — and says so on
     * the error channel rather than as "Saved".
     */
    public fun updateCapability(capability: SongCapability) {
        mutateCapabilities {
            Messages.junctionUpdate(it.update(capability), done = Messages.capabilitySaved(capability.performerName))
        }
    }

    /**
     * E7: a soft delete. The tombstone is what makes E6's revive possible at all.
     *
     * R23d: a row that was already removed is not re-stamped, and the sheet says it was already
     * gone rather than claiming to have removed it.
     */
    public fun removeCapability(capability: SongCapability) {
        mutateCapabilities {
            Messages.junctionWrite(
                it.remove(capability.id),
                done = Messages.capabilityRemoved(capability.performerName),
                alreadyRemoved = Messages.capabilityAlreadyRemoved(capability.performerName),
            )
        }
    }

    /**
     * One write, then the line-up again. [block] says what the write did on its channel, through
     * the core's [Messages] (R23c, E43): every write on this path can be a no-op, and "Saved"
     * over a write that did nothing is the exact confusion E43 exists to end.
     *
     * The session list is reloaded afterwards because a capability row is what the View's
     * eligibility filter reads (views V11): adding or removing one genuinely changes which
     * songs are in the list behind this sheet. **That reload is a query.** It is the only
     * thing this path does beyond `song_performer`, and it writes nothing at all.
     */
    private fun mutateCapabilities(
        isAdd: Boolean = false,
        /** The write, and what to say about it (R23c). */
        block: (CapabilityCoordinator) -> Notice?,
    ) {
        val songId = _capabilities.value.song?.songId ?: return
        val ticket = ++capabilityTicket
        // Both channels are cleared together (E43): the previous write's confirmation must not
        // survive beside this one's failure, and vice versa.
        _capabilities.update {
            it.copy(busy = true, message = null, error = null, sequence = ticket)
        }
        viewModelScope.launch {
            val outcome = withContext(io) {
                runCatching {
                    val coordinator = capabilityCoordinator()
                    val said = WriteOutcome.of(block(coordinator))
                    said to coordinator.lineUp(songId)
                }
            }
            _capabilities.update { state ->
                // F18 N6: every landed write moves the revision — even one dropped below as
                // stale, and even a refusal — because the line-up on disk may have changed and
                // the Songs detail reads its own copy when this moves.
                val revised = if (outcome.isSuccess) state.copy(revision = state.revision + 1) else state
                if (revised.sequence != ticket) {
                    revised
                } else {
                    outcome.fold(
                        onSuccess = { (said, lineUp) ->
                            revised.copy(
                                lineUp = lineUp,
                                busy = false,
                                message = said.message,
                                error = said.error,
                                // E44: only on the confirmed-success path. R23c: a refusal read
                                // the line-up back all the same, but does not move the counter.
                                addsCommitted = revised.addsCommitted + if (isAdd && !said.refused) 1L else 0L,
                            )
                        },
                        // E43: the error channel, rendered in the error colour. This used to be
                        // the same field as the confirmation above and painted the same accent
                        // colour, so a refused write read as a success.
                        onFailure = { failure ->
                            revised.copy(busy = false, error = Messages.failed(failure))
                        },
                    )
                }
            }
            if (outcome.isSuccess) {
                // E5 can have created a performer or an instrument, so the roster the View
                // editor types against and the chip row that *is* the instrument table must
                // not lag behind the table they read.
                loadPerformers()
                reload()
            }
        }
    }

    /**
     * The capability editor's state. [song] null means the sheet is closed, which is the same
     * shape the rest of this class uses for "no sheet".
     */
    public data class CapabilityState(
        val song: SongIdentity? = null,
        val lineUp: List<PerformerLineUp> = emptyList(),
        val busy: Boolean = false,
        /** E43: a confirmation, and only ever a confirmation. */
        val message: String? = null,
        /** E43: a refusal, rendered in the error colour and never where a confirmation goes. */
        val error: String? = null,
        /**
         * E44: adds this sheet has seen **land**. The add form clears when this changes, which
         * is what makes "clear on confirmed success, never on dispatch" expressible from a
         * composable that cannot see the write.
         */
        val addsCommitted: Long = 0L,
        /** E45: which request this state belongs to. A stale result never lands. */
        val sequence: Long = 0L,
        /**
         * F18 N6: capability writes that have **landed**, whether or not their result was shown.
         * The Songs detail re-reads its line-up when this moves — on the write's completion,
         * not on the sheet's dismissal, which can come before the write has run.
         */
        val revision: Long = 0L,
    )

    // ---- Import and export ------------------------------------------------------------

    /**
     * The suggested backup file name for the system save sheet.
     *
     * The date comes straight from [Timestamps], not from the repository. It is the same
     * value — `RepertosaurusRepository.today()` reads the system clock and nothing else — but
     * reaching it through the repository opens the database, on the main thread, from a
     * composition. That is a boot-path database touch hiding inside a filename.
     */
    public fun exportFileName(): String = holder.exportFileName(Timestamps.today())

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
            outcome.onSuccess { state ->
                // Everything the old database backed is gone: taps, rows, selection. The new
                // file passed the same gate the boot path uses, so `state` is Ready unless the
                // copy itself failed — and either way it replaces whatever the screen was
                // showing, which is how an import performed from RecoveryScreen gets the user
                // back to the Session screen (S4).
                _databaseState.value = state
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
        viewModelScope.launch {
            // Deleting the staged copy is housekeeping: it costs the user nothing if it
            // fails, and it must not be the thing that takes down a screen whose entire
            // purpose is recovering from a database that will not open (S8). The result is
            // still surfaced rather than dropped (S11), just as a message and not a screen.
            val outcome = withContext(io) { runCatching { holder.discardStagedImport() } }
            outcome.onFailure { failure ->
                _transfer.update {
                    it.copy(message = Messages.couldNot("clear the staged file", failure))
                }
            }
        }
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

    // ---- First load ---------------------------------------------------------------------

    /**
     * **This block must stay last in the class body. Do not move it up to the constructor.**
     *
     * Kotlin runs property initialisers and `init` blocks in *declaration order*, and
     * [viewModelScope] is `Dispatchers.Main.immediate` — a `launch` from the constructor
     * thread starts executing its body *before* `launch` returns, and its `withContext(io)`
     * can resume while the constructor is still running. Any property declared below the
     * `init` block is therefore still `null` when the coroutine touches it, however the
     * Kotlin type says otherwise.
     *
     * That is not theoretical. With `init { reload() }` up beside the constructor the app
     * crashed on every launch with a NullPointerException on `_performers.setValue` from
     * [loadPerformers], and `_homeViewId` had the identical latent fault — it escaped only
     * because [reload]'s IO block runs `SampleData.installIfEmpty` over a 479-song database,
     * which is slow enough that construction always won that particular race. On a small or
     * empty database it would have lost.
     *
     * Keeping the first load here rather than sprinkling `lateinit` or null checks over the
     * state flows means the hazard is structurally impossible instead of individually
     * defended: everything the class owns is constructed before anything runs.
     */
    init {
        reload()
    }

    public companion object {
        private fun kilobytes(bytes: Long): String = "${(bytes + 1023) / 1024} kB"

        public fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { SessionViewModel(graph.holder, graph.preferences, graph.deviceId) }
        }
    }
}
