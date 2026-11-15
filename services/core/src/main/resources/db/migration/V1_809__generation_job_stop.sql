-- timetable: генерація без ліміту часу (автоматичний режим). stop_requested_at — диспетчер попросив зупинити
-- задачу, що вже розвʼязується: вона лишається активною, доки планувальник не поверне найкращий варіант.
-- stop_reason — чому задача з розкладом зупинилася. NULL — задачі до цієї міграції і задачі без розкладу.

ALTER TABLE generation_job
    ADD COLUMN stop_requested_at timestamptz,
    ADD COLUMN stop_reason varchar(16),
    ADD CONSTRAINT ck_generation_job_stop_reason
        CHECK (stop_reason IS NULL OR stop_reason IN ('NO_IMPROVEMENT', 'TIME_LIMIT', 'SAFETY_CAP', 'STOPPED'));
