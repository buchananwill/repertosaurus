---
title: "Data Model — Decision Spec"
type: decision-spec
area: data-model
status: active
date: 2026-08-15
---

# Data Model — Decision Spec

The schema contract. Implementing agents execute against this; it is not a discussion document.
Numbers are for citation, not execution order.

Storage is SQLite via SQLDelight, schema authored in real `.sq` files. Identifiers are UUID
text. Dates are ISO-8601 `YYYY-MM-DD` text.

> **Revision note.** This spec was rewritten after an adversarial review found defects that
> would have shipped: duplicate lookup rows that no merge rule could reconcile, an undo path
> that could not work, an ordering column that violated the spec's own sync invariant, and a
> migration that discarded major/minor for the entire repertoire. Sections marked **[R]** exist
> because of that review. Read them before proposing simplifications — the simple version was
> tried and was wrong.

## The normalisation test

A value earns its own table when it **repeats across rows, is typed by a human, and can
therefore drift.** That is the whole criterion, and it is the failure the source workbook
demonstrates in four separate places: 288 artist strings for 278 artists; a column per singer;
`Andy?` and `Andy` in two copies of the same gig; `Party` and `party`.

It is equally a design error to make a table out of something that does not repeat.
`reference_recording` and `chart_url` are free text and stay that way.

## Type Roster

| Table | Nature | id | Written by |
|---|---|---|---|
| `artist` | mutable record | derived | desktop |
| `artist_alias` | child of artist | random | migration |
| `performer` | mutable record | derived | desktop |
| `instrument` | lookup, user-extensible | derived | rarely |
| `tag` | lookup, user-extensible | derived | rarely |
| `groove` | lookup, user-extensible | derived | rarely |
| `venue` | lookup, user-extensible | derived | desktop |
| `practice_context` | lookup, user-extensible | derived | rarely |
| `song` | mutable record | derived | desktop |
| `song_performer` | junction | derived | desktop |
| `song_instrument` | junction, carries facts | derived | desktop |
| `song_tag` | junction | derived | desktop |
| `practice_event` | **immutable, append-only** | random | phone, constantly |
| `practice_event_void` | **immutable, append-only** | random | phone |
| `setlist` | mutable record | random | desktop |
| `setlist_set` | child of setlist | random | desktop |
| `setlist_item` | mutable record | random | desktop |

## Decisions

### Identity **[R]**

1. Ids are UUID text, generated client-side. No autoincrement integers anywhere — ids must be
   generatable offline on two devices with no coordination.
2. **Derived ids** are `UUIDv5(namespace, canonical_key)`, so two devices independently
   creating the same logical row generate the *same* id and last-write-wins can then reconcile
   them. **Random ids** are UUIDv4.
3. The rule for choosing: **derive the id where a duplicate would be an error; randomise it
   where a duplicate is meaningful.** Two devices typing "wedding" into a type-ahead mean one
   context, not two. Two taps on *Valerie* mean two practice sessions (decision 36).
4. Canonical keys for derived ids:
   - lookups (`instrument`, `tag`, `groove`, `venue`, `band`, `practice_context`, `performer`,
     `artist`) — the normalised name, per decision 17.
   - `song` — `artist_id` plus normalised title.
   - junctions — `UUIDv5(namespace(table), fk_a + "/" + fk_b)`, the same shape as every other
     derived id. **This corrects an earlier form** which read "the composite of the two foreign
     keys, e.g. `UUIDv5(song_id, instrument_id)`", using the first key directly as the
     namespace. That form was inconsistent with 4a's per-table namespaces and 4d's separator,
     produced different ids, and — because it carries no table identity — would collide across
     two junctions over the same pair of ids. Every junction uses the corrected form:
     `song_instrument`, `song_tag`, `song_performer`, `setlist_item_performer`.

4a. **The namespace constants are fixed and must never change.** Every derived id already
    written becomes unreachable if they do, so these are ratified values, not defaults:

    ```
    ROOT              = UUIDv5(DNS, "songbook.dev")
                      = 3ce0f1dc-b3b4-5aee-9683-49dfb0741ca7
    namespace(table)  = UUIDv5(ROOT, table_name)
    row id            = UUIDv5(namespace(table), normalise(name))
    ```

4b. Namespaces are **per table**, not one shared root. A lookup's canonical key is only its
    normalised name (decision 4), so a shared namespace would give the tag `guitar` and the
    instrument `guitar` the same id. Verified: `instrument/guitar` is
    `f6b6f826-8eeb-5acc-9314-d2c096f780f6` and `tag/guitar` is
    `f11c3596-de4a-5c65-b83a-ecd328b88ca2`.

