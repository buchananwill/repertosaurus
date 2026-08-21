---
title: "Schema Compatibility and Boot Resilience — Decision Spec"
type: decision-spec
area: data-model
status: active
date: 2026-08-16
---

# Schema Compatibility and Boot Resilience — Decision Spec

The app hard-crashed on launch for a real user, twice, and the second time survived a full
uninstall–reinstall–reimport. The cause was not one bug but a missing contract: **nothing in this
codebase states what makes a database file loadable by a given build, and nothing checks it.**

This spec fixes that. Numbers are `S<n>` for citation, grouped by topic, **not** an execution
order.

## What actually happened

Adding `saved_view` and `song_performer.instrument_id` changed the schema, but SQLDelight's
`Schema.version` stayed at `1` because no `.sqm` migration exists. Measured on the three files in
the user's delivery folder: **all three carry `user_version = 1`, all three pass the import
validator's five-table check, and only one has `saved_view`.** They are indistinguishable to the
app. Importing either obsolete file succeeds and the app then dies on next boot with
`no such table: saved_view`, thrown from `RepertosaurusRepository.savedViews` on the start-up path —
before any screen is drawn, and therefore before the import UI that could fix it is reachable.

Three independent failures stacked: a version that did not move, a validator that checked the
wrong thing, and a boot path with no failure mode short of a crash.

## 1. Type Roster

**Kotlin — new**

- `dev.repertosaurus.data.SchemaCompatibility` — pure: given the tables and `user_version` present
  in a candidate database, decides `Loadable` / `TooOld` / `TooNew` / `NotRepertosaurus`.
- `dev.repertosaurus.data.DatabaseState` — what the app booted into: `Ready`, or `Unloadable` with
  the reason and the file it came from.
- `dev.repertosaurus.android.RecoveryScreen` — the screen shown for `Unloadable`.

**Kotlin — changed**

- `dev.repertosaurus.data.DatabaseHolder` — `open()` gains a compatibility gate; the import
  validator delegates to `SchemaCompatibility` instead of counting five tables.
- `dev.repertosaurus.android.SessionViewModel` — start-up tolerates `Unloadable` instead of throwing.

**SQLDelight**

- `RepertosaurusDatabase.Schema.version` → **2**, via a real migration file.
- `shared/src/commonMain/sqldelight/.../migrations/1.sqm` — 1 → 2.

## 2. Decisions

### The version is the contract

**S1.** **The schema version is bumped in the same change as any `.sq` edit that adds, removes or
retypes a table or column.** It stayed at `1` across a change that added a table and a column,
which is the single fault that made every other failure possible — two structurally different
databases both claiming to be version 1.

**S2.** **`Schema.version` becomes 2 and a real `1.sqm` migration ships**, creating `saved_view`
and adding `song_performer.instrument_id`. A database already on a device therefore upgrades in
place rather than being rejected.

**S3.** **The 1 → 2 migration does not attempt to correct derived ids, and this is a known,
recorded limitation.** `song_performer` ids moved from a two-key to a three-key derivation
(data-model 4f), and UUIDv5 is not computable in SQLite. An upgraded database keeps two-key ids; a
freshly imported one has three-key ids. **Before sync ships this is invisible. It must be resolved
before phase 2**, because two devices disagreeing on a row's id never converge. The honest fix is
re-import, which S4 makes safe to recommend.

**S4.** **A re-import is always the supported recovery**, so every failure mode this spec defines
must leave the import path reachable. That is the whole reason S8 exists.

### What makes a file loadable

**S5.** **Compatibility is decided by the tables and columns the build actually needs, not by a
hardcoded list of five.** `REQUIRED_TABLES = 5L` counting `song`, `artist`, `instrument`,
`practice_event`, `practice_event_void` was a phase-1 snapshot that silently stopped describing the
schema the moment a sixth table mattered. Derive the requirement from
`RepertosaurusDatabase.Schema` where possible; where it must be a list, that list lives beside the
schema and its staleness is a test failure, not a runtime crash.

**S6.** **The import validator and the boot gate use one implementation — `SchemaCompatibility` —
and never two.** The bug shipped precisely because the boot path and the import path disagreed
about what a valid database was: import said yes, boot said crash. One function, two callers.

**S7.** **A database that is missing something this build needs is `TooOld` and is refused at
import with a message naming what is missing and what to do.** "That database was written before
Views and is missing `saved_view` — export a fresh one from the desktop import." Accepting it and
crashing later is the behaviour being deleted. `TooNew` (a higher `user_version`) keeps its
existing rejection.

### Never crash

**S8.** **The app must not hard-crash on any database state — corrupt, stale, future, absent or
unreadable. This is a hard requirement, in alpha as much as in release.** A crash on the boot path
is uniquely bad because it removes the only route to recovery: the import UI lives inside the app,
so a database the app cannot read makes the app unfixable from the device. The user's verdict
stands as the rule — *hard crash is not acceptable, even when we're in alpha testing.*

**S9.** **Start-up wraps database access and resolves to `DatabaseState.Ready` or
`DatabaseState.Unloadable`; `Unloadable` renders `RecoveryScreen`.** That screen states what is
wrong in plain language, and offers **Import a database** and **Start fresh** (create an empty
current-schema database). It must reach the import picker without touching the session screen,
because the session screen is what cannot load.

**S10.** **`RecoveryScreen` is reachable, not theoretical: it is exercised by an automated test
that boots the app against a pre-Views database** and asserts the process survives and the screen
appears. The failure this spec exists to prevent is precisely the one nobody had a test for.

**S11.** **No `catch` introduced by this spec may swallow a failure silently.** Recovery means the
user is told and given an action; a bare `runCatching {}.getOrDefault(emptyList())` that turns a
schema error into an empty list is a worse outcome than the crash, because it presents an intact
repertoire as empty and invites the user to "fix" it by adding songs.

### Testing the layer that had none

**S12.** **`androidApp` gains a test source set, and `SessionViewModel` gains a construction
test.** Two boot crashes in one session both landed in the one layer nothing instantiates: a
property initialisation-order NPE, and this. A test that merely constructs `SessionViewModel`
against an empty, a stale and a current database and asserts the process survives would have caught
both. This is no longer deferrable.

**S13.** **Every schema change ships with a load test against the previous version's database.**
A fixture database per schema version, checked in, and a test that each one either loads or reports
`TooOld` — never throws.

## 3. Deferred

- **Automatic re-derivation of ids on upgrade** (S3). Needs a Kotlin-side data migration pass, not
  SQL. Must be settled before phase 2 sync, not before this fix.
- **Partial recovery** — salvaging practice events out of an unloadable file. Re-import covers the
  real case; this is only worth building if a user ever loses data that exists nowhere else.
- **A schema-version display in the UI.** Useful for support, not for correctness.

## 4. Retirement

- `DatabaseHolder.REQUIRED_TABLES` and the five-table `sqlite_master` count that reads it.
- The assumption, stated in `DatabaseHolder`'s import comment, that "a file holding all the phase 1
  tables is a phase 1 database". It is not, and has not been since `saved_view` landed.
