package dev.repertaurus.session

import dev.repertaurus.core.Ids
import dev.repertaurus.core.NearMatches
import dev.repertaurus.data.LookupTableKey
import dev.repertaurus.data.RepertaurusRepository

/**
 * Who does what on a song — the `song_performer` editor's shared core, editing E1-E11.
 *
 * **E1 is the rule this file exists under: recording a capability is not logging practice.**
 * Nothing here writes `practice_event` under any path. "Coralie can sing this" is a fact
 * about the repertoire, true whether or not anyone practised today, and the two are now one
 * long-press apart — which is exactly why it is stated first and pinned by a test.
 *
 * Blocking, like [SessionCoordinator] and [ViewCoordinator]; the caller keeps it off the main
 * thread.
 */
public class CapabilityCoordinator(
    private val repository: RepertaurusRepository,
) {

    /**
     * Every live capability row on one song, in [displayOrder] — performers who lead
     * somewhere first, then by performer name, then the chip row's instrument order
     * (decision 18).
     *
     * **E35: one comparator orders capability rows, and it is applied exactly here.** This
     * used to return the SQL `ORDER BY` as it stood — instruments alphabetically — while
     * [lineUp] re-sorted them into chip order, so the same rows read differently on two
     * surfaces. The SQL clause stays as a deterministic base for a stable scan; the display
     * rule is Kotlin's, applied once, and the grouped view inherits it.
     */
    public fun capabilities(songId: String): List<SongCapability> =
        repository.songPerformers(songId).map { row ->
            SongCapability(
                id = row.id,
                performerId = row.performerId,
                performerName = row.performerName,
                instrumentId = row.instrumentId,
                instrumentName = row.instrumentName,
                isLead = row.isLead == 1L,
                vocalRange = VocalRange.of(row.vocalRange),
                notes = row.notes,
            )
        }.let { rows -> rows.sortedWith(displayOrder(rows)) }

    /**
     * E10: the same rows **grouped by performer**, so "who plays this" reads as a line-up
     * rather than a flat list of pairs — a person holding three instruments is one entry with
     * three chips, not three entries.
     *
     * There is no sort here. [capabilities] has already applied [displayOrder] and `groupBy`
     * preserves encounter order, so the performers arrive lead-first and then by name and each
     * performer's instruments arrive in chip order. A second sort here is what made the flat
     * list and the grouped list disagree (E35).
     */
    public fun lineUp(songId: String): List<PerformerLineUp> =
        capabilities(songId)
            .groupBy { it.performerId }
            .map { (performerId, held) ->
                PerformerLineUp(
                    performerId = performerId,
                    performerName = held.first().performerName,
                    capabilities = held,
                )
            }

    // ---- The two type-aheads (E5) --------------------------------------------------------

    /** Every live performer, for the performer field. A few rows, so matched in memory. */
    public fun performers(): List<RepertaurusRepository.Performer> = repository.performers()

    /** Every live instrument, in chip order, for the instrument field. */
    public fun instruments(): List<InstrumentChip> =
        SessionInstruments.chips(repository.instruments())

    /**
     * Near-matches while typing (decisions 16, 17d), so `Ukelele` meets `Ukulele` **before** a
     * second row is committed. Those two normalise differently and would derive different
     * ids, so nothing downstream would ever merge them — the type-ahead is the only defence.
     *
     * **E36: the matcher takes the list it matches against.** These used to call [performers]
     * and [instruments], which go to disk, on every keystroke. The editor already holds both
     * lists — it renders them — and every other type-ahead in the app already matches in
     * memory over the list it holds. Either they take the list or they do not exist; there is
     * deliberately no third overload for a caller to reach for by accident.
     */
    public fun suggestPerformers(
        query: String,
        from: List<RepertaurusRepository.Performer>,
        limit: Int = 6,
    ): List<RepertaurusRepository.Performer> =
        NearMatches.search(query, from, limit) { it.name }

    public fun suggestInstruments(
        query: String,
        from: List<InstrumentChip>,
        limit: Int = 6,
    ): List<InstrumentChip> =
        NearMatches.search(query, from, limit) { it.name }

    // ---- Writing (E5, E6, E7, E9) --------------------------------------------------------

    /**
     * E5: **create on enter.** A performer or an instrument the user names and that does not
     * already exist is created rather than blocking the save, through the same
     * lookup path the manage screen uses (E13) — so a name that normalises to an existing row
     * resolves to that row rather than forking it.
     *
     * **E38: all three writes are one transaction.** Creating a capability from two typed
     * names performs three writes — the performer, the instrument, and the row that joins
     * them — and an add interrupted between them would otherwise leave a lookup row behind
     * that names nothing.
     *
     * **E33: the two tables are named by [LookupTableKey], never by a string literal.** This
     * method is where `"performer"` and `"instrument"` were spelled by hand, so a typo
     * compiled and threw on the editor's first create-on-enter.
     *
     * @return the capability row's id.
     */
    public fun add(songId: String, performerName: String, instrumentName: String): String =
        repository.inTransaction {
            val performerId = repository.lookups.add(LookupTableKey.PERFORMER, performerName)
            val instrumentId = repository.lookups.add(LookupTableKey.INSTRUMENT, instrumentName)
            addById(songId, performerId, instrumentId)
        }

    /**
     * The same, when both were picked out of the suggestions.
     *
     * **E6 lives one call down, in [RepertaurusRepository.addSongPerformer], and is the part
     * of this arc most likely to be got wrong.** The id is derived from the three keys, so
     * re-adding a pair that was removed computes the *same* primary key: a plain `INSERT`
     * throws and an `INSERT OR REPLACE` silently discards that row's `notes` and
     * `vocal_range`. Adding is therefore insert-or-revive, and it deliberately carries no
     * facts — [update] sets those afterwards, so a revive can never be the thing that quietly
     * resets them.
     */
    public fun addById(songId: String, performerId: String, instrumentId: String): String =
        repository.addSongPerformer(songId, performerId, instrumentId)

    /**
     * The three editable facts on a row: [SongCapability.isLead] (E4, a per-row toggle offered
     * on every instrument and defaulting off), [SongCapability.vocalRange] (E8) and the notes.
     *
     * E8 is enforced here rather than left to the screen: a non-null range on a guitar row is
     * nonsense, SQLite cannot `CHECK` it across a foreign table (V7), and a UI that offered it
     * anyway would write a permanent value no read would ever question. It fails loudly.
     */
    public fun update(capability: SongCapability) {
        require(capability.vocalRange == null || capability.rangeApplies) {
            "vocal_range is offered on vocal and backing vocal only (E8), " +
                "not on ${capability.instrumentName}"
        }
        repository.updateSongPerformer(
            id = capability.id,
            isLead = if (capability.isLead) 1L else 0L,
            vocalRange = capability.vocalRange?.stored,
            notes = capability.notes,
        )
    }

    /**
     * E7: a soft delete, like every other mutable row in this schema — a tombstone, never a
     * `DELETE`, because a stale device reinserts a hard-deleted row on the next merge. It is
     * also what makes E6's revive path possible at all.
     */
    public fun remove(capabilityId: String) {
        repository.removeSongPerformer(capabilityId)
    }

    public companion object {

        /**
         * **E35's one comparator.** Three keys, in this order:
         *
         * 1. whether this row's performer leads on *any* of their instruments (E4, E10) — so
         *    the line-up reads as a line-up and the lead heads it;
         * 2. the performer's name, which keeps every row a person holds together;
         * 3. decision 18's chip order over the instrument, so the editor and the session
         *    screen name the same five instruments in the same sequence.
         *
         * Lead-ness is a **performer-level** key and not a row-level one, which is why this
         * takes the rows it will order rather than being a constant. `is_lead` is per row
         * (E4) — Charlotte can lead on `keys` and not on `guitar` — and ranking rows by it
         * directly would split her two rows around Coralie's lead vocal, leaving `groupBy` to
         * reassemble a performer whose instruments were no longer in chip order. That is the
         * disagreement between the flat list and the grouped list that E35 exists to end.
         */
        internal fun displayOrder(rows: List<SongCapability>): Comparator<SongCapability> {
            val leading = rows.filter { it.isLead }.mapTo(mutableSetOf()) { it.performerId }
            return compareBy<SongCapability> { if (it.performerId in leading) 0 else 1 }
                .thenBy { it.performerName }
                .then(SessionInstruments.displayOrder { it.instrumentName })
        }
    }
}

