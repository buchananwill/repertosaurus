package dev.repertosaurus.data

import dev.repertosaurus.core.Timestamps
import dev.repertosaurus.db.RepertosaurusDatabase
import kotlinx.datetime.Clock

/**
 * How an add resolved against a **derived** id (decisions 2-4). Because the id is computed from
 * the row's canonical key, asking for a row that already exists lands on that row, so there
 * are exactly three outcomes and only one of them is an insert.
 */
public enum class Resolution {
    /** No row held the id; one was inserted. */
    CREATED,

    /** A tombstone held the id and was untombstoned, keeping everything it carried (E6, R21). */
    REVIVED,

    /** A live row held the id and was left exactly as it was. Nothing was written. */
    EXISTING,
}

/**
 * What a write to one of a song's junction rows did — `song_tag`, `song_instrument` or
 * `song_performer` (repertoire-editing R23c). Every such write can be a no-op, and the caller
 * has to be able to tell, so a form clears only on real success (E44).
 */
public sealed interface JunctionWrite {
    /**
     * The row the write targeted, whatever happened: **the stored row's id when a row holds the
     * key** — which, on a database upgraded 1 → 2, is a two-key `song_performer` id (R4a,
     * schema-compatibility S3) — and the derived id otherwise.
     */
    public val id: String

    /**
     * Whether anything was written (R23d) — **including a parent lookup the write revived**
     * (R23b). A row that was already live under a tombstoned tag still wrote: the tag came back.
     */
    public val wrote: Boolean

    /**
     * An add: [resolution] says whether it inserted, revived, or found the row already live;
     * [revivedParent] says whether a tombstoned parent lookup was brought back (R23b).
     */
    public data class Added(
        override val id: String,
        val resolution: Resolution,
        val revivedParent: Boolean = false,
    ) : JunctionWrite {
        override val wrote: Boolean get() = resolution != Resolution.EXISTING || revivedParent
    }

    /** A remove: [wrote] is false when the row was not live (R23d) — absent or already removed. */
    public data class Removed(override val id: String, override val wrote: Boolean) : JunctionWrite

    /** R23c: the song is removed, so nothing was written to its child row. */
    public data class SongGone(override val id: String) : JunctionWrite {
        override val wrote: Boolean get() = false
    }
}

/**
 * **The one "differs" rule** (R23, R23a; style review F14 N7). An add or a type-ahead that
 * resolved to an existing row differs when that row's **current** name is not the name that was
 * asked for — typed, or picked and shown in the field. Exact over the trimmed text, so *jolene*
 * landing on *Jolene* is said too (package 1 ruling: informative, not noise).
 *
 * A row this write created carries exactly the name asked for, so it never differs; the rule
 * needs no special case for [Resolution.CREATED].
 */
public fun namesDiffer(current: String, asked: String): Boolean = current != asked.trim()

/**
 * **The insert-or-revive path, once** (style review B3). Every derived-id add in the data layer
 * reads by id and branches three ways — insert, revive a tombstone, or leave a live row alone —
 * and before this there were six copies of that branch. The rules are unchanged; only the
 * branch moved here.
 *
 * **E38: the read and the branch are one transaction**, so two rapid taps cannot both observe
 * "absent". The callers usually hold an outer transaction as well; SQLDelight nests them.
 *
 * [insert] says which INSERT the site uses — see [Insert]. That choice is the site's, and it is
 * stated once, at the call, as the argument's type.
 */
internal fun <R : Any> RepertosaurusDatabase.insertOrRevive(
    read: () -> R?,
    deletedAt: (R) -> String?,
    insert: Insert,
    revive: (R) -> Unit,
): Resolution = transactionWithResult {
    val existing = read()
    when {
        existing == null -> {
            insert.statement()
            // R4a: an OR IGNORE that wrote nothing is a failure, never CREATED. The count is
            // SQLite's changes() for the statement just run, in the same transaction ([wrote]'s
            // contract: the insert is one unconditional DML statement).
            if (insert is Insert.OrIgnore && changesQueries.rowsChanged().executeAsOne() == 0L) {
                throw InsertIgnored()
            }
            Resolution.CREATED
        }
        deletedAt(existing) != null -> {
            revive(existing)
            Resolution.REVIVED
        }
        else -> Resolution.EXISTING
    }
}

/**
 * **R23b's revive, once** (style review F14 N7). A write that makes a live row reference a
 * tombstoned parent brings the parent back, in the caller's transaction, keeping everything the
 * parent carried. A live parent, or an id with no row at all, is left alone.
 *
 * Lookups ([LookupTables.reviveIfRemoved]) and artists (`SongCatalog`) both come here; only the
 * read and the restore statement differ, and they are the arguments.
 *
 * @return true when a tombstone was revived — which counts as having written (R23d).
 */
