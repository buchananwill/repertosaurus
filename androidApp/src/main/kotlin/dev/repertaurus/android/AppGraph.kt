package dev.repertaurus.android

import android.content.Context
import dev.repertaurus.core.Ids
import dev.repertaurus.data.DatabaseHolder
import dev.repertaurus.session.SessionPreferences

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
    public val preferences: SessionPreferences = AndroidSessionPreferences(context)

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

        internal const val PREFERENCES = "repertaurus"
        private const val KEY_DEVICE_ID = "device_id"
    }
}

/**
 * The instrument chip and the sort direction, remembered across launches. The rules live in
 * the shared core and are tested there; this is only the storage. Neither is data, so
 * neither is ever synced.
 */
private class AndroidSessionPreferences(context: Context) : SessionPreferences {

    private val preferences =
        context.getSharedPreferences(AppGraph.PREFERENCES, Context.MODE_PRIVATE)

    override fun lastInstrumentId(): String? = preferences.getString(KEY_INSTRUMENT, null)

    override fun rememberInstrument(instrumentId: String) {
        preferences.edit().putString(KEY_INSTRUMENT, instrumentId).apply()
    }

    override fun lastOrder(): String? = preferences.getString(KEY_ORDER, null)

    override fun rememberOrder(order: String) {
        preferences.edit().putString(KEY_ORDER, order).apply()
    }

    private companion object {
        const val KEY_INSTRUMENT = "last_instrument_id"
        const val KEY_ORDER = "last_order"
    }
}
