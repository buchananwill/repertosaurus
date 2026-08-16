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
- [Product Name — Decision Spec](naming.md) — The app is **Repertaurus**, with a dinosaur logo
  and a "monster performer" brand idea; the tagline is still open. Carries the rejected
  candidates with the evidence that killed each, the IP exposure on both the name and the logo,
  the illustrator's do/don't brief, and the rename scope. Read **N13** before touching id
  derivation: the `songbook.dev` UUID namespace deliberately survives the rename.

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
