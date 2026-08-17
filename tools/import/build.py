#!/usr/bin/env python3
"""
Repertaurus migration, phase 0, pass 2: BUILD.

Three-way merges the human review workbook and emits the SQLite database the phase 1
Android app imports.

    base    .scratch/review.xlsx              the common ancestor, READ ONLY
    user    D:/Dropbox/Work/Gigs/review.xlsx  human decisions, READ ONLY, never written
    next    .scratch/review-next.xlsx         machine's current output, READ ONLY
    out     .scratch/repertaurus.db

Schema is read from shared/src/commonMain/sqldelight/dev/repertaurus/db/*.sq, which is
authoritative. Nothing in this file restates it — including its VERSION, which is derived
from the migration files and cross-checked against the generated schema (see below).

    python tools/import/build.py

NETWORK ACCESS IS FORBIDDEN. Local files only. This script writes exactly one path.
"""

from __future__ import annotations

import argparse
import collections
import os
import random
import re
import sqlite3
import sys
import unicodedata
import uuid

import openpyxl

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from extract import (  # noqa: E402  (deliberate: one normalise/id implementation, not two)
    normalise, derived_id, junction_id, song_performer_id, sort_name,
    verify_normalisation_vectors, NORMALISATION_VECTORS,
    ANNOTATION_INSTRUMENT, UNKNOWN_ARTIST_ID, UNKNOWN_ARTIST_NAME,
)

REPO = os.path.dirname(os.path.dirname(HERE))
BASE_XLSX = os.path.join(REPO, ".scratch", "review.xlsx")
USER_XLSX = os.path.join("D:", os.sep, "Dropbox", "Work", "Gigs", "review.xlsx")
NEXT_XLSX = os.path.join(REPO, ".scratch", "review-next.xlsx")
OUT_DB = os.path.join(REPO, ".scratch", "repertaurus.db")
SQ_DIR = os.path.join(REPO, "shared", "src", "commonMain", "sqldelight", "dev", "repertaurus", "db")


# --------------------------------------------------------------------------------------
# Schema version
# --------------------------------------------------------------------------------------
#
# This was `SCHEMA_VERSION = 1`, under a comment claiming it was read from the generated
# schema. It was read from nothing. When the schema moved to 2 the literal stayed at 1 and
# the migration went on stamping `user_version = 1` into a file whose content was v2 —
# `saved_view` present, `song_performer.instrument_id` present. Nothing failed, because
# Android's `SchemaCompatibility` decides on content and not on the stamp. The moment a
# `2.sqm` ships, that file is classified `Upgradable` and `1.sqm` is replayed over tables
# that already exist. This is the third cross-implementation fork of this exact shape in
# this project, so the version is now DERIVED, from two independent artefacts, and the two
# must agree or nothing is written:
#
#   1. The checked-in migration files. `shared/build.gradle.kts` sets no explicit version
#      and does not use `deriveSchemaFromMigrations`, so SQLDelight's rule is
#      `version = max(N.sqm) + 1`, and 1 when there are none. Reading the same files by the
#      same rule is not a parallel guess at the version — it IS the rule. It needs no build
#      output, so it is always available and always current with source.
#   2. SQLDelight's generated `RepertaurusDatabaseImpl.kt`. This is the value Android
#      literally compares against `PRAGMA user_version`, so it is the one that matters — but
#      it is a Gradle output and can be absent or stale, which is why it is the cross-check
#      rather than the sole source.
#
# A literal cannot go stale here any more, because there is no literal. Either artefact
# drifting from the other is a FATAL and not a wrong number: bump the schema without
# regenerating, or regenerate without bumping, and this script refuses to run.

MIGRATIONS_DIR = os.path.join(SQ_DIR, "migrations")
GENERATED_ROOT = os.path.join(REPO, "shared", "build", "generated", "sqldelight")
GENERATED_IMPL = "RepertaurusDatabaseImpl.kt"

RE_MIGRATION = re.compile(r"^(\d+)\.sqm$")
# Matches both `override val version: Long\n  get() = N` and `override val version: Long = N`.
RE_GENERATED_VERSION = re.compile(
    r"override\s+val\s+version\s*:\s*Long\s*(?:get\(\)\s*)?=\s*(\d+)")


def migrations_version(directory=MIGRATIONS_DIR):
    """The schema version the checked-in migration files imply: `max(N.sqm) + 1`.

    Contiguity is enforced rather than assumed. SQLDelight numbers migrations from 1 with no
    gaps; a `3.sqm` beside a `1.sqm` would make `max + 1` say 4 while the file count says 3,
    and picking either silently is how this defect happened in the first place.
    """
    if not os.path.isdir(directory):
        sys.exit("FATAL: no migrations directory at %s — the schema version cannot be "
                 "derived, and this script will not guess it." % directory)
    numbers = sorted(
        int(match.group(1))
        for match in (RE_MIGRATION.match(name) for name in os.listdir(directory))
        if match)
    if not numbers:
        return 1
    if numbers != list(range(1, numbers[-1] + 1)):
        sys.exit("FATAL: the migrations in %s are not contiguous from 1: %s. SQLDelight "
                 "numbers them 1..N with no gaps; the version is not derivable from this."
                 % (directory, ", ".join("%d.sqm" % n for n in numbers)))
    return numbers[-1] + 1


def generated_versions(root=GENERATED_ROOT):
    """Every `Schema.version` SQLDelight has generated under `root`, as `(path, version)`.

    Walked rather than addressed by a fixed path: the generated tree carries the source-set
    and package layout of whichever SQLDelight version produced it, and a hardcoded path is
    the same kind of thing this whole section exists to remove. More than one is expected
    across source sets and they must all agree.
    """
    found = []
    for parent, _dirs, files in os.walk(root):
        if GENERATED_IMPL not in files:
            continue
        path = os.path.join(parent, GENERATED_IMPL)
        with open(path, encoding="utf-8") as handle:
            match = RE_GENERATED_VERSION.search(handle.read())
        if match is None:
            sys.exit("FATAL: %s carries no `override val version: Long`. The generated "
                     "layout has changed and RE_GENERATED_VERSION no longer reads it; fix "
                     "the pattern rather than removing the cross-check." % path)
        found.append((path, int(match.group(1))))
    return found


