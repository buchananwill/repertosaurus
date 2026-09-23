---
title: "Repertoire Editing — Decision Spec"
type: decision-spec
area: ui
status: active
date: 2026-09-23
---

# Repertoire Editing — Decision Spec

After a month of daily use the user's verdict is that **core database content is still
inaccessible from the app**: songs cannot be edited, a performer's repertoire on a role can only be
changed one song at a time from a logger row that the active View may have filtered out, and there
is no way to look at the database except through a logger View. This spec opens the arc that fixes
it, in the user's framing (session 09): **at this stage it is better to have too many routes than to
have anything inaccessible.** Which surfaces get foregrounded is decided later, from use.

It delivers three drawer routes — **Repertoire** (performer → role → toggle songs), **Songs** (list
→ every editable field of one song) and **Artists** — and it extends, not replaces, the long-press
*Who plays this* editor from [editing.md](./editing.md).

Numbers are `R<n>` for citation, grouped by topic, **not** an execution order. Where this spec
amends an `E<n>` it says so at the point of amendment.

## 1. Type Roster

Names marked *(proposed)* are the implementer's to choose; the responsibilities are fixed.

**Kotlin core — new**

- `dev.repertosaurus.data.SongCatalog` *(proposed)* — the song aggregate from E32: song read, update,
  soft delete, create; artist read, rename, remove; song tags; groove assignment; `song_instrument`
  rows. The first E32 aggregate to be cut, and cut because this arc adds to it.
- `dev.repertosaurus.session.SongDraft` *(proposed)* — every editable `song` column as a value,
  with validation mirroring the schema's `CHECK`s.
- `dev.repertosaurus.session.RepertoireCoordinator` *(proposed)* — performers with their roles, and
  the all-songs-with-held-flag list for one `(performer, instrument)`, plus toggle.
- A song display label (`Title — Artist`) beside the existing display helpers.

**Kotlin core — changed**

- `RepertosaurusRepository` — `createSong`, `findOrCreateArtist` and `artists()` move out to
  `SongCatalog`.
- `song_performer.sq` — gains the held-flag list query (R9).
- `song.sq`, `artist.sq`, `song_tag.sq`, `song_instrument.sq` — gain the reads/writes their editors
  need; **no table gains a column.**

**Android — new**

- `RepertoireScreen`, `SongsScreen` (list + detail), `ArtistsScreen` *(proposed)* — each with its
  own ViewModel.

**Android — changed**

- `Route` and the drawer in `RepertosaurusApp` — three new entries.
- `SessionViewModel` — reloads when the user returns to the logger (R26).
- `AddSongSheet` — shared between the logger and the Songs route (R15).

## 2. Decisions

### The Repertoire route: performer → role → songs

**R1.** **The Repertoire route is one screen listing every live performer, each with a chip per
role they hold**, where a role is an instrument they have at least one live `song_performer` row
on (E15: derived, not stored). Tapping a chip opens the toggle list for that pair. Performer and
role are picked on **one** screen, not two, so the toggle list sits one level below the route and
E24's "logger within two presses" holds without amendment.

**R2.** **Every performer row also offers "+ role", which lists every live instrument** the
performer does not yet hold. This is the only way to give a performer their first song on an
instrument, so it cannot be hidden. Picking one opens that pair's toggle list with nothing held.

**R3.** **The toggle list shows every live song in the database, not the songs in any View.** The
point of the route is to reach songs the logger's View hides (session 09 F2). Each row shows the
song's title and artist and a toggle; the list has a search field matching title and artist, and
a count of songs held.

**R4.** **Toggle on adds or revives the `(song, performer, instrument)` row; toggle off soft-deletes
it** — exactly `CapabilityCoordinator.addById` and `remove`, reused, never re-implemented. Revive
keeps the row's `is_lead`, `vocal_range` and `notes` (E6), so toggling off and on again is lossless.

**R4a** (added 2026-09-23 after the fix round 2 re-review). **A capability row is found by its
`(song, performer, instrument)` triple, never by assuming its id equals the derived id.** A database
upgraded 1 → 2 on a device keeps two-key `song_performer` ids (schema-compatibility S3). There, a
toggle that looks the row up by `Ids.songPerformer` misses it:
- **Toggle-off** reports "already removed" and removes nothing.
- **Toggle-on** has its `OR IGNORE` insert silently blocked by the unique index on the triple, so
  it reports success while the row stays removed.

Lookups go through the unique key, and a newly inserted row still uses the derived id. An `OR
IGNORE` insert that writes nothing is reported as a failure, never as `CREATED`.

**R5.** **The toggle list never logs practice and has no gesture that reaches the practice path**
(E1, E11). A row tap toggles; nothing else on the row is a tap target.

