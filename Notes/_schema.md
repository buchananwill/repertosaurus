---
title: "Documentation Schema and Conventions"
type: design-vision
area: meta
status: active
date: 2026-08-15
---

# Documentation Schema and Conventions

Conventions for all documentation in `Notes/`. Mirrors the scheme used in the Piste Perfect
repository so both projects read the same way.

## Front Matter Schema

Every markdown file in `Notes/` carries YAML front matter:

```yaml
---
title: "Human-readable document title"
type: design-vision | decision-spec | workflow | commentary | technical-reference | issue
area: meta | product | data-model | sync | platform | migration | ui | journal
status: active | done | archived | superseded | draft   # design-vision: see below
superseded_by: relative/path.md          # optional, only when status: superseded
date: YYYY-MM-DD
---
```

**type**

- `design-vision` — What a system is or should be. Remains useful as reference after
  implementation.
- `decision-spec` — A contract an implementer executes against: a roster of types plus
  numbered, fixed design decisions. Retained after implementation as the rationale for why the
  code has the shape it does.
- `workflow` — How to get there: implementation plans, phase tasks, reviews, follow-ups.
- `commentary` — Journal entries, development logs, observational notes.
- `technical-reference` — Derivations, algorithms, protocol detail supporting implementation.
- `issue` — A recorded defect or investigation, with reproduction detail and resolution state.

**design-vision statuses** (adopted 2026-09-23 from the Piste Perfect schema,
`D:\Coding\resort_game\PistePerfect_5_7\Notes\_schema.md`). A vision uses the product's own
language, argues "why" aesthetically as well as functionally, and carries no ordering. It takes:

- `speculative`: written, not yet chosen for pursuit.
- `active`: the user has ruled it worth narrowing into specs.
- `rejected`: the user has ruled against it.
- `superseded`: replaced by the vision its `superseded_by` names.

**Only the user moves a vision to `active`, `rejected` or `superseded`.** Index files (`_*.md`)
take `active` whatever their type.

**area** — Controlled vocabulary matching the topic folders. Pick the dominant area when a
document spans several.

**status** — Describes the **document**, not the work. A doc is `active` while it is still
true, and `done` once the thing it describes has shipped and the doc stands as the record of
it.

**date** — Authored or last major revision, best effort.

## Indexing Convention

1. **Root index** (`Notes/_index.md`) — lists every domain folder with a one-line summary and a
   link to its sub-index. Does not list individual documents.
2. **Domain sub-indices** (`Notes/<domain>/_index-<domain>.md`) — list every document in that
   folder with a brief description.

## The Journal Is Different

`Notes/journal/` is chronological and append-only. Entries are never edited to reflect later
understanding — a decision that was later reversed stays written as it was made, and the
reversal is recorded in the entry where it happened. The journal's value is that it shows the
reasoning as it actually developed, including the wrong turns.

Its sub-index lists entries newest-first, because the newest is the one a fresh context needs.
