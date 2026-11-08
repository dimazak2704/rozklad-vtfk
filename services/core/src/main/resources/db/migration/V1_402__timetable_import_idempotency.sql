-- Idempotency-Key of the importer's timetable step (POST /api/admin/import/timetable): a retry with the
-- same key returns the draft version created by the first call instead of creating a second one.
ALTER TABLE timetable_version
    ADD COLUMN import_key          varchar(200),
    ADD COLUMN import_request_hash varchar(64);

CREATE UNIQUE INDEX ux_timetable_version_import_key ON timetable_version (import_key) WHERE import_key IS NOT NULL;
