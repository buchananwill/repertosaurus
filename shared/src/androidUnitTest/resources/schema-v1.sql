-- Schema version 1 — the shape that shipped before Views.
--
-- Schema-compatibility decision S13: every schema change ships with a load test against the
-- previous version's database. This file IS that fixture. It was dumped straight out of the
-- user's real pre-Views database `songbook-2026-08-16b.db` (`user_version = 1`, 595
-- `song_performer` rows, no `saved_view`) with `SELECT sql FROM sqlite_master`, so it is
-- evidence rather than a transcription: no one typed it and it cannot quietly stop describing
-- what version 1 was.
--
-- Tables first, then indexes. Seed rows are deliberately absent — the fixture is a *shape*, and
-- the 1 -> 2 migration supplies the one row it depends on with INSERT OR IGNORE.
--
-- Statements are separated by a line containing only `--;`.

--;
CREATE TABLE artist (
    id          TEXT NOT NULL PRIMARY KEY,
    name        TEXT NOT NULL,
    sort_name   TEXT NOT NULL,

    updated_at  TEXT NOT NULL,
    deleted_at  TEXT,
    device_id   TEXT NOT NULL,

    CHECK (updated_at GLOB '????-??-??T??:??:??.???Z'),
    CHECK (deleted_at IS NULL OR deleted_at GLOB '????-??-??T??:??:??.???Z')
);

--;
CREATE TABLE artist_alias (
    id          TEXT NOT NULL PRIMARY KEY,
    artist_id   TEXT NOT NULL,
    alias       TEXT NOT NULL,

    updated_at  TEXT NOT NULL,
    deleted_at  TEXT,
    device_id   TEXT NOT NULL,

    FOREIGN KEY (artist_id) REFERENCES artist(id),
    CHECK (updated_at GLOB '????-??-??T??:??:??.???Z'),
    CHECK (deleted_at IS NULL OR deleted_at GLOB '????-??-??T??:??:??.???Z')
);

--;
CREATE TABLE band (
    id          TEXT NOT NULL PRIMARY KEY,
    name        TEXT NOT NULL,
    notes       TEXT,

    updated_at  TEXT NOT NULL,
    deleted_at  TEXT,
    device_id   TEXT NOT NULL,

    CHECK (updated_at GLOB '????-??-??T??:??:??.???Z'),
    CHECK (deleted_at IS NULL OR deleted_at GLOB '????-??-??T??:??:??.???Z')
);

--;
CREATE TABLE groove (
    id          TEXT NOT NULL PRIMARY KEY,
    name        TEXT NOT NULL,

    updated_at  TEXT NOT NULL,
    deleted_at  TEXT,
    device_id   TEXT NOT NULL,

    CHECK (updated_at GLOB '????-??-??T??:??:??.???Z'),
    CHECK (deleted_at IS NULL OR deleted_at GLOB '????-??-??T??:??:??.???Z')
);

--;
CREATE TABLE instrument (
    id          TEXT NOT NULL PRIMARY KEY,
    name        TEXT NOT NULL,

    updated_at  TEXT NOT NULL,
    deleted_at  TEXT,
    device_id   TEXT NOT NULL,

    CHECK (updated_at GLOB '????-??-??T??:??:??.???Z'),
    CHECK (deleted_at IS NULL OR deleted_at GLOB '????-??-??T??:??:??.???Z')
);

--;
CREATE TABLE performer (
    id          TEXT NOT NULL PRIMARY KEY,
    name        TEXT NOT NULL,
    notes       TEXT,

    updated_at  TEXT NOT NULL,
    deleted_at  TEXT,
    device_id   TEXT NOT NULL,

    CHECK (updated_at GLOB '????-??-??T??:??:??.???Z'),
    CHECK (deleted_at IS NULL OR deleted_at GLOB '????-??-??T??:??:??.???Z')
);

