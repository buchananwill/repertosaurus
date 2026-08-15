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
9. **Derive `key_signature` from the quality of every named key.** `Em` → `+1`, `E` → `+4`,
   `Bbm` → `−5`, `Bb` → `−2`. This is arithmetic, not musical analysis, and it applies to all
   2,121 plain-name cells. Without it every song imports with `key_signature NULL`, and since
   mode is implied by the `(key_signature, tonal_centre)` pair (decision 35), `E` and `Em`
   become the same row and major/minor is destroyed across the whole repertoire on day one.
10. Leave `key_signature` NULL only for the 15 genuine unknowns and for any song where the
    derived signature looks wrong against the chord content — flag those to the review sheet
    rather than asserting a signature. A bare `E` in the workbook may mean "we play it in E"
    rather than "E major", so the derivation is a strong default, not a certainty.
11. Retain the **source spelling** of every key alongside the derived pitch class. Converting
    `Gb` to the integer 6 and discarding the spelling loses information the app cannot
    reconstruct when `key_signature` is null.
12. Suspect spellings to flag, not silently fix: `Bbb` (×2 — not a key), `Cb` (×7), `Fb` (×1).

### Performers

Decisions 24–27 in [data-model.md](../decisions/data-model.md).

13. One `performer` row per singer column in the workbook. Name comes from the column header
    with the trailing instrument word removed: `Coralie Vox` → `Coralie`, `Sophie-Mae Vocal` →
    `Sophie-Mae`, `Will Vocal` → `Will`, `Kendra Piper` → `Kendra Piper`. The master sheet's
    bare `Lead vocal` column is the workbook owner; seed that performer as `Will`.
14. One `song_performer` row per non-empty cell in those columns, with `is_lead` set from
    whether the column is a lead or backing designation.
15. `vocal_range` comes from the `Range` column's `H`/`L`, mapped to `1`/`0`, attached to the
    owner's `song_performer` row — not to the song. With one performer this reproduces the
    workbook exactly.
16. Free-text performer annotations in set list tabs (`Andy`, `Andy?`, `FD`, `LV`, `Will B`)
    resolve to `performer` rows through the same normalisation as artists. `Andy?` and `Andy`
    must collapse to one performer — that pair is the reason this table exists. Flag any
    annotation that is not obviously a name (`FD`, `LV`) to the review sheet.

### Practice events

17. Every dated cell in every practice column becomes one `practice_event`, with `discipline`
    read from the column name. Column names vary across tabs — `Vocal Practice`/`Vocal
    Practise`, `Bass Practice`/`Bass Practise`, `keys practise` — match case-insensitively on
    the instrument word.
18. The `Twitch` column is a practice record with `context = twitch`, not a discipline.
19. Expect ~879 unique `(song, discipline, date)` events. Distribution by year: 2021: 16,
    2022: 605, 2023: 149, 2024: 73, 2025: 13, 2026: 18.
20. Dates are `DD/MM/YY`. Flag rather than guess: ~50 cells hold `x`, `goal`, `R`, or dates
    with no year (`13/2`, `19/3`, `29/3`, `21/8`, `11/4`); typos land in 2002 (×6) and 2099
    (×10).

### Set lists

21. **There are two sources of setlist identity and they carry different things.** The
    worksheet *name* carries a date and usually a venue or event; an in-sheet header row
    carries the client and sometimes an address or postcode. Use both. The worksheet names are
    only visible in the `.xlsx` — a CSV or markdown export discards them, which is why the
    `.xlsx` is mandatory.
22. **The date is the only part of a worksheet name that is safely parseable.** Extract
    `performed_on` from a `D-M-YY` fragment (`Blue Lion 6-9-25`, `29-4-23 MRS & Mrs Dale`,
    `2-4-22`), falling back to a bare year (`Jukefest 2025`, `Radiant Lanterns 2022`). This is
    the only source of gig dates anywhere in the workbook and fills a `setlist` column that
    previously had no source at all.
