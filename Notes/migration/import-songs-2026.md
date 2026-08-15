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

## Measured baseline — supersedes earlier estimates

**[E]** The counts originally written into this spec came from a *markdown* export of the
workbook, which lost worksheet names and, it turns out, a substantial fraction of the rows. The
extract pass measured the real `.xlsx`. Where the two disagree, **the `.xlsx` figures below are
authoritative** and the older numbers still embedded in individual rules are estimates.

Two of the original figures were also not measuring what their labels claimed: "4,049 rows" was
the line count of the markdown file, and "2,886 row instances" was the number of distinct
`(song, tab)` pairs. Neither is a row count. Treat them as retired.

| Metric | Measured |
|---|---|
| worksheets | 57 |
| non-empty data rows | 5,933 |
| distinct songs | 506 raw, ≈479 after resolving 27 unattributable titles |
| distinct artist strings → artists | 288 → 278 |
| artist collision groups | **9** (not 7) |
| distinct `Key` values | **60** (not 58) |
| plain key names | 2,797 |
| transpositions key-first / offset-first / "orig" / unknown | 20 / 3 / 2 / 15 |
| non-key values in `Key` | **59** (not 44) |
| unique practice events | 832 |
| unparseable practice cells | 85 |
| dates in year 2099 | **0 — they do not exist** |

Confirmed unchanged by measurement: the 20/3/2/15 key classification, the 288→278 artist fold,
the 9 `SET 1`/`SET 2` cells, the six 2002 date typos, and rule 35's five-cell difference between
the duplicate tabs. Six exact matches on rare values is strong evidence both readings are of the
same file, which is why the divergences above are attributed to the export rather than to a
parser fault.

## The review file is the human's, not the pipeline's

**[E] Once the user has begun editing the review workbook, the extract must not overwrite it.**
This was learned the hard way: the workbook was regenerated twice mid-review, and because the
song id derivation changed in between (decision 4d), the two files no longer joined on
`song_id` at all. The user's ~25 hand resolutions survived only because they could be
re-matched on title — and title is one of the fields they were editing.

Rules from here:

1. The user's copy is **canonical** once touched. The extract writes to a fresh path; it never
   writes over a file the user holds.
2. Machine-side corrections are **merged forward into** the user's copy, never applied by
   regenerating it.
3. The review workbook needs a **stable provenance key** that survives both regeneration and
   the user editing `title` or `artist` — the source worksheet and cell reference where the row
   first appeared. A derived id is unsuitable: it is a function of exactly the fields under
   human revision, so it changes the moment the human does their job.
4. Where a merge is genuinely ambiguous, present both values and flag it. Never silently prefer
   the machine's.

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
3. **Nine fold events, from seven spelling collisions plus two whitespace pairs.** The seven
   are `The Kaiser Chiefs`/`Kaiser Chiefs`, `Fratellis`/`The Fratellis`, `Walk The Moon`/`Walk
   the Moon`, `Florence and the Machine`/`Florence & the Machine`, `Kool and The Gang`/`Kool
   and the Gang`/`Kool & the Gang`, `The Beach Boys`/`Beach Boys`, `Human League`/`The Human
   League`. **[E]** Two more are pure trailing whitespace and invisible in the sheet:
   `Fleetwood Mac `/`Fleetwood Mac` (8 and 13 cells) and `Travis `/`Travis` (10 and 31).
   Decision 17's `trim` is load-bearing, not cosmetic.
3a. **[E]** Four further strings carry trailing whitespace with no counterpart and so fold to
   nothing: `Blue Brothers `, `Emotions `, `Fatboy Slim `, `Randy Newman `. Harmless, but a
   fold that strips *before* counting spellings will report seven collisions rather than nine
   and hide the whitespace problem entirely.
4. **Divider rows appear in the `Artist` column** and must be filtered before deduplication, or
   the import creates artists named after them. Seven distinct strings, not two:
   `SET 1`/`SET 2` (9 cells), `Set 3` (1), `FIRST DANCE` (3), `EXTRAS` (2). **[E]** 295 raw
   distinct strings minus these 7 is exactly the 288 the fold starts from — filtering fewer
   does not reproduce the artist count.

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
12. Suspect spellings to **flag, not discard**. `Bbb` (×9) and `Fb` (×1) are not keys, so
    `key_signature` stays null. **[E] `Cb` (×19) is a real key** — C♭ major is `−7`, inside
    decision 32's range — so derive its signature and flag it anyway. Flagging and nulling are
    different actions; rule 9 asks only for the flag.
12a. **[E] Some tabs write the key inside the title cell** — `Valerie Ab`, `Brown Eyed Girl - G`
    — 45 cells workbook-wide, concentrated in tab `1-6-23`. Without splitting these the tab is
    unreadable and three `Key` cells vanish. Split, and flag every split for confirmation: the
    trailing token is ambiguous with a genuine part of a title.
