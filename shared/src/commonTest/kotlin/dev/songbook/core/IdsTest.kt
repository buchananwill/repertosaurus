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

    /** Decision 4: junctions key on the composite of the two foreign keys. */
    @Test
    fun junctionIdsAreDerivedFromBothForeignKeys() {
        val song = Ids.song(Ids.derived("artist", "The Zutons"), "Valerie")
        val guitar = Ids.derived("instrument", "guitar")
        val bass = Ids.derived("instrument", "bass")
        assertEquals(Ids.junction(song, guitar), Ids.junction(song, guitar))
        assertNotEquals(Ids.junction(song, guitar), Ids.junction(song, bass))
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
