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

- [Repertoire Editing — Decision Spec](repertoire-editing.md) — That next arc. Three drawer routes:
  **Repertoire** (performer → role → toggle every song on or off for that pairing), **Songs** (every
  editable field of a song, plus tags, `song_instrument`, line-up and practice history) and
  **Artists**. Governing principle from the user: too many routes beats anything inaccessible.
  Read **R22–R23** before touching song creation: renaming never re-derives the id, and the UI
  names the row an add resolved to.

- [Rating Scale and Colour Ramp — Decision Spec](rating-scale.md) — Habit arc package P3. The
  shared 0–3 vocabulary ("not at all, somewhat, certainly, exceptionally"), three user-selectable
  colour ramps held in the shared core, the one-tap `RatingSegmentedControl`, and the days-since
  heat mapping. **RS3:** stored feel 1/2/3 read as somewhat/certainly/exceptionally, never
  rewritten. **RS8:** the ramp comes from one `CompositionLocal`, never a parameter.

- [Schema 3 — Decision Spec](schema-3.md) — Habit arc packages P1 and P2. One 2 → 3
  migration:
  - `part_rating`: priority and confidence as separate rows per song–performer–role, with a
    derived id;
  - `practice_event.feel` widened to 0–3, plus `duration_seconds`;
  - the append-only `suggestion_skip`;
  - the four triage sort names.

  Read **M18** before touching the migration: foreign keys are enforced during the upgrade, so
  rebuilding `practice_event` must move its voids out of the way first.

- [Suggest — Decision Spec](suggest.md) — Habit arc packages P4, P5 and half of P9:
  - a top-bar Suggest action opening a card: "Log it", "Another" or dismiss;
  - a no-repeat deck over the View's unlogged rows;
  - `exp(ln16 · Σ r·f)` weighting, so all radii at zero is exactly pure shuffle and coldness and
    hotness cancel;
  - a five-spoke radar in the sheet.

  **SG3:** dismissing is never a skip. **SG12:** counted skips are opt-in and written only when
  the 5 s undo window closes.

- [Scorecards — Decision Spec](scorecards.md) — Habit arc package P6. A new drawer route:
  - a 26-week contribution grid shaded by live events per day;
  - weekday reliability as fractions ("Tuesdays: 19 of 26");
  - week and month summaries.

  **SC6:** empty days are neutral outlines, and the rating ramp is not used, because a one-log
  day must not paint red. **SC12:** no streaks.

- [Triage and the Ratings Editor — Decision Spec](triage.md) — Habit arc packages P7, P8 and
  half of P9:
  - one ratings editor, reached from a Repertoire role or from the active View, paged A–Z with
    search and an All/Unrated/Rated filter;
  - a sort control split into mode (Cold/Priority/Confidence) and direction;
  - lexicographic triage comparators with staleness as the tie-breaker.

  **T8:** unrated sorts last in both directions. **T9** amends views.md V12a: ratings resolve
  through the View's performer, or else the owner.

- [First-Run Onboarding — Decision Spec](onboarding.md) — Habit arc package P14. It shows once,
  including on installs that already have data, and asks two questions: the colour ramp (Danger →
  safe preselected) and which performer is you. Every answer is written as it is chosen, and every
  step has a one-tap "Skip setup". **OB5:** it is composed inside the colour-ramp provider.

- [Visual Identity — Decision Spec](visual-identity.md) — Habit arc package P13b, the user-approved
  "handed on" look:
  - a stone ground, indigo, madder and ochre inks on charcoal;
  - every shape square, with no rotation, and hard unblurred shadows;
  - Big Shoulders Display over Barlow Semi Condensed, bundled for offline use;
  - a fine paper grain, an indigo header gradient, and the stencilled hand as a sparing motif.

  **VI8:** every label survives a 1.3 font scale. **No halftone, and nothing that reads as the
  Anthropic brand.**

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