4c. Decision 17's normalisation strips punctuation, so the seeded tags `cw-duet` and
    `need-to-learn` key on `cw duet` and `need to learn`. Display names keep their hyphens.
    This is correct — it means a user typing "CW Duet" matches the existing tag.

4d. **The `song` canonical key is `artist_id + "/" + normalise(title)`.** The separator is
    ratified, not incidental: the Kotlin core and the Python migration must concatenate
    identically or the same song gets two ids and the devices never converge.

4e. **"Punctuation" means anything that is not a Unicode letter or digit.** Accented characters
    are letters and survive — `Beyoncé` normalises with its `é` intact. Two consequences to
    hold both implementations to: Python's `\w` also matches Unicode letters, so the two agree
    on accents; but Python's `\w` includes `_` while "letter or digit" does not, so **the
    underscore must be handled explicitly** in whichever implementation would otherwise keep
    it. Any divergence here silently forks an id.
5. An id is opaque and immutable once written. Renaming an artist changes `name`, never the id;
   the derived id only has to converge at creation time, which is the moment duplicates are
   created.
6. `practice_event` and `practice_event_void` ids **must be random.** Deriving them would
   collapse two legitimate same-day sessions into one row and silently lose data.

### Mutability and merge **[R]**

7. `practice_event` rows are **immutable once written**. There is no update path and no edit UI.
8. Deleting a practice event is an **append**, not a mutation: a row in `practice_event_void`
   naming the voided event. Reads left-anti-join it. A union merge cannot suppress a row, so
   without this the undo on the product's highest-frequency path silently fails — the voided
   event returns on the next sync.
9. **Every table** carries `updated_at TEXT NOT NULL`, `deleted_at TEXT NULL` and
   `device_id TEXT NOT NULL`, including child and junction tables. Append-only tables carry
   `created_at` in place of `updated_at` and no `deleted_at`. There are no exceptions; a table
   without a tombstone cannot be deleted, because a stale device reinserts the row on merge.
10. Timestamps are ISO-8601 UTC in one fixed-width form: `YYYY-MM-DDTHH:MM:SS.sssZ`, always
    three decimal places, always literal `Z`. Variable precision breaks string comparison —
    `...:00Z` sorts after `...:00.500Z` because `Z` > `.`.
11. The merge rules, in full. There are two and there must never be a third:
    - **Append-only tables** (`practice_event`, `practice_event_void`) merge by **union on id**.
    - **Every other table** merges **last-write-wins per row**, ordered by
      `(updated_at, device_id, id)`, with a tombstone winning a tie against a live row.
12. The `device_id` and `id` components of decision 11 are not decoration. Without a total
    order, two devices editing the same field in the same millisecond each keep their own value
    and diverge permanently, with no subsequent sync repairing it.
13. Junction rows are individually addressable rows with derived ids, not a set to be replaced
    wholesale. "Replace this song's tags" is a multi-row delete plus insert, which two offline
    devices cannot merge coherently.
14. No table may depend on insertion order or a monotonic counter. See decision 44.

### Lookup tables — the user extends these, not the schema

15. `instrument`, `tag`, `groove`, `venue`, `band` and `practice_context` are **tables, not
    enums**. Each carries `id`, `name`, plus the standard three. Adding a row must never
    require a code change or a migration.
16. All follow the same UI rule: **a type-ahead that creates on enter, surfacing near-matches
    while typing.** No admin screens. Friction in adding a lookup value is how you get `Party`
    and `party`.
17. The normalisation function — **Unicode-normalise to NFC**, lowercase, trim, strip a leading
    `The `, fold `&` to `and`, strip punctuation, collapse whitespace — is used in three places
    and must be one implementation in the shared core: to match near-duplicates in the
    type-ahead, to generate derived ids (decision 2), and by the migration.

17a. **The NFC step is not optional and its absence is a latent id fork.** `é` can be a single
    code point or `e` followed by a combining acute, and macOS and iOS input methods routinely
    produce the decomposed form. Without normalisation the two spellings of *Michael Bublé*
    derive different ids, on different devices, permanently — and nothing would look wrong on
    either screen. Normalise before doing anything else, in every implementation.

