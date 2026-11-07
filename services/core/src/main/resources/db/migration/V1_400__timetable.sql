-- timetable: задачі генерації, версії базового розкладу, шаблонні заняття, заняття на дату

CREATE TABLE generation_job
(
    id                uuid        NOT NULL,
    semester_id       uuid        NOT NULL,
    status            varchar(16) NOT NULL DEFAULT 'QUEUED',
    params            jsonb       NOT NULL DEFAULT '{}'::jsonb,
    started_at        timestamptz,
    finished_at       timestamptz,
    best_score        varchar(64),
    progress          jsonb       NOT NULL DEFAULT '[]'::jsonb,
    result_version_id uuid,
    error             text,
    requested_by      varchar(100),
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    version           bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_generation_job PRIMARY KEY (id),
    CONSTRAINT fk_generation_job_semester FOREIGN KEY (semester_id) REFERENCES semester (id),
    CONSTRAINT ck_generation_job_status CHECK (status IN ('QUEUED', 'RUNNING', 'SOLVED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_generation_job_times CHECK (finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at)
);

CREATE INDEX idx_generation_job_semester ON generation_job (semester_id, created_at DESC);
CREATE INDEX idx_generation_job_active ON generation_job (status) WHERE status IN ('QUEUED', 'RUNNING');

CREATE TABLE timetable_version
(
    id                uuid         NOT NULL,
    semester_id       uuid         NOT NULL,
    name              varchar(200) NOT NULL,
    status            varchar(16)  NOT NULL DEFAULT 'DRAFT',
    source            varchar(16)  NOT NULL DEFAULT 'MANUAL',
    effective_from    date,
    published_at      timestamptz,
    published_by      varchar(100),
    generation_job_id uuid,
    metrics           jsonb,
    notes             text,
    created_at        timestamptz  NOT NULL,
    updated_at        timestamptz  NOT NULL,
    version           bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_timetable_version PRIMARY KEY (id),
    CONSTRAINT fk_timetable_version_semester FOREIGN KEY (semester_id) REFERENCES semester (id),
    CONSTRAINT fk_timetable_version_generation_job FOREIGN KEY (generation_job_id) REFERENCES generation_job (id),
    CONSTRAINT ck_timetable_version_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT ck_timetable_version_source CHECK (source IN ('MANUAL', 'IMPORT', 'GENERATED', 'COPY')),
    CONSTRAINT ck_timetable_version_published CHECK (status <> 'PUBLISHED'
        OR (effective_from IS NOT NULL AND published_at IS NOT NULL))
);

CREATE INDEX idx_timetable_version_semester ON timetable_version (semester_id);
CREATE INDEX idx_timetable_version_generation_job ON timetable_version (generation_job_id);
-- у семестрі одночасно опублікована лише одна версія
CREATE UNIQUE INDEX uq_timetable_version_published ON timetable_version (semester_id) WHERE status = 'PUBLISHED';

ALTER TABLE generation_job
    ADD CONSTRAINT fk_generation_job_result_version
        FOREIGN KEY (result_version_id) REFERENCES timetable_version (id) ON DELETE SET NULL;

CREATE INDEX idx_generation_job_result_version ON generation_job (result_version_id);

-- weekday 1..5, parity BOTH|NUMERATOR|DENOMINATOR, subgroup_number 0 = уся група
CREATE TABLE template_lesson
(
    id              uuid        NOT NULL,
    version_id      uuid        NOT NULL,
    weekday         integer     NOT NULL,
    pair_number     integer     NOT NULL,
    parity          varchar(16) NOT NULL DEFAULT 'BOTH',
    group_id        uuid        NOT NULL,
    subgroup_number integer     NOT NULL DEFAULT 0,
    discipline_id   uuid        NOT NULL,
    teacher_id      uuid,
    room_id         uuid,
    load_item_id    uuid,
    lesson_kind     varchar(16) NOT NULL DEFAULT 'MIXED',
    stream_id       uuid,
    pinned          boolean     NOT NULL DEFAULT false,
    online          boolean     NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_template_lesson PRIMARY KEY (id),
    CONSTRAINT fk_template_lesson_version FOREIGN KEY (version_id) REFERENCES timetable_version (id) ON DELETE CASCADE,
    CONSTRAINT fk_template_lesson_group FOREIGN KEY (group_id) REFERENCES student_group (id),
    CONSTRAINT fk_template_lesson_discipline FOREIGN KEY (discipline_id) REFERENCES discipline (id),
    CONSTRAINT fk_template_lesson_teacher FOREIGN KEY (teacher_id) REFERENCES teacher (id),
    CONSTRAINT fk_template_lesson_room FOREIGN KEY (room_id) REFERENCES room (id),
    CONSTRAINT fk_template_lesson_load_item FOREIGN KEY (load_item_id) REFERENCES load_item (id) ON DELETE SET NULL,
    CONSTRAINT ck_template_lesson_weekday CHECK (weekday BETWEEN 1 AND 5),
    CONSTRAINT ck_template_lesson_pair CHECK (pair_number BETWEEN 1 AND 8),
    CONSTRAINT ck_template_lesson_parity CHECK (parity IN ('BOTH', 'NUMERATOR', 'DENOMINATOR')),
    CONSTRAINT ck_template_lesson_subgroup CHECK (subgroup_number >= 0),
    CONSTRAINT ck_template_lesson_kind CHECK (lesson_kind IN ('LECTURE', 'PRACTICE', 'LAB', 'SEMINAR', 'MIXED'))
);

CREATE INDEX idx_template_lesson_version_slot ON template_lesson (version_id, weekday, pair_number);
CREATE INDEX idx_template_lesson_group ON template_lesson (group_id);
CREATE INDEX idx_template_lesson_teacher ON template_lesson (teacher_id);
CREATE INDEX idx_template_lesson_room ON template_lesson (room_id);
CREATE INDEX idx_template_lesson_discipline ON template_lesson (discipline_id);
CREATE INDEX idx_template_lesson_load_item ON template_lesson (load_item_id);
CREATE INDEX idx_template_lesson_stream ON template_lesson (stream_id) WHERE stream_id IS NOT NULL;

-- Заняття на конкретну дату: єдине джерело для розкладу, замін і обліку годин.
-- change_id отримує FK у V1_500 (таблиця change належить модулю substitution).
CREATE TABLE lesson
(
    id                 uuid        NOT NULL,
    semester_id        uuid        NOT NULL,
    version_id         uuid,
    template_lesson_id uuid,
    date               date        NOT NULL,
    pair_number        integer     NOT NULL,
    group_id           uuid        NOT NULL,
    subgroup_number    integer     NOT NULL DEFAULT 0,
    discipline_id      uuid        NOT NULL,
    teacher_id         uuid,
    room_id            uuid,
    room_exclusive     boolean     NOT NULL DEFAULT true,
    status             varchar(16) NOT NULL DEFAULT 'SCHEDULED',
    origin             varchar(16) NOT NULL DEFAULT 'TEMPLATE',
    change_id          uuid,
    stream_id          uuid,
    lesson_kind        varchar(16) NOT NULL DEFAULT 'MIXED',
    online             boolean     NOT NULL DEFAULT false,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    version            bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_lesson PRIMARY KEY (id),
    CONSTRAINT fk_lesson_semester FOREIGN KEY (semester_id) REFERENCES semester (id),
    CONSTRAINT fk_lesson_version FOREIGN KEY (version_id) REFERENCES timetable_version (id),
    CONSTRAINT fk_lesson_template_lesson FOREIGN KEY (template_lesson_id) REFERENCES template_lesson (id) ON DELETE SET NULL,
    CONSTRAINT fk_lesson_group FOREIGN KEY (group_id) REFERENCES student_group (id),
    CONSTRAINT fk_lesson_discipline FOREIGN KEY (discipline_id) REFERENCES discipline (id),
    CONSTRAINT fk_lesson_teacher FOREIGN KEY (teacher_id) REFERENCES teacher (id),
    CONSTRAINT fk_lesson_room FOREIGN KEY (room_id) REFERENCES room (id),
    CONSTRAINT ck_lesson_pair CHECK (pair_number BETWEEN 1 AND 8),
    CONSTRAINT ck_lesson_study_day CHECK (EXTRACT(ISODOW FROM date) BETWEEN 1 AND 5),
    CONSTRAINT ck_lesson_subgroup CHECK (subgroup_number >= 0),
    CONSTRAINT ck_lesson_status CHECK (status IN ('SCHEDULED', 'CANCELLED')),
    CONSTRAINT ck_lesson_origin CHECK (origin IN ('TEMPLATE', 'CHANGE', 'ADDITIONAL', 'BLOCK')),
    CONSTRAINT ck_lesson_kind CHECK (lesson_kind IN ('LECTURE', 'PRACTICE', 'LAB', 'SEMINAR', 'MIXED'))
);

-- Жорсткі обмеження для запланованих занять (DOMAIN §3): часткова унікальність серед SCHEDULED.
-- Реалізовано як EXCLUDE … DEFERRABLE (той самий B-tree індекс, що й у partial unique index),
-- щоб застосування зміни (скасувати оригінал + створити нове заняття) і обмін пар (SWAP) в одній
-- транзакції не падали через порядок INSERT/UPDATE у flush Hibernate. Перевірка — під час commit
-- або після SET CONSTRAINTS ALL IMMEDIATE. Перетин «уся група / підгрупа» перевіряється в коді
-- під песимістичним блокуванням занять групи на дату.
ALTER TABLE lesson
    ADD CONSTRAINT uq_lesson_teacher_slot
        EXCLUDE USING btree (teacher_id WITH =, date WITH =, pair_number WITH =)
        WHERE (status = 'SCHEDULED' AND teacher_id IS NOT NULL AND stream_id IS NULL)
        DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE lesson
    ADD CONSTRAINT uq_lesson_room_slot
        EXCLUDE USING btree (room_id WITH =, date WITH =, pair_number WITH =)
        WHERE (status = 'SCHEDULED' AND room_exclusive AND room_id IS NOT NULL AND stream_id IS NULL)
        DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE lesson
    ADD CONSTRAINT uq_lesson_cohort_slot
        EXCLUDE USING btree (group_id WITH =, subgroup_number WITH =, date WITH =, pair_number WITH =)
        WHERE (status = 'SCHEDULED')
        DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX idx_lesson_date_pair ON lesson (date, pair_number);
CREATE INDEX idx_lesson_group_date ON lesson (group_id, date);
CREATE INDEX idx_lesson_teacher_date ON lesson (teacher_id, date);
CREATE INDEX idx_lesson_room_date ON lesson (room_id, date);
CREATE INDEX idx_lesson_semester_date ON lesson (semester_id, date);
CREATE INDEX idx_lesson_version ON lesson (version_id);
CREATE INDEX idx_lesson_template_lesson ON lesson (template_lesson_id);
CREATE INDEX idx_lesson_discipline ON lesson (discipline_id);
CREATE INDEX idx_lesson_stream ON lesson (stream_id) WHERE stream_id IS NOT NULL;
