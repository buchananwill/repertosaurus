package dev.repertosaurus.habit

/**
 * **One day as `countLiveByDay` reads it** (scorecards SC18): its live [events], and the sum of their
 * `duration_seconds`. [timedSeconds] is **null when no live event that day was timed**: an untimed day
 * is untimed, never zero minutes (journal F23 N5), so it is never coerced to 0.
 */
public data class DayTally(val events: Long, val timedSeconds: Long?)

/**
 * The sum of two timed sums where null is "untimed": null only when both are (SC18). Timed seconds
 * add; an untimed side adds nothing, and is never read as zero.
 */
internal fun plusTimed(a: Long?, b: Long?): Long? = if (a == null) b else if (b == null) a else a + b

/**
 * One cell of the scorecards SC5 grid. [date] is `YYYY-MM-DD`. [timedSeconds] is supplementary
 * (SC17) and null on a day with no timed event (SC18); the shading reads [count] only (SC16).
 */
public data class HabitDay(val date: String, val count: Long, val isToday: Boolean, val timedSeconds: Long? = null) {
    /** SC6, and SC16: by the count of live events, **never by minutes**. */
    public val bucket: HabitBucket get() = HabitBucket.of(count)
}

/** One SC5 column, Monday first. The current week stops at today: future cells are not drawn. */
public data class HabitWeek(val monday: String, val days: List<HabitDay>)

/**
 * SC10: practice days and live events in a period so far. [timedSeconds] (SC17) is the period's timed
 * sum, and null when nothing in it was timed.
 */
public data class PeriodTotal(val days: Int, val events: Long, val timedSeconds: Long? = null)

/** SC11: a completed week or month from [start], practised on [days] of its [of] days. */
public data class PeriodTally(val start: String, val days: Int, val of: Int) {
    public val share: Float get() = share(days, of)
}

/** SC8: ISO weekday [isoDay] (1 is Monday) was practised on [practised] of the [of] such days in the window. */
public data class WeekdayReliability(val isoDay: Int, val practised: Int, val of: Int) {
    public val share: Float get() = share(practised, of)
}

private fun share(part: Int, whole: Int): Float = if (whole == 0) 0f else part.toFloat() / whole

/**
 * Everything the scorecards show, built by [HabitStats.build] for [instrumentId] (null: every
 * instrument). SC12: there is no streak here, and none is to be added.
 */
public data class HabitCard(
    val instrumentId: String?,
    val weeks: List<HabitWeek>,
    val thisWeek: PeriodTotal,
    val thisMonth: PeriodTotal,
    val recentWeeks: List<PeriodTally>,
    val recentMonths: List<PeriodTally>,
    val reliability: List<WeekdayReliability>,
    /** SC9, as amended: null unless one weekday strictly leads and was practised on at least four days. */
    val strongestIsoDay: Int?,
    /** SC14. */
    val empty: Boolean,
) {
    /** SC7: the grid's cell for [date], or null when the grid does not draw it. */
    public fun day(date: String): HabitDay? =
        weeks.firstNotNullOfOrNull { week -> week.days.firstOrNull { it.date == date } }
}
