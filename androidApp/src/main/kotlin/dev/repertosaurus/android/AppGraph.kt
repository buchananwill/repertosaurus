package dev.repertosaurus.android

import android.content.Context
import dev.repertosaurus.core.ColourRamp
import dev.repertosaurus.core.Ids
import dev.repertosaurus.core.NoteSpelling
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.habit.HabitScope
import dev.repertosaurus.session.DevicePreferences
import dev.repertosaurus.session.SessionPreferences
import dev.repertosaurus.session.SuggestTuning

/**
 * The process-wide wiring. Small enough to be a hand-rolled object; a dependency-injection
 * framework here would be more machinery than the app has moving parts.
 *
 * The [DatabaseHolder] must be a singleton: import closes the database and swaps the file
 * underneath, and two holders would each keep a connection to a file that no longer exists.
 * It also outlives the Activity, so a rotation does not reopen SQLite.
 */
public class AppGraph private constructor(context: Context) {

    public val deviceId: String = deviceId(context)
    public val holder: DatabaseHolder = DatabaseHolder(context, deviceId)
    private val stored = AndroidSessionPreferences(context)
    public val preferences: SessionPreferences = stored
    public val devicePreferences: DevicePreferences = stored

    public companion object {
        private var instance: AppGraph? = null

        public fun of(context: Context): AppGraph = synchronized(this) {
            instance ?: AppGraph(context.applicationContext).also { instance = it }
        }

        /**
         * A per-install device id. Every table carries `device_id` for the merge total
         * order of decisions 11 and 12; a real one arrives with the sync layer in phase 2.
         */
        private fun deviceId(context: Context): String {
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            preferences.getString(KEY_DEVICE_ID, null)?.let { return it }
            val generated = Ids.random()
            preferences.edit().putString(KEY_DEVICE_ID, generated).apply()
            return generated
        }

        internal const val PREFERENCES = "repertosaurus"
        private const val KEY_DEVICE_ID = "device_id"
    }
}

/**
 * Everything this device remembers across launches, in one `SharedPreferences` file: the Session
 * screen's preferences and the app-wide display ones. The rules live in the shared core and are
 * tested there; this is only the storage. None of it is data, so none of it is ever synced.
 *
 * The home View is here and **not** an `is_home` column on `saved_view` (views V19). A flag
 * on many rows has no total order under last-write-wins — two devices each promoting a
 * different View both end up true and no later sync repairs it — and keeping it local lets
 * this phone and a desktop open on different Views, which is the behaviour we want.
 */
internal class AndroidSessionPreferences(
    context: Context,
    // A parameter only so the persistence test can write a file of its own, never the app's.
    fileName: String = AppGraph.PREFERENCES,
) : SessionPreferences, DevicePreferences {

    private val preferences =
        context.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    override fun lastInstrumentId(): String? = preferences.getString(KEY_INSTRUMENT, null)

    override fun rememberInstrument(instrumentId: String) {
        preferences.edit().putString(KEY_INSTRUMENT, instrumentId).apply()
    }

    override fun lastOrder(): String? = preferences.getString(KEY_ORDER, null)

    override fun rememberOrder(order: String) {
        preferences.edit().putString(KEY_ORDER, order).apply()
    }

    override fun homeViewId(): String? = preferences.getString(KEY_HOME_VIEW, null)

    override fun rememberHomeView(viewId: String) {
        preferences.edit().putString(KEY_HOME_VIEW, viewId).apply()
    }

    // R40-R42: stored by name; unset or unknown reads as the R41 default.
    override fun noteSpelling(): NoteSpelling =
        NoteSpelling.fromStored(storedString(KEY_NOTE_SPELLING))

    override fun rememberNoteSpelling(spelling: NoteSpelling) {
        preferences.edit().putString(KEY_NOTE_SPELLING, spelling.name).apply()
    }

    // Rating-scale RS7: stored by name, as the note spelling is.
    override fun colourRamp(): ColourRamp =
        ColourRamp.fromStored(storedString(KEY_COLOUR_RAMP))

    override fun rememberColourRamp(ramp: ColourRamp) {
        preferences.edit().putString(KEY_COLOUR_RAMP, ramp.name).apply()
    }

    // Triage T10: an id, as the home View's is (V19); null removes the key.
    override fun ownerPerformer(): String? = preferences.getString(KEY_OWNER_PERFORMER, null)

    override fun rememberOwnerPerformer(performerId: String?) {
        val edit = preferences.edit()
        if (performerId == null) edit.remove(KEY_OWNER_PERFORMER) else edit.putString(KEY_OWNER_PERFORMER, performerId)
        edit.apply()
    }

    // Suggest SG15: the core's encoding; anything unreadable reads as the default.
    override fun suggestTuning(): SuggestTuning = SuggestTuning.fromStored(storedString(KEY_SUGGEST_TUNING))

    override fun rememberSuggestTuning(tuning: SuggestTuning) {
        preferences.edit().putString(KEY_SUGGEST_TUNING, tuning.encode()).apply()
    }

    // Scorecards SC4: stored by name; anything unreadable reads as all instruments and never throws.
    override fun habitScope(): HabitScope = HabitScope.fromStored(storedString(KEY_HABIT_SCOPE))

    override fun rememberHabitScope(scope: HabitScope) {
        preferences.edit().putString(KEY_HABIT_SCOPE, scope.name).apply()
    }

    // Onboarding OB1: anything unreadable reads as not done.
    override fun onboardingDone(): Boolean = storedBoolean(KEY_ONBOARDING_DONE)

    override fun markOnboardingDone() {
        preferences.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
    }

    /**
     * A stored string for a `fromStored` reader, or null. **Never throws** (schema-compatibility S8):
     * a value of another type under the key reads as unset.
     */
    private fun storedString(key: String): String? = runCatching { preferences.getString(key, null) }.getOrNull()

    /** [storedString]'s Boolean sibling: unset, or a value of another type, reads as false. */
    private fun storedBoolean(key: String): Boolean = runCatching { preferences.getBoolean(key, false) }.getOrDefault(false)

    private companion object {
        const val KEY_INSTRUMENT = "last_instrument_id"
        const val KEY_ORDER = "last_order"
        const val KEY_HOME_VIEW = "home_view_id"
        const val KEY_NOTE_SPELLING = "note_spelling"
        const val KEY_COLOUR_RAMP = "colour_ramp"
        const val KEY_OWNER_PERFORMER = "owner_performer_id"
        const val KEY_SUGGEST_TUNING = "suggest_tuning"
        const val KEY_HABIT_SCOPE = "habit_scope"
        const val KEY_ONBOARDING_DONE = "onboarding_done"
    }
}
