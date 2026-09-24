package dev.repertosaurus.habit

import dev.repertosaurus.session.Messages
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** scorecards §3: [HabitStats] on the JVM, today injected (SC13). Every expected value is a literal. */
class HabitStatsTest {

    private fun date(iso: String): LocalDate = LocalDate.parse(iso)

    private fun build(counts: Map<String, Long>, today: String, instrumentId: String? = null): HabitCard =
        HabitStats.build(counts, date(today), instrumentId)

    // ---- SC5: the grid ----------------------------------------------------------------------

    /** Today is Wednesday 7 January 2026: the grid runs from Monday 14 July 2025, across the year. */
    @Test
    fun theGridIs26MondayFirstWeeksAcrossAYearBoundary() {
        val card = build(emptyMap(), "2026-01-07")

        assertEquals(26, card.weeks.size)
        assertEquals("2025-07-14", card.weeks.first().monday)
        assertEquals("2026-01-05", card.weeks.last().monday)
        for ((i, week) in card.weeks.withIndex()) {
            assertEquals(DayOfWeek.MONDAY, date(week.monday).dayOfWeek, "week $i starts on a Monday")
            assertEquals(week.monday, week.days.first().date)
            if (i > 0) assertEquals(7, date(week.monday).toEpochDays() - date(card.weeks[i - 1].monday).toEpochDays())
        }
        for (week in card.weeks.dropLast(1)) assertEquals(7, week.days.size)
        assertEquals(
            listOf("2025-12-29", "2025-12-30", "2025-12-31", "2026-01-01", "2026-01-02", "2026-01-03", "2026-01-04"),
            card.weeks.single { it.monday == "2025-12-29" }.days.map { it.date },
        )
        assertEquals(
            listOf("2025-09-29", "2025-09-30", "2025-10-01", "2025-10-02", "2025-10-03", "2025-10-04", "2025-10-05"),
            card.weeks.single { it.monday == "2025-09-29" }.days.map { it.date },
        )
    }

    @Test
    fun theCurrentWeekStopsAtTodayAndOnlyTodayIsMarked() {
        val card = build(emptyMap(), "2026-01-07")

        assertEquals(listOf("2026-01-05", "2026-01-06", "2026-01-07"), card.weeks.last().days.map { it.date })
        assertEquals(listOf("2026-01-07"), card.weeks.flatMap { it.days }.filter { it.isToday }.map { it.date })
        assertNull(card.day("2026-01-08"), "a future day is not drawn")
    }

    @Test
    fun onASundayTheLastWeekIsComplete() {
        val card = build(emptyMap(), "2026-09-27")

        assertEquals(7, card.weeks.last().days.size)
        assertTrue(card.weeks.last().days.last().isToday)
    }

    /** A leap year: 2028-02-29 is a cell, and February's tally is out of 29. */
    @Test
    fun aLeapDayIsACellAndFebruaryHas29Days() {
        val card = build(mapOf("2028-02-29" to 2L, "2028-02-01" to 1L), "2028-03-01")

        assertEquals(listOf("2028-02-28", "2028-02-29", "2028-03-01"), card.weeks.last().days.map { it.date })
        assertEquals(2L, card.day("2028-02-29")?.count)
        assertEquals(PeriodTally(start = "2028-02-01", days = 2, of = 29), card.recentMonths.last())
    }

    // ---- SC6: the buckets -------------------------------------------------------------------

    @Test
    fun theBucketBoundariesAre0_1_2to3_4to7_8Plus() {
        val expected = mapOf(
            0L to HabitBucket.NONE, 1L to HabitBucket.ONE, 2L to HabitBucket.FEW, 3L to HabitBucket.FEW,
            4L to HabitBucket.SEVERAL, 7L to HabitBucket.SEVERAL, 8L to HabitBucket.MANY, 40L to HabitBucket.MANY,
        )
        for ((count, bucket) in expected) assertEquals(bucket, HabitBucket.of(count), "count $count")
    }

    @Test
    fun theGridCellsCarryTheirBuckets() {
        val counts = mapOf(
            "2026-09-14" to 1L, "2026-09-15" to 2L, "2026-09-16" to 3L, "2026-09-17" to 4L,
            "2026-09-18" to 7L, "2026-09-19" to 8L,
        )
        val card = build(counts, "2026-09-24")

        assertEquals(
            listOf(HabitBucket.ONE, HabitBucket.FEW, HabitBucket.FEW, HabitBucket.SEVERAL, HabitBucket.SEVERAL, HabitBucket.MANY),
            counts.keys.map { card.day(it)!!.bucket },
        )
        assertEquals(HabitDay("2026-09-20", 0L, isToday = false), card.day("2026-09-20"))
        assertEquals(HabitBucket.NONE, card.day("2026-09-20")!!.bucket)
    }

    /** F24 B5: "no later than today" is enforced here; a key that is not a date is skipped (S8). */
    @Test
    fun futureAndUnreadableDaysAreIgnored() {
        val card = build(mapOf("2026-09-25" to 3L, "2026-13-45" to 2L), "2026-09-24")

        assertTrue(card.empty)
        assertEquals(0L, card.thisWeek.events)
    }

