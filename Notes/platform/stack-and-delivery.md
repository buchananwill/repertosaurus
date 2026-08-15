---
title: "Stack and Delivery Order"
type: decision-spec
area: platform
status: active
date: 2026-08-15
---

# Stack and Delivery Order

## Stack

| Layer | Choice | Notes |
|---|---|---|
| Shared core | Kotlin Multiplatform | Merge engine, Dropbox adapter, key arithmetic, schema. Compiles to JS for the web tier. |
| Native UI | Compose Multiplatform | Android and desktop. On Android this *is* the first-party toolkit. |
| Web UI | React + TypeScript | A real DOM app over the core compiled to JS. **Not** Compose-for-Web. |
| Local store | SQLDelight over SQLite | Schema in real `.sq` files. |
| HTTP | Ktor Client | The Dropbox adapter is four methods over REST. |
| Serialization | kotlinx.serialization, kotlinx-datetime | NDJSON records. |
| Token storage | Platform keystore | Android Keystore. Never `localStorage`. |
| Packaging | APK/AAB, jpackage, static | Sideloadable APK; real desktop installers; a folder of files for web. |

Rejected, with reasons, in [the session 01 journal entry](../journal/2026-08-15-session-01.md):
Flutter, Tauri, React Native, PWA, PGlite, Google Drive.

**Do not use Compose Multiplatform for Web.** It renders the whole app to a `<canvas>`, which
breaks text input, accessibility, find-in-page and text selection, ships several megabytes, and
requires WasmGC — absent on older iOS, which is the exact audience the web tier exists to serve.

## Delivery Order

Ordered to front-load **product** risk (does tap-to-log survive a real practice session?) and
defer **engineering** risk (sync is hard, but it is *known* hard).

### Phase 0 — Import script

Not app code. A throwaway desktop script, run once, that never ships.

Two-pass round trip: read `Songs 2026` → emit a single clean review sheet with flagged rows →
the user resolves conflicts in Google Sheets, where they are already fluent → re-read that →
emit the app database. **Do not build a conflict-resolution UI for a job done once.**

Detail in [import-songs-2026.md](../migration/import-songs-2026.md).

### Phase 1 — Android app, standalone

The app with no sync. Import a database file; log practice; browse the library. This is the
QA vehicle: the user runs their real practice sessions on it for a fortnight.

Required in the first build:

- **Import from file**, so phase 0's output can be loaded.
- **Export database to file**, so a fortnight of real logging is not held hostage to an
  un-synced phone. This is also the manual Dropbox path until phase 2.

Known and accepted risk: app-private storage is wiped on uninstall, so a reinstall to pick up a
new build loses data. The user has ruled this acceptable — see *Explicitly deferred* in the
session 01 journal entry. **Do not build migration infrastructure in phase 1.**

Phase 1 exists to answer questions that cannot be answered from a spec. Instrument nothing;
just use it and find out:

- Is coldest-first the right default order, or is set-list-scoped reached for more often?
- Should the app remember the discipline chip rather than asking each session?
- Does a logged row vanishing from the list help or irritate?
- How often is logging for a past date actually needed?
- Is long-press for `feel` discoverable, or does it need a visible affordance?

### Phase 2 — Dropbox adapter

The shared-core sync layer. Detail in [../decisions/sync-protocol.md](../decisions/sync-protocol.md)
once written; the shape is settled and recorded in the session 01 journal entry.

### Phase 3 — Desktop app

Compose Desktop, jpackage installers. Uses the **folder adapter** — a native directory picker
at the Dropbox-synced path — so the desktop build needs no OAuth code at all. Only Android and
web run the PKCE flow.

### Phase 4 — Web app

React + TypeScript over the core compiled to JS. Reaches iOS users with no Mac, no $99/yr Apple
account and no App Store review, and picks up Windows, Linux and ChromeOS on the way.

Deliberately **smaller than native**, which is what stops two UIs meaning twice the work:

- Web does the high-frequency, low-complexity half — log a session, browse the library, view
  and print a set list.
- Native keeps set list building, drag-reorder, bulk transpose, import.
- Web skips SQLite entirely. Under 2 MB of data means in-memory plus NDJSON in IndexedDB.
- **Dropbox sync is mandatory in the web tier**, because browser storage can be evicted. The
  cloud copy is the durable one there. "Offline, no backup" remains available on Android and
  desktop, where the data is a real file.

### Phase 5 — Play Store, then possibly native iOS

Play Store is a $25 one-off. Native iOS needs a Mac, $99/yr and review, and **may never be
necessary** — decide it on evidence from phase 4, not on principle.

## The SQLite you can actually write against

**minSdk 26 means SQLite 3.19.** Android ships the system SQLite, and API 26 (Android 8) is
stuck at 3.19 — so a whole class of modern SQL is unavailable no matter what the desktop
`sqlite3` binary or the CI machine accepts:

| Feature | Needs | Available at minSdk 26 |
|---|---|---|
| `ORDER BY` inside an aggregate (`GROUP_CONCAT(x ORDER BY y)`) | 3.44 | **no** |
| Window functions | 3.25 | **no** |
| `UPSERT` (`ON CONFLICT DO UPDATE`) | 3.24 | **no** |

This has already changed a design. Decision 58 requires a *deterministic* order inside the
"who is staged tonight" aggregate, and neither an ordered aggregate nor a window function is
reachable. A `LEFT JOIN (… GROUP BY …)` looks like the answer and is not: it puts a grouping
sort between the `ORDER BY` and the aggregate, and SQLite's sorter is not documented as stable,
so the order would be **incidental** — exactly what decision 58 rules out. The working form is a
correlated scalar subquery, whose aggregate has no `GROUP BY` and so cannot have its input
reshuffled, and whose inner `ORDER BY` survives because SQLite may not flatten an `ORDER BY`
subquery into an aggregate outer query.

**Test ordering by inserting in the wrong order.** A query that happens to return rows in
insertion order passes a naive test and fails in the field.

Raising minSdk later relaxes this; until then, assume 3.19 and verify anything clever.

## Test Target

Samsung Galaxy S20, Android 13. The Session screen must feel right there before anything else
is considered done.
