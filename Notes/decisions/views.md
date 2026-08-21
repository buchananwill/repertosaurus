---
title: "Views — Decision Spec"
type: decision-spec
area: data-model
status: active
date: 2026-08-16
---

# Views — Decision Spec

A **View** is a saved, named pairing of *which songs are eligible* with *which instrument the
session is about*. It exists because those two things are currently the same control and must
not be: the instrument chip today selects what gets logged, what staleness is measured against,
**and** — by omission — nothing at all about which songs appear, so all 479 songs show on every
chip including the 98 that another singer fronts.

This spec fixes the schema change that makes performer × instrument capability expressible
(§2, V1–V9), the filtered staleness query (V10–V14), the `saved_view` table and its home-view
rule (V15–V24), and the migration's obligations (V25–V29). It carries no implementation bodies;
code blocks state settled declarations only.

Numbers are `V<n>` for citation, grouped by topic. They are **not** an execution order.

## 1. Type Roster

**SQLDelight — changed**

- `song_performer` — gains `instrument_id TEXT NOT NULL`; unique key widens from
  `(song_id, performer_id)` to `(song_id, performer_id, instrument_id)`; **every derived id in
  the table changes**.
- `song.sq :: selectByStaleness` — gains three nullable filter parameters; replaces the
  unfiltered form.
- `song.sq :: selectByStalenessInSetlist` — unchanged, and deliberately not folded into Views.

**SQLDelight — new**

- `saved_view` — mutable record, random id. One row per bookmarked View.

**Kotlin core — new**

- `dev.repertosaurus.session.ViewFilter` — the eligibility half of a View, as a value.
- `dev.repertosaurus.session.SessionView` — a resolved View: its identity, its filter, its
  practice instrument, its sort direction.
- `dev.repertosaurus.session.ViewCoordinator` — CRUD over `saved_view` and home-view resolution.
- `dev.repertosaurus.core.Ids.songPerformer(songId, performerId, instrumentId)` — the three-key
  junction derivation.
- `dev.repertosaurus.core.Ids.junction(table, fkA, fkB, fkC)` — the general three-key overload.

**Kotlin core — changed**

- `dev.repertosaurus.data.RepertosaurusRepository.songsByStaleness(instrumentId, today)` →
  `songsByStaleness(practiceInstrumentId, filter, today)`.
- `dev.repertosaurus.session.SessionCoordinator.rows(instrumentId)` →
  `rows(view: SessionView)`.
- `dev.repertosaurus.session.SessionState` — gains the active `SessionView`; `selectedInstrumentId`
  becomes the View's practice instrument rather than free-standing state.
- `dev.repertosaurus.session.SessionPreferences` — gains `homeViewId()` / `rememberHomeView(id)`.

**Python migration — changed**

- `tools/import/build.py :: build_song_performers` — emits `instrument_id`, derives the
  three-key id.
- `tools/import/extract.py :: PERFORMER_COLUMNS` — value widens from `(name, is_lead)` to
  `(name, instrument, is_lead)`.

## 2. Decisions

### The capability model

**V1.** `song_performer` gains **`instrument_id TEXT NOT NULL`** referencing `instrument(id)`,
turning the table from *who sings this song* into **who does what on this song** — which is the
only place a "songs X plays on Y" filter can read from, because `song_instrument` carries
per-instrument *facts* (difficulty, patch, notes) about the song itself and names no performer.

**V2.** The unique index becomes **`UNIQUE(song_id, performer_id, instrument_id)`**, so one
person can hold a guitar row and a vocal row on the same song without the two colliding.

**V3.** The derived id key becomes **`song_id + "/" + performer_id + "/" + instrument_id`**,
which **amends data-model decision 4** — junctions were specified as exactly two foreign keys
and this is the first three-key junction; the separator and the per-table namespace are
unchanged, so the shape generalises rather than forking.

**V4.** **Every one of the 595 existing `song_performer` ids changes** as a direct consequence
of V3, and this is acceptable **only** because the table is rebuilt wholesale by the migration
and no device has yet synced; it would not be acceptable after phase 2 ships.

