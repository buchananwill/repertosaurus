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
