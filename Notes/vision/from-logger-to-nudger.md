---
title: "From Logger to Nudger — the Habit Former"
type: design-vision
area: product
status: active
date: 2026-09-23
---

# From Logger to Nudger — the Habit Former

Repertosaurus today is a faithful witness: one tap records that a song was worked on, and the
list shows what has gone cold. That is necessary and no longer sufficient. What a musician wants
from it is **support in sustaining a long-term routine**: help keeping practice fresh, a sense of
the habit accumulating, and a clear picture when they want one of where the work is needed. This
vision is the move from recording practice to **supporting** it.

**The app assists; it never imposes.** People come to creative hobbies for freedom from mandates.
In the user's words: "We're not trying to impose, we're trying to assist." The musician is always
the one who decides what to play. The app's part is to make that choice easier, fresher and better
informed, and only when asked. Every thread below is an offer the musician can take or ignore, and
ignoring it costs nothing.

The tagline already names the mechanic: *don't let your repertoire fossilize.* Today the app shows
which songs are fossilising. From here it can also offer help digging them out, and show the
musician the pattern of their own digging.

## The one thing that must not change

**The one-tap log is sacred.** Recording "I went over this song today" stays one tap, offline,
mid-practice, with no dialog, no required field and no second decision. Every idea below lives
*around* that loop and never inside it. If a feature would add a tap to the log, the feature
changes shape, not the log. The user's own words, on the ratings below: a dropdown "makes two
clicks what should be one."

## The five threads

### Suggest: a way out of the rut

Practice goes stale when the same twenty songs get played because they are the ones the eye lands
on. A **Suggest** button breaks that: tap it, and the app proposes one song from the performer and
role the current View is about. It is a small, deliberately playful act, like a jukebox or a
shuffled deck. The musician asks for it; it is never pushed at them.

A suggestion is an idea, not an assignment. Declining it, or asking for another, is as easy as
taking it, and a suggestion never logs anything by itself.

**How the suggester behaves is the musician's to tune**, because musicians want different things
from it:

- **How random, shown as a radar chart.** Each axis is one input the suggester can weigh:
  priority, confidence, coldness, hotness, and counted skips. The musician drags each radius
  outwards to give that input more say. **With every radius at zero the suggester is pure
  shuffle**: every song in the View is equally likely, a deck of cards. Pulling radii outwards
  makes it progressively less random, and the *shape* of the polygon is the mix: a long
  "coldness" spoke with a short "priority" one reads at a glance as "mostly dig out the
  neglected, with a little gig prep". One control expresses both how random the suggester is (the
  polygon's overall size) and in what proportions it weighs its inputs (its shape). It should be a
  pleasure to adjust: a small instrument panel, not a settings form.
- **What a skip means.** Some musicians want skips counted: a song passed over again and again
  gradually builds weight, so the suggester raises it a little more often. That is a gentle,
  self-chosen nudge towards the thing being avoided. Others want the liberation of skipping with
  no record at all. **Both are legitimate, so it is a setting.** Skips are counted only when the
  musician has chosen to count them.

When skips are counted, two optional touches turn the count into a conscience rather than a
penalty:

- **Show the count.** The suggestion can say how many times this part has been skipped **since it
  was last practised**. The user's own words: "I'm exactly the kind of person who might go 'oh
  I'll skip that today' then see I've skipped it 20 times since I last practised it, and change my
  mind conscientiously." Seeing the number is information, not a scolding, and it is optional.
- **A moment to take it back.** A skip is not final the instant it is tapped. A short window lets
  the musician undo it, the same way a mis-tapped log can be undone. A skip taken back leaves no
  trace, and changing one's mind should cost nothing.

This is the vision's principle in miniature. The same feature is supportive for one musician and
oppressive for another, so the musician decides, and nothing is assumed on their behalf.

### Timed practice: how much, not only how often

A tap records *that* a song was touched. It cannot tell ten seconds of checking the intro from
forty minutes of woodshedding the bridge. Timed practice records **how much**, beside how often.

The timer is an alternative way to log, never a replacement for the tap. The user starts a timer
on a song, practises, stops it, and the log lands carrying its duration. It should be calm: one
clear running clock, visible from across the room, and hard to forget running. The history then
shows the minutes accumulating on each song, and both the scorecards and the triage below can
weigh them.

### Scorecards: seeing the habit

Habit trackers work because a streak is visible. A calendar grid in the style of GitHub's
contribution graph, one square per day shaded by how much was practised, turns an invisible habit
into something the user can see and protect. Around it, weeks and months summarise the pattern,
and the question the user actually asked gets answered: **which days of the week are reliable,
and which are not?**

The tone is encouraging, not accusatory. A gap is information, not a failure. The scorecards
should make a good week feel good and a bad week feel recoverable.

### Triage: what needs the work, not only what is cold

Coldest-first answers "what have I neglected?" It cannot answer "what matters right now?" A song
three weeks cold that the user has played a hundred times is less urgent than one practised
yesterday for a gig on Saturday that they still cannot get through. **Triage** is a ranking that
combines neglect with need.

Need is expressed through two ratings the user gives from feel, not from data. When starting or
finishing practice the user does not want to reason coldly about intervals. They want to answer
at most two questions:

- **Priority:** is this a priority right now? (For example, an upcoming gig.)
- **Confidence:** how confident would I be to gig it tomorrow?

Each is a four-step scale, **0–3: not at all, somewhat, certainly, exceptionally.** That is
coarse on purpose: four steps are answerable at a glance and still enough to rank songs against
each other.

These rules are fixed by the user:

