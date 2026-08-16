# SQLDelight schema

The Repertaurus local store. This is the implementation of
[Notes/decisions/data-model.md](../../../../Notes/decisions/data-model.md) — that document is
the contract, this directory is the code. Where the two disagree, the decision spec wins and
this directory is wrong.

## Layout

SQLDelight treats the directory under `sqldelight/` as the generated Kotlin package, so the
schema lives in `dev/repertaurus/db/`. **One `.sq` file per table, named after the table.** Each
file carries, in order:

1. A header comment citing the decisions it implements.
2. The `CREATE TABLE`.
3. Its `CREATE INDEX` statements.
4. Seed rows, where a decision specifies them.
5. The labelled queries that table needs.

| File | Table | Nature |
|---|---|---|
| [artist.sq](./dev/repertaurus/db/artist.sq) | `artist` | mutable record, seeded (`Unknown Artist`) |
| [artist_alias.sq](./dev/repertaurus/db/artist_alias.sq) | `artist_alias` | child of artist |
| [performer.sq](./dev/repertaurus/db/performer.sq) | `performer` | mutable record |
| [instrument.sq](./dev/repertaurus/db/instrument.sq) | `instrument` | lookup, seeded |
| [tag.sq](./dev/repertaurus/db/tag.sq) | `tag` | lookup, seeded |
| [groove.sq](./dev/repertaurus/db/groove.sq) | `groove` | lookup |
| [venue.sq](./dev/repertaurus/db/venue.sq) | `venue` | lookup |
| [band.sq](./dev/repertaurus/db/band.sq) | `band` | lookup |
| [practice_context.sq](./dev/repertaurus/db/practice_context.sq) | `practice_context` | lookup, seeded |
| [song.sq](./dev/repertaurus/db/song.sq) | `song` | mutable record; **the Session screen query lives here** |
| [song_performer.sq](./dev/repertaurus/db/song_performer.sq) | `song_performer` | junction |
| [song_instrument.sq](./dev/repertaurus/db/song_instrument.sq) | `song_instrument` | junction, carries facts |
| [song_tag.sq](./dev/repertaurus/db/song_tag.sq) | `song_tag` | junction |
| [practice_event.sq](./dev/repertaurus/db/practice_event.sq) | `practice_event` | **append-only** |
| [practice_event_void.sq](./dev/repertaurus/db/practice_event_void.sq) | `practice_event_void` | **append-only** |
| [setlist.sq](./dev/repertaurus/db/setlist.sq) | `setlist` | mutable record |
| [setlist_set.sq](./dev/repertaurus/db/setlist_set.sq) | `setlist_set` | child of setlist |
| [setlist_item.sq](./dev/repertaurus/db/setlist_item.sq) | `setlist_item` | mutable record |
| [setlist_item_performer.sq](./dev/repertaurus/db/setlist_item_performer.sq) | `setlist_item_performer` | junction |

Create tables in that table order if you replay the DDL by hand; it is dependency-ordered.

## Conventions that hold everywhere

- **Ids are `TEXT`.** UUIDv4 or UUIDv5, generated client-side. No autoincrement anywhere.
- **The standard three.** Every mutable table — including children and junctions — carries
  `updated_at TEXT NOT NULL`, `deleted_at TEXT` and `device_id TEXT NOT NULL`. The two
  append-only tables carry `created_at TEXT NOT NULL` and `device_id`, and **no `deleted_at`**.
- **Timestamps** are `YYYY-MM-DDTHH:MM:SS.sssZ`, always three decimals, always literal `Z`,
  enforced by a `GLOB` `CHECK` on every timestamp column. Variable precision breaks string
  comparison, and string comparison is what the merge order is built on.
- **Dates** are `YYYY-MM-DD`, likewise `CHECK`ed.
- **Deletes are soft.** `softDelete` sets `deleted_at`; every read filters
  `deleted_at IS NULL`. A hard delete cannot be merged — a stale device reinserts the row.
- **`applyMerged`** on each table is the write the sync engine issues *after* it has decided a
  winner. Mutable tables use `INSERT OR REPLACE` (last-write-wins on
  `(updated_at, device_id, id)` with a tombstone taking a tie); the two append-only tables use
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
junction id = UUIDv5(namespace(table), fk_a + "/" + fk_b)
```

The junction line is decision 4 **as amended**, and it holds for every junction without
exception. It replaces an earlier form, `UUIDv5(fk_a, fk_b)`, which used the first foreign key
directly as the namespace: that one carried no table identity, so two junctions over the same
pair of ids collided, and it derives entirely different ids. Cross-checked against the
migration's output: all 789 junction ids it emits (507 `song_performer`, 282 `song_tag`) match
the form above and **none** match the old one. The two foreign keys are ordered — pass them in
the order the table declares them.

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

Once the Gradle module lands, `./gradlew :shared:generateCommonMainRepertaurusDatabaseInterface`
replaces this and will additionally type-check the queries.
