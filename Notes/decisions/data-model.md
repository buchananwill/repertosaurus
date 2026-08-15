---
title: "Data Model — Decision Spec"
type: decision-spec
area: data-model
status: active
date: 2026-08-15
---

# Data Model — Decision Spec

The schema contract. Implementing agents execute against this; it is not a discussion document.
Numbers are for citation, not execution order.

Storage is SQLite via SQLDelight, schema authored in real `.sq` files. Identifiers are UUID
text. Dates are ISO-8601 `YYYY-MM-DD` text; timestamps are ISO-8601 UTC.

## Type Roster

| Table | Nature | Written by |
|---|---|---|
| `artist` | mutable record | desktop mostly |
| `song` | mutable record | desktop mostly |
| `practice_context` | mutable record, user-extensible | rarely |
| `practice_event` | **immutable, append-only** | phone, constantly |
| `setlist` | mutable record | desktop |
| `setlist_item` | mutable record | desktop |

## Decisions

### Identity and mutability

1. Every table has a `id TEXT PRIMARY KEY` holding a UUIDv4 generated client-side. No
   autoincrement integers anywhere — IDs must be generatable offline on two devices without
   coordination.
2. `practice_event` rows are **immutable once written**. There is no update path and no edit UI.
   A mistake is corrected by writing a tombstone, never by mutating the row.
3. Every mutable table carries `updated_at TEXT NOT NULL` (ISO-8601 UTC) and
   `deleted_at TEXT NULL`. Deletion is always a tombstone; rows are never removed, so a stale
   device cannot resurrect them on merge.
4. Every row carries `device_id TEXT NOT NULL` recording which device last wrote it. Needed by
   the sync layer to attribute writes to their originating file.

### artist

5. `artist` is a first-class table. `song.artist_id` is a foreign key. Artist names are never
   stored as free text on a song.
6. Columns: `id`, `name` (canonical display form, e.g. `The Kaiser Chiefs`), `sort_name`
   (article moved to the end, e.g. `Kaiser Chiefs, The`), `updated_at`, `deleted_at`,
   `device_id`.
7. Aliases live in a child table `artist_alias (artist_id, alias)`. Populated by the migration
   with every losing spelling, so historical searches still resolve.
8. **Adding an artist must never require leaving the song form.** The artist field is a
   type-ahead that creates on enter. The only intelligence required is surfacing near-matches
   while typing — normalise by lowercasing, stripping a leading `The `, and folding `&` to
   `and` — so typing `Fratellis` offers the existing `The Fratellis` before a duplicate is
   committed. No admin screen. This is a hard requirement: friction here is the reason songs
   stop being added.

### song

9. Columns: `id`, `title`, `artist_id`, `reference_recording`, `key_signature`, `tonal_centre`,
   `tonality_note`, `tempo_bpm`, `duration_seconds`, `decade`, `vocal_range`, `notes`,
   `chart_url`, `updated_at`, `deleted_at`, `device_id`.
10. `reference_recording TEXT NULL` names the specific recording all musical facts are stated
    against. A key claim is not verifiable without it. Free text or a URL; not validated.
11. `key_signature INTEGER NULL` is the dominant diatonic key signature as a signed sharp/flat
    count, constrained to −7..+7. Negative is flats, positive is sharps, `0` is none.
    Displayed as `3♯` / `2♭` / `0`.
12. `tonal_centre TEXT NULL` is the home bass note as a pitch class (`A`, `A#`/`Bb`, … `G#`).
    Where the tonality is modal or ambiguous, the convention is the first or last chord of the
    main loop.
13. `key_signature` and `tonal_centre` are **separate columns and never concatenated into a
    display string in the database.** Formatting is a UI concern. Much of this repertoire is
    not functionally tonal, and one combined field forces a lie: *Shake It Off* is `0` + `A`;
    *Sweet Home Alabama* is `1♯` + `G`.
14. `tonality_note TEXT NULL` is free text, **hidden by default in every UI**, for cases the two
    typed fields do not settle. It exists to end an argument before gig day, not to be read
    routinely.
