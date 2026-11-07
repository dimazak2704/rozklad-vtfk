-- timetable: що потрібно core, щоб прийняти результат генерації: базова версія, план одиниць
-- (unitId → навантаження, підгрупа, викладач …), аналіз оцінки й метрики «було / стало».

ALTER TABLE generation_job
    ADD COLUMN base_version_id  uuid,
    ADD COLUMN plan             jsonb,
    ADD COLUMN analysis         jsonb,
    ADD COLUMN metrics_before   jsonb,
    ADD COLUMN metrics_after    jsonb,
    ADD COLUMN last_progress_at timestamptz;

ALTER TABLE generation_job
    ADD CONSTRAINT fk_generation_job_base_version
        FOREIGN KEY (base_version_id) REFERENCES timetable_version (id) ON DELETE SET NULL;

CREATE INDEX idx_generation_job_base_version ON generation_job (base_version_id);

-- у семестрі одночасно може виконуватися лише одна генерація (захист від подвійного натискання)
CREATE UNIQUE INDEX uq_generation_job_active_semester ON generation_job (semester_id)
    WHERE status IN ('QUEUED', 'RUNNING');
