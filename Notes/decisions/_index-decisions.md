---
title: "Decisions Index"
type: decision-spec
area: decisions
status: active
date: 2026-08-15
---

# Decisions Index

Contracts implementers execute against.

- [Data Model — Decision Spec](data-model.md) — The schema. Six tables, the immutable practice
  log, the three-field key model, and the two merge invariants the schema must satisfy.
- [Product Name — Decision Spec](naming.md) — The app is **Repertosaurus**, with a dinosaur logo
  and a "monster performer" brand idea; the tagline is still open. Carries the rejected
  candidates with the evidence that killed each, the IP exposure on both the name and the logo,
  the illustrator's do/don't brief, and the rename scope. Read **N13** before touching id
  derivation: the `songbook.dev` UUID namespace deliberately survives the rename.
- [Views — Decision Spec](views.md) — Saved, named pairings of *which songs are eligible* with
  *which instrument the session is about*. Adds `instrument_id` to `song_performer` (the first
  three-key junction, amending data-model decision 4) and the `saved_view` table. Read **V4**
  before implementing: every existing `song_performer` id changes, and that is only safe before
  phase 2 ships.

- [Schema Compatibility and Boot Resilience — Decision Spec](schema-compatibility.md) — Written
  after the app hard-crashed on launch for a real user and survived a full
  uninstall–reinstall–reimport. **S8 is the load-bearing rule: the app must never hard-crash on any
  database state, in alpha as much as in release.** Also carries S1 (the schema version moves with
  the schema), S6 (one compatibility check, not one per call path) and S12 (`androidApp` gets a test
  source set). Read **S3** before phase 2: a migrated database keeps two-key `song_performer` ids
  while an imported one has three-key ids, and SQL cannot recompute them.

- [Editing and Navigation — Decision Spec](editing.md) — The editability arc: a song-capability
  editor off the long-press sheet (who plays what, recorded **without** logging practice), the
  performer roster and the remaining lookup tables in the drawer, and the rule that the practice
  logger is the root every route returns to. **E12** amends the View eligibility filter to exclude
  soft-deleted performers — latent until this arc made deletion reachable. Song field editing is
  explicitly the *next* arc, not this one.

## Not yet written

- **Sync protocol** — the four-method storage interface, the device-owned file layout, and the
  merge rules. Shape is settled and recorded in
  [the session 01 journal entry](../journal/2026-08-15-session-01.md). **Write this up as a
  spec before dispatching phase 2** — it is the next substantial piece of work, and the two
  merge rules in decisions 11–14 are the whole contract it must satisfy.
- **UI spec** — never written, and phase 1 shipped without it. The Session screen's open
  questions were deliberately left for real use to answer rather than specified in advance;
  what it actually does now lives in the code and the session 03/04 journal entries. Write one
  only if a second implementation needs it.
