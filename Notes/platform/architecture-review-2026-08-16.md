---
title: "Architecture Review — Does This Absorb the Next Six Features?"
type: workflow
area: platform
status: active
date: 2026-08-16
---

# Architecture Review — Does This Absorb the Next Six Features?

Read-only review run at the end of phase 1, before feature growth. Reviewed against the six
features known to be coming — practice_context selection, song editing, set list building, library
browser, Dropbox sync, and the desktop/web targets — rather than for general tidiness.

**Verdict: the schema and the pure core are ahead of the feature list; the Kotlin layer between
them is behind it.** Nothing needs a rewrite. Four things get more expensive with every screen.

## Do these first, in this order

### 1. Add the `jvm()` target — nothing enforces that `commonMain` is platform-free

`shared/build.gradle.kts` declares **one** target, `androidTarget()`. `commonMain` has therefore
only ever been compiled by the Android toolchain. It is currently clean — zero `android.*`,
`androidx.*`, `java.*` imports, `Sha1` hand-rolled rather than `MessageDigest`, the one `expect`
having its actual in `androidMain` — but **by luck, not by enforcement**, and nobody will notice the
day it stops being.

Adding `jvm()` now costs ~8 lines of Gradle and ~15 of Kotlin (the `unicodeNormalise` actual, a copy
of the Android one). From that commit the compiler enforces platform-freedom. Free side effect: the
tests in `androidUnitTest` already use the JVM SQLite driver and can move to `jvmTest`.

Defer it and the first `jvm()` at phase 3 surfaces every accumulated leak at once, in code written
across four intervening features. **Cheapest thing to fix, most expensive to defer.**

### 2. Collapse the duplicated tap construction — the tested path is not the shipped path

`SessionCoordinator.newTap()` has **zero production callers**. `SessionViewModel.log()`
re-implements id allocation, note trimming and the `loggedOn` default inline.

They already diverge: `newTap` uses the repository's **injected** clock and timezone; `log` uses
`Timestamps.today()`, the system clock. Every test that fixes the clock exercises a path the app
never runs. A test asserting blank-note-becomes-null protects a function nothing calls.

Feature 1 adds `contextId` to the tap — and `RepertaurusRepository.logPractice` already accepts it while
`SessionCoordinator.persist` does not pass it, which is why every event is currently NULL. Editing
one copy and not the other silently reproduces that. **Two lines net; do it before feature 1 touches
either copy.**

### 3. Generalise the lookup layer — before the second kind exists

`Lookups.kt` genuinely generalises at the top: `LookupKind`, `LookupStore` and `ManageLookupScreen`
know nothing about instruments. The layer beneath does not — `RepertaurusRepository` carries five
methods hardcoded to `instrumentQueries`, and SQLDelight generates each table's `Queries` class with
no common supertype, so no generic method can span them.

Seven lookup kinds × five methods = **35 near-identical methods**, each with its own soft-delete and
derived-id logic that must not drift. The `.sq` layer already supports all seven — every lookup table
has the identical query set. Only Kotlin is refusing the generalisation.

Fix: a `LookupTable` value holding the five function references bound to the right generated
`Queries`, and one `RepertaurusRepository.lookup(kind)`. Per-kind variation that genuinely survives —
decision 18's seed ordering — stays as an override. ~40 lines added, ~60 deleted; marginal cost of
kinds 3–7 drops to one enum entry each.

### 4. `NearMatches.exact` — stops the third copy of the duplicate-detection rule

`SessionScreen` and `ManageLookupScreen` compute "did the user type something that already exists"
with the same code inside composables, unreachable from a JVM test. They **already differ** in where
the empty guard sits. Feature 1 makes it three copies; feature 2 makes it five.

`NearMatches.rank` already returns 0 for an exact normalised match. One function beside it collapses
both call sites to a line and makes the rule testable. **~15 lines.**

## Fix when the feature needs it

- **`RepertaurusRepository` will become a god object — split by aggregate, at feature 2.** Not yet: 330
  lines, 16 members. But sync alone adds ~76 methods (`selectChangedSince` + `applyMerged` × 19
  tables). Split by *aggregate* — Song, Practice, Setlist, Lookup, Sync — not by screen (two screens
  read staleness, two read songs) and not by read/write (which would separate the derived-id rule
  from the row it resolves against, and `addInstrument` is correct precisely because they sit
  together). `SyncRepository` is separate because it treats all 19 tables uniformly and needs no
  aggregate knowledge; folding it in would spread decision 11's merge rule across four files.
- **`SessionPreferences` grows a method pair per preference, per platform.** Two preferences today,
  six projected → 12 interface methods × 4 implementations = 48 trivial bodies. Split into a
  two-method `KeyValueStore` plus a `SessionPreferences` **class** holding the typed accessors and
  defaults, written and tested once. Cheapest at exactly two implementations, which is now.