**V5.** `Ids.junction` gains a **three-key overload** and `Ids.songPerformer(songId, performerId,
instrumentId)` is the named helper, mirroring the existing `Ids.setlistItemPerformer` pattern —
and per the repository's standing rule, the Kotlin and Python derivations are changed **in the
same piece of work** and cross-checked against real migration output, never against each other's
reasoning.

**V6.** `is_lead` is **scoped to its instrument**: it means lead vocal on a `vocal` row and lead
guitar on a `guitar` row, so the pair `(instrument_id, is_lead)` is the full statement of role
and no separate role vocabulary is introduced.

**V7.** `vocal_range` stays on `song_performer` and is **meaningful only on vocal rows**, left
nullable and unconstrained against instrument because SQLite cannot `CHECK` across a foreign
table; a non-null range on a guitar row is nonsense the UI simply never offers.

**V8.** **`backing vocal` remains a distinct instrument row**, not `vocal` with `is_lead = 0` —
the seed already separates them (data-model decision 18) and collapsing them would make
"Coralie sings on this, not lead" indistinguishable from "Coralie sings backing on this".

**V9.** A song with **no `song_performer` row at all** asserts nothing and is **excluded by any
performer filter**, because the alternative — treating absent data as a match — makes the filter
useless on this dataset, where 147 of 479 songs are unannotated.

### The filtered staleness query

**V10.** `selectByStaleness` gains three parameters — `:filterPerformerId`,
`:filterInstrumentId`, `:leadOnly` — and **replaces** the unfiltered query rather than sitting
beside it, so there is exactly one staleness `ORDER BY` in the schema and no second copy to
drift.

**V11.** The eligibility predicate is a single `EXISTS` over `song_performer` in which **each
filter component independently degrades to "no constraint" when null**, so all eight
combinations are one query:

```sql
AND ((:filterPerformerId IS NULL AND :filterInstrumentId IS NULL AND :leadOnly = 0)
     OR EXISTS (
        SELECT 1 FROM song_performer
        WHERE song_performer.song_id = song.id
          AND song_performer.deleted_at IS NULL
          AND (:filterPerformerId  IS NULL OR song_performer.performer_id  = :filterPerformerId)
          AND (:filterInstrumentId IS NULL OR song_performer.instrument_id = :filterInstrumentId)
          AND (:leadOnly = 0 OR song_performer.is_lead = 1)))
```

**V11a.** **The predicate additionally excludes soft-deleted performers and instruments**, joining
`performer` and `instrument` and requiring `deleted_at IS NULL` on both. The original form checked
only `song_performer.deleted_at`, which was harmless while **nothing in the app could delete a
performer**; [editing.md](editing.md) E12 makes deletion reachable from the drawer, and without
this a View keeps filtering on a removed performer and renders its summary as "a deleted
performer". Amended deliberately as that arc lands — this was not a defect in the original
predicate, it was a predicate written for a world where the row could not go away.

**V12.** The **practice instrument and the filter instrument are separate parameters** and the
practice instrument continues to appear **only in the `LEFT JOIN … ON` clause**, so a song that
passes the filter but has never been touched on the practice instrument still appears, carrying
`last_practised IS NULL` — which is the whole point of a cross-instrument View.

**V12a.** **The sort has no performer dimension, and must not grow one.** `practice_event`
carries `song_id`, `logged_on`, `instrument_id`, `context_id`, `feel` and `note` — no performer,
because every logged event is the owner's. Staleness is therefore keyed on the practice
instrument alone, and the filter is the only place a performer appears. Adding a performer to the
sort would mean logging practice on someone else's behalf, which is a different product.

**V13.** **The instrument a View logs to is the instrument it measures staleness on** — one
field, `practice_instrument_id`, not two. Today's chip already unifies log target and staleness
scope, the user's motivating case ("show songs I sing lead on, sorted by how cold their *guitar*
parts are") is a session where guitar is both measured and logged, and a third independent
instrument would be a control nobody asked for.

**V13a.** **Tapping a different instrument chip while a saved View is active forks to an unsaved
View; it never edits the saved row.** The chip is a mid-practice control on the primary tap path
and `saved_view` is a synced row, so letting a chip tap write `practice_instrument_id` would make
an idle thumb-press mutate configuration on every other device. The forked View keeps the active
View's filter and sort, takes the tapped instrument, and is discarded when the user switches away.
Editing a saved View is an explicit action in the switcher, never a side effect of practising.

