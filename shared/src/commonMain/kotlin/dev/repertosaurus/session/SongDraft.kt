package dev.repertosaurus.session

import dev.repertosaurus.core.Keys
import dev.repertosaurus.data.SongCatalog

/**
 * Every editable `song` column as a form value — repertoire-editing R12-R14.
 *
 * Free-text and numeric columns are held as the **text in the field**, because that is what
 * the user is editing: a numeric field that has been cleared is `""`, and a half-typed `12a`
 * has to survive until [validate] says what is wrong with it. The two columns the schema
 * constrains to a range, [keySignature] and [tonalCentre], are **picked, never typed** (R13),
 * so they are held as the picked value.
 *
 * [artistId] and [grooveId] are set only when the user picked a row out of the type-ahead's
 * suggestions (R16); editing the text must clear them, and a null id with a name means
 * create-on-enter. A picked row is carried by id because a renamed row's current name derives
 * a different id from the one it is stored under (R22).
 *
 * Not here, deliberately: `id`, `updated_at`, `deleted_at`, `device_id` (R12).
 *
 * A data class, so "unsaved changes" (R14) is `draft != SongDraft.from(record)`.
 */
public data class SongDraft(
    val title: String = "",
    val artistName: String = "",
    val artistId: String? = null,
    val referenceRecording: String = "",
    /** Decision 32: signed sharp/flat count, −7..+7. Picked (R13). */
    val keySignature: Int? = null,
    /** Decision 33: pitch class 0–11, 0 = C. Picked (R13). */
    val tonalCentre: Int? = null,
    /** Decision 39: free text, hidden by default in every UI. */
    val tonalityNote: String = "",
    val tempoBpm: String = "",
    val durationSeconds: String = "",
    val decade: String = "",
    val loopLength: String = "",
    val chordCount: String = "",
    val chordPattern: String = "",
    val grooveName: String = "",
    val grooveId: String? = null,
    val mashupNote: String = "",
    val notes: String = "",
    val chartUrl: String = "",
) {

    /**
     * Per-field errors, empty when the draft can be saved. **Never throws for user input**
     * (R14): an invalid field shows its error inline and blocks Save, so a `CHECK` violation
     * never reaches SQLite from the detail.
     *
     * The rules are exactly the schema's plus R13's two mandatory fields:
     *
     * - title and artist are required (R13, decisions 27, 37 — nothing else is);
     * - `key_signature` is −7..7 and `tonal_centre` is 0..11, mirroring `song.sq`'s two
     *   `CHECK`s — the only value `CHECK`s on the table;
     * - the five `INTEGER` columns must parse as whole numbers, because SQLite's type affinity
     *   would otherwise store `12a` as text in an integer column rather than refuse it.
     *
     * No range is invented beyond those: a negative tempo is nonsense, but no decision says
     * so and the schema does not refuse it.
     */
    public fun errors(): Map<SongField, String> = buildMap {
        if (title.isBlank()) put(SongField.TITLE, REQUIRED)
        if (artistName.isBlank()) put(SongField.ARTIST, REQUIRED)
        if (keySignature != null && keySignature !in Keys.KEY_SIGNATURE_RANGE) {
            put(SongField.KEY_SIGNATURE, KEY_SIGNATURE_OUT_OF_RANGE)
        }
        if (tonalCentre != null && tonalCentre !in Keys.TONAL_CENTRE_RANGE) {
            put(SongField.TONAL_CENTRE, TONAL_CENTRE_OUT_OF_RANGE)
        }
        for ((field, text) in integerFields()) {
            if (text.isNotBlank() && text.trim().toLongOrNull() == null) put(field, NOT_A_NUMBER)
        }
    }

    /**
     * The draft as the values [SongCatalog.updateSong] writes, or the errors that block it.
     * Blank text and blank numbers become `NULL`; text is trimmed.
     */
    public fun validate(): SongDraftValidation {
        val errors = errors()
        if (errors.isNotEmpty()) return SongDraftValidation.Invalid(errors)
        return SongDraftValidation.Valid(
            SongCatalog.SongFields(
                title = title.trim(),
                artist = SongCatalog.LookupChoice.of(artistId, artistName.trim()),
                referenceRecording = referenceRecording.orNull(),
                keySignature = keySignature?.toLong(),
                tonalCentre = tonalCentre?.toLong(),
                tonalityNote = tonalityNote.orNull(),
                tempoBpm = tempoBpm.toLongOrNullWhenBlank(),
                durationSeconds = durationSeconds.toLongOrNullWhenBlank(),
                decade = decade.toLongOrNullWhenBlank(),
                loopLength = loopLength.toLongOrNullWhenBlank(),
                chordCount = chordCount.toLongOrNullWhenBlank(),
                chordPattern = chordPattern.orNull(),
                groove = if (grooveName.isBlank()) null else SongCatalog.LookupChoice.of(grooveId, grooveName.trim()),
                mashupNote = mashupNote.orNull(),
                notes = notes.orNull(),
                chartUrl = chartUrl.orNull(),
            ),
        )
    }

    /**
     * **F22 N8: one field's value, keyed by [SongField]** — for a caller that works over the
     * fields as a set rather than by name (merge's per-field pick, R34). The value as the draft
     * holds it — the text in the field, or a picked number spelled as digits — **and null when
     * the field is empty**, so "blank" and "equal" are one comparison. Not a display label: the
     * key pickers are labelled by [Keys], as the detail does.
     *
     * For [SongField.ARTIST] and [SongField.GROOVE] this is the name; the picked id travels with
     * [with]. Exhaustive with no `else`: a field added to [SongField] is a compile error here.
     */
    public fun value(field: SongField): String? = when (field) {
        SongField.TITLE -> title
        SongField.ARTIST -> artistName
        SongField.REFERENCE_RECORDING -> referenceRecording
        SongField.KEY_SIGNATURE -> keySignature?.toString()
        SongField.TONAL_CENTRE -> tonalCentre?.toString()
        SongField.TONALITY_NOTE -> tonalityNote
        SongField.TEMPO_BPM -> tempoBpm
        SongField.DURATION_SECONDS -> durationSeconds
        SongField.DECADE -> decade
        SongField.LOOP_LENGTH -> loopLength
        SongField.CHORD_COUNT -> chordCount
        SongField.CHORD_PATTERN -> chordPattern
        SongField.GROOVE -> grooveName
        SongField.MASHUP_NOTE -> mashupNote
        SongField.NOTES -> notes
        SongField.CHART_URL -> chartUrl
    }?.takeIf { it.isNotBlank() }

    /**
     * **What makes two drafts' [field] the same** — [value], except that the artist and the groove
     * are compared by their row id where there is one (session 09 ruling on 3b's #8). Two artists
     * can share a name (R22: a rename never re-derives the id), and a merge that compared names
     * would silently keep the survivor's row where the user had a real choice.
     */
    public fun identity(field: SongField): String? = when (field) {
        SongField.ARTIST -> artistId ?: value(field)
        SongField.GROOVE -> grooveId ?: value(field)
        else -> value(field)
    }

    /**
     * **F22 N8: this draft with [field] taken from [from]**, every other field untouched. The
     * artist and the groove carry their picked id with the name, so a field taken from the other
     * side keeps pointing at that side's row rather than being re-resolved by name (R22).
     * Exhaustive with no `else`, like [value].
     */
    public fun with(field: SongField, from: SongDraft): SongDraft = when (field) {
        SongField.TITLE -> copy(title = from.title)
        SongField.ARTIST -> copy(artistName = from.artistName, artistId = from.artistId)
        SongField.REFERENCE_RECORDING -> copy(referenceRecording = from.referenceRecording)
        SongField.KEY_SIGNATURE -> copy(keySignature = from.keySignature)
        SongField.TONAL_CENTRE -> copy(tonalCentre = from.tonalCentre)
        SongField.TONALITY_NOTE -> copy(tonalityNote = from.tonalityNote)
        SongField.TEMPO_BPM -> copy(tempoBpm = from.tempoBpm)
        SongField.DURATION_SECONDS -> copy(durationSeconds = from.durationSeconds)
        SongField.DECADE -> copy(decade = from.decade)
        SongField.LOOP_LENGTH -> copy(loopLength = from.loopLength)
        SongField.CHORD_COUNT -> copy(chordCount = from.chordCount)
        SongField.CHORD_PATTERN -> copy(chordPattern = from.chordPattern)
        SongField.GROOVE -> copy(grooveName = from.grooveName, grooveId = from.grooveId)
        SongField.MASHUP_NOTE -> copy(mashupNote = from.mashupNote)
        SongField.NOTES -> copy(notes = from.notes)
        SongField.CHART_URL -> copy(chartUrl = from.chartUrl)
    }

    private fun integerFields(): List<Pair<SongField, String>> = listOf(
        SongField.TEMPO_BPM to tempoBpm,
        SongField.DURATION_SECONDS to durationSeconds,
        SongField.DECADE to decade,
        SongField.LOOP_LENGTH to loopLength,
        SongField.CHORD_COUNT to chordCount,
    )

    public companion object {

        /** R13: the key signature picker's values, flats first. Label with [Keys.keySignatureLabel]. */
        public val KEY_SIGNATURE_CHOICES: List<Int> = Keys.KEY_SIGNATURE_RANGE.toList()

        /** R13: the tonal centre picker's values, C first. Label with [Keys.noteName]. */
        public val TONAL_CENTRE_CHOICES: List<Int> = Keys.TONAL_CENTRE_RANGE.toList()

        public const val REQUIRED: String = "Required"
        public const val NOT_A_NUMBER: String = "Must be a whole number"
        public const val KEY_SIGNATURE_OUT_OF_RANGE: String = "Key signature is −7 to 7"
        public const val TONAL_CENTRE_OUT_OF_RANGE: String = "Tonal centre is 0 to 11"

        /** The stored row as a draft — the detail's starting state and its "unchanged" state. */
        public fun from(record: SongCatalog.SongRecord): SongDraft = SongDraft(
            title = record.title,
            artistName = record.artistName,
            artistId = record.artistId,
            referenceRecording = record.referenceRecording.orEmpty(),
            keySignature = record.keySignature?.toInt(),
            tonalCentre = record.tonalCentre?.toInt(),
            tonalityNote = record.tonalityNote.orEmpty(),
            tempoBpm = record.tempoBpm?.toString().orEmpty(),
            durationSeconds = record.durationSeconds?.toString().orEmpty(),
            decade = record.decade?.toString().orEmpty(),
            loopLength = record.loopLength?.toString().orEmpty(),
            chordCount = record.chordCount?.toString().orEmpty(),
            chordPattern = record.chordPattern.orEmpty(),
            grooveName = record.grooveName.orEmpty(),
            grooveId = record.grooveId,
            mashupNote = record.mashupNote.orEmpty(),
            notes = record.notes.orEmpty(),
            chartUrl = record.chartUrl.orEmpty(),
        )

        private fun String.orNull(): String? = trim().takeIf { it.isNotEmpty() }

        private fun String.toLongOrNullWhenBlank(): Long? = orNull()?.toLong()
    }
}

