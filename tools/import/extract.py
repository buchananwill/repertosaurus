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

# NOT review.xlsx. That file is the common ancestor of the user's edited working copy and
# is required as the base for a three-way merge; overwriting it destroys the merge base and
# the hand resolutions become unmergeable. This pass writes a sibling.
DEFAULT_OUT = os.path.join(".scratch", "review-next.xlsx")

# Paths this script must never touch. The user's working copy is off limits.
FORBIDDEN_OUTPUTS = (os.path.join(".scratch", "review.xlsx"),)


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
    ("practice", "date typos landing in 1900", 13, "found"),
    ("practice", "date typos landing in 2099", 10, "R20"),
    ("order", "tabs with an Order/Position column", 37, "R32"),
    ("order", "hundreds-style tabs", 26, "R32"),
    ("order", "decimal set.position tabs", 3, "R32"),
    ("order", "plain counter + Set tabs", 3, "R32"),
    ("order", "degenerate or unreliable tabs", 4, "R32"),
    ("order", "empty Order tabs", 1, "R32"),
    ("order", "unheaded position columns recovered", 2, "review"),
    ("cells", "cells left unaccounted for", 0, "review"),
]


# --------------------------------------------------------------------------------------
# Normalisation [D17] and derived ids [D2, D4, D4a, D4b]
# --------------------------------------------------------------------------------------

def normalise(value) -> str:
    """NFC, lowercase, trim, strip leading 'The ', fold '&' to 'and', strip punctuation,
    collapse whitespace.  [D17]  Must match the shared-core implementation exactly.

    This is the ID DERIVATION path. [D17c]: matching may be lossy, derivation must not be.
    """
    # [D17a] NFC first, before anything else. `é` can be one code point or `e` plus a
    # combining acute, and macOS/iOS input methods routinely produce the decomposed form;
    # without this the two spellings of the same artist derive different ids on different
    # devices, permanently, with nothing looking wrong on either screen.
    #
    # [D17b] NFC, NOT NFKC. Compatibility folding is meaning-changing — ligatures,
    # full-width forms, roman-numeral characters, the trademark sign — and [D5] makes ids
    # immutable, so it is unrecoverable. Derivation takes the least lossy transform that
    # fixes the real keyboard-reachable fork. Free to adopt today (measured: NFC and NFKC
    # agree on all 24,705 text cells in the source), expensive to adopt later, because
    # compatibility characters arrive by paste and one pasted ligature forks an id forever.
    s = unicodedata.normalize("NFC", "" if value is None else str(value))
    s = s.strip().lower()
    s = s.replace("&", " and ")
    if s.startswith("the "):
        s = s[4:]
    # Punctuation becomes a SPACE, never nothing. [D4c] ratifies the tag `cw-duet` keying
    # on `cw duet`; deleting the separator instead would give `cwduet`, and `acdc`,
    # `blink182`, `ah a`. These ids are permanent once written [D4a, D5] and this same
    # function backs the app's type-ahead [D17], so "AC DC" must match the migrated row.
    #
    # [D4e] punctuation is anything that is not a Unicode letter or digit. Python's `\w`
    # matches Unicode letters, so accents agree with the Kotlin core for free — `Beyoncé`
    # keeps its `é` in both. But `\w` ALSO matches `_`, which is neither a letter nor a
    # digit, so the underscore is added to the class explicitly. Without the `|_` the two
    # implementations disagree on every name containing one and silently fork its id.
    s = re.sub(r"[^\w\s]|_", " ", s, flags=re.UNICODE)
    s = re.sub(r"\s+", " ", s).strip()
    return s


ROOT_NS = uuid.uuid5(uuid.NAMESPACE_DNS, "songbook.dev")  # 3ce0f1dc-... [D4a]


def table_ns(table: str) -> uuid.UUID:
    return uuid.uuid5(ROOT_NS, table)  # per-table, never a shared root [D4b]


def derived_id(table: str, canonical_key: str) -> str:
    return str(uuid.uuid5(table_ns(table), canonical_key))


# [D28a] the seeded placeholder for songs the workbook never attributes. The id is derived
# here, not copied from artist.sq — if the two ever disagree the migration must fail loudly
# rather than write an orphan FK. Verified equal to the seed row at import time below.
UNKNOWN_ARTIST_NAME = "Unknown Artist"
UNKNOWN_ARTIST_ID = derived_id("artist", normalise(UNKNOWN_ARTIST_NAME))

# The value seeded in shared/.../db/artist.sq. Asserted, never copied into a variable that
# feeds an id: a mismatch means the two implementations have diverged and the migration
# would write songs pointing at an artist row that does not exist.
UNKNOWN_ARTIST_SEED_ID = "cf06771d-4e8d-53fc-83fb-359be7dfaefc"
assert UNKNOWN_ARTIST_ID == UNKNOWN_ARTIST_SEED_ID, (
    "Unknown Artist id %s does not match the seed row %s in artist.sq [D28a]"
    % (UNKNOWN_ARTIST_ID, UNKNOWN_ARTIST_SEED_ID))

# [R23] user-confirmed bands. Seed data, not guesses. Ids are derived per [D4/4a/4b]:
# UUIDv5(namespace("band"), normalise(name)).
CONFIRMED_BANDS = ("Blue Lion", "Radiant Lanterns", "LPT with Ryan")


def junction_id(table: str, fk_a: str, fk_b: str) -> str:
    """[D4] EVERY junction: `UUIDv5(namespace(table), fk_a + "/" + fk_b)`.

    One implementation for `song_instrument`, `song_tag`, `song_performer` and
    `setlist_item_performer` — the amended decision 4 makes them all the same shape.

    This CORRECTS an earlier form, `UUIDv5(fk_a, fk_b)`, which used the first foreign key
    directly as the namespace with no separator. That form was inconsistent with 4a's
    per-table namespaces and 4d's separator, and — carrying no table identity — gave two
    different junctions over the same pair of ids the SAME id. Do not reintroduce it.
    """
    return derived_id(table, "%s/%s" % (fk_a, fk_b))


def setlist_item_performer_id(item_id: str, performer_id: str) -> str:
    """[D4, D58] `UUIDv5(namespace("setlist_item_performer"), item_id + "/" + performer_id)`.

    `position` is deliberately NOT in the key: moving somebody from lead to co-lead must
    update the row, not mint a second one alongside it. [D58e] that position is a plain
    INTEGER, unlike `setlist_item.position`, which is a fractional TEXT key — one orders a
    long list two devices reorder independently, the other orders two or three people
    inside a single item.

    The extract pass cannot call this. `setlist_item.id` is **random** per the type roster,
    minted by the build pass, so any value computed here would change on every run and
    break idempotence. The formula lives here so the build pass has one implementation to
    call, and the review sheet carries a stable `item_key` to join on instead.
    """
    return junction_id("setlist_item_performer", item_id, performer_id)


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

# [D42] the workbook's a-e difficulty vocabulary mapped to 1-5.
DIFFICULTY_MAP = {"a": 1, "b": 2, "c": 3, "d": 4, "e": 5}

