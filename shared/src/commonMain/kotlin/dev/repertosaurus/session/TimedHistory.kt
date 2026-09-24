package dev.repertosaurus.session

import dev.repertosaurus.data.RepertosaurusRepository.PracticeEntry

/** scorecards SC19: one live timed event in a song's history. [instrumentName] is null for a removed instrument. */
public data class TimedEvent(val id: String, val loggedOn: String, val instrumentName: String?, val seconds: Long)

/** scorecards SC19: a song's live timed events, newest first. */
public data class TimedHistory(val events: List<TimedEvent>) {
    init {
        require(events.isNotEmpty()) { "an untimed song has no TimedHistory" }
    }

    /** Over every event, not only the [latest] shown. */
    val totalSeconds: Long get() = events.sumOf { it.seconds }

    /** Journal session 11 D85 #1: the song detail lists the newest [SHOWN] and counts the rest. */
    val latest: List<TimedEvent> get() = events.take(SHOWN)

    val olderCount: Int get() = (events.size - SHOWN).coerceAtLeast(0)

    public companion object {
        public const val SHOWN: Int = 10

        /** The timed events of [history], in its order (`selectBySong`: newest first), or null when none is timed. */
        public fun of(history: List<PracticeEntry>): TimedHistory? =
            history.mapNotNull { entry -> entry.durationSeconds?.let { TimedEvent(entry.id, entry.loggedOn, entry.instrumentName, it) } }
                .takeIf { it.isNotEmpty() }
                ?.let(::TimedHistory)
    }
}