12b. **[E]** Two decade values, `1970` ×7 and `2000` ×7, have leaked into `Key` columns
    alongside the `2010` ×17 already known. This is why the measured distinct-`Key` count is 60
    rather than 58 and the non-key count 59 rather than 44.

### Performers

Decisions 24–27 in [data-model.md](../decisions/data-model.md).

13. One `performer` row per singer column in the workbook. Name comes from the column header
    with the trailing instrument word removed: `Coralie Vox` → `Coralie`, `Sophie-Mae Vocal` →
    `Sophie-Mae`, `Will Vocal` → `Will`. **[E]** Two corrections from measurement:
    - **`Kendra Piper` is not a singer column.** It is the header cell of the *Title* column on
      four tabs and holds 190 song titles. Reading it as a performer column would attribute 190
      songs to a singer who has none. Emit as a flagged performer candidate with zero
      `song_performer` rows and let the human decide.
    - `Carla Vox` exists as a header but is entirely empty.
13a. **[E]** The master sheet's bare `Lead vocal` column is **not** uniformly the owner. It
    routinely names somebody else — `Stef`, `Andy`, `Matt`, `W`, `C`. Attribute a cell holding
    a name to that person; only an `x`-style mark defaults to the owner (`Will`).
14. One `song_performer` row per non-empty cell in those columns, with `is_lead` set from
    whether the column is a lead or backing designation.
15. `vocal_range` comes from the `Range` column's `H`/`L`, mapped to `1`/`0`, attached to the
    owner's `song_performer` row — not to the song. With one performer this reproduces the
    workbook exactly.
16. Free-text performer annotations resolve to `performer` rows through the same normalisation
    as artists. `Andy?` and `Andy` must collapse to one performer — that pair is the reason this
    table exists. Flag anything not obviously a name (`FD`, `LV`, `2nd Request`) to the review
    sheet. **[E]** Corrections: `Will B` does not appear in this workbook; `Kita` ×10 does, and
    was unlisted. These annotations live in the **unheaded column A** of tabs `22-7-23`,
    `27-7-23` and `30-9-23`, not in a named column, so a header-driven scan misses them
    entirely.

### Practice events

17. Every dated cell in every practice column becomes one `practice_event`, with `discipline`
    read from the column name. Column names vary across tabs — `Vocal Practice`/`Vocal
    Practise`, `Bass Practice`/`Bass Practise`, `keys practise` — match case-insensitively on
    the instrument word.
18. The `Twitch` column is a practice record with `context = twitch`, not a discipline.
19. Expect ~879 unique `(song, discipline, date)` events. Distribution by year: 2021: 16,
    2022: 605, 2023: 149, 2024: 73, 2025: 13, 2026: 18.
20. **[E]** 85 practice cells do not parse as dates, not ~50, and the categories differ from
    the original estimate. Flag rather than guess:
    - 51 text markers — `x`, `X`, `goal`, `GOAL`
    - 25 bare-time cells and 13 year-1900 cells, all in `Guitar practise`, which are Excel
      serial `0`/`1` sitting in a date-formatted cell
    - 9 junk values in `Twitch`
    - 6 genuine typos landing in 2002

    **No cell anywhere resolves to year 2099**, and no cell holds a year-less date such as
    `13/2`. Both were artefacts of the markdown export and are retired. The only out-of-range
    years in the entire workbook are **1900 ×13** and **2002 ×6**.

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
    self-describing. An early pass took `Blue Lion` for a venue; it is a band. **A wrong guess
    here silently corrupts `band_id` and `venue_id` for the whole history**, and unlike a key
    or a tempo there is no musical check that would catch it later.

    These are **confirmed by the user** and are seed data, not guesses:

    | Remainder | Classification |
    |---|---|
    | `Blue Lion` | band |
    | `Radiant Lanterns` | band |
    | `LPT with Ryan` | band |
    | `Chigwell School`, `St Lawrence`, `Jukefest`, `50 Shades of Grant` | gig / client |

    Everything else is emitted to the review sheet with a proposed classification and a
    confidence, for the human to decide. The candidate types are **band**, **venue**,
    **client**, **event**, and **configuration** (`Acoustic`, the `ACOUSTIC` suffix, `easier`,
    `70s-disco-oriented`).
24. Within a gig remainder, splitting **venue** from **client** stays a flagged decision.
    Organisation-shaped names (`Chigwell School`, `St Lawrence`, and the holiday parks in
    in-sheet headers — `Seal Bay - Selsey`, `Haven - Allhallows`) are plausibly both the venue
    and the client; person-shaped names (`MRS & Mrs Dale`, `Mr & Mrs Brinkley`) are clients
    only. Propose, do not decide.
25. **Event names get no table.** `Jukefest 2025` and similar go in `setlist.name`, with
    `venue_id` and `client` left null unless separately recoverable. A one-off festival name is
    not a repeating entity and fails the normalisation test.