# [R12] spellings the workbook uses that a human should confirm rather than the script fix.
# Flagging is not discarding: where the spelling is nonetheless a real key, the derived
# signature is kept. Cb major is a legitimate key at -7 and is inside [D32]'s range.
SUSPECT_SPELLINGS = {"Bbb", "Cb", "Fb"}
# Of those, the ones that are not keys at all and so cannot carry a signature.
NOT_REALLY_KEYS = {"Bbb", "Fb"}

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

    def add_flag(self, flag, note):
        """Append. Never assign — a transposition reading must not clobber the
        SUSPECT-KEY-SPELLING its own root already earned."""
        flags = [f for f in self.flag.split("; ") if f]
        if flag not in flags:
            flags.append(flag)
        self.flag = "; ".join(flags)
        self.note = " | ".join([n for n in (self.note, note) if n])


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
        if root in NOT_REALLY_KEYS:
            reading.key_signature = None
            reading.add_flag("SUSPECT-KEY-SPELLING",
                             "%s is not a key at all [R12]; pitch class proposed, "
                             "key_signature left NULL" % root)
        else:
            # [R12] says flag, not discard. Cb major is a real key at -7.
            reading.add_flag("SUSPECT-KEY-SPELLING",
                             "%s is an unusual spelling and is flagged [R12], but it is a "
                             "real key, so the derived signature %+d is kept"
                             % (root, reading.key_signature))
    elif reading.key_signature is None:
        reading.add_flag("KEY-SIGNATURE-UNDERIVABLE",
                         "no diatonic signature for %s%s [R10]; tonal_centre kept, "
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

    # NFKC is deliberate and correct HERE, and must not be changed to NFC to match
    # normalise(). This is a parsing/matching path, not an id path [D17c]: the output is
    # a pitch class, a signature and a signed offset — all integers — and the source
    # spelling is retained separately from the untouched cell value [R11]. Compatibility
    # folding is harmless and mildly helpful, letting a full-width or otherwise
    # compatibility-encoded key name still parse as a key rather than land in Unresolved.
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
        reading.add_flag("TRANSPOSE-NOTATION-AMBIGUOUS",
                         "key-first notation; 'X (-n)' and '-n (X)' contradict each other "
                         "[R7]. Confirm whether %s is the written or the sounding key, and "
                         "whether %+d applies to it. Offset belongs on the setlist_item, "
                         "not the song [R8]." % (m.group(1), reading.offset))
        return reading

    m = RE_OFFSET_FIRST.match(text)
    if m:
        reading.kind = "transposition_offset_first"
        reading.offset = int(m.group(1).replace(" ", ""))
        _read_named_key(m.group(2), reading)
        reading.add_flag("TRANSPOSE-NOTATION-AMBIGUOUS",
                         "offset-first notation, the reversed form [R7]. Confirm whether "
                         "%s is the written or the sounding key. Offset belongs on the "
                         "setlist_item, not the song [R8]." % m.group(2))
        return reading

    if RE_ORIG.search(text):
        reading.kind = "original"
        reading.offset = 0
        head = RE_ORIG.split(text)[0].strip().strip("(").strip()
        if head:
            _read_named_key(head, reading)
        reading.note = " | ".join([n for n in (
            reading.note,
            "deliberately played in the original key [R7]; transpose = 0") if n])
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
# `p` and `r` are deliberately NOT here: they are single letters, and single letters are
# resolved by the rulings below rather than read as a tick for the column's owner.
CAPABILITY_MARKS = {"x", "y", "s", "*", "?", "1", "goal", "mp3", "k"}

# SPARSE BEATS WRONG. Where an attribution cannot be confirmed, emit nothing. A missing
# tag is recoverable — the user sees the gap in the app and fills it in. A wrong tag is
# not, because nobody knows to look for it. The cost is low: every unattributed cell still
# reaches Unresolved with its raw value, worksheet and cell reference, so a later ruling
# plus a re-run recovers it.

# The ONLY confirmed single-letter expansion.
INITIAL_EXPANSIONS = {"w": "Will"}

# Ruled NOT performers. Discard the attribution; never guess at what they meant.
INITIALS_NOT_PERFORMERS = {"a", "b", "c"}

# Read as initials by an earlier pass and WITHDRAWN. Ryan, Tommy and Paul are real people
# the user added, but that they are the R, T and P in a lead-vocal column was never
# confirmed — it was inference from the roster. Under 'sparse beats wrong' these produce
# no attribution at all.
UNCONFIRMED_INITIALS = {"r", "t", "p"}

# Real people the user added deliberately. They keep their `performer` rows even with no
# song attributions, because deleting a person the user created would be a second error on
# top of withdrawing the guess.
USER_ADDED_PERFORMERS = ("Ryan", "Tommy", "Paul")

# Inside a duet marker the letters mean something the standalone ruling does not cover.
# `W/C` is CONFIRMED as Will/Coralie, so `c` is Coralie here — while a lone `C` remains
# ruled not a performer at all. The user is explicit that the pairing tells them nothing
# about a standalone letter, so the two vocabularies stay separate.
DUET_INITIALS = dict(INITIAL_EXPANSIONS, c="Coralie")

# Ruled garbage by the user in their review pass. These are rulings, not accidents: the
# machine file kept regenerating them after the user deleted them by hand. `Kendra Piper`
# is a Title-column header, not a singer column [R13 vs R26]. `Si` is NOT here — it is a
# real name.
BINNED_PERFORMERS = {"2nd request", "3rd request", "fd", "lv", "kendra piper"}

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
    "vox triage", "w", "r", "a",
}

# Columns read into song facts by read_facts.
FACT_COLUMNS = {
    "tempo", "decade", "loop length", "no. of chords", "chord pattern", "mashup",
    "feel", "bass difficulty", "range", "wavestate patches", "c gtr",
    "bv comments", "guitar comments",
}

# Columns read into setlist structure by classify_order / build_setlist_items.
SETLIST_COLUMNS = {"order", "position", "set", "+/-", "change", "no.", "num", "overall"}

# [R29] Above this many songs with no ordering at all, a tab reads as a repertoire
# snapshot rather than a performance.
REPERTOIRE_ROW_THRESHOLD = 200

# A medley is not a song — the user ruled it out. Detected by pattern, not by name, so
# any further one is caught rather than only the two already known. `\bmedley\b` will not
# fire on `Stand By Me`, and the slash rule wants a title on each side, so `AC/DC` in an
# artist cell and `w/c` in a performer cell are untouched.
RE_MEDLEY = re.compile(r"\bmedle?y\b", re.IGNORECASE)
RE_JOINED_TITLES = re.compile(r"^\s*\S.*\S\s*/\s*\S.*\S\s*$")


def medley_reason(title):
    """Return why a title is not a song, or '' if it is one."""
    text = str(title).strip()
    if RE_MEDLEY.search(text):
        return "title says medley"
    if "/" in text and RE_JOINED_TITLES.match(text):
        left, right = [p.strip() for p in text.split("/", 1)]
        if len(left.split()) >= 2 and len(right.split()) >= 2:
            return "two titles joined by '/' (%r and %r)" % (left, right)
    return ""

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
    if len(head) < 3:
        return title.strip(), ""
    # No word-count guard here. The real guard is clean_title's check that the stripped
    # form names a song the master tab knows, which a bare 'Kiss' or 'Valerie' passes and
    # a false split does not. Requiring a space in the head refused 17 correct splits and
    # minted phantom songs ('Valerie Ab', 'Kiss - A', 'Shotgun F').
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


def confirmed_band(remainder):
    """Return the confirmed band a worksheet-name remainder carries, or None.

    Matches the whole remainder or a leading run of its words, so `Blue Lion ACOUSTIC`
    resolves to `Blue Lion` — the qualifier marks a configuration of the same band [R28].
    Only the three names [R23] confirms are eligible; nothing here is inferred.
    """
    key = normalise(remainder)
    words = remainder.split()
    for name in CONFIRMED_BANDS:
        band_key = normalise(name)
        if key == band_key or key.startswith(band_key + " "):
            for take in range(1, len(words) + 1):
                if normalise(" ".join(words[:take])) == band_key:
                    return name
            return name
    return None