    // ---- SC7 --------------------------------------------------------------------------------

    @Test
    fun theDayLineNamesTheDayAndItsCount() {
        assertEquals("Tue 9 Sep: 5 songs", Messages.habitDay(HabitDay("2025-09-09", 5L, isToday = false)))
        assertEquals("Tue 9 Sep: 1 song", Messages.habitDay(HabitDay("2025-09-09", 1L, isToday = false)))
        assertEquals("Tue 9 Sep: nothing logged", Messages.habitDay(HabitDay("2025-09-09", 0L, isToday = false)))
    }

    // ---- SC8, SC9 ---------------------------------------------------------------------------

    /** SC8: the first event is Thursday 10 September; Thursdays are 10, 17, 24; Fridays 11, 18. */
    @Test
    fun reliabilityIsClippedToTheFirstEvent() {
        val card = build(mapOf("2026-09-10" to 1L, "2026-09-17" to 2L, "2026-09-14" to 1L), "2026-09-24")
        val byDay = card.reliability.associateBy { it.isoDay }

        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), card.reliability.map { it.isoDay })
        assertEquals(WeekdayReliability(isoDay = 4, practised = 2, of = 3), byDay.getValue(4))
        assertEquals(WeekdayReliability(isoDay = 5, practised = 0, of = 2), byDay.getValue(5))
        assertEquals(WeekdayReliability(isoDay = 1, practised = 1, of = 2), byDay.getValue(1))
        assertEquals("Thursdays: 2 of 3", Messages.habitReliability(byDay.getValue(4)))
    }

    @Test
    fun anEarlyFirstEventLeavesTheWholeWindow() {
        val card = build(mapOf("2024-01-01" to 1L, "2026-09-24" to 1L), "2026-09-24")

        assertEquals(listOf(26, 26, 26, 26, 25, 25, 25), card.reliability.map { it.of })
        assertEquals(1, card.reliability.single { it.isoDay == 4 }.practised)
    }

    /** SC8's one-day window: the first event is today, so only today's weekday has been reached. */
    @Test
    fun aOneDayWindowReachesOnlyTodaysWeekday() {
        val card = build(mapOf("2026-09-24" to 1L), "2026-09-24")

        assertEquals(
            listOf(0 to 0, 0 to 0, 0 to 0, 1 to 1, 0 to 0, 0 to 0, 0 to 0),
            card.reliability.map { it.practised to it.of },
        )
        assertEquals("Mondays: not yet", Messages.habitReliability(card.reliability.first()))
        assertNull(card.strongestIsoDay, "one Thursday is not enough to name")
    }

    /** SC9 as tightened by F29: named only on four or more *practised* days, and only when it strictly leads. */
    @Test
    fun aLeaderIsNamedOnlyOnFourPractisedDaysAndOnlyWhenItStrictlyLeads() {
        // Window from Tuesday 1 September: Tuesdays 1, 8, 15, 22 (4 of 4); Thursdays 3, 10, 17, 24 (1 of 4).
        val named = build(
            mapOf("2026-09-01" to 1L, "2026-09-08" to 1L, "2026-09-15" to 1L, "2026-09-22" to 1L, "2026-09-03" to 1L),
            "2026-09-24",
        )
        assertEquals(2, named.strongestIsoDay)
        assertEquals("You show up most on Tuesdays", Messages.habitStrongest(2))

        // Three practised Tuesdays in a three-Tuesday window: a perfect share, but too few days to name.
        val tooFew = build(mapOf("2026-09-08" to 1L, "2026-09-15" to 1L, "2026-09-22" to 1L, "2026-09-10" to 1L), "2026-09-24")
        assertEquals(WeekdayReliability(isoDay = 2, practised = 3, of = 3), tooFew.reliability.single { it.isoDay == 2 })
        assertNull(tooFew.strongestIsoDay)

        // Four Tuesdays and four Thursdays, all practised: a tie names nobody.
        val tied = build(
            listOf("01", "08", "15", "22", "03", "10", "17", "24").associate { "2026-09-$it" to 1L },
            "2026-09-24",
        )
        assertNull(tied.strongestIsoDay)
    }

    /**
     * F29: the shape the device screenshot showed. A whole 26-week window (history from 2021), where
     * Tuesday leads on 3 of 26 and Monday follows on 2 of 26: the window holds 26 Tuesdays, which the
     * F23 N3 rule would have named, but three practised days is too few, so nobody is named.
     */
    @Test
    fun threeOfTwentySixNamesNobody() {
        val counts = mapOf(
            "2021-10-19" to 1L,
            "2026-05-12" to 7L, "2026-05-19" to 1L, "2026-06-02" to 2L, // Tuesdays
            "2026-05-11" to 1L, "2026-06-08" to 1L, // Mondays
        )
        val card = build(counts, "2026-09-24")

        assertEquals(WeekdayReliability(isoDay = 2, practised = 3, of = 26), card.reliability.single { it.isoDay == 2 })
        assertEquals(WeekdayReliability(isoDay = 1, practised = 2, of = 26), card.reliability.single { it.isoDay == 1 })
        assertNull(card.strongestIsoDay)

        // A fourth practised Tuesday names it.
        assertEquals(2, build(counts + ("2026-06-09" to 1L), "2026-09-24").strongestIsoDay)
    }

    // ---- SC10, SC11 -------------------------------------------------------------------------

    /** Today is Thursday 24 September 2026. The tallies are the completed periods before this one. */
    @Test
    fun theWeekMonthAndRecentSummaries() {
        val counts = mapOf(
            "2026-09-24" to 2L, "2026-09-21" to 3L, "2026-09-23" to 1L,
            "2026-09-15" to 1L, "2026-09-01" to 4L,
            "2026-08-31" to 2L,
            "2026-08-03" to 1L, "2026-08-04" to 1L, "2026-08-05" to 5L,
            "2026-03-01" to 1L, "2026-02-28" to 9L,
        )
        val card = build(counts, "2026-09-24")

        assertEquals(PeriodTotal(days = 3, events = 6L), card.thisWeek)
        assertEquals(PeriodTotal(days = 5, events = 11L), card.thisMonth)
        assertEquals("This week: 3 days, 6 songs", Messages.habitThisWeek(card.thisWeek))
        assertEquals("This month: 5 days, 11 songs", Messages.habitThisMonth(card.thisMonth))
        assertEquals("This week: 1 day, 1 song", Messages.habitThisWeek(PeriodTotal(1, 1L)))

        assertEquals(
            listOf(
                PeriodTally("2026-07-27", 0, 7), PeriodTally("2026-08-03", 3, 7), PeriodTally("2026-08-10", 0, 7),
                PeriodTally("2026-08-17", 0, 7), PeriodTally("2026-08-24", 0, 7), PeriodTally("2026-08-31", 2, 7),
                PeriodTally("2026-09-07", 0, 7), PeriodTally("2026-09-14", 1, 7),
            ),
            card.recentWeeks,
        )
        assertEquals(
            listOf(
                PeriodTally("2026-03-01", 1, 31), PeriodTally("2026-04-01", 0, 30), PeriodTally("2026-05-01", 0, 31),
                PeriodTally("2026-06-01", 0, 30), PeriodTally("2026-07-01", 0, 31), PeriodTally("2026-08-01", 4, 31),
            ),
            card.recentMonths,
        )
        assertFalse(card.empty)
    }

    @Test
    fun theRecentMonthsCrossAYearBoundary() {
        val card = build(mapOf("2025-12-31" to 1L, "2025-07-01" to 1L), "2026-01-07")

        assertEquals(
            listOf("2025-07-01", "2025-08-01", "2025-09-01", "2025-10-01", "2025-11-01", "2025-12-01"),
            card.recentMonths.map { it.start },
        )
        assertEquals(listOf(1, 0, 0, 0, 0, 1), card.recentMonths.map { it.days })
    }

    // ---- SC14 -------------------------------------------------------------------------------

    @Test
    fun theEmptyStateIsAnEmptyGridAndNoStatistics() {
        val card = build(emptyMap(), "2026-09-24")

        assertTrue(card.empty)
        assertEquals(26, card.weeks.size)
        assertTrue(card.weeks.flatMap { it.days }.all { it.bucket == HabitBucket.NONE })
        assertEquals(List(7) { 0 to 0 }, card.reliability.map { it.practised to it.of }, "no weekday reached")
        assertTrue(card.reliability.all { it.share == 0f })
        assertNull(card.strongestIsoDay)
        assertEquals("Your practice will fill this in", Messages.HABIT_EMPTY)
    }

    // ---- SC4 --------------------------------------------------------------------------------

    @Test
    fun theCardRecordsTheInstrumentItWasBuiltFor() {
        assertEquals("i-vocal", build(emptyMap(), "2026-09-24", instrumentId = "i-vocal").instrumentId)
        assertNull(build(emptyMap(), "2026-09-24").instrumentId)
    }

    @Test
    fun theScopeDefaultsToAllInstrumentsAndReadsBackByName() {
        assertEquals(HabitScope.ALL_INSTRUMENTS, HabitScope.DEFAULT)
        assertEquals(HabitScope.DEFAULT, HabitScope.fromStored(null))
        assertEquals(HabitScope.DEFAULT, HabitScope.fromStored("SOMETHING_LATER"))
        assertEquals(HabitScope.PRACTICE_INSTRUMENT, HabitScope.fromStored("PRACTICE_INSTRUMENT"))
    }

    /** D63's no-View fallback: the instrument scope with no practice instrument counts every instrument. */
    @Test
    fun theInstrumentScopeWithNoViewFallsBackToEveryInstrument() {
        assertEquals("i-vocal", HabitScope.PRACTICE_INSTRUMENT.instrumentId("i-vocal"))
        assertNull(HabitScope.PRACTICE_INSTRUMENT.instrumentId(null))
        assertNull(HabitScope.ALL_INSTRUMENTS.instrumentId("i-vocal"))
    }
}
