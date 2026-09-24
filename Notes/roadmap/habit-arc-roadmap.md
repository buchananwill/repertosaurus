---
title: "Habit Arc Roadmap — Critical Path to the Nudger"
type: workflow
area: product
status: active
date: 2026-09-23
---

# Habit Arc Roadmap: Critical Path to the Nudger

This is the arc lead's plan for delivering [from-logger-to-nudger.md](../vision/from-logger-to-nudger.md).
The vision says **what** and **why**, and this roadmap says **in what order**. The journal records
what happened. **Where this roadmap and the vision disagree, the vision wins, and the disagreement
is a defect in this roadmap to be fixed.**

It is a best guess, amended in place. When a package lands, is split, or is re-sequenced, its row
in §6 changes in the same edit, and the journal records why.

## 1. The shape of the arc in one paragraph

**One migration unlocks most of the arc.** Priority and confidence, feel on 0–3, timed durations,
counted skips and the triage sort names all change the schema, and each data-model rule they touch
(append-only log, two merge rules, S1 version bump, S13 load test) must be designed once, not
four times. So **schema 3 is the critical path**. Its spec is written first and its migration
lands early. Everything that needs no schema change ships **ahead of it and alongside it**:
- Suggest v1 (shuffle plus coldness and hotness);
- Scorecards v1 (counts of logs);
- the colour-ramp primitive.

That work puts something new on the user's phone early, while schema 3 is being built. After
schema 3, three feature lanes run in parallel: ratings and triage, the timer, and Suggest v2.
Beauty closes the arc, once the screens have stopped moving. Its visual exploration with the user
starts early, because it runs on the user's time and not on code.

## 2. Standing rules for this arc

1. **One tap is sacred.** No package may add a tap, a dialog or a required field to the plain-tap
   log. A reviewer brief for any package touching `SessionScreen.kt` must say so.
2. **The obligation test.** Every spec answers in one line: "would the musician experience this as
   an obligation?" If yes, it becomes a setting or changes shape.
3. **Defaults lean to the less imposing choice.** For example: skips discarded, radar at zero, no
   notifications.
4. **Hot files are serialised.** At most **one** in-flight package may own
   [SessionScreen.kt](../../androidApp/src/main/kotlin/dev/repertosaurus/android/SessionScreen.kt)
   and [SessionViewModel.kt](../../androidApp/src/main/kotlin/dev/repertosaurus/android/SessionViewModel.kt)
   at a time. Suggest, the timer and the triage sort all want the session screen. Parallel
   packages that need it are sequenced, or else run in a worktree and are merged by the lead.
   The same rule covers the `.sq` files and `RepertosaurusRepository` while P2 is in flight.
5. **Every UI brief names the shared primitives** from P3: the 0–3 vocabulary, the ramp and the
   segmented control. No delegate hand-rolls its own.
6. **Done means demonstrated.** Shared-core tests pass, instrumented tests pass on
   `Pixel_3a_API_33_x86_64`, and screenshots are taken for anything visual. A debug APK is handed
   to the user at every milestone marked 📱 in §6. Nothing is "done" on the phone until the user
   says so.
7. **Review by a different agent than the author.** Run `kotlin-safety-correctness-reviewer` and
   `kotlin-style-decomposition-reviewer` on every code package, plus `compose-quality-reviewer` on
   UI packages.

## 3. How momentum is kept without the user re-injecting it

**The frontier rule.** At the start of every session, and whenever a package lands, the lead does
the following:
1. Reads §6.
2. Marks what has landed.
3. **Dispatches every package whose dependencies have landed and whose gates are answered or
   defaulted**, up to the hot-file rule.

The user is needed only for **hard gates** (§4) and for handling an APK on the phone.

**Soft gates proceed on their default.** Each soft gate is asked once, in plain prose, batched with
the others, with the default stated. If the user has not answered by the time the spec that
depends on it is written, the spec adopts the default. The journal records it as an assumption, and
the user can overturn it in passing. A default overturned after code has landed becomes a follow-up
package; it does not halt the lane.

