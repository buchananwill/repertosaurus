package dev.repertosaurus.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.repertosaurus.core.ColourRamp
import dev.repertosaurus.core.NoteSpelling
import dev.repertosaurus.habit.HabitScope
import dev.repertosaurus.session.DevicePreferences
import dev.repertosaurus.session.SuggestTuning
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The app-wide display preferences as live state (style review F9 B1): the drawer sets them and
 * every screen reads them. `RepertosaurusApp` takes this directly; no screen's ViewModel holds them.
 */
public class DeviceSettings(
    private val preferences: DevicePreferences,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val writes = Mutex()

    private val spelling = Remembered(preferences.noteSpelling(), preferences::rememberNoteSpelling)
    public val noteSpelling: StateFlow<NoteSpelling> = spelling.flow
    public fun setNoteSpelling(value: NoteSpelling): Unit = spelling.set(value)

    private val ramp = Remembered(preferences.colourRamp(), preferences::rememberColourRamp)
    public val colourRamp: StateFlow<ColourRamp> = ramp.flow
    public fun setColourRamp(value: ColourRamp): Unit = ramp.set(value)

    // Triage T10: set by onboarding's performer step (OB2) and by the drawer's "Who you are" (OB6).
    private val owner = Remembered(preferences.ownerPerformer(), preferences::rememberOwnerPerformer)
    public val ownerPerformer: StateFlow<String?> = owner.flow
    public fun setOwnerPerformer(value: String?): Unit = owner.set(value)

    // Suggest SG11, SG15: the radar sets it on a handle's release and on "Shuffle".
    private val tuning = Remembered(preferences.suggestTuning(), preferences::rememberSuggestTuning)
    public val suggestTuning: StateFlow<SuggestTuning> = tuning.flow
    public fun setSuggestTuning(value: SuggestTuning): Unit = tuning.set(value)

    // Scorecards SC4: the scorecards' scope toggle sets it.
    private val scope = Remembered(preferences.habitScope(), preferences::rememberHabitScope)
    public val habitScope: StateFlow<HabitScope> = scope.flow
    public fun setHabitScope(value: HabitScope): Unit = scope.set(value)

    // Onboarding OB1, OB3: only ever set true, so the write ignores anything else. OB4: onboarding
    // marks it after every answer has been set.
    private val onboarded = Remembered(preferences.onboardingDone()) { done -> if (done) preferences.markOnboardingDone() }
    public val onboardingDone: StateFlow<Boolean> = onboarded.flow
    public fun markOnboardingDone(): Unit = onboarded.set(true)

    /**
     * One preference: read once at construction, shown at once on a set, and written on [io] behind
     * it. **Writes are serialised and each writes the latest value** (safety review P3 N5), so two
     * quick sets cannot land on disk in the wrong order.
     */
    private inner class Remembered<T>(initial: T, private val write: (T) -> Unit) {
        private val state = MutableStateFlow(initial)
        val flow: StateFlow<T> = state.asStateFlow()

        fun set(value: T) {
            if (state.value == value) return
            state.value = value
            viewModelScope.launch(io) { writes.withLock { write(state.value) } }
        }
    }

    public companion object {
        public fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { DeviceSettings(graph.devicePreferences) }
        }
    }
}
