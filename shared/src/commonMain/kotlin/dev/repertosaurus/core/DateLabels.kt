package dev.repertosaurus.core

import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber

/**
 * **The weekday and month names, once** (journal session 11, F24 N1): English, short, as the rest of
 * the app is. The scorecards use them first; P12's per-song history reuses them.
 */
public object DateLabels {
    private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val WEEKDAYS_PLURAL = listOf("Mondays", "Tuesdays", "Wednesdays", "Thursdays", "Fridays", "Saturdays", "Sundays")
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** "Tue", for ISO day 1 (Monday) to 7 (Sunday). */
    public fun weekdayShort(isoDay: Int): String = WEEKDAYS[isoDay - 1]

    /** "Tuesdays". */
    public fun weekdayPlural(isoDay: Int): String = WEEKDAYS_PLURAL[isoDay - 1]

    /** "Sep", for month 1 to 12. */
    public fun monthShort(month: Int): String = MONTHS[month - 1]

    /** "Tue 9 Sep". */
    public fun day(date: LocalDate): String =
        "${weekdayShort(date.dayOfWeek.isoDayNumber)} ${date.dayOfMonth} ${monthShort(date.monthNumber)}"
}
