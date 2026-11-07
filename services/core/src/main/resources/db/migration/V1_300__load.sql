-- load: навантаження група × дисципліна × семестр

CREATE TABLE load_item
(
    id                 uuid         NOT NULL,
    semester_id        uuid         NOT NULL,
    group_id           uuid         NOT NULL,
    discipline_id      uuid         NOT NULL,
    lesson_kind        varchar(16)  NOT NULL DEFAULT 'MIXED',
    hours_per_week     numeric(4, 1) NOT NULL,
    planned_hours      integer      NOT NULL,
    split_subgroups    boolean      NOT NULL DEFAULT false,
    required_room_type varchar(20),
    preferred_room_id  uuid,
    control_form       varchar(16)  NOT NULL DEFAULT 'NONE',
    stream_id          uuid,
    source             varchar(20)  NOT NULL DEFAULT 'MANUAL',
    note               text,
    created_at         timestamptz  NOT NULL,
    updated_at         timestamptz  NOT NULL,
    version            bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_load_item PRIMARY KEY (id),
    CONSTRAINT fk_load_item_semester FOREIGN KEY (semester_id) REFERENCES semester (id),
    CONSTRAINT fk_load_item_group FOREIGN KEY (group_id) REFERENCES student_group (id),
    CONSTRAINT fk_load_item_discipline FOREIGN KEY (discipline_id) REFERENCES discipline (id),
    CONSTRAINT fk_load_item_preferred_room FOREIGN KEY (preferred_room_id) REFERENCES room (id),
    CONSTRAINT ck_load_item_lesson_kind CHECK (lesson_kind IN ('LECTURE', 'PRACTICE', 'LAB', 'SEMINAR', 'MIXED')),
    CONSTRAINT ck_load_item_hours_per_week CHECK (hours_per_week > 0),
    CONSTRAINT ck_load_item_planned_hours CHECK (planned_hours >= 0),
    CONSTRAINT ck_load_item_required_room_type CHECK (required_room_type IS NULL OR required_room_type IN
        ('CLASSROOM', 'COMPUTER_LAB', 'LAB', 'GYM', 'SPORTS_GROUND', 'OTHER')),
    CONSTRAINT ck_load_item_control_form CHECK (control_form IN ('EXAM', 'CREDIT', 'NONE')),
    CONSTRAINT ck_load_item_source CHECK (source IN ('MANUAL', 'CURRICULUM', 'PROGRAM', 'TIMETABLE_DERIVED'))
);

CREATE INDEX idx_load_item_semester_group ON load_item (semester_id, group_id);
CREATE INDEX idx_load_item_group ON load_item (group_id);
CREATE INDEX idx_load_item_discipline ON load_item (discipline_id);
CREATE INDEX idx_load_item_preferred_room ON load_item (preferred_room_id);
CREATE INDEX idx_load_item_stream ON load_item (stream_id) WHERE stream_id IS NOT NULL;

-- один викладач на підгрупу; subgroup_number 0, якщо група не ділиться
CREATE TABLE load_item_teacher
(
    id              uuid    NOT NULL,
    load_item_id    uuid    NOT NULL,
    subgroup_number integer NOT NULL DEFAULT 0,
    teacher_id      uuid    NOT NULL,
    CONSTRAINT pk_load_item_teacher PRIMARY KEY (id),
    CONSTRAINT uq_load_item_teacher_subgroup UNIQUE (load_item_id, subgroup_number) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_load_item_teacher_load_item FOREIGN KEY (load_item_id) REFERENCES load_item (id) ON DELETE CASCADE,
    CONSTRAINT fk_load_item_teacher_teacher FOREIGN KEY (teacher_id) REFERENCES teacher (id),
    CONSTRAINT ck_load_item_teacher_subgroup CHECK (subgroup_number >= 0)
);

CREATE INDEX idx_load_item_teacher_teacher ON load_item_teacher (teacher_id);
