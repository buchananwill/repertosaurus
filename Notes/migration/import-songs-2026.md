---
title: "Importing Songs 2026"
type: decision-spec
area: migration
status: active
date: 2026-08-15
---

# Importing Songs 2026

Phase 0. A throwaway desktop script, run once, that never ships inside the app.

Source: Google Sheets id `1WWeDvYJ5FMUFcZg5MYX6Fe7oLyYrrfnk5jqNTnz_flA`, owned by
`stampy.the.elephant@gmail.com`, shared with `willwritescode@gmail.com` as Editor. 57 tabs,
4,049 rows on export.

**The migration recovers history rather than starting from zero.** The workbook has been
quietly destroying practice history for four years by overwriting single-date cells; ~879
events are still recoverable, some surviving only because a stale set-list tab froze an old
value.

## Shape

Two passes with a human step between them.

1. **Extract** — read all 57 tabs, produce a single **review sheet** with flagged rows.
2. **Human resolution** — the user fixes flagged rows in Google Sheets. This is deliberate: a
   spreadsheet is the right tool for reviewing a few hundred flagged cells, and building a
   resolution UI for a one-time job would be waste.
3. **Build** — re-read the resolved sheet, emit the SQLite database for phase 1 import.

## Extraction Rules

### Artists

1. 288 distinct artist strings fold to 278 artists. Normalise by lowercasing, trimming,
   stripping a leading `The `, folding `&` to `and`, and removing punctuation.
2. Canonical `name` is the most-used spelling. Every losing spelling becomes an `artist_alias`
   row.
3. Known collisions to expect: `The Kaiser Chiefs`/`Kaiser Chiefs`, `Fratellis`/`The Fratellis`,
   `Walk The Moon`/`Walk the Moon`, `Florence and the Machine`/`Florence & the Machine`,
   `Kool and The Gang`/`Kool and the Gang`/`Kool & the Gang`, `The Beach Boys`/`Beach Boys`,
   `Human League`/`The Human League`.
4. **`SET 1` and `SET 2` appear in the `Artist` column as divider rows** across nine cells.
   Filter them before deduplication or the import creates two bands with those names.

### Songs

5. Union all tabs, normalise title, attach the artist FK, dedupe to ~460 records.
6. Prefer facts from the master tab; fall back to the most common value across other tabs.

### Keys

7. The `Key` column holds 58 distinct values doing five jobs. Classify, do not coerce:
   - **2,121 plain key names** (`E`, `D`, `Em`, `Bbm`, `Gb`) → `tonal_centre`, with quality
     preserved for the review sheet.
   - **20 transpositions written key-first** (`D (-2)`, `Ab (-7)`, `Em (-4)`) → tonal centre
     plus a signed offset.
   - **3 written offset-first** (`-7 (B)`, `+2 (G)`, `-3 (Cm)`) → same, note the reversed
     notation. **The two notations contradict each other** — `B (-7)` and `-7 (B)` cannot both
     be read the same way. Flag all 23 for human confirmation rather than guessing.
   - **2 "deliberately original" markers** (`Em (Orig)`, `Orig.`) → `transpose = 0`.
   - **15 unknowns** (`???` ×13, `??`, `E?`) → `tonal_centre` NULL, preserve the question in
     `tonality_note`. An honest blank beats a guess.
   - **44 non-keys** (`0` ×25, `2010` ×17, `TRACK`, `28/05/24`) → discard, log for review.
8. A transposition offset belongs on the **`setlist_item` of the tab it was written on**, not
   on the song.
9. `key_signature` is **NULL for every song on import.** Nothing in the workbook records one.
   Backfill later; it must not block phase 1. See decision 15 in
   [data-model.md](../decisions/data-model.md).
10. Suspect spellings to flag, not silently fix: `Bbb` (×2 — not a key), `Cb` (×7), `Fb` (×1).

### Practice events

11. Every dated cell in every practice column becomes one `practice_event`, with `discipline`
    read from the column name. Column names vary across tabs — `Vocal Practice`/`Vocal
    Practise`, `Bass Practice`/`Bass Practise`, `keys practise` — match case-insensitively on
    the instrument word.
12. The `Twitch` column is a practice record with `context = twitch`, not a discipline.
13. Expect ~879 unique `(song, discipline, date)` events. Distribution by year: 2021: 16,
    2022: 605, 2023: 149, 2024: 73, 2025: 13, 2026: 18.
14. Dates are `DD/MM/YY`. Flag rather than guess: ~50 cells hold `x`, `goal`, `R`, or dates
    with no year (`13/2`, `19/3`, `29/3`, `21/8`, `11/4`); typos land in 2002 (×6) and 2099
    (×10).

### Set lists

15. Each gig tab becomes a `setlist`. The tab's client name is usually in a header cell rather
    than a column name — e.g. `Mr and Mrs Allen`, `Seal Bay - Selsey`, `SO42 7QB - Mr & Mrs
    Anthony Horne`.
16. Row order becomes `position`. Where an `Order` column holds a decimal (`0.2`, `1.09`,
    `2.03`, `3.07`), split it: integer part → `set_no`, fractional part → `position`.
17. **`Order` is ambiguous across tabs** and this is not fully resolved. Some tabs use the
    set.position decimal; others hold values like `216`, `230`, `300` that look like duration
    in seconds. Disambiguate per tab by inspecting the value range, and flag any tab that
    cannot be classified confidently rather than importing it wrong.
18. Two tabs carry the client name `SO42 7QB - Mr & Mrs Anthony Horne`, 56 rows each, differing
    in five cells (`FD`/`LV`, and `Andy?`/`Andy` annotations). Import both, suffix the names,
    and flag for the user to delete one. Which was actually played is not recoverable.

## Output

19. The build pass emits a SQLite file matching [data-model.md](../decisions/data-model.md),
    loadable by the phase 1 Android app through its import path.
20. The same script should also be able to emit NDJSON in the sync layout, so that once phase 2
    lands the import is not a special path — it writes a `devices/import/` folder and merges
    like any other device.

## Non-Goals

- No conflict-resolution UI.
- No attempt to derive key signatures.
- No attempt to resolve the 19 genuine tempo disagreements automatically. Of 27 tempo
  conflicts, 5 are rounding (`147`/`150`), 3 are half-time notation (`100`/`200`), and 19 are
  real differences — several of which are probably deliberate, a party arrangement taken
  faster. These are **questions, not errors**; most will resolve to "both were right, for
  different gigs".
