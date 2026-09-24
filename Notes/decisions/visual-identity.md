---
title: "Visual Identity — Decision Spec"
type: decision-spec
area: ui
status: active
date: 2026-09-23
---

# Visual Identity — Decision Spec

Roadmap package **P13b** ("theme foundation"; journal session 11, D49). The look was approved by
the user after three rounds on the design canvas
[Repertosaurus colour ramps](https://claude.ai/artifact/4Hq2yaxYmEdqeczZwacf5e), rows "Round 3:
handed on". **That canvas's round 3 boards are the visual reference**, and this spec is the
contract. Where the two disagree, this spec wins, and the difference is reported.

The brief, in the user's words (vision, "Beauty"):
- a bold gig-flyer look with hard shadows;
- **rectilinear**, with no jaunty angles;
- texture that feels hand-made;
- evoking the ancient tradition of music, "handing on".

**It must not resemble the Anthropic brand** (journal session 11, D41): no warm ivory ground, no
clay accent, no serif-over-sans.

Numbers are `VI<n>`, grouped by topic. They are **not** an execution order.

## 1. Type Roster

**Android, new** (`androidApp/src/main/kotlin/dev/repertosaurus/android/theme/`):
- `Tokens.kt`: the palette, stroke widths and shadow offsets of VI1–VI5 as named constants.
- `Type.kt`: the two font families and the `Typography` of VI6–VI8.
- `RepertosaurusTheme.kt`: a `MaterialTheme` wrapper with a `lightColorScheme` mapped from the
  tokens, **every shape set to `RectangleShape`**, and the typography. It replaces the bare
  `MaterialTheme { }` in `MainActivity.kt`.
- **AMENDED 2026-09-23 (journal session 11, F17 N1):** the primitives are split across
  `Primitives.kt` (surface modifiers and buttons), `InkHeader.kt` (the header with its
  navigation, actions, title and subline slots, plus `InkFooter`) and `SegmentStrip.kt` (with a
  `SegmentLayout` of `Equal`, `ContentWidth` or `Grid`). What follows was the original single-file
  list:
- `Primitives.kt`:
  - `Modifier.hardShadow(offset)`;
  - `Modifier.inkBorder(width)`;
  - `PrimaryButton`, `SecondaryButton` and `InkIconButton`;
  - `InkHeader`, the indigo gradient bar;
  - `SegmentStrip`, the joined, bordered segments that the instrument chips, the sort and the
    filters share.
- `Grain.kt`: the full-screen grain overlay of VI10.
- `HandMark.kt`: the stencilled-hand mark of VI11.

**Android resources, new:** `res/font/` containing:
- `big_shoulders_display.ttf` (the variable-weight font);
- `barlow_semi_condensed_medium.ttf`, `_semibold.ttf` and `_bold.ttf`;
- the two OFL licence texts, kept in the repository as `androidApp/src/main/assets/licenses/`.

**Source files are provided by the lead in `.scratch/fonts/`.** Delegates copy from there and
never download anything.

**Android, changed:**
- `MainActivity.kt`: `RepertosaurusTheme`.
- `rating-scale.md`'s `RatingSegmentedControl` becomes a `SegmentStrip` look (VI14).
- `SessionScreen.kt`: the header, the chips, the rows and the badges (VI15).
- `FeelSheet.kt`, the drawer and the picker inherit the theme.

## 2. Decisions

### Palette

**VI1. The tokens:**

| Token | Hex | Role |
|---|---|---|
| `Ground` | `#E6E3DA` | screen background (stone, **not** ivory) |
| `Ink` | `#1B1A17` | text, rules, borders, shadows |
| `InkMuted` | `#45423B` | secondary text |
| `Indigo` | `#25338A` | header bars, the display accent |
| `IndigoLight` / `IndigoDark` | `#2D3D9A` / `#1E2A74` | the header gradient's ends (VI9) |
| `Madder` | `#D2456F` | the offset "misregistration" shadow on display type, and the hand mark |
| `Ochre` | `#E3A92F` | **the one primary action per screen**, and the "logged" badge's text |
| `Paper` | `#F2EFE6` | text on indigo, and card and sheet surfaces |
| `Field` | `#FFFFFF` | text inputs only |

The rating ramps of [rating-scale.md](rating-scale.md) are unchanged and remain the user's
choice.

**VI2. Contrast:** `Ink` on `Ground`, `Ochre` and every ramp step, and `Paper` on `Indigo`, must
all meet WCAG AA for their text size. The implementer quotes the computed ratios.

### Shape, stroke and shadow

**VI3. Every shape is square.** The theme's `Shapes` are all `RectangleShape`. No rounded
corners anywhere: sheets, buttons, chips, fields or badges. **No element is rotated** (journal
session 11, D47).

**VI4. Strokes:** `Ink`, at **3 dp** for containers, buttons, bars and field outlines, and at
**2 dp** for list-row rules and segment dividers.

**VI5. Hard shadows:** `Ink`, **no blur**, offset down and right by **3 dp** (icon buttons,
badges) or **5 dp** (the primary button). They are drawn as a solid offset rectangle behind the
element, **not** as Material elevation, which is soft and would be wrong here. The shadow is
outside the element's touch target, and the layout reserves space for it, so nothing clips.

### Type

**VI6. Display face: Big Shoulders Display** (variable weight, used at 800 and 900). It is for
screen titles, section headings, buttons, badges, the letter strip and numbers. It is **set in
upper case** where it labels (buttons, badges and headings), and in sentence case nowhere.

**VI7. Body face: Barlow Semi Condensed** at 500, 600 and 700, for song titles (700), artists
and secondary text (500), and body copy (600). Its condensed width suits long song titles on a
phone.

**VI8. Sizes** (sp; they respect the user's font scale):

| Use | Size |
|---|---|
| screen title | 44–48 display |
| header subline | 21 display |
| button and badge | 20–22 display |
| song title | 20 body 700 |
| artist | 15 body 500 |
| body copy | 17–19 body 600 |
| segment label | 11 body |

**Every label must survive a font scale of 1.3 without clipping.** This follows journal session
11, F16: "exceptionally" in a quarter-width segment.
- It may wrap to two lines, or drop to a shorter form, which is the number alone.
- It may never be cut off.
- Test it.

### Texture and gradient

**VI9. The header bar** (`InkHeader`) is `Indigo` with a **linear gradient** from `IndigoLight`
at the top-left to `IndigoDark` at the bottom-right, at about 172°. It is subtle, as if the ink
were laid down unevenly. **Display type on the header is `Paper`, with a 3 dp `Madder` offset
shadow**: the risograph misregistration.

**VI10. Grain: a fine monochrome paper grain over the whole screen, at about 20% strength,
multiplied.**
- It is one small **tileable noise bitmap**, generated deterministically (a fixed seed) and
  bundled or built once at startup. It is drawn as a repeating shader with ~~`BlendMode.Multiply`~~
  **`BlendMode.Modulate` and no trailing `graphicsLayer`** (AMENDED 2026-09-24, journal session
  11, D93). For an opaque tile over the opaque window the pixels are identical, and on the emulator it
  measured about 1.5 ms per frame cheaper. The emulator measurement is SwiftShader, so the real
  cost on hardware is still owed.
- **It must not intercept touches, and it must not cost a frame.** It is one draw call, with no
  per-frame work.
- It sits above content and below dialogs.
- **There is no halftone or dot pattern anywhere.** The user rejected it (D49).

**VI11. The stencilled hand**, the brand's motif and a literal "handing on", is used **sparingly**:
- **a small mark (about 30 × 38 dp) in the session header**, beside the menu button;
- **the onboarding welcome's cluster** (P14).

It is drawn **procedurally** in the way a real hand stencil is made:
- a hand silhouette is masked out;
- **pigment is sprayed around it** as many small seeded-random dots, dense at the edge and
  thinning outward, in `Madder`, `Indigo` or `Ochre`;
- the silhouette's edge is slightly irregular.

The result is rendered once into a cached bitmap per size and colour, never redrawn per frame.
**The hands may sit at natural angles in the onboarding cluster only.** That is decoration on a
wall, and VI3 governs UI elements. **This carve-out is the lead's, and the user has been told
about it.**

### Components

**VI12. Buttons:**
- **Primary** (at most one a screen): `Ochre` fill, a 3 dp `Ink` border, a 5 dp hard shadow,
  display type in upper case.
- **Secondary:** `Ground` fill with the same border and no shadow.
- **Icon buttons:** ~~44 dp~~ **48 dp** square (**AMENDED 2026-09-23**: 44 dp was below the
  touch minimum, the lead's error, journal session 11, D56 #12), `Ground`, a 3 dp border and a 3 dp shadow.

When pressed, a button **moves down and right onto its shadow**, like a printed button pushed
flat, instead of showing a Material ripple. It stays square.

**VI13. `SegmentStrip`:** segments are joined inside one 3 dp `Ink` border, with 2–3 dp
dividers.
- The selected segment is `Ink`-filled with `Paper` text, or ramp-filled for ratings.
- It is used by the instrument chips, the sort mode, the filters and the letter strip. The
  letter strip wraps as a grid (triage.md T3).

**VI14. `RatingSegmentedControl` adopts the strip look.** It keeps its RS9 behaviour and
signature. Segments are square, divided by 2 dp `Ink` rules inside one border, and the selected
segment is filled with its ramp step. RS6's "position and label, not colour alone" still holds.

**VI15. The session screen, the first screen restyled explicitly:**
- `InkHeader`, holding the menu `InkIconButton`, the hand mark, and the Suggest slot (P5 fills
  it with an `Ochre` icon button);
- the View name as the screen title;
- the "practising on … · N songs" subline;
- the instruments as a `SegmentStrip`;
- rows separated by 2 dp rules;
- the staleness badge as a **square** with a 2 dp border, a 3 dp hard shadow, the heat colour
  (RS12) and display type ("NEVER", "12D");
- the logged badge in `Ink` with `Ochre` text ("DONE", or "DONE ×2");
- "Add song" as the primary button, **full width** at the bottom, with its shadow.

**The one-tap row is unchanged in behaviour and in hit area.**

**VI16. Every other screen inherits** the theme (colours, square shapes and type) through
`MaterialTheme`, without bespoke restyling in P13b. The later packages (P5, P6, P8, P14) build on
the primitives. The closing polish pass (P13) restyles whatever still looks like stock Material.

**VI17. Light theme only for now.** The app ignores the system dark setting until P13, which
decides dark mode. A night version is worth doing for dark stages, but it is its own design.

### Motion (added 2026-09-23 on the user's direction; journal session 11, D55)

**VI18. Things move as if they have mass: springs and anticipation, never a plain vanish.** Every
motion that answers a touch uses a **spring** (Compose `spring`), under-damped just enough to
settle with one small overshoot (damping ratio about 0.6–0.75), or a `back`-style ease where a
spring does not fit. **Anticipation** is the user's "move a little in the opposite direction
first". Before an element travels, it gives a small counter-movement, a few dp, the other way.

**VI19. Satisfying, never compulsive.** The response to a log is **the same small, crisp
movement every time**:
- **no variable or random rewards**, which are the mechanism of compulsion;
- no confetti, sound, haptic flourish, counters ticking up, or streak effects;
- nothing that grows with repetition.

The test: a musician should enjoy the tap once and never want to tap **for the animation**. It
is part of the "not a nag" rule (vision).

**VI20. The log tap, choreographed** (the session row):
1. **Press:** the row sinks about 2 dp, onto its rule, like a button sinking onto its shadow
   (VI12).
2. **Release:** the log is **written immediately**. Animation never delays the write (roadmap §2
   rule 1). The staleness badge **stamps**: it squashes slightly, then springs back as it
   becomes "DONE", like a rubber stamp meeting paper.
3. **Leaving:** the row gives a small **anticipation** movement (a few dp the opposite way), then
   travels out and collapses its height with a spring.
4. **Closing the gap:** the rows below **spring up** into the gap with a little mass, settling
   with one small overshoot. They do not slide linearly.

The whole sequence completes in **about 450 ms**. **Input is never blocked.** Tapping another row
mid-animation logs it at once, and its own animation runs concurrently. A row that is animating
out cannot be tapped again: it has been logged. The logged section's arrival uses the same
spring.

**VI21. The same physics everywhere else, kept small:**
- buttons sink onto their shadow on press and spring back on release (VI12);
- a selected segment's fill springs in (RS9 and VI13);
- a sheet opening keeps the platform's own motion.

**No idle animation. Nothing moves unless touched or changed.**

**VI22. Reduced motion is honoured.** When the system's animator duration scale is 0 ("Remove
animations"), every VI18–VI21 motion is instant, and the layout ends in the same final state.

## 3. Verification

- The instrumented suite stays green, and the count is quoted.
- **A font-scale test:** the feel sheet and the session row at a font scale of 1.3, with no label
  clipped. Assert it through text layout (`hasVisualOverflow` false), not by eye.
- A test that the grain overlay does not consume touches: a tap through it still logs.
- The VI2 contrast ratios, computed and quoted.
- **Screenshots** of the session screen (all three ramps), the feel sheet, the drawer and the
  ramp picker, **compared side by side with the canvas's round 3 boards.** Every deliberate
  difference is listed.
- **Motion (VI20):**
  - two rapid taps on two different rows both log, and the second is not blocked by the first's
    animation;
  - a tap still writes its event before any animation frame (assert the database row with the
    test clock paused);
  - with animations disabled, the list ends in the same state.
  - A short screen recording (`adb shell screenrecord`) of a log tap goes in `.scratch/`,
    because motion cannot be judged from a still.
- The APK grows by the fonts (about 550 KB) and the grain bitmap. Quote the size change.

## 4. Deferred

- Dark theme (VI17).
- Restyling the Songs, Artists, Repertoire and lookup screens beyond what the theme gives them
  (P13).
- A drawn logo. The dinosaur brief in naming.md N11 stands for the logo. The hand mark is the
  **UI** motif, not a replacement logo.
