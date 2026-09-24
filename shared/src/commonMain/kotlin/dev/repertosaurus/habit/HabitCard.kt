package dev.repertosaurus.habit

/** One cell of the scorecards SC5 grid. [date] is `YYYY-MM-DD`. */
public data class HabitDay(val date: String, val count: Long, val isToday: Boolean) {
    /** SC6. */
    public val bucket: HabitBucket get() = HabitBucket.of(count)
}

/** One SC5 column, Monday first. The current week stops at today: future cells are not drawn. */
public data class HabitWeek(val monday: String, val days: List<HabitDay>)

/** SC10: practice days and live events in a period so far. */
public data class PeriodTotal(val days: Int, val events: Long)

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
