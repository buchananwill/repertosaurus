---
title: "Schema 3 — Decision Spec"
type: decision-spec
area: data-model
status: active
date: 2026-09-23
---

# Schema 3 — Decision Spec

Roadmap packages **P1** (this spec) and **P2** (its implementation) of
[habit-arc-roadmap.md](../roadmap/habit-arc-roadmap.md). Every data change the habit arc needs lands
in **one migration, 2 → 3** (journal session 11, D20):

- the part ratings, priority and confidence;
- feel widened to 0–3;
- a practice duration for the timer;
- counted suggestion skips;
- the four triage sort names.

This spec fixes **shapes and migration obligations only**. How triage blends its inputs (P7), how
the timer writes a duration (P10) and how the suggester weighs skips (P4) are decided in those
packages' specs. This spec supplies the columns they write and the minimal repository surface
they build on.

Numbers are `M<n>`, grouped by topic. They are **not** an execution order.

## 1. Type Roster

**SQLDelight, new**
- `part_rating.sq`: a mutable row per `(song, performer, instrument, kind)`, with a derived id.
- `suggestion_skip.sq`: an append-only row per counted skip, with a random id.
- `migrations/2.sqm`: the 2 → 3 migration. Its existence moves `Schema.version` to 3.

**SQLDelight, changed**
- `practice_event.sq`: `feel` CHECK widened to 0–3, and a new `duration_seconds` column. The
  `insert` and `applyMerged` statements carry the new column, and `selectBySong` and
  `selectLiveRowsBySong` return it.
- `saved_view.sq`: the `sort_order` CHECK admits the four triage names.

**Kotlin core, new**
- `dev.repertosaurus.core.RatingKind`: `PRIORITY`, `CONFIDENCE`.
- `dev.repertosaurus.core.Ids.partRating(songId, performerId, instrumentId, kind)`.

**Kotlin core, changed**
- `dev.repertosaurus.data.RepertosaurusRepository`:
  - `log(...)` accepts feel 0–3 and an optional `durationSeconds`;
  - it gains the rating reads and writes, and the skip reads and writes (M14, M19).
- `dev.repertosaurus.data.SchemaCompatibility.REQUIRED` gains the two tables and the new column.
- `dev.repertosaurus.data.SongMerge`: its `Event` and the copy in `mergeInTransaction` carry
  `duration_seconds`. The merge copies events column by column (repertoire-editing R32–R33).
  Today it names `feel` explicitly (`SongMerge.kt:70, 140, 224`), so a new column is silently
  dropped unless it is added there. Its `mergeChildren` / `SongChildKey` rule governs M20.

**Tests, new**
- A schema-2 fixture database, and an upgrade test from it (schema-compatibility S13).

**Python migration**
- **No code change.** `tools/import/build.py` reads the schema from the `.sq` files and the
  version from the migrations directory (its `schema_version()`), so it picks up schema 3 by
  construction. It emits no ratings, skips, durations or feel (session 11, F2). The obligation is
  **verification only** (§3).

## 2. Decisions

### The version

**M1.** **`migrations/2.sqm` ships in the same change as every `.sq` edit below**
(schema-compatibility S1). SQLDelight derives `Schema.version` from the highest migration number
plus 1, so the file's existence moves the version to **3**. `1.sqm` is frozen history and **must
not be edited** (its own header says so).

**M2.** **A schema-2 database is `Upgradable`, not `TooOld`,** because the verdict is decided on
content (`SchemaCompatibility.check`) and schema 3 adds tables and a column that a schema-2 file
lacks. **The CHECK widenings are invisible to that content check.** That is harmless only because
they ship in the same version as the new column and tables. **No future change may widen a
CHECK on its own without also adding something `missingFrom` can see**, or two structurally
different databases would again share a verdict. Record this beside `REQUIRED` as a comment.

### Part ratings

**M3.** The table is **`part_rating`**:

```sql
CREATE TABLE part_rating (
    id            TEXT NOT NULL PRIMARY KEY,
    song_id       TEXT NOT NULL,
    performer_id  TEXT NOT NULL,
    instrument_id TEXT NOT NULL,
    kind          TEXT NOT NULL,
    level         INTEGER NOT NULL,

    updated_at    TEXT NOT NULL,
    deleted_at    TEXT,
    device_id     TEXT NOT NULL,

    FOREIGN KEY (song_id)       REFERENCES song(id),
    FOREIGN KEY (performer_id)  REFERENCES performer(id),
    FOREIGN KEY (instrument_id) REFERENCES instrument(id),
    CHECK (kind IN ('PRIORITY', 'CONFIDENCE')),
    CHECK (level BETWEEN 0 AND 3),
    CHECK (updated_at GLOB '????-??-??T??:??:??.???Z'),
    CHECK (deleted_at IS NULL OR deleted_at GLOB '????-??-??T??:??:??.???Z')
);
CREATE UNIQUE INDEX part_rating_key ON part_rating(song_id, performer_id, instrument_id, kind);
CREATE INDEX part_rating_performer_instrument ON part_rating(performer_id, instrument_id);
```

**M4.** **Priority and confidence are separate rows, distinguished by `kind`, never two columns
on one row.** Merge is last-write-wins **per row** (data-model 11). With one row carrying both,
a priority edit on the phone and a confidence edit on the desktop, made offline, would overwrite
each other on sync. As separate rows, they never touch.

**M5.** **The id is derived:** `uuid5(namespaceFor("part_rating"), song_id + "/" + performer_id
+ "/" + instrument_id + "/" + kind)`, exposed as `Ids.partRating`. Two devices that rate the same
part offline must converge on **one** row, which a random id cannot do: both inserts would
survive the merge and collide on `part_rating_key`. The table is registered at **arity 4** in
the table-aware guard that views.md V31 introduced, so a call with the wrong arity throws.
**Only Kotlin derives this id.** The Python migration never emits `part_rating`, and CLAUDE.md's
two-implementations rule is therefore not engaged. If `tools/import/` is ever made to emit
ratings, the rule engages in full.

**M6.** **The rating keys on the triple's own ids, never on `song_performer.id`.**
`song_performer` ids differ between an upgraded database (two-key) and an imported one
(three-key) until the known limitation schema-compatibility S3 is resolved. A foreign key to that
id would inherit the fork. The triple's ids (`song`, `performer`, `instrument`) are stable.

**M7.** **Unrated is a tombstone, not a null.** `level` is `NOT NULL`. Clearing a rating (a tap
on the selected segment, rating-scale RS9) sets `deleted_at`. Setting it again **revives the
same row**: it updates `level`, clears `deleted_at` and bumps `updated_at`. This is the
revive-on-write discipline of repertoire-editing R21. A second row is never inserted.
**SQLite 3.19 has no UPSERT**, so the write is an `UPDATE` followed by an `INSERT OR IGNORE` in
one transaction, or the reverse. Either order must be tested against a pre-existing tombstone.

**M8.** **A rating survives its part being toggled off.** Soft-deleting the `song_performer` row
does not touch `part_rating`. Toggling the part back on shows the old rating again. **Reads that
present ratings join a live `song_performer` row when they are about capability**, such as the
ratings editor's list. **Reads that feed a sort or a weighting read `part_rating` by the triple
directly.** Which of the two each consumer uses is fixed in its own spec (P7, P4).

### Feel and duration

**M9.** **`practice_event.feel`'s CHECK becomes `feel IS NULL OR feel BETWEEN 0 AND 3`.** Stored
values are **not touched**. They are read as rating-scale RS3 says: 1 → somewhat,
2 → certainly, 3 → exceptionally. `RepertosaurusRepository.log`'s validation widens from `1..3`
to `0..3` in the same change.

**M10.** **`practice_event` gains `duration_seconds INTEGER` (nullable)** with
`CHECK (duration_seconds IS NULL OR duration_seconds BETWEEN 1 AND 86400)`:
- **null means "logged by tap, untimed"**, which is every existing row and every plain tap;
- zero is not a duration;
- a day is the sanity ceiling.

The name matches `song.duration_seconds`. It is **written once, at insert** (data-model 7): a
timer that stops produces **one new event** carrying its duration, never an update to an
earlier one. `insert` and `applyMerged` both carry it, so a union merge preserves it.

