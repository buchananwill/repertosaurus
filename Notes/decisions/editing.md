---
title: "Editing and Navigation — Decision Spec"
type: decision-spec
area: ui
status: active
date: 2026-08-17
---

# Editing and Navigation — Decision Spec

Everything in the database is currently written by the migration and read by the app. Three
things cannot be seen or changed at all from a phone: **who the performers are, what they play,
and who is recorded as knowing which song on which instrument.** This spec fixes the slice of
editability that unblocks that — the song-capability editor (§2, E1–E12), the performer roster and
the remaining lookup tables (E13–E22), and the navigation discipline that keeps the practice
logger the root of the app (E23–E29).

It carries no implementation bodies; code blocks state settled declarations only. Numbers are
`E<n>` for citation, grouped by topic, **not** an execution order.

**One thing this spec makes reachable that was previously only latent, and therefore fixes:**
soft-deleting a performer. Nothing in the app could delete one until now, so the View filter's
failure to exclude deleted performers was harmless. E12 closes it.

## 1. Type Roster

**Kotlin core — changed**

- `dev.repertaurus.session.LookupKind` — gains `TAG`, `GROOVE`, `VENUE`, `BAND`,
  `PRACTICE_CONTEXT`, `PERFORMER`, and a `hasNotes: Boolean`.
- `dev.repertaurus.session.LookupItem` — gains `subtitle: String?` and `notes: String?`.
- `dev.repertaurus.session.LookupStore` — gains `setNotes(id, notes)`.
- `dev.repertaurus.data.RepertaurusRepository` — gains the `song_performer` write path, which
  does not exist today, plus per-kind lookup reads.

**Kotlin core — new**

- `dev.repertaurus.session.SongCapability` — one resolved `song_performer` row for display:
  performer, instrument, `isLead`, `vocalRange`.
- `dev.repertaurus.session.CapabilityCoordinator` — read/add/revive/update/remove capability rows
  for one song.
- `dev.repertaurus.session.LookupStores` — the factory mapping a `LookupKind` to its store.

**SQLDelight — changed**

- `song.sq :: selectByStaleness` — the eligibility `EXISTS` gains
  `performer.deleted_at IS NULL` and `instrument.deleted_at IS NULL` (E12).
- `performer.sq`, `tag.sq`, `groove.sq`, `venue.sq`, `band.sq`, `practice_context.sq` — each gains
  the reads its store needs; none gains a new column.

**Android — new**

- `dev.repertaurus.android.SongCapabilitySheet` — the capability editor.

**Android — changed**

- `dev.repertaurus.android.SessionScreen.FeelSheet` — gains the route into the capability editor
  without moving the feel rating.
- `dev.repertaurus.android.Route` — gains one entry per manageable kind.
- `dev.repertaurus.android.RepertaurusApp` — the drawer lists them.

## 2. Decisions

### The song-capability editor

**E1.** **Recording a capability is not logging practice, and the UI must never blur the two.**
The editor writes `song_performer` rows only and **must not write `practice_event` under any
path** — "Coralie can sing this" is a fact about the repertoire, true whether or not anyone
practised today. The two are now one long-press apart, which is exactly why this is stated first.

**E2.** **The capability editor is reached from the existing long-press sheet, and the feel
rating stays at its current cost.** Decision 46 makes long-press *the* rating affordance and a
plain tap the log; the sheet therefore shows the feel chips **and** a row opening the editor, so
rating remains one long-press and never moves behind a second choice.

**E3.** **One row per `(song, performer, instrument)`**, which is exactly the unique key the
schema already carries — so Coralie holds a `vocal` row and a `backing vocal` row on the same song,
and Charlotte holds `vocal`, `backing vocal` and `keys`, with no row shadowing another.

**E4.** **`is_lead` is a per-row toggle offered on every instrument, defaulting off.** It is
scoped to its instrument — lead vocal on a `vocal` row, lead guitar on a `guitar` row — so
`backing vocal` gets no special case; a featured backing vocalist is a real thing and the data
model already expresses it.