- **Both ratings belong to a song, a performer and a role together.** Confidence on a song's
  guitar part says nothing about its vocal. A song's guitar part can be a requirement while its
  vocal or backing vocal is only a hope. This is the same relationship as the performer–role
  toggles: the unit of practice is "this person on this part of this song."
- **Both are sticky.** A rating stays until the user changes it. Nothing decays behind their back.
- **Both are edited on their own view, apart from the logger**, in the manner of the Repertoire
  toggles. Rating is a separate, deliberate act. It never appears in the tap path.
- **The control is a segmented `[0 | 1 | 2 | 3]`. One tap sets a value.** No dropdown.
- **Triage is one ranking among the others**, beside coldest and hottest first. Priority and
  confidence can each lead, which gives the triage mode its variants.

**Feel stays, as the session's own voice.** The optional long-press **feel** rating on a practice
log is kept beside confidence. They answer different questions:
- **Feel** is how *this* run-through went, today. It is fleeting, and one of many.
- **Confidence** is the musician's standing judgement of the part. It is sticky, and one at a
  time.

A bad day on a song the musician knows cold is exactly the case the two together can tell apart.
**Feel moves to the same 0–3 scale and the same words** (not at all, somewhat, certainly,
exceptionally), so every rating in the app speaks one language and wears one colour ramp. It stays
where it is: on long-press, never on the tap.

### Beauty: from fine to inspiring

The current look is Android-native, pleasant and anonymous. The app has earned an identity. It
should feel like a warm, well-used instrument case, not a settings screen.

The brand already points the way. The illustrator's brief in [naming.md](../decisions/naming.md)
N11 asks for:

- **flat, geometric, slightly rounded forms**;
- **warm neutrals with musically-coded accents** (deep teal, ochre, slate blue, terracotta, ink
  navy);
- a calm rather than roaring dinosaur, with "monster performer" as the voice, not the imagery.

The same restraint suits the app, which must stay fast and legible on a music stand in bad light.
Whatever is beautiful must also be readable at a glance, mid-song.

**Added by the user, 2026-09-23, after seeing the first mockups:** the look should be bold and
rectilinear, with hard shadows, and carry **texture**: something hand-made and organic. The
deeper theme, in their words: "Music is maybe the oldest art we have. Birds sing. Humans probably
made music before they fully had language or painting, definitely before we had writing.
Repertosaurus needs to evoke some of that ancient tradition. … 'tradition' comes from 'handing
on', so we want to make the user feel like they're part of a tradition, holding hands with
musicians since humans began." **It must not look like the Anthropic brand** (warm ivory, clay
accent, serif over sans).

The fossil metaphor is there to be used, lightly. Songs going cold can *look* like they are
settling into stone, and a well-worked song can look warm and alive. Colour does most of this
work, which is why colour is the user's to choose.

## Colour as a language, and the user's to choose

The ratings and the heat of the list speak through colour, and people read colour differently.
The user's default suggestion is a **pastel ramp from red to blue: red, light red, light blue,
blue**. Others will want **danger-red rising to safe green**, or **cold blue rising to red-hot**.
The ramp is therefore **a user setting in the menu**, applied consistently wherever a 0–3 value
or a heat is shown. The same number always wears the same colour within one ramp. No ramp relies
on colour alone: position and label carry the meaning too.

**Updated by the user, 2026-09-23, after seeing the three ramps rendered:**
- all three are good;
- **danger-red rising to safe green is the default**;
- **choosing a ramp is one of the questions a new musician is asked when they first open the
  app.** It stays in the menu afterwards.

## What this is not

- **Not a mandate.** The app never tells the musician what they must practise. Triage, suggestions
  and scorecards are views and offers, each one choice among others, never the default imposed on
  the logger. The musician's own choice always wins, and costs nothing to exercise.
- **Not a nag.** No notifications demanding practice and no streak-loss guilt. The app offers help
  when it is opened; it does not chase.
- **Not a spaced-repetition scheduler.** The user explicitly does not want to reason about
  intervals, and the app should not make them. Triage *ranks*; it does not *schedule*.
- **Not an objective assessment.** Confidence and priority are the musician's felt sense, and the
  app trusts them.

## Open questions (for the specs, not decided here)

- ~~Does `feel` remain beside confidence, fold into it, or retire?~~ **Decided by the user: it
  remains, rescaled to 0–3** (see Triage above). What is left for the spec: how the feel values
  already logged are read on the new scale. The log cannot be edited, so they cannot be rewritten.
  Whether feel feeds triage at all is also still open.
- **What the four triage variants are exactly**: which key leads, and in which direction each
  sorts.
- **How triage blends neglect with need**: the weighting between days-since, priority and
  confidence.
- **What counts as a "reliable" day** in the scorecards: any log, a minimum number of minutes, or
  a minimum number of songs?
- **How the timer coexists with the one-tap log** on the same screen without crowding it.
- **The radar's axes.** Which inputs get a spoke. Coldness and hotness pull in opposite
  directions: can both be non-zero, and what does that mean? Does "counted skips" appear as a spoke
  only when skips are being counted?
- **The suggester's defaults.** Where the radar starts (all zero is pure shuffle), and whether skips are counted
  by default. The vision fixes that both are settings; the spec picks the starting values, leaning
  towards the less imposing choice.
- ~~What a counted skip attaches to.~~ **Decided by the user: the song–performer–role, recorded
  in its own table,** separate from the ratings. Still open:
  - ~~Whether a skip's weight fades.~~ **Decided: skips count since the part was last practised,**
    so the tally starts again from zero whenever the part is played.
  - ~~How skips are stored.~~ **Decided: one record per skip, counted when needed**, never a
    running count, so every skip survives a merge between devices. Volume is not a concern: this
    counts human behaviour, a handful of taps a day.
