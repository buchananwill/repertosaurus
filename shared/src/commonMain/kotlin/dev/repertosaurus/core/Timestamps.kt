package dev.repertosaurus.core

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Timestamp and date formatting (decision 10).
 *
 * Timestamps are ISO-8601 UTC in one fixed-width form: `YYYY-MM-DDTHH:MM:SS.sssZ`, always
 * three decimal places, always a literal `Z`. Variable precision breaks string comparison
 * — `...:00Z` sorts after `...:00.500Z` because `Z` > `.` — and string comparison is what
 * the merge order of decision 11 is built on. `Instant.toString()` drops a zero fraction,
 * so it must not be used for a stored value.
 *
 * Dates are `YYYY-MM-DD`. Every timestamp and date column carries a `GLOB` CHECK enforcing
 * exactly these shapes.
 */
public object Timestamps {

    public fun format(instant: Instant): String {
        val utc = instant.toLocalDateTime(TimeZone.UTC)
        return buildString(24) {
            append(pad(utc.year, 4))
            append('-')
            append(pad(utc.monthNumber, 2))
            append('-')
            append(pad(utc.dayOfMonth, 2))
            append('T')
            append(pad(utc.hour, 2))
            append(':')
            append(pad(utc.minute, 2))
            append(':')
            append(pad(utc.second, 2))
            append('.')
            append(pad(utc.nanosecond / 1_000_000, 3))
            append('Z')
        }
    }

    public fun now(clock: Clock = Clock.System): String = format(clock.now())

    /**
     * The device's **local** today, for `practice_event.logged_on` (decision 45). The
     * default is applied here and not by a SQL DEFAULT, which would be UTC and would
     * misdate a late-night session.
     */
    public fun today(
        clock: Clock = Clock.System,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): String = clock.now().toLocalDateTime(timeZone).date.toString()

    /** Whole days between two `YYYY-MM-DD` dates. Derived at read time (decision 48). */
    public fun daysBetween(from: String, to: String): Long =
        (LocalDate.parse(to).toEpochDays() - LocalDate.parse(from).toEpochDays()).toLong()

    private fun pad(value: Int, width: Int): String = value.toString().padStart(width, '0')
}
