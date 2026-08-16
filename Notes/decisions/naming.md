---
title: "Product Name — Decision Spec"
type: decision-spec
area: meta
status: active
date: 2026-08-16
---

# Product Name — Decision Spec

The app was built under the working title **Songbook**, which is taken on both the Google Play
Store and the Apple App Store and was never intended to survive. This document fixes the real
name, records the candidates that were rejected and the evidence that killed them, states the
intellectual-property exposure the name and its intended logo carry, and defines the rename
scope so it can be executed as one piece of work.

Decisions here are numbered `N1`–`N12`. The data-model spec owns the global decision sequence
(currently through 58) and this document deliberately does **not** extend it, so that a naming
decision and a schema decision can never be confused by number.

## The Decisions

### The name

**N1. The product is called `Repertaurus`.** Spelled `R-E-P-E-R-T-A-U-R-U-S`. Canonical
everywhere: store listing, launcher label, package namespace, repository, documentation.

**N2. It is pronounced `re-per-TAU-rus`** — four syllables, primary stress on the third. The
metre is a two-syllable pickup into a falling close.

**N3. The tagline is "Don't let your repertoire fossilize."** It is load-bearing rather than
decorative: the name is a coinage and does not describe the product on its own, so wherever the
name appears cold — store listing, first-run screen, README — the tagline appears with it.

It also does something the name cannot. **It names the mechanic the app already implements**: the
Session screen sorts coldest-first, so a song going cold *is* the thing being tracked. The
dinosaur stops being a decorative pun and becomes the product's own metaphor — a repertoire left
alone fossilises. The "monster performer" idea that motivated the name survives as brand voice,
not as copy.

Spelling note: recorded with the `-ize` ending as the user wrote it. The codebase otherwise uses
British `-ise` (`Normalise.kt`, `unicodeNormalise`). Both are valid in British English — `-ize` is
Oxford spelling — but the two conventions now coexist. This is deliberate, not drift: the tagline
is copy, the code is code. Do not "align" them.

**N4. The word and the logo point at different animals, and this is a known, accepted tension.**
`-taurus` is Latin for *bull* — Taurus the zodiac sign, the Minotaur. Dinosaurs are `-saurus`,
from Greek *sauros*, lizard. **The dinosaur reading is therefore carried entirely by the logo,
not by the name.** This was weighed against `Repertosaurus`, which makes the word do that work,
and the shorter form was preferred anyway. Recorded so a later reader does not "correct" the
spelling to `-saurus`.

**N5. The dominant reading of the name is *repertoire*, and that is what makes it safe.**
Consumers do not parse `Repertaurus` into `Reper` + `taurus`; the overall commercial impression
is a single invented word beginning `Repert-`. This is not merely aesthetic — it is the
load-bearing fact in the trademark assessment at N8, because neither the bull nor any TAURUS
mark is ever brought to mind.

### Clearance and exposure

**N6. The name is clear on every automatable surface.** Searches returned **zero hits** for
`Repertaurus` — no app on either store, no company, band, album, product, domain or handle. No
search engine returned the literal string.

**N7. No trademark register was ever actually searched, and this gap is permanent until closed
by a professional.** USPTO `tmsearch` returns a JavaScript shell; its API path 404s. EUIPO
eSearch returns site chrome only. TMview resets the connection. Justia, Trademarkia and
uspto.report all return HTTP 403. **The one route that worked is USPTO TSDR by serial number**,
which is genuine primary-source register data but requires the serial to be known first — so
every register fact below is verified, and the *absence* of a `Repertaurus` mark is not.
The cheap definitive checks are a **Google Play Console name reservation** and a paid or
attorney-run register search. Neither has been done.

**N8. Name exposure is LOW, and there is near-precedent on the register.** `TAURUSX`, USPTO
serial 88769781, is a **live, registered class 9 mark** (downloadable software, mobile apps,
music files) owned by WEBEYE PTE. LTD. — a mark that fuses TAURUS with another element and
coexists. Google Play separately carries ten unrelated Taurus apps from ten proprietors. Three
reasons stack:

1. **Crowded field.** Where many proprietors use a term in one class, each registration's scope
   contracts. "Taurus" is Latin for bull and a zodiac sign — inherently weak, already diluted in
   software.
2. **`taurus` is not a separable element** in this mark, per N5.
3. **Remote goods.** Ford (class 12), Forjas Taurus firearms (class 13), Taurus SA (B2B digital
   asset custody). No shared trade channel with a free offline practice tracker.