**V13b.** **Changing the sort direction persists to wherever the active View came from** — to
`saved_view.sort_order` when the View is saved, to `SessionPreferences` when it is the unsaved
View of V21. This is a two-branch rule with no I/O of its own and it belongs in the shared core,
not in a ViewModel, because it is testable on the JVM and a second UI would otherwise re-derive
it. Note that under V13a a chip tap has already forked to an unsaved View, so a direction change
after one lands in preferences rather than on the saved row.

**V14.** SQL returns **coldest-first as a stable base order** and `SessionState.pending`
re-sorts in Kotlin via `SessionOrder.comparator`; the two are **not** redundant and neither may
be deleted in favour of the other, because the Kotlin comparator is what makes the direction
toggle instant and is the only place null-handling is tested.

> **Worked example — the direction is easy to invert, so trace it.** View: filter
> `(performer = Will, instrument = vocal, leadOnly = 1)`, practice instrument `guitar`, order
> `COLDEST_FIRST`.
>
> Values below are **lifted from the migrated database**, not invented.
>
> - *A Whole New World* — Will sings lead ✔ passes the filter. Never practised on guitar →
>   `last_practised = NULL` → `last_practised IS NOT NULL` evaluates to **0**.
> - *9 to 5* — Will sings lead ✔ passes the filter. Last guitar practice `2026-05-13` →
>   `last_practised IS NOT NULL` evaluates to **1**.
> - *Ain't No Sunshine* — Newton sings lead, no Will row → **absent entirely**, whatever its
>   guitar history.
>
> `ORDER BY last_practised IS NOT NULL ASC` puts **0 before 1**, so *A Whole New World* (never
> played on guitar) leads and *9 to 5* follows. Coldest-first leads with never-practised. The
> whole View returns **231 songs, 138 of them never practised on guitar**. The inversion
> trap: `SessionOrder.HOTTEST_FIRST` deliberately sorts nulls the **other** way
> (`if (daysSince == null) 1 else 0`) — that is not a bug to be reconciled with this clause,
> it is the toggle working.

### The saved View

**V15.** The table is **`saved_view`**, not `view`, because `VIEW` is a SQL keyword and a table
named for one is a permanent papercut in every hand-written query.

```sql
CREATE TABLE saved_view (
    id                     TEXT NOT NULL PRIMARY KEY,
    name                   TEXT NOT NULL,
    filter_performer_id    TEXT,
    filter_instrument_id   TEXT,
    filter_lead_only       INTEGER NOT NULL DEFAULT 0,
    practice_instrument_id TEXT NOT NULL,
    sort_order             TEXT NOT NULL,
    position               INTEGER NOT NULL,
    notes                  TEXT,

    updated_at             TEXT NOT NULL,
    deleted_at             TEXT,
    device_id              TEXT NOT NULL,

    FOREIGN KEY (filter_performer_id)    REFERENCES performer(id),
    FOREIGN KEY (filter_instrument_id)   REFERENCES instrument(id),
    FOREIGN KEY (practice_instrument_id) REFERENCES instrument(id),
    CHECK (filter_lead_only IN (0, 1)),
    CHECK (sort_order IN ('COLDEST_FIRST', 'HOTTEST_FIRST')),
    CHECK (updated_at GLOB '????-??-??T??:??:??.???Z'),
    CHECK (deleted_at IS NULL OR deleted_at GLOB '????-??-??T??:??:??.???Z')
);
```

**V16.** The id is **random UUIDv4**, not derived — `name` is the only candidate key and it is
user-editable, so deriving from it would violate the rule that an id is immutable while a name
is not; `setlist` is random for exactly this reason.

**V17.** `sort_order` is stored as the **`SessionOrder` enum name** (`COLDEST_FIRST` /
`HOTTEST_FIRST`) under a `CHECK`, not as a boolean or an integer, so the stored value reads as
what it means.

**V17a.** **The enum and the `CHECK` are two statements of one vocabulary and a test must pin them
together** — a test that iterates `SessionOrder.entries` and inserts each name into `saved_view`,
so adding a constant without widening the `CHECK` fails loudly. An earlier draft of V17 called a
third sort direction "a one-line change"; it is three (the constant, the `CHECK`, and the parse),
and adding the constant alone throws at insert while adding the `CHECK` alone makes an unknown
value silently downgrade to `COLDEST_FIRST` and be written back on the next update. Corrected
rather than deleted, because the unmeasured claim is the defect worth remembering.

