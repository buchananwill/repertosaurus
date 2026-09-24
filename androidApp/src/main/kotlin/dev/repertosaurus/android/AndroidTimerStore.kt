package dev.repertosaurus.android

import android.content.Context
import dev.repertosaurus.session.PracticeTimer
import dev.repertosaurus.session.TimerStore

/**
 * timer TM10: the running timer in the device preferences file, under keys of its own. Device state, never
 * synced. Written with `commit`, not `apply`, because the start must be on disk before a process death can
 * lose it; the caller is off the main thread.
 */
internal class AndroidTimerStore(
    context: Context,
    // A parameter only so a test can write a file of its own, never the app's.
    fileName: String = AppGraph.PREFERENCES,
) : TimerStore {

    private val preferences = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    /** S8: a missing, partial or mistyped value reads as no timer, never a throw. */
    override fun read(): PracticeTimer.State.Running? = runCatching {
        val songId = preferences.getString(KEY_SONG, null) ?: return null
        val instrumentId = preferences.getString(KEY_INSTRUMENT, null) ?: return null
        if (!preferences.contains(KEY_STARTED_AT)) return null
        PracticeTimer.State.Running(songId, instrumentId, preferences.getLong(KEY_STARTED_AT, 0L))
    }.getOrNull()

    override fun write(running: PracticeTimer.State.Running?) {
        val edit = preferences.edit()
        if (running == null) {
            edit.remove(KEY_SONG).remove(KEY_INSTRUMENT).remove(KEY_STARTED_AT)
        } else {
            edit.putString(KEY_SONG, running.songId)
                .putString(KEY_INSTRUMENT, running.instrumentId)
                .putLong(KEY_STARTED_AT, running.startedAtEpochMs)
        }
        check(edit.commit()) { "the timer was not written" }
    }

    private companion object {
        const val KEY_SONG = "timer_song_id"
        const val KEY_INSTRUMENT = "timer_instrument_id"
        const val KEY_STARTED_AT = "timer_started_at_epoch_ms"
    }
}
