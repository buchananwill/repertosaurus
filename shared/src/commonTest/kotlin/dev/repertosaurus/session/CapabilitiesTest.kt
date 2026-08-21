package dev.repertosaurus.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure half of the capability editor — editing E4, E8, E16, E17, E18. No database.
 */
class CapabilitiesTest {

    /**
     * E8's two instruments, pinned against the **literal seed ids in `instrument.sq`**, not
     * against a re-derivation. A test that agrees with its own author cannot catch an id
     * fork, which is the failure mode this project has hit twice.
     */
    @Test
    fun vocalRangeAppliesToExactlyTheTwoSeededVoices() {
        assertEquals(
            setOf("6409e4f3-5881-5293-8918-9dbafe572ac6", "38ae379a-fb72-5c64-9242-b57f6deeb5cd"),
            VocalRanges.INSTRUMENT_IDS,
        )
        assertTrue(VocalRanges.appliesTo("6409e4f3-5881-5293-8918-9dbafe572ac6"), "vocal")
        assertTrue(VocalRanges.appliesTo("38ae379a-fb72-5c64-9242-b57f6deeb5cd"), "backing vocal")
        assertFalse(VocalRanges.appliesTo("f6b6f826-8eeb-5acc-9314-d2c096f780f6"), "guitar")
        assertFalse(VocalRanges.appliesTo("4e6210b7-1029-5855-99c2-32f48d87ec2a"), "bass")
        assertFalse(VocalRanges.appliesTo("ce4b4d8d-ea47-52f0-aeed-a167949377e4"), "keys")
    }

    /**
     * Decision 27 and V7: a closed two-value vocabulary, `H`/`L` mapped to `1`/`0`. The
     * `CHECK` in `song_performer.sq` and this enum are two statements of it, so the mapping is
     * pinned in both directions.
     */
    @Test
    fun theVocalRangeVocabularyIsTwoValuesAndNull() {
        assertEquals(listOf(0L, 1L), VocalRange.entries.map { it.stored })
        assertEquals(VocalRange.LOW, VocalRange.of(0L))
        assertEquals(VocalRange.HIGH, VocalRange.of(1L))
        assertNull(VocalRange.of(null))
        assertNull(VocalRange.of(2L), "a value the CHECK forbids reads as absent, not as high")
    }

    /** E4: `is_lead` defaults off, on every instrument. */
    @Test
    fun aCapabilityDefaultsToNotLeadWithNoRangeAndNoNotes() {
        val row = capability(instrumentId = "f6b6f826-8eeb-5acc-9314-d2c096f780f6")
        assertFalse(row.isLead)
        assertNull(row.vocalRange)
        assertNull(row.notes)
        assertFalse(row.rangeApplies)
    }

    /** E10: the entry knows whether this person leads on any of their instruments. */
    @Test
    fun aLineUpEntryLeadsWhenAnyOfItsRowsDoes() {
        val guitar = capability(instrumentId = "f6b6f826-8eeb-5acc-9314-d2c096f780f6")
        val vocal = capability(
            id = "row-2",
            instrumentId = "6409e4f3-5881-5293-8918-9dbafe572ac6",
            instrumentName = "vocal",
        )
        assertFalse(PerformerLineUp("p", "Charlotte", listOf(guitar, vocal)).leads)
        assertTrue(
            PerformerLineUp("p", "Charlotte", listOf(guitar, vocal.copy(isLead = true))).leads,
        )
    }

    // ---- The display rules (E46) -----------------------------------------------------------

    /**
     * E46: the title-case helper is **one function in the core**, and the chip row and the
     * capability chips read the same instrument the same way. The sheet used to hold a
     * character-for-character duplicate of it.
     */
    @Test
    fun oneTitleCaseHelperSpellsTheSameInstrumentTheSameWayEverywhere() {
        assertEquals("Backing Vocal", titleCase("backing vocal"))
        assertEquals("Guitar", titleCase("guitar"))
        assertEquals("", titleCase(""))
        assertEquals(
            InstrumentChip("i", "backing vocal").label,
            capability(
                instrumentId = "38ae379a-fb72-5c64-9242-b57f6deeb5cd",
                instrumentName = "backing vocal",
            ).instrumentLabel,
            "the chip row and the capability chip must not spell it differently",
        )
    }

    /**
     * E46: the chip sentence, assembled once. `is_lead` is scoped to its instrument (E4, V6),
     * so "lead" reads against the chip and not against the person, and the joiner is the
     * separator the View summary already owns rather than a second `·` literal.
     */
    @Test
    fun theChipSentenceNamesTheInstrumentThenWhatTheRowAsserts() {
        val vocal = capability(
            instrumentId = "6409e4f3-5881-5293-8918-9dbafe572ac6",
            instrumentName = "backing vocal",
        )
        assertEquals("Backing Vocal", vocal.label)
        assertEquals("Backing Vocal · lead", vocal.copy(isLead = true).label)
        assertEquals(
            "Backing Vocal · lead · high",
            vocal.copy(isLead = true, vocalRange = VocalRange.HIGH).label,
        )
        assertEquals("Backing Vocal · low", vocal.copy(vocalRange = VocalRange.LOW).label)
        assertTrue(
            vocal.copy(isLead = true).label.contains(ViewSummary.SEPARATOR),
            "the chip sentence must reuse the View summary's separator",
        )
    }