**M11.** **The plain-tap path is unchanged in cost.** `log(...)` gains `durationSeconds: Long? =
null` as a trailing defaulted parameter, and the session screen's tap call site does not change.
(Roadmap §2 rule 1.)

### Counted skips

**M12.** The table is **`suggestion_skip`** (journal session 10, D16):

```sql
CREATE TABLE suggestion_skip (
    id            TEXT NOT NULL PRIMARY KEY,
    song_id       TEXT NOT NULL,
    performer_id  TEXT NOT NULL,
    instrument_id TEXT NOT NULL,

    created_at    TEXT NOT NULL,
    device_id     TEXT NOT NULL,

    FOREIGN KEY (song_id)       REFERENCES song(id),
    FOREIGN KEY (performer_id)  REFERENCES performer(id),
    FOREIGN KEY (instrument_id) REFERENCES instrument(id),
    CHECK (created_at GLOB '????-??-??T??:??:??.???Z')
);
CREATE INDEX suggestion_skip_part ON suggestion_skip(song_id, instrument_id, created_at);
```

**M13.** **Append-only, with a random id (UUIDv4) and no `updated_at` or `deleted_at`**
(data-model 6, 9). It **merges by union on id** and so joins data-model 11's existing
append-only rule, which does not add a third. **Data-model 11's list of append-only tables is
amended to name it** when P2 lands. There is **no void table and no pruning** (session 10, D16:
volume is not a concern). An undone skip is **never written** (session 10, D17), so it needs no
void.

**M14.** **"Skips since the part was last practised" is a read, never a stored count**
(data-model 14, 48). The boundary is the latest **live** (not voided) `practice_event` for the
same `song_id` and `instrument_id`. `practice_event` carries no performer (views.md V12a), so
"last practised" is necessarily per song and instrument. The count is skips with
`created_at` later than that event's `created_at`, or every skip for the part when it has never
been practised. **Compare on `created_at`, not `logged_on`.** A skip at 09:00 and a log at 18:00
on the same day must reset the count. A back-dated log (data-model 45) resets it too, because its
`created_at` is now. The repository exposes this per part, and in bulk for a View's pool.

### The triage sort names

**M15.** **`saved_view.sort_order`'s CHECK admits exactly six names**: `COLDEST_FIRST`,
`HOTTEST_FIRST`, `TRIAGE_PRIORITY`, `TRIAGE_PRIORITY_REVERSED`, `TRIAGE_CONFIDENCE`,
`TRIAGE_CONFIDENCE_REVERSED`. This follows the user's ruling in journal session 11, D25:
- two leading keys;
- `TRIAGE_*` is "most in need first";
- `_REVERSED` is its mirror, as hottest mirrors coldest.

**The meaning of each order is P7's to fix.** This spec fixes only the spellings.

**M16.** **P2 widens the CHECK; P2 does not add the `SessionOrder` constants.** The constants
arrive in P9 with their comparators. In the meantime:
- nothing writes a triage name;
- `SessionOrder.fromStored` keeps downgrading an unknown name, as views.md V17a describes.

P2 adds a test that inserts each of the six literal names into `saved_view`, and one that shows a
seventh is rejected. P9 then extends views.md V17a's enum-to-CHECK pin to the new constants.
V17a warns that the pin is two statements of one vocabulary, and this spec deliberately
separates them for one package's length.

### The migration mechanics

**M17.** **`2.sqm` rebuilds `practice_event` and `saved_view`, and creates `part_rating` and
`suggestion_skip`.** SQLite cannot alter a CHECK. The rebuild obligations are:
- **every** row is copied with its `id`, `created_at` and `device_id` byte for byte, and with
  `duration_seconds` null;
- `practice_event`'s three indexes and `saved_view_position` are recreated with their
  existing names and definitions;
- the log count before and after is equal, and so is the count of voided events.