**E5.** **Both fields are type-aheads that create on enter**, per the lookup rule the rest of the
app follows: a performer or instrument the user names and does not already exist is created rather
than blocking the save, with near-matches surfaced while typing so `Ukelele` meets `Ukulele`
before a second row is committed.

**E6.** **Adding a pair that already exists as a tombstone revives that row; it never inserts a
second.** The id is derived from `song_id/performer_id/instrument_id`, so a re-add computes the
*same* primary key and a plain `INSERT` would fail — revive means clearing `deleted_at` and
bumping `updated_at`, which is also the only outcome last-write-wins can merge.

> **Worked example — the revive path, because "add" and "insert" are not the same here.**
> Charlotte on `keys` for *Jolene* is added, then removed, then added again.
> - Add → `Ids.songPerformer(jolene, charlotte, keys)` = `X`; insert row `X`, `deleted_at = NULL`.
> - Remove → row `X` set `deleted_at = <now>`. The row is still there.
> - Add again → derives `X` again, **not** a new id. The correct write is an update clearing
>   `deleted_at`, not an insert. An insert throws on the primary key; an `INSERT OR REPLACE`
>   would silently discard the row's `notes` and `vocal_range`.

**E7.** **Removing a capability is a soft delete**, like every other mutable row in this schema —
a tombstone, never a `DELETE`, because a stale device reinserts a hard-deleted row on the next
merge.

**E8.** **`vocal_range` is offered only on `vocal` and `backing vocal` rows**, as the two-value
high/low vocabulary the schema constrains it to. Range is meaningful only for a voice, and a
non-null range on a guitar row is nonsense the UI simply never offers.

**E9.** **The editor writes through `RepertaurusRepository`, which has no `song_performer` write
path today** — the table has been read-only since the migration created it. Adding that path is
part of this work, and it derives ids with `dev.repertaurus.core.Ids.songPerformer`, never by
hand.

**E10.** **The editor lists existing rows grouped by performer**, so "who plays this" reads as a
line-up rather than a flat list of pairs, and a performer holding three instruments is one entry
with three chips rather than three entries.

**E11.** **Nothing in the editor is a tap target that logs.** No row in it is tappable-to-log and
no gesture inside it reaches the practice path, so a mis-tap while editing the line-up cannot
silently enter a practice event.

**E12.** **The View eligibility filter must exclude soft-deleted performers and instruments.**
The `EXISTS` predicate over `song_performer` currently checks only `song_performer.deleted_at`,
which was harmless while nothing could delete a performer — this spec makes deletion reachable
from the drawer, so without the amendment a View keeps filtering on a performer the user has
removed and its summary renders "a deleted performer". **This amends the View filter predicate
deliberately; it is not a defect in it.**

### The performer roster and the remaining lookups

**E13.** **`performer` is managed through the same lookup screen as the other tables, not a
bespoke one.** It has the same shape — derived id from the normalised name, a create-on-enter
type-ahead, a soft delete — and a second screen doing the same job in a different way is how two
implementations drift.

**E14.** **`LookupItem` gains `subtitle: String?`, supplied by the store**, so the shared screen
can answer "what do they play" without knowing what a performer is; for `PERFORMER` the subtitle
is the instruments the person is recorded on, for the others it is null.

**E15.** **The instruments a performer plays are derived from `song_performer`, not stored.**
There is no `performer_instrument` table and none is proposed: the capability rows already carry
the fact, and a second place to state it would drift from the first. **The accepted gap: a
performer added before any song is recorded for them shows no instruments** — the roster still
lists them, and the subtitle fills in as capabilities are entered.

**E16.** **`LookupItem` gains `notes: String?` and `LookupKind` gains `hasNotes`**, because
`performer` and `band` carry a notes column while `instrument`, `tag`, `groove`,
`practice_context` **and `venue`** do not — the screen offers the field where the kind declares it
and hides it elsewhere, rather than special-casing performer.

