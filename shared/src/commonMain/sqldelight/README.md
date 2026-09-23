# SQLDelight schema

The Repertosaurus local store. This is the implementation of
[Notes/decisions/data-model.md](../../../../Notes/decisions/data-model.md) — that document is
the contract, this directory is the code. Where the two disagree, the decision spec wins and
this directory is wrong.

## Layout

SQLDelight treats the directory under `sqldelight/` as the generated Kotlin package, so the
schema lives in `dev/repertosaurus/db/`. **One `.sq` file per table, named after the table.** Each
file carries, in order:

1. A header comment citing the decisions it implements.
2. The `CREATE TABLE`.
3. Its `CREATE INDEX` statements.
4. Seed rows, where a decision specifies them.
5. The labelled queries that table needs.

| File | Table | Nature |
|---|---|---|
| [artist.sq](./dev/repertosaurus/db/artist.sq) | `artist` | mutable record, seeded (`Unknown Artist`) |
| [artist_alias.sq](./dev/repertosaurus/db/artist_alias.sq) | `artist_alias` | child of artist |
| [performer.sq](./dev/repertosaurus/db/performer.sq) | `performer` | mutable record |
| [instrument.sq](./dev/repertosaurus/db/instrument.sq) | `instrument` | lookup, seeded |
| [tag.sq](./dev/repertosaurus/db/tag.sq) | `tag` | lookup, seeded |
| [groove.sq](./dev/repertosaurus/db/groove.sq) | `groove` | lookup |
| [venue.sq](./dev/repertosaurus/db/venue.sq) | `venue` | lookup |
| [band.sq](./dev/repertosaurus/db/band.sq) | `band` | lookup |
| [practice_context.sq](./dev/repertosaurus/db/practice_context.sq) | `practice_context` | lookup, seeded |
| [song.sq](./dev/repertosaurus/db/song.sq) | `song` | mutable record; **the Session screen query lives here** |
| [song_performer.sq](./dev/repertosaurus/db/song_performer.sq) | `song_performer` | junction, **three keys** (`song_id`, `performer_id`, `instrument_id`) |
| [song_instrument.sq](./dev/repertosaurus/db/song_instrument.sq) | `song_instrument` | junction, carries facts |
| [song_tag.sq](./dev/repertosaurus/db/song_tag.sq) | `song_tag` | junction |
| [practice_event.sq](./dev/repertosaurus/db/practice_event.sq) | `practice_event` | **append-only** |
| [practice_event_void.sq](./dev/repertosaurus/db/practice_event_void.sq) | `practice_event_void` | **append-only** |
| [setlist.sq](./dev/repertosaurus/db/setlist.sq) | `setlist` | mutable record |
| [setlist_set.sq](./dev/repertosaurus/db/setlist_set.sq) | `setlist_set` | child of setlist |
| [setlist_item.sq](./dev/repertosaurus/db/setlist_item.sq) | `setlist_item` | mutable record |
| [setlist_item_performer.sq](./dev/repertosaurus/db/setlist_item_performer.sq) | `setlist_item_performer` | junction |
| [saved_view.sq](./dev/repertosaurus/db/saved_view.sq) | `saved_view` | mutable record, random id |
| [part_rating.sq](./dev/repertosaurus/db/part_rating.sq) | `part_rating` | mutable record, **derived id over four keys** (`song_id`, `performer_id`, `instrument_id`, `kind`) |
| [suggestion_skip.sq](./dev/repertosaurus/db/suggestion_skip.sq) | `suggestion_skip` | **append-only**, random id |

Create tables in that table order if you replay the DDL by hand; it is dependency-ordered.

## Versions and migrations

**`Schema.version` is currently 3.** SQLDelight derives it from the highest migration number in
[dev/repertosaurus/db/migrations/](./dev/repertosaurus/db/migrations/) plus one, so the version moves
when — and only when — a `.sqm` lands.

