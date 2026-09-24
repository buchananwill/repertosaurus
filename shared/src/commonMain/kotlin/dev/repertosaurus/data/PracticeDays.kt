package dev.repertosaurus.data

import dev.repertosaurus.core.Timestamps
import dev.repertosaurus.db.RepertosaurusDatabase
import dev.repertosaurus.habit.DayTally
import dev.repertosaurus.habit.HabitCard
import dev.repertosaurus.habit.HabitStats
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** **The scorecards' one read** (scorecards SC1-SC4, SC13, SC18), reached as `RepertosaurusRepository.habit`. */
public class PracticeDays internal constructor(
    private val database: RepertosaurusDatabase,
    private val clock: Clock,
    private val timeZone: TimeZone,
) {

    /** The card for [instrumentId] (null: every instrument), as of the device's local today (decision 45). */
    public fun card(instrumentId: String?): HabitCard {
        val today = LocalDate.parse(Timestamps.today(clock, timeZone))
        return HabitStats.build(liveTalliesByDay(instrumentId), today, instrumentId)
    }

    /** `countLiveByDay`: each day's live events and timed sum, over all history. */
    internal fun liveTalliesByDay(instrumentId: String?): Map<String, DayTally> =
        database.practice_eventQueries.countLiveByDay(instrumentId)
            .executeAsList()
            .associate { it.logged_on to DayTally(events = it.events, timedSeconds = it.timed_seconds) }
}