**E16a.** **`venue` has no notes column and does not gain one here.** An earlier draft of E16
listed it alongside `performer` and `band`; that was wrong — `venue` is `id`, `name` plus the
standard three, and the data model never gave it notes. Adding one is a schema change requiring a
version bump and a migration, which is not worth doing on a guess that nobody has asked for.
`LookupKind.VENUE.hasNotes` is `false` and the store refuses `setNotes` for it. **The kind's
`hasNotes` and the table's real shape must agree in both directions, pinned by a test** — a
mismatch here is a crash on a screen, not a compile error.

**E17.** **`usageCount` means "live rows that would be hidden by removing this one", and each
store defines what counts.** For `instrument` that is practice events; for `performer` it is
capability rows; for `tag` it is tagged songs. The field's current comment says practice events
specifically, and that stops being true the moment a second kind is wired.

**E18.** **`LookupKind` gains `TAG`, `GROOVE`, `VENUE`, `BAND`, `PRACTICE_CONTEXT` and
`PERFORMER`**, each with a store, which is the extension the abstraction was built for.

**E19.** **Wiring the `practice_context` lookup does not close the practice-context gap.** The
screen manages the *vocabulary*; the app still cannot attach a context to a logged event, which is
a separate change on the logging path. Recorded so the drawer entry is not mistaken for the
feature.

**E20.** **`artist` is not a managed lookup in this arc.** It carries `sort_name` and an alias
child table, so it is not the same shape as the others, and it is already reachable through the
add-song type-ahead. It belongs with song editing.

**E21.** **Renaming never re-derives an id.** An id is opaque and immutable once written and every
referencing row points at it; re-deriving on rename would orphan the lot.

**E22.** **A removed lookup row stays referenced by history.** Soft delete hides the row from
pickers and from View eligibility, and the practice events pointing at it survive — the screen
states how many rows removal will hide before it happens.

### Navigation

**E23.** **The practice logger is the root of the app, and every route returns to it.** This is
the product's whole premise — tap-to-log must be the thing you land on and the thing you can
always get back to — so no screen may become a place the user can be stranded in.

**E24.** **Back from any route goes straight to the logger, in at most two presses.** A route
returns to the logger in one; a detail *inside* a route — editing one performer, one capability —
is that screen's own state with its own back handler and closes first. There is no deep stack to
unwind and no route from which the logger is three presses away.

**E25.** **Routes stay a flat enum with no back stack and no navigation library.** The model is
one root plus a set of siblings, which an enum plus a back handler expresses completely; a
navigation dependency would buy argument passing and a stack this design deliberately does not
have. **Revisit when a route genuinely needs arguments or a third level** — not before.

**E26.** **The drawer is the only menu of routes**, and every manageable kind appears in it, so
"where do I edit X" has one answer rather than a scavenger hunt through long-presses.

**E27.** **Export keeps its place in the top bar as well as the drawer.** It is the user's only
backup until sync exists, and a backup behind a swipe and a scroll is a backup that does not
happen.

**E28.** **Drawer swipe-to-open stays disabled.** The horizontal drag that would open it is the
same gesture that scrolls the instrument chip row, and losing the chip row to a mis-swipe is far
worse than losing a swipe — the button opens the drawer. A new screen must not re-enable it.

**E29.** **`Route` stays in the Android module.** It is presentation, not a rule, and the desktop
UI will have a different shape; what belongs in the shared core is the *state* each screen reads,
not the enum naming them.

### The repository split

**E30.** **`RepertaurusRepository` has passed the split trigger its own deferral named, and the
deferral is hereby expired.** The architecture review granted it on a stated condition — *"split by
aggregate, at feature 2. Not yet: 330 lines, 16 members"* — and this arc took it to **698 lines and
41 public members** across five unrelated aggregates in a single pass. None of the usual reasons to
keep a class whole applies: it holds no mutable state, no lock, no coroutine scope, and no
invariant spanning two aggregates. The only thing binding the five groups is the constructor.

**E31.** **The first cut is the lookup write surface, and it happens now.** The six `*Lookup`
methods and their private helper move into `LookupTables.kt`, which is already that aggregate's
plumbing and is already in the right module — roughly 85 lines leave the repository, **no nested
row type moves**, and the only callers are the lookup store, the delegates E32 deletes, and tests.