--;
CREATE TABLE practice_context (
    id          TEXT NOT NULL PRIMARY KEY,
    name        TEXT NOT NULL,

    updated_at  TEXT NOT NULL,
    deleted_at  TEXT,
    device_id   TEXT NOT NULL,

    CHECK (updated_at GLOB '????-??-??T??:??:??.???Z'),
    CHECK (deleted_at IS NULL OR deleted_at GLOB '????-??-??T??:??:??.???Z')
);

--;
CREATE TABLE practice_event (
    id             TEXT NOT NULL PRIMARY KEY,
    song_id        TEXT NOT NULL,
    logged_on      TEXT NOT NULL,
    instrument_id  TEXT NOT NULL,
    context_id     TEXT,
    feel           INTEGER,
    note           TEXT,

    created_at     TEXT NOT NULL,
    device_id      TEXT NOT NULL,

    FOREIGN KEY (song_id) REFERENCES song(id),
    FOREIGN KEY (instrument_id) REFERENCES instrument(id),
    FOREIGN KEY (context_id) REFERENCES practice_context(id),
    CHECK (feel IS NULL OR feel BETWEEN 1 AND 3),
    CHECK (logged_on GLOB '????-??-??'),
    CHECK (created_at GLOB '????-??-??T??:??:??.???Z')
);

--;
CREATE TABLE practice_event_void (
    id                 TEXT NOT NULL PRIMARY KEY,
    practice_event_id  TEXT NOT NULL,

    created_at         TEXT NOT NULL,
    device_id          TEXT NOT NULL,

    FOREIGN KEY (practice_event_id) REFERENCES practice_event(id),
    CHECK (created_at GLOB '????-??-??T??:??:??.???Z')
);

--;
CREATE TABLE setlist (
    id            TEXT NOT NULL PRIMARY KEY,
    name          TEXT NOT NULL,
    performed_on  TEXT,
    band_id       TEXT,
    venue_id      TEXT,
    client        TEXT,
    notes         TEXT,

    updated_at    TEXT NOT NULL,
    deleted_at    TEXT,
    device_id     TEXT NOT NULL,

    FOREIGN KEY (band_id) REFERENCES band(id),
    FOREIGN KEY (venue_id) REFERENCES venue(id),
    CHECK (performed_on IS NULL OR performed_on GLOB '????-??-??'),
    CHECK (updated_at GLOB '????-??-??T??:??:??.???Z'),
    CHECK (deleted_at IS NULL OR deleted_at GLOB '????-??-??T??:??:??.???Z')
);

--;
CREATE TABLE setlist_item (
    id                 TEXT NOT NULL PRIMARY KEY,
    setlist_set_id     TEXT NOT NULL,
    song_id            TEXT NOT NULL,
    position           TEXT NOT NULL,
    transpose          INTEGER NOT NULL DEFAULT 0,
    tempo_override     INTEGER,
    note               TEXT,

    updated_at         TEXT NOT NULL,
    deleted_at         TEXT,
    device_id          TEXT NOT NULL,

    FOREIGN KEY (setlist_set_id) REFERENCES setlist_set(id),
    FOREIGN KEY (song_id) REFERENCES song(id),
    CHECK (LENGTH(position) > 0),
    CHECK (tempo_override IS NULL OR tempo_override > 0),
    CHECK (updated_at GLOB '????-??-??T??:??:??.???Z'),
    CHECK (deleted_at IS NULL OR deleted_at GLOB '????-??-??T??:??:??.???Z')
);

--;
CREATE TABLE setlist_item_performer (
    id               TEXT NOT NULL PRIMARY KEY,
    setlist_item_id  TEXT NOT NULL,
    performer_id     TEXT NOT NULL,
    position         INTEGER NOT NULL,

    updated_at       TEXT NOT NULL,
    deleted_at       TEXT,
    device_id        TEXT NOT NULL,

    FOREIGN KEY (setlist_item_id) REFERENCES setlist_item(id),
    FOREIGN KEY (performer_id) REFERENCES performer(id),
    CHECK (position >= 1),
    CHECK (updated_at GLOB '????-??-??T??:??:??.???Z'),
    CHECK (deleted_at IS NULL OR deleted_at GLOB '????-??-??T??:??:??.???Z')
);

