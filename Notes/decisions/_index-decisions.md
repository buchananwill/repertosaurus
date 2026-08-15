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