def schema_version(migrations_dir=MIGRATIONS_DIR, generated_root=GENERATED_ROOT):
    """The version to stamp, from the migration files, cross-checked against the generated
    schema. Any disagreement — including the cross-check being unavailable — is fatal."""
    derived = migrations_version(migrations_dir)
    generated = generated_versions(generated_root)
    if not generated:
        sys.exit(
            "FATAL: no %s under %s, so the version the migration files imply (%d) cannot be "
            "cross-checked against the schema Android actually carries. Build the shared "
            "module first (gradlew :shared:assembleDebug) and re-run."
            % (GENERATED_IMPL, generated_root, derived))
    for path, version in generated:
        if version != derived:
            sys.exit(
                "FATAL: schema version fork. The migration files in %s imply version %d "
                "(max(N.sqm) + 1), but %s generates version %d. One side moved and the "
                "other did not: either a .sqm was added without rebuilding, or the schema "
                "changed without a migration. Stamping either number would write a "
                "database whose PRAGMA user_version contradicts its own content."
                % (migrations_dir, derived, path, version))
    return derived


# Derived at import, so every entry point — the build, --check-stamp, anything importing this
# module — sees the same number and fails on the same fork.
SCHEMA_VERSION = schema_version()


def assert_stamped(path, expected=None):
    """`PRAGMA user_version` in `path` must be the version this build's schema is.

    The written stamp is read back off disk rather than trusted, because the failure this
    guards against produced a database that looked entirely healthy: every table right,
    every row right, integrity_check ok, and one integer wrong.
    """
    expected = SCHEMA_VERSION if expected is None else expected
    conn = sqlite3.connect(path)
    try:
        stamped = conn.execute("PRAGMA user_version").fetchone()[0]
    finally:
        conn.close()
    if stamped != expected:
        sys.exit(
            "FATAL: %s is stamped user_version = %d, but this build's schema is version %d. "
            "A file whose stamp contradicts its content is the exact input that makes "
            "Android replay a migration over tables that already exist."
            % (path, stamped, expected))
    return stamped


DEVICE_ID = "migration"
STAMP = "2026-08-16T00:00:00.000Z"   # decision 10's fixed-width form

# Seeded so a re-run reproduces the same file. The ids are still UUIDv4 in form and are
# not derived from content, which is what decision 52 requires of setlist_item.
RANDOM_SEED = 20260816

# Only paths this script may write.
WRITABLE = {os.path.normcase(os.path.abspath(OUT_DB))}


def assert_readonly_inputs():
    """The user's copy and the merge base are inputs. Refuse to run if the output would
    land on either, or anywhere under Dropbox."""
    target = os.path.normcase(os.path.abspath(OUT_DB))
    for guarded in (BASE_XLSX, USER_XLSX, NEXT_XLSX):
        if target == os.path.normcase(os.path.abspath(guarded)):
            sys.exit("FATAL: refusing to write over an input: %s" % guarded)
    if "dropbox" in target.lower():
        sys.exit("FATAL: refusing to write anywhere under Dropbox: %s" % OUT_DB)


# --------------------------------------------------------------------------------------
# Workbook loading
# --------------------------------------------------------------------------------------

def load(path, sheet):
    wb = openpyxl.load_workbook(path, data_only=True, read_only=True)
    if sheet not in wb.sheetnames:
        wb.close()
        return [], []
    rows = list(wb[sheet].iter_rows(values_only=True))
    wb.close()
    if not rows:
        return [], []
    headers = list(rows[0])
    out = [dict(zip(headers, r)) for r in rows[1:] if any(c is not None for c in r)]
    return headers, out


def blank(v):
    return v is None or (isinstance(v, str) and not v.strip())


def text(v):
    return "" if blank(v) else str(v).strip()


def number(v):
    if blank(v):
        return None
    try:
        return int(float(v))
    except (TypeError, ValueError):
        return None


# --------------------------------------------------------------------------------------
# The join
# --------------------------------------------------------------------------------------

def legacy_song_id(artist, title):
    """The id form the user's copy was generated with, before the separator was corrected
    from "|" to "/". The user's copy descends from that generation: 472 of its 482 song
    ids reproduce under this form and none reproduce under the current one. It is used
    ONLY to join their copy back to the base — never to write an id."""
    artist_id = (derived_id("artist", normalise(artist)) if artist
                 else UNKNOWN_ARTIST_ID)
    return derived_id("song", "%s|%s" % (artist_id, normalise(title)))


def current_song_id(artist, title):
    artist_id = (derived_id("artist", normalise(artist)) if artist
                 else UNKNOWN_ARTIST_ID)
    return derived_id("song", "%s/%s" % (artist_id, normalise(title)))


def placeholder_equivalent(value):
    """`Unknown Artist` is the structural placeholder decision 28a requires where the
    workbook names no artist. It carries exactly the information a blank does, so for
    conflict detection the two are the same value — otherwise every song the user
    attributed would register as a spurious conflict against the machine's placeholder
    and bury the real ones."""
    t = text(value)
    return "" if t == UNKNOWN_ARTIST_NAME else t


# --------------------------------------------------------------------------------------
# Merge
# --------------------------------------------------------------------------------------

