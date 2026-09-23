package dev.repertosaurus.session

import dev.repertosaurus.core.ColourRamp
import dev.repertosaurus.core.NoteSpelling

/**
 * **This device's display preferences, app-wide** (style review F9 B1): never data, never synced,
 * and not the Session screen's. Unset, each reads as its type's `DEFAULT`. A new device-wide
 * setting is one getter and one setter here.
 *
 * - note spelling: repertoire-editing R40-R42;
 * - colour ramp: rating-scale RS5, RS7.
 */
public interface DevicePreferences {
    public fun noteSpelling(): NoteSpelling
    public fun rememberNoteSpelling(spelling: NoteSpelling)
    public fun colourRamp(): ColourRamp
    public fun rememberColourRamp(ramp: ColourRamp)
}

/** For tests and previews. */
public class InMemoryDevicePreferences(
    private var noteSpelling: NoteSpelling = NoteSpelling.DEFAULT,
    private var colourRamp: ColourRamp = ColourRamp.DEFAULT,
) : DevicePreferences {
    override fun noteSpelling(): NoteSpelling = noteSpelling

    override fun rememberNoteSpelling(spelling: NoteSpelling) {
        this.noteSpelling = spelling
    }

    override fun colourRamp(): ColourRamp = colourRamp

    override fun rememberColourRamp(ramp: ColourRamp) {
        this.colourRamp = ramp
    }
}