17b. **NFC, not NFKC, and every implementation must agree.** NFKC additionally applies
    compatibility folding — ligatures, full-width forms, roman-numeral characters, `™` — which
    is meaning-changing and, because decision 5 makes ids immutable, unrecoverable. Id
    derivation must be the least lossy transform that fixes the real keyboard-reachable fork.
    Measured on the source workbook by two independent implementations: only three non-ASCII
    code points occur (`é` ×21, `’` ×5, NBSP ×1), nothing is decomposed, and NFC and NFKC
    produce identical output on every one of 24,705 text cells. **The choice is therefore free
    today and expensive later** — compatibility characters arrive by paste, not by keyboard,
    and one pasted `ﬂ` forks an id permanently.

17c. **Matching may be lossy; derivation must not be.** If ligature-tolerant search is ever
    wanted, add NFKC as an *extra pass in the type-ahead only*. Never in the id path.

17d. **Normalisation does not catch typos, and the difference decides who protects the user.**
    Two cases look alike and behave oppositely:
    - `Fratellis` / `The Fratellis`, `AC DC` / `AC/DC`, `Florence & the Machine` — these
      normalise to the *same* string, so they derive the *same* id and converge **on their own**.
      The type-ahead suggestion is a courtesy; the derived id is the guarantee. A user who
      ignores the suggestion still gets one row.
    - `Ukelele` / `Ukulele`, `Beyonce` / `Beyoncé` — these normalise to *different* strings, so
      they derive *different* ids and **never converge**. Nothing downstream will ever merge
      them.

    For the second class the UI is the only defence, so the type-ahead carries a capped
    edit-distance pass — one edit, or two at eight characters or more — surfacing the existing
    row before a duplicate is committed. **Matching only; it must never reach id derivation**
    (17c), or two genuinely different names one edit apart would collapse into one row.
18. `instrument` is seeded with `vocal`, `backing vocal`, `guitar`, `bass`, `keys`, **in that
    display order**. The schema cannot currently express it — there is no ordering column, and
    sorting by name gives *backing vocal, bass, guitar, keys, vocal*, which puts the most-used
    chip last. For phase 1 the order is applied in the shared core: seeded instruments in the
    order above, user-added ones appended alphabetically. **If display order needs to become
    data — user-reorderable chips — that is a schema change (`sort_order INTEGER`) and should be
    made deliberately rather than by accreting special cases in the UI.** Deferred, not decided.
    **Voice is an instrument here** — it behaves identically everywhere in this schema, and two parallel
    vocabularies (one for practice, one for line-ups) would drift apart. `backing vocal` is
    separate from `vocal` so a song can carry both a lead and a backing-vocal note without
    needing two rows for the same `(song, instrument)` pair.
19. `practice_context` is seeded with `practice`, `twitch`, `rehearsal`, `gig`. `tag` is seeded
    from the workbook's flag columns: `party`, `christmas`, `cw-duet`, `target`,
    `need-to-learn`.

### artist

20. `song.artist_id` is a foreign key. Artist names are never stored as free text on a song.
21. Columns: `id`, `name` (canonical display form, e.g. `The Kaiser Chiefs`), `sort_name`
    (article moved to the end, e.g. `Kaiser Chiefs, The`), plus the standard three.
22. `artist_alias (id, artist_id, alias, …)` exists for aliases that decision 17's
    normalisation **cannot** resolve — `OCS` for `Ocean Colour Scene`, `Florence + the Machine`
    for `Florence and the Machine`. It is not needed for the seven spelling collisions the
    migration will find; those all collapse under normalisation alone. Do not populate it with
    cases decision 17 already handles.
23. **We do not model composition separately from recording.** For a covers repertoire the
    version you play is the only version that matters; splitting writer from performer buys
    nothing and costs a join on every screen.

### performer

24. `performer` is a first-class table: `id`, `name`, `notes`, plus the standard three.
25. This is the fix for the workbook's worst structural habit: it grew a **new column per
    singer** — `Coralie Vox`, `Carla Vox`, `Sophie-Mae Vocal`, `Kendra Piper`, `Will Vocal` —
    and the two copies of the Anthony Horne gig differ precisely because one says `Andy?` and
    the other `Andy`. A new band member must cost zero schema change and zero free text.
26. `song_performer (id, song_id, performer_id, is_lead, vocal_range, notes, …)` records **who
    can sing or play a given song**. A song-level capability, distinct from decision 48.
    `is_lead` is `NOT NULL DEFAULT 0` with a `CHECK (is_lead IN (0, 1))`.