**E32.** **The remaining split by aggregate — `CapabilityStore`, `SongCatalog`, `SavedViewStore`,
`PracticeLog` — is its own piece of work and is deliberately not a subtask of a feature arc.** The
seven nested row types are referenced as `RepertaurusRepository.Performer` and friends at ~32 sites
across 10 files, 12 of them in `androidApp`; promoting them to top-level `dev.repertaurus.data`
types is the non-mechanical part. **It must land before phase 2's sync adapter**, which adds
`selectChangedSince`/`applyMerged` across ~20 tables and would otherwise take this file past a
thousand lines.

**E33.** **The lookup key is an `internal enum` in `dev.repertaurus.data`, never a raw `String`.**
Keeping `data` free of a dependency on `session.LookupKind` is correct and is not in question — but
that argues for an enum in `data`, not for a stringly-typed key, and the string has already escaped:
the capability coordinator spells `"performer"` and `"instrument"` by hand, so a typo compiles and
throws on the editor's first create-on-enter. `LookupKind` carries the enum in place of its table
string. **Fix it at seven branches rather than at twenty** — phase 2's sync will copy whatever
precedent this file sets, into the one place where a wrong table name silently forks an id.

**E34.** **The named instrument delegates are deleted.** `addInstrument`, `renameInstrument`,
`removeInstrument` and `practiceEventsOn` lost their last production caller when `InstrumentStore`
was replaced, and `instrument` is one of seven equal kinds — a bespoke API for one of them is the
same asymmetry this spec rejects for `performer`.

**E35.** **One comparator orders capability rows, applied once.** The flat `capabilities()` list and
the grouped `lineUp()` currently disagree — the first returns instruments alphabetically from the
SQL `ORDER BY`, the second re-sorts them into chip order — so the same rows read differently on two
surfaces. The SQL clause stays as a deterministic base for a stable scan; the display rule is
applied once in Kotlin, in `capabilities()`, and the grouped view inherits it.

**E36.** **A type-ahead matcher takes the list it matches against; it does not query per
keystroke.** Every screen in the app already does this, and the two new capability suggest
functions go to disk instead. Either they take the list as a parameter or they do not exist —
there must not be a third option on the API while the editor is being written against it.

### Tombstones and atomicity

**E37.** **`update` on a capability row must never clear its tombstone.** The statement currently
writes `deleted_at` and its only caller always binds `null`, so editing a removed row silently
un-deletes it — the exact inverse of the soft-delete rule, and a second undocumented revive path
beside the deliberate one. The column leaves the `SET` list and `AND deleted_at IS NULL` joins the
`WHERE`, matching how rename and set-notes already leave tombstones alone. **The defence cannot be
a comment saying "only ever called on a live row"**: the UI dispatches each write as its own
coroutine, so a remove and an in-flight edit on the same row complete in either order, and under
last-write-wins the resurrected row carries the newer timestamp and wins on every device forever.

**E38.** **Every read-then-write path is wrapped in a transaction.** Adding a capability or a
lookup reads by derived id and then branches to insert, revive or no-op across separate statements
with nothing atomic between them, so two rapid taps both observe "absent" and the second violates
the primary key. Creating a capability performs three writes — two lookups and the row — so an
interrupted add otherwise leaves an orphan lookup behind.

**E39.** **`usageCount` counts referencing rows whose parents are all live, and the count and the
subtitle must never disagree.** The performer count currently ignores a soft-deleted instrument
while the subtitle beside it excludes one, so removing `keys` drops it from Charlotte's subtitle
while her count still includes it — two numbers describing the same data, on the same row, on the
same screen. Every count excludes rows hidden by a tombstone on any parent, and each kind's count
is pinned by its own test.

**E40.** **The capability editor hides rows whose instrument is soft-deleted, matching View
eligibility.** It already hides rows whose performer is deleted; showing the instrument case would
list a capability that no View can ever match. Consistency with the filter is what matters — a row
the app will never act on is noise in the one screen whose job is to state what is true.

