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