27. `vocal_range` lives here, not on `song`. Range is only meaningful for a particular voice —
    a song that sits high for one singer sits comfortably for another. Stored as `INTEGER`
    with a `CHECK`, mapping the workbook's `H`/`L` to `1`/`0`; it is a closed two-value
    vocabulary, so a free-text column would drift into `H`, `h`, `high`, `H/L`.

### song

28. Columns: `id`, `title`, `artist_id`, `reference_recording`, `key_signature`,
    `tonal_centre`, `tonality_note`, `tempo_bpm`, `duration_seconds INTEGER`,
    `decade INTEGER`, `loop_length INTEGER`, `chord_count INTEGER`, `chord_pattern TEXT`,
    `groove_id`, `mashup_note`, `notes`, `chart_url`, plus the standard three.
28a. **`artist_id` is `NOT NULL`.** Decision 4 makes it half of the song's canonical key, so a
    null artist yields an unstable derived id and two devices would not converge. Where the
    workbook has no artist, the migration attaches a seeded `Unknown Artist` row rather than
    leaving the column null. A placeholder is honest; a null FK spreads `LEFT JOIN` through
    every screen.
29. `reference_recording TEXT NULL` names the specific recording all musical facts are stated
    against. A key claim is not verifiable without it. Free text or a URL; not validated; not a
    table, because it does not repeat.
30. `chord_pattern` stays **free text**, deliberately, against the normalisation test. It is a
    rarely-populated field over a vocabulary that is not closed, and the cost of a lookup table
    exceeds the drift risk. Mandate roman-numeral notation (`I-V-vi-IV`) in the UI hint and
    revisit if it turns out to be populated broadly.
31. `chord_count` is authoritative only when `chord_pattern` is absent; where both are present
    the pattern wins and the count is display-only. The workbook has 23 chord counts and almost
    no patterns, which is why the field survives decision 43.

### Key and tonality

32. `key_signature INTEGER NULL` is the dominant diatonic key signature as a signed sharp/flat
    count, constrained to −7..+7. Negative is flats, positive is sharps, `0` is none. Displayed
    as `3♯` / `2♭` / `0`.
33. `tonal_centre INTEGER NULL` is the home bass note as a **pitch class 0–11**, where 0 is C.
    Stored as an integer, not a note name, so transposition is modular arithmetic rather than
    string manipulation.
34. Where the home bass note is ambiguous — a modal loop, an opening inversion or slash chord —
    the tie-break is **the root of the first or last chord of the main loop**, not the sounding
    bass note of that chord. A loop of `Am/C – F – C – G` has tonal centre `A`, not `C`.
35. **There is no mode or quality column.** Major/minor is implied by the pair: `0` + `A` is A
    minor, `0` + `C` is C major. A quality column would be a third field that can contradict
    the other two. Do not add one.
36. Decision 35 only holds when `key_signature` is non-null. The migration must therefore
    **derive `key_signature` from the quality of a named key** wherever the workbook gives one
    — `Em` → `+1`, `E` → `+4`, `Bbm` → `−5`, `Bb` → `−2`. Without this the workbook's 2,121
    plain-name cells all import as `key_signature NULL` and `E` becomes indistinguishable from
    `Em`, destroying major/minor across the entire repertoire on day one.
37. `key_signature` remains **nullable**, for the 15 genuine unknowns and for songs whose
    tonality is modal enough that no signature is honest. It must not be *required* in phase 1.
38. `key_signature` and `tonal_centre` are never concatenated into a display string in the
    database. Formatting is a UI concern. Much of this repertoire is not functionally tonal and
    one combined field forces a lie: *Shake It Off* is `0` + `A`; *Sweet Home Alabama* is `1♯`
    + `G`.
39. `tonality_note TEXT NULL` is free text, **hidden by default in every UI**, for the cases the
    two typed fields do not settle. It exists to end an argument before gig day, not to be read
    routinely.
40. Enharmonic spelling is derived at display time from `key_signature`. When `key_signature` is
    null, fall back to a fixed spelling table — flats for pitch classes 1, 3, 6, 8 and 10 — so
    the UI is never unable to name a key.

### Per-instrument song facts

41. `song_instrument (id, song_id, instrument_id, difficulty, patch, notes, …)` carries facts
    true of a song **on one instrument**. This absorbs the workbook's `bass difficulty`,
    `Guitar comments`, `BV Comments`, `C GTR` and `Wavestate Patches` columns, and means "how
    hard is this on keys" costs no schema change.
