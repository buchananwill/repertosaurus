package dev.repertosaurus.session

import dev.repertosaurus.data.SongCatalog
import dev.repertosaurus.data.SongMerge

/**
 * **The merge preview's plan — repertoire-editing R33-R35, R38 — pure, in the core** (F17 N2): the
 * per-field picks, the child rows carried or deselected, and the events carried or dropped. The
 * ViewModel holds one of these and applies its changes; `SongMerge` writes what [request] says.
 */

/** Which song a field is taken from. */
public enum class MergeSide { SURVIVOR, LOSER }

/**
 * R34: one field whose two values differ, and the side it is taken from. It carries no display
 * text: the preview names each side's value at render time with [Messages.mergeFieldValue], so the
 * user's note spelling (R40-R42) reaches it the way it reaches every other note name.
 */
public data class MergeFieldPick(
    val field: SongField,
    val pick: MergeSide,
)

/**
 * R35: one child key in the union of both songs' live rows. [kept] is the checkbox: a kept key
 * ends live on the survivor, an unkept one ends removed — whichever side held it.
 */
public data class MergeChildRow(
    val key: SongMerge.ChildKey,
    val parentNames: List<String>,
    val onSurvivor: Boolean,
    val onLoser: Boolean,
    /** R38a / R23b: a parent lookup is removed; carrying the row restores it. */
    val parentRemoved: Boolean,
    val kept: Boolean,
)

/**
 * One merge as the user is shaping it. [picks] is one side per [SongField] (R34); [dropped] are the
 * child keys deselected (R35); [droppedEvents] are loser events deselected (R33).
 *
 * **The merged row is built by field key** — [SongDraft.value] and [SongDraft.with] over
 * [SongField.entries] — and goes through [SongDraft.validate], so a merged value passes exactly
 * the `CHECK` mirror a Save does (F11 N9, F17 N2). No column is listed here.
 */
public data class MergePlan(
    val survivor: SongMerge.Side,
    val loser: SongMerge.Side,
    val picks: Map<SongField, MergeSide>,
    val dropped: Set<SongMerge.ChildKey> = emptySet(),
    val droppedEvents: Set<String> = emptySet(),
) {
    public val survivorDraft: SongDraft = SongDraft.from(survivor.record)
    public val loserDraft: SongDraft = SongDraft.from(loser.record)

    public fun pick(field: SongField): MergeSide = picks[field] ?: MergeSide.SURVIVOR

    /**
     * R34: the fields worth a choice — those whose two values differ — in [SongField] order.
     * Artist and groove differ **by id** ([SongDraft.identity]), so two same-named artists are
     * offered as a choice (session 09 ruling on 3b's #8).
     */
    public val fields: List<MergeFieldPick>
        get() = SongField.entries
            .filter { survivorDraft.identity(it) != loserDraft.identity(it) }
            .map { field -> MergeFieldPick(field = field, pick = pick(field)) }

    /** One side's draft, which the preview names a field's value from ([Messages.mergeFieldValue]). */
    public fun draft(side: MergeSide): SongDraft = when (side) {
        MergeSide.SURVIVOR -> survivorDraft
        MergeSide.LOSER -> loserDraft
    }

    /** The survivor's draft with every field picked from the loser taken from it. */
    public val merged: SongDraft
        get() = SongField.entries.fold(survivorDraft) { draft, field ->
            if (pick(field) == MergeSide.LOSER) draft.with(field, loserDraft) else draft
        }

    /** The merged row through the detail's own validation (R14's `CHECK` mirror). */
    public fun validate(): SongDraftValidation = merged.validate()

    /** R35: the union of both songs' live child rows, by table then name. */
    public val children: List<MergeChildRow>
        get() {
            val held = survivor.children.associateBy { it.key }
            val offered = loser.children.associateBy { it.key }
            return (held.keys + offered.keys).map { key ->
                val sides = listOfNotNull(held[key], offered[key])
                MergeChildRow(
                    key = key,
                    parentNames = sides.first().parentNames,
                    onSurvivor = key in held,
                    onLoser = key in offered,
                    parentRemoved = sides.any { it.parentRemoved },
                    kept = key !in dropped,
                )
            }.sortedWith(compareBy<MergeChildRow> { it.key.table.ordinal }.thenBy { it.parentNames.joinToString().lowercase() })
        }

    /** R14's `CHECK` mirror over the merged row, by field: empty when the merge can be confirmed. */
    public val errors: Map<SongField, String>
        get() = (validate() as? SongDraftValidation.Invalid)?.errors.orEmpty()

    /** R33: whether the loser event [eventId] is carried — the checkbox. Every event is, until deselected. */
    public fun eventKept(eventId: String): Boolean = eventId !in droppedEvents

    /** R33: loser events carried — every one not deselected. */
    public val eventsCarried: Int get() = loser.events.count { eventKept(it.id) }

    public val eventsDropped: Int get() = loser.events.size - eventsCarried

    public fun withPick(field: SongField, side: MergeSide): MergePlan = copy(picks = picks + (field to side))

    public fun toggleChild(key: SongMerge.ChildKey): MergePlan =
        copy(dropped = if (key in dropped) dropped - key else dropped + key)

    public fun toggleEvent(eventId: String): MergePlan =
        copy(droppedEvents = if (eventId in droppedEvents) droppedEvents - eventId else droppedEvents + eventId)

    /**
     * The other song survives. Every choice is **reset to the defaults for the new direction**:
     * a pick means "this side's value", and which side is which has just changed.
     */
    public fun swapped(): MergePlan = of(survivor = loser, loser = survivor)

    /** What the merge writes, or null while the merged row does not validate. */
    public fun request(): SongMerge.Request? {
        val fields = (validate() as? SongDraftValidation.Valid)?.fields ?: return null
        return SongMerge.Request(
            survivorId = survivor.record.id,
            loserId = loser.record.id,
            fields = fields,
            // R38b: what the user dropped, so a row that appears after this preview is kept.
            dropChildren = dropped,
            dropEvents = droppedEvents,
        )
    }

    public companion object {
        /** R33-R35's defaults: every field from the survivor unless blank there, the union, every event. */
        public fun of(survivor: SongMerge.Side, loser: SongMerge.Side): MergePlan {
            val kept = SongDraft.from(survivor.record)
            val merged = SongDraft.from(loser.record)
            return MergePlan(
                survivor = survivor,
                loser = loser,
                picks = SongField.entries.associateWith { defaultPick(it, kept, merged) },
            )
        }

        /** R34: the survivor's value, or the loser's where the survivor's is blank. */
        public fun defaultPick(field: SongField, survivor: SongDraft, loser: SongDraft): MergeSide =
            if (survivor.value(field) == null && loser.value(field) != null) MergeSide.LOSER else MergeSide.SURVIVOR
    }
}