/**
 * One resolved `song_performer` row for display (decisions 26, 27; views V1-V9).
 *
 * E3: one row per `(song, performer, instrument)`, which is exactly the unique key the schema
 * carries — so Coralie holds a `vocal` row and a `backing vocal` row on the same song with
 * neither shadowing the other.
 */
public data class SongCapability(
    val id: String,
    val performerId: String,
    val performerName: String,
    val instrumentId: String,
    val instrumentName: String,
    /**
     * E4, V6: **scoped to its instrument** — lead vocal on a `vocal` row, lead guitar on a
     * `guitar` row. `backing vocal` gets no special case; a featured backing vocalist is a
     * real thing and the pair `(instrument, is_lead)` already expresses it.
     */
    val isLead: Boolean = false,
    val vocalRange: VocalRange? = null,
    val notes: String? = null,
) {

    /** E8: whether a range is meaningful on this row at all. */
    public val rangeApplies: Boolean
        get() = VocalRanges.appliesTo(instrumentId)

    /**
     * The instrument, spelled for a human, through [titleCase] — **the core's helper, not a
     * private copy of it** (E46). The sheet held a character-for-character duplicate.
     */
    public val instrumentLabel: String
        get() = titleCase(instrumentName)

    /**
     * **E46: the chip sentence.** The instrument, plus whatever this row actually asserts
     * beyond it: `Backing Vocal · lead · high`.
     *
     * `is_lead` is scoped to its instrument (E4, V6), so "lead" reads against the chip it sits
     * on rather than against the person — which is the whole reason the words are assembled
     * here, once, rather than in each UI that draws a chip.
     *
     * The joiner is [ViewSummary.SEPARATOR], the one the View summary already owns. A fresh
     * `·` literal beside it is how two lines on the same screen come to be punctuated
     * differently.
     */
    public val label: String
        get() = buildString {
            append(instrumentLabel)
            if (isLead) append(ViewSummary.SEPARATOR).append(LEAD)
            vocalRange?.let { append(ViewSummary.SEPARATOR).append(it.name.lowercase()) }
        }

    /** The row named in full, for the editor that opens on it: `Coralie · Backing Vocal`. */
    public val heading: String
        get() = performerName + ViewSummary.SEPARATOR + instrumentLabel

    /** The lead toggle's own words, so the dialog does not re-assemble them (E4). */
    public val leadLabel: String
        get() = "Leads on $instrumentLabel"

    public companion object {
        /** E4's word, once. */
        public const val LEAD: String = "lead"
    }
}