42. `difficulty INTEGER NULL` with `CHECK (difficulty BETWEEN 1 AND 5)`, mapping the workbook's
    `a`–`e` to `1`–`5`. The range is five, not three: the extract pass found `d` ×31 and `e` ×24
    in `bass difficulty` alongside the `a`/`b`/`c` originally specced, and a three-value
    constraint would silently discard 56 cells. `patch TEXT NULL`, `notes TEXT NULL`.
43. `song_tag (id, song_id, tag_id, …)` is a proper many-to-many against `tag`. Tags are never
    stored as free text on a song.

### practice_event

44. The high-frequency table and the reason the product exists. Columns: `id`, `song_id`,
    `logged_on` (date), `instrument_id`, `context_id`, `feel`, `note`, `created_at`,
    `device_id`.
45. `logged_on TEXT NOT NULL` defaults to today. Logging for a past date is available but
    demoted into a menu; it must never be on the primary tap path.
45a. **`context_id` is nullable.** A one-tap log must never require a second chip. The app
    writes the session's context when the user has set one and null otherwise; a null context
    reads as "just practising" and is not an error.
46. `feel INTEGER NULL` is 1–3, optional. **Surfaced as long-press on the row, not as an extra
    step in the tap path.** A plain tap logs with `feel` null; long-press opens the rating.
    Day-one affordance, not a later addition.
47. **No uniqueness constraint on `(song_id, logged_on, instrument_id)`.** Working the same
    piece several times across a day is normal practice behaviour and each pass is a
    substantive session in its own right. The UI must not treat the second as a mistake. Undo
    is a real operation — see decision 8 — not something a constraint quietly swallows.
48. Derived values are computed, never stored. `last_practised` is `MAX(logged_on)`;
    `times_practised` is `COUNT(*)`; `days_since` is derived at query time. The workbook's
    abandoned counter columns are the cautionary tale — anything a human must increment by hand
    will not be maintained.

### setlist

49. `setlist` columns: `id`, `name`, `performed_on` (date, nullable), `band_id`, `venue_id`,
    `client`, `notes`, plus the standard three.
49a. **`band` is a first-class lookup table** — `id`, `name`, `notes`, plus the standard three.
    The user performs under several acts and configurations, and a set list belongs to one of
    them. The workbook shows this plainly once the worksheet names are visible: `Blue Lion`
    recurs across four tabs, `Acoustic` and `ACOUSTIC` mark a different configuration of the
    same booking, and `Bass-vox Rep` is a repertoire for a specific role. Earlier gig sheets
    name several acts outright — *The Fleet*, *The Gifted*, *Three Lance*, *Jukebox Nation*.
49b. Band and venue are **different dimensions and must not be conflated.** An early reading of
    the tab names took `Blue Lion` for a pub; it is a band. Nothing in a tab name is
    self-describing, so the migration classifies rather than assumes — see migration rule 18.
49c. Per-band repertoire — "what can we play as an acoustic duo" — is **not** modelled as a
    junction yet. Use `tag` for it in phase 1. Revisit if tags prove too weak.
50. `venue` is a table because venues genuinely repeat, and because it makes a question worth
    asking answerable: *what did we play last time we were here?* `client` stays free text — a
    wedding couple is a one-off.
51. `setlist_set (id, setlist_id, set_no, target_minutes, …)` replaces the workbook's `2x60`
    free text. The set list screen totals actual duration against `target_minutes` per set;
    parsing a string in the UI to do that would be a defect.

### setlist_item

52. Columns: `id`, `setlist_set_id`, `song_id`, `position`, `transpose`, `tempo_override`,
    `note`, plus the standard three. **There is no `lead_performer_id`** — see decision 58.
53. Set membership is the **foreign key `setlist_set_id`**, not an integer compared by value.
    `setlist_id` and `set_no` are not repeated on the item; both are reachable through the FK.
    Carrying `set_no` in two tables that merge independently lets two devices each add "set 3"
    and leaves every item in both.
54. `position TEXT NOT NULL` is a **fractional ordering key** (LexoRank-style), not an integer
    index, with `(position, id)` as the total order. Inserting or moving one song allocates a
    key between its new neighbours and touches **exactly one row**, which is what
    last-write-wins can merge. An integer index makes every reorder a mass update, and two
    devices reordering offline produce duplicated positions and holes — and it would violate
    decision 14.
55. `transpose INTEGER NOT NULL DEFAULT 0` is a signed semitone offset from the song's reference
    key. The song's own key never changes. Sounding pitch class is
    `(tonal_centre + transpose) mod 12`.
