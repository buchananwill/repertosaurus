---
title: "Triage and the Ratings Editor — Decision Spec"
type: decision-spec
area: product
status: active
date: 2026-09-23
---

# Triage and the Ratings Editor — Decision Spec

Roadmap packages **P7** (this spec), **P8** (the ratings editor) and the triage half of **P9**, in
[habit-arc-roadmap.md](../roadmap/habit-arc-roadmap.md). The vision's "Triage: what needs the
work, not only what is cold" is the brief:
- two sticky 0–3 ratings, **priority** and **confidence**, per song–performer–role;
- edited on their own screen with a one-tap segmented control, **never on the tap path**;
- a triage ranking that sits beside coldest and hottest as **one ranking among others**. It is
  never the default imposed on the logger.

**The obligation test:** ratings are optional everywhere. An unrated part is not an error, and it
is not nagged about. Triage ranks; it does not schedule (vision, "What this is not").

Numbers are `T<n>`, grouped by topic. They are **not** an execution order. The data surface is
[schema-3.md](schema-3.md) (`part_rating`, `repository.ratings`). The control is
[rating-scale.md](rating-scale.md) RS9.

## 1. Type Roster

**Kotlin core, changed**
- `SessionOrder` gains `TRIAGE_PRIORITY`, `TRIAGE_PRIORITY_REVERSED`, `TRIAGE_CONFIDENCE` and
  `TRIAGE_CONFIDENCE_REVERSED`, with their comparators. The names are fixed by schema-3 M15.
- `SessionRow` gains `priority: RatingLevel?` and `confidence: RatingLevel?`.
- `SessionCoordinator` (or its row loader) attaches the ratings of the resolved part (T9).
- `DevicePreferences` gains `ownerPerformerId(): String?` and its setter (T10). The onboarding
  package P14 is its first writer.

**Kotlin core, new**
- `SortMode` (`STALENESS`, `TRIAGE_PRIORITY`, `TRIAGE_CONFIDENCE`), plus a direction: the
  two-control model of T6. `SessionOrder` is derived from them.
- `RatingsEditorState`, holding the page, search and filter state of T3–T5. It is pure, and
  shared by both entry points (T1).

**Android, new**
- `RatingsEditorScreen.kt`.

**Android, changed**
- `RepertoireScreen.kt`: a "Ratings" entry on the role screen.
- `ViewsUi.kt`, or the View menu: a "Rate these songs" entry.
- `SessionScreen.kt`: the sort control of T6.

## 2. Decisions

### The ratings editor

**T1. One editor, two entry points,** because musicians reach "rate these" from two places:
- **From the Repertoire route**, on a performer's role (for example "Will · guitar"). It lists
  the songs **where that part is enabled**, which is a live `song_performer` row.
- **From the session screen's View menu: "Rate these songs".** It lists **the active View's
  pool**, rating the part resolved by T9. This matters because capability data exists only
  for vocals (views.md §3). Without this entry, a guitar View could not be triaged until every
  guitar part had first been toggled on by hand.

Both open the same `RatingsEditorScreen` for one `(performer, instrument)` and a song list
source. The title names the part: "Will · guitar".

**T2. Each song row shows its title and artist, and two `RatingSegmentedControl`s labelled
"Priority" and "Confidence"**, at the full 0–3 range. **One tap sets a value, and a tap on the
selected value clears it** (RS9). Each tap writes through `repository.ratings` immediately. There
is no Save button, because the ratings are sticky and a rating is its own confirmation. Each
control passes a **unique `tagPrefix`** per song and kind (journal session 11, F8 N7).

### Paging, search and filter (journal session 11, D40)

**T3. The list is paged A–Z: one letter a page.** A letter strip across the top offers `#`
(digits and symbols) and A–Z.
- A letter with no songs in the current source and filter is greyed and not tappable.
- The strip is a grid that wraps to fit the phone. It never scrolls sideways.
- The page opens on the first letter that has songs.
- A song's letter is taken from its title **after dropping a leading "The ", "A " or "An "**.
  "The Weight" files under W, because musicians look songs up that way. That rule is authored
  here, and it is the only one.

**T4. Search overrides the letter.** Typing shows every match across all letters, in title
order, under a "Search" heading. Clearing the search returns to the letter that was open. It
matches on the title and the artist name, using the same normalisation as the Songs route's
search. There is one search implementation, not a second.

