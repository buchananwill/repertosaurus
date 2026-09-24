---
title: "Scorecards — Decision Spec"
type: decision-spec
area: product
status: active
date: 2026-09-23
---

# Scorecards — Decision Spec

Roadmap package **P6** (Scorecards v1), with **P12** (v2, minutes) sketched in §4, in
[habit-arc-roadmap.md](../roadmap/habit-arc-roadmap.md). The vision's "Scorecards: seeing the
habit" is the brief:
- a contribution-graph calendar that makes an invisible habit visible;
- week and month summaries;
- an answer to the user's own question: **which days of the week are reliable, and which are
  not?**

**The tone is the design constraint.** "A gap is information, not a failure." The scorecards
should make a good week feel good and a bad week feel recoverable. This is the vision's obligation
test in its sharpest form, because a habit tracker is the feature most likely to become a nag.

Numbers are `SC<n>`, grouped by topic. They are **not** an execution order. **No schema change.**

## 1. Type Roster

**SQLDelight, changed**
- `practice_event.sq` gains one read, `countLiveByDay`: the live (not voided) events per
  `logged_on` ~~in a date range~~ **over all history** (AMENDED 2026-09-23, journal session
  11, F24 B5: SC8 needs the first event wherever it lies, and "no later than today" is enforced
  once, in `HabitStats`), optionally filtered to one instrument.

**Kotlin core, new** (`dev.repertosaurus.session` or a sibling `habit` package):
- `HabitStats`: pure. From per-day counts, a window and today, it builds:
  - the grid cells, with their buckets;
  - the week and month summaries;
  - weekday reliability.

**Android, new**
- `HabitScreen.kt` and its ViewModel: a new route.

**Android, changed**
- `RepertosaurusApp.kt`: the route is added to `Route` and to the drawer, among the
  `Route.EDITING` routes above "Advanced".

## 2. Decisions

### What is counted

**SC1.** **A practice day is a local date (`logged_on`) with at least one live event.** "Live"
means not voided, by the same left anti-join as every other read (data-model 8). This is the
roadmap's G7 default, adopted as an assumption the user may overturn.

**SC2.** **The day is `logged_on`, not `created_at`.** A back-dated log (data-model 45) counts on
the day it records. Days are the device's local calendar dates, which is what `logged_on` already
stores.

**SC3.** **Events on soft-deleted songs still count.** The practice happened, and deleting a song
later must not rewrite the musician's history of showing up. This deliberately differs from
editing.md E39. E39 excludes such events from an instrument's usage count because that count
states what removing the instrument would *hide*, which is a different question.

**SC4.** **Scope is a toggle with two states:** **all instruments** (the default, because the
habit is the musician's, not the guitar's) and **this View's practice instrument**. The screen
opens on all instruments. The choice is remembered ~~in `SessionPreferences`~~ **in
`DevicePreferences`** (AMENDED 2026-09-23: device preferences moved there, journal session 11,
F9 B1) as a display preference, never synced.

### The calendar grid

**SC5.** **The grid shows the last 26 weeks**, as weeks in columns (oldest on the left) and
weekdays in rows, **Monday first**. Twenty-six columns fit a 390 dp phone at about 12 dp a cell,
with 2 dp gaps. Today's cell is outlined. Future cells in the current week are not drawn.

**SC6.** **Cells are shaded by the count of live events that day, in five buckets:** 0, 1, 2–3,
4–7 and 8+.
- **Zero is a neutral empty cell: an outline, never red, never a warning colour.**
- The four non-zero buckets are **one hue at rising intensity**, the app's accent colour.
- **The rating ramp is not used here.** A ramp step 0 means "not at all" (red, in the default
  ramp), and a day with one log painted red would read as a failure. The vision's ramp rule
  covers "a 0–3 value or a heat". A count of sessions is neither.

The bucket boundaries are authored here, are provisional, and are tuned in P13a.

**SC7.** **Tapping a cell shows that day** in a line beneath the grid: "Tue 9 Sep: 5 songs".
It does not navigate. The grid is for looking, not for editing history (data-model 7).

### Weekday reliability

**SC8.** **For each weekday, reliability is the share of that weekday in the window that is a
practice day.** For example, "Tuesdays: 19 of 26". The window is the grid's 26 weeks, **clipped to
start no earlier than the first logged event**, so a new user is not measured against months
before they began.