| Version | Migration | What changed |
|---|---|---|
| 1 | — | Phase 1: everything above except `saved_view`, and `song_performer` keyed on two columns. |
| 2 | [migrations/1.sqm](./dev/repertosaurus/db/migrations/1.sqm) | Views: `saved_view` added; `song_performer` gains `instrument_id` and the key widens to the triple. |
| 3 | [migrations/2.sqm](./dev/repertosaurus/db/migrations/2.sqm) | The habit arc (schema-3 spec): `part_rating` and `suggestion_skip` added; `practice_event` gains `duration_seconds` and its `feel` CHECK widens to 0-3; `saved_view.sort_order`'s CHECK admits the four triage names. `practice_event` and `saved_view` are rebuilt. |

**Every `.sq` edit that adds, removes or retypes a table or column ships with a new `.sqm` in the
same change** (schema-compatibility decision S1). Adding `saved_view` without one is exactly how
two structurally different databases both came to claim `user_version = 1`, and how the app came
to accept an obsolete file at import and then die on the next launch with `no such table:
saved_view`.

Three things must move together, and a test fails if any is left behind:

1. the `.sq` file;
2. a new `.sqm` — `3.sqm` next, which makes `Schema.version` 4;
3. `SchemaCompatibility.REQUIRED`, the table-and-column map the boot gate and the import
   validator both check against.

`SchemaCompatibilityTest` pins all three: it asserts the version, asserts `REQUIRED` equals what
`Schema.create` actually produces, and asserts that a database migrated from the previous version
ends up with the same tables, columns and indexes as a freshly created one.

**A migration cannot re-derive ids.** UUIDv5 is not computable in SQLite, so the 1 → 2 migration
leaves the 595 `song_performer` rows on their old two-key ids while a freshly imported database
has three-key ones (S3). That is invisible until phase 2 sync and must be resolved before it.

**minSdk 26 means SQLite 3.19** in a migration as much as anywhere else: no window functions, no
`UPSERT`, no `DROP COLUMN`, no `RENAME COLUMN`. `ALTER TABLE … ADD COLUMN` exists but cannot add a
`NOT NULL` column carrying a `REFERENCES` clause, which is why `1.sqm` rebuilds `song_performer`
rather than altering it.

**Migrations run with `foreign_keys = ON`, inside the upgrade transaction.** The Android driver
enables foreign keys in `onConfigure`, before `onUpgrade`, and the pragma cannot be changed inside
a transaction. So rebuilding a table that another table references — dropping it fires an
implicit `DELETE` — fails on any database holding child rows: the child rows must be held aside in
a table with no foreign key first, and restored after the rebuild (schema-3 M18; `2.sqm` does this
for `practice_event_void`). And because `SchemaCompatibility` judges a file by its tables and
columns, **a CHECK widening must never ship on its own**: it must travel with a change that
`missingFrom` can see, or two structurally different databases share one verdict (schema-3 M2).

## Conventions that hold everywhere

- **Ids are `TEXT`.** UUIDv4 or UUIDv5, generated client-side. No autoincrement anywhere.
- **The standard three.** Every mutable table — including children and junctions — carries
  `updated_at TEXT NOT NULL`, `deleted_at TEXT` and `device_id TEXT NOT NULL`. The three
  append-only tables carry `created_at TEXT NOT NULL` and `device_id`, and **no `deleted_at`**.
- **Timestamps** are `YYYY-MM-DDTHH:MM:SS.sssZ`, always three decimals, always literal `Z`,
  enforced by a `GLOB` `CHECK` on every timestamp column. Variable precision breaks string
  comparison, and string comparison is what the merge order is built on.
- **Dates** are `YYYY-MM-DD`, likewise `CHECK`ed.
- **Deletes are soft.** `softDelete` sets `deleted_at`; every read filters
  `deleted_at IS NULL`. A hard delete cannot be merged — a stale device reinserts the row.
- **`applyMerged`** on each table is the write the sync engine issues *after* it has decided a
  winner. Mutable tables use `INSERT OR REPLACE` (last-write-wins on
  `(updated_at, device_id, id)` with a tombstone taking a tie); the three append-only tables use
  `INSERT OR IGNORE`, which is union-on-id. The comparison itself is Kotlin in the shared core,
  not SQL — it has to run over records read from Dropbox that are not yet in the database.