class Merge(object):

    # Fields a human edits and the build actually reads. Provenance columns and the
    # machine's own flag/note commentary are not merged.
    SONG_FIELDS = ("title", "artist", "tonal_centre", "key_signature", "tonality_note",
                   "tempo_bpm", "duration_seconds", "decade", "loop_length",
                   "chord_count", "chord_pattern", "bass_difficulty", "groove",
                   "keys_patch", "instrument_notes", "mashup_note")

    def __init__(self):
        self.report = collections.Counter()
        self.conflicts = []
        self.notes = []

    def run(self):
        _, base = load(BASE_XLSX, "Songs")
        _, user = load(USER_XLSX, "Songs")
        _, nxt = load(NEXT_XLSX, "Songs")

        by_user = {r["song_id"]: r for r in user}
        by_next = {r["song_id"]: r for r in nxt}

        merged = {}
        user_deleted, machine_deleted, converged = [], [], []

        for b in base:
            u = by_user.get(legacy_song_id(b.get("artist"), b["title"]))
            n = by_next.get(b["song_id"])

            if u is None and n is None:
                converged.append(b["title"])
                self.report["deleted-by-both"] += 1
                continue
            if u is None:
                user_deleted.append((b["title"], text(b.get("artist"))))
                self.report["rule2-user-deleted"] += 1
                continue
            if n is None:
                machine_deleted.append(b["title"])
                self.report["rule3-machine-deleted"] += 1
                continue

            row = dict(n)
            for field in self.SONG_FIELDS:
                bv, uv, nv = b.get(field), u.get(field), n.get(field)
                if field == "artist":
                    bc, uc, nc = (placeholder_equivalent(bv), placeholder_equivalent(uv),
                                  placeholder_equivalent(nv))
                else:
                    bc, uc, nc = text(bv), text(uv), text(nv)

                user_changed = uc != bc
                machine_changed = nc != bc

                if user_changed and machine_changed and uc != nc:
                    # Rule 4: both moved the same field, to different values. Do not pick.
                    self.conflicts.append({
                        "song": b["title"], "field": field,
                        "base": bv, "user": uv, "machine": nv,
                    })
                    self.report["rule4-conflict"] += 1
                    row[field] = uv
                elif user_changed:
                    row[field] = uv
                    self.report["rule1-user-edit"] += 1
                elif machine_changed:
                    self.report["rule3-machine-correction"] += 1

            row["_base_id"] = b["song_id"]
            row["_next_id"] = n["song_id"]
            merged[b["song_id"]] = row

        self.songs = merged
        self.user_deleted = user_deleted
        self.machine_deleted = machine_deleted
        self.converged = converged
        return self

    # -- artists and performers ---------------------------------------------------

    def merge_lookup(self, sheet, key_col, name_cols):
        """Artists and Performers carry the same shape of decision: deletions and
        renames. Both are keyed on a derived id that the rename itself changes, so the
        join is on the BASE id and the rename is read off the name column."""
        _, base = load(BASE_XLSX, sheet)
        _, user = load(USER_XLSX, sheet)
        by_user = {r[key_col]: r for r in user}
        renames, deletions = {}, set()
        for b in base:
            u = by_user.get(b[key_col])
            if u is None:
                deletions.add(text(b[name_cols[0]]))
                self.report["rule2-user-deleted-%s" % sheet.lower()] += 1
                continue
            if text(u[name_cols[0]]) != text(b[name_cols[0]]):
                renames[text(b[name_cols[0]])] = text(u[name_cols[0]])
                self.report["rule1-user-renamed-%s" % sheet.lower()] += 1
        return renames, deletions

    def flag_performer_attribution_conflict(self, renames):
        """The user's copy renames the performer rows `R`, `T` and `p` to Ryan, Tommy and
        Paul. Those rows carried the marks from every cell holding that letter, so the
        rename is the user saying the letter IS that person — and their copy therefore
        attributes those songs. The machine's current state does the opposite: the R/T/P
        expansions were withdrawn as unconfirmed, leaving Ryan, Tommy and Paul with no
        songs at all.

        Both moved, and they moved in opposite directions, so this is rule 4. Rule 4 says
        apply the user's value and flag it — but the user's value here is 118 attributions
        the machine no longer carries anywhere, and re-deriving them would mean reversing
        a ruling I was given directly. So the conflict is recorded in full rather than
        silently resolved either way, and the person who owns both statements decides.
        """
        _, base = load(BASE_XLSX, "Performers")
        _, machine_sp = load(NEXT_XLSX, "SongPerformers")
        _, machine_sip = load(NEXT_XLSX, "SetlistItemPerformers")
        by_name = {text(r["name"]): r for r in base}
        machine_counts = collections.Counter()
        for r in machine_sp:
            machine_counts[text(r.get("performer"))] += 1
        for r in machine_sip:
            machine_counts[text(r.get("performer"))] += 1

        for old, new in sorted(renames.items()):
            if len(old) != 1 or not old.isalpha():
                continue
            row = by_name.get(old)
            if row is None:
                continue
            held = machine_counts.get(new, 0)
            if held:
                # Not a conflict: the machine reached the same reading the user did.
                self.report["convergent-initial-expansion"] += 1
                continue
            self.conflicts.append({
                "song": "(performer identity)",
                "field": "performer %r" % old,
                "base": "%s carried %s marks" % (old, row.get("total_marks")),
                "user": "renamed to %s, so those marks are that person's" % new,
                "machine": "expansion withdrawn; %s has 0 attributions" % new,
            })
            self.report["rule4-conflict"] += 1


# --------------------------------------------------------------------------------------
# Schema
# --------------------------------------------------------------------------------------

QUERY_LABEL = re.compile(r"^[A-Za-z][A-Za-z0-9_]*:\s*$")


def read_query(file_name, label):
    """The body of one SQLDelight named query, lifted from the .sq file.

    [V10, C5] `verify()` used to hold a hand-copied transcription of `selectByStaleness` and
    report the numbers it produced as proof the shipped query works. It agreed on the day it
    was written, which is exactly what the second copy V10 forbids always does. The schema is
    authoritative (see this module's docstring); so is every query in it.

    Named parameters survive as `:name`, which is the form sqlite3 binds from a dict.
    """
    path = os.path.join(SQ_DIR, file_name)
    with open(path, encoding="utf-8") as handle:
        text = handle.read()
    text = re.sub(r"--[^\n]*", "", text)
    match = re.search(r"(?m)^%s:\s*$(.*?);" % re.escape(label), text, re.S)
    if not match:
        sys.exit("FATAL: %s has no query named %r" % (file_name, label))
    return match.group(1).strip()


def read_schema():
    """Extract DDL and seed rows from the .sq files. SQLDelight named queries are
    `label:` followed by a statement; those are application queries, not schema, and are
    skipped. Everything else that is a CREATE or a literal INSERT is schema."""
    statements = []
    for name in sorted(os.listdir(SQ_DIR)):
        if not name.endswith(".sq"):
            continue
        kept, skipping = [], False
        with open(os.path.join(SQ_DIR, name), encoding="utf-8") as handle:
            for line in handle:
                if QUERY_LABEL.match(line):
                    skipping = True
                    continue
                if skipping:
                    if ";" in line:
                        skipping = False
                    continue
                kept.append(line)
        body = "".join(kept)
        body = re.sub(r"--[^\n]*", "", body)
        for chunk in body.split(";"):
            chunk = chunk.strip()
            if not chunk:
                continue
            head = chunk.split(None, 1)[0].upper()
            if head in ("CREATE", "INSERT"):
                statements.append((name, chunk + ";"))
    return statements


