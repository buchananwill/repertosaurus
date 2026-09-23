package dev.repertosaurus.session

import dev.repertosaurus.data.RepertosaurusRepository

/**
 * The eligibility half of a View (views V9, V11, V24), as a value.
 *
 * Three independent components, each of which degrades to **no constraint** when absent, so
 * all eight combinations are one query rather than eight. All three absent is the unfiltered
 * case — every live song — which is what makes V21's empty state exactly today's behaviour.
 *
 * V9: a song with no `song_performer` row at all asserts nothing and is excluded by *any*
 * filter. Treating absent data as a match would make the filter useless on this dataset,
 * where 147 of 479 songs are unannotated.
 *
 * V24: ids, never names, so renaming a performer or an instrument leaves every View intact.
 *
 * [instrumentId] is the instrument the *capability* is filtered on and is **not** the
 * instrument the session practises — that is [SessionView.practiceInstrumentId], and V12
 * keeps the two apart deliberately.
 */
public data class ViewFilter(
    val performerId: String? = null,
    val instrumentId: String? = null,
    val leadOnly: Boolean = false,
) {

    /** True when this filter constrains nothing, so every live song is eligible. */
    public val unfiltered: Boolean
        get() = performerId == null && instrumentId == null && !leadOnly

    /** `saved_view.filter_lead_only`, and the `:leadOnly` bind of `selectByStaleness`. */
    public val leadOnlyFlag: Long
        get() = if (leadOnly) 1L else 0L

    public companion object {
        /** No constraint at all. The empty state of V21 and the whole repertoire. */
        public val NONE: ViewFilter = ViewFilter()

        public fun of(
            performerId: String?,
            instrumentId: String?,
            leadOnlyFlag: Long,
        ): ViewFilter = ViewFilter(performerId, instrumentId, leadOnlyFlag == 1L)
    }
}

/**
 * The filter in words — which parts appear, in what order, and how they are joined.
 *
 * The **composition rule** lives here and the **lookup** does not: [summarise] takes id → name
 * resolution as function parameters, so resolving a performer id against whatever list a UI
 * happens to hold stays a UI concern while the sentence it builds becomes testable on the JVM.
 * `SessionRow.badge` lives in the core for exactly this reason.
 *
 * V11: each component independently absent means no constraint, so all three absent reads as
 * the whole repertoire — V21's empty state, and exactly today's behaviour.
 *
 * V24: names are resolved at render time and never stored. A View holds ids, so renaming a
 * performer or an instrument changes this line and nothing else — and an id whose row has gone
 * reads as a marker rather than vanishing, because a filter that silently stops describing
 * itself is worse than one that admits a gap.
 */
public object ViewSummary {

    /** All three components absent. */
    public const val UNFILTERED: String = "Every song"

    /** The filter names a performer whose row is not in the caller's list. */
    public const val MISSING_PERFORMER: String = "a deleted performer"

    /** The filter names an instrument whose row is not in the caller's list. */
    public const val MISSING_INSTRUMENT: String = "no instrument"

    /** The lead-only component, which needs no lookup. */
    public const val LEAD_ONLY: String = "lead only"

    /** Performer, then instrument, then lead-only — the order the editor asks for them in. */
    public const val SEPARATOR: String = " · "

    public fun summarise(
        filter: ViewFilter,
        performerName: (String) -> String?,
        instrumentName: (String) -> String?,
    ): String {
        if (filter.unfiltered) return UNFILTERED
        val parts = buildList {
            filter.performerId?.let { add(performerName(it) ?: MISSING_PERFORMER) }
            filter.instrumentId?.let { add(instrumentName(it) ?: MISSING_INSTRUMENT) }
            if (filter.leadOnly) add(LEAD_ONLY)
        }
        return parts.joinToString(SEPARATOR)
    }

    /**
     * **E47, E42: does this View's filter name a row that is gone?**
     *
     * E12 made deletion of a performer reachable from the drawer and V11a excluded soft-deleted
     * performers and instruments from eligibility. Both are right, and together they turn such a
     * View into an **empty list on a phone holding 479 songs** — under which the session screen
     * rendered *"No songs yet. Use Import to load your database file"*, pointing the user at the
     * one destructive path in the app.
     *
     * This is the predicate that tells that case apart from a genuinely empty repertoire, and it
     * is a **rule**, which is why it is here beside [summarise] rather than being a branch
     * invented in a composable. It takes the same id → name resolution [summarise] does, for the
     * same reason: the composition rule is the core's, the lookup is the caller's.
     *
     * There is no sensible automatic repair (E42) — nothing can stand in for the performer the
     * user meant — so the honest answer is an empty state that names the problem and opens the
     * View editor. The caller renders that; this decides when.
     */
    public fun namesRemovedRow(
        filter: ViewFilter,
        performerName: (String) -> String?,
        instrumentName: (String) -> String?,
    ): Boolean {
        val performerGone = filter.performerId?.let { performerName(it) == null } ?: false
        val instrumentGone = filter.instrumentId?.let { instrumentName(it) == null } ?: false
        return performerGone || instrumentGone
    }
}

