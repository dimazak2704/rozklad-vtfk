-- Довідкові дані календаря, потрібні в кожному середовищі (DOMAIN §2): профілі дзвінків і вікна змін.
-- Повторний запуск на базі, де профілі вже є (з іншого джерела), нічого не дублює.

INSERT INTO bell_profile (id, name, active, created_at, updated_at, version)
VALUES ('b0000000-0000-4000-8000-000000000001', '2 зміни, 60 хв', true, now(), now(), 0),
       ('b0000000-0000-4000-8000-000000000002', '2 зміни, 50 хв', false, now(), now(), 0),
       ('b0000000-0000-4000-8000-000000000003', '1 зміна, 80 хв', false, now(), now(), 0)
ON CONFLICT (name) DO NOTHING;

-- «2 зміни, 60 хв» (типовий): пн–чт 8 пар
INSERT INTO bell_slot (id, profile_id, weekday, pair_number, starts_at, ends_at)
SELECT gen_random_uuid(), p.id, d.weekday, s.pair, s.starts_at, s.ends_at
FROM bell_profile p
         CROSS JOIN (VALUES (1), (2), (3), (4)) AS d(weekday)
         CROSS JOIN (VALUES (1, TIME '08:30', TIME '09:30'),
                            (2, TIME '09:40', TIME '10:40'),
                            (3, TIME '11:00', TIME '12:00'),
                            (4, TIME '12:10', TIME '13:10'),
                            (5, TIME '13:30', TIME '14:30'),
                            (6, TIME '14:40', TIME '15:40'),
                            (7, TIME '16:00', TIME '17:00'),
                            (8, TIME '17:10', TIME '18:10')) AS s(pair, starts_at, ends_at)
WHERE p.name = '2 зміни, 60 хв'
  AND NOT EXISTS (SELECT 1 FROM bell_slot b WHERE b.profile_id = p.id);

-- «2 зміни, 60 хв»: п'ятниця 6 пар, 4-та і 6-та з іншим часом
INSERT INTO bell_slot (id, profile_id, weekday, pair_number, starts_at, ends_at)
SELECT gen_random_uuid(), p.id, 5, s.pair, s.starts_at, s.ends_at
FROM bell_profile p
         CROSS JOIN (VALUES (1, TIME '08:30', TIME '09:30'),
                            (2, TIME '09:40', TIME '10:40'),
                            (3, TIME '11:00', TIME '12:00'),
                            (4, TIME '12:20', TIME '13:20'),
                            (5, TIME '13:30', TIME '14:30'),
                            (6, TIME '14:50', TIME '15:50')) AS s(pair, starts_at, ends_at)
WHERE p.name = '2 зміни, 60 хв'
  AND NOT EXISTS (SELECT 1 FROM bell_slot b WHERE b.profile_id = p.id AND b.weekday = 5);

-- «2 зміни, 50 хв»: пн–чт 8 пар
INSERT INTO bell_slot (id, profile_id, weekday, pair_number, starts_at, ends_at)
SELECT gen_random_uuid(), p.id, d.weekday, s.pair, s.starts_at, s.ends_at
FROM bell_profile p
         CROSS JOIN (VALUES (1), (2), (3), (4)) AS d(weekday)
         CROSS JOIN (VALUES (1, TIME '08:30', TIME '09:20'),
                            (2, TIME '09:30', TIME '10:20'),
                            (3, TIME '10:40', TIME '11:30'),
                            (4, TIME '11:40', TIME '12:30'),
                            (5, TIME '12:50', TIME '13:40'),
                            (6, TIME '13:50', TIME '14:40'),
                            (7, TIME '15:00', TIME '15:50'),
                            (8, TIME '16:00', TIME '16:50')) AS s(pair, starts_at, ends_at)
WHERE p.name = '2 зміни, 50 хв'
  AND NOT EXISTS (SELECT 1 FROM bell_slot b WHERE b.profile_id = p.id);

-- «2 зміни, 50 хв»: п'ятниця 6 пар
INSERT INTO bell_slot (id, profile_id, weekday, pair_number, starts_at, ends_at)
SELECT gen_random_uuid(), p.id, 5, s.pair, s.starts_at, s.ends_at
FROM bell_profile p
         CROSS JOIN (VALUES (1, TIME '08:30', TIME '09:20'),
                            (2, TIME '09:30', TIME '10:20'),
                            (3, TIME '10:40', TIME '11:30'),
                            (4, TIME '11:50', TIME '12:40'),
                            (5, TIME '12:50', TIME '13:40'),
                            (6, TIME '14:00', TIME '14:50')) AS s(pair, starts_at, ends_at)
WHERE p.name = '2 зміни, 50 хв'
  AND NOT EXISTS (SELECT 1 FROM bell_slot b WHERE b.profile_id = p.id AND b.weekday = 5);

-- «1 зміна, 80 хв»: однаково пн–пт, 4 пари
INSERT INTO bell_slot (id, profile_id, weekday, pair_number, starts_at, ends_at)
SELECT gen_random_uuid(), p.id, d.weekday, s.pair, s.starts_at, s.ends_at
FROM bell_profile p
         CROSS JOIN (VALUES (1), (2), (3), (4), (5)) AS d(weekday)
         CROSS JOIN (VALUES (1, TIME '08:30', TIME '09:50'),
                            (2, TIME '10:00', TIME '11:20'),
                            (3, TIME '12:00', TIME '13:20'),
                            (4, TIME '13:30', TIME '14:50')) AS s(pair, starts_at, ends_at)
WHERE p.name = '1 зміна, 80 хв'
  AND NOT EXISTS (SELECT 1 FROM bell_slot b WHERE b.profile_id = p.id);

-- Вікна змін: 1 зміна — пари 1–4 пн–пт; 2 зміна — 5–8 пн–чт і 4–6 у п'ятницю
INSERT INTO shift_window (id, shift, weekday, first_pair, last_pair)
SELECT gen_random_uuid(), w.shift, w.weekday, w.first_pair, w.last_pair
FROM (SELECT 1 AS shift, d AS weekday, 1 AS first_pair, 4 AS last_pair FROM generate_series(1, 5) AS d
      UNION ALL
      SELECT 2, d, 5, 8 FROM generate_series(1, 4) AS d
      UNION ALL
      SELECT 2, 5, 4, 6) AS w
WHERE NOT EXISTS (SELECT 1 FROM shift_window);