Dilution does not realistically reach it. US TDRA requires fame with the *general consuming
public* and niche fame is insufficient; EU/UK Art. 8(5) EUTMR needs a "link" — the average
consumer seeing a music-practice app must have a bull or a Ford saloon brought to mind. They
will not.

**N9. Logo exposure is MODERATE, and the risk is the logo you get by default.** Universal's
**Jurassic Park device mark** — USPTO serial 87485426, verified on TSDR, **live in class 9,
expressly covering downloadable and mobile software** — is described by the register as:

> a circle containing a drawing of a dinosaur skeleton in profile intersected by a shaded
> rectangle containing the stylized words, with trees silhouetted below, all surrounded by an
> ornamental border

The same device is separately registered in class 30, for biscuits, which shows how broadly
Universal defends it. **That composition — skeleton, roundel, name-bar across the middle — is
what a competent illustrator produces on the first pass from the words "dinosaur logo, app
icon". The default is the mark.**

Two further constraints:

- **Toho enforces Godzilla's *shape*, not the `-zilla` suffix.** They have pursued vendors of
  unnamed inflatable dinosaurs and asserted rights over bare silhouettes, and hold a 3D shape
  mark on the creature's form. An upright, spiny-backed, roaring reptile in dark silhouette is
  the risk; the name is nowhere near it.
- **Purple reptiles are claimed.** *Lyons Partnership v. Morris Costumes*, 243 F.3d 789 (4th
  Cir. 2001), found liability for "Duffy the Dragon" — a purple reptilian costume that was not
  Barney.

**N10. Music adjacency raises the stakes structurally, not coincidentally.** Class 9 holds
recorded music, downloadable software **and** mobile game software, so a music app and a
dinosaur film franchise are literal class-mates. "Monster performer" also plants the brand in
class 41, where entertainment marks live. A dinosaur-branded plumbing app never touches either.
**The margin for a generic dinosaur logo is smaller here than for a dinosaur-branded fitness or
finance app**, so originality in the mark must do more work. Encouragingly, no dinosaur-branded
music-practice app exists — the category is uniformly abstract and typographic.

**N11. The illustrator's brief.** Give this to whoever draws the logo **before** they start.

**Do:**

- **Choose a non-theropod body plan** — sauropod, stegosaur or ceratopsian. Every high-risk mark
  in this space (the Jurassic Park skeleton, Godzilla, Rex, Yoshi, Reptar, the Chrome offline
  dino) is a bipedal theropod. Stepping off T-rex removes most of the exposure in one decision.
- **Draw flesh, not bone.** Skeletons belong to Universal's registered device.
- **Horizontal, grounded posture** — head level or lowered, four feet down, tail out behind.
- **A single flat silhouette in one accent colour**, geometric and slightly rounded, legible at
  48px. Warm neutrals or musically-coded accents: deep teal, ochre, slate blue, terracotta, ink
  navy.
- **Fuse the music into the anatomy rather than placing it beside the animal** — stegosaur
  dorsal plates as sheet-music pages, a sauropod's neck and head as a treble-clef curve, the
  tail as a metronome arm. **This is the originality that carries the mark.** A dinosaur *plus* a
  quaver is generic; a dinosaur *made of* a music idea is ours.
- **Keep the expression neutral.** A calm dinosaur under a "monster performer" line is funnier
  than a roaring one, and safer.

**Do not:**

- **No circular or rounded badge containing the animal with a horizontal name-bar across it.**
  This is the literal registered description of the Jurassic Park device, live in class 9. It is
  the single most important thing to avoid.
