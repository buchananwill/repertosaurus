package dev.repertosaurus.habit

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * **The scorecards' arithmetic** (scorecards SC13): pure, from `countLiveByDay`'s per-day counts and
 * an injected today. Days are epoch-day numbers, so month and year boundaries are nothing special.
 */
public object HabitStats {
    /** SC5. */
    public const val GRID_WEEKS: Int = 26

    /** SC11. */
    public const val RECENT_WEEKS: Int = 8
    public const val RECENT_MONTHS: Int = 6

    /** SC9, as amended: practised days of the leading weekday (journal session 11, F23 N3, F29). */
    public const val STRONGEST_MIN_DAYS: Int = 4

    public const val DAYS_IN_WEEK: Int = 7

    /**
     * The whole card for [instrumentId]. A key after [today], with no events, or that is not a real
     * date is ignored: "no later than today" is enforced here and only here (F24 B5).
     */
    public fun build(countsByDay: Map<String, Long>, today: LocalDate, instrumentId: String?): HabitCard {
        val days = Days(liveCounts(countsByDay, today.toEpochDays()), today)
        val reliability = days.reliability()
        return HabitCard(
            instrumentId = instrumentId,
            weeks = days.grid(),
            thisWeek = days.total(days.thisMonday..days.today),
            thisMonth = days.total(days.firstOfMonth.toEpochDays()..days.today),
            recentWeeks = days.recentWeeks(),
            recentMonths = days.recentMonths(),
            reliability = reliability,
            strongestIsoDay = strongest(reliability),
            empty = days.counts.isEmpty(),
        )
    }

    /**
     * Positive counts by epoch day, today and before. A stored `logged_on` passes its `GLOB` check
     * without being a real date ("2026-13-45"), and such a row is skipped, never thrown on
     * (schema-compatibility S8).
     */
    private fun liveCounts(countsByDay: Map<String, Long>, today: Int): Map<Int, Long> {
        val counts = HashMap<Int, Long>()
        for ((date, events) in countsByDay) {
            val day = runCatching { LocalDate.parse(date) }.getOrNull()?.toEpochDays() ?: continue
            if (day <= today && events > 0L) counts[day] = (counts[day] ?: 0L) + events
        }
        return counts
    }

    /** SC9: the weekday with the highest share, if it strictly leads and was practised on enough days. */
    private fun strongest(reliability: List<WeekdayReliability>): Int? {
        val reached = reliability.filter { it.of > 0 }
        val best = reached.maxWithOrNull(SHARE) ?: return null
        val leads = reached.count { SHARE.compare(it, best) == 0 } == 1
        return best.isoDay.takeIf { leads && best.practised >= STRONGEST_MIN_DAYS }
    }

    /** practised / of, compared without division. Only for weekdays the window has reached. */
    private val SHARE = Comparator<WeekdayReliability> { a, b ->
        (a.practised.toLong() * b.of).compareTo(b.practised.toLong() * a.of)
    }

    /** The counts, and today's week and month, as epoch days. */
    private class Days(val counts: Map<Int, Long>, date: LocalDate) {
        val today: Int = date.toEpochDays()
        val thisMonday: Int = today - (date.dayOfWeek.isoDayNumber - 1)
        val firstOfMonth: LocalDate = LocalDate(date.year, date.monthNumber, 1)
        val gridStart: Int = thisMonday - DAYS_IN_WEEK * (GRID_WEEKS - 1)

        fun count(day: Int): Long = counts[day] ?: 0L

        fun practised(day: Int): Boolean = count(day) > 0L

        fun total(range: IntRange): PeriodTotal =
            PeriodTotal(days = range.count(::practised), events = range.sumOf(::count))

        /** SC5: Monday first, oldest week first, stopping at today. */
        fun grid(): List<HabitWeek> = (0 until GRID_WEEKS).map { week ->
            val monday = gridStart + DAYS_IN_WEEK * week
            val shown = monday..minOf(monday + DAYS_IN_WEEK - 1, today)
            HabitWeek(monday = iso(monday), days = shown.map { HabitDay(iso(it), count(it), isToday = it == today) })
        }

        /** SC11: the completed weeks before this one, oldest first. */
        fun recentWeeks(): List<PeriodTally> = (RECENT_WEEKS downTo 1).map { back ->
            tally(thisMonday - DAYS_IN_WEEK * back until thisMonday - DAYS_IN_WEEK * (back - 1))
        }

        /** SC11: the completed months before this one, oldest first. */
        fun recentMonths(): List<PeriodTally> = (RECENT_MONTHS downTo 1).map { back ->
            val start = firstOfMonth.minus(back, DateTimeUnit.MONTH)
            tally(start.toEpochDays() until start.plus(1, DateTimeUnit.MONTH).toEpochDays())
        }

        /** SC8: the grid's window up to today, clipped to start no earlier than the first live event. */
        fun reliability(): List<WeekdayReliability> {
            val window = maxOf(gridStart, counts.keys.minOrNull() ?: (today + 1))..today
            return (1..DAYS_IN_WEEK).map { isoDay ->
                val days = (0 until GRID_WEEKS).map { gridStart + DAYS_IN_WEEK * it + isoDay - 1 }.filter { it in window }
                WeekdayReliability(isoDay = isoDay, practised = days.count(::practised), of = days.size)
            }
        }

        private fun tally(range: IntRange): PeriodTally =
            PeriodTally(start = iso(range.first), days = range.count(::practised), of = range.last - range.first + 1)

        private fun iso(day: Int): String = LocalDate.fromEpochDays(day).toString()
    }
}