RE_CREATE_TABLE = re.compile(r"(?is)^CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?[\"`\[]?(\w+)")


def schema_tables():
    """Every table the .sq files create, in the order they are created."""
    names = []
    for _name, statement in read_schema():
        match = RE_CREATE_TABLE.match(statement)
        if match:
            names.append(match.group(1))
    return names


# Tables the schema creates that this migration deliberately writes no rows into. Each is
# either fully seeded by its own .sq file or, for saved_view, deliberately left empty.
#
# `Build.write`'s insert order is a hardcoded roster, and a roster is the same class of thing
# as the schema-version literal was: the next table to appear would simply be skipped, in
# silence, with a complete-looking database as the result. So the roster is checked against
# the schema, and a table that is in neither list is fatal — adding a table forces a decision
# here rather than defaulting to "not migrated".
NOT_POPULATED = {
    "instrument": "seeded by instrument.sq [D18]",
    "practice_context": "seeded by practice_context.sq",
    "saved_view": "[V28] the migration seeds no views",
}


def assert_roster_covers_schema(order):
    """Every table the schema creates must be either in the insert `order` or in
    NOT_POPULATED with a reason. A table in neither is fatal."""
    unaccounted = [t for t in schema_tables()
                   if t not in order and t not in NOT_POPULATED]
    if unaccounted:
        sys.exit("FATAL: the schema creates %s, which this migration neither populates nor "
                 "names in NOT_POPULATED. Decide which it is; do not let a new table be "
                 "skipped in silence." % ", ".join(unaccounted))


# --------------------------------------------------------------------------------------
# Position keys
# --------------------------------------------------------------------------------------

RE_TRAILING_KEY = re.compile(r"^(.*?)\s*(?:-\s*)?[A-G][#b]?m?$")


def position_key(index):
    """[D54] `position` is a fractional ordering key, not an integer index. Fixed-width
    zero padding makes lexicographic order match intended order, and the stride leaves
    room to allocate between neighbours without touching either."""
    return "a%08d" % ((index + 1) * 4096)


# --------------------------------------------------------------------------------------
# Build
# --------------------------------------------------------------------------------------