26. In-sheet header rows are equally mixed — `Seal Bay - Selsey` (venue),
    `GU19 5PJ - Adam & Anna Scott` (postcode plus client), `SO42 7QB - Mr & Mrs Anthony Horne`
    (postcode plus client), `Kendra Piper` (a person), `50 Shades of Grant` (an act). Same
    treatment: propose, flag, do not assume.
27. **Excel truncates worksheet names to 31 characters.** At least two are cut off
    (`Filtered 24-8-24 70s-disco-orie`, `15-6-24 Emma Munro-Faure &  Dha`). Recover the full
    name from the in-sheet header where one exists; flag to the review sheet where it does not.
28. **Not every tab is a gig.** Classify before importing, and flag anything unclassifiable:
    - `Everything` — the master song list, not a setlist. Source for song facts.
    - `Sheet2` — junk, skip.
    - **`Bass-vox Rep` is not a setlist and must not be imported as one.** It is the user
      working out which songs are easier to *play bass and sing at the same time*. Import it as
      a **tag** — seed `bass-vox` — with one `song_tag` row per song listed. See rule 29.
    - `LPT with Ryan` — a band (rule 23). Whether the tab is a performance or a working
      repertoire for that band is unresolved; import as a setlist if it carries an ordered song
      list, otherwise as a `band`-scoped tag, and flag either way.
    - `Copy of 1-7-23`, `Copy of 1-7-23 1`, `Copy of 20-5-23` — literal duplicate tabs. Import,
      suffix, and flag for deletion, exactly as with the two Anthony Horne tabs.
    - Variant tabs of one gig — `Blue Lion 7-6-25` and `Blue Lion 7-6-25 ACOUSTIC`,
      `15-10-22` and `15-10-22 easier`, `24-8-24 70s-disco-oriented` and its `Filtered`
      counterpart. These are **not** duplicates; they are alternative sets for the same booking.
      Import both, keep the qualifier in `setlist.name`, and flag the pair.
29. **Capability lists become tags, not setlists.** A tab that enumerates songs meeting a
    playing constraint rather than an ordered performance is a filter over the repertoire.
    `Bass-vox Rep` is the known case; treat any similar tab the same way and flag it.

    This is worth stating because the schema cannot derive it. `song_instrument.difficulty`
    records how hard a song is on bass, and separately how hard it is to sing — but the
    difficulty of doing *both at once* is emergent and is not a function of either. A tag
    captures it honestly; a computed field would be a lie.
30. **[E] `target_minutes` has no source. No `NxM` string exists anywhere in the workbook** — an
    exhaustive regex over every cell of all 57 tabs returns zero. The `2 x 60mins` examples came
    from a *different* workbook (the band's gig-booking sheet), not this one. Derive `set_no`
    from `SET 1`/`Set 1:` divider rows and from `Order` (rule 32), create the `setlist_set` rows
    that implies, and leave `target_minutes` NULL with the setlist flagged. 54 setlists are
    affected, i.e. all of them.
31. Row order becomes `position`, allocated as fractional ordering keys (decision 54), not
    integers.
32. **[E] `Order` never holds duration. All 37 occurrences encode position**, in one of three
    conventions:
    - `set × 100 + position` — 26 tabs. The values `216`, `230`, `300` previously read as
      durations are set 2 item 16, set 2 item 30, and set 3. Verified: they sit inside
      contiguous `101, 102, …` runs on `2-7-22`, `28-5-22`, `2-9-22` and `19-11-22`.
    - decimal `set.position` — 3 tabs.
    - plain `1..N` with a separate `Set` column — 3 tabs.
    - degenerate — 4 tabs; empty — 1.

    **Contiguity and monotonicity must be tested, not assumed.** A range check alone
    misclassifies: on `15-10-22`, `15-10-22 easier` and `6-8-22` the column runs
    `49, 50, 60, 99, 101…230`, which under a naive hundreds reading yields a fabricated set 0
    and colliding positions — breaking decision 54's requirement that `(position, id)` be a
    total order. Flag `ORDER-UNCLASSIFIABLE` on any tab with a repeated `Order` value or a
    value below 100.
33. **[E] There is no duration source anywhere in the workbook, so `song.duration_seconds`
    cannot be populated at all.** This was the last candidate and it evaporated on inspection.
    Consequence for the product: the set list screen's running-time total has neither durations
    nor set targets from the import, so that feature starts empty and is fed by hand or by a
    later lookup against a music service. Do not fabricate durations.
34. Where a song's tempo on a gig tab differs from the master value, write the tab's value to
    that item's `setlist_item.tempo_override` rather than dropping it. Of 27 tempo
    disagreements, 5 are rounding and 3 are half-time notation, but the remaining 19 are real —
    and several are deliberate, a party arrangement taken faster. `tempo_override` is the
    column designed for exactly that.
35. Two tabs carry the client name `SO42 7QB - Mr & Mrs Anthony Horne`, 56 rows each, differing
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
