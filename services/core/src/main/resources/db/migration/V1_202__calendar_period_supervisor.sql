-- Керівники практики: викладачі, яких диспетчер призначає на період PRACTICE.
CREATE TABLE calendar_period_supervisor
(
    period_id  uuid NOT NULL,
    teacher_id uuid NOT NULL,
    CONSTRAINT pk_calendar_period_supervisor PRIMARY KEY (period_id, teacher_id),
    CONSTRAINT fk_calendar_period_supervisor_period FOREIGN KEY (period_id) REFERENCES calendar_period (id) ON DELETE CASCADE,
    CONSTRAINT fk_calendar_period_supervisor_teacher FOREIGN KEY (teacher_id) REFERENCES teacher (id) ON DELETE CASCADE
);

CREATE INDEX idx_calendar_period_supervisor_teacher ON calendar_period_supervisor (teacher_id);