class Build(object):

    def __init__(self, merge):
        self.merge = merge
        self.rng = random.Random(RANDOM_SEED)
        self.rows = collections.defaultdict(list)
        self.skipped = collections.Counter()
        self.notes = []

    def random_id(self):
        return str(uuid.UUID(int=self.rng.getrandbits(128), version=4))

    # -- lookups ------------------------------------------------------------------

    def build_artists(self, renames, deletions):
        seen = {}
        for song in self.merge.songs.values():
            name = text(song.get("artist")) or UNKNOWN_ARTIST_NAME
            name = renames.get(name, name)
            if name in deletions:
                continue
            key = normalise(name)
            if key not in seen:
                seen[key] = name
        for key, name in sorted(seen.items()):
            artist_id = derived_id("artist", key)
            if artist_id == UNKNOWN_ARTIST_ID:
                continue          # seeded by artist.sq; never insert it twice
            self.rows["artist"].append((artist_id, name, sort_name(name),
                                        STAMP, None, DEVICE_ID))
        self.artist_ids = {k: derived_id("artist", k) for k in seen}
        self.artist_names = seen

    def build_bands(self):
        _, bands = load(NEXT_XLSX, "Bands")
        for b in bands:
            self.rows["band"].append((b["band_id"], text(b["name"]), None,
                                      STAMP, None, DEVICE_ID))

    def build_performers(self, renames, deletions):
        _, performers = load(NEXT_XLSX, "Performers")
        self.performer_ids = {}
        seen = set()
        for p in performers:
            name = renames.get(text(p["name"]), text(p["name"]))
            if text(p["name"]) in deletions or name in deletions:
                self.skipped["performer-deleted-by-user"] += 1
                continue
            key = normalise(name)
            if not key or key in seen:
                continue
            seen.add(key)
            performer_id = derived_id("performer", key)
            self.performer_ids[key] = performer_id
            self.rows["performer"].append((performer_id, name, None,
                                           STAMP, None, DEVICE_ID))
        # The user's renames also name people the machine no longer emits a row for.
        for old, new in sorted(renames.items()):
            key = normalise(new)
            if key in seen or new in deletions:
                continue
            seen.add(key)
            performer_id = derived_id("performer", key)
            self.performer_ids[key] = performer_id
            self.rows["performer"].append((performer_id, new, None,
                                           STAMP, None, DEVICE_ID))
        self.performer_renames = renames
        self.performer_deletions = deletions

    def build_grooves(self):
        seen = {}
        for song in self.merge.songs.values():
            g = text(song.get("groove"))
            if g:
                seen.setdefault(normalise(g), g)
        for key, name in sorted(seen.items()):
            self.rows["groove"].append((derived_id("groove", key), name,
                                        STAMP, None, DEVICE_ID))
        self.groove_ids = {k: derived_id("groove", k) for k in seen}

    # -- songs --------------------------------------------------------------------

    def build_songs(self, renames, deletions):
        self.song_ids = {}        # next_id -> final song id
        self.song_by_key = {}
        self.songs_by_title = {}
        for base_id, song in sorted(self.merge.songs.items()):
            name = text(song.get("artist")) or UNKNOWN_ARTIST_NAME
            name = renames.get(name, name)
            if name in deletions:
                self.skipped["song-artist-deleted-by-user"] += 1
                continue
            artist_key = normalise(name)
            artist_id = derived_id("artist", artist_key)
            title = text(song["title"])
            song_id = derived_id("song", "%s/%s" % (artist_id, normalise(title)))

            self.song_ids[song["_next_id"]] = song_id
            key = (artist_key, normalise(title))
            self.songs_by_title.setdefault(normalise(title), set()).add(song_id)
            if key in self.song_by_key:
                self.skipped["song-merged-into-existing"] += 1
                continue
            self.song_by_key[key] = song_id

            groove = text(song.get("groove"))
            self.rows["song"].append((
                song_id, title, artist_id, None,
                number(song.get("key_signature")), number(song.get("tonal_centre")),
                text(song.get("tonality_note")) or None,
                number(song.get("tempo_bpm")), number(song.get("duration_seconds")),
                number(song.get("decade")), number(song.get("loop_length")),
                number(song.get("chord_count")),
                text(song.get("chord_pattern")) or None,
                self.groove_ids.get(normalise(groove)) if groove else None,
                text(song.get("mashup_note")) or None, None, None,
                STAMP, None, DEVICE_ID))

            difficulty = number(song.get("bass_difficulty"))
            patch = text(song.get("keys_patch"))
            notes = text(song.get("instrument_notes"))
            if difficulty is not None:
                self.add_song_instrument(song_id, "bass", difficulty, None, None)
            if patch:
                self.add_song_instrument(song_id, "keys", None, patch, None)
            if notes:
                self.add_song_instrument(song_id, "guitar", None, None, notes)

    def add_song_instrument(self, song_id, instrument, difficulty, patch, notes):
        instrument_id = derived_id("instrument", normalise(instrument))
        row_id = junction_id("song_instrument", song_id, instrument_id)
        self.rows["song_instrument"].append((row_id, song_id, instrument_id,
                                             difficulty, patch, notes,
                                             STAMP, None, DEVICE_ID))

    def resolve_song(self, artist, title):
        """Child rows name their song by text, and several gig tabs have no Artist column
        at all — the extract backfilled those songs from the rest of the workbook, so an
        exact (artist, title) match misses. Fall back to the title, but ONLY where it
        names exactly one song: a title shared by two artists is precisely the case where
        guessing would attach a practice session to the wrong song."""
        name = text(artist) or UNKNOWN_ARTIST_NAME
        name = self.artist_renames.get(name, name)
        title_key = normalise(title)

        exact = self.song_by_key.get((normalise(name), title_key))
        if exact is not None:
            return exact

        candidates = self.songs_by_title.get(title_key)
        if candidates and len(candidates) == 1:
            self.skipped["resolved-by-title-alone"] += 1
            return next(iter(candidates))

        stripped = RE_TRAILING_KEY.match(text(title))
        if stripped:
            head = normalise(stripped.group(1))
            candidates = self.songs_by_title.get(head)
            if candidates and len(candidates) == 1:
                self.skipped["resolved-after-stripping-inline-key"] += 1
                return next(iter(candidates))

        if candidates and len(candidates) > 1:
            self.skipped["ambiguous-title-refused"] += 1
        return None

    # -- children -----------------------------------------------------------------

    def build_song_tags(self):
        _, tags = load(NEXT_XLSX, "Tags")
        # `tag` is user-extensible [D15]. tag.sq seeds five; the migration also needs
        # `bass-vox` from rule 29, which is not seeded, so create any tag the review
        # sheet uses that the schema does not already carry.
        for name in sorted({text(t["tag"]) for t in tags if text(t["tag"])}):
            self.rows["tag"].append((derived_id("tag", normalise(name)), name,
                                     STAMP, None, DEVICE_ID))
        seen = set()
        for t in tags:
            song_id = self.resolve_song(t.get("artist"), t.get("song"))
            if song_id is None:
                self.skipped["song_tag-song-gone"] += 1
                continue
            tag_id = derived_id("tag", normalise(t["tag"]))
            if (song_id, tag_id) in seen:
                continue
            seen.add((song_id, tag_id))
            self.rows["song_tag"].append((junction_id("song_tag", song_id, tag_id),
                                          song_id, tag_id, STAMP, None, DEVICE_ID))

    def build_song_performers(self):
        """[V1, V2, V25] song_performer names an instrument now — who does WHAT on a song,
        not merely who sings it. The unique key is (song, performer, instrument) and the id
        is the three-key derivation of [V3]."""
        _, sp = load(NEXT_XLSX, "SongPerformers")
        seen = set()
        for r in sp:
            song_id = self.resolve_song(r.get("artist"), r.get("song"))
            if song_id is None:
                self.skipped["song_performer-song-gone"] += 1
                continue
            performer_id = self.performer_id_for(r.get("performer"))
            if performer_id is None:
                self.skipped["song_performer-performer-deleted"] += 1
                continue
            # [V1] instrument_id is NOT NULL. The review sheet states it per row; an
            # absent value would be a guess written into permanent data, so fail loudly.
            instrument = text(r.get("instrument"))
            if not instrument:
                sys.exit("FATAL: SongPerformers row %r carries no instrument [V1, V25]" % (r,))
            instrument_id = derived_id("instrument", normalise(instrument))
            if (song_id, performer_id, instrument_id) in seen:
                continue
            seen.add((song_id, performer_id, instrument_id))
            self.rows["song_performer"].append((
                song_performer_id(song_id, performer_id, instrument_id),
                song_id, performer_id, instrument_id,
                1 if number(r.get("is_lead")) else 0,
                # [V7] a range is meaningful only on a vocal row.
                number(r.get("vocal_range")) if normalise(instrument) ==
                normalise(ANNOTATION_INSTRUMENT) else None,
                None, STAMP, None, DEVICE_ID))

    def performer_id_for(self, name):
        raw = text(name)
        if not raw:
            return None
        resolved = self.performer_renames.get(raw, raw)
        if raw in self.performer_deletions or resolved in self.performer_deletions:
            return None
        return self.performer_ids.get(normalise(resolved))

    def build_practice_events(self):
        _, events = load(NEXT_XLSX, "PracticeEvents")
        for r in events:
            song_id = self.resolve_song(r.get("artist"), r.get("song"))
            if song_id is None:
                self.skipped["practice_event-song-gone"] += 1
                continue
            instrument = text(r.get("instrument"))
            if not instrument:
                # [R18] Twitch is a context, not a discipline, and the workbook never
                # records which instrument was practised on stream. instrument_id is NOT
                # NULL, and inventing one would be a guess written into history.
                self.skipped["practice_event-twitch-no-instrument"] += 1
                continue
            instrument_id = derived_id("instrument", normalise(instrument))
            context = text(r.get("context"))
            context_id = (derived_id("practice_context", normalise(context))
                          if context else None)
            date = text(r.get("date"))
            if not re.match(r"^\d{4}-\d{2}-\d{2}$", date):
                self.skipped["practice_event-bad-date"] += 1
                continue
            self.rows["practice_event"].append((
                self.random_id(), song_id, date, instrument_id, context_id,
                None, None, STAMP, DEVICE_ID))

    # -- setlists -----------------------------------------------------------------

    def build_setlists(self):
        _, setlists = load(NEXT_XLSX, "Setlists")
        _, sets = load(NEXT_XLSX, "Sets")
        _, items = load(NEXT_XLSX, "SetlistItems")
        _, staged = load(NEXT_XLSX, "SetlistItemPerformers")

        self.setlist_ids = {}
        for s in setlists:
            if text(s.get("purpose")) != "setlist":
                self.skipped["setlist-not-a-setlist"] += 1
                continue
            setlist_id = self.random_id()
            self.setlist_ids[s["worksheet"]] = setlist_id
            performed = text(s.get("performed_on"))
            if not re.match(r"^\d{4}-\d{2}-\d{2}$", performed):
                performed = None       # a bare year is not a date; the CHECK enforces it
            self.rows["setlist"].append((
                setlist_id, text(s["worksheet"]), performed,
                text(s.get("band_id")) or None, None,
                text(s.get("proposed_client")) or None, None,
                STAMP, None, DEVICE_ID))

        self.set_ids = {}
        for s in sets:
            setlist_id = self.setlist_ids.get(s["worksheet"])
            if setlist_id is None:
                continue
            set_no = number(s.get("set_no"))
            if set_no is None or set_no < 1:
                # CHECK (set_no >= 1). Set 0 is the pre-show block; fold it into set 1
                # rather than dropping the songs.
                set_no = 1
            key = (s["worksheet"], set_no)
            if key in self.set_ids:
                continue
            set_id = self.random_id()
            self.set_ids[key] = set_id
            self.rows["setlist_set"].append((set_id, setlist_id, set_no, None,
                                             STAMP, None, DEVICE_ID))

        self.item_ids = {}
        counters = collections.Counter()
        for r in items:
            setlist_id = self.setlist_ids.get(r["worksheet"])
            if setlist_id is None:
                self.skipped["setlist_item-not-a-setlist"] += 1
                continue
            song_id = self.resolve_song(r.get("artist"), r.get("title"))
            if song_id is None:
                self.skipped["setlist_item-song-gone"] += 1
                continue
            set_no = number(r.get("set_no")) or 1
            if set_no < 1:
                set_no = 1
            set_id = self.set_ids.get((r["worksheet"], set_no))
            if set_id is None:
                set_id = self.set_ids.get((r["worksheet"], 1))
            if set_id is None:
                self.skipped["setlist_item-no-set"] += 1
                continue
            item_id = self.random_id()     # [D52] random, minted here
            self.item_ids[(r["worksheet"], number(r.get("row")))] = item_id
            index = counters[set_id]
            counters[set_id] += 1
            transpose = number(r.get("transpose")) or 0
            tempo = number(r.get("tempo_on_tab"))
            self.rows["setlist_item"].append((
                item_id, set_id, song_id, position_key(index), transpose,
                tempo if tempo and tempo > 0 else None, None,
                STAMP, None, DEVICE_ID))

        seen = set()
        for r in staged:
            item_id = self.item_ids.get((r["setlist"], number(r.get("item_row"))))
            if item_id is None:
                self.skipped["setlist_item_performer-item-gone"] += 1
                continue
            performer_id = self.performer_id_for(r.get("performer"))
            if performer_id is None:
                self.skipped["setlist_item_performer-performer-deleted"] += 1
                continue
            if (item_id, performer_id) in seen:
                continue
            seen.add((item_id, performer_id))
            position = number(r.get("position")) or 1
            # [D4] derived from the MINTED item id, per the corrected junction form.
            self.rows["setlist_item_performer"].append((
                junction_id("setlist_item_performer", item_id, performer_id),
                item_id, performer_id, position, STAMP, None, DEVICE_ID))

    # -- write --------------------------------------------------------------------

    def write(self, path):
        if os.path.exists(path):
            os.remove(path)
        conn = sqlite3.connect(path)
        conn.execute("PRAGMA foreign_keys = ON")
        for _, statement in read_schema():
            conn.execute(statement)
        conn.execute("PRAGMA user_version = %d" % SCHEMA_VERSION)

        order = ["artist", "groove", "band", "performer", "venue", "tag",
                 "song", "song_instrument", "song_performer", "song_tag",
                 "practice_event", "practice_event_void",
                 "setlist", "setlist_set", "setlist_item", "setlist_item_performer",
                 "artist_alias"]
        assert_roster_covers_schema(order)
        counts = {}
        for table in order:
            rows = self.rows.get(table, [])
            # Never double-insert a seed row. The .sq files are authoritative about what
            # is already there, so ask the database rather than keeping a second list.
            existing = {r[0] for r in conn.execute("SELECT id FROM %s" % table)}
            if existing:
                before = len(rows)
                rows = [r for r in rows if r[0] not in existing]
                self.skipped["%s-already-seeded" % table] += before - len(rows)
            if not rows:
                counts[table] = 0
                continue
            width = len(rows[0])
            sql = "INSERT INTO %s VALUES (%s)" % (table, ",".join("?" * width))
            try:
                conn.executemany(sql, rows)
            except sqlite3.Error:
                # Name the offending row rather than the batch: a constraint failure here
                # is a data defect and the row is the only useful thing to report.
                for row in rows:
                    try:
                        conn.execute(sql, row)
                    except sqlite3.Error as exc:
                        conn.close()
                        sys.exit("FATAL: %s on %s row %r" % (exc, table, row))
                raise
            counts[table] = len(rows)
        conn.commit()
        conn.close()
        return counts