- No skeleton, ribcage or theropod skull, especially in lateral profile.
- No silhouetted trees, ferns or jungle foliage beneath the animal; no ornamental rope border.
- No red-orange-on-black or amber-on-black palette; no distressed "excavated" texture.
- No upright, spiny-backed, heavy-tailed roaring reptile in dark silhouette (Godzilla's form).
- No purple reptile of any kind. No green-with-white-belly rounded dino with a back saddle or
  red-orange feet (Yoshi). No skinny green T-rex with tiny arms and anxious eyes (Rex).
- No monochrome grey/black pixel-art side-profile T-rex — the Chrome offline dino, and the most
  likely accidental collision.
- No open roaring mouth with teeth, bared claws, motion lines, fire or impact cracks. "Monster"
  stays in the words.
- **Keep `-saurus`, `-zilla`, `Rex`, `Dino`, `Jurassic` and `Rawr` out of the name, subtitle and
  store listing.** Store metadata is what triggers automated brand-protection sweeps. The name
  already avoids all of these; keep the listing clean too.

Two operational safeguards: **commission under a written assignment of copyright**, not a
licence, with a warranty of originality and no AI derivation from franchise imagery — "cartoon
dinosaur mascot" is exactly the prompt space where something already owned falls out. And
**run a Vienna-classification device search with an attorney before launch**; it is the only
thing that actually clears a drawing, which is where the risk sits.

### Rejected candidates

**N12. These names were considered and rejected. Do not resurrect them without new evidence.**

| Name | Why it died |
|---|---|
| **Songbook** | Taken on both stores. The working title only. |
| **Repertorio** | Legally weak rather than dangerous: it is the dictionary word for the product's own category in three languages. TMEP 1209.03(g) — "the foreign equivalent of a merely descriptive English word is no more registrable than the English word itself" (cf. SAPORITO refused for sausage, LAPELLE for leather furniture). EUIPO tests descriptiveness natively in Italian and Spanish. Safe to use, impossible to own. Also crowded by eight adjacent products. |
| **Repetorious** | Reached the point of a full committed rename before being reconsidered. Cleanest exact-string clearance of the spike, but web search **auto-corrected it to "repetitious"** every time — an unflattering word, and a broken first step for word-of-mouth discovery. Five Latinate syllables also sat awkwardly against a product whose pitch is one tap and no bloat. |
| **Repertorious** | Occupied by a live indexed fan-fiction directory at `repertorious.skyrock.com`. A weak obstacle in itself, but no reason to accept it. |
| **Reposaurus** | **Taken.** `pip install reposaurus` resolves today — a live PyPI package (v1.0.0, 2025-01-20) that turns git repositories into searchable text files. `reposaurus.com` is registered. Separately, a deliberate search for evidence that musicians clip "repertoire" to "repo" **found none**; the established senses are git repository, repossession and repurchase agreement. The stem does not carry the meaning. |
| **Repertosaurus** | Not rejected on evidence — it was the cleanest name found in the entire spike, with zero hits on both stores, GitHub, npm, PyPI, crates.io, Docker Hub and the open web, and it makes the *word* carry the dinosaur. Rejected on preference: five syllables, and `-saurus` in the store listing attracts the automated sweeps described at N11. **This is the fallback if `Repertaurus` ever has to be abandoned.** |
| **Anything shed-rooted** (Shedlog, Shedbook, Woodshed) | Four-plus live practice apps sit on this root — Woodshedding, Woodshedder, WoodShed Music, Woodshed Speed Trainer. Confirmed independently by two agents. |
| **Fakebook, SetBook, Tutti, GigBag, Practory, RepShed, Rep Book** | All live products in this exact category under these exact names. |
| **Segno, ChartLog, Rota, Notch** | Segno is a live music app; chartlog.com is established trading SaaS; Rota returns nine-plus Play apps and means shift-scheduling in the target market; NOTCH has registered marks in software goods classes. |
| **ChopLog, Shedlog** | Chop, shed and log are all firewood words. Stacked, they read as a woodpile before they read as music. |

The surviving runners-up outside the dinosaur family, if this is ever reopened, were **Repnotch**
and **Tunetory**.

### The rename

**N13. `songbook.dev` remains the UUID root namespace, permanently. Do not "finish the rename"
here.** Decision 4a fixes the UUID v5 root as `uuid5(DNS, "songbook.dev")`. That string survives
the rename in four places, deliberately:

- `shared/src/commonMain/kotlin/dev/repertaurus/core/Uuid.kt`
- `tools/import/extract.py`
- `Notes/decisions/data-model.md`
- `shared/src/commonMain/sqldelight/README.md`

It is an **opaque derivation seed, not a brand**. Changing it re-derives every id in the
database — 479 songs, 826 practice events and every junction row — and **nothing would fail
loudly**: the build would pass, the tests would pass, and the ids would simply be different from
every id already written. This is the exact defect class this project has produced twice before,
where a spec was amended on one side only and the fork was silent.

If it is ever changed, that is a **data migration**, not a rename, and both implementations must
change in the same piece of work with a cross-check against real data, per the id-derivation rule
in [CLAUDE.md](../../CLAUDE.md).

**N14. The rename is one piece of work and must land whole.** Scope, verified against the tree:

- `settings.gradle.kts` — `rootProject.name`
- `androidApp/build.gradle.kts` — `namespace`, `applicationId`
- `shared/build.gradle.kts` — `namespace`, and the SQLDelight database name and package
- **29 Kotlin files** declaring `package dev.songbook.*`, and their directory paths
- The 19 `.sq` files, which move by directory only — **zero content change**
- Type names carrying the old brand: `SongbookApp`, `SongbookRepository`, `SongbookDatabase`,
  `SongbookDatabaseTest`
- `androidApp/src/main/res/values/strings.xml` — `app_name`
- `androidApp/src/main/res/values/themes.xml` and `AndroidManifest.xml` — `Theme.Songbook`
- The on-device `DATABASE_NAME`, the SharedPreferences file name, the user-facing export
  filename and the import-rejection message strings
- `.scratch/songbook.db`, and the Python migration's output path
- The repository directory and the delivery folder under Dropbox

Two consequences, both accepted:

1. **`applicationId` is immutable once published to Google Play.** Nothing has been published,
   so the change is free now and impossible later.
2. **Changing `applicationId` changes the installed app's data directory**, so the currently
   installed APK's database will not carry over. Harmless: import is destructive and repeatable
   by design, and reloading from the build pass is the intended flow. The new build installs
   *alongside* the old one rather than upgrading it, so uninstall the old one.

`songbook` remains correct in prose describing the **workbook's** history and the working title,
and permanently in the UUID namespace per N13. Do not rename it in either place.

## Follow-ups arising from the rename

- **The repository directory `D:\coding\songbook` was not renamed**, and this is a deferral, not
  an oversight. A live session's working directory sits inside it and Windows will not rename a
  directory in use, so it must be done with the editor closed. Verified safe: no source or
  tooling file hardcodes an absolute repo path — `tools/import/build.py` derives `REPO` from
  `__file__`, and the only absolute path in the tree is `USER_XLSX`, pointing at Dropbox.
- **The Dropbox delivery folder** `D:\Dropbox\Work\Gigs\Songbook\` is outside the repository and
  was not renamed.
- **`DatabaseHolder.kt` contains a literal NUL byte (0x00)** inside a KDoc comment about the
  SQLite file header. Git and ripgrep therefore classify the file as **binary and silently skip
  it** — the first rename sweep missed two brand strings in it for exactly this reason, and any
  future audit relying on grep will skip it too. Escaping it as `\u0000` would make the file
  greppable again. Not done.
- **Two renames are coupled to the `applicationId` change and are not independently safe. Never
  backport either one alone to a build whose `applicationId` has not changed.**
  - **`AppGraph.PREFERENCES`** holds `device_id`, which is written into the `device_id` column of
    all 19 tables and is load-bearing for the decision 11/12 merge total order. Renaming it makes
    the app forget its device id and mint a new one — so one device would look like two to the
    phase 2 sync merge.
  - **`DatabaseFactory.DATABASE_NAME`** has the same coupling and a quieter failure mode. Applied
    alone, `getDatabasePath("repertaurus.db")` resolves to a fresh file in the *same* data
    directory, SQLDelight creates the schema into it, and **the app opens empty with no error**
    while the old `songbook.db` sits beside it, orphaned and invisible. There is no fallback probe
    for a prior filename.

  Both are harmless as landed **only** because `applicationId` changed in the same piece of work,
  orphaning the whole data directory anyway, and because `android:allowBackup="false"` rules out a
  restore path.
- **None of the persistence and UX identifiers changed by the rename is under test.** The tests
  construct the database over an in-memory driver and never reach `DatabaseHolder`, the database
  filename, the preferences file or the import/export round-trip. The gap predates the rename,
  but it means a passing suite is **not** evidence for any of them. One JVM test over
  `DatabaseHolder` with a temp-dir `Context` double — asserting `stageImport` accepts a fixture
  `.db` whatever its filename, and rejects a non-SQLite file — would cover the whole class.
- **`extract.py`'s workbook `creator` property is a determinism field, not only a brand string.**
  It sits in the block pinned so two runs differ only in the zip container's member timestamps.
  Nothing reads it, so there is no functional consequence — but a byte-level "two runs are
  identical" check comparing a review workbook built before the rename against one built after
  will differ for a reason that is not content.
- **`.idea/modules.xml` still names `songbook.iml`.** It is the only residual old-brand string
  outside the sanctioned set. `.idea/` and `*.iml` are both gitignored, and the reference is IDE
  metadata derived from `rootProject.name` that Gradle sync regenerates. Recorded so the next
  completeness audit does not treat it as a miss.
- **Nothing has run on hardware.** The launcher label, theme, export filename and import-rejection
  messages are unverified visually, as is everything tactile.