    /** E46: the performer heading and the row heading, both from the core. */
    @Test
    fun theHeadingsAreTheCoresAndNotTheSheets() {
        val vocal = capability(
            instrumentId = "6409e4f3-5881-5293-8918-9dbafe572ac6",
            instrumentName = "vocal",
        )
        assertEquals("Charlotte · Vocal", vocal.heading)
        assertEquals("Leads on Vocal", vocal.leadLabel)
        assertEquals("Charlotte", PerformerLineUp("p", "Charlotte", listOf(vocal)).heading)
        assertEquals(
            "Charlotte · leads",
            PerformerLineUp("p", "Charlotte", listOf(vocal.copy(isLead = true))).heading,
        )
    }

    /** The range's own words, through the same helper. */
    @Test
    fun theVocalRangeSpellsItself() {
        assertEquals("High", VocalRange.HIGH.label)
        assertEquals("Low", VocalRange.LOW.label)
    }

    // ---- The lookup vocabulary (E16, E17, E18) --------------------------------------------

    /**
     * E18: every kind names a real table, and the table name is also the id namespace
     * (decision 4a), so a typo here would fork ids as well as miss a screen.
     */
    @Test
    fun everyLookupKindNamesADistinctTable() {
        val tables = LookupKind.entries.map { it.table.table }
        assertEquals(tables.size, tables.toSet().size, "no two kinds share a table")
        assertEquals(
            listOf("instrument", "performer", "tag", "groove", "venue", "band", "practice_context"),
            tables,
        )
        assertTrue(LookupKind.entries.all { it.plural.isNotBlank() && it.singular.isNotBlank() })
    }

    /**
     * E16: `hasNotes` is the screen's only guard, so it is stated once here and pinned against
     * the schema by `LookupStoresTest`. `venue` is deliberately false — see the comment on the
     * constant; the column does not exist and adding it is a schema change under S1.
     */
    @Test
    fun onlyPerformerAndBandDeclareANotesField() {
        assertEquals(
            listOf(LookupKind.PERFORMER, LookupKind.BAND),
            LookupKind.entries.filter { it.hasNotes },
        )
    }

    /** E17: the field means live rows a removal would hide, and it is never negative. */
    @Test
    fun aLookupItemCarriesItsCountAndOptionalSecondLine() {
        val bare = LookupItem(id = "i", name = "guitar", usageCount = 0L)
        assertNull(bare.subtitle)
        assertNull(bare.notes)

        val performer = LookupItem(
            id = "p",
            name = "Charlotte",
            usageCount = 3L,
            subtitle = "vocal, backing vocal, keys",
            notes = "can also play bass at a push",
        )
        assertEquals(3L, performer.usageCount)
        assertEquals("vocal, backing vocal, keys", performer.subtitle)
    }

    /**
     * E17, E46: **every kind's count says what it counts, and every one gets singular and
     * plural right.** Seven branches of wording that used to live in a composable, where the
     * desktop UI could not reach them and no JVM test could see them.
     */
    @Test
    fun everyLookupKindSpellsItsOwnUsageCountBothWays() {
        assertEquals("1 practice logged", LookupKind.INSTRUMENT.usagePhrase(1L))
        assertEquals("4 practices logged", LookupKind.INSTRUMENT.usagePhrase(4L))
        assertEquals("1 recorded part", LookupKind.PERFORMER.usagePhrase(1L))
        assertEquals("595 recorded parts", LookupKind.PERFORMER.usagePhrase(595L))
        assertEquals("1 song tagged", LookupKind.TAG.usagePhrase(1L))
        assertEquals("48 songs tagged", LookupKind.TAG.usagePhrase(48L))
        assertEquals("1 song", LookupKind.GROOVE.usagePhrase(1L))
        assertEquals("2 songs", LookupKind.GROOVE.usagePhrase(2L))
        assertEquals("1 set list", LookupKind.VENUE.usagePhrase(1L))
        assertEquals("3 set lists", LookupKind.VENUE.usagePhrase(3L))
        assertEquals("1 set list", LookupKind.BAND.usagePhrase(1L))
        assertEquals("1 event logged", LookupKind.PRACTICE_CONTEXT.usagePhrase(1L))
        assertEquals("9 events logged", LookupKind.PRACTICE_CONTEXT.usagePhrase(9L))
        assertTrue(
            LookupKind.entries.all { it.usagePhrase(0L).startsWith("0 ") },
            "zero takes the plural, which is what the screen's other branch reads as",
        )
    }

    /** E46: English grammar is a rule, and the seven singulars each get the right article. */
    @Test
    fun eachKindTakesTheArticleEnglishWants() {
        assertEquals("an instrument", LookupKind.INSTRUMENT.withArticle)
        assertEquals("a performer", LookupKind.PERFORMER.withArticle)
        assertEquals("a tag", LookupKind.TAG.withArticle)
        assertEquals("a groove", LookupKind.GROOVE.withArticle)
        assertEquals("a venue", LookupKind.VENUE.withArticle)
        assertEquals("a band", LookupKind.BAND.withArticle)
        assertEquals("a practice context", LookupKind.PRACTICE_CONTEXT.withArticle)
        assertEquals("an", indefiniteArticle("Encore"), "the test is on the first letter, not case")
        assertEquals("a", indefiniteArticle(""), "an empty word must not crash the label")
    }

    private fun capability(
        id: String = "row-1",
        instrumentId: String,
        instrumentName: String = "guitar",
    ): SongCapability = SongCapability(
        id = id,
        performerId = "performer-1",
        performerName = "Charlotte",
        instrumentId = instrumentId,
        instrumentName = instrumentName,
    )
}