15. `key_signature` is nullable and **must remain nullable through phase 1**. Nothing in the
    source workbook records one, so requiring it would block the import behind ~460 rows of
    musical analysis. The Session screen never reads it.
16. `vocal_range TEXT NULL` carries the existing `H` / `L` vocabulary. `decade INTEGER NULL`.
17. Boolean-ish tags from the workbook (`Party`, `Christmas`, `C&W Duets`, `TARGET`) become a
    `song_tag (song_id, tag)` child table, not columns. New tags must not require a schema
    change.

### practice_context

18. `practice_context` is a table, not an enum. Columns: `id`, `name`, `updated_at`,
    `deleted_at`, `device_id`.
19. Seeded with `practice`, `twitch`, `rehearsal`, `gig`. The user adds rows freely — "wedding",
    "dep gig", "lesson" must not require a code change.
20. Adding a context follows the same type-ahead-creates rule as artist.

### practice_event

21. The high-frequency table and the reason the product exists. Columns: `id`, `song_id`,
    `logged_on` (date), `discipline`, `context_id`, `feel`, `note`, `created_at`, `device_id`.
22. `discipline TEXT NOT NULL` is a fixed set — `vocal`, `guitar`, `bass`, `keys` — because it
    maps to the instrument the user physically picked up, not to a user-defined category.
    Contrast decision 18: context is open, discipline is closed.
23. `logged_on TEXT NOT NULL` defaults to today. Logging for a past date is available but
    demoted into a menu; it must never be on the primary tap path.
24. `feel INTEGER NULL` is 1–3, optional. **Surfaced as long-press on the row, not as an extra
    step in the tap path.** A plain tap logs with `feel` null; long-press opens the rating.
    This is a day-one affordance, not a later addition.
25. `note TEXT NULL`, entered via the same long-press sheet.
26. There is **no uniqueness constraint** on `(song_id, logged_on, discipline)`. Logging the
    same song twice in a day is legitimate — you did work on it twice.
27. Derived values are computed, never stored. `last_practised` is `MAX(logged_on)`;
    `times_practised` is `COUNT(*)`; `days_since` is derived at query time. The workbook's
    abandoned counter columns are the cautionary tale — anything a human must increment by hand
    will not be maintained.

### setlist and setlist_item

28. `setlist` columns: `id`, `name`, `performed_on` (date, nullable), `venue`, `client`,
    `lineup`, `set_lengths` (e.g. `2x60`), `updated_at`, `deleted_at`, `device_id`.
29. `setlist_item` columns: `id`, `setlist_id`, `song_id`, `set_no`, `position`, `transpose`,
    `lead_vocal`, `tempo_override`, `note`, `updated_at`, `deleted_at`, `device_id`.
30. `set_no INTEGER` and `position INTEGER` are **separate columns**. The workbook's `2.03`
    decimal encoding is a composite key crammed into a number; the migration splits it.
31. `transpose INTEGER NOT NULL DEFAULT 0` is a signed semitone offset from the song's
    reference key. The song's own key never changes. Displayed key is computed as
    `tonal_centre + transpose`, with the key signature shifted correspondingly.
32. `lead_vocal TEXT NULL` is per performance, not per song. The workbook grew a new column for
    each new singer (`Coralie Vox`, `Carla Vox`, `Sophie-Mae Vocal`, `Kendra Piper`); a new
    singer must cost zero schema change.
33. A set list reads song facts **through the foreign key**. Facts are never copied onto a
    `setlist_item`. `transpose` and `tempo_override` are the only sanctioned per-gig
    divergences.
34. Multi-select with transpose-all ±1 and reset-to-original is a required set list affordance.
    It operates on `transpose` only. The motivating case is a downtuned guitar, where horns and
    keys otherwise get caught out — so **set lists display and print sounding (concert) key**.

### Sync-facing invariants

35. The schema must satisfy two merge rules and nothing more: `practice_event` merges by union
    on `id`; all mutable tables merge last-write-wins per row on `updated_at`, with tombstones
    winning ties. Anything that would require a third rule is a design error — raise it rather
    than implementing it.
36. No table may depend on insertion order or on a monotonic counter. Two devices generate rows
    offline with no coordination.