/**
 * A resolved View: its identity, its filter, its practice instrument and its sort direction.
 *
 * A View exists because *which songs are eligible* and *which instrument the session is
 * about* were the same control — the instrument chip — and must not be. The chip selected
 * what got logged and what staleness was measured against, and said nothing at all about
 * which songs appeared, so all 479 songs showed on every chip including the 98 another
 * singer fronts.
 *
 * V13: [practiceInstrumentId] is one field, not two. The instrument a View logs to is the
 * instrument it measures staleness on.
 *
 * V21: with no `saved_view` rows the app behaves exactly as it does today, and [unsaved] is
 * that state — no filter, the remembered chip, the remembered direction. Views are additive
 * and the empty state is the current product.
 */
public data class SessionView(
    val id: String,
    val name: String,
    val filter: ViewFilter,
    val practiceInstrumentId: String,
    val order: SessionOrder,
    // No default. `updateView` writes this column back, so a caller that *constructs* a saved
    // View rather than `copy`ing one would silently move it to the head of the list — and a
    // View editor is exactly that caller.
    val position: Long,
    val notes: String? = null,
) {

    /** False for the V21 stand-in, which has no `saved_view` row behind it. */
    public val saved: Boolean
        get() = id != UNSAVED_ID

    /**
     * Two Views produce the same rows exactly when their identity, their filter and their
     * practice instrument agree; the direction is applied in Kotlin (V14) and never
     * re-queries.
     *
     * This is a pure domain rule, not a ViewModel detail: every UI that loads rows off the
     * main thread has to drop the answer to a query the user has already moved on from, and
     * without this each one would re-derive the same three-field comparison.
     */
    public fun sameQuery(other: SessionView?): Boolean =
        other != null &&
            other.id == id &&
            other.filter == filter &&
            other.practiceInstrumentId == practiceInstrumentId

    /**
     * V13a: tapping a different instrument chip **forks to an unsaved View; it never edits
     * the saved row.**
     *
     * The chip is a mid-practice control on the primary tap path and `saved_view` is a synced
     * row, so letting a chip tap write `practice_instrument_id` would make an idle thumb-press
     * mutate configuration on every other device. The fork keeps this View's filter and its
     * direction, takes the tapped instrument, and is discarded when the user switches away.
     * Editing a saved View is an explicit action in the switcher, never a side effect of
     * practising.
     */
    public fun forkedTo(practiceInstrumentId: String): SessionView = SessionView(
        id = UNSAVED_ID,
        name = "",
        filter = filter,
        practiceInstrumentId = practiceInstrumentId,
        order = order,
        position = 0L,
        notes = null,
    )

    public companion object {
        /**
         * The id of the View that is not a View: the current-behaviour state of V21. Empty
         * rather than a plausible-looking uuid, so a row can never be written under it and
         * a stale home preference can never resolve to it.
         */
        public const val UNSAVED_ID: String = ""

        /** V21's empty state: no filter, the remembered chip, the remembered direction. */
        public fun unsaved(
            practiceInstrumentId: String,
            order: SessionOrder = SessionOrder.COLDEST_FIRST,
        ): SessionView = SessionView(
            id = UNSAVED_ID,
            name = "",
            filter = ViewFilter.NONE,
            practiceInstrumentId = practiceInstrumentId,
            order = order,
            position = 0L,
        )
    }
}

/**
 * The home-View rule, pure so it is testable without a database — the same shape as
 * [SessionInstruments.resolve], deliberately.
 *
 * V19: the home View is a **local preference, never a column**. An `is_home INTEGER` on many
 * rows has no total order under last-write-wins: two devices each promoting a different View
 * both end up true and no subsequent sync repairs it. Storing it beside the instrument and
 * sort preferences also lets a phone and a desktop open on different Views, which is the
 * desirable behaviour rather than a compromise.
 */