56. The sounding key signature is `key_signature + 7 × transpose`, reduced by ±12 until it lies
    in −7..+7. Where two spellings are legal — any result in `{±5, ±6, ±7}` — **choose the one
    with the smaller absolute value, breaking a 6-versus-−6 tie toward flats.** Without this
    rule C major transposed up a semitone renders as C♯ major with seven sharps rather than
    D♭ major with five flats. Unit-test the full 15 × 12 grid.
56a. **The respelling rule fires only when `transpose ≠ 0`.** `key_signature` is already
    constrained to −7..+7, so at zero there is nothing out of range to reduce and
    `soundingKeySignature(ks, 0)` must be the identity. Applying it uniformly would silently
    re-spell a stored value the user chose deliberately — a song genuinely notated in C♯ major
    would display as D♭ major and never show what was entered. Display stored data as stored;
    respell only what transposition actually moved.
57. This arithmetic lives in the shared core with tests, never in a UI layer.
58. **Who performs a set list item is a junction table, not a column.**
    `setlist_item_performer (id, setlist_item_id, performer_id, position, …)` with the standard
    three. `position` orders them — 1 is the lead, 2 the co-lead — so a printed set list is
    deterministic rather than alphabetical by accident.

58a. **The two performer tables answer different questions and must never be merged.**
    `song_performer` records who **knows** a song — a capability, true independently of any
    gig. `setlist_item_performer` records who it is **staged with** on one night. Any song can
    be staged as a duet without being "a duet song"; duet-ness is a property of the
    performance, so there is no `is_duet` flag anywhere.

58b. **A capped pair of columns was rejected.** `lead_performer_id` plus a nullable
    `duet_performer_id` is cheaper and needs no join, but it is structurally identical to the
    workbook growing a column per singer — `Coralie Vox`, `Carla Vox`, `Sophie-Mae Vocal`,
    `Kendra Piper` — which is the failure this project exists to end. A cap of two is a guess
    about the future, not a design. The first trio, or a guest sitting in for one number, would
    reintroduce the disease on day one of the cure.

58c. The accepted cost: "who is singing this tonight" becomes an aggregate over the junction,
    ordered by `position`, rather than a column read. That is one query written once, and a
    permanent tax on the common case of a single performer. Taken deliberately. An item with
    **nobody** staged reads as NULL, not an empty string, and must never drop out of a set list
    query — the same `LEFT JOIN` discipline as decision 8.

58e. **`setlist_item_performer.position` is a plain `INTEGER`, unlike `setlist_item.position`.**
    That looks inconsistent and is deliberate. Decision 54 makes item ordering a fractional TEXT
    key because a set list is a long list two devices reorder independently offline; this
    position orders two or three people inside a single item, which is not that. Reads order by
    `(position, id)` so a tie between two devices both writing `1` is at least deterministic,
    and `UNIQUE(setlist_item_id, performer_id)` stops the same person appearing twice.

58f. Removing a performer from an item is a **soft delete**, per decision 9, like every other
    mutable row. There is no hard delete anywhere in this schema.

58d. **Not added:** a `role` column (lead / harmony / feature) — no defined vocabulary, so it
    would become free text and drift; `position` already carries what is needed. Nor
    `instrument_id`, which belongs to the deferred full gig line-up and is the natural
    extension of this same junction when that arrives.
59. A set list reads song facts **through the foreign key**. Facts are never copied onto a
    `setlist_item`. `transpose` and `tempo_override` are the only sanctioned per-gig
    divergences.
60. Multi-select with transpose-all ±1 and reset-to-original is a required set list affordance.
    It operates on `transpose` only. The motivating case is a downtuned guitar, where horns and
    keys otherwise get caught out — so **set lists display and print sounding (concert) key.**

## Deliberately not modelled

- **Full gig line-up** (`setlist_performer`, who played what at which gig). The `performer` and
  `instrument` tables make it a trivial addition later; not needed before phase 3.
- **Mashups as a song-to-song relation.** `song.mashup_note` is free text for now. Revisit if
  mashups need querying rather than reading.
- **The set-list triage columns** — `Confidence`, `Fit`, `Sum`, `Score`, `Priority`,
  `Inclusion`, `GTR Triage`, `Vox Triage`, `W`/`R`/`A`. Working scratch from building one
  particular set; they do not describe a song. Not imported; raise it if any prove load-bearing.
- **Song release year.** `decade` is kept as the workbook has it. A precise year is not recorded
  anywhere and nothing needs it.
