package dev.songbook.data

import dev.songbook.core.Ids
import dev.songbook.core.Timestamps
import dev.songbook.db.SongbookDatabase
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * A thin repository over the generated SQLDelight queries.
 *
 * Thin is the point: it owns id generation (decisions 2-6), the timestamp format
 * (decision 10) and the local-today default (decision 45), and nothing else. There is no
 * UI state here, no caching and no observation — that belongs a layer up.
 */
public class SongbookRepository(
    private val database: SongbookDatabase,
    private val deviceId: String,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
    private val newId: () -> String = { Ids.random() },
) {

    /**
     * One row of the coldest-first session list.
     *
     * [daysSince] is derived here rather than in SQL (decisions 48, 57), and is null for a
     * song never practised on this instrument — the rows the ordering deliberately puts
     * first.
     */
    public data class StaleSong(
        val songId: String,
        val title: String,
        val artistName: String?,
        val lastPractised: String?,
        val timesPractised: Long,
        val daysSince: Long?,
    )

    /** One row of the `instrument` lookup (decisions 15, 18). */
    public data class Instrument(val id: String, val name: String)

    /** The device's local today, `YYYY-MM-DD` (decision 45). */
    public fun today(): String = Timestamps.today(clock, timeZone)

    /**
     * Live instruments, for the Session screen's chip row. Read from the table, never a
     * hardcoded list: decision 15 says adding one must never require a code change.
     */
    public fun instruments(): List<Instrument> =
        database.instrumentQueries.selectAllLive().executeAsList()
            .map { Instrument(id = it.id, name = it.name) }

    /**
     * Staleness-ordered songs for one instrument: never-practised first, then coldest
     * first. Voided events are excluded (decision 8).
     */
    public fun songsByStaleness(
        instrumentId: String,
        today: String = today(),
    ): List<StaleSong> =
        database.songQueries.selectByStaleness(instrumentId).executeAsList().map { row ->
            StaleSong(
                songId = row.song_id,
                title = row.title,
                artistName = row.artist_name,
                lastPractised = row.last_practised,
                timesPractised = row.times_practised,
                daysSince = row.last_practised?.let { Timestamps.daysBetween(it, today) },
            )
        }

    /** The same ordering scoped to one set list. */
    public fun songsByStalenessInSetlist(
        setlistId: String,
        instrumentId: String,
        today: String = today(),
    ): List<StaleSong> =
        database.songQueries.selectByStalenessInSetlist(setlistId, instrumentId)
            .executeAsList().map { row ->
                StaleSong(
                    songId = row.song_id,
                    title = row.title,
                    artistName = row.artist_name,
                    lastPractised = row.last_practised,
                    timesPractised = row.times_practised,
                    daysSince = row.last_practised?.let { Timestamps.daysBetween(it, today) },
                )
            }

    /**
     * The primary tap path. One insert, no read first. The id is random (decision 6):
     * deriving it would collapse two legitimate same-day sessions into one row.
     *
     * [contextId] is nullable — a one-tap log must never require a second chip
     * (decision 45a). [feel] is the optional 1-3 long-press rating (decision 46).
     *
     * @return the id of the event just written, so the caller can offer undo.
     */
    public fun logPractice(
        songId: String,
        instrumentId: String,
        contextId: String? = null,
        feel: Long? = null,
        note: String? = null,
        loggedOn: String = today(),
    ): String {
        require(feel == null || feel in 1L..3L) { "feel is 1-3 or null, got $feel" }
        val id = newId()
        database.practice_eventQueries.insert(
            id = id,
            song_id = songId,
            logged_on = loggedOn,
            instrument_id = instrumentId,
            context_id = contextId,
            feel = feel,
            note = note,
            created_at = Timestamps.now(clock),
            device_id = deviceId,
        )
        return id
    }

    /**
     * Undo. Deleting a practice event is an **append**, not a mutation (decision 8): a row
     * in `practice_event_void` naming the voided event, which reads left-anti-join. A
     * union merge cannot suppress a row, so a mutation here would silently come back on
     * the next sync.
     *
     * @return the id of the void row.
     */
    public fun voidPractice(practiceEventId: String): String {
        val id = newId()
        database.practice_event_voidQueries.insert(
            id = id,
            practice_event_id = practiceEventId,
            created_at = Timestamps.now(clock),
            device_id = deviceId,
        )
        return id
    }

    /** The newest live event, the sensible undo target. */
    public fun mostRecentLiveEvent(): String? =
        database.practice_eventQueries.selectMostRecentLive().executeAsOneOrNull()?.id

    /** Live practice history for one song, voided events removed (decision 8). */
    public fun practiceHistory(songId: String): List<PracticeEntry> =
        database.practice_eventQueries.selectBySong(songId).executeAsList().map { row ->
            PracticeEntry(
                id = row.id,
                loggedOn = row.logged_on,
                instrumentId = row.instrument_id,
                instrumentName = row.instrument_name,
                contextName = row.context_name,
                feel = row.feel,
                note = row.note,
            )
        }

    public data class PracticeEntry(
        val id: String,
        val loggedOn: String,
        val instrumentId: String,
        val instrumentName: String?,
        val contextName: String?,
        val feel: Long?,
        val note: String?,
    )

    /** Live count for a song, derived and never stored (decision 48). */
    public fun timesPractised(songId: String): Long =
        database.practice_eventQueries.countLiveBySong(songId).executeAsOne()
}
