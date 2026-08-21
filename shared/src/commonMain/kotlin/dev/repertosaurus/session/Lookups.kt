package dev.repertosaurus.session

import dev.repertosaurus.core.NearMatches
import dev.repertosaurus.data.LookupRow
import dev.repertosaurus.data.LookupTableKey
import dev.repertosaurus.data.RepertosaurusRepository

/**
 * Managing a lookup table — decisions 15 and 16, editing E13 and E18.
 *
 * `instrument`, `tag`, `groove`, `venue`, `band`, `practice_context` and `performer` are
 * tables, not enums, precisely so the user can extend them without a code change or a
 * migration. They are also the *same shape*: an id derived from the normalised name, a
 * display name, a type-ahead that creates on enter, and a soft delete.
 *
 * So this is one abstraction, and **E18 wires all seven kinds through it**. `performer` is
 * managed here rather than on a bespoke screen (E13) for exactly that reason: a second screen
 * doing the same job in a different way is how two implementations drift.
 *
 * E19 is worth stating where the practice-context entry is defined rather than only in the
 * spec: **wiring that lookup manages the vocabulary and nothing else.** The app still cannot
 * attach a context to a logged event; that is a change on the logging path. The drawer entry
 * is not the feature.
 */
public data class LookupItem(
    val id: String,
    val name: String,
    /**
     * E17: **the live rows that removing this one would hide**, as this kind defines it —
     * practice events for `instrument`, capability rows for `performer`, tagged songs for
     * `tag`, songs for `groove`, set lists for `venue` and `band`, logged events for
     * `practice_context`. E22: the screen states it before the removal happens.
     *
     * It used to mean practice events specifically. That stopped being true the moment a
     * second kind was wired, and the comment saying so is retired.
     */
    val usageCount: Long,
    /**
     * E14: a second line the store supplies, so the shared screen can answer "what do they
     * play" without knowing what a performer is. Null for every kind that has nothing to add.
     */
    val subtitle: String? = null,
    /** E16: the notes column, for the kinds that have one. Null where [LookupKind.hasNotes] is false. */
    val notes: String? = null,
)

/**
 * The lookup tables that can be managed. **All seven are wired** (E18).
 *
 * The declaration order is the drawer's order (E26) — the two the user reaches for most
 * first, then the rest.
 *
 * [hasNotes] is E16: the shared screen offers the notes field where the kind declares one and
 * hides it elsewhere, rather than special-casing performer. It is the same fact the data layer
 * states as a non-null `LookupTable.setNotes`, and a test pins the two together so a kind
 * cannot claim a field its table does not have.
 *
 * **E33: [table] is a `LookupTableKey`, not a raw `String`.** It used to be the table name
 * spelled out here and then spelled out again by hand in the capability coordinator, where a
 * typo compiled and threw on the editor's first create-on-enter. The key is `internal` because
 * it belongs to `dev.repertosaurus.data` and the layering is `session → data → core`; nothing in
 * `androidApp` needs it, because the screens talk to a [LookupStore].
 */
