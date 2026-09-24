---
title: "Rating Scale and Colour Ramp — Decision Spec"
type: decision-spec
area: ui
status: active
date: 2026-09-23
---

# Rating Scale and Colour Ramp — Decision Spec

Roadmap package **P3** of [habit-arc-roadmap.md](../roadmap/habit-arc-roadmap.md). The vision
[from-logger-to-nudger.md](../vision/from-logger-to-nudger.md) says every rating in the app
"speaks one language and wears one colour ramp". This spec builds that language once, as shared
primitives, **before** any feature uses it. It serves the ratings editor, triage, the suggester,
the feel sheet, the staleness badge and the scorecards. No delegate on a later package may
hand-roll its own version of anything here.

**This package changes no schema.** The feel sheet stays on 1–3 until schema 3 widens the CHECK
(roadmap P1/P2). The exact colour values are **provisional**: they are owned by the beauty
exploration (roadmap P13a) and may change without amending this spec. The **order, count and
naming** of steps and ramps are fixed here.

Numbers are `RS<n>`, grouped by topic. They are **not** an execution order.

## 1. Type Roster

**Kotlin core (`shared/src/commonMain`), new**

- `dev.repertosaurus.core.RatingLevel`: the 0–3 vocabulary.
- `dev.repertosaurus.core.ColourRamp`: the user-selectable ramps, each four ARGB values.
- `dev.repertosaurus.core.Heat`: the mapping from days-since to a `RatingLevel` step.

**Kotlin core, changed**

- `dev.repertosaurus.session.SessionPreferences` gains `colourRamp(): ColourRamp` and
  `rememberColourRamp(ramp: ColourRamp)`, and `InMemorySessionPreferences` implements them.

**Android (`androidApp`), new**

- `RatingUi.kt` holds:
  - `LocalColourRamp`, a `CompositionLocal`;
  - `RatingSegmentedControl`;
  - `ColourRampPicker`, a sheet or dialog.

**Android, changed**

- `AppGraph.kt :: AndroidSessionPreferences` persists the ramp.
- `RepertosaurusApp.kt`: a new drawer item opens the ramp picker, and `LocalColourRamp` is
  provided at the app root.
- `SessionScreen.kt`:
  - `FeelSheet` uses `RatingSegmentedControl`;
  - `StalenessBadge` wears the heat colour;
  - the undo snackbar names the feel level in words.
- `androidTest/.../TestSupport.kt :: InMemoryPreferences` implements the two new methods.

## 2. Decisions

### The vocabulary

**RS1.** `RatingLevel` is an enum of exactly four entries in this order:

| Entry | Stored value | Label |
|---|---|---|
| `NOT_AT_ALL` | 0 | "not at all" |
| `SOMEWHAT` | 1 | "somewhat" |
| `CERTAINLY` | 2 | "certainly" |
| `EXCEPTIONALLY` | 3 | "exceptionally" |

These are the vision's words verbatim. Each entry carries `value: Long` (matching SQLDelight's
`INTEGER` mapping) and `label: String`.

**RS2.** `RatingLevel.fromStored(value: Long?): RatingLevel?` returns `null` for `null` **and for
any value outside 0–3**. An out-of-range stored value reads as "unrated". It never throws, per
schema-compatibility S8: no database state may crash the app. ~~`RatingLevel.stored` is the
inverse.~~ **AMENDED 2026-09-23 (journal session 11, F9 N1): `value` is the inverse. There is
no second name for the field.**

**RS3.** **Stored feel values are read on this scale unchanged:** 1 → somewhat, 2 → certainly,
3 → exceptionally. This is the roadmap's G2 default (journal session 10, D14), adopted as an
assumption that the user may overturn. **No stored number is ever rewritten**, because
`practice_event` is immutable (data-model 7).

### The ramp

**RS4.** `ColourRamp` is an enum of three ramps. Each one carries `steps: List<Long>` of **exactly
four ARGB values**, indexed by `RatingLevel.value`: step 0 is "not at all", or the coldest heat.

