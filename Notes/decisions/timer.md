---
title: "Timed Practice — Decision Spec"
type: decision-spec
area: product
status: active
date: 2026-09-23
---

# Timed Practice — Decision Spec

Roadmap packages **P10** (this spec) and **P11** (the implementation), in
[habit-arc-roadmap.md](../roadmap/habit-arc-roadmap.md). The vision's "Timed practice: how much,
not only how often" is the brief. It asks for four things:
- **an alternative way to log, never a replacement for the tap**;
- a calm, single running clock that can be seen from across the room;
- a clock that is hard to forget running;
- a log that lands carrying its duration.

The data is already in place: `practice_event.duration_seconds` is 1–86400, and null means an
untimed tap ([schema-3.md](schema-3.md) M10–M11).

**The obligation test:** the timer is opt-in per use and it never nags. A notification demanding
attention is out (vision, "Not a nag"). A running timer never blocks a plain tap.

Numbers are `TM<n>`, grouped by topic. They are **not** an execution order.

## 1. Type Roster

**Kotlin core, new** (`dev.repertosaurus.session`):
- `PracticeTimer`: a pure state machine.
  - States: `Idle`, and `Running(songId, instrumentId, startedAtEpochMs)`.
  - Transitions: `start`, `stop`, `cancel`, `restore`.
  - `elapsed(now)`.
  - It uses an injected clock.
- `TimerStore`: an interface holding the one running timer (song, instrument, start). An
  in-memory fake is provided for tests.
- `StopOutcome`: `Timed(seconds)`, `Untimed` (too short) or `NeedsChoice(seconds)` (too long).

**Android, new**
- `AndroidTimerStore` (backed by `SharedPreferences`, the same file as the device preferences,
  under its own keys).
- `TimerBar.kt`: the running bar and the full-screen clock.

**Android, changed**
- `FeelSheet.kt` gains "Start timer".
- `SessionScreen.kt` hosts the bar.
- `SessionViewModel.kt` exposes the timer and routes stop to `logPractice(durationSeconds = …)`.
- The Suggest card (P5) gains "Time it", if P5 has landed.

## 2. Decisions

### Starting

**TM1. The timer starts from the long-press sheet.** It is a "Start timer" secondary button beside
Log, and **never on the plain tap** (roadmap G8 default). Once Suggest v1 exists, **the suggestion
card also offers "Time it"**, because a suggestion is a natural moment to start practising
properly.

**TM2. There is one timer at a time.** When one is running, the sheet's button reads **"Switch
timer here"**. One tap stops the running timer (by TM4–TM6) and starts the new one. There is no
dialog.

**TM3. The timer records the View's practice instrument at the moment it started,** and the log
lands on that instrument even if the View changes meanwhile.

### While running

**AMENDED 2026-09-24 (journal session 11, D83):**
- **while a timer runs, "Add song" demotes to a secondary button**, so Stop is the screen's one
  primary action (VI12);
- **the screen is kept on while the full-screen clock is open** (`FLAG_KEEP_SCREEN_ON` scoped to
  it). This is a display flag, not a notification, and it serves "visible from across the room"
  on a music stand;
- **the feel sheet opens fully expanded**, so "Log it" and the timer button are never below the
  fold.

**TM4. The running bar** sits directly under the session header, in the visual-identity style:
- the song title;
- a **large `mm:ss` clock** (display face), switching to `h:mm:ss` after an hour;
- a **Stop** primary button;
- a **Cancel** secondary button.

**Tapping the clock opens a full-screen clock**: huge digits, readable from across a room on a
music stand, with Stop and Cancel. Back returns to the list. The bar is present on the session
screen and **its presence is shown in the drawer** ("Timer running: <title>"), so it is hard to
forget from anywhere in the app.

**TM5. A running timer does not change the tap path.** A plain tap on any row, including the
timed song, still logs one untimed event at once.

**TM6. No notification and no background service.** The timer is a stored start instant, not a
ticking process. It costs no battery, and it needs no notification permission. The clock is
recomputed from the stored instant whenever it is shown. **This is the less imposing choice.** A
status-bar notification is left for the user to ask for (§4).

