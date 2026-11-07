-- задачі генерації розкладу: статус, найкращий рахунок, причина помилки та
-- результат generation.completed, доки його не підтвердив брокер (повторна відправка)
CREATE TABLE generation_job
(
    id                 uuid        NOT NULL,
    semester_id        uuid,
    status             varchar(16) NOT NULL,
    time_limit_seconds integer,
    unit_count         integer,
    weights            jsonb,
    received_at        timestamptz NOT NULL,
    started_at         timestamptz,
    finished_at        timestamptz,
    best_hard_score    bigint,
    best_soft_score    bigint,
    elapsed_ms         bigint,
    cancel_requested   boolean     NOT NULL DEFAULT false,
    error              text,
    completion         jsonb,
    completion_sent_at timestamptz,
    version            bigint      NOT NULL,
    CONSTRAINT pk_generation_job PRIMARY KEY (id),
    CONSTRAINT ck_generation_job_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'SOLVED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_generation_job_time_limit CHECK (time_limit_seconds IS NULL OR time_limit_seconds > 0),
    -- незавершена задача не має результату; завершена має час завершення і результат
    CONSTRAINT ck_generation_job_finished CHECK (
        (status IN ('QUEUED', 'RUNNING') AND finished_at IS NULL AND completion IS NULL)
        OR (status IN ('SOLVED', 'FAILED', 'CANCELLED') AND finished_at IS NOT NULL AND completion IS NOT NULL))
);

CREATE INDEX idx_generation_job_active ON generation_job (status) WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX idx_generation_job_unsent ON generation_job (finished_at)
    WHERE completion IS NOT NULL AND completion_sent_at IS NULL;
CREATE INDEX idx_generation_job_finished_at ON generation_job (finished_at);
