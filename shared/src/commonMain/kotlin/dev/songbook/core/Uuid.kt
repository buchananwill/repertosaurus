package dev.songbook.core

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

    // Namespaces are per table, not one shared root (decision 4b): a shared namespace
    // would give the tag `guitar` and the instrument `guitar` the same id.
    private val namespaces: MutableMap<String, String> = mutableMapOf()

    /** `UUIDv5(ROOT, table)` — decision 4a. */
    public fun namespaceFor(table: String): String =
        namespaces.getOrPut(table) { uuid5(ROOT, table) }

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
     * A junction row id: the composite of the two foreign keys, e.g. `song_instrument` is
     * `UUIDv5(song_id, instrument_id)` — decision 4. The first key is the namespace.
     */
    public fun junction(firstId: String, secondId: String): String = uuid5(firstId, secondId)

    /**
     * A `setlist_item_performer` row id (decisions 3, 4, 4d, 58):
     * `UUIDv5(namespace('setlist_item_performer'), setlist_item_id + "/" + performer_id)`.
     *
     * Derived, not random: two devices staging the same performer on the same item is a
     * duplicate, and decision 3 derives the id wherever a duplicate would be an error.
     *
     * `position` is deliberately **not** part of the key. Moving someone from lead to
     * co-lead updates that one row rather than minting a second, which is what
     * last-write-wins can merge.
     *
     * Note the shape: this junction keys on the table's own namespace with the `/`
     * separator of decision 4d, where [junction] keys on `UUIDv5(firstId, secondId)` for
     * `song_instrument` and `song_performer`. Both forms satisfy decision 4 and they are
     * not interchangeable, so the migration must use this one for this table.
     */
    public fun setlistItemPerformer(setlistItemId: String, performerId: String): String =
        uuid5(namespaceFor("setlist_item_performer"), setlistItemId + "/" + performerId)

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
