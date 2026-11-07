-- substitution: відсутності, зміни (заміни), історія змін, побажання викладачів

-- pairs NULL = увесь день; reason_category і comment приватні (не виходять за межі адмінки)
CREATE TABLE absence
(
    id              uuid        NOT NULL,
    teacher_id      uuid        NOT NULL,
    date_from       date        NOT NULL,
    date_to         date        NOT NULL,
    pairs           smallint[],
    reason_category varchar(20) NOT NULL,
    comment         text,
    source          varchar(16) NOT NULL,
    status          varchar(16) NOT NULL DEFAULT 'ACTIVE',
    created_by      varchar(100),
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_absence PRIMARY KEY (id),
    CONSTRAINT fk_absence_teacher FOREIGN KEY (teacher_id) REFERENCES teacher (id),
    CONSTRAINT ck_absence_dates CHECK (date_to >= date_from),
    CONSTRAINT ck_absence_pairs CHECK (pairs IS NULL
        OR (cardinality(pairs) > 0 AND 1 <= ALL (pairs) AND 8 >= ALL (pairs))),
    CONSTRAINT ck_absence_reason_category CHECK (reason_category IN
        ('SICK_LEAVE', 'VACATION', 'BUSINESS_TRIP', 'PERSONAL', 'OTHER')),
    CONSTRAINT ck_absence_source CHECK (source IN ('WEB', 'BOT', 'ADMIN')),
    CONSTRAINT ck_absence_status CHECK (status IN ('ACTIVE', 'CANCELLED'))
);

CREATE INDEX idx_absence_teacher_dates ON absence (teacher_id, date_from, date_to);
CREATE INDEX idx_absence_active_dates ON absence (date_from, date_to) WHERE status = 'ACTIVE';

CREATE TABLE change
(
    id                 uuid        NOT NULL,
    date               date        NOT NULL,
    pair_from          integer     NOT NULL,
    pair_to            integer     NOT NULL,
    group_id           uuid        NOT NULL,
    subgroup_number    integer     NOT NULL DEFAULT 0,
    kind               varchar(24) NOT NULL,
    original_lesson_id uuid,
    orig_discipline_id uuid,
    orig_teacher_id    uuid,
    orig_room_id       uuid,
    new_discipline_id  uuid,
    new_teacher_id     uuid,
    new_room_id        uuid,
    reason_code        varchar(16),
    reason_text        varchar(100),
    absence_id         uuid,
    status             varchar(24) NOT NULL DEFAULT 'DRAFT',
    approval_mode      varchar(16) NOT NULL DEFAULT 'AUTO',
    confirm_deadline   timestamptz,
    responded_at       timestamptz,
    response_comment   text,
    order_id           uuid,
    created_by         varchar(100),
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    version            bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_change PRIMARY KEY (id),
    CONSTRAINT fk_change_group FOREIGN KEY (group_id) REFERENCES student_group (id),
    CONSTRAINT fk_change_original_lesson FOREIGN KEY (original_lesson_id) REFERENCES lesson (id),
    CONSTRAINT fk_change_orig_discipline FOREIGN KEY (orig_discipline_id) REFERENCES discipline (id),
    CONSTRAINT fk_change_orig_teacher FOREIGN KEY (orig_teacher_id) REFERENCES teacher (id),
    CONSTRAINT fk_change_orig_room FOREIGN KEY (orig_room_id) REFERENCES room (id),
    CONSTRAINT fk_change_new_discipline FOREIGN KEY (new_discipline_id) REFERENCES discipline (id),
    CONSTRAINT fk_change_new_teacher FOREIGN KEY (new_teacher_id) REFERENCES teacher (id),
    CONSTRAINT fk_change_new_room FOREIGN KEY (new_room_id) REFERENCES room (id),
    CONSTRAINT fk_change_absence FOREIGN KEY (absence_id) REFERENCES absence (id),
    CONSTRAINT ck_change_pairs CHECK (pair_from BETWEEN 1 AND 8 AND pair_to BETWEEN pair_from AND 8),
    CONSTRAINT ck_change_subgroup CHECK (subgroup_number >= 0),
    CONSTRAINT ck_change_kind CHECK (kind IN ('REPLACE_TEACHER', 'REPLACE_DISCIPLINE', 'SWAP', 'CANCEL', 'ADDITIONAL',
                                              'ROOM_CHANGE', 'BLOCK', 'DISCIPLINE_FINISHED')),
    CONSTRAINT ck_change_status CHECK (status IN ('DRAFT', 'PENDING_CONFIRMATION', 'CONFIRMED', 'DECLINED',
                                                  'CANCELLED', 'APPLIED')),
    CONSTRAINT ck_change_approval_mode CHECK (approval_mode IN ('AUTO', 'CONFIRM')),
    CONSTRAINT ck_change_reason_code CHECK (reason_code IS NULL
        OR reason_code IN ('ABSENT', 'MUTUAL', 'FINISHED', 'BUSINESS_TRIP', 'OTHER'))
);

