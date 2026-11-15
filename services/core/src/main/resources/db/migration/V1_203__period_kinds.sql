-- calendar: види календарних періодів — довідник із фіксованим набором ефектів (DOMAIN §2), аудиторія й
-- джерело періоду (ручний чи з імпорту графіка освітнього процесу), тижні теорії груп із графіка.

CREATE TABLE period_kind
(
    id            uuid         NOT NULL,
    code          varchar(10)  NOT NULL,
    name          varchar(100) NOT NULL,
    effect        varchar(16)  NOT NULL,
    color         varchar(7),
    aliases       text[]       NOT NULL DEFAULT '{}',
    note_template text,
    builtin       boolean      NOT NULL DEFAULT false,
    active        boolean      NOT NULL DEFAULT true,
    position      integer      NOT NULL DEFAULT 0,
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL,
    version       bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_period_kind PRIMARY KEY (id),
    -- BLOCK (свої пари блоку) з'явиться пізніше окремою міграцією
    CONSTRAINT ck_period_kind_effect CHECK (effect IN ('DAY_OFF', 'SUSPEND', 'ONLINE', 'INFO')),
    CONSTRAINT ck_period_kind_color CHECK (color IS NULL OR color ~ '^#[0-9A-F]{6}$'),
    CONSTRAINT ck_period_kind_position CHECK (position BETWEEN 0 AND 9999)
);

-- код унікальний без урахування регістру; збіг коду з псевдонімом іншого виду перевіряє сервіс
CREATE UNIQUE INDEX uq_period_kind_code ON period_kind (lower(code));

-- Вбудовані види: коди графіка освітнього процесу коледжу. Ідентифікатори сталі (на них посилаються
-- перенесення старих записів нижче і тригер сумісності). НП зветься «Практика», І — «Сесія», щоб тексти
-- п'яти старих видів на сайті й у боті не змінилися.
INSERT INTO period_kind (id, code, name, effect, color, aliases, note_template, builtin, active, position,
                         created_at, updated_at, version)
VALUES ('a1000000-0000-4000-8000-000000000001', 'К', 'Канікули', 'DAY_OFF', '#FFFFCC', '{}',
        '{хто}: {назва_мала} з {від}р. по {до} р.', true, true, 10, now(), now(), 0),
       ('a1000000-0000-4000-8000-000000000002', 'С', 'Святкові дні', 'DAY_OFF', NULL, '{}',
        '{хто}: {назва_мала} з {від}р. по {до} р.', true, true, 20, now(), now(), 0),
       ('a1000000-0000-4000-8000-000000000003', 'БЗ', 'Без занять', 'DAY_OFF', NULL, '{}',
        '{хто}: {назва_мала} з {від}р. по {до} р.', true, true, 30, now(), now(), 0),
       ('a1000000-0000-4000-8000-000000000004', 'НП', 'Практика', 'SUSPEND', NULL, '{}',
        '{хто} – {назва} з {від}р. по {до} р.[, ауд. {ауд}]', true, true, 40, now(), now(), 0),
       ('a1000000-0000-4000-8000-000000000005', 'ВП', 'Виробнича практика', 'SUSPEND', '#33CCCC', '{}',
        '{хто} – {назва} з {від}р. по {до} р.[, ауд. {ауд}]', true, true, 50, now(), now(), 0),
       ('a1000000-0000-4000-8000-000000000006', 'ПП', 'Переддипломна практика', 'SUSPEND', '#33CCCC', '{}',
        '{хто} – {назва} з {від}р. по {до} р.[, ауд. {ауд}]', true, true, 60, now(), now(), 0),
       ('a1000000-0000-4000-8000-000000000007', 'Т', 'Технологічна практика', 'SUSPEND', NULL, '{}',
        '{хто} – {назва} з {від}р. по {до} р.[, ауд. {ауд}]', true, true, 70, now(), now(), 0),
       ('a1000000-0000-4000-8000-000000000008', 'ЗУ', 'Збори', 'SUSPEND', NULL, '{З}',
        '{хто} – {назва} з {від}р. по {до} р.[, ауд. {ауд}]', true, true, 80, now(), now(), 0),
       ('a1000000-0000-4000-8000-000000000009', 'НС', 'Національний спротив', 'SUSPEND', NULL, '{}',
        '{хто} – {назва} з {від}р. по {до} р.[, ауд. {ауд}]', true, true, 90, now(), now(), 0),
       ('a1000000-0000-4000-8000-000000000010', 'І', 'Сесія', 'SUSPEND', NULL, '{}',
        '{хто} – {назва} з {від}р. по {до} р.[, ауд. {ауд}]', true, true, 100, now(), now(), 0),
       ('a1000000-0000-4000-8000-000000000011', 'Д', 'ДПА', 'SUSPEND', NULL, '{}',
        '{хто} – {назва} з {від}р. по {до} р.[, ауд. {ауд}]', true, true, 110, now(), now(), 0),
       ('a1000000-0000-4000-8000-000000000012', 'А', 'Атестація', 'SUSPEND', '#CCFFCC', '{}',
        '{хто} – {назва} з {від}р. по {до} р.[, ауд. {ауд}]', true, true, 120, now(), now(), 0),
       ('a1000000-0000-4000-8000-000000000013', 'ФН', 'Форма навчання', 'ONLINE', NULL, '{}',
        '{хто} з {від}р. по {до} {режим}.', true, true, 130, now(), now(), 0),
       ('a1000000-0000-4000-8000-000000000014', 'Р', 'Резерв навчального часу', 'INFO', NULL, '{}',
        '{хто} – {назва} з {від}р. по {до} р.', true, true, 140, now(), now(), 0),
       ('a1000000-0000-4000-8000-000000000015', 'ВКР', 'Виконання кваліфікаційної роботи', 'INFO', NULL, '{}',
        '{хто} – {назва} з {від}р. по {до} р.', true, true, 150, now(), now(), 0);