- **`selectChangedSince` / `selectCreatedSince`** on each table are the export side of a sync.
- No SQLDelight type adapters and no column type annotations are used yet. Plain SQLite types
  only, so these files are valid SQLite as written and can be validated without Gradle.

## Things that look like omissions and are not

- **No unique constraint on `practice_event (song_id, logged_on, instrument_id)`.** Working the
  same piece twice in a day is normal, and each pass is a real session. Adding one would make
  undo a silent no-op.
- **No unique constraint on `setlist_set (setlist_id, set_no)`.** Two offline devices may each
  add "set 3"; both rows must survive the merge.
- **`setlist_item` carries neither `setlist_id` nor `set_no`.** Membership is the foreign key
  `setlist_set_id` and nothing else.
- **`setlist_item.position` is `TEXT`**, a fractional ordering key, with `(position, id)` as the
  total order. It is not an integer index and must never become one.
- **`setlist_item` has no `lead_performer_id`.** Who performs an item is the junction
  `setlist_item_performer`, ordered by its own `INTEGER position` — 1 is the lead, 2 the
  co-lead (decisions 52, 58, 58b). That table's `position` *is* a plain integer, unlike
  `setlist_item.position`: it orders two or three people inside one item, not a list two
  devices reorder offline. "Who is singing this tonight" is the aggregate
  `selectStagedForSetlist`, written once there (decision 58c).
- **`song_performer` and `setlist_item_performer` are not the same table twice.** The first
  records who *knows* a song, the second who it is *staged with* on one night (decision 58a).
  There is no `is_duet` flag anywhere: any song can be staged as a duet.
- **`saved_view` has no `is_home` column.** The home View is a local preference in
  `SessionPreferences`, never a row (views decision V19). A flag on many rows has no total
  order under last-write-wins: two devices each promoting a different View both end up true
  and no subsequent sync repairs it. Keeping it local also lets a phone and a desktop open on
  different Views.
- **`saved_view.position` is a plain `INTEGER`**, unlike `setlist_item.position` (V18). It
  orders a handful of one user's configs, not a long list two devices reorder against each
  other; `(position, id)` is the total order and the `id` tie-break keeps two devices that both
  wrote `1` deterministic.
- **`song_performer.vocal_range` is not constrained against `instrument_id`** (V7). It is
  meaningful only on a vocal row, but SQLite cannot `CHECK` across a foreign table, so a range
  on a guitar row is nonsense the UI simply never offers.
- **No mode or quality column on `song`.** Major/minor is implied by the
  `(key_signature, tonal_centre)` pair.
- **`practice_event.logged_on` has no SQL `DEFAULT`.** "Defaults to today" means the device's
  *local* today, applied in the shared core. A SQL default would be UTC and would misdate a
  late-night session.

## Seed data and derived ids

`instrument`, `tag` and `practice_context` carry seed rows as unlabelled `INSERT` statements,
which SQLDelight runs as part of `Schema.create()`. `artist` carries exactly one seed row,
`Unknown Artist` (`cf06771d-4e8d-53fc-83fb-359be7dfaefc`), because `song.artist_id` is
`NOT NULL` (decision 28a) and the migration attaches that row where the workbook has no
artist. `groove`, `venue` and `band` are unseeded — their vocabulary arrives from the
migration and the type-ahead.

Seed ids are UUIDv5 so that a device creating the same lookup value by hand converges on the
same row. The derivation used to generate them:

```
ROOT        = UUIDv5(DNS namespace, "songbook.dev")   = 3ce0f1dc-b3b4-5aee-9683-49dfb0741ca7
namespace   = UUIDv5(ROOT, <table name>)
row id      = UUIDv5(namespace, normalise(<name>))
song id     = UUIDv5(namespace("song"), artist_id + "/" + normalise(title))
junction id = UUIDv5(namespace(table), fk_a + "/" + fk_b [+ "/" + fk_c])
```