**E41.** **Normalisation iterates code points, not UTF-16 units.** The Kotlin implementation drops
a non-BMP letter — each surrogate half fails `isLetterOrDigit` — while the Python migration keeps
it, so the same name yields different canonical keys and therefore **different UUIDv5 ids that
never converge**. This arc widens user-typed derived-id creation from one table to seven, which is
what promotes a latent fork to one worth closing. **Both implementations read a shared
normalisation vector file** so agreement is mechanised rather than asserted.

**E42.** **A View whose filter names a removed performer or instrument states that plainly and
offers a route to edit itself.** Excluding deleted rows from eligibility is right, but it turns
such a View into an empty list with no in-app explanation and no way out. There is no sensible
automatic repair — unlike a stale home id or practice instrument, nothing can stand in for the
performer the user meant — so the honest answer is an empty state that names the problem and opens
the View editor.

### The write path must not lie about what happened

**E43.** **Success and failure are separate state, rendered differently.** They currently share one
`message` field painted in the accent colour, so a refused write — a range rejected on a guitar
row, a constraint, a disk error — appears exactly where a confirmation appeared a moment ago and
reads as one. The state carries a success and an error channel; the error renders in the error
colour. **The arc's own test could only assert `message != null`**, so it agreed with the defect
rather than catching it: the test must distinguish them too.

**E44.** **The add form clears on confirmed success, never on dispatch.** It currently blanks both
typed names the instant the button is pressed, so a failed write leaves empty fields, a message the
user may read as success, and no record of what they typed. The practice-log path already gets this
right by rolling its optimistic tap back on failure; the capability path has no equivalent and needs
one.

**E45.** **Capability mutations are serialised, and the controls are disabled while one is in
flight.** Every action is its own coroutine with no ordering, so two writes can complete out of
order and the earlier read wins the screen — a chip the user just added vanishes, or a lead flag
reverts. The dialog compounds it by editing a **snapshot captured when the chip was tapped**: while
a write is in flight nothing is disabled, so re-tapping the stale chip and saving writes the old
values back. The dialog resolves its row from the live line-up by id on every composition, and a
result whose sequence is not the newest is dropped.

**E46.** **A display rule lives in the shared core, not in a composable — including the ones that
look too small to matter.** The capability sheet re-implements the instrument title-case helper
**character for character** from the core, and invents the chip sentence, the performer heading and
a third copy of the type-ahead browse rule, each with its own separator and its own limit constant
disagreeing with the one next door. Every one is JVM-testable, every one is needed verbatim by the
desktop UI, and this project has already forked twice on exactly this pattern. One matcher —
`PerformerSuggestions`, beside the existing artist one — and the labels on the capability types.

**E47.** **An empty list must never claim the repertoire is empty when it is not.** A View filtered
on a removed performer currently renders "No songs yet. Use Import to load your database file" on a
phone holding 479 songs, pointing the user at the one destructive path in the app. The predicate —
*does this View's filter name a row that is gone* — is a rule and belongs in the shared core beside
the View summary, not as a branch invented in a composable.

## 3. Deferred (explicitly out of scope)

- **Song field editing** — title, artist, key, tempo, decade, groove, chart URL, tags. This is the
  largest remaining hole in "everything is editable" and it is a screen of its own; doing it inside
  this arc would double the work and delay the capability editor that unblocks the actual
  complaint. **This is the next arc.**
- **Set list creation and editing.** The tables exist and nothing in the app touches them. A
  bigger piece than everything in this spec combined.
- **Artist management** (E20) — belongs with song editing.
- **Attaching a practice context when logging** (E19) — a change to the logging path, not to a
  management screen.
- **`performer_instrument` as a stored fact** (E15) — derived is honest until someone needs to
  record an instrument for a performer with no songs.
- **Reordering instruments by drag.** The chip order is applied in the shared core with no
  ordering column; making it user-editable is a schema change and should be made deliberately.

## 4. Retirement

- `LookupItem.usageCount`'s comment claiming it counts practice events specifically — false for
  every kind wired by E18.
- `LookupKind`'s comment that only `instrument` is wired, and `Lookups.kt`'s header paragraph
  saying practice context "is a real gap … but that is a later dispatch". This is that dispatch.
- The `song_performer` read-only assumption — the table gains a write path in E9.
