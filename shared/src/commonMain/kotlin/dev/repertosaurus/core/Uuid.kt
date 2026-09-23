package dev.repertosaurus.core

import kotlin.random.Random

/**
 * UUID generation for the identity scheme of decisions 1-6.
 *
 * Derived ids are `UUIDv5(namespace, canonical_key)` so two devices independently creating
 * the same logical row generate the *same* id and last-write-wins can reconcile them.
 * Random ids are UUIDv4, for rows where a duplicate is meaningful.
 *
 * **The namespace constants below are ratified and must never change** (decision 4a).
 * Every derived id already written to a database becomes unreachable if they do.
 */
public object Ids {

    /** RFC 4122 DNS namespace. */
    public const val DNS_NAMESPACE: String = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"

    /** `UUIDv5(DNS, "songbook.dev")` — decision 4a. */
    public val ROOT: String = uuid5(DNS_NAMESPACE, "songbook.dev")

    /**
     * `UUIDv5(ROOT, table)` — decision 4a.
     *
     * Namespaces are per table, not one shared root (decision 4b): a shared namespace would
     * give the tag `guitar` and the instrument `guitar` the same id.
     *
     * **Recomputed every call, deliberately.** This used to memoise into an unsynchronised
     * `mutableMapOf` inside a process-wide `object`, and Views added call sites on a path
     * documented as blocking and called off the main thread. The derived value is
     * deterministic so no wrong id could result, but a concurrent `HashMap` resize is the
     * classic corruption case, and process-wide mutable state is a hazard on a future Native
     * target. A SHA-1 over a short string is microseconds and this is not a hot loop, so the
     * memo went rather than acquiring a lock that would have to work on every KMP target.
     */
    public fun namespaceFor(table: String): String = uuid5(ROOT, table)

    /**
     * How many keys each multi-key derived id's canonical key holds. Views V31: `song_performer`
     * is three since V3, and the two-key overload still compiles for it — so
     * `junction("song_performer", song, performer)` would silently return the **superseded**
     * id, and decision 5 makes a written id permanent. A table absent from this map is
     * unconstrained; a table present in it must be called with exactly its arity.
     * `part_rating` is four (schema-3 M5).
     */
    private val JUNCTION_KEYS: Map<String, Int> = mapOf(
        "song_instrument" to 2,
        "song_tag" to 2,
        "setlist_item_performer" to 2,
        "song_performer" to 3,
        "part_rating" to 4,
    )

    private fun requireArity(table: String, keys: Int) {
        val expected = JUNCTION_KEYS[table]
        require(expected == null || expected == keys) {
            "$table is a $expected-key derived id (views V31, schema-3 M5); deriving it from " +
                "$keys keys returns an id that nothing else would flag"
        }
    }

    /**
     * A lookup or record row id: `UUIDv5(namespace(table), normalise(name))` — decision 4.
     * Applies to `instrument`, `tag`, `groove`, `venue`, `band`, `practice_context`,
     * `performer` and `artist`.
     */
    public fun derived(table: String, name: String): String =
        uuid5(namespaceFor(table), normalise(name))

    /**
     * A song id. Decision 4 makes the canonical key `artist_id` plus the normalised title.
     *
     * The `/` separator is ratified by decision 4d, not incidental: the Kotlin core and the
     * Python migration must concatenate identically or the same song gets two ids.
     */
    public fun song(artistId: String, title: String): String =
        uuid5(namespaceFor("song"), artistId + "/" + normalise(title))

    /**
     * A two-key junction row id — decision 4:
     * `UUIDv5(namespace(table), fkA + "/" + fkB)`.
     *
     * The form for `song_instrument`, `song_tag` and `setlist_item_performer`. It is the
     * same shape as every other derived id — the table's own namespace (decisions 4a, 4b)
     * over the canonical key, joined with the ratified `/` of decision 4d.
     *
     * This **supersedes** an earlier form, `UUIDv5(fkA, fkB)`, which used the first foreign
     * key directly as the namespace. That form carried no table identity, so two junctions
     * over the same pair of ids would collide, and it derives entirely different ids — every
     * one of the 595 `song_performer` rows the migration emits differs between the two. Ids
     * are immutable once written (decision 5), so reintroducing it forks every junction row
     * silently, with nothing failing loudly.
     *
     * The keys are **ordered**: pass them in the order the table declares them —
     * `song_instrument` is `(song_id, instrument_id)` — or the Kotlin core and the Python
     * migration will not converge.
     *
     * V31: this **refuses `song_performer`**, which has been a three-key junction since V3.
     * The overload still resolves for it, and without the check it would quietly return the
     * superseded two-key id.
     */
    public fun junction(table: String, fkA: String, fkB: String): String = derivedKey(table, fkA, fkB)

    /**
     * A three-key junction row id — decision 4 as amended by views decision V3:
     * `UUIDv5(namespace(table), fkA + "/" + fkB + "/" + fkC)`.
     *
     * `song_performer` is the first three-key junction (V1, V2, V3). The separator and the
     * per-table namespace are unchanged, so the shape **generalises** rather than forking:
     * this is the same construction as the two-key form with one more key appended, not a
     * second scheme.
     *
     * V4: every one of the existing `song_performer` ids changes as a direct consequence,
     * which is acceptable **only** because the table is rebuilt wholesale by the migration
     * and no device has yet synced. It would not be acceptable after phase 2 ships.
     *
     * The keys are **ordered**, exactly as in the two-key form: `song_performer` is
     * `(song_id, performer_id, instrument_id)`.
     */
    public fun junction(table: String, fkA: String, fkB: String, fkC: String): String =
        derivedKey(table, fkA, fkB, fkC)

