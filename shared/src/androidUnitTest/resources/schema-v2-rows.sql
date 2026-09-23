-- Rows for the schema-2 fixture (`schema-v2.sql`), replayed over it by `SchemaV2Fixture`.
--
-- Schema-3 §3 asks the 2 -> 3 upgrade test to start from a database holding at least one voided
-- event, one event with feel, one saved View and one tombstoned View. The void is the load-bearing
-- one: `practice_event_void` references `practice_event`, so with foreign keys enforced a
-- migration that drops `practice_event` while a void exists fails (schema-3 M18).
--
-- Every value is a literal, so the test can compare ids, `created_at` and `device_id` byte for
-- byte across the migration. The ids are UUIDv4 in form and the lookup ids are the ones the
-- shared core derives, so the rows read the way a real database's do.
--
-- Statements are separated by a line containing only `--;`.

--;
INSERT INTO instrument(id, name, updated_at, deleted_at, device_id)
VALUES ('6409e4f3-5881-5293-8918-9dbafe572ac6', 'vocal', '2026-08-15T00:00:00.000Z', NULL, 'seed');

--;
INSERT INTO artist(id, name, sort_name, updated_at, deleted_at, device_id)
VALUES ('cf06771d-4e8d-53fc-83fb-359be7dfaefc', 'Unknown Artist', 'unknown artist',
        '2026-08-15T00:00:00.000Z', NULL, 'seed');

--;
INSERT INTO song(id, title, artist_id, updated_at, deleted_at, device_id)
VALUES ('8a1b2c3d-0000-4000-8000-000000000001', '9 to 5', 'cf06771d-4e8d-53fc-83fb-359be7dfaefc',
        '2026-09-01T09:00:00.000Z', NULL, 'old-phone');

--;
INSERT INTO performer(id, name, notes, updated_at, deleted_at, device_id)
VALUES ('8a1b2c3d-0000-4000-8000-0000000000aa', 'Will', NULL, '2026-09-01T09:00:00.000Z', NULL,
        'old-phone');

--;
-- A plain tap: no feel, no note.
INSERT INTO practice_event(
    id, song_id, logged_on, instrument_id, context_id, feel, note, created_at, device_id
) VALUES (
    '3f1e39c4-0593-4cc0-9f29-fa186e35295d', '8a1b2c3d-0000-4000-8000-000000000001', '2026-09-10',
    '6409e4f3-5881-5293-8918-9dbafe572ac6', NULL, NULL, NULL, '2026-09-10T18:00:00.111Z', 'old-phone'
);

--;
-- A long-press log with feel and a note.
INSERT INTO practice_event(
    id, song_id, logged_on, instrument_id, context_id, feel, note, created_at, device_id
) VALUES (
    '9d8441e6-0542-464b-bb20-49f61b7ef93c', '8a1b2c3d-0000-4000-8000-000000000001', '2026-09-12',
    '6409e4f3-5881-5293-8918-9dbafe572ac6', NULL, 3, 'nailed the bridge', '2026-09-12T19:30:00.222Z',
    'old-phone'
);

--;
-- A tap that was undone: the event stays, and the void below hides it.
INSERT INTO practice_event(
    id, song_id, logged_on, instrument_id, context_id, feel, note, created_at, device_id
) VALUES (
    'ba947ad2-8759-4977-b713-bb3421ea05ef', '8a1b2c3d-0000-4000-8000-000000000001', '2026-09-13',
    '6409e4f3-5881-5293-8918-9dbafe572ac6', NULL, NULL, NULL, '2026-09-13T08:15:00.333Z', 'old-phone'
);

--;
INSERT INTO practice_event_void(id, practice_event_id, created_at, device_id)
VALUES ('2bf6459a-0618-4e7e-817b-28a82aa7f35c', 'ba947ad2-8759-4977-b713-bb3421ea05ef',
        '2026-09-13T08:15:04.444Z', 'old-phone');

--;
INSERT INTO saved_view(
    id, name, filter_performer_id, filter_instrument_id, filter_lead_only,
    practice_instrument_id, sort_order, position, notes, updated_at, deleted_at, device_id
) VALUES (
    '43bf3387-126e-4c84-9a23-a547f913d56f', 'Will leads', '8a1b2c3d-0000-4000-8000-0000000000aa',
    '6409e4f3-5881-5293-8918-9dbafe572ac6', 1, '6409e4f3-5881-5293-8918-9dbafe572ac6',
    'HOTTEST_FIRST', 0, 'gig prep', '2026-09-02T10:00:00.555Z', NULL, 'old-phone'
);

--;
INSERT INTO saved_view(
    id, name, filter_performer_id, filter_instrument_id, filter_lead_only,
    practice_instrument_id, sort_order, position, notes, updated_at, deleted_at, device_id
) VALUES (
    'cc9c1d85-2153-45af-bf18-e912c7765575', 'Retired view', NULL, NULL, 0,
    '6409e4f3-5881-5293-8918-9dbafe572ac6', 'COLDEST_FIRST', 1, NULL,
    '2026-09-03T11:00:00.666Z', '2026-09-03T11:00:00.666Z', 'old-phone'
);