| Entry | Meaning | Provisional steps 0 → 3 |
|---|---|---|
| `PASTEL_RED_BLUE` | the user's own suggestion | `0xFFF2A7A7`, `0xFFF9D6D6`, `0xFFD3E5F7`, `0xFF93BDE8` |
| `DANGER_TO_SAFE` | danger red rising to safe green | `0xFFF2A7A7`, `0xFFF6D7A4`, `0xFFD5EBB4`, `0xFF9FD3A6` |
| `COLD_TO_HOT` | cold blue rising to red-hot | `0xFF93BDE8`, `0xFFD3E5F7`, `0xFFF9D2C2`, `0xFFF09A7E` |

**AMENDED 2026-09-23 (journal session 11, F9 B13): each ramp also carries its display `label`
in the shared core** ("Pastel red to blue", "Danger to safe", "Cold to hot"), beside
`RatingLevel.label`.

The colours live in the **shared core**, not in `androidApp`, so a later desktop or web UI reads
the same values. They are plain `Long`s, and the core takes no dependency on Compose.

**RS5.** ~~**`ColourRamp.DEFAULT` is `PASTEL_RED_BLUE`**, the user's stated default (vision,
"Colour as a language").~~ **AMENDED 2026-09-23 by the user, after seeing all three ramps
rendered: `ColourRamp.DEFAULT` is `DANGER_TO_SAFE`.** The user's words: "all three are good…
My personal preference would be Danger → Safe, so perhaps make that the default." The ramp
becomes a first-run onboarding question (roadmap P14), so the default is what a musician who
skips that question gets. `ColourRamp.fromStored(name: String?)` returns the entry whose `name`
matches, or `DEFAULT` when the name is null or unknown. This mirrors `NoteSpelling.fromStored`
(repertoire-editing R40–R42).

**RS6.** **Every step is light enough to carry dark text,** so the content colour on any ramp step
is `onSurface` in the light theme. The dark theme is not re-coloured by this package; P13 owns
it. **No ramp relies on colour alone:** everywhere a step is shown, its position or its label
is shown too (vision, "Colour as a language").

**RS7.** **The ramp is a device preference, never data, never synced**, stored by enum `name`
under its own key in `AndroidSessionPreferences`. This is the same reasoning as the note spelling
and the home View (views.md V19).

**RS8.** **`LocalColourRamp` is provided once, at the app root,** from the preference, as
~~`compositionLocalOf { ColourRamp.DEFAULT }`~~ **`staticCompositionLocalOf { ColourRamp.DEFAULT
}` (AMENDED 2026-09-23, journal session 11, F10 N1).** The value changes only on an explicit pick
and is read at many leaves in lazy lists, so it should not carry per-read tracking. A full
subtree recompose on change is exactly the behaviour wanted. Changing the ramp in the picker
recomposes every screen with no restart. Composables read the ramp from the local; **no composable takes a ramp
parameter**, so later packages cannot thread it inconsistently.

### The control

**RS9.** `RatingSegmentedControl(value: RatingLevel?, onValueChange: (RatingLevel?) -> Unit,
modifier: Modifier = Modifier, ~~levels: List<RatingLevel> = RatingLevel.entries~~ **lowest:
RatingLevel = RatingLevel.NOT_AT_ALL**)` is the only 0–3 input in the app. **AMENDED 2026-09-23
(journal session 11, F10 B1):** a `List` parameter is unstable to the Compose compiler, and
strong skipping is off at this compiler version. The control will run two to a row in a lazy
list of hundreds, so every parameter must be stable. The segments shown are `lowest` through
`EXCEPTIONALLY`. Its behaviour:
- **One tap sets a value.** No dropdown and no confirmation (vision; journal session 10, D4).
- **Tapping the selected segment clears it to `null`.** This matches today's feel chips, so
  "unrated" stays reachable in one tap.
- Each segment shows its **number and label**. A selected segment is filled with its ramp step.
  An unselected segment is outlined, with its step shown as a small swatch or border, so the ramp
  is legible before anything is chosen.
- Each segment's touch target is at least 48 dp tall.
- Each segment carries a test tag built from a caller-supplied prefix and the level's value, so
  instrumented tests can address it.