**Hard gates block only their own package.** A package waiting on a hard gate never blocks a
parallel lane.

## 4. Gates

| Gate | Question | Default if unanswered | Blocks | Hard? |
|---|---|---|---|---|
| G1 | **The four triage sorts.** ~~What are they?~~ **ANSWERED by the user, 2026-09-23 (session 11, D25):** | Two keys × two directions: **priority-led** and **confidence-led**, each with a "most in need first" direction and its reverse. The reverse mirrors coldest/hottest. | P1 (schema 3's `sort_order` CHECK, F1) | ~~Hard~~ closed |
| G2 | **Feel mapping.** Stored 1/2/3 are read as somewhat/certainly/exceptionally, and 0 is new. | Adopt it. Stored numbers are never rewritten. | P1 | Soft |
| G3 | **Where ratings live:** their own table, or columns on `song_performer`? | Their own table, one row per part, merged last-write-wins. A rating edit and a role toggle must not overwrite each other. | P1 | Soft (a technical call, which the lead owns) |
| G4 | **Radar spokes and meaning.** Can coldness and hotness both be non-zero? | Five spokes: priority, confidence, coldness, hotness, skips. Coldness and hotness **cancel** when both are set (they add weight in opposite directions). The skips spoke is hidden while skips are discarded. | P5, P9 | Soft |
| G5 | **Suggester defaults** | Radar all zero (pure shuffle). Skips discarded. Skip-count display on when skips are counted. Undo window of 5 s. | P5, P9 | Soft |
| G6 | **Radar tuning scope:** per device, or per View? | A **per-device preference**, like the home View (views.md V19). This takes no schema. Per-View tuning is deferred. | P1 | Soft |
| G7 | **"Reliable day"** | A day with at least one non-voided log. Weekday reliability is the share of that weekday, over the window, that has one. Minutes shading is added after the timer. | P6 | Soft |
| G8 | **Timer placement** | Start from the long-press sheet (never on the tap). A running timer shows as one persistent bar at the top of the session screen. Its start time persists across process death. | P11 | Soft |
| G9 | **Does feel feed triage?** | No, not in v1. Triage uses priority, confidence and days-since. | P7 | Soft |
| G10 | **Beauty direction** | None. This is explored with the user. **Ramps ruled 2026-09-23** (session 11, D36): all three are approved and `DANGER_TO_SAFE` is the default. The overall look (ground, accent, type) is still open. | P13 | **Hard** (for the look only) |
| G11 | Fast-forward `main`, and adopt Piste Perfect's full notes schema? | Leave `main` alone. Keep this schema. | nothing | User's call |
| G12 | **Whose ratings does a View read when it names no performer?** Ratings belong to a song–performer–role, but a View may filter on no performer (journal session 11, F4). | The View's filter performer when set. Otherwise an **owner performer** ("me"): a device preference, asked for once, the first time a feature needs it. Until it is set, the priority and confidence inputs are simply absent. | P4 (v2 spokes), P7 | Soft |

## 5. Packages

Each package is a unit the lead either writes (**spec**) or dispatches (**impl**). "Depends on"
means the package cannot start until those have **landed**, not merely been dispatched.

### Keystone

**P1. Schema-3 spec** (spec, by the lead). A decision spec at `Notes/decisions/schema-3.md`. It fixes
every data change of the arc in one migration:
- the part-ratings table on the `(song, performer, instrument)` triple (priority, confidence, 0–3,
  nullable = unrated);
- `practice_event` rebuilt with `CHECK (feel BETWEEN 0 AND 3)` and a new nullable
  `duration_seconds`;
- `suggestion_skip` (journal session 10, D16);
- `saved_view.sort_order` CHECK widened to the G1 names;
- `Schema.version` 3 with `2.sqm`, and the S13 load test against a schema-2 database.

It must honour data-model 5–14, S1 and S3. The `practice_event` rebuild keeps every id,
`created_at` and `device_id` byte for byte, and `practice_event_void` still resolves against it.
The Python migration is **not** engaged (F2). Depends on: G1, G2, G3, G6.

**P2. Schema-3 implementation** (impl). The `.sq` files, `2.sqm`, repository reads and writes for
ratings, skips and duration, and the widened feel validation in `RepertosaurusRepository.log`.
Verified by:
- the S13 load test on a schema-2 fixture;
- a real v2 → v3 upgrade on the emulator, with the log count before and after quoted;
- the V17a-style enum-to-CHECK pin for the new sort names.

Depends on: P1. **This is the critical-path node; nothing in the rating, timer or skip lanes starts
without it.**

### Early lane (no schema, parallel with P1–P2)

**P3. Rating vocabulary and colour-ramp primitive** (small spec, then impl). The shared primitives
are:
- the 0–3 labels (shared core);
- the ramp as a user setting with at least three ramps (the user's pastel red→blue, danger-red→green,
  cold-blue→red-hot);
- a Compose `RatingSegmentedControl` `[0|1|2|3]`, one tap per change;
- a heat mapping for days-since.

Every ramp carries position and label, not colour alone. It also converts the FeelSheet chips to the
labelled 0–3 control, with 0 disabled until P2 lands. Depends on: nothing.

**P4. Suggest spec** (spec, by the lead). A decision spec at `Notes/decisions/suggest.md`. It
covers:
- the pool (the current View's eligible songs on its practice instrument);
- the weighting function (pure shared core, with the random source injected so tests are
  deterministic);
- the radar control;
- the suggestion card (take = one-tap log via the existing path, skip, another);
- the skip-undo semantics "write only when the window closes" (journal session 10, D17);
- the settings.

It is written with all five spokes, and v1 implements the two that need no schema. Depends on: G4,
G5 (soft).

**P5. Suggest v1** (impl) 📱. The core weighting with the coldness and hotness spokes. The
Suggest button and suggestion card on the session screen. The radar control with the unimplemented
spokes ~~absent (not disabled)~~ **drawn but locked at zero, greyed, with the reason shown**
(AMENDED 2026-09-23 by [suggest.md](../decisions/suggest.md) SG11: a two-spoke radar is a
line, not an instrument). Skips are always discarded in v1. **Owns the hot files** while in
flight. Depends on: P4, P3.

**P6. Scorecards v1** (spec + impl) 📱. A new route from the drawer showing:
- a GitHub-style calendar grid shaded by count of non-voided logs;
- week and month summaries;
- weekday reliability per G7.

All of it is computed at read time (data-model 48). It is scoped to the View's practice instrument,
or to all instruments, per a toggle. The tone is encouraging: gaps are neutral, not red. It does
**not** touch the hot files, so it runs in parallel with P5. Depends on: P3, G7.

### After schema 3 (three parallel lanes)

**P7. Triage spec** (spec, by the lead). A decision spec at `Notes/decisions/triage.md`. It covers:
- the ratings editor, placed on the Repertoire route's performer → role → song list
  ([RepertoireScreen.kt](../../androidApp/src/main/kotlin/dev/repertosaurus/android/RepertoireScreen.kt)
  `ToggleListScreen`), with one priority control and one confidence control per enabled part;
- **A–Z pagination (one letter a page, with a letter strip), search, and an unrated or rated
  filter**, for both the ratings editor and the toggle list (journal session 11, D40);
- the blend of days-since, priority and confidence;
- the G1 sorts as new `SessionOrder` entries.

Depends on: P1 (P7 may be written while P2 is in flight).

**P8. Ratings editor** (impl) 📱. Depends on: P2, P3, P7. It does not touch the hot files.

**P9. Triage sort + Suggest v2** (impl) 📱. It also fixes journal session 11's F7: a sort change
scrolls the list to the top. **It also inherits these hot-file items deferred from P8's review
(journal session 11, F20–F22):**
- the session search uses `SongSearchField` (F21 B5);
- `SongRowContent` and `FeelSheet` adopt `SongTitleArtist`, omitting a missing artist (F21 B7);
- the session screen passes `performers = null` to `rateThese` until performers load (F20 N2's
  seam);
- session state exposes the resolved part once, for the View menu and the triage sort
  (F21 B11, F20 N2's wiring);
- `rateThese` is memoised at its call site (F22 N2);
- remove RS9's `lowest` if it is still unused (F16);
- ~~a "performers loaded" signal from `SessionViewModel`~~ **moved to the P5 fix round**
  (journal session 11, F30), because onboarding's first-load gate needs it sooner.

This adds:
- the triage `SessionOrder` entries and their comparator (shared core, with null-handling
  tests per V14);
- the priority, confidence and skips spokes;
- counted skips with the count display and the undo window.

These are bundled because both read the ratings from the session screen and both **own the hot
files**. Depends on: P2, P5, P7.

**P10. Timer spec** (spec, by the lead). Covers placement per G8; one running timer at a time; stop
→ one `practice_event` carrying `duration_seconds`; cancel → nothing written; survives process death;
a minimum duration below which the log still lands untimed rather than as 0 s; "hard to forget
running" without a nagging notification. Depends on: P1.

**P11. Timer** (impl) 📱. **Owns the hot files**, so it is sequenced after P9 or run in a worktree
and merged by the lead. Depends on: P2, P10.

**P12. Scorecards v2** (impl). Shades by minutes where timed data exists, falling back to count.
**Brief note (journal session 11, F23 N5):** it needs a per-day value (the timed sum plus the
untimed count). A NULL `SUM(duration_seconds)` means untimed and must never be coerced to 0.
A per-song history shows minutes accumulating. Depends on: P6, P11.

### Onboarding

**P14. First-run onboarding** (spec + impl) 📱. Added 2026-09-23 on the user's ruling (journal
session 11, D37).

It is a short, **skippable** sequence of questions, shown once, before the session screen on an
install that has not completed it. It asks:
- **the colour ramp**, as three rendered choices with `DANGER_TO_SAFE` preselected;
- **which performer is "me"**: the owner performer that G12 needs, chosen from the roster or
  skipped.

Every question has a default, and **skipping all of it costs one tap**. Nothing in it blocks
logging (the obligation test). Every answer stays changeable from the drawer.

The spec must decide what an existing install sees: the user's phone already has data. The lead's
lean is to show it once there too, because the user is the one person who can check it.

Depends on: P3 (the ramp picker is reused). It does **not** touch the hot files: it is a separate
route shown before the session screen.

### Beauty

**P13a. Beauty exploration** (with the user, starting now). Visual mockups as artifacts, not
code:
- the ramps first;
- then the session row, the suggestion card and the scorecard grid in the naming.md N11 palette.

The lead produces them and the user rules. Depends on: nothing. **Hard-gated on G10 for anything
beyond mockups.**

**P13b. Theme foundation** (impl) 📱. Added 2026-09-23 (journal session 11, D49), after the
user approved the round 3 look. It builds tokens, fonts, square shapes, hard shadows, grain, the
header gradient and the hand mark as Compose primitives, and restyles the session screen. The
contract is [visual-identity.md](../decisions/visual-identity.md). **It owns the hot files, so
it runs before P5.** Every later UI package builds on its primitives. Depends on: P3.

**P13. Theme implementation** (impl) 📱. **AMENDED 2026-09-23: it is now the closing polish pass
over what P13b did not restyle.** Collected notes (journal session 11):
- the grain-layer profiling A/B (F18 N4);
- the scorecards' empty band under the grid (F29);
- the stock Material leftovers (D56 #7, plus the Views sheet's rounded "Make the first view"
  button, **and the rounded `Switch`es in Tune and the drawer**, per D79);
- "Confidence" breaking mid-word at font scale 2.0 (D78 #6);
- the Songs, Artists, Repertoire toggle and lookup screens;
- dark theme (VI17). A Material theme and tokens, the brand palette and
typography, applied across screens that have stopped moving. Depends on: P13a ruled, P8, P9, P11.

## 6. Status board

Amended in place by the lead. Order is the critical path first, then the parallel lanes.

| Pkg | What | Kind | Depends on | Gates | Status |
|---|---|---|---|---|---|
| P1 | Schema-3 spec | spec | — | G1 answered; G2, G3, G6 soft | **done**: [schema-3.md](../decisions/schema-3.md) |
| P2 | Schema-3 impl | impl | P1, P3 (for `RatingLevel` and the emulator; journal session 11, D31) | — | **complete, green, awaiting commit** (journal session 11, D48) |
| P3 | Rating vocabulary + ramp primitive | spec+impl | — | — | **complete, green, awaiting commit**: spec [rating-scale.md](../decisions/rating-scale.md) (journal session 11, D48) |
| P4 | Suggest spec | spec | — | G4, G5 soft | **done**: [suggest.md](../decisions/suggest.md) (defaults adopted) |
| P5 | Suggest v1 📱 | impl | P3, P4, **P13b** (hot files, and it builds in the new look) | — | **committed `2d7836e`** (with its fix round) |
| P6 | Scorecards v1 📱 | spec+impl | P3 | G7 soft | spec **done**: [scorecards.md](../decisions/scorecards.md); **committed `2d7836e`** |
| P7 | Triage spec | spec | P1 | G9 soft | **done**: [triage.md](../decisions/triage.md) (G9 and G12 defaults adopted) |
| P8 | Ratings editor 📱 | impl | P2, P3, P7 | — | **committed** `4631d26` + fix round `2d7836e` |
| P9 | Triage sort + Suggest v2 📱 | impl | P2, P5, P7 | — | **split (journal session 11, D74):** P9a (triage sort plus inherited items) **in flight**; P9b (Suggest v2) next |
| P10 | Timer spec | spec | P1 | G8 soft | **done**: [timer.md](../decisions/timer.md) (G8 default adopted) |
| P11 | Timer 📱 | impl | P2, P10; after P9 (hot files) | — | blocked |
| P12 | Scorecards v2 | impl | P6, P11 | — | blocked |
| P14 | First-run onboarding 📱 (ramp; owner performer) | spec+impl | P3 | G12 soft | spec **done**: [onboarding.md](../decisions/onboarding.md); **committed `2d7836e`**, fix round in flight |
| P13b | Theme foundation 📱 | impl | P3 | G10 closed for the look (D49) | **committed** (journal session 11, D56); the review fix round follows |
| P13a | Beauty exploration | with the user | — | — | **done**: three rounds on [Repertosaurus colour ramps](https://claude.ai/artifact/4Hq2yaxYmEdqeczZwacf5e); round 3 ("handed on") approved (journal session 11, D49) |
| P13 | Theme implementation 📱 | impl | P13a, P8, P9, P11 | G10 hard | blocked |

**Critical path (amended 2026-09-23):** P1 → P2 ✓ → P13b → P5 → P9 → P11 → P13. After
P13b, P6, P8 and P14 run in parallel with P5, because none of them owns the hot files.
**Superseded:** P1 → P2 → P9 → P11 → P13. P7 and P10 are written alongside P2, so they are off
the path.

## 7. Explicitly out of this arc

- Sync (phase 2) and its prerequisites from journal session 09 §4, item 4. Schema 3 must not make
  them harder: every new table follows data-model 9–11. **Carried to the sync spec** (journal
  session 11, F14 N2): `part_rating` and `song_performer` have unique keys that are not their
  ids, so a merge must compare by key before `applyMerged`'s `INSERT OR REPLACE`.
- A spaced-repetition scheduler, notifications, and streak-loss mechanics (the vision's "What this
  is not").
- Per-View radar tuning (G6 default), and set-list-scoped Views (views.md §3).