**T5. A three-way filter: All / Unrated / Rated.**
- **Unrated** means neither rating is set for the part.
- **Rated** means at least one rating is set.
- The filter combines with the letter and with search.
- It defaults to **All**.
- The filter and the letter are screen state, saved across rotation, **not** preferences.

**T5a. The same paging, search and filter apply to the Repertoire route's toggle list**
(`ToggleListScreen`), because it has the same 400-row problem. The filter labels there are
**All / On / Off**. `RatingsEditorState`'s paging and search are written once and used by both.

### The sort

**T6. The sort becomes two controls: a mode and a direction.** The current chips (one per
`SessionOrder`) cannot hold six sorts.
- **Mode** is a three-way segmented control: **Cold**, **Priority** and **Confidence**.
- **Direction** is one flip button.

| Mode | Direction "need first" (default) | Direction flipped |
|---|---|---|
| Cold | `COLDEST_FIRST` | `HOTTEST_FIRST` |
| Priority | `TRIAGE_PRIORITY` | `TRIAGE_PRIORITY_REVERSED` |
| Confidence | `TRIAGE_CONFIDENCE` | `TRIAGE_CONFIDENCE_REVERSED` |

Persistence is unchanged (views.md V13b). The resolved `SessionOrder` is what is stored.
**Changing mode or direction scrolls the list to the top** (journal session 11, F7).

**T7. The triage comparators are lexicographic,** with staleness as the final key and the title
last, as today:
- **`TRIAGE_PRIORITY`:** priority high to low, then confidence low to high, then `COLDEST_FIRST`'s
  order.
- **`TRIAGE_CONFIDENCE`:** confidence low to high, then priority high to low, then
  `COLDEST_FIRST`'s order.
- **Each `_REVERSED` is its partner's exact reverse.** For priority that means priority low to
  high, then confidence high to low, then `HOTTEST_FIRST`'s order.

This **is** the vision's blend: neglect enters as the tie-breaker. The keys have four steps each,
so ties are large and neglect does real work. The order also reads aloud as a sentence ("top
priority, then least confident, then coldest"), which a weighted score cannot. **Feel does not
enter triage** (roadmap G9 default, adopted as an assumption).

**T8. Unrated sorts after every rated value, in both directions, within its key.** An unrated
priority is "no statement", not "0". Treating it as 0 would bury it under "need first" and raise
it under the reverse, which puts words in the musician's mouth. On a fresh install with nothing
rated, every triage sort therefore degrades to plain staleness. That is correct: it asks nothing
of the musician.

### Whose ratings

**T9. A session row reads the rating of `(song, performer, the View's practice instrument)`.**
The performer is resolved in this order:
1. the View's filter performer, when it names one;
2. otherwise the **owner performer** (T10);
3. otherwise none.

With no performer, **the Priority and Confidence modes are shown disabled**, with the line "Choose
who you are in Settings to sort by ratings". Nothing is guessed.

This **amends views.md V12a deliberately.** V12a said the sort has no performer dimension. That
still holds for **staleness**, because events carry no performer. Triage adds the ratings as a
second input, and they do have one. (Journal session 11, F4.)

**T10. The owner performer ("me") is a device preference,** not synced, because the device's
owner is a fact about the device (views.md V19's reasoning). It is set in onboarding (P14) and
changeable from the drawer. Soft-deleting that performer resolves it to none. The app does not
choose a replacement.

## 3. Verification: what must be demonstrated

- **Shared:**
  - the T7 comparators on a fixture that covers every key tie, including unrated at each key in
    both directions (T8);
  - the V17a enum-to-CHECK pin extended to the six names;
  - T3's letter rule ("The Weight" → W, "9 to 5" → #);
  - T4 search across letters;
  - T5's filter;
  - T9 resolution in all three branches.

  Insert the ratings out of order.
- **Mutation:** swap the direction of T7's second key and show a test fail.
- **Instrumented:**
  - both entry points open the editor on the right part;
  - one tap writes a rating, and a tap on the selected value clears it;
  - letter, search and filter;
  - the triage mode sorts the session list;
  - a mode change scrolls to the top;
  - the disabled state with no owner.
- Screenshots of the editor on a letter page, a search result, the Unrated filter, and the
  session list in triage mode.

## 4. Deferred

- Showing ratings on the session row itself, for example as a small priority mark. It is not
  asked for, and it would add visual weight to the one-tap list.
- Bulk rating, such as "set priority 2 on this whole set list". Set-list-scoped Views are
  deferred anyway (views.md §3).
- Feel as a triage input (G9).
