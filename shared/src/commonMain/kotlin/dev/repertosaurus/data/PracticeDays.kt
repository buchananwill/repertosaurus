package dev.repertosaurus.data

import dev.repertosaurus.core.Timestamps
import dev.repertosaurus.db.RepertosaurusDatabase
import dev.repertosaurus.habit.HabitCard
import dev.repertosaurus.habit.HabitStats
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** **The scorecards' one read** (scorecards SC1-SC4, SC13), reached as `RepertosaurusRepository.habit`. */
public class PracticeDays internal constructor(
    private val database: RepertosaurusDatabase,
    private val clock: Clock,
    private val timeZone: TimeZone,
) {

    /** The card for [instrumentId] (null: every instrument), as of the device's local today (decision 45). */
    public fun card(instrumentId: String?): HabitCard {
        val today = LocalDate.parse(Timestamps.today(clock, timeZone))
        return HabitStats.build(liveCountsByDay(instrumentId), today, instrumentId)
    }

    /** `countLiveByDay`: live events per `logged_on` over all history, for the days that have any. */
    internal fun liveCountsByDay(instrumentId: String?): Map<String, Long> =
        database.practice_eventQueries.countLiveByDay(instrumentId)
            .executeAsList()
            .associate { it.logged_on to it.events }
}
