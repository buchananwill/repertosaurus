package dev.songbook.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Decisions 2, 4, 4a, 4b.
 *
 * The values asserted here are **ratified constants**, not expectations to be updated if
 * the implementation changes. Every derived id already written to a database becomes
 * unreachable if any of them move, and the seed rows in the `.sq` files carry these exact
 * ids. If one of these fails, the implementation is wrong.
 */
class IdsTest {

    @Test
    fun rootNamespaceIsTheRatifiedValue() {
        assertEquals("3ce0f1dc-b3b4-5aee-9683-49dfb0741ca7", Ids.ROOT)
    }

    @Test
    fun tableNamespacesAreTheRatifiedValues() {
        assertEquals("bf0c6920-7eae-590d-847d-49436fa15980", Ids.namespaceFor("instrument"))
        assertEquals("2e80c13e-b285-5e54-b5c8-b556ba347362", Ids.namespaceFor("tag"))
        assertEquals("947acc5f-6891-5179-bfdb-268d05808236", Ids.namespaceFor("artist"))
        assertEquals("799fddcf-482e-5e9c-bd85-3e5cbbb2f477", Ids.namespaceFor("performer"))
        assertEquals("84fa607d-e399-5f04-8a32-7b3d81104268", Ids.namespaceFor("groove"))
        assertEquals("d14fa03f-4788-55a6-9b2c-e675c56c8829", Ids.namespaceFor("venue"))
        assertEquals("414530ce-0bcb-5557-a96a-f532ccac4bfa", Ids.namespaceFor("practice_context"))
    }

    @Test
    fun instrumentSeedIdsMatchTheSchema() {
        assertEquals("f6b6f826-8eeb-5acc-9314-d2c096f780f6", Ids.derived("instrument", "guitar"))
        assertEquals("6409e4f3-5881-5293-8918-9dbafe572ac6", Ids.derived("instrument", "vocal"))
        assertEquals("4e6210b7-1029-5855-99c2-32f48d87ec2a", Ids.derived("instrument", "bass"))
        assertEquals("ce4b4d8d-ea47-52f0-aeed-a167949377e4", Ids.derived("instrument", "keys"))
        assertEquals(
            "38ae379a-fb72-5c64-9242-b57f6deeb5cd",
            Ids.derived("instrument", "backing vocal"),
        )
    }

    @Test
    fun tagSeedIdsMatchTheSchema() {
        assertEquals("19f52880-05eb-5873-9d19-e754f0ee75f6", Ids.derived("tag", "party"))
        assertEquals("88c3f71b-6d7e-5a4b-ab3d-af296c4a76e5", Ids.derived("tag", "christmas"))
        assertEquals("cc9212da-f163-5dbf-a41d-cc13ad4daaff", Ids.derived("tag", "cw-duet"))
        assertEquals("774d2338-a928-54ea-9b6a-c6552ab6ea0b", Ids.derived("tag", "target"))
        assertEquals("4de25824-dbf2-5a3e-bb3e-fb2e610b6c1e", Ids.derived("tag", "need-to-learn"))
    }

    @Test
    fun practiceContextSeedIdsMatchTheSchema() {
        assertEquals(
            "1a9bb75b-e61f-533f-aaae-30053e06856e",
            Ids.derived("practice_context", "practice"),
        )
        assertEquals(
            "42cd9d7c-6ee6-5604-b2f4-81d68820212d",
            Ids.derived("practice_context", "twitch"),
        )
        assertEquals(
            "a547e2df-0f82-5dba-a7c4-c4d0f2031208",
            Ids.derived("practice_context", "rehearsal"),
        )
        assertEquals("ead21184-4281-5573-829a-5bb64a2b399c", Ids.derived("practice_context", "gig"))
    }

    /** Decision 4b: per-table namespaces, or the tag and the instrument collide. */
    @Test
    fun perTableNamespacesKeepGuitarTheTagApartFromGuitarTheInstrument() {
        assertEquals("f6b6f826-8eeb-5acc-9314-d2c096f780f6", Ids.derived("instrument", "guitar"))
        assertEquals("f11c3596-de4a-5c65-b83a-ecd328b88ca2", Ids.derived("tag", "guitar"))
        assertNotEquals(Ids.derived("instrument", "guitar"), Ids.derived("tag", "guitar"))
    }

    /** Decision 4c: the display name keeps its hyphens, the id keys on the normalised form. */
    @Test
    fun derivedIdsKeyOnTheNormalisedName() {
        assertEquals(Ids.derived("tag", "cw-duet"), Ids.derived("tag", "CW Duet"))
        assertEquals(Ids.derived("tag", "need-to-learn"), Ids.derived("tag", "Need To Learn"))
        assertEquals(Ids.derived("artist", "The Fratellis"), Ids.derived("artist", "Fratellis"))
        assertEquals(
            Ids.derived("artist", "Florence & the Machine"),
            Ids.derived("artist", "florence and the machine"),
        )
    }