    /**
     * A `part_rating` row id (schema-3 M5, M6): the derived key over
     * `(song_id, performer_id, instrument_id, kind)`, so two devices rating one part offline
     * converge on one row. Only Kotlin derives it; if `tools/import/` ever emits ratings, the
     * two-implementations rule engages.
     */
    public fun partRating(songId: String, performerId: String, instrumentId: String, kind: RatingKind): String =
        derivedKey("part_rating", songId, performerId, instrumentId, kind.name)

    /**
     * **The one multi-key derivation** (decision 4 as amended by V3):
     * `UUIDv5(namespace(table), keys joined by "/")`, keys in the table's declared order, after
     * [requireArity].
     */
    private fun derivedKey(table: String, vararg keys: String): String {
        requireArity(table, keys.size)
        return uuid5(namespaceFor(table), keys.joinToString("/"))
    }

    /**
     * A `song_performer` row id (decisions 3, 4, 4d, 26; views V1-V5) — [junction] over
     * `(song_id, performer_id, instrument_id)`.
     *
     * The instrument is part of the key because V2 widens the unique index to the triple:
     * one person holds a guitar row and a vocal row on the same song, and the two must not
     * collide. Derived, not random — two devices recording the same capability is a
     * duplicate, and decision 3 derives the id wherever a duplicate would be an error.
     *
     * `is_lead` and `vocal_range` are deliberately **not** in the key. Promoting somebody
     * from backing to lead updates that one row rather than minting a second, which is what
     * last-write-wins can merge.
     *
     * This must stay byte-for-byte identical to `junction_id("song_performer", …)` in
     * `tools/import/build.py`. The two have forked twice; when either moves, both move in
     * the same piece of work and the result is cross-checked against real migration output.
     */
    public fun songPerformer(songId: String, performerId: String, instrumentId: String): String =
        junction("song_performer", songId, performerId, instrumentId)

    /**
     * A `setlist_item_performer` row id (decisions 3, 4, 4d, 58) — [junction] over
     * `(setlist_item_id, performer_id)`, named because this junction has a caller in the
     * shared core rather than only in the migration.
     *
     * Derived, not random: two devices staging the same performer on the same item is a
     * duplicate, and decision 3 derives the id wherever a duplicate would be an error.
     *
     * `position` is deliberately **not** part of the key. Moving someone from lead to
     * co-lead updates that one row rather than minting a second, which is what
     * last-write-wins can merge.
     */
    public fun setlistItemPerformer(setlistItemId: String, performerId: String): String =
        junction("setlist_item_performer", setlistItemId, performerId)

    /**
     * A `song_tag` row id (decisions 4, 13, 43) — [junction] over `(song_id, tag_id)`, named
     * so no caller spells the table string by hand. The value is exactly the two-key
     * junction's, which is exactly what the migration emits; `IdsTest` pins it against a row
     * lifted from the migrated database.
     */
    public fun songTag(songId: String, tagId: String): String =
        junction("song_tag", songId, tagId)

    /**
     * A `song_instrument` row id (decisions 4, 41) — [junction] over
     * `(song_id, instrument_id)`, in the order the table declares them. Pinned in `IdsTest`
     * against a row lifted from the migrated database.
     */
    public fun songInstrument(songId: String, instrumentId: String): String =
        junction("song_instrument", songId, instrumentId)

    /** A UUIDv4, for rows where a duplicate is meaningful (decisions 3, 6). */
    public fun random(random: Random = Random.Default): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        return bytesToUuid(bytes)
    }
}

/**
 * UUIDv5 per RFC 4122 section 4.3: SHA-1 over the namespace's 16 bytes followed by the
 * name's UTF-8 bytes, truncated to 16 bytes, with the version nibble set to 5 and the
 * variant bits set to RFC 4122.
 */
public fun uuid5(namespace: String, name: String): String {
    val input = uuidToBytes(namespace) + name.encodeToByteArray()
    val hash = sha1(input)
    val bytes = hash.copyOf(16)
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x50).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
    return bytesToUuid(bytes)
}

private const val HEX = "0123456789abcdef"

internal fun uuidToBytes(uuid: String): ByteArray {
    val hex = uuid.filter { it != '-' }
    require(hex.length == 32) { "Not a UUID: $uuid" }
    val bytes = ByteArray(16)
    for (i in 0 until 16) {
        val high = hexDigit(hex[i * 2])
        val low = hexDigit(hex[i * 2 + 1])
        bytes[i] = ((high shl 4) or low).toByte()
    }
    return bytes
}

private fun hexDigit(ch: Char): Int {
    val digit = HEX.indexOf(ch.lowercaseChar())
    require(digit >= 0) { "Not a hex digit: $ch" }
    return digit
}

internal fun bytesToUuid(bytes: ByteArray): String {
    require(bytes.size == 16) { "A UUID is 16 bytes, got ${bytes.size}" }
    val builder = StringBuilder(36)
    for (i in 0 until 16) {
        if (i == 4 || i == 6 || i == 8 || i == 10) builder.append('-')
        val value = bytes[i].toInt() and 0xFF
        builder.append(HEX[value ushr 4])
        builder.append(HEX[value and 0x0F])
    }
    return builder.toString()
}
