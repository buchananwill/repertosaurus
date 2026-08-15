#!/usr/bin/env python3
"""
Songbook migration, phase 0, pass 1: EXTRACT.

Reads the downloaded `Songs 2026` workbook and writes a human review workbook with one
sheet per concern and a FLAG/NOTE column on everything that needs a human decision.

Contract: Notes/migration/import-songs-2026.md  (rules cited as [Rn] throughout)
Schema:   Notes/decisions/data-model.md         (decisions cited as [Dn])

This is throwaway tooling. It never ships in the app. It prefers loud failure and
explicit flags over cleverness. It is idempotent: two runs over the same input produce
the same output workbook (no timestamps, no random ids, all collections sorted).

    python tools/import/extract.py
    python tools/import/extract.py --in <path.xlsx> --out <path.xlsx>

NETWORK ACCESS IS FORBIDDEN. This script reads one local file and writes one local file.
"""

from __future__ import annotations

import argparse
import collections
import datetime
import os
import re
import sys
import unicodedata
import uuid

import openpyxl
from openpyxl.utils import get_column_letter

DEFAULT_IN = os.path.join(".scratch", "Songs2026.xlsx")
DEFAULT_OUT = os.path.join(".scratch", "review.xlsx")


# --------------------------------------------------------------------------------------
# Expected counts, quoted from the migration spec. NEVER tune the parser to these.
# A mismatch is a finding, not a bug to be papered over.
# --------------------------------------------------------------------------------------

EXPECTED = [
    # (section, metric, expected, spec reference)
    ("workbook", "worksheets", 57, "spec header"),
    ("workbook", "rows on export", 4049, "spec header"),
    ("artists", "distinct artist strings", 288, "R1"),
    ("artists", "artists after normalisation", 278, "R1"),
    ("artists", "known collision groups", 7, "R3"),
    ("artists", "SET 1 / SET 2 cells in Artist column", 9, "R4"),
    ("songs", "distinct songs", 460, "R5"),
    ("songs", "song row instances", 2886, "R5"),
    ("keys", "distinct Key values", 58, "R7"),
    ("keys", "plain key names", 2121, "R7"),
    ("keys", "transpositions key-first", 20, "R7"),
    ("keys", "transpositions offset-first", 3, "R7"),
    ("keys", "deliberately-original markers", 2, "R7"),
    ("keys", "unknowns", 15, "R7"),
    ("keys", "non-keys", 44, "R7"),
    ("keys", "suspect spelling Bbb", 2, "R12"),
    ("keys", "suspect spelling Cb", 7, "R12"),
    ("keys", "suspect spelling Fb", 1, "R12"),
    ("practice", "unique (song, discipline, date)", 879, "R19"),
    ("practice", "unique events 2021", 16, "R19"),
    ("practice", "unique events 2022", 605, "R19"),
    ("practice", "unique events 2023", 149, "R19"),
    ("practice", "unique events 2024", 73, "R19"),
    ("practice", "unique events 2025", 13, "R19"),
    ("practice", "unique events 2026", 18, "R19"),
    ("practice", "unparseable practice-date cells", 50, "R20"),
    ("practice", "date typos landing in 2002", 6, "R20"),
    ("practice", "date typos landing in 2099", 10, "R20"),
]


# --------------------------------------------------------------------------------------
# Normalisation [D17] and derived ids [D2, D4, D4a, D4b]
# --------------------------------------------------------------------------------------

def normalise(value) -> str:
    """lowercase, trim, strip leading 'The ', fold '&' to 'and', strip punctuation,
    collapse whitespace.  [D17]  Must match the shared-core implementation exactly."""
    s = unicodedata.normalize("NFKC", "" if value is None else str(value))
    s = s.strip().lower()
    s = s.replace("&", " and ")
    if s.startswith("the "):
        s = s[4:]
    s = re.sub(r"[^\w\s]", "", s, flags=re.UNICODE)
    s = re.sub(r"\s+", " ", s).strip()
    return s


ROOT_NS = uuid.uuid5(uuid.NAMESPACE_DNS, "songbook.dev")  # 3ce0f1dc-... [D4a]


def table_ns(table: str) -> uuid.UUID:
    return uuid.uuid5(ROOT_NS, table)  # per-table, never a shared root [D4b]


def derived_id(table: str, canonical_key: str) -> str:
    return str(uuid.uuid5(table_ns(table), canonical_key))


def sort_name(name: str) -> str:
    """'The Kaiser Chiefs' -> 'Kaiser Chiefs, The'  [D21]"""
    for article in ("The ", "A ", "An "):
        if name.startswith(article):
            return "%s, %s" % (name[len(article):], article.strip())
    return name


# --------------------------------------------------------------------------------------
# Keys [R7-R12, D32-D40]
# --------------------------------------------------------------------------------------

PITCH_CLASS = {
    "C": 0, "B#": 0, "C#": 1, "Db": 1, "D": 2, "D#": 3, "Eb": 3, "E": 4, "Fb": 4,
    "E#": 5, "F": 5, "F#": 6, "Gb": 6, "G": 7, "G#": 8, "Ab": 8, "A": 9, "Bbb": 9,
    "A#": 10, "Bb": 10, "B": 11, "Cb": 11,
}

# Dominant diatonic key signature as a signed sharp/flat count [D32].
# Arithmetic, not analysis [R9]: every named key implies one.
MAJOR_SIG = {
    "C": 0, "G": 1, "D": 2, "A": 3, "E": 4, "B": 5, "F#": 6, "C#": 7,
    "F": -1, "Bb": -2, "Eb": -3, "Ab": -4, "Db": -5, "Gb": -6, "Cb": -7,
}
MINOR_SIG = {
    "A": 0, "E": 1, "B": 2, "F#": 3, "C#": 4, "G#": 5, "D#": 6, "A#": 7,
    "D": -1, "G": -2, "C": -3, "F": -4, "Bb": -5, "Eb": -6, "Ab": -7,
}

# [R12] spellings the workbook uses that a human should confirm rather than the script fix.
SUSPECT_SPELLINGS = {"Bbb", "Cb", "Fb"}

UNKNOWN_KEYS = {"???", "??", "?", "E?"}

RE_PLAIN = re.compile(r"^([A-Ga-g])([#b]{1,2})?(m)?$")
RE_KEY_FIRST = re.compile(r"^([A-Ga-g][#b]{0,2}m?)\s*\(\s*([+-]\s*\d+)\s*\)$")
RE_OFFSET_FIRST = re.compile(r"^([+-]\s*\d+)\s*\(\s*([A-Ga-g][#b]{0,2}m?)\s*\)$")
RE_ORIG = re.compile(r"orig", re.IGNORECASE)


class KeyReading(object):
    """The classified reading of one Key cell. Nothing is coerced; everything is labelled."""

    __slots__ = ("raw", "kind", "root", "minor", "tonal_centre", "key_signature",
                 "offset", "flag", "note")

    def __init__(self, raw):
        self.raw = raw
        self.kind = "nonkey"
        self.root = None
        self.minor = False
        self.tonal_centre = None
        self.key_signature = None
        self.offset = None
        self.flag = ""
        self.note = ""


def _read_named_key(text, reading):
    """Fill root/minor/tonal_centre/key_signature from a plain key name. Returns True on
    success. Leaves key_signature None where the spelling is not a real key [R10, D37]."""
    m = RE_PLAIN.match(text)
    if not m:
        return False
    letter, accidental, minor = m.group(1).upper(), m.group(2) or "", bool(m.group(3))
    root = letter + accidental
    reading.root = root
    reading.minor = minor
    reading.tonal_centre = PITCH_CLASS.get(root)
    table = MINOR_SIG if minor else MAJOR_SIG
    reading.key_signature = table.get(root)
    if root in SUSPECT_SPELLINGS:
        reading.flag = "SUSPECT-KEY-SPELLING"
        reading.note = ("%s is not a key the workbook should contain [R12]; "
                        "pitch class proposed, key_signature left NULL" % root)
        reading.key_signature = None
    elif reading.key_signature is None:
        reading.flag = "KEY-SIGNATURE-UNDERIVABLE"
        reading.note = ("no diatonic signature for %s%s [R10]; tonal_centre kept, "
                        "key_signature NULL" % (root, "m" if minor else ""))
    return True


def classify_key(value):
    """Classify one Key cell into exactly one of the five jobs the column does [R7]."""
    reading = KeyReading(value)
    if value is None:
        reading.kind = "empty"
        return reading
    if isinstance(value, datetime.datetime):
        reading.kind = "nonkey"
        reading.flag = "KEY-NOT-A-KEY"
        reading.note = "date in the Key column [R7]; discarded"
        return reading
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        reading.kind = "nonkey"
        reading.flag = "KEY-NOT-A-KEY"
        reading.note = "number in the Key column [R7]; discarded"
        return reading

    text = unicodedata.normalize("NFKC", str(value)).strip()
    if not text:
        reading.kind = "empty"
        return reading

    if text in UNKNOWN_KEYS:
        reading.kind = "unknown"
        reading.note = "workbook records the key as unknown [R7]; tonality_note preserves it"
        return reading

    m = RE_KEY_FIRST.match(text)
    if m:
        reading.kind = "transposition_key_first"
        reading.offset = int(m.group(2).replace(" ", ""))
        _read_named_key(m.group(1), reading)
        reading.flag = "TRANSPOSE-NOTATION-AMBIGUOUS"
        reading.note = ("key-first notation; 'X (-n)' and '-n (X)' contradict each other "
                        "[R7]. Confirm whether %s is the written or the sounding key, and "
                        "whether %+d applies to it. Offset belongs on the setlist_item, not "
                        "the song [R8]." % (m.group(1), reading.offset))
        return reading

    m = RE_OFFSET_FIRST.match(text)
    if m:
        reading.kind = "transposition_offset_first"
        reading.offset = int(m.group(1).replace(" ", ""))
        _read_named_key(m.group(2), reading)
        reading.flag = "TRANSPOSE-NOTATION-AMBIGUOUS"
        reading.note = ("offset-first notation, the reversed form [R7]. Confirm whether %s "
                        "is the written or the sounding key. Offset belongs on the "
                        "setlist_item, not the song [R8]." % m.group(2))
        return reading

    if RE_ORIG.search(text):
        reading.kind = "original"
        reading.offset = 0
        head = RE_ORIG.split(text)[0].strip().strip("(").strip()
        if head:
            _read_named_key(head, reading)
        reading.note = "deliberately played in the original key [R7]; transpose = 0"
        return reading

    if _read_named_key(text, reading):
        reading.kind = "plain"
        return reading

    reading.kind = "nonkey"
    reading.flag = "KEY-NOT-A-KEY"
    reading.note = "not a key name [R7]; discarded, logged for review"
    return reading