**SC9.** **Reliability is shown as seven horizontal bars, Monday to Sunday, each with its
fraction written out.** No bar is red. The strongest day may be named ("You show up most on
Tuesdays"), ~~but only once the clipped window holds at least four of that weekday~~ **but only
once that weekday has at least four *practised* days in the window, and it strictly leads**
(AMENDED 2026-09-23, journal session 11, F23 N3, then tightened by F29. A leader on three
Tuesdays out of 26 still read as a strong claim on thin data). **The weakest day is shown, never called out.** The user asked which days are
unreliable, and the bars answer that plainly without the app saying it in words.

### Summaries

**SC10.** **This week and this month**: practice days so far and live events so far. For
example, "This week: 4 days, 23 songs".

**SC11.** **The last 8 weeks** as a compact row of "days practised out of 7", and **the last 6
months** as "days practised". Each is a plain number with a small bar in the accent colour.
**No comparison arrows and no "down from last month".** A drop is visible in the numbers
without the app pointing at it.

**SC12.** **There are no streaks.** No "current streak" and no "longest streak". A streak
counter is the canonical streak-loss guilt mechanism, and the vision rules it out ("Not a nag").
The grid already shows runs to anyone who wants to see them.

### Mechanics

**SC13.** **One SQL read, all arithmetic in the shared core.** `countLiveByDay` returns
`(logged_on, count)` for days with events, using only `GROUP BY` (SQLite 3.19; no window
functions). `HabitStats` fills zero days and does all bucketing, clipping and summarising, with
its tests on the JVM. "Today" is injected, as it is everywhere else in the core.

**SC14.** **The empty state** (no events yet) shows the empty grid and the line "Your practice
will fill this in". No zeros are printed as statistics.

**SC15.** **The screen reads only.** It writes nothing and adds nothing to the tap path.

## 3. Verification: what must be demonstrated

- Shared tests for `HabitStats`:
  - the SC5 grid shape across a month and a year boundary, Monday-first;
  - the SC6 bucket boundaries 0/1/2/3/4/7/8;
  - SC8 clipping to the first event;
  - SC10 and SC11 on a fixture;
  - the empty state.
- A repository test for `countLiveByDay` that proves each of these:
  - a voided event is excluded;
  - a soft-deleted song's event is included (SC3);
  - a back-dated event counts on its `logged_on`;
  - the instrument filter works.

  **Insert the rows out of date order.**
- **Mutation:** remove the anti-join from `countLiveByDay` and show the voided-event test fail.
- Instrumented tests: the route opens from the drawer, the scope toggle persists, and tapping a
  cell shows its line.
- Screenshots on the emulator against a database with **real history**: the migrated
  database at `.scratch/repertosaurus.db`. Copy it, and never modify it in place. It holds the
  workbook's history, not the user's month of phone logs. Quote the 26-week practice-day
  count shown next to a count from `sqlite3` over the same file.

## 3a. Scorecards v2: minutes (P12), added 2026-09-24 (journal session 11, D84)

**SC16. The grid keeps shading by the count of live events (SC6). It never shades by minutes.**
Most logs are one-tap and untimed (the timer is an alternative, never a replacement, per the
vision). A day of twelve untimed taps would shade paler than one timed ten-minute run, which
misreports the practice. **Untimed taps never count as zero minutes** (§4's standing rule), and
the only honest way to keep that promise in a colour is not to colour by minutes.

**SC17. Minutes are shown as supplementary facts wherever timed data exists, and are absent
otherwise:**
- the day line reads "Tue 9 Sep: 7 songs · 42 min timed" when that day has timed events, and
  otherwise stays as it is;
- SC10's week and month totals gain "· 1 h 10 min timed" when non-zero;
- the SC11 bars are unchanged (still days).

"Timed" is always said, so a musician never reads the minutes as the whole of their practice.

**SC18. The read gains a timed sum per day.** `countLiveByDay` also returns
`SUM(duration_seconds)` over the same live events. **A NULL sum (a day with no timed event)
means untimed, and is never coerced to 0** (journal F23 N5). `HabitStats` carries a per-day value
type `(events, timedSeconds: Long?)`, not a second map.

**SC19. The per-song history (the Songs route's song detail) shows each event's duration when it
has one**, and a "timed total" line when any exist. This is the vision's "history shows the
minutes accumulating on each song".
(AMENDED 2026-09-24, journal session 11, D85 #1: the song detail has no per-event list, only
R20's per-instrument summary. SC19 is met by a timed total and a list of **the timed events
only**, beneath that summary. A full per-event history is not decided.)

**SC20. Verification:**
- `HabitStats` tests: a mixed day, an all-untimed day (null, not 0), and totals;
- a repository test showing that a voided timed event contributes neither count nor minutes;
- the real-data cross-check again. The workbook has no durations, so it must show "timed" is
  absent;
- instrumented tests: the day line and the song history with and without durations.

## 4. Deferred

- **P12 (after the timer):** shade by minutes where timed data exists, and add "minutes this
  week". The design question P12 must answer is how a day with three timed logs and ten untimed
  taps is shaded. The fixed rule for now: **untimed taps never count as zero minutes.**
- A full-year view, and scrolling back past 26 weeks.
- Per-song history charts. The Songs route already lists a song's history.
