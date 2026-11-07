-- calendar: навчальні роки, семестри, парність тижнів, дзвінки, зміни, календарні періоди

CREATE TABLE academic_year
(
    id         uuid        NOT NULL,
    name       varchar(20) NOT NULL,
    starts_on  date        NOT NULL,
    ends_on    date        NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version    bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_academic_year PRIMARY KEY (id),
    CONSTRAINT uq_academic_year_name UNIQUE (name),
    CONSTRAINT ck_academic_year_dates CHECK (ends_on > starts_on)
);

CREATE TABLE bell_profile
(
    id         uuid         NOT NULL,
    name       varchar(100) NOT NULL,
    active     boolean      NOT NULL DEFAULT true,
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL,
    version    bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_bell_profile PRIMARY KEY (id),
    CONSTRAINT uq_bell_profile_name UNIQUE (name)
);

-- weekday: 1 = понеділок … 5 = п'ятниця
CREATE TABLE bell_slot
(
    id          uuid    NOT NULL,
    profile_id  uuid    NOT NULL,
    weekday     integer NOT NULL,
    pair_number integer NOT NULL,
    starts_at   time    NOT NULL,
    ends_at     time    NOT NULL,
    CONSTRAINT pk_bell_slot PRIMARY KEY (id),
    CONSTRAINT uq_bell_slot UNIQUE (profile_id, weekday, pair_number) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_bell_slot_profile FOREIGN KEY (profile_id) REFERENCES bell_profile (id) ON DELETE CASCADE,
    CONSTRAINT ck_bell_slot_weekday CHECK (weekday BETWEEN 1 AND 5),
    CONSTRAINT ck_bell_slot_pair CHECK (pair_number BETWEEN 1 AND 8),
    CONSTRAINT ck_bell_slot_times CHECK (ends_at > starts_at)
);

CREATE TABLE semester
(
    id                uuid         NOT NULL,
    academic_year_id  uuid         NOT NULL,
    number            integer      NOT NULL,
    name              varchar(100) NOT NULL,
    starts_on         date         NOT NULL,
    ends_on           date         NOT NULL,
    first_week_parity varchar(16)  NOT NULL DEFAULT 'NUMERATOR',
    weeks_count       integer,
    bell_profile_id   uuid,
    active            boolean      NOT NULL DEFAULT false,
    created_at        timestamptz  NOT NULL,
    updated_at        timestamptz  NOT NULL,
    version           bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_semester PRIMARY KEY (id),
    CONSTRAINT uq_semester_year_number UNIQUE (academic_year_id, number),
    CONSTRAINT fk_semester_academic_year FOREIGN KEY (academic_year_id) REFERENCES academic_year (id),
    CONSTRAINT fk_semester_bell_profile FOREIGN KEY (bell_profile_id) REFERENCES bell_profile (id),
    CONSTRAINT ck_semester_number CHECK (number IN (1, 2)),
    CONSTRAINT ck_semester_dates CHECK (ends_on > starts_on),
    CONSTRAINT ck_semester_first_week_parity CHECK (first_week_parity IN ('NUMERATOR', 'DENOMINATOR')),
    CONSTRAINT ck_semester_weeks_count CHECK (weeks_count IS NULL OR weeks_count > 0)
);

CREATE INDEX idx_semester_bell_profile ON semester (bell_profile_id);
-- активним може бути лише один семестр
CREATE UNIQUE INDEX uq_semester_single_active ON semester (active) WHERE active;

-- week_start завжди понеділок
CREATE TABLE week_parity_override
(
    id          uuid        NOT NULL,
    semester_id uuid        NOT NULL,
    week_start  date        NOT NULL,
    parity      varchar(16) NOT NULL,
    CONSTRAINT pk_week_parity_override PRIMARY KEY (id),
    CONSTRAINT uq_week_parity_override UNIQUE (semester_id, week_start) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_week_parity_override_semester FOREIGN KEY (semester_id) REFERENCES semester (id) ON DELETE CASCADE,
    CONSTRAINT ck_week_parity_override_parity CHECK (parity IN ('NUMERATOR', 'DENOMINATOR')),
    CONSTRAINT ck_week_parity_override_monday CHECK (EXTRACT(ISODOW FROM week_start) = 1)
);

-- shift 1 → пари 1–4 пн–пт; shift 2 → 5–8 пн–чт, 4–6 пт (дані заповнює seed)
CREATE TABLE shift_window
(
    id         uuid    NOT NULL,
    shift      integer NOT NULL,
    weekday    integer NOT NULL,
    first_pair integer NOT NULL,
    last_pair  integer NOT NULL,
    CONSTRAINT pk_shift_window PRIMARY KEY (id),
    CONSTRAINT uq_shift_window UNIQUE (shift, weekday) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_shift_window_shift CHECK (shift IN (1, 2)),
    CONSTRAINT ck_shift_window_weekday CHECK (weekday BETWEEN 1 AND 5),
    CONSTRAINT ck_shift_window_pairs CHECK (first_pair BETWEEN 1 AND 8 AND last_pair BETWEEN first_pair AND 8)
);

CREATE TABLE calendar_period
(
    id          uuid         NOT NULL,
    semester_id uuid         NOT NULL,
    kind        varchar(20)  NOT NULL,
    title       varchar(300) NOT NULL,
    starts_on   date         NOT NULL,
    ends_on     date         NOT NULL,
    study_mode  varchar(16),
    order_note  text,
    scope       varchar(16)  NOT NULL DEFAULT 'ALL',
    courses     smallint[],
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    version     bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_calendar_period PRIMARY KEY (id),
    CONSTRAINT fk_calendar_period_semester FOREIGN KEY (semester_id) REFERENCES semester (id) ON DELETE CASCADE,
    CONSTRAINT ck_calendar_period_kind CHECK (kind IN ('HOLIDAY', 'NO_CLASSES', 'PRACTICE', 'STUDY_MODE', 'EXAM_SESSION')),
    CONSTRAINT ck_calendar_period_study_mode CHECK (study_mode IS NULL OR study_mode IN ('OFFLINE', 'ONLINE', 'MIXED')),
    CONSTRAINT ck_calendar_period_study_mode_required CHECK (kind <> 'STUDY_MODE' OR study_mode IS NOT NULL),
    CONSTRAINT ck_calendar_period_scope CHECK (scope IN ('ALL', 'COURSES', 'GROUPS')),
    CONSTRAINT ck_calendar_period_courses CHECK (scope <> 'COURSES' OR (courses IS NOT NULL AND cardinality(courses) > 0)),
    CONSTRAINT ck_calendar_period_dates CHECK (ends_on >= starts_on)
);

CREATE INDEX idx_calendar_period_semester_dates ON calendar_period (semester_id, starts_on, ends_on);

CREATE TABLE calendar_period_group
(
    id        uuid NOT NULL,
    period_id uuid NOT NULL,
    group_id  uuid NOT NULL,
    CONSTRAINT pk_calendar_period_group PRIMARY KEY (id),
    CONSTRAINT uq_calendar_period_group UNIQUE (period_id, group_id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_calendar_period_group_period FOREIGN KEY (period_id) REFERENCES calendar_period (id) ON DELETE CASCADE,
    CONSTRAINT fk_calendar_period_group_group FOREIGN KEY (group_id) REFERENCES student_group (id) ON DELETE CASCADE
);

CREATE INDEX idx_calendar_period_group_group ON calendar_period_group (group_id);
