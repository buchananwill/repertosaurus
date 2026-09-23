---
title: "Suggest — Decision Spec"
type: decision-spec
area: product
status: active
date: 2026-09-23
---

# Suggest — Decision Spec

Roadmap packages **P4** (this spec), **P5** (Suggest v1) and the Suggest half of **P9** (v2), in
[habit-arc-roadmap.md](../roadmap/habit-arc-roadmap.md). The vision's "Suggest: a way out of the
rut" is the brief: a button the musician presses when they want an idea. It is playful, like a
shuffled deck, and **declining costs nothing**. The musician tunes it with a radar whose every
radius at zero is pure shuffle.

**The obligation test** (roadmap §2): Suggest appears only when asked for, never logs by itself,
never counts a skip unless the musician opted in, and dismissing it is not a skip.

Numbers are `SG<n>`, grouped by topic. They are **not** an execution order. Each decision is
marked with the version that implements it: **[v1]** is P5, and **[v2]** is P9, after schema 3
and the ratings editor.

## 1. Type Roster

**Kotlin core, new** (`dev.repertosaurus.session`, or a sibling `suggest` package, following
local convention):
- `SuggestTuning`: five radii in `[0, 1]`, plus `countSkips` and `showSkipCount`.
- `SuggestCandidate`: `songId`, `daysSince`, `priority: RatingLevel?`, `confidence: RatingLevel?`,
  `skips: Int`.
- `Suggester`: pure weighting and drawing, with the random source injected.
- `SuggestionDeck`: the state of one open suggestion sheet, holding what has been shown, the
  current suggestion and the pending skip.

**Kotlin core, changed**
- `SessionPreferences` gains `suggestTuning()` / `rememberSuggestTuning(...)`.

**Android, new**
- `SuggestUi.kt`: the suggestion sheet, the card and the radar control.

**Android, changed**
- `SessionScreen.kt`: one top-bar action that opens the sheet.
- `SessionViewModel.kt`: it exposes the deck, and "Log it" delegates to the existing log path.
- `AppGraph.kt` persists the tuning.

## 2. Decisions

### Entry and the card

**SG1. [v1]** **Suggest is one top-bar action on the session screen** (a dice or shuffle icon,
with a content description of "Suggest a song"). It is not a floating button, because the floating
button already adds a song, and not a row in the list. Opening it costs one tap and adds nothing
to the tap path.

**SG2. [v1]** **It opens a bottom sheet showing one suggestion card.** The card shows the song
title, the artist and the staleness badge (heat-coloured, rating-scale RS12). It has three
actions:
- **"Log it"**, which logs through the **same** repository call as a plain tap: feel null, no
  duration, the View's practice instrument. It closes the sheet and shows the standard undo
  snackbar.
- **"Another"**, which draws the next suggestion, per SG6.
- Dismissing the sheet (swiping it down or tapping outside).

**A suggestion never logs anything by itself** (vision).

**SG3. [v1]** **Dismissing the sheet is never a skip.** Only "Another" is a skip, and it is
counted only under SG12. Walking away from the suggester must leave no trace.

**SG4. [v1]** **When the pool is empty,** the card says "Everything here is logged today", with
no draw and no error styling. When every song in the pool has been shown once, the card says so,
and "Another" starts a fresh deck (SG6).

### The pool and the draw

**SG5. [v1]** **The pool is the active View's rows that have not been logged this session:**
`SessionState.rows` minus the logged songs, **ignoring the search query**. The View defines
the performer and role (vision). The practice instrument defines staleness. A search is a
navigation aid, not a filter on ideas.

**SG6. [v1]** **A deck, not a die: within one open sheet, a song shown is not shown again** until
every song in the pool has been. Closing the sheet discards the deck, and reopening it starts
fresh. This is the vision's "shuffled deck", and it makes "Another" always show something new.

**SG7. [v1]** **The draw is weighted random with an injected `kotlin.random.Random`,** so tests
are deterministic. It is a pure function in the shared core. The ViewModel supplies
`Random.Default`.

### The weighting

**SG8. [v1]** **`weight = exp(ln(16) × Σₖ rₖ · fₖ)`**, where:
- `rₖ` is spoke *k*'s radius in `[0, 1]`;
- `fₖ` is the candidate's normalised input in `[0, 1]` (SG9).

The properties this buys:
- **every radius at zero gives equal weights, which is pure shuffle** (vision, and journal session
  10, D13);
- a single spoke at full radius makes the neediest candidate **16×** as likely as the least;
- the polygon's **size** is how un-random the suggester is, and its **shape** is the proportions.

The constant 16 is authored here, is provisional, and is tuned in use.

**SG9.** The normalised inputs are:

| Spoke | `f` for a candidate | Version |
|---|---|---|
| Coldness | its staleness percentile in the pool. Never practised counts as coldest (`f = 1`), and ties share a percentile. | **[v1]** |
| Hotness | `1 − f_coldness` | **[v1]** |
| Priority | `level / 3`. **Unrated is 0.** | [v2] |
| Confidence | `(3 − level) / 3`, so lower confidence means more need. **Unrated is 0.** | [v2] |
| Skips | `min(skips, 10) / 10`, where `skips` is schema-3 M14's "since last practised" | [v2] |

**Unrated contributes nothing** on the rating spokes. The suggester acts only on what the musician
has said, never on an assumed rating.

**SG10. [v1]** **Coldness and hotness cancel.** Because `f_hot = 1 − f_cold`, the exponent's
staleness term is `(r_cold − r_hot) · f_cold` plus a constant, so equal radii neutralise each other
and the larger one wins by the difference. This is the roadmap's G4 default. The radar shows it
honestly: the two spokes sit **opposite each other**.

### The radar

**SG11. [v1]** **The radar lives inside the suggestion sheet**, behind a "Tune" disclosure below
the card, collapsed by default. The musician tunes the suggester where they use it.
- **Five spokes are always drawn**, with coldness and hotness opposite each other.
- Each radius is set by **dragging its handle**. The handle snaps to 0.05 steps and the change
  persists on release.
- **Spokes whose inputs do not exist yet are drawn but locked at zero, greyed, and labelled with
  why.** That is priority and confidence until the ratings editor ships, and skips while skips are
  discarded. It amends the roadmap's P5 wording, "absent": a radar with two spokes is a line, not
  an instrument.
- A **"Shuffle"** reset sets every radius to zero in one tap.
- It should feel like a small instrument panel (vision), and the beauty pass (P13) refines it.

**SG12. [v2]** **Skip counting is a setting, off by default** (vision; roadmap G5). With it off,
"Another" writes nothing and the skips spoke is locked. With it on:
- "Another" **stages** a skip for the shown part, and an inline "Skipped · Undo" shows for
  **5 seconds**.
- **The skip is written to `suggestion_skip` only when the window closes** (journal session 10,
  D17). Undo restores the previous card and writes nothing.
- A skip is also **written, not dropped,** when the window is cut short by "Another" again (the
  earlier skip closes and the new one is staged), or by "Log it" on a *different* card.
- It is **dropped** if the sheet is dismissed or the process dies within the window. Losing a skip
  means less nagging, never more, which is the right way round.

**SG13. [v2]** **When skips are counted, "Show the count" is a second setting, on by default,**
and it shows "Skipped 20 times since you last played it" on the card when the count is above
zero. The wording is **information, not a scolding** (vision): no red, no exclamation mark.

**SG14. [v2]** **Whose part:** a skip and the rating spokes attach to `(song, performer,
practice instrument)`. The performer is resolved by roadmap gate G12: the View's filter
performer, otherwise the owner performer. **When there is no performer to resolve, the rating
and skip spokes stay locked**, with the reason shown, and skips are not recorded.

**SG15. [v1]** **The tuning is a per-device preference**, stored in `SessionPreferences` like the
home View (views.md V19; journal session 11, D24). An unreadable stored tuning reads as the
default. It never throws (schema-compatibility S8).

**SG16. [v1]** **Defaults:** every radius zero (pure shuffle), skips not counted, the count shown
when they are. The less imposing choice every time (roadmap §2 rule 3).

## 3. Verification: what must be demonstrated

**[v1] (P5):**
- Shared tests for:
  - all-zero weights being exactly equal;
  - a single full spoke giving a 16:1 ratio between the extremes;
  - coldness plus hotness at equal radii giving equal weights;
  - the percentile with ties and never-practised;
  - the deck never repeating until it is exhausted;
  - a seeded draw sequence pinned;
  - an empty pool.
- **A distribution check:** 10,000 seeded draws at all-zero are uniform within a stated
  tolerance, and at coldness 1.0 the never-practised songs dominate as SG8 predicts.
- Instrumented tests:
  - Suggest opens the card;
  - "Another" shows a different song;
  - "Log it" creates exactly one `practice_event` with feel null;
  - dismissing writes nothing;
  - the tuning persists across a ViewModel restart.
- **Mutation:** break the deck exclusion and show the no-repeat test fail.
- Screenshots of the card, the expanded radar with one spoke dragged out, and the empty pool.

**[v2] (P9):**
- The skip window: undo writes nothing, closing writes one row, dismissing inside the window
  writes nothing, and "Another" inside the window writes the earlier skip.
- The count display is pinned against schema-3 M14's boundaries.

## 4. Deferred

- Per-View tuning (roadmap G6 default: per device).
- Suggesting more than one song at once, such as "suggest a set". Nobody has asked.
- Any suggestion outside the app (notifications or widgets): "Not a nag" (vision).
