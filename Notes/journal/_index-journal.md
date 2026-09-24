---
title: "Journal Index"
type: commentary
area: journal
status: active
date: 2026-08-15
---

# Journal Index

The chronological thread of leadership, newest first. Entries are append-only and are never
edited to reflect later understanding — a decision that was later reversed stays written as it
was made, and the reversal is recorded where it happened.

- [2026-09-23 — Session 11: The Habit Arc Gets a Roadmap](2026-09-23-session-11.md)
  — [habit-arc-roadmap.md](../roadmap/habit-arc-roadmap.md) is written: 14 packages, 11 gates and
  a live status board, run by a frontier rule so the arc keeps moving without the user having to
  restart it. Schema 3 is the single keystone migration. Suggest v1, Scorecards v1 and the ramp
  primitive ship ahead of it.
  - Only two gates block work: G1 (the triage sort names, because they fix a CHECK, F1) and G10
    (beauty).
  - **The user then authorised the whole roadmap overnight (D51).** Delivered, reviewed three
    ways and committed:
    - schema 3 and the rating scale;
    - the approved "handed on" look, with motion;
    - the ratings editor;
    - Suggest v1;
    - Scorecards;
    - onboarding;
    - the triage sorts.
  - Last verified: 520 shared and 137 instrumented tests, 0 failures.
  - Read §4 first. It was rewritten overnight.

- [2026-09-23 — Session 10: From Logger to Nudger, the Habit Arc Opens](2026-09-23-session-10.md)
  — The user's five-point direction, recorded in their words: suggest a song, timed practice,
  habit scorecards, UI beauty, and a triage sort driven by two 0–3 ratings (priority, confidence).
  The handover to the new app is confirmed on the phone.
  - The vision [from-logger-to-nudger.md](../vision/from-logger-to-nudger.md) is written and
    `active`. The user corrected its premise from "tell them what to play" to "support a long-term
    routine; assist, never impose".
  - Locked: per-song–performer–role ratings, a radar-chart suggester, counted or discarded skips
    (append-only), and feel rescaled to 0–3.
  - The editing arc is committed as `8dc64d5` and pushed. No arc code yet.
  - Read §4 first.

- [2026-09-23 — Session 09: Repertoire Editing Arc Opens](2026-09-23-session-09.md)
  — After a month of daily use: "core database content is still inaccessible". The arc delivered
  Repertoire (performer → role → toggle every song), Songs (every editable field) and Artists
  routes, song merge (voids plus appends, never re-pointing the immutable log), and a note-spelling
  setting.
  - The baseline was red at bootstrap from CRLF checkout, fixed with `.gitattributes`.
  - A two-key-id defect (R4a) on upgraded databases was found in review before it reached the
    user's phone.
  - Measured at close: 386 shared + 75 instrumented tests, 0 failures. All uncommitted.
  - Read its §4 first.

- [2026-08-21 — Session 08: The Name Reverses Again: Repertosaurus](2026-08-21-session-08.md)
  — No feature work. `Repertaurus` became **Repertosaurus** — the bull/dinosaur etymology
  collision N4 accepted as a known tension kept bothering the user in daily use, and
  `Repertosaurus` removes it by construction. Clearance audit re-run clean, arguably cleaner than
  `Repertaurus`'s. Full code rename executed and verified: `songbook.dev` proven untouched by
  re-deriving all 1,356 real ids against it, a genuinely clean rebuild (172/172 tasks, 506 shared
  test results), and two independent reviews each catching the same stale cross-reference in the
  file built specifically to stop that class of drift.

- [2026-08-17 — Session 07: Editability, and the Fourth Id Fork](2026-08-17-session-07.md)
  — The database becomes editable from the phone: the capability editor, the performer roster and
  every lookup table reachable from the drawer. The fourth instance of the project's signature
  id-fork defect this week, and this time it had already corrupted real data. 253 shared + 35
  instrumented tests, 0 failures.

- [2026-08-16 — Session 06: Views, and the First Screen Anyone Has Ever Seen](2026-08-16-session-06.md)
  — Views land: a saved pairing of *which songs are eligible* with *which instrument the session
  is about*, proving the motivating case (231 songs, 138 never practised on guitar) three
  independent ways. Also the app's first run on an emulator, which crashed on launch from a
  property initialisation-order hazard no test could have caught, then crashed a second way on the
  user's actual phone from a missing schema-version contract — `schema-compatibility.md`'s S1–S13,
  with **S8** the rule worth carrying: the app must never hard-crash on any database state, in
  alpha as much as in release. 192 shared + 24 `androidApp` tests, 0 failures.

- [2026-08-16 — Session 05: The App Gets Its Name](2026-08-16-session-05.md)
  — No feature work. `Songbook` became **Repertaurus**, "Don't let your repertoire fossilize",
  with a dinosaur logo still undrawn. A three-lane naming spike and four clearance dossiers; one
  name chosen, committed and then reversed while still on an unmerged branch. The rename ran
  twice, and the second pass was better because the first's near-misses became instructions.
  Reusable technique: un-rename the diff mechanically and compare — 63 of 64 files byte-identical.
  `songbook.dev` survives as the UUID root namespace, permanently and deliberately.

- [2026-08-16 — Session 04: The App Runs on Real Data](2026-08-16-session-04.md)
  — Phase 1 functionally complete. Session screen, add-song with the artist type-ahead, sort
  toggle, drawer and instrument management; the build pass emitting a verified database of 479
  songs and 826 practice events, delivered to the user with an APK. Duets became the
  `setlist_item_performer` junction. A junction id fork that would have silently doubled every
  staging row was found by cross-checking the two implementations against each other and closed
  across 789 ids. 19 tables, 124 tests.

- [2026-08-15 — Session 03: Phase 0 Built and Corrected, Phase 1 Foundation Landed](2026-08-15-session-03.md)
  — First code. Schema (18 `.sq`), shared core (57 tests) and a building APK; the phase 0
  extract complete after three rounds of correction, with cell accounting closed to zero. The
  real workbook corrected the spec repeatedly — no song duration or set targets exist anywhere.
  Source corruption found and handled without repairing it. Read the three-review-workbook
  table before touching anything in `.scratch/`.

- [2026-08-15 — Session 02: Normalisation Pass and Adversarial Schema Review](2026-08-15-session-02.md)
  — `discipline` became `instrument`; `performer`, `song_instrument`, `tag`, `venue`, `groove`
  and `setlist_set` added; `tonal_centre` became an integer pitch class. An adversarial review
  then found 18 defects, ~15 accepted, including four that would have shipped: unmergeable
  duplicate lookup rows, an unimplementable undo, an ordering column that violated the spec's
  own sync invariant, and a migration that destroyed major/minor. Still no code.

- [2026-08-15 — Session 01: Diagnosis, Data Model, Stack, Delivery Order](2026-08-15-session-01.md)
  — Project established. Workbook analysed and the case for rebuilding evidenced. Data model,
  key/transposition model, Kotlin Multiplatform stack, Dropbox-first sync and the delivery
  order all settled. Four reversals recorded (Postgres → SQLite, PWA → native, Flutter → KMP,
  Drive → Dropbox). No code written.