internal fun <R : Any> reviveIfRemoved(row: R?, deletedAt: (R) -> String?, revive: (R) -> Unit): Boolean {
    if (row == null || deletedAt(row) == null) return false
    revive(row)
    return true
}

/**
 * The INSERT an [insertOrRevive] site uses. Two, and the difference is stated here once rather
 * than at every site:
 *
 * - [Plain] — a plain `INSERT`. **The transaction is the whole guard.** Every form-driven add
 *   uses this: a lookup, an artist, a song, a tag or `song_instrument` row. An `OR IGNORE`
 *   would additionally swallow a `CHECK` failure, for no gain on a path that is not a
 *   double-tap target.
 * - [OrIgnore] — `INSERT OR IGNORE`, the second half of E38's guard for the one derived-id
 *   insert that sits on a double-tap path: `song_performer.insertIfAbsent`. The loser of a race
 *   the transaction did not serialise writes nothing, where `OR REPLACE` would discard the
 *   winner's notes and range (E6). **R4a: writing nothing is then reported as [InsertIgnored],
 *   never as [Resolution.CREATED]** — [insertOrRevive] checks `changes()`. The accepted cost of
 *   the clause — it swallows a `CHECK` failure — is documented on that statement, whose values
 *   are all literals or `Timestamps.now`; R4a turns that swallow into a loud failure too.
 *
 * The clause itself lives in the `.sq` statement [statement] calls; this type is how the site
 * says which one it chose. (Named `statement`, not `run`, so it does not read as the stdlib
 * scope function — style review F14 N2.)
 */
internal sealed class Insert(val statement: () -> Unit) {
    class Plain(statement: () -> Unit) : Insert(statement)
    class OrIgnore(statement: () -> Unit) : Insert(statement)
}

/**
 * **R4a: an [Insert.OrIgnore] that wrote nothing.** [insertOrRevive] read no row for the key and
 * the insert was still ignored — a unique-key hit the read did not see (the pre-R4a derived-id
 * lookup on a two-key database was exactly this), or a swallowed `CHECK`. Thrown rather than
 * returned, so the enclosing transaction rolls back (a parent revived on the way is undone too)
 * and no caller can mistake it for [Resolution.CREATED]. Merge (R38a) treats it as the failure
 * it is; the routes report it on the error channel (S8).
 */
public class InsertIgnored : IllegalStateException(
    "The row was not written: the database already holds one under that key.",
)

/**
 * Run one guarded `UPDATE` and report whether it changed a row — the E37 and R23d guards
 * (`deleted_at IS NULL` in the `WHERE`) make "nothing" a real outcome, and R23c requires the
 * caller be told. The statement and the count are one transaction, so nothing else on the
 * connection can land between them.
 *
 * **The contract (safety review F15 N1): [statement] runs exactly one DML statement, and runs it
 * unconditionally.** The answer is SQLite's `changes()`, which reports the row count of the
 * **most recent** `INSERT`, `UPDATE` or `DELETE` on the connection — not of this lambda. So:
 *
 * - a lambda that runs two statements reports only the second;
 * - a lambda that skips its statement on some branch reports whatever the connection ran last,
 *   which can be a different write entirely and reads as "wrote".
 *
 * Every caller passes a single generated query call with no branch around it. A caller that
 * needs a condition decides it **before** calling this, and does not call it at all on the
 * branch that writes nothing.
 */
internal fun RepertosaurusDatabase.wrote(statement: () -> Unit): Boolean =
    transactionWithResult {
        statement()
        changesQueries.rowsChanged().executeAsOne() > 0L
    }

/**
 * A soft delete (decision 9, E7) — the boilerplate once (style review N6): one timestamp for
 * both `deleted_at` and `updated_at`, and the R23d report of whether a live row was actually
 * removed. [statement] receives the timestamp and binds it twice, and is held to [wrote]'s
 * contract: one guarded `softDelete` statement, run unconditionally.
 */
internal fun RepertosaurusDatabase.softDelete(clock: Clock, statement: (now: String) -> Unit): Boolean {
    val now = Timestamps.now(clock)
    return wrote { statement(now) }
}

/**
 * R23c's test: a song that exists and is not tombstoned. The one definition, shared by the
 * catalog, the junction-row envelope ([SongChildren]) and the capability coordinator.
 */
internal fun RepertosaurusDatabase.songIsLive(songId: String): Boolean {
    val row = songQueries.selectById(songId).executeAsOneOrNull() ?: return false
    return row.deleted_at == null
}