**R6.** **The toggle list edits membership only.** `is_lead`, `vocal_range` and notes are edited in
the capability editor (R18). A new row gets the schema defaults.

**R7.** **Held songs sort first, then title, case-insensitively.** Answering "what is in this
repertoire" is the list's first job and "add to it" is the second. The order is fixed when the list
loads and when the search changes — **a row does not jump position on the tap that toggles it**,
so a mis-tap can be undone in the same place.

**R8.** **Toggles are applied optimistically, per row, with rollback on failure.** A row shows its
new state at once and is disabled until its write completes; a failed write restores the row and
reports on the error channel (E43). Writes for different rows are **serialised** through one queue
for the screen, because the E45 ticket in `SessionViewModel` covers one song's line-up and this
screen writes across many songs. The ticket pattern is not reused here; its property — no write
completes out of order against the screen — is.

**R9.** **One query returns all live songs with a held flag for one `(performer, instrument)`** — a
`LEFT JOIN` of live songs to `song_performer` on performer and instrument with
`song_performer.deleted_at IS NULL`. SQLite 3.19 supports this; test it by inserting rows in the
wrong order.

**R10.** **Soft-deleted performers and instruments do not appear on the Repertoire route**, matching
E12 and E40.

### The Songs route: every editable field

**R11.** **The Songs route lists every live song**, searchable by title and artist, filtering an
in-memory list rather than querying per keystroke (E36). A row shows `Title — Artist`. Tapping a row
opens that song's detail; the detail is the route's own state with its own back handler (E24).

**R12.** **The song detail exposes every user-meaningful `song` column**: title, artist,
reference recording, key signature, tonal centre, tonality note, tempo, duration, decade, loop
length, chord count, chord pattern, groove, mashup note, notes, chart URL. **Not** `id`,
`updated_at`, `deleted_at` or `device_id`. Field labels and meaning come from
[data-model.md](./data-model.md); a column whose meaning is unclear there is flagged, not guessed.

**R13.** **Values the schema constrains are picked, never typed.** Key signature is chosen from the
-7..7 range and tonal centre from the twelve pitch classes, labelled with the existing `Keys`
helpers. Numbers are numeric fields where blank means `NULL`. **No field is mandatory except title
and artist** (decisions 27, 37).

**R14.** **The detail saves on an explicit Save, writing the whole row**, because `song.update`
writes every column and last-write-wins merges per row anyway. Validation lives in `SongDraft` in
the shared core; an invalid field shows its error inline and blocks Save. **A `CHECK` violation must
never reach SQLite from this screen** — if one does, it is reported on the error channel, never a
crash (S8). Leaving with unsaved changes asks whether to discard them.

**R15.** **Songs can be added from the Songs route with the existing `AddSongSheet`**, moved out of
`SessionScreen` so both routes share one component. The logger's add path is unchanged.

**R16.** **Artist and groove on the detail are create-on-enter type-aheads**, like every other
lookup field (E5), with near-matches surfaced while typing.

**R17.** **Tags are a multi-select on the detail**, writing `song_tag` rows: add inserts or revives,
remove soft-deletes. The same revive rule as E6, because the junction id is derived.

**R18.** **The detail shows the song's line-up and opens the existing capability editor for it.**
One capability editor exists in the app; the detail reuses it, it does not grow a second.

**R19.** **The detail shows `song_instrument` rows — difficulty (1–5), patch, notes — per
instrument, editable**, add inserts or revives, remove soft-deletes. This is per-song technical
detail the workbook carried and nothing in the app has shown yet.

**R20.** **The detail shows the song's practice history read-only**: times practised and last
practised, per instrument. Viewing the database was part of the complaint. Editing or voiding
practice events is out of scope (§3).

**R21.** **A song can be removed from the detail — a soft delete, behind a confirmation** that
states its practice history is kept and hidden. **Adding a song whose derived id is a tombstone
revives it** rather than returning a hidden row: `createSong` currently returns any existing id
untouched, which was harmless only while nothing could delete a song. This is the same closure E12
made for performers.

### Ids after editing

**R22.** **Editing a song's title or artist never re-derives its id** (decision 5, E21), the same
rule the lookup tables already follow. The accepted consequence, stated so nobody "fixes" it by
re-deriving: after renaming *Jolene* to *Jolene (live)*, adding *Jolene* again resolves to the
renamed row (its derived id is still taken), and adding *Jolene (live)* creates a second row
(its derived id is free).

**R23.** **When an add resolves to an existing row whose current title or artist differs from what
was typed, the UI says so** — "already in the repertoire as *Jolene (live)*" — and opens that row
rather than silently returning it. The same message applies to an artist type-ahead resolving to a
renamed artist. This is the defence R22 relies on; changing id derivation is not, because it would
touch both implementations for a case the migration never produces.

