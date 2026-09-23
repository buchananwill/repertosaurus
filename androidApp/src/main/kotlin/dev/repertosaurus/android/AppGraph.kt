package dev.repertosaurus.android

import android.content.Context
import dev.repertosaurus.core.ColourRamp
import dev.repertosaurus.core.Ids
import dev.repertosaurus.core.NoteSpelling
import dev.repertosaurus.data.DatabaseHolder
import dev.repertosaurus.session.DevicePreferences
import dev.repertosaurus.session.SessionPreferences

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
        NoteSpelling.fromStored(preferences.getString(KEY_NOTE_SPELLING, null))

    override fun rememberNoteSpelling(spelling: NoteSpelling) {
        preferences.edit().putString(KEY_NOTE_SPELLING, spelling.name).apply()
    }

    // Rating-scale RS7: stored by name, as the note spelling is.
    override fun colourRamp(): ColourRamp =
        ColourRamp.fromStored(preferences.getString(KEY_COLOUR_RAMP, null))

    override fun rememberColourRamp(ramp: ColourRamp) {
        preferences.edit().putString(KEY_COLOUR_RAMP, ramp.name).apply()
    }

    private companion object {
        const val KEY_INSTRUMENT = "last_instrument_id"
        const val KEY_ORDER = "last_order"
        const val KEY_HOME_VIEW = "home_view_id"
        const val KEY_NOTE_SPELLING = "note_spelling"
        const val KEY_COLOUR_RAMP = "colour_ramp"
    }
}
