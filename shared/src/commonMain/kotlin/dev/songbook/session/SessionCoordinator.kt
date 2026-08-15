package dev.songbook.session

import dev.songbook.core.Ids
import dev.songbook.core.normalise
import dev.songbook.data.SongbookRepository

/**
 * Where the Session screen touches the database. Every method here is **blocking** and is
 * meant to be called off the main thread; the pure, instant half of the screen is
 * [SessionState].
 *
 * The split is deliberate. A tap calls [newTap] (pure) and [SessionState.plusTap] (pure)
 * and the UI is already updated; [persist] happens afterwards on a background dispatcher.
 * Nothing on the tap path waits on SQLite.
 */
public class SessionCoordinator(
    private val repository: SongbookRepository,
    private val preferences: SessionPreferences,
    private val newTapId: () -> String = { Ids.random() },
) {

    /** The chip row, read from the `instrument` table (decisions 15, 18). */
    public fun instruments(): List<InstrumentChip> =
        SessionInstruments.chips(repository.instruments())

    /**
     * Which chip to open on: the one last used if it still exists, otherwise the first.
     * The resolved answer is written back, so a first launch is remembered too.
     */
    public fun initialInstrument(chips: List<InstrumentChip>): String? {
        val resolved = SessionInstruments.resolve(preferences.lastInstrumentId(), chips)
        if (resolved != null) preferences.rememberInstrument(resolved)
        return resolved
    }

    /** Remembered across launches — the app must not ask which discipline every session. */
    public fun rememberInstrument(instrumentId: String) {
        preferences.rememberInstrument(instrumentId)
    }

    /** Coldest first, never-practised leading, scoped to one instrument. */
    public fun rows(instrumentId: String): List<SessionRow> =
        repository.songsByStaleness(instrumentId).map { song ->
            SessionRow(
                songId = song.songId,
                title = song.title,
                artistName = song.artistName,
                daysSince = song.daysSince,
                timesPractised = song.timesPractised,
            )
        }

    /** Pure: allocates the local tap identity. No database contact. */
    public fun newTap(
        songId: String,
        instrumentId: String,
        feel: Long? = null,
        note: String? = null,
        loggedOn: String = repository.today(),
    ): SessionTap = SessionTap(
        tapId = newTapId(),
        songId = songId,
        instrumentId = instrumentId,
        feel = feel,
        note = note?.trim()?.takeIf { it.isNotEmpty() },
        loggedOn = loggedOn,
    )

    /** The insert. Returns the `practice_event` id, which is the undo's target. */
    public fun persist(tap: SessionTap): String = repository.logPractice(
        songId = tap.songId,
        instrumentId = tap.instrumentId,
        feel = tap.feel,
        note = tap.note,
        loggedOn = tap.loggedOn,
    )

    /** Undo: an append to `practice_event_void`, never a delete or an update (decision 8). */
    public fun voidEvent(practiceEventId: String) {
        repository.voidPractice(practiceEventId)
    }
}

/** The chip row's ordering and selection rules. */
public object SessionInstruments {

    /**
     * Decision 18's seed order — vocal, backing vocal, guitar, bass, keys — which is the
     * order the user thinks in, not alphabetical. It is a *display* preference only: an
     * instrument the user adds later is unknown to this list and lands after the seeded
     * five, alphabetically, so decision 15 still holds and adding a row needs no code
     * change. There is no ordering column in the schema and none is proposed here; adding
     * one would be a data-model change, not a screen decision.
     */
    private val SEEDED = listOf("vocal", "backing vocal", "guitar", "bass", "keys")

    public fun chips(instruments: List<SongbookRepository.Instrument>): List<InstrumentChip> =
        instruments
            .map { InstrumentChip(id = it.id, name = it.name) }
            .sortedWith(
                compareBy(
                    { chip ->
                        val rank = SEEDED.indexOf(normalise(chip.name))
                        if (rank < 0) SEEDED.size else rank
                    },
                    { chip -> chip.name },
                ),
            )

    /** The remembered chip if it still exists, otherwise the first one. */
    public fun resolve(remembered: String?, chips: List<InstrumentChip>): String? =
        chips.firstOrNull { it.id == remembered }?.id ?: chips.firstOrNull()?.id
}

/**
 * The one thing the Session screen remembers between launches (a phase 1 question in the
 * delivery spec: *should the app remember the discipline chip rather than asking each
 * session?* — this build says yes, and the fortnight of real use will confirm or refute it).
 *
 * An interface so the rule is testable without a device; Android backs it with
 * `SharedPreferences`.
 */
public interface SessionPreferences {
    public fun lastInstrumentId(): String?
    public fun rememberInstrument(instrumentId: String)
}

/** For tests and previews. */
public class InMemorySessionPreferences(private var value: String? = null) : SessionPreferences {
    override fun lastInstrumentId(): String? = value
    override fun rememberInstrument(instrumentId: String) {
        value = instrumentId
    }
}