    /**
     * Decision 17a. The composed and decomposed spellings of an accent must derive the
     * **same** id, or an iPhone and an Android phone fork the artist permanently —
     * decision 5 makes an id immutable once written. The characters are built from code
     * points because the two spellings are indistinguishable in an editor.
     *
     * Both artists are in the source workbook: `Michael Bublé` x18, `Emily Sandé` x3.
     */
    @Test
    fun composedAndDecomposedAccentsDeriveTheSameId() {
        val eAcute = Char(0x00E9)
        val combiningAcute = Char(0x0301)

        assertEquals(
            "42f5d102-136f-59eb-bb4e-fc113027743e",
            Ids.derived("artist", "Michael Bubl" + eAcute),
        )
        assertEquals(
            Ids.derived("artist", "Michael Bubl" + eAcute),
            Ids.derived("artist", "Michael Buble" + combiningAcute),
        )

        assertEquals(
            "eec18a86-ef0d-5ee5-a8a4-3d43d71042c4",
            Ids.derived("artist", "Emily Sand" + eAcute),
        )
        assertEquals(
            Ids.derived("artist", "Emily Sand" + eAcute),
            Ids.derived("artist", "Emily Sande" + combiningAcute),
        )
    }

    /**
     * The NFC step of decision 17 must not disturb a single ratified id: every seed name
     * is ASCII, and NFC is a no-op on ASCII. The 14 seed ids themselves are asserted
     * above, one table at a time; this pins the property they all rest on.
     */
    @Test
    fun theNfcStepIsANoOpOnEverySeedName() {
        val seedNames = listOf(
            "vocal", "backing vocal", "guitar", "bass", "keys",
            "party", "christmas", "cw-duet", "target", "need-to-learn",
            "practice", "twitch", "rehearsal", "gig",
        )
        assertEquals(14, seedNames.size)
        for (name in seedNames) {
            assertEquals(name, unicodeNormalise(name), "NFC must not touch the seed '$name'")
        }
        // And the placeholder decision 28a seeds into `artist`.
        assertEquals(
            "cf06771d-4e8d-53fc-83fb-359be7dfaefc",
            Ids.derived("artist", "Unknown Artist"),
        )
    }

    /** Decision 2: two devices creating the same logical row must converge on one id. */
    @Test
    fun derivationIsDeterministic() {
        assertEquals(Ids.derived("venue", "The Blue Lion"), Ids.derived("venue", "the blue lion"))
        assertEquals(Ids.namespaceFor("song"), Ids.namespaceFor("song"))
    }

    @Test
    fun versionAndVariantNibblesAreRfc4122() {
        val id = Ids.derived("instrument", "guitar")
        assertEquals('5', id[14], "version nibble must be 5")
        assertTrue(id[19] in "89ab", "variant nibble must be 8, 9, a or b — was ${id[19]}")
    }

    @Test
    fun uuid5MatchesTheRfc4122ReferenceVector() {
        // RFC 4122 / Python `uuid.uuid5(uuid.NAMESPACE_DNS, "python.org")`.
        assertEquals(
            "886313e1-3b8a-5372-9b90-0c9aee199e5d",
            uuid5(Ids.DNS_NAMESPACE, "python.org"),
        )
    }

    /**
     * Decision 4: a junction id is `UUIDv5(namespace(table), fkA + "/" + fkB)` — the
     * table's own namespace over the two foreign keys, joined with the `/` of decision 4d.
     */
    @Test
    fun junctionIdsAreDerivedFromTheTableNamespaceAndBothForeignKeys() {
        val song = Ids.song(Ids.derived("artist", "The Zutons"), "Valerie")
        val guitar = Ids.derived("instrument", "guitar")
        val bass = Ids.derived("instrument", "bass")

        assertEquals(
            uuid5(Ids.namespaceFor("song_instrument"), "$song/$guitar"),
            Ids.junction("song_instrument", song, guitar),
        )
        assertEquals(
            Ids.junction("song_instrument", song, guitar),
            Ids.junction("song_instrument", song, guitar),
        )
        assertNotEquals(
            Ids.junction("song_instrument", song, guitar),
            Ids.junction("song_instrument", song, bass),
        )
    }

    /**
     * The superseded junction form — the first foreign key used directly as the namespace,
     * no table identity and no separator — must stay dead. It collides across two junctions
     * over the same pair of ids, and it derives ids the migration has never emitted.
     */
    @Test
    fun theSupersededJunctionFormIsNotWhatWeDerive() {
        val song = Ids.song(Ids.derived("artist", "The Zutons"), "Valerie")
        val guitar = Ids.derived("instrument", "guitar")

        assertNotEquals(uuid5(song, guitar), Ids.junction("song_instrument", song, guitar))
        // The table is part of the key, so the same pair in two junctions cannot collide.
        assertNotEquals(
            Ids.junction("song_instrument", song, guitar),
            Ids.junction("song_tag", song, guitar),
        )
    }