**RS10. RETIRED 2026-09-23 (journal session 11, D76):** schema 3 widened feel to 0–3, so nothing
passes `lowest`, and it was removed from RS9's signature, along with its test. The original text
is kept for the record: `lowest` (formerly `levels`, amended per RS9) exists only so the feel sheet can offer
1–3 until schema 3 widens `practice_event`'s CHECK. **The feel sheet passes `lowest =
SOMEWHAT`**, with a comment citing the roadmap's P1/P2 as the change that widens it.
Showing a "not at all" segment that the database would reject is forbidden. Every other caller
uses the default.

### Heat

**RS11.** `Heat.level(daysSince: Long?): RatingLevel` maps staleness onto the ramp:

| Days since | Heat level |
|---|---|
| `null` (never practised) or ≥ 30 | `NOT_AT_ALL` |
| 14–29 | `SOMEWHAT` |
| 4–13 | `CERTAINLY` |
| 0–3 | `EXCEPTIONALLY` |

The 30-day boundary is today's `StalenessBadge` threshold, kept deliberately. The other
thresholds are **authored here and provisional**, and they are tuned by the user's eye in P13a.
The function is pure, lives in the core, and is tested at every boundary: 3/4, 13/14, 29/30, and
`null`.

**RS12.** `StalenessBadge` takes its container colour from `LocalColourRamp.current.steps[
Heat.level(days).value]`. **The "logged" and "logged ×N" states keep `primary`** so this
session's taps stay distinct from heat. The badge text is unchanged ("12 d", "never"), which
satisfies RS6's "not colour alone".

### The feel sheet and the snackbar

**RS13.** `FeelSheet` replaces its three `FilterChip`s with `RatingSegmentedControl(levels = RS10's
three)`. **Its cost is unchanged:** one long-press, one tap on a level, then Log. Converting the
control between `RatingLevel?` and the `Long?` that `viewModel.log` takes happens at this call
site only.

**RS14.** The undo snackbar's feel suffix reads **"· feel: certainly"** instead of "· feel 2", with
the label from `RatingLevel.fromStored`.

**RS15.** **Two comments go stale with this package and must be rewritten in it** (CLAUDE.md,
"grep for stale comments"):
- `FeelSheet`'s KDoc says "The numbers carry no words because the spec assigns them no meaning".
  It now cites this spec and RS3.
- The `SongRow` / `FeelSheet` references to "feel 1-3" stay true until schema 3, so they are
  **left** as they are.

### The picker

**RS16.** A drawer item **"Colour ramp"** sits beside the note-spelling item. It opens
`ColourRampPicker`, which lists each ramp as its four swatches with the four labels, the current
one marked. **One tap on a ramp selects it, persists it and closes the picker.** The drawer item
and each ramp row carry test tags.

## 3. Verification — what must be demonstrated

- **Shared core:** `:shared:testDebugUnitTest --max-workers=2` passes, with new tests for:
  - `RatingLevel`: order, values, labels, and `fromStored` on `null`, −1, 0, 3 and 4;
  - `ColourRamp`: each ramp has exactly 4 steps, and `fromStored` works on `null`, an unknown
    name and every entry's name;
  - `Heat.level`: every boundary in RS11.

  Quote the pass count.
- **Instrumented** (on `Pixel_3a_API_33_x86_64`, headless):
  - a ramp persistence test in the shape of `NoteSpellingPreferenceTest`;
  - a `RatingSegmentedControl` test: a tap sets, a tap on the selected segment clears to `null`,
    and one tap per change;
  - a feel-sheet test: long-press a row, tap "certainly", Log, and a `practice_event` with
    `feel = 2` exists.

  **All pre-existing instrumented tests still pass**, and the full count is quoted.
- **Mutation:** break `Heat.level`'s 29/30 boundary and show the test fail, then restore it.
- **Screenshots** (`adb exec-out screencap`) of:
  - the session screen under each of the three ramps;
  - the feel sheet;
  - the ramp picker.

  Save them under `.scratch/p3/`, which is gitignored.

## 4. Deferred

- A "not at all" feel segment. It arrives with schema 3 (roadmap P2), which widens the CHECK and
  `RepertosaurusRepository.log`'s range, and then drops RS10's `levels` argument at the feel
  sheet.
- Dark-theme ramp values and final hex values (P13a/P13).
- A user-defined custom ramp. Nobody has asked for one.
