-- catalog: відділення, спеціальності, циклові комісії, викладачі, дисципліни, корпуси, аудиторії, групи

CREATE TABLE department
(
    id         uuid         NOT NULL,
    name       varchar(200) NOT NULL,
    short_name varchar(50),
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL,
    version    bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_department PRIMARY KEY (id),
    CONSTRAINT uq_department_name UNIQUE (name)
);

CREATE TABLE specialty
(
    id            uuid         NOT NULL,
    code          varchar(10),
    letters       varchar(10)  NOT NULL,
    name          varchar(200) NOT NULL,
    department_id uuid,
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL,
    version       bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_specialty PRIMARY KEY (id),
    CONSTRAINT uq_specialty_letters UNIQUE (letters),
    CONSTRAINT fk_specialty_department FOREIGN KEY (department_id) REFERENCES department (id)
);

CREATE INDEX idx_specialty_department ON specialty (department_id);

CREATE TABLE cycle_commission
(
    id         uuid         NOT NULL,
    name       varchar(200) NOT NULL,
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL,
    version    bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_cycle_commission PRIMARY KEY (id),
    CONSTRAINT uq_cycle_commission_name UNIQUE (name)
);

CREATE TABLE teacher
(
    id                  uuid         NOT NULL,
    last_name           varchar(100) NOT NULL,
    first_name          varchar(100),
    middle_name         varchar(100),
    short_name          varchar(100) NOT NULL,
    display_name        varchar(100) NOT NULL,
    cycle_commission_id uuid,
    employment          varchar(16)  NOT NULL DEFAULT 'STAFF',
    email               varchar(254),
    active              boolean      NOT NULL DEFAULT true,
    keycloak_user_id    uuid,
    needs_review        boolean      NOT NULL DEFAULT false,
    created_at          timestamptz  NOT NULL,
    updated_at          timestamptz  NOT NULL,
    version             bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_teacher PRIMARY KEY (id),
    CONSTRAINT uq_teacher_short_name UNIQUE (short_name),
    CONSTRAINT uq_teacher_keycloak_user UNIQUE (keycloak_user_id),
    CONSTRAINT fk_teacher_cycle_commission FOREIGN KEY (cycle_commission_id) REFERENCES cycle_commission (id),
    CONSTRAINT ck_teacher_employment CHECK (employment IN ('STAFF', 'PART_TIME'))
);

CREATE INDEX idx_teacher_cycle_commission ON teacher (cycle_commission_id);
CREATE INDEX idx_teacher_last_name ON teacher (last_name);

CREATE TABLE teacher_alias
(
    id         uuid         NOT NULL,
    teacher_id uuid         NOT NULL,
    alias      varchar(100) NOT NULL,
    normalized varchar(100) NOT NULL,
    CONSTRAINT pk_teacher_alias PRIMARY KEY (id),
    CONSTRAINT uq_teacher_alias_normalized UNIQUE (normalized),
    CONSTRAINT fk_teacher_alias_teacher FOREIGN KEY (teacher_id) REFERENCES teacher (id) ON DELETE CASCADE
);

CREATE INDEX idx_teacher_alias_teacher ON teacher_alias (teacher_id);

CREATE TABLE discipline
(
    id           uuid         NOT NULL,
    name         varchar(300) NOT NULL,
    short_name   varchar(64)  NOT NULL,
    color        varchar(7),
    active       boolean      NOT NULL DEFAULT true,
    needs_review boolean      NOT NULL DEFAULT false,
    created_at   timestamptz  NOT NULL,
    updated_at   timestamptz  NOT NULL,
    version      bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_discipline PRIMARY KEY (id),
    CONSTRAINT uq_discipline_short_name UNIQUE (short_name),
    CONSTRAINT ck_discipline_color CHECK (color IS NULL OR color ~ '^#[0-9A-Fa-f]{6}$')
);

CREATE TABLE discipline_alias
(
    id            uuid         NOT NULL,
    discipline_id uuid         NOT NULL,
    alias         varchar(100) NOT NULL,
    normalized    varchar(100) NOT NULL,
    CONSTRAINT pk_discipline_alias PRIMARY KEY (id),
    CONSTRAINT uq_discipline_alias_normalized UNIQUE (normalized),
    CONSTRAINT fk_discipline_alias_discipline FOREIGN KEY (discipline_id) REFERENCES discipline (id) ON DELETE CASCADE
);

CREATE INDEX idx_discipline_alias_discipline ON discipline_alias (discipline_id);