--;
CREATE TABLE setlist_set (
    id              TEXT NOT NULL PRIMARY KEY,
    setlist_id      TEXT NOT NULL,
    set_no          INTEGER NOT NULL,
    target_minutes  INTEGER,

    updated_at      TEXT NOT NULL,
    deleted_at      TEXT,
    device_id       TEXT NOT NULL,

    FOREIGN KEY (setlist_id) REFERENCES setlist(id),
    CHECK (set_no >= 1),
    CHECK (target_minutes IS NULL OR target_minutes > 0),
    CHECK (updated_at GLOB '????-??-??T??:??:??.???Z'),
    CHECK (deleted_at IS NULL OR deleted_at GLOB '????-??-??T??:??:??.???Z')
);

--;
CREATE TABLE song (
    id                   TEXT NOT NULL PRIMARY KEY,
    title                TEXT NOT NULL,
    artist_id            TEXT NOT NULL,
    reference_recording  TEXT,
    key_signature        INTEGER,
    tonal_centre         INTEGER,
    tonality_note        TEXT,
    tempo_bpm            INTEGER,
    duration_seconds     INTEGER,
    decade               INTEGER,
    loop_length          INTEGER,
    chord_count          INTEGER,
    chord_pattern        TEXT,
    groove_id            TEXT,
    mashup_note          TEXT,
    notes                TEXT,
    chart_url            TEXT,

    updated_at           TEXT NOT NULL,
    deleted_at           TEXT,
    device_id            TEXT NOT NULL,

    FOREIGN KEY (artist_id) REFERENCES artist(id),
    FOREIGN KEY (groove_id) REFERENCES groove(id),
    CHECK (key_signature IS NULL OR key_signature BETWEEN -7 AND 7),
    CHECK (tonal_centre IS NULL OR tonal_centre BETWEEN 0 AND 11),
    CHECK (updated_at GLOB '????-??-??T??:??:??.???Z'),
    CHECK (deleted_at IS NULL OR deleted_at GLOB '????-??-??T??:??:??.???Z')
);

--;
CREATE TABLE song_instrument (
    id             TEXT NOT NULL PRIMARY KEY,
    song_id        TEXT NOT NULL,
    instrument_id  TEXT NOT NULL,
    difficulty     INTEGER,
    patch          TEXT,
    notes          TEXT,

    updated_at     TEXT NOT NULL,
    deleted_at     TEXT,
    device_id      TEXT NOT NULL,

    FOREIGN KEY (song_id) REFERENCES song(id),
    FOREIGN KEY (instrument_id) REFERENCES instrument(id),
    CHECK (difficulty IS NULL OR difficulty BETWEEN 1 AND 5),
    CHECK (updated_at GLOB '????-??-??T??:??:??.???Z'),
    CHECK (deleted_at IS NULL OR deleted_at GLOB '????-??-??T??:??:??.???Z')
);

--;
CREATE TABLE song_performer (
    id            TEXT NOT NULL PRIMARY KEY,
    song_id       TEXT NOT NULL,
    performer_id  TEXT NOT NULL,
    is_lead       INTEGER NOT NULL DEFAULT 0,
    vocal_range   INTEGER,
    notes         TEXT,

    updated_at    TEXT NOT NULL,
    deleted_at    TEXT,
    device_id     TEXT NOT NULL,

    FOREIGN KEY (song_id) REFERENCES song(id),
    FOREIGN KEY (performer_id) REFERENCES performer(id),
    CHECK (is_lead IN (0, 1)),
    CHECK (vocal_range IS NULL OR vocal_range IN (0, 1)),
    CHECK (updated_at GLOB '????-??-??T??:??:??.???Z'),
    CHECK (deleted_at IS NULL OR deleted_at GLOB '????-??-??T??:??:??.???Z')
);