def classify_remainder(remainder):
    """Propose a classification with a confidence. NEVER decide [R23, R24, R25, R26]."""
    text = remainder.strip(" -_,").strip()
    if not text:
        return "", "", "", ""
    key = normalise(text)

    if key in CONFIRMED_CLASSIFICATION:
        return CONFIRMED_CLASSIFICATION[key], "confirmed", "", ""

    # A confirmed name plus a trailing qualifier, e.g. 'Blue Lion ACOUSTIC'. Align on word
    # boundaries, not on the normalised string's length — normalisation drops a leading
    # 'The ' and expands '&', so a raw slice by len(normalised) misaligns.
    words = text.split()
    for confirmed, kind in sorted(CONFIRMED_CLASSIFICATION.items()):
        if not (key == confirmed or key.startswith(confirmed + " ")):
            continue
        for take in range(1, len(words) + 1):
            if normalise(" ".join(words[:take])) == confirmed:
                qualifier = " ".join(words[take:]).strip()
                return (kind + " + configuration" if qualifier else kind,
                        "confirmed-with-qualifier" if qualifier else "confirmed",
                        qualifier, "")
        return (kind, "confirmed", "", "")

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
        self.artist_cells = collections.Counter()      # raw string -> count, for the fold
        self.artist_column_cells = collections.Counter()  # Artist column only, for [R1]
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
        self.medley_titles = set()    # normalised titles the user ruled are not songs
        self.known_pairs = set()      # (norm artist, norm title) from headed columns only
        self.misaligned = {}          # sheet -> alignment finding for an inferred column
        self.dropped_attributions = 0
        self.vocal_ranges = {}        # (norm artist, norm title) -> 1/0, for [R15, D27]
        self.triage_cells = 0         # deliberately-dropped triage cells, counted not lost
        self.combined_columns = collections.defaultdict(set)
        self.unheaded_order_columns = collections.defaultdict(set)
        self.unheaded_key_columns = collections.defaultdict(set)
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
        self.build_known_pairs()
        self.detect_misaligned_columns()
        self.log_misaligned_columns()
        self.detect_special_columns()
        for sheet_name in self.wb.sheetnames:
            headers, rows = read_sheet(self.wb[sheet_name])
            roles = self.sheet_columns[sheet_name]
            self.census_keys(sheet_name, headers, rows, roles)
            self.scan_sheet(sheet_name, headers, rows, roles)
            self.account_columns(sheet_name, headers, rows, roles)

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

            # '5-7-24' carries a gig-key column with no header at all. Its cells are real
            # key evidence but are NOT part of [R7]'s census of the `Key` column, so they
            # are recorded with census=False and logged.
            for ci in sorted(self.unheaded_key_columns.get(sheet_name, ())):
                v = row[ci]
                if is_blank(v):
                    continue
                reading = classify_key(v)
                if reading.kind == "empty":
                    continue
                self.key_cells.append({
                    "worksheet": sheet_name, "cell": cell_ref(ci, ri),
                    "column": "(unheaded)", "position": "gig", "title": title,
                    "artist": artist, "raw": str(v).strip(), "kind": reading.kind,
                    "root": reading.root or "", "minor": reading.minor,
                    "tonal_centre": reading.tonal_centre,
                    "key_signature": reading.key_signature, "offset": reading.offset,
                    "set_no": current_set, "census": False, "is_master": False,
                    "flag": "; ".join([f for f in (reading.flag,
                                                   "KEY-COLUMN-UNHEADED") if f]),
                    "note": " | ".join([n for n in (
                        reading.note,
                        "key read from an unheaded column; not counted in [R7]'s census "
                        "of the `Key` column") if n]),
                })
                self.unresolve(sheet_name, cell_ref(ci, ri), v,
                               "key value in an unheaded column; recorded as gig-key "
                               "evidence but excluded from the [R7] Key-column census")

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

            # Two rows of 'Radiant Lanterns 2022' populate ONLY the combined
            # 'Title - Artist' column, so it is the sole record of those songs.
            if is_blank(title_raw):
                for ci in sorted(self.combined_columns.get(sheet_name, ())):
                    if is_blank(row[ci]) or " - " not in str(row[ci]):
                        continue
                    part_title, part_artist = str(row[ci]).rsplit(" - ", 1)
                    title_raw, artist_raw = part_title.strip(), part_artist.strip()
                    self.unresolve(sheet_name, cell_ref(ci, ri), str(row[ci]),
                                   "row's only content is the combined 'Title - Artist' "
                                   "column; song recovered by splitting on the last ' - ' "
                                   "into %r / %r — confirm the split"
                                   % (title_raw, artist_raw))
                    break

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
                # [R1]'s 288 is a census of the Artist *column*. An artist recovered by
                # splitting a combined cell still needs an artist row, but must not shift
                # that census, so it is folded without being counted.
                if ai is not None and not is_blank(row[ai]):
                    self.artist_column_cells[str(artist_raw)] += 1

            if is_blank(title_raw):
                # A practice date, a tag mark or a singer mark on a row that names no song
                # cannot become a row in any table — there is no song to attach it to. Log
                # it rather than dropping it silently.
                self.log_orphan_row(sheet_name, headers, lower, row, ri)
                continue

            title, inline_key = self.clean_title(title_raw)
            artist = str(artist_raw).strip() if not is_blank(artist_raw) else ""
            nt, na = normalise(title), normalise(artist)

            # USER RULING: a medley is not a song. Detected by pattern so a third one is
            # caught too, and logged rather than dropped.
            reason = medley_reason(title)
            if reason:
                self.medley_titles.add(nt)
                self.unresolve(sheet_name, cell_ref(ti, ri), title,
                               "medley, user ruled not a song (%s)" % reason)
                continue

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

            facts = self.read_facts(sheet_name, headers, lower, row, ri, ti,
                                    title_for_row=title, artist_for_row=artist)
            if is_master:
                self.master_facts[(na, nt)] = facts
            else:
                self.tab_facts[(na, nt)].append(facts)

            # Key cells are censused in their own pass (census_keys), not here.
            self.read_practice(sheet_name, headers, lower, row, ri, title, artist)
            self.read_performers(sheet_name, headers, lower, row, ri, title, artist)
            self.read_tags(sheet_name, headers, lower, row, ri, title, artist)

    def recognised_columns(self, sheet_name, headers, roles):
        """Every column index some reader consumes, plus the triage columns the spec
        deliberately drops. Anything outside this set is unaccounted-for data."""
        lower = [h.lower() for h in headers]
        recognised, triage = set(), set()
        for ci, h in enumerate(lower):
            if h in TRIAGE_COLUMNS:
                triage.add(ci)
            elif (h == "key" or h in FACT_COLUMNS or h in SETLIST_COLUMNS
                    or h in PERFORMER_COLUMNS or h in ANNOTATION_COLUMNS
                    or h in TAG_COLUMNS or h == "twitch"
                    or "practice" in h or "practise" in h):
                recognised.add(ci)
            elif h == "" and self.is_annotation_column(sheet_name, ci):
                recognised.add(ci)
            elif h == "" and ci in self.combined_columns.get(sheet_name, ()):
                recognised.add(ci)
            elif h == "" and ci in self.unheaded_order_columns.get(sheet_name, ()):
                recognised.add(ci)
            elif h == "" and ci in self.unheaded_key_columns.get(sheet_name, ()):
                recognised.add(ci)
        for role in ("title", "artist", "artist_misaligned_column"):
            if roles.get(role) is not None:
                # A column dropped for misalignment is still accounted for: every one of
                # its cells is logged individually by log_misaligned_columns.
                recognised.add(roles[role])
        return recognised, triage

    def account_columns(self, sheet_name, headers, rows, roles):
        """No silent data loss. Every non-blank cell in a column no reader claims is
        logged, with the header named, so the human can see what the migration ignored."""
        recognised, triage = self.recognised_columns(sheet_name, headers, roles)
        for ci in range(len(headers)):
            if ci in recognised:
                continue
            header = headers[ci] or "(unheaded)"
            cells = [(ri, r[ci]) for ri, r in enumerate(rows) if not is_blank(r[ci])]
            if not cells:
                continue
            if ci in triage:
                self.triage_cells += len(cells)
                self.unresolve(sheet_name, "%s2:%s%d" % (get_column_letter(ci + 1),
                                                         get_column_letter(ci + 1),
                                                         len(rows) + 1),
                               "%d cells" % len(cells),
                               "column %r is a set-list triage column, deliberately not "
                               "modelled (see 'Deliberately not modelled' in data-model.md); "
                               "dropped on purpose, not by omission" % header)
                continue
            for ri, value in cells:
                self.unresolve(sheet_name, cell_ref(ci, ri), value,
                               "column %r has no role in the target schema; the cell was "
                               "read but nothing consumes it" % header)

    def build_known_pairs(self):
        """(artist, title) pairs from columns the workbook itself heads `Artist` and
        `Title`. Those are aligned by construction, so they are the yardstick an inferred
        column is measured against."""
        for sheet_name in self.wb.sheetnames:
            roles = self.sheet_columns[sheet_name]
            if roles["artist"] is None or roles["title"] is None:
                continue
            if roles["artist_inferred"]:
                continue
            headers, rows = read_sheet(self.wb[sheet_name])
            for row in rows:
                a, t = row[roles["artist"]], row[roles["title"]]
                if is_blank(a) or is_blank(t) or not isinstance(a, str):
                    continue
                self.known_pairs.add(
                    (normalise(a), normalise(self.clean_title(t)[0])))

    def detect_misaligned_columns(self):
        """A column sorted without extending the selection leaves its values offset
        against the rows they describe. `26-8-23` is the known case: its titles are in
        alphabetical order and the unheaded artist column is one row out.

        The test is comparative, not a hardcoded tab list. For every column inferred as an
        artist source, score the aligned reading against the +1 and -1 shifts using
        (artist, title) pairs the headed columns already vouch for. If a shift scores
        better, the column is not trustworthy at any offset: the aligned reading is
        provably wrong and the shift is only inferred. Drop it and flag it.
        """
        for sheet_name in self.wb.sheetnames:
            roles = self.sheet_columns[sheet_name]
            aci, tci = roles["artist"], roles["title"]
            if aci is None or tci is None or not roles["artist_inferred"]:
                continue
            headers, rows = read_sheet(self.wb[sheet_name])
            scores = {}
            for offset in (0, 1, -1):
                hits = total = 0
                for ri, row in enumerate(rows):
                    a = row[aci]
                    if is_blank(a) or not isinstance(a, str):
                        continue
                    tj = ri + offset
                    if not 0 <= tj < len(rows):
                        continue
                    t = rows[tj][tci]
                    if is_blank(t):
                        continue
                    total += 1
                    if (normalise(a),
                            normalise(self.clean_title(t)[0])) in self.known_pairs:
                        hits += 1
                scores[offset] = (hits, total)

            aligned_hits = scores[0][0]
            best_offset, (best_hits, best_total) = max(
                scores.items(), key=lambda kv: (kv[1][0], -abs(kv[0])))
            if best_offset == 0 or best_hits <= aligned_hits:
                continue
            self.misaligned[sheet_name] = {
                "column": aci,
                "aligned_hits": aligned_hits,
                "best_offset": best_offset,
                "best_hits": best_hits,
                "considered": scores[best_offset][1],
            }
            roles["artist"] = None
            roles["artist_inferred"] = False
            roles["artist_misaligned_column"] = aci

    def log_misaligned_columns(self):
        """Every cell of a dropped column is logged, with the reason required by review."""
        for sheet_name, finding in sorted(self.misaligned.items()):
            headers, rows = read_sheet(self.wb[sheet_name])
            ci = finding["column"]
            for ri, row in enumerate(rows):
                if is_blank(row[ci]):
                    continue
                self.dropped_attributions += 1
                self.unresolve(
                    sheet_name, cell_ref(ci, ri), row[ci],
                    "column %s is offset relative to titles (sort desync) - artist "
                    "attribution unreliable; %d of %d values match a known artist/title "
                    "pair at an offset of %+d but only %d match as aligned, so the column "
                    "is dropped rather than shifted - a wrong repair is worse than none"
                    % (get_column_letter(ci + 1), finding["best_hits"],
                       finding["considered"], finding["best_offset"],
                       finding["aligned_hits"]))

    def detect_special_columns(self):
        """Three unheaded column shapes that carry real data and would otherwise be lost.

        - a combined 'Title - Artist' column ('Radiant Lanterns 2022', 'Sheet2'), which on
          two rows is the ONLY populated cell and so is the sole record of that song;
        - an unheaded position series ('29-7-23', '9-9-23'), which is the ordering those
          tabs were otherwise flagged as missing;
        - an unheaded gig-key column ('5-7-24').
        """
        self.combined_columns = collections.defaultdict(set)
        self.unheaded_order_columns = collections.defaultdict(set)
        self.unheaded_key_columns = collections.defaultdict(set)
        for sheet_name in self.wb.sheetnames:
            headers, rows = read_sheet(self.wb[sheet_name])
            roles = self.sheet_columns[sheet_name]
            for ci, header in enumerate(headers):
                if header.strip():
                    continue
                if ci in (roles.get("title"), roles.get("artist")):
                    continue
                if self.is_dropped_column(sheet_name, ci):
                    continue
                values = [r[ci] for r in rows if not is_blank(r[ci])]
                if len(values) < 5:
                    continue
                texts = [str(v) for v in values if isinstance(v, str)]
                if texts and len(texts) > len(values) * 0.8:
                    split = [t for t in texts if " - " in t]
                    if len(split) > len(texts) * 0.8:
                        self.combined_columns[sheet_name].add(ci)
                        continue
                    keys = [t for t in texts if classify_key(t).kind == "plain"]
                    if len(keys) > len(texts) * 0.8:
                        self.unheaded_key_columns[sheet_name].add(ci)
                    continue
                numbers = [v for v in values
                           if isinstance(v, (int, float)) and not isinstance(v, bool)]
                if len(numbers) == len(values) and len(set(numbers)) > len(numbers) * 0.8:
                    if all(0 <= v <= 500 for v in numbers):
                        self.unheaded_order_columns[sheet_name].add(ci)

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

        # Real people the user added. Their only attributions came from the R/T/P initial
        # expansion, which has been withdrawn under 'sparse beats wrong' — but withdrawing
        # a guess must not delete the person the guess was about.
        for name in USER_ADDED_PERFORMERS:
            self.performer_marks.append({
                "worksheet": "(user-added)", "cell": "-", "source": "user-added",
                "column": "-", "performer": name, "is_lead": 1, "title": "", "artist": "",
                "raw": "", "flag": "PERFORMER-NO-ATTRIBUTIONS",
                "note": "added deliberately by the user and kept as a performer row. It "
                        "has no songs: the single-letter initials that would have "
                        "attributed them were never confirmed, and a missing attribution "
                        "is recoverable where a wrong one is not. The raw letters are in "
                        "Unresolved if this is ever ruled on.",
            })

        for record in self.setlists:
            header = record["in_sheet_header"]
            if not header or not re.match(r"^[A-Z][a-z]+ [A-Z][a-z]+$", header.strip()):
                continue
            # USER RULING: binned in the review pass. `Kendra Piper` is the header cell of
            # a Title column, which is why [R13] mistook it for a singer column.
            if normalise(header) in BINNED_PERFORMERS:
                self.unresolve(record["worksheet"], "in-sheet header", header,
                               "user ruled not a performer")
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

    def log_orphan_row(self, sheet_name, headers, lower, row, ri):
        """Cells on a row with no song title. Nothing can consume them; all are logged."""
        for ci, h in enumerate(lower):
            if is_blank(row[ci]):
                continue
            if "practice" in h or "practise" in h or h == "twitch":
                kind = "practice"
            elif h in TAG_COLUMNS:
                kind = "tag"
            elif h in PERFORMER_COLUMNS or h in ANNOTATION_COLUMNS:
                kind = "performer"
            else:
                continue
            self.unresolve(sheet_name, cell_ref(ci, ri), row[ci],
                           "%s cell in column %r on a row that names no song; there is no "
                           "song_id to attach it to [R17, R14, D43]" % (kind, headers[ci]))

    # -- facts [R6] ----------------------------------------------------------------

    def read_facts(self, sheet_name, headers, lower, row, ri, ti,
                   title_for_row="", artist_for_row=""):
        facts = {}
        for ci, h in enumerate(lower):
            v = row[ci]
            if is_blank(v) or self.is_dropped_column(sheet_name, ci):
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
                # [D42] maps a-e to 1-5. The range is five, not three, precisely because
                # this extract found d x31 and e x24 alongside a/b/c.
                mapped = DIFFICULTY_MAP.get(str(v).strip().lower())
                if mapped:
                    facts["bass_difficulty"] = mapped
                else:
                    self.unresolve(sheet_name, cell_ref(ci, ri), v,
                                   "bass difficulty outside the a-e vocabulary [D42]; "
                                   "no mapping asserted")
            elif h == "range":
                mapped = {"h": 1, "l": 0}.get(str(v).strip().lower())
                if mapped is not None:
                    # Held aside for the owner's song_performer row [R15, D27], NOT put
                    # on the song.
                    self.vocal_ranges.setdefault(
                        (normalise(artist_for_row), normalise(title_for_row)), mapped)
                else:
                    self.unresolve(sheet_name, cell_ref(ci, ri), v,
                                   "Range outside the H/L vocabulary [D27]")
            elif h == "wavestate patches":
                facts["keys_patch"] = str(v).strip()
            elif h == "c gtr":
                # Named verbatim in [D41] as a song_instrument fact for guitar. It holds
                # dates, so record it and say so rather than asserting a difficulty.
                facts["c_gtr"] = (v.date().isoformat()
                                  if isinstance(v, datetime.datetime) else str(v).strip())
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
            if is_blank(v) or self.is_dropped_column(sheet_name, ci):
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
                    "worksheet": sheet_name, "cell": cell_ref(ci, ri), "row": ri + 2,
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

    def is_dropped_column(self, sheet_name, ci):
        """True for a column dropped as COLUMN-MISALIGNED-SUSPECTED.

        A column whose rows are offset against the titles is unusable by EVERY reader, not
        just the artist path. The first version of this fix dropped it as an artist source
        only, and the same six names promptly reappeared as performers through the unheaded
        annotation column. Every consumer of an unheaded column asks this first.
        """
        finding = self.misaligned.get(sheet_name)
        return finding is not None and finding["column"] == ci

    def is_annotation_column(self, sheet_name, ci):
        """The unheaded column A of the gig tabs whose Title header carries the client
        name holds free-text performer annotations: FD, LV, Andy?, Andy, Kita [R16, R35].
        The same position on other tabs holds a row number, so require text content."""
        if ci != 0:
            return False
        if self.is_dropped_column(sheet_name, ci):
            return False
        roles = self.sheet_columns.get(sheet_name, {})
        if ci in (roles.get("title"), roles.get("artist")):
            return False
        if ci in self.combined_columns.get(sheet_name, ()):
            return False
        # Not conditioned on title_inferred: '15-10-22 easier' names its Title column and
        # still keeps 10 `Kita` annotations in an unheaded column A [R16].
        return sheet_name in self.text_first_columns

    @staticmethod
    def expand_person(part):
        """Resolve one half of a duet marker to a person, or None if it does not resolve.
        Single letters go through DUET_INITIALS; anything longer is already a name."""
        text = part.strip()
        if len(text) == 1 and text.isalpha():
            return DUET_INITIALS.get(text.lower())
        return text or None

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

        # USER RULING: deleted as garbage in the review pass. Not a performer.
        if normalise(text) in BINNED_PERFORMERS:
            self.unresolve(sheet_name, cell_ref(ci, ri), text,
                           "user ruled not a performer")
            return

        column_label = headers[ci] or "(unheaded)"

        # A duet marker names two people. [D58] now gives them somewhere to go: the
        # setlist_item_performer junction, ordered by position. [D58a] a pair is never a
        # performer, so the composite is split here and never becomes a performer row.
        if "/" in text:
            left, right = [p.strip() for p in text.split("/", 1)]
            if left and right:
                for position, part in ((1, left), (2, right)):
                    person = self.expand_person(part)
                    if person is None:
                        self.unresolve(sheet_name, cell_ref(ci, ri), text,
                                       "duet annotation half %r does not resolve to a "
                                       "performer" % part)
                        continue
                    self.performer_marks.append({
                        "worksheet": sheet_name, "cell": cell_ref(ci, ri),
                        "row": ri + 2, "source": "annotation", "column": column_label,
                        "performer": person, "is_lead": 1 if position == 1 else 0,
                        "position": position, "staging_only": True,
                        "title": title, "artist": artist, "raw": text,
                        "flag": "DUET-ORDER-INFERRED",
                        "note": "%r in column %r is a duet: %s at position %d. That the "
                                "pair performed it together is certain; which of them led "
                                "is NOT — position comes from the writing order, left of "
                                "the slash first, which is an inference and not something "
                                "the workbook states. [D58a] a pair is never a performer, "
                                "so the composite %r is not carried anywhere."
                                % (text, column_label, person, position, text),
                    })
                return

        # USER RULING on single-letter lead-vocal initials.
        if len(text) == 1 and text.isalpha():
            letter = text.lower()
            if letter in INITIALS_NOT_PERFORMERS:
                self.unresolve(sheet_name, cell_ref(ci, ri), text,
                               "single-letter lead-vocal initial, user ruled not a "
                               "performer")
                return
            if letter in UNCONFIRMED_INITIALS:
                # Sparse beats wrong: emit no attribution at all. The raw letter, the
                # worksheet and the cell reference are preserved here, so a future ruling
                # plus a re-run recovers every one of these without re-deriving anything.
                self.unresolve(sheet_name, cell_ref(ci, ri), text,
                               "single-letter lead-vocal initial, unconfirmed - user "
                               "prefers a missing tag to a guessed one")
                return
            if letter in INITIAL_EXPANSIONS:
                person = INITIAL_EXPANSIONS[letter]
                self.performer_marks.append({
                    "worksheet": sheet_name, "cell": cell_ref(ci, ri), "row": ri + 2,
                    "source": "annotation", "column": column_label,
                    "performer": person, "is_lead": is_lead, "title": title,
                    "artist": artist, "raw": text,
                    "flag": "INITIAL-EXPANDED-UNCONFIRMED",
                    "note": "%r in column %r read as %s, on two grounds: %s is the "
                            "workbook owner, whose bare `Lead vocal` column this mostly "
                            "is; and `W/C` is confirmed as Will/Coralie, which corroborates "
                            "W = Will directly. Still flagged so it stays visible and "
                            "correctable — the letter-to-person mapping itself was never "
                            "stated outright." % (text, column_label, person, person),
                })
                return

        # A leading marker character is almost certainly not part of the name.
        if text.startswith("*") and len(letters) > 1:
            flags.append("PERFORMER-SPELLING-COLLISION")
            notes.append("%r is read as %r. The leading asterisk is almost certainly a "
                         "marker the user added rather than part of the name, but the "
                         "workbook does not say so — it folds to the same performer under "
                         "[D17] either way, so nothing is lost if that reading is wrong."
                         % (text, text.lstrip("*").strip()))
            text = text.lstrip("*").strip()

        if len(letters) <= 2 and letters.upper() == letters:
            flags.append("ANNOTATION-NOT-OBVIOUSLY-A-NAME")
            notes.append("%r is an initialism or a role code, not obviously a name [R16]; "
                         "confirm before it becomes a performer" % text)
        if re.search(r"\d", text):
            flags.append("ANNOTATION-NOT-A-SINGLE-NAME")
            notes.append("%r contains a digit [R16]; it may name two performers, or none"
                         % text)
        if text.endswith("?"):
            flags.append("ANNOTATION-UNCERTAIN")
            notes.append("%r carries the workbook's own uncertainty [R16]; it must "
                         "collapse to the same performer as %r"
                         % (text, text.rstrip("?")))
        self.performer_marks.append({
            "worksheet": sheet_name, "cell": cell_ref(ci, ri), "row": ri + 2,
            "source": "annotation", "column": headers[ci] or "(unheaded)",
            "performer": text, "is_lead": is_lead, "title": title, "artist": artist,
            "raw": text, "flag": "; ".join(flags), "note": " | ".join(notes),
        })

    # -- tags [D19, R29] -----------------------------------------------------------

    def read_tags(self, sheet_name, headers, lower, row, ri, title, artist):
        for ci, h in enumerate(lower):
            if h not in TAG_COLUMNS or self.is_dropped_column(sheet_name, ci):
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

    def search_nxm(self):
        """[R30] Search for an NxM set-length string rather than asserting its absence.
        The claim is only worth making if the script actually looked."""
        pattern = re.compile(r"\b\d\s*[x×]\s*\d{2,3}\s*(mins?|minutes?)?\b", re.I)
        self.nxm_hits = 0
        self.nxm_examples = []
        for sheet_name in self.wb.sheetnames:
            _, rows = read_sheet(self.wb[sheet_name])
            for ri, row in enumerate(rows):
                for ci, value in enumerate(row):
                    if not isinstance(value, str):
                        continue
                    if pattern.search(value):
                        self.nxm_hits += 1
                        if len(self.nxm_examples) < 10:
                            self.nxm_examples.append(
                                "%s!%s=%r" % (sheet_name, cell_ref(ci, ri), value[:40]))

    def build_bands(self):
        """[D49a] `band` is a first-class lookup table; [R23] confirms exactly three.

        Only the three the user confirmed are emitted. `Acoustic`, `easier` and the rest
        are configurations or unclassified remainders and stay out of this table — [D49b]
        is explicit that band and venue are different dimensions and that a wrong guess
        here silently corrupts band_id for the whole history.
        """
        usage = collections.Counter()
        for record in self.setlists:
            if record["band_id"]:
                usage[record["band_id"]] += 1

        self.bands = []
        for name in CONFIRMED_BANDS:
            band_id = derived_id("band", normalise(name))
            self.bands.append({
                "band_id": band_id,
                "name": name,
                "normalised_key": normalise(name),
                "setlists": usage.get(band_id, 0),
                "source": "user-confirmed [R23]",
                "flag": "" if usage.get(band_id) else "BAND-WITH-NO-SETLIST",
                "note": "confirmed by the user as a band, not a venue or a client [R23, "
                        "D49b]; id is UUIDv5(namespace('band'), %r) [D4, D4a, D4b]"
                        % normalise(name),
            })

    def build_sets(self):
        """[R30, D51, D53] Emit one setlist_set per distinct set_no so that
        setlist_item.setlist_set_id has something to point at. Where a tab shows no set
        structure at all, default to a single set numbered 1 — a set list always has at
        least one set, and 77% of items would otherwise carry a dangling FK."""
        items_by_sheet = collections.defaultdict(list)
        for item in self.setlist_items:
            items_by_sheet[item["worksheet"]].append(item)

        self.sets = []
        for record in self.setlists:
            if record["purpose"] != "setlist":
                continue
            sheet_name = record["worksheet"]
            items = items_by_sheet.get(sheet_name, [])
            seen = sorted({item["set_no"] for item in items
                           if isinstance(item["set_no"], int)})
            defaulted = not seen
            if defaulted:
                seen = [1]
            # Items before the first divider, or on a tab whose Order says nothing, have
            # no set of their own. [D53] makes setlist_set_id the only route to a set, so
            # a blank here is a dangling FK. Put them in the first set and say so.
            fallback = seen[0]
            orphans = 0
            for item in items:
                if isinstance(item["set_no"], int):
                    continue
                item["set_no"] = fallback
                if defaulted:
                    # The whole tab has no set structure. That is reported once on the
                    # Sets row as SET-DEFAULTED; flagging every item would bury the
                    # findings that actually need a decision.
                    continue
                orphans += 1
                item["flag"] = "; ".join(
                    [f for f in item["flag"].split("; ") if f] + ["SET-ASSUMED"])
                item["note"] = " | ".join([n for n in (
                    item["note"],
                    "this tab does mark sets, but this row falls outside all of them; "
                    "assigned to set %d so setlist_set_id resolves [D53]"
                    % fallback) if n])
            for set_no in seen:
                flags, notes = ["SET-TARGET-MINUTES-NULL"], []
                notes.append("target_minutes has no source in this workbook [R30]")
                if defaulted:
                    flags.append("SET-DEFAULTED")
                    notes.append("the tab shows no set structure; defaulted to a single "
                                 "set so setlist_item.setlist_set_id resolves [D53]")
                if set_no == 0:
                    flags.append("SET-NUMBER-ZERO")
                    notes.append("set 0 is a pre-show block (first dance, walk-in) read "
                                 "from an Order integer part of 0; confirm what it means "
                                 "before importing [R32]")
                if orphans:
                    flags.append("SET-ABSORBED-ORPHAN-ITEMS")
                    notes.append("%d item(s) on this tab sat outside any set marker and "
                                 "were assigned to set %d" % (orphans, fallback))
                self.sets.append({
                    "worksheet": sheet_name,
                    "setlist_name": sheet_name,
                    "set_no": set_no,
                    "target_minutes": "",
                    "item_count": sum(1 for i in items if i["set_no"] == set_no),
                    "source": "defaulted" if defaulted else record["order_convention"],
                    "flag": "; ".join(flags),
                    "note": " | ".join(notes),
                })

    def flag_position_collisions(self):
        """[D54] `(position, id)` must be a total order. Two items sharing a position
        inside one set is a defect the build pass cannot resolve on its own, so surface
        it rather than let it through."""
        groups = collections.defaultdict(list)
        for item in self.setlist_items:
            groups[(item["worksheet"], item["set_no"], item["position"])].append(item)
        for (sheet_name, set_no, position), items in groups.items():
            if len(items) < 2:
                continue
            titles = ", ".join(sorted(i["title"] for i in items))
            for item in items:
                item["flag"] = "; ".join(
                    [f for f in item["flag"].split("; ") if f] + ["POSITION-COLLISION"])
                item["note"] = " | ".join([n for n in (
                    item["note"],
                    "%d items share set %s position %s on this tab (%s); [D54] needs a "
                    "total order, so the build pass must break this tie"
                    % (len(items), set_no, position, titles)) if n])

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

        finding = self.misaligned.get(sheet_name)
        if finding:
            flags.append("COLUMN-MISALIGNED-SUSPECTED")
            notes.append("column %s reads as artists but is offset against the titles: "
                         "%d of %d values match a known artist/title pair at %+d rows, "
                         "against %d aligned. The titles on this tab are sorted and the "
                         "column was not carried with them. All %s attributions are "
                         "dropped, not shifted — one value does not fit the offset, and a "
                         "wrong repair is worse than none."
                         % (get_column_letter(finding["column"] + 1),
                            finding["best_hits"], finding["considered"],
                            finding["best_offset"], finding["aligned_hits"],
                            get_column_letter(finding["column"] + 1)))

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

        order_kind, order_note, set_numbers = self.classify_order(
            sheet_name, headers, lower, rows, roles)
        if order_kind in ("degenerate", "ambiguous", "absent", "unreliable"):
            if purpose == "setlist":
                flags.append("ORDER-UNCLASSIFIABLE")
        notes.append(order_note)

        # [R29] A tab that enumerates a few hundred songs with no ordering is a repertoire
        # snapshot, not an ordered performance. Importing it as a gig invents a gig.
        if (purpose == "setlist" and order_kind == "absent"
                and row_count > REPERTOIRE_ROW_THRESHOLD):
            flags.append("NOT-A-SETLIST-SUSPECTED")
            notes.append("%d songs and no Order column at all: this reads as a repertoire "
                         "snapshot, not an ordered performance [R29]. Treat it as a tag "
                         "over the repertoire unless you know otherwise — importing it as "
                         "a gig fabricates a performance that never happened." % row_count)

        if purpose == "setlist":
            flags.append("SET-TARGET-MINUTES-NO-SOURCE")
            notes.append("target_minutes has no source [R30]: an exhaustive regex for an "
                         "NxM set-length string over every cell of all %d tabs returned "
                         "%d matches. setlist_set rows are still created; target_minutes "
                         "stays NULL." % (len(self.wb.sheetnames), self.nxm_hits))

        # [D49a] band_id is set only where [R23] confirmed the name. Everywhere else it
        # stays null and the remainder keeps its existing REMAINDER-UNCLASSIFIED flag —
        # guessing here corrupts band_id for the whole history with no way to catch it.
        band = confirmed_band(remainder) if purpose == "setlist" else None
        band_id = derived_id("band", normalise(band)) if band else ""
        if band:
            notes.append("band_id set to the confirmed band %r [R23, D49a]" % band)

        return {
            "worksheet": sheet_name,
            "purpose": purpose,
            "performed_on": performed_on or "",
            "date_source": how,
            "remainder": remainder,
            "proposed_classification": classification,
            "confidence": confidence,
            "band": band or "",
            "band_id": band_id,
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

    def classify_order(self, sheet_name, headers, lower, rows, roles):
        """[R32] Disambiguate the Order column per tab. Every Order column in this workbook
        encodes position, never duration [R33] — there is no duration branch because there
        is no duration source.

        Contiguity and monotonicity are TESTED, not assumed. A bare range check
        misclassifies `49, 50, 60, 99, 101…230` as hundreds-style, which fabricates a
        set 0 and produces colliding positions, breaking [D54]'s requirement that
        `(position, id)` be a total order.
        """
        oi = None
        for ci, h in enumerate(lower):
            if h in ("order", "position"):
                oi = ci
                break
        if oi is None:
            # '29-7-23' and '9-9-23' keep a real 1.01/1.02/1.03 series in an unheaded
            # column. Without this they were flagged as having no ordering at all.
            candidates = sorted(self.unheaded_order_columns.get(sheet_name, ()))
            if candidates:
                oi = candidates[-1]
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

        # Divider rows ('SET 2') and set-header rows carry the round hundreds 100/200/300.
        # They are set markers, not item positions, and counting them as items would
        # manufacture repeats that are not really repeats.
        ti_local, ai_local = roles.get("title"), roles.get("artist")
        values, header_values = [], []
        for r in rows:
            v = r[oi]
            if not isinstance(v, (int, float)) or isinstance(v, bool):
                continue
            title_cell = r[ti_local] if ti_local is not None else None
            artist_cell = r[ai_local] if ai_local is not None else None
            is_divider = any(isinstance(c, str) and SET_DIVIDER.match(c)
                             for c in (title_cell, artist_cell))
            if is_divider or (float(v).is_integer() and v >= 100 and v % 100 == 0):
                header_values.append(v)
                continue
            if is_blank(title_cell):
                continue
            values.append(v)
        for v in header_values:
            if float(v).is_integer() and v >= 100:
                set_numbers.add(int(v) // 100)
        if not values:
            return ("absent", "Order column is empty [R32]", set_numbers)

        lo, hi = min(values), max(values)
        distinct = len(set(values))
        repeated = len(values) - distinct

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
            # [R32] Test the properties instead of asserting them.
            below_hundred = sorted({v for v in values if v < 100})
            if repeated or below_hundred:
                reasons = []
                if below_hundred:
                    reasons.append("%d value(s) below 100 (%s), which a hundreds reading "
                                   "would put in a fabricated set 0"
                                   % (len(below_hundred),
                                      ", ".join("%g" % v for v in below_hundred[:6])))
                if repeated:
                    duplicates = sorted({v for v in values if values.count(v) > 1})
                    reasons.append("%d repeated value(s) (%s), which would collide as "
                                   "positions and break [D54]'s total order"
                                   % (repeated,
                                      ", ".join("%g" % v for v in duplicates[:6])))
                return ("unreliable",
                        "Order runs %g..%g and looks hundreds-style, but %s. Refusing to "
                        "derive set_no or position from it [R32]."
                        % (lo, hi, "; and ".join(reasons)), set_numbers)

            # Every remainder must be a real position within its hundred. A remainder of 0
            # would be a set header that slipped through; anything outside 1..99 is not a
            # position at all. Row order is deliberately NOT tested — on these tabs the
            # Order column IS the order and the rows are not stored sorted.
            bad = sorted({v for v in values if not 1 <= (v - 100 * (int(v) // 100)) < 100})
            if bad:
                return ("unreliable",
                        "Order runs %g..%g and looks hundreds-style, but %d value(s) (%s) "
                        "leave no valid position within their hundred. Refusing to derive "
                        "set_no or position [R32]."
                        % (lo, hi, len(bad), ", ".join("%g" % v for v in bad[:6])),
                        set_numbers)
            for v in values:
                set_numbers.add(int(v) // 100)
            return ("hundreds set*100+position",
                    "Order runs %g..%g; the hundreds digit is the set and the remainder is "
                    "the position [R31]. Verified across %d item rows: no repeated value, "
                    "no value below 100, every remainder in 1..99."
                    % (lo, hi, len(values)), set_numbers)

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
                    item_pool_flag = False
                    if isinstance(order_value, (int, float)):
                        if record["order_convention"].startswith("hundreds"):
                            set_no = int(order_value) // 100
                            position = order_value - 100 * set_no
                            if position == 0:
                                # A round hundred (200, 300, 400) marks set membership
                                # with no position: a pool of songs assigned to that set
                                # but not yet ordered. Keep the set, do not invent an
                                # order, and say which it is.
                                position = sequence
                                item_pool_flag = True
                        elif record["order_convention"].startswith("decimal"):
                            set_no = int(order_value)
                            position = round(order_value - set_no, 4)
                        # Where the convention was rejected, the Order value is NOT used
                        # as a position: it is exactly the column classify_order refused
                        # to trust. Row sequence is the only signal left, and it is at
                        # least a total order [D54].
                    if si is not None and isinstance(row[si], (int, float)):
                        set_no = int(row[si])

                    # None, not "", so a legitimate transpose of 0 is distinguishable
                    # from "no transpose stated".
                    transpose = None
                    item_flags, item_notes = [], []
                    if normalise(str(row[ti])) in self.medley_titles:
                        item_flags.append("MEDLEY-NOT-A-SONG")
                        item_notes.append("the user ruled this a medley rather than a "
                                          "song, so it has no row in Songs and this item "
                                          "has no song_id to point at")
                    if item_pool_flag:
                        item_flags.append("POSITION-UNSPECIFIED")
                        item_notes.append("Order %g names set %d but gives no position "
                                          "within it; row order used as a placeholder "
                                          "[R31]" % (order_value, set_no))
                    if transpose_i is not None and not is_blank(row[transpose_i]):
                        raw = str(row[transpose_i]).strip()
                        m = re.match(r"^([+-]?\d+(?:\.\d+)?)$", raw)
                        if m:
                            transpose = int(float(m.group(1)))
                            if not raw.startswith(("+", "-")):
                                item_flags.append("TRANSPOSE-SIGN-UNSTATED")
                                item_notes.append("%r states no sign; read as %+d, but the "
                                                  "direction is not stated in the workbook"
                                                  % (raw, transpose))
                        else:
                            item_flags.append("TRANSPOSE-UNPARSEABLE")
                            item_notes.append("%r is not a signed semitone count [R8]" % raw)
                            self.unresolve(sheet_name, cell_ref(transpose_i, ri), raw,
                                           "transposition column value is not a signed "
                                           "semitone count [R8]")

                    key_reading = classify_key(row[ki]) if ki is not None else None
                    if key_reading is not None and key_reading.offset is not None:
                        if transpose is None:
                            transpose = key_reading.offset
                            item_flags.append("TRANSPOSE-FROM-KEY-CELL")
                            item_notes.append("offset taken from the Key cell %r [R8]"
                                              % str(row[ki]).strip())
                        # Carry the rule 7 ambiguity through. The human resolving this row
                        # must see that the notation itself is contested, not just a
                        # tidy asserted number.
                        for flag in [f for f in key_reading.flag.split("; ") if f]:
                            if flag not in item_flags:
                                item_flags.append(flag)
                        if key_reading.note:
                            item_notes.append(key_reading.note)

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
                        "transpose": "" if transpose is None else transpose,
                        "tempo_on_tab": row[tempo_i] if tempo_i is not None
                                        and isinstance(row[tempo_i], (int, float)) else "",
                        "flag": "; ".join(item_flags),
                        "note": " | ".join(item_notes),
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

    # [D28a] The placeholder every unattributed song points at. It is not a workbook
    # spelling, so it is appended rather than folded — and it is deliberately excluded
    # from [R1]'s 288/278 census, which counts the Artist column only.
    if normalise(UNKNOWN_ARTIST_NAME) not in groups:
        artists.append({
            "artist_id": UNKNOWN_ARTIST_ID,
            "canonical_name": UNKNOWN_ARTIST_NAME,
            "sort_name": UNKNOWN_ARTIST_NAME,
            "normalised_key": normalise(UNKNOWN_ARTIST_NAME),
            "spelling_count": 0,
            "total_occurrences": 0,
            "variants": "",
            "flag": "SEEDED-PLACEHOLDER",
            "note": "seeded row from artist.sq, not a workbook spelling [D28a]; every "
                    "song the workbook never attributes points here so artist_id can be "
                    "NOT NULL. Not counted in [R1]'s artist census.",
        })
    return artists


def fold_songs(extract, artists_by_key):
    """[R5, R6] Union all tabs, dedupe, prefer master-tab facts."""
    grouped = collections.defaultdict(lambda: {
        "titles": collections.Counter(), "artists": collections.Counter(),
        "rows": 0, "sheets": set(), "backfilled": 0,
    })
    # Two tabs attributing one title to different artists is either a cover, two different
    # songs sharing a name, or a straight mistake — the workbook has 'Radiohead' against
    # "Don't Stop Me Now" on one tab and 'Queen' on the master. The migration cannot tell
    # them apart, so it must not merge them and must not stay silent.
    # Keyed on the NORMALISED artist: 'Kaiser Chiefs' vs 'The Kaiser Chiefs' is a spelling
    # collision that [D17] already folds and the Artists sheet already reports. Counting
    # those here would bury the real disagreements under six false ones.
    attributions = collections.defaultdict(lambda: collections.defaultdict(set))
    for row in extract.song_rows:
        if row["artist"] and not row.get("artist_backfilled"):
            attributions[normalise(row["title"])][normalise(row["artist"])].add(
                (row["artist"], row["worksheet"]))

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
            # [D28a] artist_id is NOT NULL because [D4d] makes it half the song's
            # canonical key. The placeholder is a structural requirement, NOT a
            # resolution — the flag stays so the user still sees these rows.
            flags.append("ARTIST-MISSING")
            notes.append("no artist anywhere in the workbook; attached to the seeded "
                         "'%s' row (%s) rather than a null FK [D28a]. This is a "
                         "placeholder, not an answer — the real artist is still unknown."
                         % (UNKNOWN_ARTIST_NAME, UNKNOWN_ARTIST_ID))
            artist = UNKNOWN_ARTIST_NAME
            artist_id = UNKNOWN_ARTIST_ID
        else:
            artist_id = artists_by_key.get(na, "")

        rivals = attributions.get(nt, {})
        if len(rivals) > 1:
            flags.append("ARTIST-DISAGREEMENT")
            described = []
            for _, pairs in sorted(rivals.items()):
                spelling = sorted({p[0] for p in pairs})[0]
                sheets = sorted({p[1] for p in pairs})
                described.append("%s on %d tab%s (%s)"
                                 % (spelling, len(sheets), "" if len(sheets) == 1 else "s",
                                    ", ".join(sheets[:4])
                                    + (", …" if len(sheets) > 4 else "")))
            notes.append("the workbook attributes this title to %d different artists: %s. "
                         "They are kept as separate songs — a cover and a mistake look "
                         "identical from here — but at most one of them is right."
                         % (len(rivals), "; ".join(described)))

        if g["backfilled"]:
            flags.append("ARTIST-BACKFILLED-BY-TITLE")
            notes.append("%d of %d rows for this song came from a tab with no Artist "
                         "column [R21]; the artist was resolved by title from the rest of "
                         "the workbook, not read from those rows" % (g["backfilled"],
                                                                     g["rows"]))

        facts = dict(extract.master_facts.get(key, {}))
        from_master = key in extract.master_facts
        # [R6] Prefer the master tab, fall back to the most common value across the other
        # tabs. That fallback is per FIELD, not per song: the master tab has no Feel,
        # BV Comments or Guitar comments column at all, so a song present in the master
        # would otherwise lose facts that only a gig tab records.
        merged = collections.defaultdict(collections.Counter)
        for other in extract.tab_facts.get(key, []):
            for name, value in other.items():
                if isinstance(value, list):
                    for item in value:
                        merged[name][item] += 1
                    continue
                merged[name][value] += 1
        borrowed = []
        for name, counter in merged.items():
            if name in facts:
                continue
            if name == "instrument_notes":
                facts[name] = sorted(counter)
            else:
                facts[name] = sorted(counter.items(),
                                     key=lambda kv: (-kv[1], str(kv[0])))[0][0]
            borrowed.append(name)
        if borrowed and from_master:
            notes.append("the master tab records no %s for this song; taken by majority "
                         "across %d gig tabs [R6]"
                         % (", ".join(sorted(borrowed)), len(extract.tab_facts.get(key, []))))
        elif not from_master and extract.tab_facts.get(key):
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
            "_norm_key": key,
            # [D4d] the song canonical key is artist_id + "/" + normalise(title). The
            # separator is ratified, and `artist_id` is the artist's DERIVED ID, never its
            # normalised name — the Kotlin core and this script must concatenate
            # identically or the same song gets two ids and the devices never converge.
            "song_id": derived_id("song", "%s/%s" % (artist_id, nt)),
            "title": title,
            "artist": artist,
            "artist_id": artist_id,
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
            "groove": facts.get("groove", ""),
            "keys_patch": facts.get("keys_patch", ""),
            "instrument_notes": " | ".join(facts.get("instrument_notes", [])),
            "guitar_worked_on": facts.get("c_gtr", ""),
            "mashup_note": facts.get("mashup_note", ""),
            "row_instances": g["rows"],
            "worksheets": len(g["sheets"]),
            "from_master_tab": "yes" if from_master else "no",
            "flag": "; ".join(sorted(set(flags))),
            "note": " | ".join(notes),
        })
    return songs


def build_setlist_item_performers(extract, performers_by_key, names_by_key):
    """[D58] who a set list item is STAGED WITH on one night, ordered by position.

    Distinct from `song_performer`, which records who KNOWS a song [D58a]. Neither is
    derived from the other and there is no is_duet flag anywhere — any song can be staged
    as a duet, so duet-ness is a property of the performance, not the song.

    Sourced from the free-text annotation columns on gig tabs (`LV`, `Lead vocal`, and the
    unheaded column A of the client-header tabs). The singer-capability columns
    (`Coralie Vox`, `Sophie-Mae Vocal`, …) are NOT used here — they say who can sing a
    song, which is the other table's question.
    """
    items = {}
    for item in extract.setlist_items:
        items[(item["worksheet"], item["row"])] = item

    rows = {}
    for mark in extract.performer_marks:
        if mark.get("source") != "annotation" or not mark.get("row"):
            continue
        item = items.get((mark["worksheet"], mark["row"]))
        if item is None:
            continue
        key = normalise(mark["performer"].rstrip("?"))
        if not key:
            continue
        ident = (mark["worksheet"], mark["row"], key)
        position = mark.get("position", 1)
        if ident in rows:
            existing = rows[ident]
            existing["position"] = min(existing["position"], position)
            merged = [f for f in existing["flag"].split("; ") if f]
            for flag in [f for f in mark["flag"].split("; ") if f]:
                if flag not in merged:
                    merged.append(flag)
            existing["flag"] = "; ".join(merged)
            if mark["note"] and mark["note"] not in existing["note"]:
                existing["note"] = " | ".join(
                    [n for n in (existing["note"], mark["note"]) if n])
            continue
        rows[ident] = {
            "setlist": mark["worksheet"],
            "item_key": "%s!%d" % (mark["worksheet"], mark["row"]),
            "item_row": mark["row"],
            "song": item["title"],
            "artist": item["artist"],
            "performer": names_by_key.get(key, mark["performer"].rstrip("?")),
            "performer_id": performers_by_key.get(key, ""),
            "position": position,
            "source": "%s:%s" % (mark["source"], mark["column"]),
            "flag": mark["flag"],
            "note": mark["note"],
        }

    ordered = sorted(rows.values(),
                     key=lambda r: (r["setlist"], r["item_row"], r["position"],
                                    normalise(r["performer"])))
    # Renumber within each item so positions are 1..n and contiguous, which is what makes
    # a printed set list deterministic rather than alphabetical by accident [D58].
    by_item = collections.defaultdict(list)
    for row in ordered:
        by_item[(row["setlist"], row["item_row"])].append(row)
    for group in by_item.values():
        for index, row in enumerate(group, start=1):
            row["position"] = index
        if len(group) < 2:
            continue
        if any("DUET-ORDER-INFERRED" in row["flag"] for row in group):
            continue
        # Two columns on one row named two different people. That they both performed it
        # is what the workbook says; which of them led is not, and [D58] gives position 1
        # a meaning. Say so rather than letting the arbitrary order read as a fact.
        names = ", ".join("%d:%s" % (row["position"], row["performer"]) for row in group)
        for row in group:
            row["flag"] = "; ".join(
                [f for f in row["flag"].split("; ") if f] + ["MULTI-PERFORMER-ORDER-UNKNOWN"])
            row["note"] = " | ".join([n for n in (
                row["note"],
                "%d performers are named on this row by different columns (%s). The "
                "workbook does not say which of them led, so position here is ordering "
                "only and carries no claim about the lead [D58]."
                % (len(group), names)) if n])
    return ordered


def build_song_performers(extract, performers_by_key, names_by_key, song_ids):
    """[R14] one row per non-empty cell in a singer column, deduped per (song, performer).
    [R15, D27] `vocal_range` lives HERE, on the owner's row, never on the song: range is
    only meaningful for a particular voice."""
    owner_key = normalise("Will")
    rows = {}
    for mark in extract.performer_marks:
        if not mark.get("title"):
            continue
        # [D58a] a duet split is a staging fact about one night. Letting it create a
        # song_performer row would derive capability from staging, which is exactly the
        # merge the decision forbids.
        if mark.get("staging_only"):
            continue
        key = normalise(mark["performer"].rstrip("?"))
        if not key:
            continue
        ident = (normalise(mark["artist"]), normalise(mark["title"]), key)
        if ident in rows:
            existing = rows[ident]
            if mark["is_lead"]:
                existing["is_lead"] = 1
            # Merge the flags rather than keeping only the first cell's. An expanded
            # initial that lands on a song the same performer already holds must still
            # carry INITIAL-EXPANDED-UNCONFIRMED, or the user cannot see and correct it.
            merged = [f for f in existing["flag"].split("; ") if f]
            for flag in [f for f in mark["flag"].split("; ") if f]:
                if flag not in merged:
                    merged.append(flag)
            existing["flag"] = "; ".join(merged)
            if mark["note"] and mark["note"] not in existing["note"]:
                existing["note"] = " | ".join(
                    [n for n in (existing["note"], mark["note"]) if n])
            continue
        rows[ident] = {
            "song": mark["title"],
            # Match the Songs sheet: an unattributed song reads 'Unknown Artist' there
            # [D28a], so it must read the same here or the two sheets appear to disagree.
            "artist": mark["artist"] or UNKNOWN_ARTIST_NAME,
            # The folded canonical name, not this cell's spelling: '*Stef' and 'Stef' are
            # one performer under [D17] and must read as one here too.
            "performer": names_by_key.get(key, mark["performer"].rstrip("?")),
            "performer_id": performers_by_key.get(key, ""),
            # [D4, amended] every junction: UUIDv5(namespace(table), fk_a + "/" + fk_b).
            "song_performer_id": junction_id(
                "song_performer", song_ids.get(ident[:2], ""),
                performers_by_key.get(key, "")) if song_ids.get(ident[:2]) else "",
            "is_lead": mark["is_lead"], "vocal_range": "",
            "source": "%s:%s" % (mark["source"], mark["column"]),
            "flag": mark["flag"], "note": mark["note"],
        }

    ranges = extract.vocal_ranges
    for ident, row in rows.items():
        if ident[2] != owner_key:
            continue
        value = ranges.get((ident[0], ident[1]))
        if value is None:
            continue
        row["vocal_range"] = value
        row["note"] = " | ".join([n for n in (
            row["note"],
            "vocal_range from the Range column, attached to the owner's song_performer "
            "row and not to the song [R15, D27]") if n])

    orphans = set(ranges) - {(i[0], i[1]) for i in rows if i[2] == owner_key}
    for artist_key, title_key in sorted(orphans):
        extract.unresolve("(multiple)", "Range column", str(ranges[(artist_key, title_key)]),
                          "Range value for %r has no owner song_performer row to attach to "
                          "[R15, D27]; a range without a voice is meaningless" % title_key)

    return sorted(rows.values(),
                  key=lambda r: (normalise(r["artist"]), normalise(r["song"]),
                                 normalise(r["performer"])))


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


def build_tags(extract, song_ids):
    """[D19] flag columns, plus the bass-vox tag from the Bass-vox Rep tab [R29]."""
    rows = []
    seen = set()
    for mark in extract.tag_marks:
        key = (mark["tag"], normalise(mark["artist"]), normalise(mark["title"]))
        if key in seen:
            continue
        seen.add(key)
        song_id = song_ids.get((key[1], key[2]), "")
        tag_id = derived_id("tag", normalise(mark["tag"]))
        rows.append({
            "tag": mark["tag"], "song": mark["title"],
            "artist": mark["artist"] or UNKNOWN_ARTIST_NAME,
            "tag_id": tag_id,
            # [D4, amended] every junction shares one shape.
            "song_tag_id": junction_id("song_tag", song_id, tag_id) if song_id else "",
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
        song_id = song_ids.get((key[1], key[2]), "")
        tag_id = derived_id("tag", normalise("bass-vox"))
        rows.append({
            "tag": "bass-vox", "song": row["title"],
            "artist": row["artist"] or UNKNOWN_ARTIST_NAME,
            "tag_id": tag_id,
            "song_tag_id": junction_id("song_tag", song_id, tag_id) if song_id else "",
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

def account_cells(extract):
    """Prove there is no silent loss: every non-blank cell in the workbook is either in a
    column some reader consumes, or in a deliberately-dropped triage column, or logged to
    Unresolved. Anything left over is a hole in the extractor."""
    total = 0
    in_recognised = 0
    unrecognised = 0
    for sheet_name in extract.wb.sheetnames:
        headers, rows = read_sheet(extract.wb[sheet_name])
        roles = extract.sheet_columns[sheet_name]
        recognised, triage = extract.recognised_columns(sheet_name, headers, roles)
        for row in rows:
            for ci, value in enumerate(row):
                if is_blank(value):
                    continue
                total += 1
                if ci in recognised:
                    in_recognised += 1
                elif ci not in triage:
                    unrecognised += 1
    header_cells = sum(1 for name in extract.wb.sheetnames
                       for h in read_sheet(extract.wb[name])[0] if h)
    return {
        "total_cells": total,
        "in_recognised_columns": in_recognised,
        "triage_cells": extract.triage_cells,
        "unrecognised_cells": unrecognised,
        "unresolved_rows": len(extract.unresolved),
        "header_cells": header_cells,
        "unaccounted": total - in_recognised - extract.triage_cells - unrecognised,
    }


def build_counts(extract, artists, songs, events, accounting):
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

    order_kinds = collections.Counter()
    for record in extract.setlists:
        kind = record["order_convention"]
        order_kinds[kind] += 1
        headers = read_sheet(extract.wb[record["worksheet"]])[0]
        lower = [h.lower() for h in headers]
        if "order" in lower or "position" in lower:
            order_kinds["_any"] += 1
            if kind == "absent":
                order_kinds["absent-with-column"] += 1
        elif extract.unheaded_order_columns.get(record["worksheet"]):
            order_kinds["_unheaded"] += 1

    found = {
        ("workbook", "worksheets"): len(extract.wb.sheetnames),
        ("workbook", "rows on export"): total_rows,
        ("artists", "distinct artist strings"): len(extract.artist_column_cells),
        ("artists", "artists after normalisation"): len(
            {normalise(a) for a in extract.artist_column_cells}),
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
        ("practice", "date typos landing in 1900"): implausible["1900"],
        ("practice", "date typos landing in 2099"): implausible["2099"],
        ("order", "tabs with an Order/Position column"): order_kinds["_any"],
        ("order", "hundreds-style tabs"): order_kinds["hundreds set*100+position"],
        ("order", "decimal set.position tabs"): order_kinds["decimal set.position"],
        ("order", "plain counter + Set tabs"): order_kinds["plain counter + Set column"],
        ("order", "degenerate or unreliable tabs"): (order_kinds["degenerate"]
                                                     + order_kinds["unreliable"]),
        ("order", "empty Order tabs"): order_kinds["absent-with-column"],
        ("order", "unheaded position columns recovered"): order_kinds["_unheaded"],
        ("cells", "cells left unaccounted for"): accounting["unaccounted"],
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

    # Loud refusal beats a quiet overwrite: review.xlsx is a merge base carrying the
    # user's hand resolutions in a derived copy, and it cannot be regenerated.
    target = os.path.normcase(os.path.abspath(args.target))
    for forbidden in FORBIDDEN_OUTPUTS:
        if target == os.path.normcase(os.path.abspath(forbidden)):
            sys.exit("FATAL: %s is the merge base for the user's edited copy and must not "
                     "be overwritten. Write to review-next.xlsx instead." % args.target)

    print("reading %s" % args.source)
    wb = openpyxl.load_workbook(args.source, data_only=True)

    extract = Extract(wb)
    extract.load_master()
    extract.scan()
    extract.resolve_missing_artists()
    extract.search_nxm()
    extract.build_setlists()
    extract.annotate_setlist_relationships()
    extract.seed_declared_performers()
    extract.build_setlist_items()
    extract.build_sets()
    extract.build_bands()
    extract.flag_position_collisions()

    artists = fold_artists(extract.artist_cells)
    artists_by_key = {a["normalised_key"]: a["artist_id"] for a in artists}
    songs = fold_songs(extract, artists_by_key)
    performers = fold_performers(extract)
    performers_by_key = {p["normalised_key"]: p["performer_id"] for p in performers}
    names_by_key = {p["normalised_key"]: p["name"] for p in performers}
    song_ids = {s["_norm_key"]: s["song_id"] for s in songs}
    song_performers = build_song_performers(extract, performers_by_key, names_by_key,
                                            song_ids)
    item_performers = build_setlist_item_performers(extract, performers_by_key,
                                                    names_by_key)
    events = build_practice_events(extract)
    tags = build_tags(extract, song_ids)
    accounting = account_cells(extract)
    counts = build_counts(extract, artists, songs, events, accounting)

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
                ["song_id", "title", "artist", "artist_id", "tonal_centre", "key_signature",
                 "source_key_spelling", "tonality_note", "tempo_bpm", "duration_seconds",
                 "decade", "loop_length", "chord_count", "chord_pattern",
                 "bass_difficulty", "groove", "keys_patch", "instrument_notes",
                 "guitar_worked_on", "mashup_note", "row_instances",
                 "worksheets", "from_master_tab", "flag", "note"])
    write_sheet(out, "Artists", artists,
                ["artist_id", "canonical_name", "sort_name", "normalised_key",
                 "spelling_count", "total_occurrences", "variants", "flag", "note"])
    write_sheet(out, "Performers", performers,
                ["performer_id", "name", "normalised_key", "spellings", "lead_marks",
                 "backing_marks", "total_marks", "sources", "flag", "note"])
    write_sheet(out, "Bands", extract.bands,
                ["band_id", "name", "normalised_key", "setlists", "source", "flag", "note"])
    write_sheet(out, "Setlists", extract.setlists,
                ["worksheet", "purpose", "performed_on", "date_source", "remainder",
                 "proposed_classification", "confidence", "band", "band_id",
                 "proposed_venue", "proposed_client", "in_sheet_header", "row_count",
                 "sets_seen", "order_convention", "flag", "note"])
    write_sheet(out, "Sets", extract.sets,
                ["worksheet", "setlist_name", "set_no", "target_minutes", "item_count",
                 "source", "flag", "note"])
    write_sheet(out, "SetlistItems", extract.setlist_items,
                ["worksheet", "row", "set_no", "position", "title", "artist", "key_raw",
                 "transpose", "tempo_on_tab", "flag", "note"])
    write_sheet(out, "SetlistItemPerformers", item_performers,
                ["setlist", "item_key", "item_row", "song", "artist", "performer",
                 "performer_id", "position", "source", "flag", "note"])
    write_sheet(out, "SongPerformers", song_performers,
                ["song", "artist", "performer", "performer_id", "song_performer_id",
                 "is_lead", "vocal_range", "source", "flag", "note"])
    write_sheet(out, "PracticeEvents", events,
                ["song", "artist", "instrument", "context", "date", "source_worksheets",
                 "surviving_only_on", "flag", "note"])
    write_sheet(out, "Tags", tags,
                ["tag", "song", "artist", "tag_id", "song_tag_id", "source", "flag",
                 "note"])
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
    print("CELL ACCOUNTING (no cell may vanish silently)")
    print("-" * 104)
    for label, key in (("non-blank data cells", "total_cells"),
                       ("in a column a reader consumes", "in_recognised_columns"),
                       ("in a triage column, dropped on purpose", "triage_cells"),
                       ("in no column, each one logged", "unrecognised_cells"),
                       ("rows in the Unresolved sheet", "unresolved_rows"),
                       ("UNACCOUNTED FOR", "unaccounted")):
        print("   %-42s %7d" % (label, accounting[key]))
    print()

    flagged = collections.Counter()
    for name, rows in (("Songs", songs), ("Artists", artists), ("Performers", performers),
                       ("SongPerformers", song_performers),
                       ("SetlistItemPerformers", item_performers),
                       ("Sets", extract.sets),
                       ("Bands", extract.bands),
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