-- компетенції викладача: використовуються в ранжуванні кандидатів на заміну.
-- Унікальні ключі дочірніх рядків DEFERRABLE: Hibernate виконує INSERT раніше за DELETE в одному flush,
-- тож заміна набору рядків з тими самими ключами інакше впала б посеред транзакції.
CREATE TABLE teacher_discipline
(
    id            uuid NOT NULL,
    teacher_id    uuid NOT NULL,
    discipline_id uuid NOT NULL,
    CONSTRAINT pk_teacher_discipline PRIMARY KEY (id),
    CONSTRAINT uq_teacher_discipline UNIQUE (teacher_id, discipline_id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_teacher_discipline_teacher FOREIGN KEY (teacher_id) REFERENCES teacher (id) ON DELETE CASCADE,
    CONSTRAINT fk_teacher_discipline_discipline FOREIGN KEY (discipline_id) REFERENCES discipline (id) ON DELETE CASCADE
);

CREATE INDEX idx_teacher_discipline_discipline ON teacher_discipline (discipline_id);

CREATE TABLE building
(
    id         uuid         NOT NULL,
    name       varchar(200) NOT NULL,
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL,
    version    bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_building PRIMARY KEY (id),
    CONSTRAINT uq_building_name UNIQUE (name)
);

-- capacity NULL = невідома (перевірка місткості пропускається); max_parallel > 1 для спортзалу «с.з.»
CREATE TABLE room
(
    id           uuid        NOT NULL,
    building_id  uuid,
    name         varchar(50) NOT NULL,
    type         varchar(20) NOT NULL DEFAULT 'CLASSROOM',
    capacity     integer,
    max_parallel integer     NOT NULL DEFAULT 1,
    active       boolean     NOT NULL DEFAULT true,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    version      bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_room PRIMARY KEY (id),
    CONSTRAINT uq_room_name UNIQUE (name),
    CONSTRAINT fk_room_building FOREIGN KEY (building_id) REFERENCES building (id),
    CONSTRAINT ck_room_type CHECK (type IN ('CLASSROOM', 'COMPUTER_LAB', 'LAB', 'GYM', 'SPORTS_GROUND', 'OTHER')),
    CONSTRAINT ck_room_capacity CHECK (capacity IS NULL OR capacity >= 0),
    CONSTRAINT ck_room_max_parallel CHECK (max_parallel >= 1)
);

CREATE INDEX idx_room_building ON room (building_id);

CREATE TABLE student_group
(
    id                 uuid        NOT NULL,
    code               varchar(16) NOT NULL,
    course             integer     NOT NULL,
    specialty_id       uuid        NOT NULL,
    number             integer,
    size               integer,
    shift              integer     NOT NULL DEFAULT 1,
    curator_teacher_id uuid,
    home_room_id       uuid,
    active             boolean     NOT NULL DEFAULT true,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    version            bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_student_group PRIMARY KEY (id),
    CONSTRAINT uq_student_group_code UNIQUE (code),
    CONSTRAINT fk_student_group_specialty FOREIGN KEY (specialty_id) REFERENCES specialty (id),
    CONSTRAINT fk_student_group_curator FOREIGN KEY (curator_teacher_id) REFERENCES teacher (id),
    CONSTRAINT fk_student_group_home_room FOREIGN KEY (home_room_id) REFERENCES room (id),
    CONSTRAINT ck_student_group_course CHECK (course BETWEEN 1 AND 6),
    CONSTRAINT ck_student_group_number CHECK (number IS NULL OR number >= 0),
    CONSTRAINT ck_student_group_size CHECK (size IS NULL OR size >= 0),
    CONSTRAINT ck_student_group_shift CHECK (shift IN (1, 2))
);

CREATE INDEX idx_student_group_specialty ON student_group (specialty_id);
CREATE INDEX idx_student_group_curator ON student_group (curator_teacher_id);
CREATE INDEX idx_student_group_home_room ON student_group (home_room_id);
CREATE INDEX idx_student_group_course ON student_group (course);

-- підгрупа 0 (уся група) не зберігається
CREATE TABLE subgroup
(
    id         uuid        NOT NULL,
    group_id   uuid        NOT NULL,
    number     integer     NOT NULL,
    name       varchar(100),
    size       integer,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version    bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_subgroup PRIMARY KEY (id),
    CONSTRAINT uq_subgroup_group_number UNIQUE (group_id, number) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_subgroup_group FOREIGN KEY (group_id) REFERENCES student_group (id) ON DELETE CASCADE,
    CONSTRAINT ck_subgroup_number CHECK (number >= 1),
    CONSTRAINT ck_subgroup_size CHECK (size IS NULL OR size >= 0)
);
