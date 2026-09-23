package dev.repertosaurus.session

import dev.repertosaurus.core.ColourRamp
import dev.repertosaurus.core.NoteSpelling

/**
 * **This device's display preferences, app-wide** (style review F9 B1): never data, never synced,
 * and not the Session screen's. Unset, each reads as its type's `DEFAULT`. A new device-wide
 * setting is one getter and one setter here.
 *
 * - note spelling: repertoire-editing R40-R42;
 * - colour ramp: rating-scale RS5, RS7;
 * - owner performer: triage T10.
 */
public interface DevicePreferences {
    public fun noteSpelling(): NoteSpelling
    public fun rememberNoteSpelling(spelling: NoteSpelling)
    public fun colourRamp(): ColourRamp
    public fun rememberColourRamp(ramp: ColourRamp)

    /** triage T10: who "me" is on this device, a performer id. Unset is null, and no default is guessed. */
    public fun ownerPerformerId(): String?

    /** T10: null clears it. */
    public fun rememberOwnerPerformer(performerId: String?)
}

/** For tests and previews. */
public class InMemoryDevicePreferences(
    private var noteSpelling: NoteSpelling = NoteSpelling.DEFAULT,
    private var colourRamp: ColourRamp = ColourRamp.DEFAULT,
    private var ownerPerformerId: String? = null,
) : DevicePreferences {
    override fun noteSpelling(): NoteSpelling = noteSpelling

    override fun rememberNoteSpelling(spelling: NoteSpelling) {
        this.noteSpelling = spelling
    }

    override fun colourRamp(): ColourRamp = colourRamp

    override fun rememberColourRamp(ramp: ColourRamp) {
        this.colourRamp = ramp
    }

    override fun ownerPerformerId(): String? = ownerPerformerId

    override fun rememberOwnerPerformer(performerId: String?) {
        this.ownerPerformerId = performerId
    }
}