**R23a** (amended 2026-09-23 after package 1 review). **"Differs" compares the title and the
artist.** An add returns how both the song and the typed artist resolved, and the message fires when
either one lands on a row whose current name is not what was typed. A blank typed artist resolving
to *Unknown Artist* does not count as differing. **A picked artist counts as what was typed**
(clarified 2026-09-23 after re-review): it is the name the user chose and can see in the field. An
add under a picked artist differs when the resolved song's current artist is not the picked one.
**A typed artist is written only if the song ends up under it.** If the song resolves to an existing
row under a different artist, the typed artist is not created or revived as a side effect.

**R23b.** **A write that makes a live row reference a tombstoned parent revives the parent, in the
same transaction.** This covers a song's artist or groove, whether reached by reviving a song (R21)
or by a picked id from a stale type-ahead list, and the tag or instrument on a junction row. It is
R21's rule applied to the parent: asking for the row again says the tombstone was wrong. Without it,
a live song renders under a hidden artist, which R25 exists to prevent.

**R23c.** **A write to a child of a removed song is refused, and the caller can tell.** Tag,
`song_instrument` and capability writes on a tombstoned song write nothing and report it. Every
write that can be a no-op reports whether it wrote, so a form clears only on real success (E44).

**R23d.** **Soft deletes do not re-stamp a row that is already removed.** `AND deleted_at IS NULL` on
every `softDelete` **in the schema, the lookup tables and `saved_view` included**, as E37 does for
updates. Every remove path reports whether it wrote. A write reports `wrote` if it wrote anything,
including a parent it revived. Under last-write-wins a spurious later stamp would
outrank a legitimate revive made on another device.

### The Artists route

**R24.** **The Artists route lists every live artist with its live song count, and edits name and
sort name.** Rename never re-derives the id (R22). Aliases (`artist_alias`) are out of scope (§3).

**R25.** **An artist with live songs cannot be removed**; the screen says how many songs point at
it. The songs would otherwise render against a hidden artist. An artist with none is soft-deleted.

**R25a** (amended 2026-09-23 after package 1). **The seeded *Unknown Artist* can never be removed,
whatever its song count.** A blank artist on add-song resolves to its fixed id, so removing it would
point every later artist-less song at a hidden row. It can be renamed.

### Navigation and wiring

**R26.** **Returning to the logger from any route reloads the logger's state.** A toggle on the
Repertoire route changes which songs a View shows, and the logger must not keep showing the old
list until the app restarts.

**R27.** **The three routes are drawer entries in the flat `Route` enum, and E25's revisit is
declined.** The drill-downs (role toggle list, song detail) are screen state, which is what E24
already prescribes for a detail inside a route. No route needs arguments from outside itself, and no
screen is a third level. **Drill-down state holds ids only and survives rotation** — the existing
`Route` state does not, and this arc does not change that.

**R28.** **Each new route gets its own ViewModel** rather than growing `SessionViewModel`, which is
already over a thousand lines. Each builds its coordinator per call against `holder.repository`, as
`SessionViewModel` does, so a database swap on import is picked up.

**R29.** **Each new route shows success and failure separately** (E43): the error channel in the
error colour, and a form clears only on confirmed success (E44).

### The repository split

**R30.** **Song and artist writes go into `SongCatalog`, not `RepertosaurusRepository`.** This is
the first E32 aggregate to be cut, and it is cut now because this arc adds to it — the same
reasoning that made E31 cut the lookup surface when that arc grew it. `createSong`,
`findOrCreateArtist` and `artists()` move with it. **The rest of E32 remains its own piece of work.**

### Merging two songs (added 2026-09-23 at the user's request; queued as package 3)

The user found *Shake It Off* and *Shake If Off* both in the database and asked for a way to merge
two song entries, either summing their logs or cherry-picking at the user's discretion.

**R31.** **A merge has a survivor and a loser, chosen by the user, and the loser is soft-deleted at
the end.** The survivor keeps its id. Nothing is re-derived (R22).

**R32.** **Practice events are never re-pointed.** `practice_event` is immutable and append-only
(data-model decisions 7–8), and a union merge cannot see an update. **Each carried-over event is
voided on the loser and appended to the survivor as a new row with a new random id**. The copy keeps `logged_on`,
`instrument_id`, `context_id`, `feel`, `note` **and `created_at`**, since it records the same act of
practice and every "last practised" or ordering read must treat it exactly as the original. Only
`id`, `song_id` and `device_id` (the merging device) change. This counts as a void plus an append, both of which
the log already allows, so the merged history exists exactly once and survives a union merge.

