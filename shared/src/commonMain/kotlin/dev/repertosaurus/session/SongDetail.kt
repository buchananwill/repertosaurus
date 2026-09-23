package dev.repertosaurus.session

import dev.repertosaurus.data.RepertosaurusRepository
import dev.repertosaurus.data.RepertosaurusRepository.PracticeSummary
import dev.repertosaurus.data.SongCatalog

/**
 * The Songs route's state (R11-R23) — in the core (style review F17 B1), so R14's "unsaved
 * changes" and the detail's ordering are JVM-tested and the desktop UI renders the same value.
 * The ViewModel, its lock and its scope stay in `androidApp` (E29).
 */
public data class SongsState(
    /** Every live song, **unordered** — the screen orders and filters with `SongSearch.search`. */
    val songs: List<SongCatalog.SongListEntry> = emptyList(),
    val query: String = "",
    val loading: Boolean = false,
    /** E36: the type-aheads' lists, held in memory and matched without a query per keystroke. */
    val artists: List<RepertosaurusRepository.Artist> = emptyList(),
    val grooves: List<LookupItem> = emptyList(),
    val tags: List<LookupItem> = emptyList(),
    val instruments: List<InstrumentChip> = emptyList(),
    val detail: SongDetail? = null,
    /** An add is in flight; the sheet's button is dead. */
    val adding: Boolean = false,
    /** E44: adds this screen has seen **land**. The add sheet closes when this changes. */
    val addsCommitted: Long = 0L,
    /** A one-shot request to open or close the detail, which is the screen's state (R27). */
    val detailRequest: DetailRequest? = null,
    /** E43: a confirmation, and only ever a confirmation. */
    val message: String? = null,
    /** E43: a refusal or failure, rendered in the error colour. */
    val error: String? = null,
) {
    /** [change] applied to the detail only if it is still open on [songId]. */
    public fun withDetail(songId: String, change: (SongDetail) -> SongDetail): SongsState {
        val open = detail?.takeIf { it.songId == songId } ?: return this
        return copy(detail = change(open))
    }
}

/** A one-shot navigation request from the ViewModel to the screen's drill-down state. */
public sealed interface DetailRequest {
    public data class Open(val songId: String) : DetailRequest
    public data object Close : DetailRequest
}

/**
 * The user's in-progress edit of one `song_instrument` row (R19) — the text in its fields and
 * the picked difficulty. Held in [SongDetail] rather than in the composable, so R14's discard
 * prompt can see it (session 09, F16 #6).
 */
public data class InstrumentEdit(val difficulty: Long?, val patch: String, val notes: String) {
    public companion object {
        /** The stored row as an edit — its "unchanged" state. */
        public fun of(row: SongCatalog.SongInstrument): InstrumentEdit =
            InstrumentEdit(row.difficulty, row.patch.orEmpty(), row.notes.orEmpty())
    }
}

/** One song's detail: the stored row, the draft being edited, and the song's children. */
public data class SongDetail(
    val songId: String,
    /** Null while the first read is in flight. */
    val record: SongCatalog.SongRecord? = null,
    val draft: SongDraft = SongDraft(),
    val tags: List<SongCatalog.SongTag> = emptyList(),
    val instruments: List<SongCatalog.SongInstrument> = emptyList(),
    val practice: List<PracticeSummary> = emptyList(),
    val lineUp: List<PerformerLineUp> = emptyList(),
    /**
     * Edits to `song_instrument` rows not yet saved, by row id. Only rows whose edit differs from
     * the stored row are kept.
     */
    val instrumentEdits: Map<String, InstrumentEdit> = emptyMap(),
    /** A write or a read is in flight; every control is dead. */
    val busy: Boolean = false,
    /** A Save of the song's own row is in flight (F18 N4): Close waits for it. */
    val saving: Boolean = false,
) {
    /**
     * R14: unsaved changes — the draft against the stored row, by `SongDraft`'s own equality,
     * **or any `song_instrument` row edited and not saved** (F16 #6).
     */
    val dirty: Boolean get() = draftDirty || instrumentEdits.isNotEmpty()

    /** The song's own row has unsaved changes; this, and only this, is what Save writes. */
    val draftDirty: Boolean get() = record != null && draft != SongDraft.from(record)

    /**
     * R38: whether "Merge with…" may start — the song is read, nothing is in flight, and nothing
     * is unsaved. The merge re-reads the song, so an unsaved draft would be dropped without the
     * discard prompt ever asking (3b ruling #9). The button and the ViewModel's guard both read
     * this one rule (style review F27 B2).
     */
    val canMerge: Boolean get() = record != null && !busy && !dirty

    /** The edit shown for [row]: the user's, or the stored values when there is none. */
    public fun instrumentEdit(row: SongCatalog.SongInstrument): InstrumentEdit =
        instrumentEdits[row.id] ?: InstrumentEdit.of(row)

    /** Record an edit to [row]; an edit back to the stored values is no edit at all. */
    public fun withInstrumentEdit(row: SongCatalog.SongInstrument, edit: InstrumentEdit): SongDetail =
        copy(
            instrumentEdits = if (edit == InstrumentEdit.of(row)) {
                instrumentEdits - row.id
            } else {
                instrumentEdits + (row.id to edit)
            },
        )

    /**
     * A fresh read of the children. The record and the draft are never touched — only a save
     * replaces those. An instrument edit survives only while its row is still there **and
     * unchanged underneath it**; a write that changed the stored row resets the fields to what is
     * stored, as the fields keyed on the row did before the edit was hoisted.
     */
    public fun withChildren(read: SongDetailRead): SongDetail {
        val stored = instruments.associateBy { it.id }
        val keep = read.instruments.filter { stored[it.id] == it }.mapTo(mutableSetOf()) { it.id }
        return copy(
            tags = read.tags,
            instruments = read.instruments,
            practice = read.practice,
            lineUp = read.lineUp,
            instrumentEdits = instrumentEdits.filterKeys { it in keep },
        )
    }

    /** A save's read-back: the record, the draft reset to it, and the children. */
    public fun saved(read: SongDetailRead): SongDetail {
        val record = read.record
        return withChildren(read).copy(
            record = record,
            draft = record?.let { SongDraft.from(it) } ?: draft,
        )
    }
}

/**
 * Everything the detail shows, read in one go, **in display order** — the detail's ordering
 * lives here rather than in a ViewModel (style review F17 B1): decision 18's chip order over the
 * `song_instrument` rows (R19) and the practice summary (R20).
 *
 * [record] is null when the song is gone.
 */
public class SongDetailRead(
    public val record: SongCatalog.SongRecord?,
    public val tags: List<SongCatalog.SongTag>,
    public val instruments: List<SongCatalog.SongInstrument>,
    public val practice: List<PracticeSummary>,
    public val lineUp: List<PerformerLineUp>,
) {
    public companion object {
        /** Read one song's detail. Blocking; the caller keeps it off the main thread. */
        public fun of(repository: RepertosaurusRepository, songId: String): SongDetailRead {
            val catalog = repository.catalog
            return SongDetailRead(
                record = catalog.song(songId),
                tags = catalog.songTags(songId),
                instruments = catalog.songInstruments(songId)
                    .sortedWith(SessionInstruments.displayOrder { it.instrumentName }),
                practice = repository.practiceSummary(songId)
                    .sortedWith(SessionInstruments.displayOrder { it.instrumentName.orEmpty() }),
                lineUp = CapabilityCoordinator(repository).lineUp(songId),
            )
        }
    }
}