- **No reactive read path, and phase 2 needs one.** Reads are blocking; the UI refreshes by calling
  `reload()` after mutations. A background sync writing rows leaves every open screen stale.
  **SQLDelight's `asFlow()` is not sufficient here**: import calls `close()` then `open()` and builds
  a new driver, so any listener bound to the old one dies silently. A `MutableStateFlow<Long>`
  generation counter on `DatabaseHolder`, bumped on write-commit and import-swap, survives the swap.
  ~20 lines, before phase 2 is dispatched.
- **`setlist_item_performer` can be soft-deleted but not restored.** No `restore:` query and
  `setPosition` does not clear `deleted_at`, while the derived id means re-inserting hits the primary
  key. Decision 58f makes removal a soft delete and re-staging the same person is routine — it will
  throw. Four lines, modelled on `song_tag.sq`.
- **`Ids.random` on JS is `Math.random()`-backed** — 32–53 bits of state, not the 122 a UUIDv4
  implies. Decision 6 requires random ids for `practice_event`, and decision 11 merges them **by
  union**, so a collision does not conflict — it silently drops one of two events. Needs an
  `expect fun secureRandomBytes`. Cheapest once the `jvm()` target exists.

## Smaller, real

- `sortName` is character-identical in `RepertaurusRepository` and `SampleData` — decision 21's article
  rule in two places.
- `SessionState.matches` calls `normalise(query).split(' ')` **per row**: ~960 NFC passes per
  keystroke at 479 songs. Hoist to a `by lazy val`; feature 4 makes this the browser's hot path on a
  device nobody has measured.
- `SampleData` ships in the production binary and **materialises all 479 songs with every column
  before first paint** just to test emptiness. Gate behind a debug flag now the real repertoire has
  landed, and use a `LIMIT 1`.
- `Ids.namespaces` is a `MutableMap` mutated via `getOrPut` from `Dispatchers.IO`. Idempotent, so the
  realistic worst case is recomputation — but it is 8 static entries and should be an eager `mapOf`.
- The heat threshold (`days >= 30`) lives only in the UI; feature 4 will re-express or diverge from
  it. A `heat` enum in the core is a dozen lines.
- No reusable `TypeAheadField`. Extract on the second use, not now.

## Testability

The arrangement is close to right — `SessionState` is a pure value with pure transitions, fully
covered on the JVM, as are `Keys`, `Ids`, `Normalise`, `NearMatches`.

**The untestable surface is `SessionViewModel`: 436 lines, zero tests.** Most of it is
platform-agnostic orchestration that only *looks* Android. Genuinely Android: `viewModelScope`, and
import/export's streams. Crucially the subtlest mechanic in the app lives there untested — the
`writes: MutableMap<String, Deferred<...>>` bookkeeping that lets an undo be offered *before* its
insert has landed. That is a race, on the product's highest-frequency path, and it will be written
again for desktop and again for web.

Once `jvm()` exists, lift that orchestration into a `SessionPresenter` in `commonMain` taking a
`CoroutineScope` and dispatcher as constructor parameters — the ViewModel already parameterises the
dispatcher, so the shape is there. Requires adding `kotlinx-coroutines-core` to `commonMain`, which
currently arrives only transitively through `androidApp`.

## Deliberately fine — do not "fix" these

1. **The `.sq` layer being ahead of the Kotlin is the right way round.** Every table already carries
   `selectChangedSince`/`applyMerged` for phase 2; `setlist_item.sq` already has fractional-key
   allocation and `transposeSelection`. Not speculative generality — each query implements a ratified
   decision.
2. **Sorting in SQL *and* in Kotlin is not duplication.** SQL gives a stable self-describing result;
   the Kotlin comparator implements the user-facing toggle *including explicit null placement in both
   directions*, which is the whole point and which two `ORDER BY` clauses would drift on.
3. **`daysSince` in Kotlin not SQL** — documented, with a concrete nullability reason. Decisions 48
   and 57 agree.
4. **`Keys.kt` has no production caller.** 138 lines of transposition arithmetic, tested across the
   15×12 grid, before the screen that needs it exists. That is decision 57 done in the right order.
5. **`RepertaurusRepository` never returns a SQLDelight-generated type** — every method maps to a
   hand-written data class. That is why extracting an interface for the web tier's non-SQLite store
   will be mechanical. **Hold this line: no generated row type in a public signature, ever.**
6. **`AppGraph` as a hand-rolled singleton.** Three dependencies; a DI framework would be more
   machinery than the app has moving parts.
7. **`InstrumentStore.items()`'s count-per-row.** N+1, documented, correct at 5–12 rows with an
   indexed count. Do not turn it into a join.
8. **`SessionScreen.kt` at 620 lines.** Six cohesive composables, a third comments, nothing nesting
   deeper than four. Its problem is a missing extraction, not its length.
9. **State is genuinely out of the composables.** What remains is form-local, which is not business
   logic.
