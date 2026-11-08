-- Демо-дані (DOMAIN §12). Виконується лише з профілем Spring «demo» (DemoSeedConfig додає цю локацію).
-- Групи, викладачі, аудиторії, дисципліни й розклад приходять з імпорту Excel, тут їх немає.
-- Лише те, що підтверджують файли коледжу: відділення з DOMAIN §12, спеціальність 123 «Комп’ютерна
-- інженерія» і дві циклові комісії з програм дисциплін. Повних назв інших спеціальностей і корпусів
-- у джерелах немає, тому спеціальності мають назву = літери, а корпусів немає зовсім.

INSERT INTO academic_year (id, name, starts_on, ends_on, created_at, updated_at, version)
VALUES ('d0000000-0000-4000-8000-000000000001', '2026/2027', DATE '2026-09-01', DATE '2027-08-31', now(), now(), 0)
ON CONFLICT (name) DO NOTHING;

-- I семестр активний; перший тиждень — чисельник (19.10.2026 = 8-й тиждень, знаменник, як у розпорядженні №35)
INSERT INTO semester (id, academic_year_id, number, name, starts_on, ends_on, first_week_parity, weeks_count,
                      bell_profile_id, active, created_at, updated_at, version)
SELECT 'd0000000-0000-4000-8000-000000000011', y.id, 1, 'I семестр 2026/2027', DATE '2026-09-01', DATE '2026-12-30',
       'NUMERATOR', NULL, p.id, NOT EXISTS (SELECT 1 FROM semester s WHERE s.active), now(), now(), 0
FROM academic_year y
         LEFT JOIN bell_profile p ON p.name = '2 зміни, 60 хв'
WHERE y.name = '2026/2027'
ON CONFLICT DO NOTHING;

INSERT INTO semester (id, academic_year_id, number, name, starts_on, ends_on, first_week_parity, weeks_count,
                      bell_profile_id, active, created_at, updated_at, version)
SELECT 'd0000000-0000-4000-8000-000000000012', y.id, 2, 'II семестр 2026/2027', DATE '2027-01-19', DATE '2027-06-30',
       'NUMERATOR', NULL, p.id, false, now(), now(), 0
FROM academic_year y
         LEFT JOIN bell_profile p ON p.name = '2 зміни, 60 хв'
WHERE y.name = '2026/2027'
ON CONFLICT DO NOTHING;

INSERT INTO department (id, name, short_name, created_at, updated_at, version)
VALUES ('d0000000-0000-4000-8000-000000000101', 'Комп’ютерне відділення', 'Комп’ютерне', now(), now(), 0),
       ('d0000000-0000-4000-8000-000000000102', 'Фінансово-економічне відділення', 'Фінансово-економічне', now(), now(), 0),
       ('d0000000-0000-4000-8000-000000000103', 'Радіотехнічне відділення', 'Радіотехнічне', now(), now(), 0),
       ('d0000000-0000-4000-8000-000000000104', 'Відділення електроенергетики та електромеханіки',
        'Електроенергетики та електромеханіки', now(), now(), 0),
       ('d0000000-0000-4000-8000-000000000105', 'Відділення транспортних технологій', 'Транспортних технологій',
        now(), now(), 0)
ON CONFLICT (name) DO NOTHING;

INSERT INTO specialty (id, code, letters, name, department_id, created_at, updated_at, version)
SELECT gen_random_uuid(), s.code, s.letters, s.name, d.id, now(), now(), 0
FROM (VALUES ('123', 'КІ', 'Комп’ютерна інженерія', 'Комп’ютерне відділення'),
             (NULL, 'Ф', 'Ф', 'Фінансово-економічне відділення'),
             (NULL, 'ОБ', 'ОБ', 'Фінансово-економічне відділення'),
             (NULL, 'М', 'М', 'Фінансово-економічне відділення'),
             (NULL, 'ЕП', 'ЕП', 'Фінансово-економічне відділення'),
             (NULL, 'ОД', 'ОД', 'Фінансово-економічне відділення'),
             (NULL, 'РТ', 'РТ', 'Радіотехнічне відділення'),
             (NULL, 'ТК', 'ТК', 'Радіотехнічне відділення'),
             (NULL, 'ЕА', 'ЕА', 'Відділення електроенергетики та електромеханіки'),
             (NULL, 'ВЕ', 'ВЕ', 'Відділення електроенергетики та електромеханіки'),
             (NULL, 'ОП', 'ОП', 'Відділення транспортних технологій')) AS s(code, letters, name, department)
         JOIN department d ON d.name = s.department
ON CONFLICT (letters) DO NOTHING;

INSERT INTO cycle_commission (id, name, created_at, updated_at, version)
VALUES ('d0000000-0000-4000-8000-000000000201', 'Циклова комісія комп’ютерної інженерії', now(), now(), 0),
       ('d0000000-0000-4000-8000-000000000202', 'Циклова комісія економіки і фінансів', now(), now(), 0)
ON CONFLICT (name) DO NOTHING;

-- Нотатки з розпоряджень: 3 курс дистанційно 01.09–31.10.2026. Періоди, прив'язані до груп (практика 2КІ3,
-- дистанційне навчання 4КІ), створює DemoGroupPeriods після імпорту груп.
INSERT INTO calendar_period (id, semester_id, kind, title, starts_on, ends_on, study_mode, order_note, scope, courses,
                             created_at, updated_at, version)
SELECT 'd0000000-0000-4000-8000-000000000301', s.id, 'STUDY_MODE', '3 курс навчається дистанційно',
       DATE '2026-09-01', DATE '2026-10-31', 'ONLINE',
       'Групи 3 курсу з 01.09.26р. по 31.10.26 навчаються – дистанційно.', 'COURSES', ARRAY [3]::smallint[], now(),
       now(), 0
FROM semester s
WHERE s.name = 'I семестр 2026/2027'
ON CONFLICT DO NOTHING;