### Stopping

**TM7. Stop writes exactly one `practice_event`** for `(song, the stored instrument)`:
- `duration_seconds` is the elapsed whole seconds;
- `feel` is null;
- `logged_on` is the **local date the timer started** (a session that runs past midnight belongs
  to the day it began);
- the View's context applies as for a tap.

The standard undo snackbar reads "Logged <title> · 24 min", and undo voids the event as for a
tap.

**TM8. The boundaries of a stop:**
- **Under 10 seconds:** Stop logs the event **untimed** (`duration_seconds` null), with "Logged
  <title> (too short to time)". A mis-started timer still records that the song was touched,
  which is what Stop means.
- **Between 10 seconds and 3 hours:** a timed log.
- **Over 3 hours:** Stop asks the one question the app cannot answer, "Log 5 h 12 min, or log
  without a time?". It has two buttons and nothing else. This is the only dialog in the timer,
  and it appears only for a timer that was probably forgotten.
- The ceiling of 86400 seconds is schema-3 M10's CHECK. A timer older than a day is always a
  `NeedsChoice`, and "with a time" is then capped at 86400.

**TM9. Cancel writes nothing.** It shows "Timer cancelled · Undo". Undo, within the standard
window, restores the **same** running timer with its original start instant. So Cancel is as
cheap to take back as a tap.

### Persistence

**TM10. The running timer survives process death and reboots.** `TimerStore` holds the song, the
instrument and the start instant, **and is written at start**. On launch, a stored timer
restores the bar, with the elapsed time computed from the instant.
- A stored timer whose **song is soft-deleted** or merged away is dropped quietly, with a one-line
  message "A timer for a removed song was discarded".
- A stored timer whose **instrument was soft-deleted** logs to the fallback instrument, by the
  same rule as views.md V20a.

The timer is **device state, never synced.**

**TM11. Wall-clock time is used**, because the timer must survive a reboot. If the clock is
found to have gone backwards (a negative elapsed time), the elapsed time reads as zero, and Stop
therefore takes TM8's under-10-seconds branch. Nothing crashes (schema-compatibility S8).
- **A stored start instant later than now is rejected on read** as a corrupt value: the timer is
  dropped, as for a removed song. Otherwise a backwards clock would restore a timer dated in the
  future. (AMENDED 2026-09-24, journal session 11, F42 N3.)
- **When elapsed is negative at Stop, the event is dated today**, not the start instant's date,
  so a clock that jumped cannot write a `logged_on` in the future or in 1970. (F42 N3.)

**TM12. The date of a timed log is the start instant's date in the time zone current at Stop.**
The zone is read at Stop through an injected provider, never captured when the timer is built,
so a timed log and a plain tap at the same moment agree on the day. The store does not keep the
zone of the start. A musician who crosses a zone mid-timer may see the event land on the
neighbouring date. That is accepted, because the case is rare and TM10's store stays three
fields. (ADDED 2026-09-24, journal session 11, F42 B1.)

## 3. Verification

- **Shared:**
  - the `PracticeTimer` transitions with a fake clock: start, stop, cancel, restore and switch;
  - every TM8 boundary: 9 and 10 seconds, 3 hours ± 1 second, and over a day;
  - the TM7 date rule across midnight;
  - TM11's negative elapsed time;
  - TM10's handling of a removed song and a removed instrument.
- **Instrumented:**
  - start from the sheet, stop, and see exactly one event with a duration;
  - cancel and undo;
  - switch;
  - **a plain tap during a running timer logs untimed and leaves the timer running** (TM5);
  - the timer survives an activity recreation, and a process restart by re-reading the store;
  - the full-screen clock opens and closes.
- **Mutation:** break the 10-second boundary and show a test fail.
- Screenshots of the bar, the full-screen clock and the over-3-hours question.

## 4. Deferred

- A status-bar notification or a lock-screen clock (TM6). The user can ask for either. Each
  needs a foreground service and, on Android 13, a permission prompt.
- Timing several songs in one medley session.
- Editing a logged duration. Events are immutable (data-model 7), so the fix for a wrong duration
  is undo and a re-log.