public object SessionViews {

    /**
     * V20: the remembered View if it is still there, otherwise the first by `(position,
     * id)` — which is the order [RepertosaurusRepository.savedViews] already returns, so this
     * takes the head rather than re-sorting. A View deleted on another device must not leave
     * the app opening on nothing. The caller writes the resolution back.
     */
    public fun resolve(remembered: String?, views: List<SessionView>): SessionView? =
        views.firstOrNull { it.id == remembered } ?: views.firstOrNull()

    /**
     * The View already on screen, if a reload has not invalidated it.
     *
     * A saved View is re-read from [views] so an edit made elsewhere lands, and dropped when
     * its row has gone — which is V20's job to resolve. V21's unsaved stand-in has no row and
     * survives on its own, unless the instrument it points at has left the chip row.
     */
    public fun keepable(
        current: SessionView?,
        views: List<SessionView>,
        chips: List<InstrumentChip>,
    ): SessionView? = when {
        current == null -> null
        current.saved -> views.firstOrNull { it.id == current.id }
        else -> current.takeIf { view -> chips.any { it.id == view.practiceInstrumentId } }
    }
}

/**
 * Everything the Session screen needs to open, resolved in one pass — V20, V20a and V21
 * together.
 *
 * [view] is null only when there is nothing to open on at all: an empty `instrument` table.
 * When it is not null its practice instrument is guaranteed to be one of [instruments], which
 * is the invariant `SessionState.selectedInstrumentId` rests on.
 */
public data class SessionStart(
    val instruments: List<InstrumentChip>,
    val views: List<SessionView>,
    val view: SessionView?,
    val homeViewId: String?,
)

/**
 * CRUD over `saved_view`, and home-View resolution.
 *
 * Blocking, like [SessionCoordinator], and meant to be called off the main thread. It owns
 * the mapping between the stored row and [SessionView] — including V17's stored
 * [SessionOrder] name — so nothing above it handles the raw columns.
 */