/**
 * E10: one performer's whole contribution to one song, so the editor lists a line-up rather
 * than a flat list of pairs.
 */
public data class PerformerLineUp(
    val performerId: String,
    val performerName: String,
    val capabilities: List<SongCapability>,
) {
    /** True when this person leads on at least one of their instruments (E4). */
    public val leads: Boolean
        get() = capabilities.any { it.isLead }

    /**
     * **E46: the performer heading.** `Coralie · leads`, or just the name when they do not.
     *
     * Same rule, same separator, same core as [SongCapability.label] — the sheet invented all
     * three separately, each with its own `·`.
     */
    public val heading: String
        get() = if (leads) performerName + ViewSummary.SEPARATOR + LEADS else performerName

    public companion object {
        /** Performer-level, unlike [SongCapability.LEAD], which is scoped to one instrument. */
        public const val LEADS: String = "leads"
    }
}

/**
 * `song_performer.vocal_range` (decision 27, V7): a closed two-value vocabulary, stored as an
 * `INTEGER` under a `CHECK`, mapping the workbook's `H`/`L` to `1`/`0`. Free text would drift
 * into `H`, `h`, `high` and `H/L`, which is precisely what happened in the workbook.
 */
public enum class VocalRange(public val stored: Long) {
    LOW(0L),
    HIGH(1L),
    ;

    /** The range spelled for a human — `High`, `Low` — through the core's one helper (E46). */
    public val label: String
        get() = titleCase(name.lowercase())

    public companion object {
        /** The stored integer, or null — which is what every non-vocal row carries (E8). */
        public fun of(stored: Long?): VocalRange? = entries.firstOrNull { it.stored == stored }
    }
}

/**
 * E8: **`vocal_range` is offered only on `vocal` and `backing vocal` rows.** Range is
 * meaningful only for a voice.
 *
 * Matched on the **derived id**, not the display name. An id is immutable and a rename never
 * re-derives it (decision 5, E21), so renaming `vocal` to `Lead Voice` keeps the range field
 * where it belongs — whereas a name match would silently stop offering it. A user who adds an
 * instrument called `vocal` derives that same id and gets the same row, which is decision 2
 * working rather than a coincidence.
 */
public object VocalRanges {

    /**
     * `UUIDv5(namespace(instrument), normalise(name))` for the two seeded voices — the exact
     * ids `instrument.sq` seeds. Derived here rather than pasted so the two cannot drift, and
     * a test pins them against the literals in the seed.
     */
    public val INSTRUMENT_IDS: Set<String> = setOf(
        Ids.derived("instrument", "vocal"),
        Ids.derived("instrument", "backing vocal"),
    )

    public fun appliesTo(instrumentId: String): Boolean = instrumentId in INSTRUMENT_IDS
}