/**
 * **The merge overlay's state** (R38), separate from the detail's: opened on [songId] from the
 * song detail, it is the picker while [plan] is null and the preview once both sides are read.
 *
 * Every rule of the overlay is here (style review F27 B2) — when a pick may start a read, what a
 * read lands as, what back and a closing detail do, and what a confirm sends — so the ViewModel
 * only dispatches.
 */
public data class MergeState(
    /** The song the detail had open when "Merge with…" was pressed. */
    val songId: String,
    val query: String = "",
    val plan: MergePlan? = null,
    /**
     * F28 N4: which database the [plan] was read from — the holder's generation at the read. A
     * confirm writes to that database or to none: a merge previewed before an import must not land
     * in the imported file. Null while there is no plan.
     */
    val generation: Long? = null,
    /** The two sides are being read. */
    val loading: Boolean = false,
    /** The merge is in flight; every control is dead. */
    val merging: Boolean = false,
    /** E43: a refusal or failure, as an error line (D97). */
    val error: String? = null,
) {
    /** The picker's rows: every live song but this one, filtered and ordered by [SongSearch]. */
    public fun candidates(songs: List<SongCatalog.SongListEntry>): List<SongCatalog.SongListEntry> =
        SongSearch.search(songs, query).filter { it.id != songId }

    /**
     * The picker's tap on [otherId]: the state while both sides are read, or null when the tap is
     * ignored — a read or a merge is already in flight, or it is the song the merge started from.
     */
    public fun choosing(otherId: String): MergeState? =
        if (loading || merging || otherId == songId) null else copy(loading = true, error = null)

    /**
     * Both sides read from the database at [generation]: the preview with R33-R35's defaults —
     * **the song the merge started from survives** until the user swaps — or, when either song is
     * no longer live, the picker with the reason.
     */
    public fun read(survivor: SongMerge.Side?, loser: SongMerge.Side?, generation: Long): MergeState =
        if (survivor == null || loser == null) {
            copy(loading = false, error = Messages.SONG_NOT_FOUND)
        } else {
            copy(loading = false, plan = MergePlan.of(survivor, loser), generation = generation)
        }

    /** A read or a merge that failed or was refused: nothing in flight, and the reason (E43). */
    public fun failed(error: String?): MergeState = copy(loading = false, merging = false, error = error)

    /** [change] applied to the plan, when there is one and nothing is in flight. */
    public fun withPlan(change: (MergePlan) -> MergePlan): MergeState {
        val open = plan ?: return this
        if (merging) return this
        return copy(plan = change(open), error = null)
    }

    /**
     * E24's overlays ruling: back closes the preview to the picker, then closes the picker (null).
     * Nothing moves while the merge is in flight — its result has to land somewhere.
     */
    public fun back(): MergeState? = when {
        merging -> this
        plan != null -> copy(plan = null, generation = null, error = null)
        else -> null
    }

    /**
     * The detail the overlay belongs to has closed (R38): the overlay closes with it (null) —
     * unless a merge is in flight, whose result still lands here.
     */
    public fun cancelled(): MergeState? = takeIf { merging }

    /** The confirmation's Merge (R36, R38): what to send, or why nothing is sent. */
    public fun confirm(): MergeConfirmation {
        val open = plan
        val readFrom = generation
        if (open == null || readFrom == null || merging) return MergeConfirmation.Ignored
        val request = open.request() ?: return MergeConfirmation.Invalid(copy(error = Messages.SAVE_INVALID))
        return MergeConfirmation.Start(copy(merging = true, error = null), request, readFrom)
    }
}

/** What [MergeState.confirm] decided. */
public sealed interface MergeConfirmation {
    /** Nothing to confirm: no preview is open, or a merge is already in flight. */
    public data object Ignored : MergeConfirmation

    /** The merged row does not validate (R14): nothing is sent, and [state] says so. */
    public data class Invalid(val state: MergeState) : MergeConfirmation

    /** Send [request] to the database at [generation] (F28 N4); [state] is the overlay meanwhile. */
    public data class Start(val state: MergeState, val request: SongMerge.Request, val generation: Long) : MergeConfirmation
}
