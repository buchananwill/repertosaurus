package dev.repertaurus.session

import dev.repertaurus.core.NearMatches
import dev.repertaurus.data.RepertaurusRepository

/**
 * Managing a lookup table — decisions 15 and 16.
 *
 * `instrument`, `tag`, `groove`, `venue`, `band`, `practice_context` and `performer` are
 * tables, not enums, precisely so the user can extend them without a code change or a
 * migration. They are also the *same shape*: an id derived from the normalised name, a
 * display name, a type-ahead that creates on enter, and a soft delete.
 *
 * So this is one abstraction with one implementation wired. [LookupKind] gains an entry and
 * [LookupStores] gains a branch when the next one is wanted; nothing above this line
 * changes, and the manage screen does not know which kind it is showing.
 *
 * **Only `instrument` is wired today.** Practice context in particular is a real gap — the
 * app cannot set one at all, though decision 45a expects it — but that is a later dispatch.
 */
public data class LookupItem(
    val id: String,
    val name: String,
    /** Live practice events pointing at this row: what a soft delete would hide. */
    val usageCount: Long,
)

/** The lookup tables that can be managed. One wired; the rest are a one-line addition. */
public enum class LookupKind(
    public val plural: String,
    public val singular: String,
    public val table: String,
) {
    INSTRUMENT("Instruments", "instrument", "instrument"),
    ;
}

/**
 * Read and write one lookup table. Blocking, like [SessionCoordinator] — the caller keeps
 * it off the main thread.
 */
public interface LookupStore {

    public val kind: LookupKind

    /** Live rows, in the order the screen should show them. */
    public fun items(): List<LookupItem>

    /** Near-matches while typing (decision 16), so a duplicate is seen before it is made. */
    public fun suggest(query: String, limit: Int = 6): List<LookupItem>

    /**
     * Create on enter. The id is derived from the normalised name (decisions 2, 4), so two
     * devices adding `mandolin` independently converge on one row.
     *
     * @return the id, which is an existing row's whenever the name normalises to one.
     */
    public fun add(name: String): String

    /**
     * Rename. The id is opaque and immutable (decision 5) and is **not** re-derived: every
     * practice event points at it, and re-deriving would orphan the lot.
     */
    public fun rename(id: String, name: String)

    /**
     * Remove. A **soft delete** (decision 9) — a tombstone, never a `DELETE`. A stale
     * device would reinsert a hard-deleted row on the next merge, and the practice events
     * referencing it must survive regardless.
     */
    public fun remove(id: String)
}

/** The `instrument` table, the one lookup wired for management today. */
public class InstrumentStore(
    private val repository: RepertaurusRepository,
) : LookupStore {

    override val kind: LookupKind = LookupKind.INSTRUMENT

    /**
     * Ordered by the chip row's rule (decision 18: the seeded five in spec order, then
     * anything the user added, alphabetically) so the manage screen and the chips agree.
     * The ordering is reused from [SessionInstruments], not restated here.
     *
     * One count query per row. The list is five rows today and a dozen at worst, each
     * count an indexed lookup, so a join to save a handful of queries would buy nothing.
     */
    override fun items(): List<LookupItem> =
        SessionInstruments.chips(repository.instruments()).map { chip ->
            LookupItem(
                id = chip.id,
                name = chip.name,
                usageCount = repository.practiceEventsOn(chip.id),
            )
        }

    override fun suggest(query: String, limit: Int): List<LookupItem> =
        NearMatches.search(query, items(), limit) { it.name }

    override fun add(name: String): String = repository.addInstrument(name)

    override fun rename(id: String, name: String) {
        repository.renameInstrument(id, name)
    }

    override fun remove(id: String) {
        repository.removeInstrument(id)
    }
}

/** Where a [LookupKind] becomes a [LookupStore]. Adding a kind adds a branch here. */
public object LookupStores {
    public fun of(kind: LookupKind, repository: RepertaurusRepository): LookupStore = when (kind) {
        LookupKind.INSTRUMENT -> InstrumentStore(repository)
    }
}