CREATE INDEX idx_change_date_status ON change (date, status);
CREATE INDEX idx_change_group_date ON change (group_id, date);
CREATE INDEX idx_change_original_lesson ON change (original_lesson_id);
CREATE INDEX idx_change_absence ON change (absence_id);
CREATE INDEX idx_change_new_teacher_status ON change (new_teacher_id, status);
CREATE INDEX idx_change_orig_teacher ON change (orig_teacher_id);
CREATE INDEX idx_change_orig_discipline ON change (orig_discipline_id);
CREATE INDEX idx_change_new_discipline ON change (new_discipline_id);
CREATE INDEX idx_change_orig_room ON change (orig_room_id);
CREATE INDEX idx_change_new_room ON change (new_room_id);
CREATE INDEX idx_change_pending_deadline ON change (confirm_deadline) WHERE status = 'PENDING_CONFIRMATION';

-- заняття, створені або змінені застосованою зміною
ALTER TABLE lesson
    ADD CONSTRAINT fk_lesson_change FOREIGN KEY (change_id) REFERENCES change (id) ON DELETE SET NULL;

CREATE INDEX idx_lesson_change ON lesson (change_id);

CREATE TABLE change_history
(
    id          uuid        NOT NULL,
    change_id   uuid        NOT NULL,
    at          timestamptz NOT NULL,
    actor       varchar(100),
    from_status varchar(24),
    to_status   varchar(24) NOT NULL,
    comment     text,
    CONSTRAINT pk_change_history PRIMARY KEY (id),
    CONSTRAINT fk_change_history_change FOREIGN KEY (change_id) REFERENCES change (id) ON DELETE CASCADE
);

CREATE INDEX idx_change_history_change ON change_history (change_id, at);

CREATE TABLE teacher_preference
(
    id          uuid        NOT NULL,
    teacher_id  uuid        NOT NULL,
    semester_id uuid        NOT NULL,
    weekday     integer     NOT NULL,
    pair_number integer     NOT NULL,
    kind        varchar(16) NOT NULL,
    note        varchar(500),
    CONSTRAINT pk_teacher_preference PRIMARY KEY (id),
    CONSTRAINT uq_teacher_preference_slot UNIQUE (teacher_id, semester_id, weekday, pair_number)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_teacher_preference_teacher FOREIGN KEY (teacher_id) REFERENCES teacher (id) ON DELETE CASCADE,
    CONSTRAINT fk_teacher_preference_semester FOREIGN KEY (semester_id) REFERENCES semester (id) ON DELETE CASCADE,
    CONSTRAINT ck_teacher_preference_weekday CHECK (weekday BETWEEN 1 AND 5),
    CONSTRAINT ck_teacher_preference_pair CHECK (pair_number BETWEEN 1 AND 8),
    CONSTRAINT ck_teacher_preference_kind CHECK (kind IN ('UNWANTED', 'UNAVAILABLE'))
);

CREATE INDEX idx_teacher_preference_semester ON teacher_preference (semester_id);