# --------------------------------------------------------------------------------------
# Verification
# --------------------------------------------------------------------------------------

# The motivating View, as the worked example in views.md states it and as this migration's
# own output measured it: filter (Will, vocal, leadOnly), practice instrument guitar.
#
# These are ASSERTED, not printed. They were printed, under a comment about being believed,
# and nothing checked them — so the one number this whole arc exists to preserve could have
# moved between two runs and the build would still have said BUILD SUCCESSFUL and a wall of
# output nobody diffs. A regression guard that only prints is not a guard.
#
# Lifted from this migration's output, not re-derived by hand. If one of these moves, STOP:
# either the workbook changed, or the query did, and the two have different answers.
MOTIVATING_VIEW_SONGS = 231
MOTIVATING_VIEW_NEVER_ON_GUITAR = 138


def verify(path):
    conn = sqlite3.connect(path)
    conn.execute("PRAGMA foreign_keys = ON")
    out = []
    failures = []

    def show(label, sql, params=()):
        cur = conn.execute(sql, params)
        rows = cur.fetchall()
        out.append((label, [d[0] for d in cur.description] if cur.description else [], rows))
        return rows

    def expect(label, expected, sql, params=()):
        """show(), and then hold the answer to a number. Drift fails the build."""
        rows = show("%s  [expect %s]" % (label, expected), sql, params)
        actual = rows[0][0]
        if actual != expected:
            failures.append("%s: expected %s, got %s" % (label, expected, actual))
        return actual

    show("PRAGMA integrity_check", "PRAGMA integrity_check")
    show("PRAGMA foreign_key_check", "PRAGMA foreign_key_check")
    show("PRAGMA user_version", "PRAGMA user_version")

    tables = [r[0] for r in conn.execute(
        "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")]
    counts = [(t, conn.execute("SELECT COUNT(*) FROM %s" % t).fetchone()[0])
              for t in tables]
    out.append(("row counts", ["table", "rows"], counts))

    show("orphan song.artist_id",
         "SELECT COUNT(*) FROM song LEFT JOIN artist ON artist.id = song.artist_id "
         "WHERE artist.id IS NULL")

    show("CHECK ranges (must all be 0)",
         "SELECT "
         "(SELECT COUNT(*) FROM song WHERE key_signature NOT BETWEEN -7 AND 7), "
         "(SELECT COUNT(*) FROM song WHERE tonal_centre NOT BETWEEN 0 AND 11), "
         "(SELECT COUNT(*) FROM song_instrument WHERE difficulty NOT BETWEEN 1 AND 5), "
         "(SELECT COUNT(*) FROM practice_event WHERE feel NOT BETWEEN 1 AND 3), "
         "(SELECT COUNT(*) FROM setlist_item WHERE LENGTH(position) = 0), "
         "(SELECT COUNT(*) FROM setlist_item_performer WHERE position < 1)")

    # [V10, C5] The shipped query, read out of song.sq at verification time. NOT a copy: a
    # transcription here would agree with the schema on the day it was written and then drift,
    # and this function's whole job is to be believed.
    stale_sql = read_query("song.sq", "selectByStaleness")

    def unfiltered_on(instrument_id):
        return {"practiceInstrumentId": instrument_id, "filterPerformerId": None,
                "filterInstrumentId": None, "leadOnly": 0}

    vocal = derived_id("instrument", "vocal")
    show("selectByStaleness (vocal), first 12",
         "SELECT * FROM (%s) LIMIT 12" % stale_sql, unfiltered_on(vocal))

    show("selectByStaleness (vocal), most-recently-practised 6",
         """
         SELECT song.title, artist.name, MAX(practice_event.logged_on) AS last_practised,
                COUNT(practice_event.id) AS times
         FROM song
         LEFT JOIN artist ON artist.id = song.artist_id
         JOIN practice_event ON practice_event.song_id = song.id
              AND practice_event.instrument_id = ?
         GROUP BY song.id
         ORDER BY last_practised DESC LIMIT 6
         """, (vocal,))

    # Specifically a Will/Coralie item — the duet the review split — not merely any item
    # that happens to carry two performers.
    duet = conn.execute(
        """
        SELECT setlist_item_performer.setlist_item_id
        FROM setlist_item_performer
        JOIN performer ON performer.id = setlist_item_performer.performer_id
        WHERE performer.name IN ('Will', 'Coralie')
        GROUP BY setlist_item_performer.setlist_item_id
        HAVING COUNT(DISTINCT performer.name) = 2
        LIMIT 1
        """).fetchone()
    if duet:
        duet = (None, duet[0])
    if duet:
        show("selectStagedForSetlist — a setlist containing a duet",
             """
             SELECT setlist.name, song.title, performer.name AS performer_name,
                    setlist_item_performer.position
             FROM setlist_item_performer
             JOIN setlist_item ON setlist_item.id = setlist_item_performer.setlist_item_id
                  AND setlist_item.deleted_at IS NULL
             JOIN setlist_set ON setlist_set.id = setlist_item.setlist_set_id
                  AND setlist_set.deleted_at IS NULL
             JOIN setlist ON setlist.id = setlist_set.setlist_id
             JOIN song ON song.id = setlist_item.song_id
             JOIN performer ON performer.id = setlist_item_performer.performer_id
                  AND performer.deleted_at IS NULL
             WHERE setlist_item_performer.setlist_item_id = ?
               AND setlist_item_performer.deleted_at IS NULL
             ORDER BY setlist_item_performer.position, setlist_item_performer.id
             """, (duet[1],))

    # ---- Views [V1-V29] --------------------------------------------------------------
    show("song_performer by instrument and is_lead",
         """
         SELECT instrument.name AS instrument, song_performer.is_lead, COUNT(*) AS rows
         FROM song_performer
         JOIN instrument ON instrument.id = song_performer.instrument_id
         GROUP BY instrument.name, song_performer.is_lead
         ORDER BY instrument.name, song_performer.is_lead
         """)

    show("song_performer by performer, instrument and is_lead",
         """
         SELECT performer.name AS performer, instrument.name AS instrument,
                song_performer.is_lead, COUNT(*) AS rows
         FROM song_performer
         JOIN performer ON performer.id = song_performer.performer_id
         JOIN instrument ON instrument.id = song_performer.instrument_id
         GROUP BY performer.name, instrument.name, song_performer.is_lead
         ORDER BY performer.name, instrument.name, song_performer.is_lead
         """)

    show("saved_view rows (must be 0 — [V28] the migration seeds none)",
         "SELECT COUNT(*) FROM saved_view")

    # [V3, V29] the id is UUIDv5(namespace('song_performer'), song/performer/instrument).
    # Recomputed from the stored foreign keys rather than trusted: a row written with the
    # old two-key form would look fine in every other check. The Kotlin cross-check runs
    # separately, against these same rows.
    stored = conn.execute(
        "SELECT id, song_id, performer_id, instrument_id FROM song_performer").fetchall()
    mismatched = [row[0] for row in stored
                  if row[0] != song_performer_id(row[1], row[2], row[3])]
    out.append(("song_performer ids recomputed from the three-key form [V3]  [expect 0 "
                "mismatched]", ["rows", "mismatched"], [(len(stored), len(mismatched))]))
    if mismatched:
        failures.append(
            "song_performer ids: %d of %d rows do not recompute from their own three keys "
            "[V3, V29]" % (len(mismatched), len(stored)))

    will = derived_id("performer", normalise("Will"))
    vocal = derived_id("instrument", normalise("vocal"))
    guitar = derived_id("instrument", normalise("guitar"))
    motivating = {"practiceInstrumentId": guitar, "filterPerformerId": will,
                  "filterInstrumentId": vocal, "leadOnly": 1}

    expect("selectByStaleness — the motivating View: filter (Will, vocal, leadOnly), "
           "practice instrument guitar, COUNT",
           MOTIVATING_VIEW_SONGS,
           "SELECT COUNT(*) FROM (%s)" % stale_sql, motivating)
    show("selectByStaleness — unfiltered on guitar, COUNT ([V11] all-null is every song)",
         "SELECT COUNT(*) FROM (%s)" % stale_sql, unfiltered_on(guitar))
    show("selectByStaleness — the motivating View, first 12 (coldest first)",
         "SELECT * FROM (%s) LIMIT 12" % stale_sql, motivating)
    show("selectByStaleness — the motivating View, warmest 6",
         "SELECT * FROM (%s) WHERE last_practised IS NOT NULL "
         "ORDER BY last_practised DESC LIMIT 6" % stale_sql, motivating)
    expect("[V12] motivating-View songs never practised on the practice instrument",
           MOTIVATING_VIEW_NEVER_ON_GUITAR,
           "SELECT COUNT(*) FROM (%s) WHERE last_practised IS NULL" % stale_sql,
           motivating)

    show("timestamp format violations (must be 0)",
         "SELECT (SELECT COUNT(*) FROM song WHERE updated_at NOT GLOB "
         "'????-??-??T??:??:??.???Z'), "
         "(SELECT COUNT(*) FROM practice_event WHERE created_at NOT GLOB "
         "'????-??-??T??:??:??.???Z'), "
         "(SELECT COUNT(*) FROM practice_event WHERE logged_on NOT GLOB '????-??-??')")

    conn.close()
    return out, failures