**V18.** `position INTEGER NOT NULL` with reads ordered by **`(position, id)`** — a plain integer
rather than the fractional TEXT key of `setlist_item`, on the same reasoning as data-model
decision 58e: this orders a handful of a single user's configs, not a long list two devices
reorder against each other, and the `id` tie-break keeps two devices that both wrote `1`
deterministic.

**V19.** **The home View is a local preference, never a column.** An `is_home INTEGER` on many
rows has no total order under last-write-wins: two devices each promoting a different View both
end up true and no subsequent sync repairs it. `SessionPreferences.homeViewId()` stores it
alongside the existing instrument and sort preferences, which also makes a phone and a desktop
free to open on different Views — the desirable behaviour, not a compromise.

**V20.** A **stale or deleted home id resolves to the first View by `(position, id)`**, and that
resolution is **written back**, mirroring `SessionInstruments.resolve` — so a View deleted on
another device cannot leave the app opening on nothing.

**V20a.** **A View's `practice_instrument_id` is resolved against the live `instrument` table on
open, exactly as its home id is, and the repair is written back.** Instrument removal is an
unguarded soft delete that already ships, and it can also arrive by sync from another device, so
a View can name a tombstoned instrument through no fault of the user. Without this the app opens
with no chip highlighted and **every tap writes `practice_event.instrument_id` pointing at a
removed instrument** — silently, because the foreign key is still satisfied by the tombstoned row.
An unresolvable practice instrument falls back to the first chip, the same rule
`SessionInstruments.resolve` already applies. **`SessionState` carries the invariant that
`selectedInstrumentId` is always present in `instruments`.** The instrument-resolution discipline
was lost when the field moved from free-standing state onto the View; this restores it.

**V21.** **With no `saved_view` rows the app behaves exactly as it does today**: no filter, the
remembered instrument chip, the remembered sort direction. Views are additive and the empty
state is the current product, so the feature cannot regress a fresh install.

**V22.** Switching View is a **full reload** of the filter, the practice instrument and the sort,
and it **clears the pending undo offer** — which refers to a tap the user is no longer looking at
— but it **does not clear the session's optimistic taps**. An earlier draft cleared them, on the
grounds that two Views can share a practice instrument. That was wrong twice over: `tapsHere`
already scopes the logged section by practice instrument, and `logged` resolves each tap against
the current View's rows, so a song absent from the new View simply does not appear. Clearing
would instead break the ordinary practice motion of flicking guitar → bass → guitar, which today
returns you to your logged list and under the draft rule emptied it. Nothing is ever voided;
every tap is already written to `practice_event`.

**V23.** Deleting a View is a **soft delete** carrying `deleted_at`, like every other mutable
row in this schema; there is no hard delete anywhere.

**V24.** A View **stores ids, never names**, so renaming a performer or an instrument leaves
every View intact — the same discipline as `song.artist_id`.

### The migration

**V25.** `PERFORMER_COLUMNS` widens from `header -> (name, is_lead)` to
**`header -> (name, instrument, is_lead)`**, and every current singer column maps to instrument
**`vocal`** with its existing `is_lead` — `Coralie Vox` → `(Coralie, vocal, 0)`, `Will Vocal` →
`(Will, vocal, 1)`, `lead vocal` → `(Will, vocal, 1)`.

**V26.** The `ANNOTATION_COLUMNS` free-text performers map to **`vocal`** likewise: `LV` is
literally *lead vocal* → `is_lead = 1`; `Demo 2022` → `is_lead = 0`.

**V27.** **`Will plays` is deleted from `PERFORMER_COLUMNS` and added to `TRIAGE_COLUMNS`** — the
user has ruled it copy-and-paste residue from another sheet, so it is silently skipped and **not**
logged as unresolved, which is what `TRIAGE_COLUMNS` is for.