The junction line is decision 4 **as amended**, and it holds for every junction without
exception. It replaces an earlier form, `UUIDv5(fk_a, fk_b)`, which used the first foreign key
directly as the namespace: that one carried no table identity, so two junctions over the same
pair of ids collided, and it derives entirely different ids. The foreign keys are ordered —
pass them in the order the table declares them.

**`song_performer` takes three keys** (views decision V3): `song_id`, `performer_id`,
`instrument_id`, in that order. The separator and the per-table namespace are unchanged, so the
shape generalises rather than forking. Every `song_performer` id changed when the instrument
joined the key (V4); that was acceptable only because the migration rebuilds the table wholesale
and no device had synced, and it will not be acceptable again after phase 2 ships.

Cross-checked against the migration's output: all **595** `song_performer` ids it emits were
recomputed with `Ids.songPerformer` in the shared core and matched byte for byte, 595 of 595.
The 280 `song_tag` ids it emits are untouched by the amendment and stay on the two-key form.
Three of the recomputed values are pinned in `IdsTest`, lifted from the migration's output
rather than re-derived by the test's author (V29).

Per-table namespaces, so that the tag `guitar` and the instrument `guitar` cannot collide:

| Table | Namespace UUID |
|---|---|
| `artist` | `947acc5f-6891-5179-bfdb-268d05808236` |
| `performer` | `799fddcf-482e-5e9c-bd85-3e5cbbb2f477` |
| `instrument` | `bf0c6920-7eae-590d-847d-49436fa15980` |
| `tag` | `2e80c13e-b285-5e54-b5c8-b556ba347362` |
| `groove` | `84fa607d-e399-5f04-8a32-7b3d81104268` |
| `venue` | `d14fa03f-4788-55a6-9b2c-e675c56c8829` |
| `band` | `ff2f3f80-7e27-5303-b760-a36ba4655a9b` |
| `practice_context` | `414530ce-0bcb-5557-a96a-f532ccac4bfa` |
| `song` | `7638e555-dd49-5ef9-8500-f8d3f2ec5b82` |
| `song_instrument` | `6572c369-eed2-5b33-8a1f-8d19bc16ee51` |
| `song_tag` | `e32a16d4-bfe6-5d25-a481-ae7ea033f53e` |
| `song_performer` | `f6e3d47e-9ffd-5ddc-8595-8ed9a98071f1` |
| `setlist_item_performer` | `52a976ba-d58d-5c3c-ac60-acb796d7e8cf` |

`normalise` is the single shared-core function: **Unicode-normalise to NFC** (decision 17a),
then lowercase, trim, strip a leading `The `, fold `&` to `and`, strip punctuation, collapse
whitespace. The NFC step is first and is not optional — without it the composed and decomposed
spellings of `Bublé` derive different ids — and it is a no-op on every ASCII seed name above,
so none of the ids in this file move. Note the consequence for tags — `cw-duet`
keys on `cw duet` and `need-to-learn` on `need to learn`, while the stored display name keeps
its hyphens.

**The `ROOT` namespace string is not fixed by the decision spec.** It was chosen here so seed
ids could be written down. When the shared core implements id derivation it must use exactly
these values, or the seeded rows and the rows the app derives will not converge. Changing
`ROOT` later invalidates every derived id already written to a database.

## Validating without Gradle

The Gradle module does not exist yet, so the check is: strip the labelled queries, concatenate
the `CREATE`/`INSERT` statements in the table order above, and run them through `sqlite3`.

```
sqlite3 schema-check.db ".read schema-all.sql" ".tables" "PRAGMA integrity_check;"
```

Run it against a scratch path, never inside the repo. A labelled query is a line matching
`^name:$` followed by one statement ending in `;`; everything else at top level is DDL.

Both of these must hold:

- every statement executes clean with `PRAGMA foreign_keys = ON`;
- every labelled query compiles (`EXPLAIN <query>` with placeholder bindings) against the
  created schema.

Once the Gradle module lands, `./gradlew :shared:generateCommonMainRepertosaurusDatabaseInterface`
replaces this and will additionally type-check the queries.
