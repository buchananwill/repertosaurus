package dev.repertosaurus.session

/**
 * timer TM10: **the one running timer, as device state, never synced.** It holds the song, the instrument and
 * the start instant, and is written at start, so the timer survives process death and a reboot.
 *
 * Both calls are blocking, for a caller off the main thread. [read] never throws: a stored value it cannot
 * read reads as no timer (schema-compatibility S8).
 */
public interface TimerStore {
    public fun read(): PracticeTimer.State.Running?

    /** Null clears the store. */
    public fun write(running: PracticeTimer.State.Running?)
}

/** For tests and previews. */
public class InMemoryTimerStore(private var stored: PracticeTimer.State.Running? = null) : TimerStore {
    override fun read(): PracticeTimer.State.Running? = stored

    override fun write(running: PracticeTimer.State.Running?) {
        stored = running
    }
}