**V27a.** **`Will plays` was already emitting nothing, and an earlier draft of V27 was wrong about
why.** The column is entirely empty in the workbook: its only trace was a single
`PERFORMER-COLUMN-EMPTY` seed mark attached to no song. Removing it is hygiene, not a data fix,
and `song_performer` stays at **595 rows**. The three Will non-lead rows the earlier draft blamed
on it — *Signed, Sealed, Delivered*, *I Feel Good*, *Play That Funky Music* — come from the
**`Demo 2022`** annotation column, which V26 deliberately keeps at `is_lead = 0`; they are
retained. Will therefore holds **231 lead and 3 non-lead** vocal rows. The motivating query is
unaffected either way, because `leadOnly = 1` excludes non-lead rows regardless of their source.
Recorded because the earlier draft's claim was a plausible inference that real data refuted, and
a spec that asserts a row count nobody re-measured is how the next wrong inference survives.

**V28.** The migration **seeds no `saved_view` rows.** It is a throwaway tool and must not own
product configuration; the user builds their Views once, in the app, against V21's
current-behaviour empty state.

**V29.** The migration's emitted `song_performer` ids are **recomputed with the Kotlin
implementation and compared byte for byte** before the change is accepted, and the resulting
values are pinned in `IdsTest` **lifted from the migration's output** — not re-derived by the
test's author, which is the failure mode this rule exists to catch.

**V30.** **A `performer_marks` entry must carry its instrument explicitly; the migration may never
default it.** `extract.py` currently supplies `ANNOTATION_INSTRUMENT` ("vocal") via
`mark.get("instrument", …)`, which makes `build.py`'s `FATAL: … carries no instrument` guard
**unreachable** — the loud failure is written in the one place it cannot fire. Harmless today,
because all 595 rows are genuinely vocal; lethal the moment hand-entered guitar or bass capability
data arrives, because a mark path that forgets its instrument would emit a **vocal** row with a
derived, permanent id that collides with or shadows the real one, and nothing would fail. Read
`mark["instrument"]`, make `record_annotation`'s `instrument` parameter required, and pass
`ANNOTATION_INSTRUMENT` explicitly at each call site so V26's rule is stated where it applies.

**V31.** **`Ids.junction` must refuse two keys for `song_performer`, in both implementations.**
Kotlin keeps the two-key overload for `song_instrument`, `song_tag` and `setlist_item_performer`,
and Python's `junction_id` became variadic and thereby *lost* the arity error it used to raise —
so `junction("song_performer", song, performer)` silently returns the superseded id on both sides.
A table-aware assertion turns a permanent silent id fork into a crash.

## 3. Deferred (explicitly out of scope)

- **An owner / "me" concept.** A View names its performer explicitly, so nothing here needs to
  know which performer is the user. Worth revisiting when a default View or a "Mine" shortcut is
  wanted; today it would be a decision with no caller.
- **Set-list-scoped Views.** `selectByStalenessInSetlist` already exists and a `setlist_id`
  column on `saved_view` is the obvious extension, but "practise the Blue Lion set" is a
  different session shape and folding it in now would double the query's parameter surface
  before either half has been used in anger.
- **Capability data for guitar, bass and keys.** V1 makes it *expressible*; the workbook does not
  contain it, so a guitar-filtered View will be empty until it is entered by hand. No
  data-entry UI is specced here.
- **Tag and band filters on a View.** `bass-vox` (48 songs) is a real repertoire tag and an
  obvious second filter axis, but performer × instrument is what was asked for and one axis
  shipping is worth more than two specced.
- **A `sort_order` third direction** — most-practised, least-practised. The enum admits it; no
  one has asked.
- **Reordering Views by drag.** `position` supports it; phase 1 may write positions in creation
  order.

## 4. Retirement

- `song.sq :: selectByStaleness` in its unfiltered five-line form — replaced in place by V10, not
  kept alongside.
- `RepertosaurusRepository.songsByStaleness(instrumentId, today)` — replaced by the filter-taking
  signature; no deprecated overload is kept, because a caller that silently skips the filter is
  the exact bug this spec is fixing.
- The `(name, is_lead)` tuple shape of `PERFORMER_COLUMNS` and the two-key
  `junction_id("song_performer", …)` call in `tools/import/build.py`.
- The `"will plays": ("Will", 0)` entry in `tools/import/extract.py :: PERFORMER_COLUMNS` —
  moved to `TRIAGE_COLUMNS` per V27.