# --------------------------------------------------------------------------------------
# Column roles
# --------------------------------------------------------------------------------------

PRACTICE_INSTRUMENTS = (("vocal", "vocal"), ("keys", "keys"),
                        ("guitar", "guitar"), ("bass", "bass"))

# Singer columns [R13]. Key is the lowercased header; value is the performer name with the
# trailing instrument word removed.
PERFORMER_COLUMNS = {
    "coralie vox": ("Coralie", 0),
    "carla vox": ("Carla", 0),
    "sophie-mae vocal": ("Sophie-Mae", 1),
    "will vocal": ("Will", 1),
    "will plays": ("Will", 0),
    "will lv?": ("Will", 1),
    "andy": ("Andy", 1),
    "coralie": ("Coralie", 0),
    "lead vocal": ("Will", 1),   # the workbook owner's bare column [R13]
}

# Free-text performer annotation columns [R16]. Value is the is_lead the column implies.
ANNOTATION_COLUMNS = {
    "lv": 1,          # 'LV' is literally 'lead vocal'
    "demo 2022": 0,   # who recorded the demo, which is not a claim about the lead
}

# Marks that mean 'yes, this performer' rather than naming somebody else.
CAPABILITY_MARKS = {"x", "y", "s", "p", "*", "?", "1", "goal", "mp3", "k", "r"}

# Tag columns [D19]. Header -> seeded tag name.
TAG_COLUMNS = {
    "party": "party",
    "christmas": "christmas",
    "c&w duets": "cw-duet",
    "target": "target",
    "need to learn": "need-to-learn",
}

# Columns the spec deliberately does not model. Silently skipped, not logged as unresolved.
TRIAGE_COLUMNS = {
    "confidence", "fit", "sum", "score", "priority", "inclusion", "gtr triage",
    "vox triage", "w", "r", "a", "overall", "num", "no.", "current level", "suggested",
    "request", "requests", "learn", "ignore", "four-piece", "vocal level", "rehearsal",
    "sing", "play", "set", "position", "order", "num.", "change", "+/-",
}

SET_DIVIDER = re.compile(
    r"^\s*(set\s*\d+|first\s*dance|extras?|encore)\s*(/\s*extras?)?\s*:?\s*$",
    re.IGNORECASE)

# One tab ('1-6-23') writes the key inside the title cell — 'Valerie Ab', 'Lovely Day E',
# 'Brown Eyed Girl - G'. Recognised so the tab is readable at all, and always flagged.
RE_INLINE_KEY = re.compile(r"^(.*?)\s*(?:-\s*)?([A-G][#b]?m?)$")


def strip_inline_key(title):
    """Return (clean_title, inline_key or ''). Only splits when the remainder still looks
    like a title, so 'Kiss' and 'Africa' are left alone."""
    if not isinstance(title, str):
        return title, ""
    m = RE_INLINE_KEY.match(title.strip())
    if not m:
        return title.strip(), ""
    head, key = m.group(1).strip(), m.group(2)
    if len(head) < 3 or " " not in head:
        return title.strip(), ""
    return head, key

NON_SETLIST_TABS = {
    "Everything": "master song list, source for song facts [R28]",
    "Sheet2": "junk, skipped [R28]",
    "Bass-vox Rep": "capability list, imported as the bass-vox tag [R28, R29]",
}


def read_sheet(ws):
    """Materialise a worksheet as (headers, rows). Row 1 is the header row in every tab."""
    grid = [[c.value for c in row] for row in ws.iter_rows()]
    if not grid:
        return [], []
    width = max(len(r) for r in grid)
    grid = [r + [None] * (width - len(r)) for r in grid]
    headers = ["" if v is None else str(v).strip() for v in grid[0]]
    rows = [r for r in grid[1:]]
    return headers, rows


def cell_ref(col_index, row_index):
    """col_index and row_index are 0-based into (headers, rows); row 1 is the header."""
    return "%s%d" % (get_column_letter(col_index + 1), row_index + 2)


def is_blank(v):
    return v is None or str(v).strip() == ""


# --------------------------------------------------------------------------------------
# Worksheet-name parsing [R21, R22, R23, R27]
# --------------------------------------------------------------------------------------

RE_TAB_DATE = re.compile(r"(?<!\d)(\d{1,2})-(\d{1,2})-(\d{2})(?!\d)")
RE_TAB_YEAR = re.compile(r"(?<!\d)(20\d{2})(?!\d)")

# [R23] User-confirmed. Seed data, not guesses. Do not extend this table by inference.
CONFIRMED_CLASSIFICATION = {
    "blue lion": "band",
    "radiant lanterns": "band",
    "lpt with ryan": "band",
    "chigwell school": "gig/client",
    "st lawrence": "gig/client",
    "jukefest": "gig/client",
    "50 shades of grant": "gig/client",
}

CONFIGURATION_WORDS = {
    "acoustic", "acoustic guitar", "easier", "70s-disco-oriented", "70s-disco-orie",
    "filtered", "four-piece",
}

RE_PERSON_SHAPED = re.compile(
    r"\b(mr|mrs|ms|miss|dr)\b|&|\band\b", re.IGNORECASE)


def parse_tab_date(name):
    """The date is the only safely parseable part of a worksheet name [R22]."""
    m = RE_TAB_DATE.search(name)
    if m:
        day, month, year = int(m.group(1)), int(m.group(2)), int(m.group(3))
        try:
            return (datetime.date(2000 + year, month, day).isoformat(),
                    m.span(), "D-M-YY", "")
        except ValueError:
            return (None, m.span(), "D-M-YY",
                    "date fragment %r is not a valid date [R22]" % m.group(0))
    m = RE_TAB_YEAR.search(name)
    if m:
        # A bare year is all the workbook knows. Do NOT invent a January the first.
        return (m.group(1), m.span(), "year-only",
                "worksheet name gives a year only [R22]; day and month are unrecoverable, "
                "so performed_on holds the year and nothing more")
    return (None, None, "none", "no date in the worksheet name [R22]")


def classify_remainder(remainder):
    """Propose a classification with a confidence. NEVER decide [R23, R24, R25, R26]."""
    text = remainder.strip(" -_,").strip()
    if not text:
        return "", "", "", ""
    key = normalise(text)

    if key in CONFIRMED_CLASSIFICATION:
        return CONFIRMED_CLASSIFICATION[key], "confirmed", "", ""

    # A confirmed name plus a trailing qualifier, e.g. 'Blue Lion ACOUSTIC'.
    for confirmed, kind in sorted(CONFIRMED_CLASSIFICATION.items()):
        if key.startswith(confirmed + " ") or key == confirmed:
            qualifier = text[len(confirmed):].strip() if len(text) > len(confirmed) else ""
            return (kind + " + configuration", "confirmed-with-qualifier", qualifier, "")

    if key in {normalise(w) for w in CONFIGURATION_WORDS}:
        return "configuration", "high", "", ""

    if key.startswith("copy of"):
        return "duplicate tab", "high", "", ""

    if RE_PERSON_SHAPED.search(text):
        return "client", "medium", "", text

    return "unknown", "low", "", ""


# --------------------------------------------------------------------------------------
# Extraction
# --------------------------------------------------------------------------------------