--;
CREATE TABLE song_tag (
    id          TEXT NOT NULL PRIMARY KEY,
    song_id     TEXT NOT NULL,
    tag_id      TEXT NOT NULL,

    updated_at  TEXT NOT NULL,
    deleted_at  TEXT,
    device_id   TEXT NOT NULL,

    FOREIGN KEY (song_id) REFERENCES song(id),
    FOREIGN KEY (tag_id) REFERENCES tag(id),
    CHECK (updated_at GLOB '????-??-??T??:??:??.???Z'),
    CHECK (deleted_at IS NULL OR deleted_at GLOB '????-??-??T??:??:??.???Z')
);

--;
CREATE TABLE tag (
    id          TEXT NOT NULL PRIMARY KEY,
    name        TEXT NOT NULL,

    updated_at  TEXT NOT NULL,
    deleted_at  TEXT,
    device_id   TEXT NOT NULL,

    CHECK (updated_at GLOB '????-??-??T??:??:??.???Z'),
    CHECK (deleted_at IS NULL OR deleted_at GLOB '????-??-??T??:??:??.???Z')
);

--;
CREATE TABLE venue (
    id          TEXT NOT NULL PRIMARY KEY,
    name        TEXT NOT NULL,

    updated_at  TEXT NOT NULL,
    deleted_at  TEXT,
    device_id   TEXT NOT NULL,

    CHECK (updated_at GLOB '????-??-??T??:??:??.???Z'),
    CHECK (deleted_at IS NULL OR deleted_at GLOB '????-??-??T??:??:??.???Z')
);

--;
CREATE INDEX artist_alias_alias ON artist_alias(alias);

--;
CREATE INDEX artist_alias_artist ON artist_alias(artist_id);

--;
CREATE INDEX artist_sort_name ON artist(sort_name);

--;
CREATE INDEX band_name ON band(name);

--;
CREATE INDEX groove_name ON groove(name);

--;
CREATE INDEX instrument_name ON instrument(name);

--;
CREATE INDEX performer_name ON performer(name);

--;
CREATE INDEX practice_context_name ON practice_context(name);

--;
CREATE INDEX practice_event_instrument_logged
    ON practice_event(instrument_id, logged_on DESC);

--;
CREATE INDEX practice_event_logged ON practice_event(logged_on DESC);

--;
CREATE INDEX practice_event_song_instrument_logged
    ON practice_event(song_id, instrument_id, logged_on DESC);

--;
CREATE INDEX practice_event_void_event ON practice_event_void(practice_event_id);

--;
CREATE INDEX setlist_band ON setlist(band_id);

--;
CREATE INDEX setlist_item_performer_item_position
    ON setlist_item_performer(setlist_item_id, position, id);

--;
CREATE UNIQUE INDEX setlist_item_performer_pair
    ON setlist_item_performer(setlist_item_id, performer_id);

--;
CREATE INDEX setlist_item_performer_performer
    ON setlist_item_performer(performer_id);

--;
CREATE INDEX setlist_item_set_position ON setlist_item(setlist_set_id, position, id);

--;
CREATE INDEX setlist_item_song ON setlist_item(song_id);

--;
CREATE INDEX setlist_performed_on ON setlist(performed_on DESC);

--;
CREATE INDEX setlist_set_setlist ON setlist_set(setlist_id, set_no);

--;
CREATE INDEX setlist_venue ON setlist(venue_id);

--;
CREATE INDEX song_artist ON song(artist_id);

--;
CREATE INDEX song_groove ON song(groove_id);

--;
CREATE INDEX song_instrument_instrument ON song_instrument(instrument_id);

--;
CREATE UNIQUE INDEX song_instrument_pair ON song_instrument(song_id, instrument_id);

--;
CREATE UNIQUE INDEX song_performer_pair ON song_performer(song_id, performer_id);

--;
CREATE INDEX song_performer_performer ON song_performer(performer_id);

--;
CREATE UNIQUE INDEX song_tag_pair ON song_tag(song_id, tag_id);

--;
CREATE INDEX song_tag_tag ON song_tag(tag_id);

--;
CREATE INDEX song_title ON song(title);

--;
CREATE INDEX tag_name ON tag(name);

--;
CREATE INDEX venue_name ON venue(name);