public enum class LookupKind(
    public val plural: String,
    public val singular: String,
    internal val table: LookupTableKey,
    public val hasNotes: Boolean,
) {
    INSTRUMENT("Instruments", "instrument", LookupTableKey.INSTRUMENT, hasNotes = false),
    PERFORMER("Performers", "performer", LookupTableKey.PERFORMER, hasNotes = true),
    TAG("Tags", "tag", LookupTableKey.TAG, hasNotes = false),
    GROOVE("Grooves", "groove", LookupTableKey.GROOVE, hasNotes = false),

    /**
     * E16 states that `venue` carries a notes column. **It does not** — `venue.sq` is `id`,
     * `name` and the standard three, and data-model decision 50 never gave it one, unlike
     * 49a for `band` and 24 for `performer`. Adding the column is a schema change under
     * schema-compatibility S1 (a version bump plus a migration) and is deliberately out of
     * scope for the shared-core half of this arc, so the flag states the schema as it is.
     * Flip it here and in `LookupTables.kt` together, with a migration, when the column lands.
     */
    VENUE("Venues", "venue", LookupTableKey.VENUE, hasNotes = false),
    BAND("Bands", "band", LookupTableKey.BAND, hasNotes = true),
    PRACTICE_CONTEXT(
        "Practice contexts",
        "practice context",
        LookupTableKey.PRACTICE_CONTEXT,
        hasNotes = false,
    ),
    ;

    /**
     * **E17 and E46: what [LookupItem.usageCount] counts, in this kind's own words** — "3
     * practices logged", "1 recorded part", "12 songs tagged".
     *
     * The `when` is exhaustive on purpose: a new kind is a **compile error here** rather than a
     * row that quietly claims to count something it does not. The old wording said practice
     * events for everything, which stopped being true the moment a second kind was wired.
     *
     * This is seven branches of per-kind wording with singular/plural handling. It was written
     * as a private function in a composable, where it is neither JVM-testable nor reachable
     * from the desktop UI that needs all seven of them verbatim.
     */
    public fun usagePhrase(count: Long): String {
        val (one, many) = when (this) {
            INSTRUMENT -> "1 practice logged" to "$count practices logged"
            PERFORMER -> "1 recorded part" to "$count recorded parts"
            TAG -> "1 song tagged" to "$count songs tagged"
            GROOVE -> "1 song" to "$count songs"
            VENUE -> "1 set list" to "$count set lists"
            BAND -> "1 set list" to "$count set lists"
            PRACTICE_CONTEXT -> "1 event logged" to "$count events logged"
        }
        return if (count == 1L) one else many
    }

    /** `an instrument`, `a tag` — [singular] with the article English wants in front of it. */
    public val withArticle: String
        get() = "${indefiniteArticle(singular)} $singular"
}

/**
 * English's indefinite article, which is a **rule and not a screen detail** (E46): it depends
 * on the word, and every screen that composes a sentence around a [LookupKind.singular] needs
 * the same answer. It was a private function in a composable.
 *
 * Sound, not spelling, is what English actually keys on — *an hour*, *a unicorn* — but no
 * lookup singular in this app is one of those, and a pronunciation table for seven fixed words
 * would be more machinery than the sentence is worth. The vowel test is stated as the
 * approximation it is, so a later kind whose name breaks it is a known thing to check rather
 * than a surprise.
 */
public fun indefiniteArticle(word: String): String =
    if (word.firstOrNull()?.lowercaseChar() in setOf('a', 'e', 'i', 'o', 'u')) "an" else "a"

/**
 * Read and write one lookup table. Blocking, like [SessionCoordinator] — the caller keeps
 * it off the main thread.
 */
public interface LookupStore {

    public val kind: LookupKind

    /** Live rows, in the order the screen should show them. */
    public fun items(): List<LookupItem>

    /**
     * Near-matches while typing (decision 16), so a duplicate is seen before it is made.
     *
     * **E36: the matcher takes the list it matches against.** It used to call [items], which
     * is 1 + N queries — plus, for `PERFORMER`, a full `song_performer` scan and group-by —
     * **per keystroke**. Every screen in the app already holds the list it is matching over
     * and every other type-ahead already matches in memory; [from] is that list.
     */
    public fun suggest(query: String, from: List<LookupItem>, limit: Int = 6): List<LookupItem>

    /**
     * Create on enter. The id is derived from the normalised name (decisions 2, 4), so two
     * devices adding `mandolin` independently converge on one row.
     *
     * @return the id, which is an existing row's whenever the name normalises to one.
     */
    public fun add(name: String): String

    /**
     * Rename. The id is opaque and immutable (decision 5, E21) and is **not** re-derived:
     * every referencing row points at it, and re-deriving would orphan the lot.
     */
    public fun rename(id: String, name: String)

