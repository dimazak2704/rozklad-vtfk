-- importer: новий вид імпорту — робочий графік освітнього процесу (ACADEMIC_CALENDAR_XLSX)

ALTER TABLE import_job DROP CONSTRAINT ck_import_job_kind;
ALTER TABLE import_job ADD CONSTRAINT ck_import_job_kind CHECK (kind IN
    ('TIMETABLE_XLSX', 'DISCIPLINE_PROGRAM', 'CURRICULUM', 'ORDER_DOCUMENT', 'ACADEMIC_CALENDAR_XLSX'));