23. **Do not infer what the non-date remainder is.** It is heterogeneous and nothing in it is
    self-describing. It may be:
    - a **band** — `Blue Lion` is an act the user performs in, not a pub. Earlier gig sheets
      name others outright: *The Fleet*, *The Gifted*, *Three Lance*, *Jukebox Nation*.
    - a **venue** — plausibly `Chigwell School`, `St Lawrence`, and the holiday parks that
      appear in in-sheet headers (`Seal Bay - Selsey`, `Haven - Allhallows`).
    - an **event** — `Jukefest 2025`, `Radiant Lanterns 2022`.
    - a **configuration** — `Acoustic`, the `ACOUSTIC` suffix, `easier`,
      `70s-disco-oriented`, `Bass-vox Rep`.
    - a **client** — `MRS & Mrs Dale`, `Mr & Mrs Brinkley`, `Emma Munro-Faure & Dha…`.

    An early pass took `Blue Lion` for a venue and was wrong. Emit every distinct remainder to
    the review sheet with a proposed classification and a confidence, and let the human decide.
    **A wrong guess here silently corrupts `band_id` and `venue_id` for the whole history**, and
    unlike a key or a tempo there is no musical check that would catch it later.
24. In-sheet header rows are equally mixed — `Seal Bay - Selsey` (venue),
    `GU19 5PJ - Adam & Anna Scott` (postcode plus client), `SO42 7QB - Mr & Mrs Anthony Horne`
    (postcode plus client), `Kendra Piper` (a person), `50 Shades of Grant` (an act). Same
    treatment: propose, flag, do not assume.
25. **Excel truncates worksheet names to 31 characters.** At least two are cut off
    (`Filtered 24-8-24 70s-disco-orie`, `15-6-24 Emma Munro-Faure &  Dha`). Recover the full
    name from the in-sheet header where one exists; flag to the review sheet where it does not.
26. **Not every tab is a gig.** Classify before importing, and flag anything unclassifiable:
    - `Everything` — the master song list, not a setlist. Source for song facts.
    - `Sheet2` — junk, skip.
    - `Bass-vox Rep`, `LPT with Ryan` — repertoire views or working lists, not performances.
      Import as setlists only if they carry an ordered song list; otherwise skip and report.
    - `Copy of 1-7-23`, `Copy of 1-7-23 1`, `Copy of 20-5-23` — literal duplicate tabs. Import,
      suffix, and flag for deletion, exactly as with the two Anthony Horne tabs.
    - Variant tabs of one gig — `Blue Lion 7-6-25` and `Blue Lion 7-6-25 ACOUSTIC`,
      `15-10-22` and `15-10-22 easier`, `24-8-24 70s-disco-oriented` and its `Filtered`
      counterpart. These are **not** duplicates; they are alternative sets for the same booking.
      Import both, keep the qualifier in `setlist.name`, and flag the pair.
27. **Parse `NxM` set structure** (`2 x 60mins`, `3x40`) from the tab into N `setlist_set` rows
    with `target_minutes = M`. Default to a single set when the tab says nothing. Without this
    no `setlist_set` exists, and `setlist_item.setlist_set_id` has nothing to point at.
28. Row order becomes `position`, allocated as fractional ordering keys (decision 54), not
    integers. Where an `Order` column holds a decimal (`0.2`, `1.09`, `2.03`, `3.07`), the
    integer part selects the `setlist_set` and the fractional part orders within it.
29. **`Order` is ambiguous across tabs** and this is not fully resolved. Some tabs use the
    set.position decimal; others hold values like `216`, `230`, `300` that are duration in
    seconds. Disambiguate per tab by inspecting the value range, and flag any tab that cannot
    be classified confidently rather than importing it wrong.
30. Where a tab's `Order` column is classified as duration, **write it to
    `song.duration_seconds`.** It is the only source of duration anywhere in the workbook, and
    the set list screen needs it to total against `target_minutes`. Discovering it and then
    discarding it would be waste.
31. Where a song's tempo on a gig tab differs from the master value, write the tab's value to
    that item's `setlist_item.tempo_override` rather than dropping it. Of 27 tempo
    disagreements, 5 are rounding and 3 are half-time notation, but the remaining 19 are real —
    and several are deliberate, a party arrangement taken faster. `tempo_override` is the
    column designed for exactly that.
32. Two tabs carry the client name `SO42 7QB - Mr & Mrs Anthony Horne`, 56 rows each, differing
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
- No attempt to **backfill** key signatures beyond what a named key already implies (rule 9).
  Songs whose workbook key carries no quality, or which are genuinely modal, stay NULL for a
  human to resolve later.
- No attempt to *decide* the 19 genuine tempo disagreements. Both values are retained — master
  on the song, gig value on the item (rule 31) — because most will resolve to "both were right,
  for different gigs". These are **questions, not errors**.
- No import of the set-list triage columns. See *Deliberately not modelled* in
  [data-model.md](../decisions/data-model.md).
