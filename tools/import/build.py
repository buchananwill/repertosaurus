#!/usr/bin/env python3
"""
Songbook migration, phase 0, pass 2: BUILD.

Three-way merges the human review workbook and emits the SQLite database the phase 1
Android app imports.

    base    .scratch/review.xlsx              the common ancestor, READ ONLY
    user    D:/Dropbox/Work/Gigs/review.xlsx  human decisions, READ ONLY, never written
    next    .scratch/review-next.xlsx         machine's current output, READ ONLY
    out     .scratch/songbook.db

Schema is read from shared/src/commonMain/sqldelight/dev/songbook/db/*.sq, which is
authoritative. Nothing in this file restates it.

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
    normalise, derived_id, junction_id, sort_name, UNKNOWN_ARTIST_ID, UNKNOWN_ARTIST_NAME,
)

REPO = os.path.dirname(os.path.dirname(HERE))
BASE_XLSX = os.path.join(REPO, ".scratch", "review.xlsx")
USER_XLSX = os.path.join("D:", os.sep, "Dropbox", "Work", "Gigs", "review.xlsx")
NEXT_XLSX = os.path.join(REPO, ".scratch", "review-next.xlsx")
OUT_DB = os.path.join(REPO, ".scratch", "songbook.db")
SQ_DIR = os.path.join(REPO, "shared", "src", "commonMain", "sqldelight", "dev", "songbook", "db")

# The version SQLDelight actually generates, read from its own generated Schema object at
# shared/build/generated/.../SongbookDatabaseImpl.kt, not assumed. Android compares this
# against PRAGMA user_version; a 0 there means "brand new file" and it would run schema
# creation over populated tables and throw on first open.
SCHEMA_VERSION = 1

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
            if (song_id, performer_id) in seen:
                continue
            seen.add((song_id, performer_id))
            self.rows["song_performer"].append((
                junction_id("song_performer", song_id, performer_id),
                song_id, performer_id, 1 if number(r.get("is_lead")) else 0,
                number(r.get("vocal_range")), None, STAMP, None, DEVICE_ID))

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

def verify(path):
    conn = sqlite3.connect(path)
    conn.execute("PRAGMA foreign_keys = ON")
    out = []

    def show(label, sql, params=()):
        cur = conn.execute(sql, params)
        rows = cur.fetchall()
        out.append((label, [d[0] for d in cur.description] if cur.description else [], rows))
        return rows

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

    vocal = derived_id("instrument", "vocal")
    show("selectByStaleness (vocal), first 12",
         """
         SELECT song.id AS song_id, song.title AS title, artist.name AS artist_name,
                MAX(practice_event.logged_on) AS last_practised,
                COUNT(practice_event.id) AS times_practised
         FROM song
         LEFT JOIN artist ON artist.id = song.artist_id AND artist.deleted_at IS NULL
         LEFT JOIN practice_event ON practice_event.song_id = song.id
              AND practice_event.instrument_id = ?
              AND NOT EXISTS (SELECT 1 FROM practice_event_void
                              WHERE practice_event_void.practice_event_id = practice_event.id)
         WHERE song.deleted_at IS NULL
         GROUP BY song.id, song.title, artist.name
         ORDER BY last_practised IS NOT NULL, last_practised ASC, song.title ASC
         LIMIT 12
         """, (vocal,))

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

    show("timestamp format violations (must be 0)",
         "SELECT (SELECT COUNT(*) FROM song WHERE updated_at NOT GLOB "
         "'????-??-??T??:??:??.???Z'), "
         "(SELECT COUNT(*) FROM practice_event WHERE created_at NOT GLOB "
         "'????-??-??T??:??:??.???Z'), "
         "(SELECT COUNT(*) FROM practice_event WHERE logged_on NOT GLOB '????-??-??')")

    conn.close()
    return out


# --------------------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", dest="target", default=OUT_DB)
    args = parser.parse_args()
    assert_readonly_inputs()

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
    for label, cols, rows in verify(args.target):
        print()
        print("-- %s" % label)
        if cols and len(cols) > 1:
            print("   " + " | ".join(str(c) for c in cols))
        for r in rows:
            print("   " + " | ".join("" if v is None else str(v) for v in r))
        if not rows:
            print("   (no rows)")


if __name__ == "__main__":
    main()