    /**
     * E16: set the notes on a row. Only defined where [LookupKind.hasNotes] is true; calling
     * it elsewhere is a programming error and fails loudly rather than writing nothing and
     * looking like it worked. A blank string clears the field.
     */
    public fun setNotes(id: String, notes: String?)

    /**
     * Remove. A **soft delete** (decision 9, E22) — a tombstone, never a `DELETE`. A stale
     * device would reinsert a hard-deleted row on the next merge, and the history
     * referencing it must survive regardless.
     */
    public fun remove(id: String)
}

/**
 * The one implementation, over [RepertosaurusRepository]'s table-keyed lookup surface.
 *
 * Everything a kind does differently is a constructor parameter rather than a subclass:
 * [order] is the display order the screen shows rows in and [subtitles] is E14's second line.
 * Both default to "nothing special", which is what five of the seven kinds want.
 */
public class TableLookupStore(
    override val kind: LookupKind,
    private val repository: RepertosaurusRepository,
    private val order: (List<LookupRow>) -> List<LookupRow> = { it },
    private val subtitles: () -> Map<String, String> = { emptyMap() },
) : LookupStore {

    /**
     * One usage count per row. Each is an indexed count over a handful of rows, and these
     * lists are five to a few dozen entries, so a join to save a handful of queries would buy
     * nothing and would need a different shape per kind.
     */
    override fun items(): List<LookupItem> {
        val second = subtitles()
        return order(repository.lookups.rows(kind.table)).map { row ->
            LookupItem(
                id = row.id,
                name = row.name,
                usageCount = repository.lookups.usage(kind.table, row.id),
                subtitle = second[row.id],
                notes = row.notes,
            )
        }
    }

    override fun suggest(query: String, from: List<LookupItem>, limit: Int): List<LookupItem> =
        NearMatches.search(query, from, limit) { it.name }

    override fun add(name: String): String = repository.lookups.add(kind.table, name)

    override fun rename(id: String, name: String) {
        repository.lookups.rename(kind.table, id, name)
    }

    override fun setNotes(id: String, notes: String?) {
        require(kind.hasNotes) { "${kind.singular} has no notes field (E16)" }
        repository.lookups.setNotes(kind.table, id, notes)
    }

    override fun remove(id: String) {
        repository.lookups.remove(kind.table, id)
    }
}

/** Where a [LookupKind] becomes a [LookupStore]. Adding a kind adds a branch here. */
public object LookupStores {

    public fun of(kind: LookupKind, repository: RepertosaurusRepository): LookupStore = when (kind) {
        // Ordered by the chip row's rule (decision 18: the seeded five in spec order, then
        // anything the user added, alphabetically) so the manage screen and the chips agree.
        // The ordering is reused from SessionInstruments, not restated here.
        LookupKind.INSTRUMENT -> TableLookupStore(
            kind = kind,
            repository = repository,
            order = { rows -> rows.sortedWith(SessionInstruments.displayOrder { it.name }) },
        )

        // E14 and E15: the subtitle is the instruments this person is recorded on, derived
        // from song_performer and never stored. Shown in the chip row's order so the roster
        // and the chips read the same way, and one query for the whole roster rather than one
        // per performer.
        LookupKind.PERFORMER -> TableLookupStore(
            kind = kind,
            repository = repository,
            subtitles = { performerSubtitles(repository) },
        )

        LookupKind.TAG,
        LookupKind.GROOVE,
        LookupKind.VENUE,
        LookupKind.BAND,
        LookupKind.PRACTICE_CONTEXT,
        -> TableLookupStore(kind, repository)
    }

    /**
     * E15's accepted gap, stated where it happens: a performer with no capability rows is
     * absent from [RepertosaurusRepository.performerInstruments] and therefore has **no
     * subtitle at all** rather than an empty one. The roster still lists them.
     */
    private fun performerSubtitles(repository: RepertosaurusRepository): Map<String, String> =
        repository.performerInstruments().mapValues { (_, instruments) ->
            instruments
                .sortedWith(SessionInstruments.displayOrder { it.name })
                .joinToString(", ") { it.name }
        }
}