    /**
     * The four junctions, pinned. The `song_performer` and `song_tag` values are **exact
     * ids the Python migration emitted** for real rows — Amy Winehouse's *Valerie* tagged
     * `bass-vox`, ABBA's *Dancing Queen* sung by Jennifer — taken from a cross-check of all
     * 507 + 282 junction ids it emits. If one of these moves, the app and the migration
     * have forked, and decision 5 makes that permanent.
     */
    @Test
    fun junctionIdsForAllFourTablesAreTheRatifiedValues() {
        // Namespaces derived rather than copied: namespace(table) = UUIDv5(ROOT, table).
        assertEquals(uuid5(Ids.ROOT, "song_performer"), Ids.namespaceFor("song_performer"))
        assertEquals(uuid5(Ids.ROOT, "song_tag"), Ids.namespaceFor("song_tag"))
        assertEquals(uuid5(Ids.ROOT, "song_instrument"), Ids.namespaceFor("song_instrument"))
        assertEquals(
            uuid5(Ids.ROOT, "setlist_item_performer"),
            Ids.namespaceFor("setlist_item_performer"),
        )

        assertEquals("f6e3d47e-9ffd-5ddc-8595-8ed9a98071f1", Ids.namespaceFor("song_performer"))
        assertEquals("e32a16d4-bfe6-5d25-a481-ae7ea033f53e", Ids.namespaceFor("song_tag"))
        assertEquals("6572c369-eed2-5b33-8a1f-8d19bc16ee51", Ids.namespaceFor("song_instrument"))
        assertEquals(
            "52a976ba-d58d-5c3c-ac60-acb796d7e8cf",
            Ids.namespaceFor("setlist_item_performer"),
        )

        // Both song ids are derived here, and both match what the migration emitted.
        val valerie = Ids.song(Ids.derived("artist", "Amy Winehouse"), "Valerie")
        assertEquals("79915806-b3fe-5ece-b33e-77c09dd8c907", valerie)
        val dancingQueen = Ids.song(Ids.derived("artist", "ABBA"), "Dancing Queen")
        assertEquals("ac7c45d3-fe48-548b-a718-d13ce4f94394", dancingQueen)

        // song_tag: Valerie tagged `bass-vox`.
        assertEquals(
            "5fd6b9f0-9c89-5c7f-b191-19e36d0bb885",
            Ids.junction("song_tag", valerie, "212b5a4b-0a46-5ab5-bb7c-a448f42ae70c"),
        )
        // song_performer: Dancing Queen sung by Jennifer.
        assertEquals(
            "9d6741be-10f9-5e0c-9d7f-d9933d272018",
            Ids.junction("song_performer", dancingQueen, "563c8cf4-40a7-50b0-8956-00a4055a7d34"),
        )
        // song_instrument: Valerie on guitar. No migration row to compare — the migration
        // emits per-instrument facts without ids yet — so this pins the construction.
        assertEquals(
            "124c7c67-a795-57c4-a04a-a655d2df3145",
            Ids.junction("song_instrument", valerie, Ids.derived("instrument", "guitar")),
        )
        // setlist_item_performer: a fixed item id, staged with Will.
        assertEquals(
            "1782fe77-20bc-5914-bb1d-76fb20821e38",
            Ids.setlistItemPerformer(
                "3f1d9c58-0f3e-4a3f-9a1b-2c7d4e5f6a7b",
                Ids.derived("performer", "Will"),
            ),
        )
        // And that performer id is the one the migration emitted for Will.
        assertEquals("c5b61fd7-b087-52a1-a43c-e771203207b5", Ids.derived("performer", "Will"))
    }

    /**
     * Decisions 3, 4, 4d, 58. `setlist_item_performer` derives its id inside the table's
     * own namespace over `setlist_item_id + "/" + performer_id` — and **not** over
     * `position`, so moving someone from lead to co-lead updates one row rather than
     * minting a second.
     */
    @Test
    fun setlistItemPerformerIdsAreNamespacedAndIgnorePosition() {
        val item = "3f1d9c58-0f3e-4a3f-9a1b-2c7d4e5f6a7b"
        val will = Ids.derived("performer", "Will")
        val coralie = Ids.derived("performer", "Coralie")

        assertEquals(
            uuid5(Ids.namespaceFor("setlist_item_performer"), "$item/$will"),
            Ids.setlistItemPerformer(item, will),
        )
        assertEquals(Ids.setlistItemPerformer(item, will), Ids.setlistItemPerformer(item, will))
        assertNotEquals(
            Ids.setlistItemPerformer(item, will),
            Ids.setlistItemPerformer(item, coralie),
        )

        // It is the one junction form, not a second one: this is exactly Ids.junction
        // against this table's namespace, which is its own and not the root.
        assertNotEquals(Ids.namespaceFor("setlist_item_performer"), Ids.ROOT)
        assertEquals(
            Ids.junction("setlist_item_performer", item, will),
            Ids.setlistItemPerformer(item, will),
        )
    }

    /** Decision 6: practice events must be random, or same-day sessions collapse. */
    @Test
    fun randomIdsAreDistinctAndWellFormedV4() {
        val ids = (1..500).map { Ids.random() }.toSet()
        assertEquals(500, ids.size)
        for (id in ids) {
            assertEquals(36, id.length)
            assertEquals('4', id[14])
            assertTrue(id[19] in "89ab")
        }
    }
}