public class ViewCoordinator(
    private val repository: RepertosaurusRepository,
    private val preferences: SessionPreferences,
) {

    /** Live Views in `(position, id)` order (V18). */
    public fun views(): List<SessionView> = repository.savedViews().map(::toSessionView)

    /**
     * **The one entry point for opening the Session screen.** V20 + V20a + V21 in a single
     * call, so no caller has to know that those three are a sequence.
     *
     * [current] is whatever View is already on screen. It wins if it survived, so a reload
     * triggered by an unrelated edit — adding an instrument, renaming one — does not throw the
     * musician back to their home View mid-practice.
     *
     * With no saved Views the answer is V21's stand-in built from the remembered chip and the
     * remembered direction: the app behaves exactly as it does today and the feature cannot
     * regress a fresh install.
     *
     * Otherwise V20 applies — the remembered home id if it still resolves, the first View by
     * `(position, id)` if it does not — and **the resolution is written back**, so a View
     * deleted on another device cannot leave the app opening on nothing twice.
     *
     * V20a then resolves the chosen View's `practice_instrument_id` against the live
     * `instrument` table and **writes that repair back too**. Instrument removal is an
     * unguarded soft delete that already ships and can also arrive by sync, so a View can name
     * a tombstoned instrument through no fault of the user. Without this the app opens with no
     * chip highlighted and every tap writes `practice_event.instrument_id` pointing at a
     * removed instrument — silently, because the foreign key is still satisfied by the
     * tombstoned row. The fallback is the first chip, the same rule
     * [SessionInstruments.resolve] already applies.
     */
    public fun start(current: SessionView? = null): SessionStart {
        val chips = SessionInstruments.chips(repository.instruments())
        val saved = views()
        val chosen = SessionViews.keepable(current, saved, chips) ?: choose(saved, chips)
        val repaired = chosen?.let { repair(it, chips) }
        // The repair rewrote a row, so the list the switcher renders has to be re-read or it
        // describes a View in a shape that no longer exists.
        val rewritten = repaired != null && repaired.saved && repaired != chosen
        val views = if (rewritten) views() else saved
        return SessionStart(
            instruments = chips,
            views = views,
            view = repaired,
            homeViewId = preferences.homeViewId(),
        )
    }

    /** V20 and V21: the home View if there is one, otherwise today's behaviour. */
    private fun choose(views: List<SessionView>, chips: List<InstrumentChip>): SessionView? {
        if (views.isEmpty()) {
            val instrumentId = SessionInstruments.resolve(preferences.lastInstrumentId(), chips)
                ?: return null
            preferences.rememberInstrument(instrumentId)
            return SessionView.unsaved(instrumentId, SessionOrder.parse(preferences.lastOrder()))
        }
        val resolved = SessionViews.resolve(preferences.homeViewId(), views) ?: return null
        preferences.rememberHomeView(resolved.id)
        return resolved
    }

    /**
     * V20a: the practice instrument, resolved against the live chip row and written back.
     * Null when there is no instrument at all — with nothing to point at, there is no View to
     * open, and `SessionState` must never carry a `selectedInstrumentId` that is not a chip.
     */
    private fun repair(view: SessionView, chips: List<InstrumentChip>): SessionView? {
        val instrumentId = SessionInstruments.resolve(view.practiceInstrumentId, chips)
            ?: return null
        if (instrumentId == view.practiceInstrumentId) return view
        val fixed = view.copy(practiceInstrumentId = instrumentId)
        if (fixed.saved) updateView(fixed)
        preferences.rememberInstrument(instrumentId)
        return fixed
    }

    /**
     * V13b: the sort direction persists to wherever the active View came from —
     * `saved_view.sort_order` when it is saved, [SessionPreferences] when it is V21's unsaved
     * View. Two branches with no I/O of their own to choose between, which is why the choice
     * is here and not in a ViewModel.
     *
     * Note that under V13a a chip tap has already forked to an unsaved View, so a direction
     * change after one lands in preferences rather than on the saved row.
     *
     * @return the refreshed list when the direction landed on a saved row, so the switcher can
     *   re-render; null when it went to preferences and no row moved.
     */
    public fun rememberOrder(view: SessionView): List<SessionView>? =
        if (view.saved) {
            updateView(view)
            views()
        } else {
            preferences.rememberOrder(view.order.name)
            null
        }

    /** Remembered across launches, per device (V19). */
    public fun rememberHomeView(viewId: String) {
        preferences.rememberHomeView(viewId)
    }

    /**
     * A new View, appended at the end. The id is random UUIDv4 (V16): `name` is the only
     * candidate key and it is user-editable, so a derived id would violate decision 5.
     */
    public fun createView(
        name: String,
        filter: ViewFilter,
        practiceInstrumentId: String,
        order: SessionOrder = SessionOrder.COLDEST_FIRST,
        notes: String? = null,
    ): SessionView {
        val display = name.trim()
        require(display.isNotEmpty()) { "a view needs a name" }
        return toSessionView(
            repository.createSavedView(
                name = display,
                filterPerformerId = filter.performerId,
                filterInstrumentId = filter.instrumentId,
                filterLeadOnly = filter.leadOnlyFlag,
                practiceInstrumentId = practiceInstrumentId,
                sortOrder = order.name,
                notes = notes,
            ),
        )
    }

    /** Every field is editable: nothing about a View is derived from another (V16). */
    public fun updateView(view: SessionView) {
        require(view.saved) { "the unsaved view has no row to update" }
        val display = view.name.trim()
        require(display.isNotEmpty()) { "a view needs a name" }
        repository.updateSavedView(
            RepertosaurusRepository.SavedView(
                id = view.id,
                name = display,
                filterPerformerId = view.filter.performerId,
                filterInstrumentId = view.filter.instrumentId,
                filterLeadOnly = view.filter.leadOnlyFlag,
                practiceInstrumentId = view.practiceInstrumentId,
                sortOrder = view.order.name,
                position = view.position,
                notes = view.notes,
            ),
        )
    }

    /**
     * V23: a tombstone, never a `DELETE`. There is no hard delete anywhere in this schema.
     *
     * @return false when there was no live View to delete (R23d): nothing was written.
     */
    public fun deleteView(viewId: String): Boolean = repository.deleteSavedView(viewId)

    private fun toSessionView(row: RepertosaurusRepository.SavedView): SessionView = SessionView(
        id = row.id,
        name = row.name,
        filter = ViewFilter.of(
            performerId = row.filterPerformerId,
            instrumentId = row.filterInstrumentId,
            leadOnlyFlag = row.filterLeadOnly,
        ),
        practiceInstrumentId = row.practiceInstrumentId,
        // V17: the stored value is the enum name under a CHECK. An unreadable one is a row
        // a later build wrote, and coldest first is the default that never surprises.
        order = SessionOrder.parse(row.sortOrder),
        position = row.position,
        notes = row.notes,
    )
}