ALTER TABLE calendar_period
    ADD COLUMN kind_id    uuid,
    ADD COLUMN room_id    uuid,
    ADD COLUMN source     varchar(16) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN source_key varchar(200);

-- старі види → вбудовані: HOLIDAY→К, NO_CLASSES→БЗ, PRACTICE→НП, STUDY_MODE→ФН, EXAM_SESSION→І
UPDATE calendar_period
SET kind_id = CASE kind
                  WHEN 'HOLIDAY' THEN 'a1000000-0000-4000-8000-000000000001'::uuid
                  WHEN 'NO_CLASSES' THEN 'a1000000-0000-4000-8000-000000000003'::uuid
                  WHEN 'PRACTICE' THEN 'a1000000-0000-4000-8000-000000000004'::uuid
                  WHEN 'STUDY_MODE' THEN 'a1000000-0000-4000-8000-000000000013'::uuid
                  WHEN 'EXAM_SESSION' THEN 'a1000000-0000-4000-8000-000000000010'::uuid
    END;

-- стара колонка kind лишається на один реліз: ядро пише в неї похідне значення, але не читає її
ALTER TABLE calendar_period
    ALTER COLUMN kind_id SET NOT NULL,
    ALTER COLUMN kind DROP NOT NULL,
    DROP CONSTRAINT ck_calendar_period_kind,
    ADD CONSTRAINT fk_calendar_period_kind FOREIGN KEY (kind_id) REFERENCES period_kind (id),
    ADD CONSTRAINT fk_calendar_period_room FOREIGN KEY (room_id) REFERENCES room (id) ON DELETE SET NULL,
    ADD CONSTRAINT ck_calendar_period_source CHECK (source IN ('MANUAL', 'IMPORT'));

CREATE UNIQUE INDEX uq_calendar_period_source_key ON calendar_period (source_key) WHERE source_key IS NOT NULL;
CREATE INDEX idx_calendar_period_kind ON calendar_period (kind_id);
CREATE INDEX idx_calendar_period_room ON calendar_period (room_id);

-- Сумісність на один реліз: SQL, що вставляє період лише зі старим kind (демо-міграція V9_001 на новій базі),
-- отримує вбудований вид. Прибрати разом із колонкою kind.
CREATE FUNCTION calendar_period_kind_from_legacy() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.kind_id IS NULL THEN
        NEW.kind_id := CASE NEW.kind
                           WHEN 'HOLIDAY' THEN 'a1000000-0000-4000-8000-000000000001'::uuid
                           WHEN 'NO_CLASSES' THEN 'a1000000-0000-4000-8000-000000000003'::uuid
                           WHEN 'PRACTICE' THEN 'a1000000-0000-4000-8000-000000000004'::uuid
                           WHEN 'STUDY_MODE' THEN 'a1000000-0000-4000-8000-000000000013'::uuid
                           WHEN 'EXAM_SESSION' THEN 'a1000000-0000-4000-8000-000000000010'::uuid
            END;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_calendar_period_kind_from_legacy
    BEFORE INSERT
    ON calendar_period
    FOR EACH ROW
EXECUTE FUNCTION calendar_period_kind_from_legacy();

-- Тижні теорії й кількість іспитів групи в семестрі з графіка освітнього процесу (колонки E/F і G/H).
-- Крок 1 лише зберігає їх; навантаження й генератор беруть їх у кроці 2.
CREATE TABLE group_semester
(
    semester_id  uuid        NOT NULL,
    group_id     uuid        NOT NULL,
    theory_weeks smallint,
    exams        smallint,
    source       varchar(16) NOT NULL DEFAULT 'IMPORT',
    updated_at   timestamptz NOT NULL,
    CONSTRAINT pk_group_semester PRIMARY KEY (semester_id, group_id),
    CONSTRAINT fk_group_semester_semester FOREIGN KEY (semester_id) REFERENCES semester (id) ON DELETE CASCADE,
    CONSTRAINT fk_group_semester_group FOREIGN KEY (group_id) REFERENCES student_group (id) ON DELETE CASCADE,
    CONSTRAINT ck_group_semester_theory_weeks CHECK (theory_weeks IS NULL OR theory_weeks BETWEEN 1 AND 53),
    CONSTRAINT ck_group_semester_exams CHECK (exams IS NULL OR exams BETWEEN 0 AND 20),
    CONSTRAINT ck_group_semester_source CHECK (source IN ('MANUAL', 'IMPORT'))
);

CREATE INDEX idx_group_semester_group ON group_semester (group_id);