class Extract(object):

    def __init__(self, wb):
        self.wb = wb
        self.unresolved = []          # worksheet, cell, raw, reason
        self.artist_cells = collections.Counter()      # raw string -> count
        self.artist_divider_cells = collections.Counter()
        self.song_rows = []           # one dict per song row instance
        self.key_cells = []           # every Key cell, classified
        self.practice_raw = []        # every practice cell, parsed or not
        self.performer_marks = []     # every performer/annotation cell
        self.tag_marks = []           # every tag cell
        self.setlists = []            # one per worksheet
        self.setlist_items = []
        self.sheet_columns = {}       # sheet -> resolved roles
        self.master_titles = set()
        self.master_artists = set()
        self.text_first_columns = set()  # sheets whose unheaded column A holds text
        self.master_facts = {}        # normalised (artist, title) -> facts from 'Everything'
        self.tab_facts = collections.defaultdict(list)  # same key -> facts from other tabs

    # -- unresolved log ------------------------------------------------------------

    def unresolve(self, sheet, ref, raw, reason):
        self.unresolved.append({
            "worksheet": sheet,
            "cell": ref,
            "raw_value": "" if raw is None else str(raw),
            "reason": reason,
        })

    # -- pass 1: master tab --------------------------------------------------------

    def load_master(self):
        ws = self.wb["Everything"]
        headers, rows = read_sheet(ws)
        lower = [h.lower() for h in headers]
        ai, ti = lower.index("artist"), lower.index("title")
        for row in rows:
            if is_blank(row[ti]):
                continue
            self.master_titles.add(normalise(row[ti]))
            if not is_blank(row[ai]):
                self.master_artists.add(normalise(row[ai]))

    def clean_title(self, raw):
        """Split an inline key off a title, but only where the split is demonstrably
        right: the raw string names no song the master tab knows and the stripped string
        names one. 'Valerie Ab' becomes ('Valerie', 'Ab'); 'Vitamin C' is left alone."""
        text = str(raw).strip()
        if normalise(text) in self.master_titles:
            return text, ""
        head, key = strip_inline_key(text)
        if key and normalise(head) in self.master_titles:
            return head, key
        return text, ""

    # -- column resolution ---------------------------------------------------------

    def resolve_columns(self, sheet_name, headers, rows):
        """Locate the artist and title columns.

        Most tabs name them. Several do not: the gig or client name is written into the
        header cell of the Title column instead [R21, R26], and the Artist column is left
        unheaded. Where a header is missing we identify the column by content, matching
        against the master tab's title and artist vocabularies.
        """
        lower = [h.lower() for h in headers]
        roles = {"title": None, "artist": None, "in_sheet_header": "",
                 "title_inferred": False, "artist_inferred": False}

        if "title" in lower:
            roles["title"] = lower.index("title")
        if "artist" in lower:
            roles["artist"] = lower.index("artist")

        def best_match(vocabulary, exclude):
            best, best_hits = None, 0
            for ci in range(len(headers)):
                if ci in exclude:
                    continue
                hits = 0
                for row in rows:
                    v = row[ci]
                    if not isinstance(v, str):
                        continue
                    if normalise(v) in vocabulary:
                        hits += 1
                    elif normalise(strip_inline_key(v)[0]) in vocabulary:
                        hits += 1
                if hits > best_hits:
                    best, best_hits = ci, hits
            return (best, best_hits) if best_hits >= 5 else (None, best_hits)

        if roles["title"] is None:
            ci, hits = best_match(self.master_titles, set())
            if ci is not None:
                roles["title"] = ci
                roles["title_inferred"] = True
                roles["in_sheet_header"] = headers[ci]

        if roles["artist"] is None:
            exclude = {roles["title"]} if roles["title"] is not None else set()
            ci, hits = best_match(self.master_artists, exclude)
            if ci is not None:
                roles["artist"] = ci
                roles["artist_inferred"] = True

        return roles

    # -- pass 2: every sheet -------------------------------------------------------

    def scan(self):
        for sheet_name in self.wb.sheetnames:
            ws = self.wb[sheet_name]
            headers, rows = read_sheet(ws)
            roles = self.resolve_columns(sheet_name, headers, rows)
            self.sheet_columns[sheet_name] = roles
            first = [r[0] for r in rows if not is_blank(r[0])] if rows else []
            if first and sum(1 for v in first if isinstance(v, str)) > len(first) / 2:
                self.text_first_columns.add(sheet_name)
            self.census_keys(sheet_name, headers, rows, roles)
            self.scan_sheet(sheet_name, headers, rows, roles)

    def census_keys(self, sheet_name, headers, rows, roles):
        """[R7] classifies the Key column by counting every cell in it. That census must
        not depend on whether the row's song could be identified, so it runs as its own
        pass over every worksheet."""
        lower = [h.lower() for h in headers]
        key_indices = [ci for ci, h in enumerate(lower) if h == "key"]
        if not key_indices:
            return
        ti, ai = roles["title"], roles["artist"]
        is_master = sheet_name == "Everything"
        current_set = None
        for ri, row in enumerate(rows):
            if all(is_blank(v) for v in row):
                continue
            title = (self.clean_title(row[ti])[0]
                     if ti is not None and not is_blank(row[ti]) else "")
            artist = str(row[ai]).strip() if ai is not None and not is_blank(row[ai]) else ""
            if isinstance(title, str) and SET_DIVIDER.match(title or "|"):
                m = re.search(r"\d+", title)
                if m:
                    current_set = int(m.group(0))
                continue
            if SET_DIVIDER.match(artist or "|"):
                m = re.search(r"\d+", artist)
                if m:
                    current_set = int(m.group(0))
                continue
            self.read_keys(sheet_name, headers, lower, row, ri, title, artist,
                           current_set, is_master)

    def scan_sheet(self, sheet_name, headers, rows, roles):
        lower = [h.lower() for h in headers]
        ti, ai = roles["title"], roles["artist"]
        is_master = sheet_name == "Everything"

        if ti is None:
            if sheet_name not in NON_SETLIST_TABS:
                self.unresolve(sheet_name, "1:1", " | ".join(headers[:8]),
                               "no Title column found by header or by content; "
                               "whole worksheet skipped for songs")
            elif sheet_name == "Sheet2":
                self.unresolve(sheet_name, "A1", "",
                               "Sheet2 is junk and is skipped [R28]")
            return

        current_set = None
        for ri, row in enumerate(rows):
            if all(is_blank(v) for v in row):
                continue

            title_raw = row[ti]
            artist_raw = row[ai] if ai is not None else None

            # Divider rows. SET 1 / SET 2 in the Artist column [R4], and 'Set 1:' in the
            # title column of the tabs whose Title header carries the gig name.
            divider = None
            for candidate, ci in ((artist_raw, ai), (title_raw, ti)):
                if isinstance(candidate, str) and SET_DIVIDER.match(candidate):
                    divider = candidate.strip()
                    if ci == ai:
                        self.artist_divider_cells[candidate.strip()] += 1
                    break
            if divider is not None:
                m = re.search(r"\d+", divider)
                if m:
                    current_set = int(m.group(0))
                continue

            if not is_blank(artist_raw):
                self.artist_cells[str(artist_raw)] += 1

            if is_blank(title_raw):
                continue

            title, inline_key = self.clean_title(title_raw)
            artist = str(artist_raw).strip() if not is_blank(artist_raw) else ""
            nt, na = normalise(title), normalise(artist)

            if inline_key:
                self.unresolve(sheet_name, cell_ref(ti, ri), str(title_raw).strip(),
                               "title cell carries the key inline; split into title %r "
                               "plus key %r — confirm the split" % (title, inline_key))
                self.key_cells.append({
                    "worksheet": sheet_name, "cell": cell_ref(ti, ri),
                    "column": "(inline in Title)", "position": "gig",
                    "title": title, "artist": artist, "raw": inline_key,
                    "kind": classify_key(inline_key).kind,
                    "root": classify_key(inline_key).root or "",
                    "minor": classify_key(inline_key).minor,
                    "tonal_centre": classify_key(inline_key).tonal_centre,
                    "key_signature": classify_key(inline_key).key_signature,
                    "offset": None, "set_no": current_set, "census": False,
                    "flag": "KEY-WAS-INLINE-IN-TITLE",
                    "note": "the key was written into the title cell, not the Key column",
                    "is_master": False,
                })

            self.song_rows.append({
                "worksheet": sheet_name,
                "row": ri + 2,
                "title": title,
                "artist": artist,
                "norm_key": (na, nt),
            })

            facts = self.read_facts(sheet_name, headers, lower, row, ri, ti)
            if is_master:
                self.master_facts[(na, nt)] = facts
            else:
                self.tab_facts[(na, nt)].append(facts)

            # Key cells are censused in their own pass (census_keys), not here.
            self.read_practice(sheet_name, headers, lower, row, ri, title, artist)
            self.read_performers(sheet_name, headers, lower, row, ri, title, artist)
            self.read_tags(sheet_name, headers, lower, row, ri, title, artist)

    def seed_declared_performers(self):
        """[R13] wants one performer per singer column, whether or not the column has any
        marks in it, and it names `Kendra Piper` as such a column. In the actual workbook
        `Carla Vox` is present but empty, and `Kendra Piper` is not a singer column at all
        — it is the header cell of the Title column on four gig tabs, which [R26] reads
        correctly as a person. Both are surfaced, both are flagged."""
        for sheet_name in self.wb.sheetnames:
            ws = self.wb[sheet_name]
            headers, rows = read_sheet(ws)
            for ci, header in enumerate(headers):
                if header.lower() not in PERFORMER_COLUMNS:
                    continue
                if any(not is_blank(r[ci]) for r in rows):
                    continue
                name, is_lead = PERFORMER_COLUMNS[header.lower()]
                self.performer_marks.append({
                    "worksheet": sheet_name, "cell": "%s1" % get_column_letter(ci + 1),
                    "source": "empty column", "column": header, "performer": name,
                    "is_lead": is_lead, "title": "", "artist": "", "raw": "",
                    "flag": "PERFORMER-COLUMN-EMPTY",
                    "note": "[R13] wants a performer per singer column; this column exists "
                            "but holds no marks, so the performer has no songs",
                })

        for record in self.setlists:
            header = record["in_sheet_header"]
            if not header or not re.match(r"^[A-Z][a-z]+ [A-Z][a-z]+$", header.strip()):
                continue
            self.performer_marks.append({
                "worksheet": record["worksheet"], "cell": "in-sheet header",
                "source": "in-sheet header", "column": header, "performer": header,
                "is_lead": 1, "title": "", "artist": "", "raw": header,
                "flag": "PERFORMER-FROM-IN-SHEET-HEADER",
                "note": "[R13] lists %r as a singer column whose cells become "
                        "song_performer rows. In the workbook it is the header cell of "
                        "the Title column on this tab, so it names a person but marks no "
                        "songs [R26]. Confirm whether this is a performer, a client or "
                        "both." % header,
            })

    # -- artist backfill -----------------------------------------------------------

    def resolve_missing_artists(self):
        """Seven gig tabs have no Artist column at all — the gig or client name occupies
        the Title column's header instead [R21, R26]. Their rows still name songs the rest
        of the workbook attributes, so resolve the artist by title rather than minting a
        second, artist-less copy of every song. Only a title that appears nowhere with an
        artist falls through to the seeded 'Unknown Artist' [D28a]. Every resolution is
        flagged.
        """
        by_title = collections.defaultdict(collections.Counter)
        for row in self.song_rows:
            if row["artist"]:
                by_title[normalise(row["title"])][row["artist"]] += 1

        self.backfilled = set()
        self.unattributable = set()

        def resolve(title):
            candidates = by_title.get(normalise(title))
            if not candidates:
                return ""
            return sorted(candidates.items(), key=lambda kv: (-kv[1], kv[0]))[0][0]

        for row in self.song_rows:
            if row["artist"]:
                continue
            artist = resolve(row["title"])
            if artist:
                row["artist"] = artist
                row["artist_backfilled"] = True
                row["norm_key"] = (normalise(artist), normalise(row["title"]))
                self.backfilled.add(row["norm_key"])
            else:
                self.unattributable.add(normalise(row["title"]))
                self.unresolve(row["worksheet"], "row %d" % row["row"], row["title"],
                               "worksheet has no Artist column and this title appears "
                               "with no artist anywhere else [R21, D28a]")

        for bucket in (self.practice_raw, self.tag_marks, self.key_cells,
                       self.performer_marks):
            for record in bucket:
                if record.get("artist"):
                    continue
                artist = resolve(record.get("title", ""))
                if artist:
                    record["artist"] = artist

        for key in [k for k in self.tab_facts if not k[0]]:
            artist = resolve(key[1])
            if artist:
                self.tab_facts[(normalise(artist), key[1])].extend(self.tab_facts.pop(key))

    # -- facts [R6] ----------------------------------------------------------------

    def read_facts(self, sheet_name, headers, lower, row, ri, ti):
        facts = {}
        for ci, h in enumerate(lower):
            v = row[ci]
            if is_blank(v):
                continue
            if h == "tempo":
                if isinstance(v, (int, float)) and not isinstance(v, bool):
                    facts["tempo_bpm"] = int(round(float(v)))
                else:
                    self.unresolve(sheet_name, cell_ref(ci, ri), v,
                                   "non-numeric tempo; discarded")
            elif h == "decade":
                if isinstance(v, (int, float)) and not isinstance(v, bool):
                    year = int(v)
                    if year % 10 == 0 and 1900 <= year <= 2030:
                        facts["decade"] = year
                    else:
                        self.unresolve(sheet_name, cell_ref(ci, ri), v,
                                       "decade column holds %d, which is not a decade "
                                       "[D-not-modelled: song release year]" % year)
                else:
                    self.unresolve(sheet_name, cell_ref(ci, ri), v,
                                   "non-numeric decade; discarded")
            elif h == "loop length":
                if isinstance(v, (int, float)):
                    facts["loop_length"] = int(v)
            elif h == "no. of chords":
                if isinstance(v, (int, float)):
                    facts["chord_count"] = int(v)
                else:
                    self.unresolve(sheet_name, cell_ref(ci, ri), v,
                                   "non-numeric chord count; discarded")
            elif h == "chord pattern":
                facts["chord_pattern"] = str(v).strip()
            elif h == "mashup":
                facts["mashup_note"] = str(v).strip()
            elif h == "feel":
                facts["groove"] = str(v).strip()
            elif h == "bass difficulty":
                mapped = {"a": 1, "b": 2, "c": 3}.get(str(v).strip().lower())
                if mapped:
                    facts["bass_difficulty"] = mapped
                else:
                    self.unresolve(sheet_name, cell_ref(ci, ri), v,
                                   "bass difficulty outside the a/b/c vocabulary [D42]; "
                                   "no mapping asserted")
            elif h == "range":
                mapped = {"h": 1, "l": 0}.get(str(v).strip().lower())
                if mapped is not None:
                    facts["vocal_range"] = mapped
                else:
                    self.unresolve(sheet_name, cell_ref(ci, ri), v,
                                   "Range outside the H/L vocabulary [D27]")
            elif h == "wavestate patches":
                facts["keys_patch"] = str(v).strip()
            elif h in ("bv comments", "guitar comments"):
                facts.setdefault("instrument_notes", []).append("%s: %s" % (h, str(v).strip()))
        return facts

    # -- keys ----------------------------------------------------------------------

    def read_keys(self, sheet_name, headers, lower, row, ri, title, artist,
                  current_set, is_master):
        key_indices = [ci for ci, h in enumerate(lower) if h == "key"]
        # '5-7-24' carries an unheaded gig-key column immediately right of Title.
        if not key_indices:
            return
        for position, ci in enumerate(key_indices):
            v = row[ci]
            if is_blank(v):
                continue
            reading = classify_key(v)
            if reading.kind == "empty":
                continue
            self.key_cells.append({
                "worksheet": sheet_name,
                "cell": cell_ref(ci, ri),
                "column": headers[ci] or "(unheaded)",
                "position": "gig" if position == 0 and len(key_indices) > 1 else
                            ("master-block" if position > 0 else "only"),
                "title": title,
                "artist": artist,
                "raw": "" if v is None else str(v),
                "kind": reading.kind,
                "root": reading.root or "",
                "minor": reading.minor,
                "tonal_centre": reading.tonal_centre,
                "key_signature": reading.key_signature,
                "offset": reading.offset,
                "set_no": current_set,
                "census": True,
                "flag": reading.flag,
                "note": reading.note,
                "is_master": is_master,
            })
            if reading.kind == "nonkey":
                self.unresolve(sheet_name, cell_ref(ci, ri), v,
                               "Key column: %s" % reading.note)

    # -- practice [R17, R18, R19, R20] ---------------------------------------------

    def read_practice(self, sheet_name, headers, lower, row, ri, title, artist):
        for ci, h in enumerate(lower):
            instrument = None
            context = None
            if "practice" in h or "practise" in h:
                for word, name in PRACTICE_INSTRUMENTS:
                    if word in h:
                        instrument = name
                        break
                if instrument is None:
                    self.unresolve(sheet_name, cell_ref(ci, ri), headers[ci],
                                   "practice column names no instrument [R17]")
                    continue
                context = "practice"
            elif h == "twitch":
                instrument = None
                context = "twitch"   # a context, not a discipline [R18]
            else:
                continue

            v = row[ci]
            if is_blank(v):
                continue

            record = {
                "worksheet": sheet_name,
                "cell": cell_ref(ci, ri),
                "column": headers[ci],
                "title": title,
                "artist": artist,
                "instrument": instrument or "",
                "context": context,
                "date": "",
                "raw": str(v),
                "flag": "",
                "note": "",
            }

            if isinstance(v, datetime.datetime):
                if 2015 <= v.year <= 2030:
                    record["date"] = v.date().isoformat()
                else:
                    record["flag"] = "PRACTICE-DATE-IMPLAUSIBLE"
                    record["note"] = ("date lands in %d [R20]; almost certainly a typo or "
                                      "a numeric value in a date-formatted cell" % v.year)
                    self.unresolve(sheet_name, cell_ref(ci, ri), v.isoformat(),
                                   "practice date in year %d [R20]" % v.year)
            elif isinstance(v, datetime.date):
                record["date"] = v.isoformat()
            elif isinstance(v, datetime.time):
                record["flag"] = "PRACTICE-DATE-UNPARSEABLE"
                record["note"] = "cell holds a time, not a date [R20]"
                self.unresolve(sheet_name, cell_ref(ci, ri), str(v),
                               "practice cell holds a time, not a date [R20]")
            else:
                text = str(v).strip()
                parsed = self.parse_loose_date(text)
                if parsed:
                    record["date"] = parsed
                    record["flag"] = "PRACTICE-DATE-INFERRED-YEAR"
                    record["note"] = "%r has no year [R20]; year not asserted" % text
                    record["date"] = ""
                    self.unresolve(sheet_name, cell_ref(ci, ri), text,
                                   "practice date with no year [R20]; refused to guess")
                else:
                    record["flag"] = "PRACTICE-DATE-UNPARSEABLE"
                    record["note"] = "%r is not a date [R20]" % text
                    self.unresolve(sheet_name, cell_ref(ci, ri), text,
                                   "practice cell is not a date [R20]")
            self.practice_raw.append(record)

    @staticmethod
    def parse_loose_date(text):
        """A D/M fragment with no year. Recognised so it can be flagged, never resolved."""
        return bool(re.match(r"^\d{1,2}\s*/\s*\d{1,2}$", text))

    # -- performers [R13-R16] ------------------------------------------------------

    def read_performers(self, sheet_name, headers, lower, row, ri, title, artist):
        for ci, h in enumerate(lower):
            v = row[ci]
            if is_blank(v):
                continue

            if h in PERFORMER_COLUMNS:
                name, is_lead = PERFORMER_COLUMNS[h]
                mark = str(v).strip()
                flag, note = "", ""
                if isinstance(v, datetime.datetime):
                    flag = "PERFORMER-CELL-IS-A-DATE"
                    note = ("singer column holds a date, not a mark [R14]; the "
                            "song_performer row is asserted, the date is not")
                elif mark.lower() not in CAPABILITY_MARKS:
                    # The bare 'Lead vocal' column is the owner's [R13], but its cells
                    # routinely name somebody else. Attribute the cell to the name it
                    # holds, not to the column's owner.
                    self.record_annotation(sheet_name, headers, ci, ri, v, title, artist,
                                           is_lead)
                    continue
                self.performer_marks.append({
                    "worksheet": sheet_name, "cell": cell_ref(ci, ri),
                    "source": "column", "column": headers[ci], "performer": name,
                    "is_lead": is_lead, "title": title, "artist": artist,
                    "raw": mark, "flag": flag, "note": note,
                })
                continue

            if h in ANNOTATION_COLUMNS:
                self.record_annotation(sheet_name, headers, ci, ri, v, title, artist,
                                       ANNOTATION_COLUMNS[h])
            elif h == "" and self.is_annotation_column(sheet_name, ci):
                self.record_annotation(sheet_name, headers, ci, ri, v, title, artist, 1)

    def is_annotation_column(self, sheet_name, ci):
        """The unheaded column A of the gig tabs whose Title header carries the client
        name holds free-text performer annotations: FD, LV, Andy?, Andy, Kita [R16, R35].
        The same position on other tabs holds a row number, so require text content."""
        if ci != 0:
            return False
        roles = self.sheet_columns.get(sheet_name, {})
        if not roles.get("title_inferred") or roles.get("artist") == ci:
            return False
        return sheet_name in self.text_first_columns

    def record_annotation(self, sheet_name, headers, ci, ri, v, title, artist, is_lead):
        text = str(v).strip()
        if not text:
            return
        flags, notes = [], []
        letters = re.sub(r"[^A-Za-z]", "", text)
        if not letters:
            self.unresolve(sheet_name, cell_ref(ci, ri), text,
                           "performer annotation column holds a non-name value [R16]")
            return
        if len(letters) <= 2 and letters.upper() == letters:
            flags.append("ANNOTATION-NOT-OBVIOUSLY-A-NAME")
            notes.append("%r is an initialism or a role code, not obviously a name [R16]; "
                         "confirm before it becomes a performer" % text)
        if re.search(r"[/\d]", text):
            flags.append("ANNOTATION-NOT-A-SINGLE-NAME")
            notes.append("%r contains a separator or a digit [R16]; it may name two "
                         "performers, or none" % text)
        if text.endswith("?"):
            flags.append("ANNOTATION-UNCERTAIN")
            notes.append("%r carries the workbook's own uncertainty [R16]; it must "
                         "collapse to the same performer as %r"
                         % (text, text.rstrip("?")))
        self.performer_marks.append({
            "worksheet": sheet_name, "cell": cell_ref(ci, ri),
            "source": "annotation", "column": headers[ci] or "(unheaded)",
            "performer": text, "is_lead": is_lead, "title": title, "artist": artist,
            "raw": text, "flag": "; ".join(flags), "note": " | ".join(notes),
        })

    # -- tags [D19, R29] -----------------------------------------------------------

    def read_tags(self, sheet_name, headers, lower, row, ri, title, artist):
        for ci, h in enumerate(lower):
            if h not in TAG_COLUMNS:
                continue
            v = row[ci]
            if is_blank(v):
                continue
            flag, note = "", ""
            if isinstance(v, datetime.datetime):
                flag = "TAG-CELL-IS-A-DATE"
                note = ("tag column holds a date, not a mark; the tag is asserted, the "
                        "date is not modelled anywhere [D43]")
            self.tag_marks.append({
                "worksheet": sheet_name, "cell": cell_ref(ci, ri),
                "tag": TAG_COLUMNS[h], "title": title, "artist": artist,
                "source": "column %s" % headers[ci], "raw": str(v).strip(),
                "flag": flag, "note": note,
            })

    # -- setlists [R21-R28, R30-R33] -----------------------------------------------

    def build_setlists(self):
        for sheet_name in self.wb.sheetnames:
            ws = self.wb[sheet_name]
            headers, rows = read_sheet(ws)
            roles = self.sheet_columns[sheet_name]
            record = self.describe_setlist(sheet_name, headers, rows, roles)
            self.setlists.append(record)

    def annotate_setlist_relationships(self):
        """Tabs that share a date, or share an in-sheet client header, are either literal
        duplicates [R28, R35] or alternative sets for one booking [R28]. Which of the two
        is not recoverable from the data, so both get flagged as a group and the human
        decides."""
        by_date = collections.defaultdict(list)
        by_header = collections.defaultdict(list)
        for record in self.setlists:
            if record["purpose"] != "setlist":
                continue
            if record["performed_on"]:
                by_date[record["performed_on"]].append(record["worksheet"])
            if record["in_sheet_header"]:
                by_header[record["in_sheet_header"]].append(record["worksheet"])

        for record in self.setlists:
            extra_flags, extra_notes = [], []
            peers = [w for w in by_date.get(record["performed_on"], [])
                     if w != record["worksheet"]]
            if peers:
                extra_flags.append("SHARES-DATE-WITH-ANOTHER-TAB")
                extra_notes.append("same performed_on as %s [R28]; decide whether these "
                                   "are duplicates to delete or alternative sets for one "
                                   "booking — both must be imported until you do"
                                   % ", ".join(sorted(peers)))
            peers = [w for w in by_header.get(record["in_sheet_header"], [])
                     if w != record["worksheet"]]
            if peers:
                extra_flags.append("SHARES-CLIENT-WITH-ANOTHER-TAB")
                extra_notes.append("same in-sheet client header as %s [R35]; import both, "
                                   "suffix the names, and flag for the user to delete one "
                                   "— which was actually played is not recoverable"
                                   % ", ".join(sorted(peers)))
            if extra_flags:
                record["flag"] = "; ".join(sorted(
                    set([f for f in record["flag"].split("; ") if f] + extra_flags)))
                record["note"] = " | ".join(
                    [n for n in [record["note"]] if n] + extra_notes)

    def describe_setlist(self, sheet_name, headers, rows, roles):
        lower = [h.lower() for h in headers]
        performed_on, span, how, date_note = parse_tab_date(sheet_name)

        remainder = sheet_name
        if span:
            remainder = (sheet_name[:span[0]] + " " + sheet_name[span[1]:])
        remainder = re.sub(r"\s+", " ", remainder).strip()
        remainder = re.sub(r"\s+\d+$", "", remainder).strip()   # 'Copy of 1-7-23 1'

        classification, confidence, qualifier, client = classify_remainder(remainder)

        row_count = sum(1 for r in rows
                        if roles["title"] is not None and not is_blank(r[roles["title"]]))

        flags, notes = [], []
        if date_note:
            notes.append(date_note)

        if how == "year-only":
            flags.append("DATE-YEAR-ONLY")

        purpose = "setlist"
        if sheet_name in NON_SETLIST_TABS:
            purpose = "not a setlist"
            notes.append(NON_SETLIST_TABS[sheet_name])
            flags.append("NOT-A-SETLIST")
            classification, confidence = "", ""

        if normalise(remainder) == "lpt with ryan":
            flags.append("BAND-TAB-PURPOSE-UNRESOLVED")
            notes.append("whether this tab is a performance or a working repertoire for "
                         "the band is unresolved [R28]; imported as a setlist because it "
                         "carries an ordered song list, but flag either way")

        in_sheet_header = roles.get("in_sheet_header", "")
        venue, client_proposal, header_note = self.split_header(in_sheet_header)
        if header_note:
            notes.append(header_note)
        if in_sheet_header:
            flags.append("IN-SHEET-HEADER-UNCLASSIFIED")

        if len(sheet_name) == 31:
            flags.append("WORKSHEET-NAME-TRUNCATED")
            notes.append("worksheet name is exactly 31 characters, Excel's limit [R27]; "
                         "the full name is %s" %
                         ("recoverable from the in-sheet header %r" % in_sheet_header
                          if in_sheet_header else "NOT recoverable from this tab"))

        if remainder.lower().startswith("copy of"):
            flags.append("DUPLICATE-TAB")
            notes.append("literal duplicate tab [R28]; import, suffix, flag for deletion")

        if qualifier:
            flags.append("VARIANT-OF-ANOTHER-TAB")
            notes.append("qualifier %r marks an alternative set for the same booking, not "
                         "a duplicate [R28]; keep the qualifier in setlist.name" % qualifier)

        if confidence in ("low", "medium") and purpose == "setlist":
            flags.append("REMAINDER-UNCLASSIFIED")
            notes.append("worksheet-name remainder %r is a proposal only [R23]; a wrong "
                         "guess silently corrupts band_id and venue_id" % remainder)

        order_kind, order_note, set_numbers = self.classify_order(headers, lower, rows, roles)
        if order_kind in ("degenerate", "ambiguous", "absent"):
            if purpose == "setlist":
                flags.append("ORDER-UNCLASSIFIABLE")
        notes.append(order_note)

        if purpose == "setlist":
            flags.append("SET-TARGET-MINUTES-NO-SOURCE")
            notes.append("no NxM set-length string exists anywhere in the workbook [R30]; "
                         "setlist_set.target_minutes has no source and stays NULL")

        return {
            "worksheet": sheet_name,
            "purpose": purpose,
            "performed_on": performed_on or "",
            "date_source": how,
            "remainder": remainder,
            "proposed_classification": classification,
            "confidence": confidence,
            "proposed_venue": venue,
            "proposed_client": client_proposal or client,
            "in_sheet_header": in_sheet_header,
            "row_count": row_count,
            "sets_seen": ", ".join(str(n) for n in sorted(set_numbers)) if set_numbers else "",
            "order_convention": order_kind,
            "flag": "; ".join(sorted(set(flags))),
            "note": " | ".join(n for n in notes if n),
        }

    @staticmethod
    def split_header(header):
        """In-sheet header rows are mixed: postcode plus client, venue, a person, an act.
        Propose, flag, do not assume [R26]."""
        if not header:
            return "", "", ""
        text = header.strip()
        m = re.match(r"^([A-Z]{1,2}\d{1,2}[A-Z]?\s*\d[A-Z]{2})\s*-\s*(.+)$", text)
        if m:
            return ("", m.group(2).strip(),
                    "in-sheet header %r is a postcode plus a client [R26]; the postcode "
                    "locates a venue that is not otherwise named" % text)
        if " - " in text:
            left, right = [p.strip() for p in text.split(" - ", 1)]
            return (text, "",
                    "in-sheet header %r reads as venue %r in locality %r [R26]; "
                    "organisation-shaped names are plausibly both venue and client [R24]"
                    % (text, left, right))
        if RE_PERSON_SHAPED.search(text):
            return ("", text,
                    "in-sheet header %r is person-shaped, so client only [R24]" % text)
        return ("", text,
                "in-sheet header %r is unclassified [R26]; it may be a venue, a client, "
                "a person or an act" % text)

    @staticmethod
    def classify_order(headers, lower, rows, roles):
        """[R32] Disambiguate the Order column per tab by inspecting its value range.

        Every Order column in this workbook encodes position, never duration. The three
        conventions found are: set*100 + position; a set.position decimal; and a plain
        1..N counter paired with a separate Set column.
        """
        oi = None
        for ci, h in enumerate(lower):
            if h in ("order", "position"):
                oi = ci
                break
        set_col = lower.index("set") if "set" in lower else None
        set_numbers = set()

        if oi is None:
            if set_col is not None:
                for r in rows:
                    v = r[set_col]
                    if isinstance(v, (int, float)):
                        set_numbers.add(int(v))
                return ("set-column-only",
                        "no Order column; the Set column carries set membership and row "
                        "order carries position [R31]", set_numbers)
            return ("absent",
                    "no Order and no Set column; row order is the only ordering signal "
                    "[R31]", set_numbers)

        values = [v for v in (r[oi] for r in rows)
                  if isinstance(v, (int, float)) and not isinstance(v, bool)]
        if not values:
            return ("absent", "Order column is empty [R32]", set_numbers)

        lo, hi = min(values), max(values)
        distinct = len(set(values))

        if distinct <= 2:
            return ("degenerate",
                    "Order holds %d distinct value(s) across %d rows; it carries no "
                    "ordering information [R32]" % (distinct, len(values)), set_numbers)

        if hi <= 10 and lo >= 0:
            for v in values:
                set_numbers.add(int(v))
            return ("decimal set.position",
                    "Order runs %.2f..%.2f; the integer part selects the set and the "
                    "fraction orders within it [R31]" % (lo, hi), set_numbers)

        if lo >= 40 and hi <= 500:
            for v in values:
                set_numbers.add(int(v) // 100)
            return ("hundreds set*100+position",
                    "Order runs %g..%g; the hundreds digit is the set and the remainder "
                    "is the position [R31]. These are NOT durations in seconds — the "
                    "series is contiguous and monotone within each hundred [R32, R33]"
                    % (lo, hi), set_numbers)

        if set_col is not None:
            for r in rows:
                v = r[set_col]
                if isinstance(v, (int, float)):
                    set_numbers.add(int(v))
            return ("plain counter + Set column",
                    "Order runs %g..%g alongside a Set column [R31]" % (lo, hi),
                    set_numbers)

        return ("ambiguous",
                "Order runs %g..%g and matches no known convention; refusing to import "
                "an ordering from it [R32]" % (lo, hi), set_numbers)

    # -- setlist items -------------------------------------------------------------

    def build_setlist_items(self):
        by_sheet = {s["worksheet"]: s for s in self.setlists}
        for sheet_name in self.wb.sheetnames:
            record = by_sheet[sheet_name]
            if record["purpose"] != "setlist":
                continue
            ws = self.wb[sheet_name]
            headers, rows = read_sheet(ws)
            lower = [h.lower() for h in headers]
            roles = self.sheet_columns[sheet_name]
            if roles["title"] is None:
                continue
            ti, ai = roles["title"], roles["artist"]
            oi = next((ci for ci, h in enumerate(lower) if h in ("order", "position")), None)
            si = lower.index("set") if "set" in lower else None
            ki = next((ci for ci, h in enumerate(lower) if h == "key"), None)
            tempo_i = lower.index("tempo") if "tempo" in lower else None
            transpose_i = next((ci for ci, h in enumerate(lower)
                                if h in ("+/-", "change")), None)

            current_set = None
            sequence = 0
            for ri, row in enumerate(rows):
                if all(is_blank(v) for v in row):
                    continue
                for candidate in (row[ai] if ai is not None else None, row[ti]):
                    if isinstance(candidate, str) and SET_DIVIDER.match(candidate):
                        m = re.search(r"\d+", candidate)
                        if m:
                            current_set = int(m.group(0))
                        break
                else:
                    if is_blank(row[ti]):
                        continue
                    sequence += 1
                    order_value = row[oi] if oi is not None else None
                    set_no = current_set
                    position = sequence
                    if isinstance(order_value, (int, float)):
                        if record["order_convention"].startswith("hundreds"):
                            set_no = int(order_value) // 100
                            position = order_value - 100 * set_no
                        elif record["order_convention"].startswith("decimal"):
                            set_no = int(order_value)
                            position = round(order_value - set_no, 4)
                        else:
                            position = order_value
                    if si is not None and isinstance(row[si], (int, float)):
                        set_no = int(row[si])

                    transpose = ""
                    transpose_flag = ""
                    if transpose_i is not None and not is_blank(row[transpose_i]):
                        raw = str(row[transpose_i]).strip()
                        m = re.match(r"^([+-]?\d+(?:\.\d+)?)$", raw)
                        if m:
                            transpose = int(float(m.group(1)))
                            if not raw.startswith(("+", "-")):
                                transpose_flag = "TRANSPOSE-SIGN-UNSTATED"
                        else:
                            transpose_flag = "TRANSPOSE-UNPARSEABLE"
                            self.unresolve(sheet_name, cell_ref(transpose_i, ri), raw,
                                           "transposition column value is not a signed "
                                           "semitone count [R8]")

                    key_reading = classify_key(row[ki]) if ki is not None else None
                    if key_reading is not None and key_reading.offset is not None:
                        if transpose == "":
                            transpose = key_reading.offset
                            transpose_flag = "TRANSPOSE-FROM-KEY-CELL"

                    self.setlist_items.append({
                        "worksheet": sheet_name,
                        "row": ri + 2,
                        "set_no": "" if set_no is None else set_no,
                        "position": position,
                        "title": str(row[ti]).strip(),
                        "artist": str(row[ai]).strip() if ai is not None
                                  and not is_blank(row[ai]) else "",
                        "key_raw": "" if ki is None or is_blank(row[ki])
                                   else str(row[ki]).strip(),
                        "transpose": transpose,
                        "tempo_on_tab": row[tempo_i] if tempo_i is not None
                                        and isinstance(row[tempo_i], (int, float)) else "",
                        "flag": transpose_flag,
                        "note": "",
                    })


# --------------------------------------------------------------------------------------
# Consolidation
# --------------------------------------------------------------------------------------

def fold_artists(artist_cells):
    """[R1, R2, R3] Fold spellings to artists. Canonical name is the most-used spelling;
    every loser is an alias."""
    groups = collections.defaultdict(collections.Counter)
    for raw, count in artist_cells.items():
        groups[normalise(raw)][raw.strip()] += count

    artists = []
    for key in sorted(groups):
        spellings = groups[key]
        canonical = sorted(spellings.items(), key=lambda kv: (-kv[1], kv[0]))[0][0]
        variants = sorted(spellings.items(), key=lambda kv: (-kv[1], kv[0]))
        flags, notes = [], []
        if len(spellings) > 1:
            flags.append("SPELLING-COLLISION")
            notes.append("%d spellings fold to one artist under [D17]; the losers become "
                         "artist_alias rows only if [D22] applies — these collapse under "
                         "normalisation alone, so do NOT populate artist_alias [R2, D22]"
                         % len(spellings))
        if not key:
            flags.append("ARTIST-EMPTY-AFTER-NORMALISATION")
        artists.append({
            "artist_id": derived_id("artist", key),
            "canonical_name": canonical,
            "sort_name": sort_name(canonical),
            "normalised_key": key,
            "spelling_count": len(spellings),
            "total_occurrences": sum(spellings.values()),
            "variants": " | ".join("%s (%d)" % (n, c) for n, c in variants),
            "flag": "; ".join(flags),
            "note": " | ".join(notes),
        })
    return artists


def fold_songs(extract, artists_by_key):
    """[R5, R6] Union all tabs, dedupe, prefer master-tab facts."""
    grouped = collections.defaultdict(lambda: {
        "titles": collections.Counter(), "artists": collections.Counter(),
        "rows": 0, "sheets": set(), "backfilled": 0,
    })
    for row in extract.song_rows:
        g = grouped[row["norm_key"]]
        g["titles"][row["title"]] += 1
        if row["artist"]:
            g["artists"][row["artist"]] += 1
        g["rows"] += 1
        g["sheets"].add(row["worksheet"])
        if row.get("artist_backfilled"):
            g["backfilled"] += 1

    # Key evidence per song: the master tab wins; otherwise the most common reading.
    key_by_song = collections.defaultdict(list)
    for cell in extract.key_cells:
        na, nt = normalise(cell["artist"]), normalise(cell["title"])
        key_by_song[(na, nt)].append(cell)

    songs = []
    for key in sorted(grouped):
        na, nt = key
        g = grouped[key]
        title = sorted(g["titles"].items(), key=lambda kv: (-kv[1], kv[0]))[0][0]
        artist = (sorted(g["artists"].items(), key=lambda kv: (-kv[1], kv[0]))[0][0]
                  if g["artists"] else "")

        flags, notes = [], []
        if not artist:
            flags.append("ARTIST-MISSING")
            notes.append("no artist anywhere in the workbook; attach the seeded "
                         "'Unknown Artist' row rather than a null FK [D28a]")
            artist_id = derived_id("artist", normalise("Unknown Artist"))
        else:
            artist_id = artists_by_key.get(na, "")

        if g["backfilled"]:
            flags.append("ARTIST-BACKFILLED-BY-TITLE")
            notes.append("%d of %d rows for this song came from a tab with no Artist "
                         "column [R21]; the artist was resolved by title from the rest of "
                         "the workbook, not read from those rows" % (g["backfilled"],
                                                                     g["rows"]))

        facts = dict(extract.master_facts.get(key, {}))
        from_master = key in extract.master_facts
        if not from_master:
            merged = collections.defaultdict(collections.Counter)
            for other in extract.tab_facts.get(key, []):
                for name, value in other.items():
                    if isinstance(value, list):
                        continue
                    merged[name][value] += 1
            for name, counter in merged.items():
                facts[name] = sorted(counter.items(), key=lambda kv: (-kv[1], str(kv[0])))[0][0]
            if extract.tab_facts.get(key):
                notes.append("song is absent from the master tab; facts taken by majority "
                             "across %d gig tabs [R6]" % len(extract.tab_facts[key]))

        cells = key_by_song.get(key, [])
        master_cells = [c for c in cells if c["is_master"]]
        named = [c for c in (master_cells or cells)
                 if c["kind"] in ("plain", "original") and c["tonal_centre"] is not None]
        source_spelling = ""
        tonal_centre = ""
        key_signature = ""
        tonality_note = ""
        if named:
            counter = collections.Counter((c["raw"], c["tonal_centre"], c["key_signature"])
                                          for c in named)
            (source_spelling, tonal_centre, key_signature), _ = sorted(
                counter.items(), key=lambda kv: (-kv[1], str(kv[0])))[0]
            spellings = {c["raw"] for c in named}
            if len(spellings) > 1:
                flags.append("KEY-DISAGREEMENT")
                notes.append("the workbook records %d different keys for this song across "
                             "tabs (%s); the most common is used and the rest are on the "
                             "setlist items [R7, R8]"
                             % (len(spellings), ", ".join(sorted(spellings))))
            if key_signature is None:
                key_signature = ""
                flags.append("KEY-SIGNATURE-NULL")
                notes.append("key_signature left NULL [R10, D37]; without it mode is not "
                             "implied and this song's major/minor is unresolved [D35]")
        else:
            unknowns = [c for c in cells if c["kind"] == "unknown"]
            if unknowns:
                tonality_note = "; ".join(sorted({c["raw"] for c in unknowns}))
                flags.append("KEY-UNKNOWN")
                notes.append("the workbook itself records the key as unknown [R7]; an "
                             "honest blank beats a guess")
            elif cells:
                flags.append("KEY-NOT-A-KEY")
                notes.append("every Key cell for this song is a non-key [R7]")
            else:
                flags.append("KEY-ABSENT")

        if tonal_centre != "" and tonal_centre is not None and key_signature != "":
            flags.append("KEY-QUALITY-DERIVED")
            notes.append("key_signature %+d derived from the quality of %r by arithmetic "
                         "[R9, D36]. A bare letter may mean 'we play it in X' rather than "
                         "'X major' [R10] — confirm."
                         % (key_signature, source_spelling))

        songs.append({
            "song_id": derived_id("song", "%s|%s" % (artist_id, nt)),
            "title": title,
            "artist": artist,
            "tonal_centre": "" if tonal_centre is None else tonal_centre,
            "key_signature": "" if key_signature is None else key_signature,
            "source_key_spelling": source_spelling,
            "tonality_note": tonality_note,
            "tempo_bpm": facts.get("tempo_bpm", ""),
            "duration_seconds": "",
            "decade": facts.get("decade", ""),
            "loop_length": facts.get("loop_length", ""),
            "chord_count": facts.get("chord_count", ""),
            "chord_pattern": facts.get("chord_pattern", ""),
            "bass_difficulty": facts.get("bass_difficulty", ""),
            "vocal_range": facts.get("vocal_range", ""),
            "mashup_note": facts.get("mashup_note", ""),
            "row_instances": g["rows"],
            "worksheets": len(g["sheets"]),
            "from_master_tab": "yes" if from_master else "no",
            "flag": "; ".join(sorted(set(flags))),
            "note": " | ".join(notes),
        })
    return songs


def fold_performers(extract):
    """[R13-R16] One performer per singer column, plus the free-text annotations, all
    resolved through the same normalisation as artists."""
    groups = collections.defaultdict(lambda: {
        "spellings": collections.Counter(), "sources": collections.Counter(),
        "leads": 0, "backings": 0, "flags": set(), "notes": set(),
    })
    for mark in extract.performer_marks:
        key = normalise(mark["performer"].rstrip("?"))
        if not key:
            continue
        g = groups[key]
        g["spellings"][mark["performer"]] += 1
        g["sources"]["%s:%s" % (mark["source"], mark["column"])] += 1
        if mark["is_lead"]:
            g["leads"] += 1
        else:
            g["backings"] += 1
        for flag in [f for f in mark["flag"].split("; ") if f]:
            g["flags"].add(flag)
        if mark["note"]:
            g["notes"].add(mark["note"])

    performers = []
    for key in sorted(groups):
        g = groups[key]
        spellings = sorted(g["spellings"].items(), key=lambda kv: (-kv[1], kv[0]))
        canonical = spellings[0][0]
        flags = set(g["flags"])
        notes = set(g["notes"])
        if len(g["spellings"]) > 1:
            flags.add("PERFORMER-SPELLING-COLLISION")
            notes.add("%d spellings collapse to one performer under [D17] — this pair is "
                      "the reason the table exists [R16, D25]" % len(g["spellings"]))
        performers.append({
            "performer_id": derived_id("performer", key),
            "name": canonical,
            "normalised_key": key,
            "spellings": " | ".join("%s (%d)" % (n, c) for n, c in spellings),
            "lead_marks": g["leads"],
            "backing_marks": g["backings"],
            "total_marks": sum(g["spellings"].values()),
            "sources": " | ".join("%s (%d)" % (s, c) for s, c
                                  in sorted(g["sources"].items())),
            "flag": "; ".join(sorted(flags)),
            "note": " | ".join(sorted(notes)),
        })
    return performers


def build_practice_events(extract):
    """[R17-R20] Dedupe to unique (song, instrument, date). The workbook has been
    overwriting single-date cells for four years; some events survive only because a stale
    set-list tab froze an old value, so every tab is a source."""
    seen = {}
    for record in extract.practice_raw:
        if not record["date"]:
            continue
        key = (normalise(record["artist"]), normalise(record["title"]),
               record["instrument"], record["context"], record["date"])
        if key in seen:
            seen[key]["sources"].add(record["worksheet"])
            continue
        seen[key] = {
            "song": record["title"],
            "artist": record["artist"],
            "instrument": record["instrument"],
            "context": record["context"],
            "date": record["date"],
            "sources": {record["worksheet"]},
            "flag": record["flag"],
            "note": record["note"],
        }
    events = []
    for key in sorted(seen):
        e = seen[key]
        events.append({
            "song": e["song"], "artist": e["artist"], "instrument": e["instrument"],
            "context": e["context"], "date": e["date"],
            "source_worksheets": len(e["sources"]),
            "surviving_only_on": (sorted(e["sources"])[0] if len(e["sources"]) == 1 else ""),
            "flag": e["flag"], "note": e["note"],
        })
    return events


def build_tags(extract):
    """[D19] flag columns, plus the bass-vox tag from the Bass-vox Rep tab [R29]."""
    rows = []
    seen = set()
    for mark in extract.tag_marks:
        key = (mark["tag"], normalise(mark["artist"]), normalise(mark["title"]))
        if key in seen:
            continue
        seen.add(key)
        rows.append({
            "tag": mark["tag"], "song": mark["title"], "artist": mark["artist"],
            "source": mark["source"], "flag": mark["flag"], "note": mark["note"],
        })

    # [R29] Bass-vox Rep is NOT a setlist. One song_tag row per listed song.
    for row in extract.song_rows:
        if row["worksheet"] != "Bass-vox Rep":
            continue
        key = ("bass-vox", normalise(row["artist"]), normalise(row["title"]))
        if key in seen:
            continue
        seen.add(key)
        rows.append({
            "tag": "bass-vox", "song": row["title"], "artist": row["artist"],
            "source": "worksheet Bass-vox Rep",
            "flag": "",
            "note": "capability list, not a setlist [R29]. The difficulty of playing bass "
                    "and singing at once is emergent and is not a function of either "
                    "song_instrument.difficulty, so a tag is the honest model.",
        })
    rows.sort(key=lambda r: (r["tag"], normalise(r["artist"]), normalise(r["song"])))
    return rows


# --------------------------------------------------------------------------------------
# Counts
# --------------------------------------------------------------------------------------

def build_counts(extract, artists, songs, events):
    # The [R7] census counts cells in the Key *column*. Keys recovered from inside a
    # title cell are real findings but are not part of that column's census.
    census = [c for c in extract.key_cells if c.get("census")]
    kinds = collections.Counter(c["kind"] for c in census)
    raw_keys = collections.Counter(c["raw"].strip() for c in census)
    suspect = collections.Counter()
    for c in census:
        if c["root"] in SUSPECT_SPELLINGS:
            suspect[c["root"]] += 1

    discipline_events = [e for e in events if e["context"] == "practice"]
    by_year = collections.Counter(e["date"][:4] for e in discipline_events)

    unparseable = sum(1 for r in extract.practice_raw
                      if r["flag"] in ("PRACTICE-DATE-UNPARSEABLE",
                                       "PRACTICE-DATE-INFERRED-YEAR"))
    implausible = collections.Counter()
    for r in extract.practice_raw:
        if r["flag"] == "PRACTICE-DATE-IMPLAUSIBLE":
            m = re.match(r"^(\d{4})", r["raw"])
            if m:
                implausible[m.group(1)] += 1

    collisions = sum(1 for a in artists if a["spelling_count"] > 1)
    row_instances = len(extract.song_rows)
    total_rows = 0
    for name in extract.wb.sheetnames:
        _, rows = read_sheet(extract.wb[name])
        total_rows += sum(1 for r in rows if any(not is_blank(v) for v in r))

    found = {
        ("workbook", "worksheets"): len(extract.wb.sheetnames),
        ("workbook", "rows on export"): total_rows,
        ("artists", "distinct artist strings"): len(extract.artist_cells),
        ("artists", "artists after normalisation"): len(artists),
        ("artists", "known collision groups"): collisions,
        ("artists", "SET 1 / SET 2 cells in Artist column"): sum(
            n for s, n in extract.artist_divider_cells.items()
            if re.match(r"^set\s*[12]$", s.strip(), re.IGNORECASE)),
        ("songs", "distinct songs"): len(songs),
        ("songs", "song row instances"): row_instances,
        ("keys", "distinct Key values"): len(raw_keys),
        ("keys", "plain key names"): kinds["plain"],
        ("keys", "transpositions key-first"): kinds["transposition_key_first"],
        ("keys", "transpositions offset-first"): kinds["transposition_offset_first"],
        ("keys", "deliberately-original markers"): kinds["original"],
        ("keys", "unknowns"): kinds["unknown"],
        ("keys", "non-keys"): kinds["nonkey"],
        ("keys", "suspect spelling Bbb"): suspect["Bbb"],
        ("keys", "suspect spelling Cb"): suspect["Cb"],
        ("keys", "suspect spelling Fb"): suspect["Fb"],
        ("practice", "unique (song, discipline, date)"): len(discipline_events),
        ("practice", "unique events 2021"): by_year["2021"],
        ("practice", "unique events 2022"): by_year["2022"],
        ("practice", "unique events 2023"): by_year["2023"],
        ("practice", "unique events 2024"): by_year["2024"],
        ("practice", "unique events 2025"): by_year["2025"],
        ("practice", "unique events 2026"): by_year["2026"],
        ("practice", "unparseable practice-date cells"): unparseable,
        ("practice", "date typos landing in 2002"): implausible["2002"],
        ("practice", "date typos landing in 2099"): implausible["2099"],
    }

    table = []
    for section, metric, expected, ref in EXPECTED:
        actual = found.get((section, metric), 0)
        delta = actual - expected
        if delta == 0:
            verdict = "match"
        elif abs(delta) <= max(2, expected * 0.02):
            verdict = "close"
        else:
            verdict = "DIVERGES"
        table.append({
            "section": section, "metric": metric, "spec_ref": ref,
            "expected": expected, "found": actual, "delta": delta, "verdict": verdict,
        })
    return table


# --------------------------------------------------------------------------------------
# Output
# --------------------------------------------------------------------------------------

def write_sheet(wb, title, rows, columns):
    ws = wb.create_sheet(title)
    ws.append(columns)
    for cell in ws[1]:
        cell.font = openpyxl.styles.Font(bold=True)
    for row in rows:
        ws.append([row.get(c, "") for c in columns])
    ws.freeze_panes = "A2"
    for ci, name in enumerate(columns, start=1):
        widest = len(name)
        for row in rows[:400]:
            widest = max(widest, len(str(row.get(name, ""))))
        ws.column_dimensions[get_column_letter(ci)].width = min(60, max(9, widest + 2))
    return ws


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--in", dest="source", default=DEFAULT_IN)
    parser.add_argument("--out", dest="target", default=DEFAULT_OUT)
    args = parser.parse_args()

    if not os.path.isfile(args.source):
        sys.exit("FATAL: source workbook not found: %s" % args.source)

    print("reading %s" % args.source)
    wb = openpyxl.load_workbook(args.source, data_only=True)

    extract = Extract(wb)
    extract.load_master()
    extract.scan()
    extract.resolve_missing_artists()
    extract.build_setlists()
    extract.annotate_setlist_relationships()
    extract.seed_declared_performers()
    extract.build_setlist_items()

    artists = fold_artists(extract.artist_cells)
    artists_by_key = {a["normalised_key"]: a["artist_id"] for a in artists}
    songs = fold_songs(extract, artists_by_key)
    performers = fold_performers(extract)
    events = build_practice_events(extract)
    tags = build_tags(extract)
    counts = build_counts(extract, artists, songs, events)

    out = openpyxl.Workbook()
    out.remove(out.active)
    # Pinned so two runs differ only in the zip container's own member timestamps.
    out.properties.creator = "songbook tools/import/extract.py"
    out.properties.title = "Songs 2026 migration review"
    out.properties.created = datetime.datetime(2026, 1, 1)
    out.properties.modified = datetime.datetime(2026, 1, 1)

    write_sheet(out, "Counts", counts,
                ["section", "metric", "spec_ref", "expected", "found", "delta", "verdict"])
    write_sheet(out, "Songs", songs,
                ["song_id", "title", "artist", "tonal_centre", "key_signature",
                 "source_key_spelling", "tonality_note", "tempo_bpm", "duration_seconds",
                 "decade", "loop_length", "chord_count", "chord_pattern",
                 "bass_difficulty", "vocal_range", "mashup_note", "row_instances",
                 "worksheets", "from_master_tab", "flag", "note"])
    write_sheet(out, "Artists", artists,
                ["artist_id", "canonical_name", "sort_name", "normalised_key",
                 "spelling_count", "total_occurrences", "variants", "flag", "note"])
    write_sheet(out, "Performers", performers,
                ["performer_id", "name", "normalised_key", "spellings", "lead_marks",
                 "backing_marks", "total_marks", "sources", "flag", "note"])
    write_sheet(out, "Setlists", extract.setlists,
                ["worksheet", "purpose", "performed_on", "date_source", "remainder",
                 "proposed_classification", "confidence", "proposed_venue",
                 "proposed_client", "in_sheet_header", "row_count", "sets_seen",
                 "order_convention", "flag", "note"])
    write_sheet(out, "SetlistItems", extract.setlist_items,
                ["worksheet", "row", "set_no", "position", "title", "artist", "key_raw",
                 "transpose", "tempo_on_tab", "flag", "note"])
    write_sheet(out, "PracticeEvents", events,
                ["song", "artist", "instrument", "context", "date", "source_worksheets",
                 "surviving_only_on", "flag", "note"])
    write_sheet(out, "Tags", tags, ["tag", "song", "artist", "source", "flag", "note"])
    write_sheet(out, "KeyCells", sorted(
        extract.key_cells, key=lambda c: (c["kind"], c["raw"], c["worksheet"], c["cell"])),
        ["worksheet", "cell", "column", "position", "title", "artist", "raw", "kind",
         "root", "minor", "tonal_centre", "key_signature", "offset", "set_no",
         "flag", "note"])
    write_sheet(out, "Unresolved", sorted(
        extract.unresolved, key=lambda u: (u["worksheet"], u["cell"])),
        ["worksheet", "cell", "raw_value", "reason"])

    out.save(args.target)
    print("wrote %s" % args.target)

    # ---- report ------------------------------------------------------------------
    print()
    print("%-10s %-40s %-6s %8s %8s %7s  %s"
          % ("SECTION", "METRIC", "SPEC", "EXPECTED", "FOUND", "DELTA", "VERDICT"))
    print("-" * 104)
    for row in counts:
        print("%-10s %-40s %-6s %8s %8s %+7d  %s"
              % (row["section"], row["metric"], row["spec_ref"], row["expected"],
                 row["found"], row["delta"], row["verdict"]))

    print()
    flagged = collections.Counter()
    for name, rows in (("Songs", songs), ("Artists", artists), ("Performers", performers),
                       ("Setlists", extract.setlists), ("SetlistItems",
                       extract.setlist_items), ("PracticeEvents", events), ("Tags", tags)):
        for row in rows:
            for flag in [f for f in row.get("flag", "").split("; ") if f]:
                flagged[(name, flag)] += 1
    print("FLAGGED ROWS AWAITING HUMAN REVIEW")
    print("-" * 104)
    for (sheet, flag), n in sorted(flagged.items(), key=lambda kv: (kv[0][0], -kv[1])):
        print("   %-15s %-38s %5d" % (sheet, flag, n))
    print("   %-15s %-38s %5d" % ("Unresolved", "(cells the script refused to read)",
                                  len(extract.unresolved)))
    print()
    print("total flagged rows: %d across %d categories"
          % (sum(flagged.values()), len(flagged)))


if __name__ == "__main__":
    main()
