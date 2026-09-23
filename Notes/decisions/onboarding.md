---
title: "First-Run Onboarding — Decision Spec"
type: decision-spec
area: ui
status: active
date: 2026-09-23
---

# First-Run Onboarding — Decision Spec

Roadmap package **P14** of [habit-arc-roadmap.md](../roadmap/habit-arc-roadmap.md). It was opened
by the user's ruling that choosing a colour ramp is "one of the 'onboarding' questions for a new
user" (journal session 11, D37). It also asks the one other question the app needs answered
exactly once: **which performer is you** (triage.md T10, roadmap G12).

**The obligation test is at its sharpest here.** Onboarding stands between a musician and the
app. It must be:
- short;
- skippable in one tap;
- never a precondition for logging;
- asked once, with every answer changeable later from the drawer.

Numbers are `OB<n>`, grouped by topic. They are **not** an execution order.

## 1. Type Roster

**Kotlin core, changed**
- `DevicePreferences` gains:
  - `onboardingDone(): Boolean` / `markOnboardingDone()`;
  - `ownerPerformerId(): String?` / `rememberOwnerPerformer(id: String?)` (triage.md T10).

  If P7/P9 has not yet added the owner preference, this package does.

**Android, new**
- `OnboardingScreen.kt`: two steps and a skip.

**Android, changed**
- `RepertosaurusApp.kt` shows onboarding before the drawer and routes when it is not done.
- `DeviceSettings` exposes the owner performer.
- The drawer gains a "Who you are" item beside "Colour ramp".

## 2. Decisions

**OB1. Onboarding shows once, on any install where `onboardingDone()` is false. That includes
installs with data already on them, such as the user's phone** (journal session 11, D39). It is
shown after the database has loaded (`DatabaseState.Ready`), never in place of `RecoveryScreen`.

**OB2. Two steps, in this order:**
1. **"How should cold songs look?"** The three ramps are rendered as tappable cards, each showing
   its four labelled steps. `ColourRamp.DEFAULT` (Danger → safe) is preselected. One tap selects,
   and "Next" continues.
2. **"Which of these is you?"** The live performers are listed, each tappable, plus "None of
   these / skip".
   - Choosing one sets the owner performer.
   - With **no performers at all**, which is a fresh install before any import, the step is
     skipped automatically.
   - Adding a performer from onboarding is out of scope; the Repertoire route does that.

   "Done" finishes.

**OB3. "Skip setup" is on every step.** It ends onboarding at once, keeps whatever was chosen so
far, and leaves everything else at its default. **Finishing and skipping both mark onboarding
done.** It never reappears on its own.

**OB4. Every answer is written as it is chosen, not at the end,** through `DeviceSettings`. So a
process death partway through loses nothing already chosen. Because `markOnboardingDone` is
written last, onboarding shows again from the start, which is harmless.

**OB5. Onboarding is composed inside the `LocalColourRamp` provider** (journal session 11, F8
N7), so step 1's cards show the live selection.

**OB6. The drawer's "Who you are" item** opens the same performer list as step 2, with the
current choice marked and a "None" option. The "Colour ramp" item already exists (RS16).

**OB7. The copy is plain and informative:** "You can change this any time from the menu." There
is no marketing or welcome carousel, and no request for anything the app does not use.

## 3. Verification

- **Instrumented:**
  - a fresh preference file shows onboarding, a done one does not;
  - skip on step 1 marks it done, and the ramp stays the default;
  - choosing Pastel then a performer then Done persists both;
  - with no performers, step 2 is skipped;
  - `RecoveryScreen` takes precedence over onboarding;
  - the drawer's "Who you are" item changes the owner.
- Screenshots of both steps.
- **On the user's phone:** onboarding appears once after the update and never again. This can
  only be confirmed by the user.

## 4. Deferred

- Asking which instrument the musician mainly plays, to seed a first View. Views already fall
  back to the current product (views.md V21), and nobody has asked for this.
- Any tutorial or coach marks. The tap-to-log loop is its own tutorial.
