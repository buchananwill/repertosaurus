# SQLDelight schema

The Songbook local store. This is the implementation of
[Notes/decisions/data-model.md](../../../../Notes/decisions/data-model.md) — that document is
the contract, this directory is the code. Where the two disagree, the decision spec wins and
this directory is wrong.

## Layout

SQLDelight treats the directory under `sqldelight/` as the generated Kotlin package, so the
schema lives in `dev/songbook/db/`. **One `.sq` file per table, named after the table.** Each
file carries, in order:

1. A header comment citing the decisions it implements.
2. The `CREATE TABLE`.
3. Its `CREATE INDEX` statements.
4. Seed rows, where a decision specifies them.
5. The labelled queries that table needs.

| File | Table | Nature |
|---|---|---|
| [artist.sq](./dev/songbook/db/artist.sq) | `artist` | mutable record |
| [artist_alias.sq](./dev/songbook/db/artist_alias.sq) | `artist_alias` | child of artist |
| [performer.sq](./dev/songbook/db/performer.sq) | `performer` | mutable record |
| [instrument.sq](./dev/songbook/db/instrument.sq) | `instrument` | lookup, seeded |
| [tag.sq](./dev/songbook/db/tag.sq) | `tag` | lookup, seeded |
| [groove.sq](./dev/songbook/db/groove.sq) | `groove` | lookup |
| [venue.sq](./dev/songbook/db/venue.sq) | `venue` | lookup |
| [practice_context.sq](./dev/songbook/db/practice_context.sq) | `practice_context` | lookup, seeded |
| [song.sq](./dev/songbook/db/song.sq) | `song` | mutable record; **the Session screen query lives here** |
| [song_performer.sq](./dev/songbook/db/song_performer.sq) | `song_performer` | junction |
| [song_instrument.sq](./dev/songbook/db/song_instrument.sq) | `song_instrument` | junction, carries facts |
| [song_tag.sq](./dev/songbook/db/song_tag.sq) | `song_tag` | junction |
| [practice_event.sq](./dev/songbook/db/practice_event.sq) | `practice_event` | **append-only** |
| [practice_event_void.sq](./dev/songbook/db/practice_event_void.sq) | `practice_event_void` | **append-only** |
| [setlist.sq](./dev/songbook/db/setlist.sq) | `setlist` | mutable record |
| [setlist_set.sq](./dev/songbook/db/setlist_set.sq) | `setlist_set` | child of setlist |
| [setlist_item.sq](./dev/songbook/db/setlist_item.sq) | `setlist_item` | mutable record |

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
- **No mode or quality column on `song`.** Major/minor is implied by the
  `(key_signature, tonal_centre)` pair.
- **`practice_event.logged_on` has no SQL `DEFAULT`.** "Defaults to today" means the device's
  *local* today, applied in the shared core. A SQL default would be UTC and would misdate a
  late-night session.

## Seed data and derived ids

`instrument`, `tag` and `practice_context` carry seed rows as unlabelled `INSERT` statements,
which SQLDelight runs as part of `Schema.create()`. `groove` and `venue` are unseeded — their
vocabulary arrives from the migration and the type-ahead.

Seed ids are UUIDv5 so that a device creating the same lookup value by hand converges on the
same row. The derivation used to generate them:

```
ROOT       = UUIDv5(DNS namespace, "songbook.dev")   = 3ce0f1dc-b3b4-5aee-9683-49dfb0741ca7
namespace  = UUIDv5(ROOT, <table name>)
row id     = UUIDv5(namespace, normalise(<name>))
```

Per-table namespaces, so that the tag `guitar` and the instrument `guitar` cannot collide:

| Table | Namespace UUID |
|---|---|
| `artist` | `947acc5f-6891-5179-bfdb-268d05808236` |
| `performer` | `799fddcf-482e-5e9c-bd85-3e5cbbb2f477` |
| `instrument` | `bf0c6920-7eae-590d-847d-49436fa15980` |
| `tag` | `2e80c13e-b285-5e54-b5c8-b556ba347362` |
| `groove` | `84fa607d-e399-5f04-8a32-7b3d81104268` |
| `venue` | `d14fa03f-4788-55a6-9b2c-e675c56c8829` |
| `practice_context` | `414530ce-0bcb-5557-a96a-f532ccac4bfa` |

`normalise` is the single shared-core function: lowercase, trim, strip a leading `The `, fold
`&` to `and`, strip punctuation, collapse whitespace. Note the consequence for tags — `cw-duet`
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

Once the Gradle module lands, `./gradlew :shared:generateCommonMainSongbookDatabaseInterface`
replaces this and will additionally type-check the queries.
