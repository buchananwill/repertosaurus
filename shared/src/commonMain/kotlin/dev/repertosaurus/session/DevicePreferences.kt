package dev.repertosaurus.session

import dev.repertosaurus.core.ColourRamp
import dev.repertosaurus.core.NoteSpelling
import dev.repertosaurus.habit.HabitScope

/**
 * **This device's display preferences, app-wide** (style review F9 B1): never data, never synced,
 * and not the Session screen's. Unset, each reads as its type's `DEFAULT`. A new device-wide
 * setting is one getter and one setter here, each citing its decision.
 */
public interface DevicePreferences {
    /** repertoire-editing R40-R42. */
    public fun noteSpelling(): NoteSpelling
    public fun rememberNoteSpelling(spelling: NoteSpelling)

    /** rating-scale RS5, RS7. */
    public fun colourRamp(): ColourRamp
    public fun rememberColourRamp(ramp: ColourRamp)

    /** triage T10: who "me" is on this device, a performer id. Unset is null, and no default is guessed. */
    public fun ownerPerformer(): String?

    /** T10: null clears it. */
    public fun rememberOwnerPerformer(performerId: String?)

    /** suggest SG15: unset or unreadable reads as [SuggestTuning.DEFAULT], and never throws. */
    public fun suggestTuning(): SuggestTuning
    public fun rememberSuggestTuning(tuning: SuggestTuning)

    /** scorecards SC4: unset or unknown reads as [HabitScope.DEFAULT], all instruments. */
    public fun habitScope(): HabitScope
    public fun rememberHabitScope(scope: HabitScope)

    /** onboarding OB1: unset is false, so every install, one with data on it included, sees it once. */
    public fun onboardingDone(): Boolean

    /** onboarding OB3: one way only. Nothing un-marks it, so it never reappears on its own. */
    public fun markOnboardingDone()
}

/** For tests and previews. */
public class InMemoryDevicePreferences(
    private var noteSpelling: NoteSpelling = NoteSpelling.DEFAULT,
    private var colourRamp: ColourRamp = ColourRamp.DEFAULT,
    private var ownerPerformer: String? = null,
    private var suggestTuning: SuggestTuning = SuggestTuning.DEFAULT,
    private var habitScope: HabitScope = HabitScope.DEFAULT,
    private var onboardingDone: Boolean = false,
) : DevicePreferences {
    override fun noteSpelling(): NoteSpelling = noteSpelling

    override fun rememberNoteSpelling(spelling: NoteSpelling) {
        this.noteSpelling = spelling
    }

    override fun colourRamp(): ColourRamp = colourRamp

    override fun rememberColourRamp(ramp: ColourRamp) {
        this.colourRamp = ramp
    }

    override fun ownerPerformer(): String? = ownerPerformer

    override fun rememberOwnerPerformer(performerId: String?) {
        this.ownerPerformer = performerId
    }

    override fun suggestTuning(): SuggestTuning = suggestTuning

    override fun rememberSuggestTuning(tuning: SuggestTuning) {
        this.suggestTuning = tuning
    }

    override fun habitScope(): HabitScope = habitScope

    override fun rememberHabitScope(scope: HabitScope) {
        this.habitScope = scope
    }

    override fun onboardingDone(): Boolean = onboardingDone

    override fun markOnboardingDone() {
        this.onboardingDone = true
    }
}
