# Songbook

A repertoire and practice-tracking app for working musicians.

Replaces a 57-tab Google Sheet maintained since 2022, which became too high-friction to keep
updated — practice logging in it fell from 605 entries in 2022 to 13 in 2025.

The core interaction is **tap-to-log**: recording "I went over this song today" takes one tap,
offline, on a phone, mid-practice. Everything else is subordinate to that.

## Status

**Phase 1 is functionally complete on Android.** Tap-to-log with undo, `feel` on long-press,
coldest/hottest sort, search, add-song with a near-match artist type-ahead, instrument
management behind a drawer, and import/export. 19 tables, 124 tests.

The migration out of the old workbook runs end to end: 479 songs, 826 practice events and 54
set lists, into a database the app imports.

Next is phase 2, sync through the user's own Dropbox. See
[Notes/journal](Notes/journal/_index-journal.md) for the current state — the most recent entry
is always the place to start.

## Stack

Kotlin Multiplatform shared core · Compose Multiplatform on Android and desktop · React on the
web · SQLDelight over SQLite · sync through the user's own Dropbox, with no server and no
accounts.

## Documentation

Start at [Notes/_index.md](Notes/_index.md). Conventions in
[Notes/_schema.md](Notes/_schema.md); working agreements in [CLAUDE.md](CLAUDE.md).