/**
 * The editable `song` columns, for per-field errors and labels (R12). Labels follow
 * data-model decisions 28-40; where a column's unit is not settled there, the label states
 * only the column (`Loop length`, `Decade`) rather than guessing one.
 */
public enum class SongField(public val label: String) {
    TITLE("Title"),
    ARTIST("Artist"),
    REFERENCE_RECORDING("Reference recording"),
    KEY_SIGNATURE("Key signature"),
    TONAL_CENTRE("Tonal centre"),
    TONALITY_NOTE("Tonality note"),
    TEMPO_BPM("Tempo (BPM)"),
    DURATION_SECONDS("Duration (seconds)"),
    DECADE("Decade"),
    LOOP_LENGTH("Loop length"),
    CHORD_COUNT("Chord count"),
    CHORD_PATTERN("Chord pattern"),
    GROOVE("Groove"),
    MASHUP_NOTE("Mashup note"),
    NOTES("Notes"),
    CHART_URL("Chart URL"),
}

/** [SongDraft.validate]'s answer: the values to write, or why they cannot be written. */
public sealed interface SongDraftValidation {
    public data class Valid(val fields: SongCatalog.SongFields) : SongDraftValidation
    public data class Invalid(val errors: Map<SongField, String>) : SongDraftValidation
}