**M18.** **The migration runs with foreign keys enforced, inside the upgrade transaction.**
`DatabaseFactory.createDriver` calls `setForeignKeyConstraintsEnabled(true)` in `onConfigure`,
which runs before `onUpgrade`. `PRAGMA foreign_keys` cannot be changed inside a transaction.
`practice_event_void.practice_event_id` references `practice_event(id)`, and with enforcement on,
`DROP TABLE practice_event` performs an implicit `DELETE` that **fails on any database holding
a void**. The user's database holds voids, since undo is on the tap path. The migration must
therefore:
- get every void row out of the way before the old `practice_event` is dropped;
- put the voids back, byte for byte, after the new table has taken the name.

One known-good order is:
1. copy the voids to a holding table with no foreign key;
2. drop `practice_event_void`;
3. rebuild `practice_event`;
4. recreate `practice_event_void` with its `.sq` definition and index;
5. copy the voids back;
6. drop the holding table.

**The property is the contract, and the order is advice.** The migration must succeed on a
database with voids and enforced foreign keys, **on SQLite 3.19 syntax** (no `DROP COLUMN`, no
`RENAME COLUMN`, no UPSERT).

### Repository surface (minimal)

**M19.** P2 adds exactly the surface the later packages need, and nothing presentational:
- `setRating(songId, performerId, instrumentId, kind, level: RatingLevel?)`: null tombstones
  the rating, per M7;
- `ratingsFor(performerId, instrumentId)`: live ratings keyed by song;
- `ratingsForParts(...)` in bulk, for a View's pool;
- `recordSkip(songId, performerId, instrumentId)`;
- `skipsSinceLastPractised(...)`, per M14, single and bulk.

`RatingLevel` is rating-scale RS1's type, **reused, not redeclared**. Names may be adjusted to
the repository's idiom; the semantics may not.

**M20.** **Song merge carries the new facts.** Merging song B into A (repertoire-editing
R31–R39) copies B's live events to A as new events. **They must carry `duration_seconds`** (and
`feel`, as today).

B's `part_rating` and `suggestion_skip` rows follow the merge's existing rule for per-song child
rows:
- ratings are re-created on A's triple, and **A's rating wins where both exist**;
- skips are **not** carried. They are a transient nudge, and "since last practised" on A is
  recomputed from A's events.

**Flag it if the merge code's existing child-row rule contradicts this. Do not reconcile it
silently.**

## 3. Verification: what must be demonstrated

- **Shared core**: `:shared:testDebugUnitTest --max-workers=2` passes, including:
  - **the S13 upgrade test**. A checked-in schema-2 fixture that contains at least one voided
    event, one event with feel, one saved View and one tombstoned View is migrated. The test
    asserts:
    - `Current` afterwards;
    - equal event and void counts;
    - byte-equal ids, `created_at` and `device_id`;
    - the anti-join still hides the voided event;
  - `SchemaCompatibility.check` returns `Upgradable(2, …)` on the fixture;
  - the M7 revive test against a pre-existing tombstone;
  - the M14 boundary tests: same-day skip then log, a back-dated log, a voided log (it must **not**
    reset the count), and a never-practised part;
  - the M15/M16 six-names-admitted and seventh-rejected tests;
  - feel 0 accepted and 4 rejected, and `duration_seconds` 0 rejected;
  - `Ids.partRating` arity guard: a wrong arity throws.
- **The upgrade on a device**: install the schema-2 build on `Pixel_3a_API_33_x86_64` and log
  events, including an undone one. Install the schema-3 build over it. Quote `PRAGMA
  user_version`, the event count and the void count, before and after. The app opens on the
  session screen.
- **The Python build**: run `tools/import` against `.scratch/Songs2026.xlsx` and quote the
  emitted `user_version` (3) and its table list. `SchemaCompatibility` must return `Current` for
  it.
- **Mutation**: remove the void-holding steps from `2.sqm` and show that the upgrade test fails
  with a foreign-key error, then restore them.
- **Not verifiable here, and it must be said**: SQLite 3.19 itself. No API 26 AVD exists
  (`Pixel_4_API_30` has a newer SQLite). Syntax discipline is the only guard.

## 4. Deferred

- `SessionOrder` triage constants and comparators (P9), and the blend (P7).
- Which performer's rating a session row reads when the View names no performer (session 11,
  F4; roadmap G12).
- Any UI.
- Amending data-model 11, 44 and 46 is done **by the lead when P2 lands**. It is not done in
  this spec.