# --------------------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", dest="target", default=OUT_DB)
    parser.add_argument(
        "--check-stamp", dest="check_stamp", metavar="PATH", default=None,
        help="Run the PRAGMA user_version guard against an existing database and exit. "
             "Builds nothing and writes nothing.")
    args = parser.parse_args()

    if args.check_stamp:
        stamped = assert_stamped(args.check_stamp)
        print("%s is stamped user_version = %d, matching this build's schema."
              % (args.check_stamp, stamped))
        return

    assert_readonly_inputs()

    # [D41] Before a single row is derived: this migration and the shared core must normalise
    # identically, or every id it writes forks from the one the app would derive for the same
    # name — silently, permanently, with nothing looking wrong on either screen.
    checked = verify_normalisation_vectors()
    print("normalisation: %d shared vectors agree (%s)"
          % (checked, os.path.relpath(NORMALISATION_VECTORS, REPO)))

    print("schema version %d (from %s, cross-checked against the generated schema)"
          % (SCHEMA_VERSION, MIGRATIONS_DIR))
    print("base : %s" % BASE_XLSX)
    print("user : %s  (read only)" % USER_XLSX)
    print("next : %s" % NEXT_XLSX)
    print()

    merge = Merge().run()
    artist_renames, artist_deletions = merge.merge_lookup(
        "Artists", "artist_id", ("canonical_name", "sort_name"))
    performer_renames, performer_deletions = merge.merge_lookup(
        "Performers", "performer_id", ("name",))
    merge.flag_performer_attribution_conflict(performer_renames)

    build = Build(merge)
    build.artist_renames = artist_renames
    build.build_artists(artist_renames, artist_deletions)
    build.build_bands()
    build.build_performers(performer_renames, performer_deletions)
    build.build_grooves()
    build.build_songs(artist_renames, artist_deletions)
    build.build_song_tags()
    build.build_song_performers()
    build.build_practice_events()
    build.build_setlists()
    counts = build.write(args.target)
    assert_stamped(args.target)

    print("=" * 96)
    print("MERGE")
    print("=" * 96)
    for rule, n in sorted(merge.report.items()):
        print("   %-38s %d" % (rule, n))
    print()
    print("   user deletions (rule 2), %d songs:" % len(merge.user_deleted))
    for title, artist in merge.user_deleted:
        print("        %-44r %s" % (title, artist))
    print("   machine-only deletions (rule 3): %s" % (merge.machine_deleted or "none"))
    print("   deleted by both (convergence): %s" % ", ".join(merge.converged))
    print()
    print("   artist renames: %s" % (artist_renames or "none"))
    print("   artist deletions: %s" % (sorted(artist_deletions) or "none"))
    print("   performer renames: %s" % performer_renames)
    print("   performer deletions: %s" % sorted(performer_deletions))
    print()
    print("   MERGE-CONFLICT rows: %d" % len(merge.conflicts))
    for c in merge.conflicts:
        print("        %-34r %-14s base=%r user=%r machine=%r"
              % (c["song"], c["field"], c["base"], c["user"], c["machine"]))
    print()
    if build.skipped:
        print("   rows not emitted:")
        for reason, n in sorted(build.skipped.items()):
            print("        %-44s %d" % (reason, n))
    print()
    print("wrote %s" % args.target)
    print()
    print("=" * 96)
    print("VERIFICATION")
    print("=" * 96)
    report, failures = verify(args.target)
    for label, cols, rows in report:
        print()
        print("-- %s" % label)
        if cols and len(cols) > 1:
            print("   " + " | ".join(str(c) for c in cols))
        for r in rows:
            print("   " + " | ".join("" if v is None else str(v) for v in r))
        if not rows:
            print("   (no rows)")

    print()
    if failures:
        print("=" * 96)
        for failure in failures:
            print("FATAL: %s" % failure)
        sys.exit(
            "The migration's regression guards moved. Either the workbook changed or a query "
            "did; the two have different answers and only one of them is a build to keep.")
    print("regression guards: all held.")


if __name__ == "__main__":
    main()
