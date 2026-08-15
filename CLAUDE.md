# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Project Overview

**Songbook** is a repertoire and practice-tracking app for working musicians. It replaces a
57-tab Google Sheet (`Songs 2026`) that the user has maintained since 2022 and which became
too high-friction to keep updated.

The core interaction is **tap-to-log**: recording "I went over this song today" must take one
tap, offline, on a phone, mid-practice. Everything else in the product is subordinate to that.

Target platforms in delivery order: **Android** → **Dropbox sync** → **Desktop** → **Web** →
(optionally) Play Store → (optionally) native iOS.

## Start Here

Read these, in order, before doing anything:

1. [Notes/journal/_index-journal.md](./Notes/journal/_index-journal.md) — the leadership thread.
   Read the most recent entry first; it states where we are and what is in flight.
2. [Notes/decisions/data-model.md](./Notes/decisions/data-model.md) — the schema contract.
3. [Notes/platform/stack-and-delivery.md](./Notes/platform/stack-and-delivery.md) — what we
   build on and in what order.

The journal is the load-bearing document. It exists so a session with no prior context can
resume the thread without re-deriving it from scratch.

## Core Rules

**DO NOT use the `AskUserQuestion` tool.** The user drives the design trajectory and the
multiple-choice interview flow obstructs it. When something is genuinely ambiguous, ask in
plain prose inline, keep it brief, and otherwise proceed with a sensible default and state the
assumption so the user can correct it in passing.

**DO NOT ADD BOM TO FILES.**

**Do not invent product decisions.** Every decision of consequence is recorded in
`Notes/decisions/`. If the answer is not there, it has not been decided — ask, or state the
assumption explicitly in the journal.

## Operating Model

The main session acts as **senior engineer**: it owns the design thread, the journal, the
decision specs, and the dispatch of work. It does **not** write production code.

- **All coding is delegated to sub-agents.** Give each agent a scoped brief that names the
  files it owns, the contract it implements, and what "done" means.
- **All review is delegated to sub-agents**, and to a *different* agent than the one that wrote
  the code.
- The senior session writes documentation, specs, and journal entries directly. That is
  leadership work, not implementation.

### Agent tool scoping — read before dispatching

A general-purpose agent inherits **every** tool in the session, including browser automation.
An agent told to fetch something will improvise a route if the one you named does not
immediately work, and improvising against the user's authenticated accounts is not acceptable.
This has already happened once: an agent briefed to use the Google Drive MCP reached for
Playwright against the user's Google session on its own initiative.

Rules:

1. **Name the sanctioned route and forbid improvisation explicitly.** "Use tool X; if X fails,
   STOP and report" is not sufficient — say *and do not attempt any other route, including
   browser automation*.
2. **Never let an agent drive a browser against the user's logged-in accounts.** Credentialed
   access goes through an MCP tool the user has connected deliberately, or it does not happen.
3. **Prefer a narrow agent type over `general-purpose`** when the work does not need write
   access or the full toolset. `Explore` is read-only.
4. **Acquire external data yourself, at the top level, before dispatching.** Hand the agent a
   local file path. An agent that has the data has no reason to go looking for it.

The Songs 2026 workbook is already downloaded to `.scratch/Songs2026.xlsx` (gitignored). Import
agents read that file. They do not fetch it.

### Journalling

Append to the journal at the end of any session that changes the design, lands code, or
resolves an open question. One file per session, named `YYYY-MM-DD-session-NN.md`.

Each entry must carry, at minimum:

- **Where we are** — the state a fresh context needs to resume.
- **Decisions made this session**, and the reasoning, especially where a prior decision was
  reversed.
- **Open questions** — what is genuinely undecided, phrased so it can be picked up cold.
- **In flight** — work dispatched but not landed.

Reversals matter more than confirmations. Several early decisions in this project have already
been overturned (Postgres → SQLite, PWA → native, Flutter → Kotlin Multiplatform); record why,
so a future session does not relitigate settled ground or resurrect a rejected option.

## Documentation Conventions

`Notes/` follows the front-matter and indexing schema defined in
[Notes/_schema.md](./Notes/_schema.md). Every markdown file carries YAML front matter; every
domain folder carries an `_index-<domain>.md`. Reference files as relative markdown links, not
backticked paths.

## Engineering Conventions

Kotlin throughout. The shared core is Kotlin Multiplatform, Android UI is Compose, the local
store is SQLDelight over SQLite, and the schema lives in real `.sq` files so it reads as SQL.
The one-off migration is throwaway Python in `tools/import/` and never ships.

**Two implementations derive ids — the Kotlin core and the Python migration — and they must
agree byte for byte.** This has forked twice. Both times a decision was amended and only one
side was updated, and neither time did anything fail loudly. When you touch id derivation,
normalisation, or a decision either depends on:

1. Change **both** implementations in the same piece of work.
2. **Cross-check against real data** — recompute the migration's emitted ids with the Kotlin
   implementation and compare, rather than reasoning that they agree.
3. Pin the result in a test using values **lifted from the migration's output**, not from your
   own derivation. A test that agrees with its own author cannot catch this class of bug.
4. Grep for stale comments. A comment beside the implementation outlives the spec paragraph it
   contradicts, and reads as more authoritative.

**minSdk 26 means SQLite 3.19** — no ordered aggregates, window functions or UPSERT. See
[Notes/platform/stack-and-delivery.md](./Notes/platform/stack-and-delivery.md) before writing
anything clever in SQL, and test ordering by inserting rows in the wrong order.

**Verification is the definition of done, not "the code is written."** Every agent brief should
say what must be *demonstrated* — tests passing, a query's real output quoted, a build produced
— and every report should state plainly what could not be verified. Nothing has run on physical
hardware; anything visual or tactile is untested by definition.