**R33.** **By default every event carries over — the "sum" case — and the user can deselect
individual events.** A deselected event is voided with the loser and not copied, so it drops out of
the history. That is the cherry-pick. Nothing is ever hard-deleted.

**R34.** **Every song field is picked per field from either side**, defaulting to the survivor's
value, or to the loser's where the survivor's is blank. Title and artist are picked like any other
field.

**R35.** **Capability, tag and `song_instrument` rows default to the union and can be
deselected.** Carrying a row means inserting or reviving the survivor's row at its derived id and
soft-deleting the loser's. Where both sides hold the same `(performer, instrument)` or instrument,
the survivor's row wins and its fields are kept.

**R36.** **The whole merge is one transaction.** A half-merged pair — events copied but the loser
still live — would show the history twice.

**R37.** **Set-list items that point at the loser are re-pointed to the survivor**, because they are
mutable rows. The set-list UI does not exist yet, but the rows do, and the migration populated
them.

**R38.** **The merge is reached from the song detail ("Merge with…") and shows a preview before it
commits:** the field picks, the rows carried and the count of events carried. It is irreversible
from the UI, so the confirmation says so.

**R38a** (added 2026-09-23 from the package 1 review, for package 3):

- **Where the survivor's row is tombstoned and the loser's is live, the loser's facts win.** The
  live row is the one the user currently believes. R35's "survivor wins" applies only when both rows
  are live.
- **Merge treats any refusal as a failure and throws, so its transaction rolls back.** A refusal is
  `SongGone`, `Gone`, or a write that should have written and did not. R23c's refusals return
  values rather than throwing, so a merge that ignored them could commit half-done, breaking R36.
  The loser is removed last (R31), after all of its children.
- **Merge reads song children directly, not through the display reads.** `songTags` and
  `songInstruments` hide rows whose parent lookup is tombstoned, and a merge must not silently drop
  them.
- **Accepted: re-adding the loser's exact title and artist after a merge revives the loser** (R21)
  as a song with no history, rather than resolving to the survivor. Nothing records that a merge
  happened, and recording it is the redirect question R39 defers. The result is a visible, empty
  duplicate that can be merged again, not lost data.

**R38b** (added 2026-09-23 after the merge safety review). **Every child row is kept unless the
user deselected it.** The merge request carries the rows the user *dropped*, not the rows to keep.
A survivor row that appears after the preview was read is therefore kept, matching events (the
default is the sum).

**R32a — for the phase 2 sync spec, not changed now.** R32 keeps the original `created_at` on each
copied event, and that conflicts with `practice_event.selectCreatedSince`, the schema's
`created_at`-keyed delta export. A device whose sync watermark is later than the original event
would receive the merge's voids (fresh `created_at`) but not the copies (old `created_at`), so on
that device the loser's history would vanish. Union on id stays sound; a delta keyed on `created_at`
does not. **The phase 2 sync spec must key its deltas on something a copy gets fresh**: a separate
write-time column, or a delta that is not time-keyed. The spec must not drop R32's `created_at`,
because every "last practised" read depends on it.

**R39.** **Accepted limitation, recorded for phase 2:** an event logged on the loser by a device
that has not yet received the merge stays with the tombstoned loser. There is one device today, so
this cannot happen yet. Whether sync needs a song-redirect record is a phase 2 sync spec question,
not this arc's.

### Note spelling (added 2026-09-23 at the user's request; queued as package 4)

**R40.** **Whether a double sharp or double flat is spelled as written or simplified is a user
setting.** The user's words: "F## is a real thing but rare. User should have the option whether to
display F## or G." Under a six-sharp key the tonal centre G reads **F♯♯** when spelled as written,
and **G** when simplified. The setting changes display only. The stored `tonal_centre` pitch class
and `key_signature` are unaffected, and no id depends on either.

**R41.** **The default is simplified (G).** Double accidentals are rare, and a working musician
reads G faster. This is an assumption the user can correct in passing.

**R42.** **The rule lives in the core `Keys` helpers as a parameter**, not as a branch in a
composable (E46), so every surface that names a note follows the one setting. That includes the
pickers, any key label, and the desktop later. It persists alongside the app's other preferences
(`SessionPreferences`) and is reached from the drawer.

## 3. Deferred (explicitly out of scope)

- **Restoring a removed song other than by re-adding it** (R21). There is no bin view.
- **Artist aliases.** Nothing in the app reads them.
- **Editing or voiding practice events.** `practice_event_void` exists; the UI for it is its own
  change to the logging path.
- **Attaching a practice context when logging** — still E19.
- **Set lists.** Unchanged since editing.md §3.
- **Any change to id derivation.** R23 is the defence; the Python migration is not touched by this
  arc.
