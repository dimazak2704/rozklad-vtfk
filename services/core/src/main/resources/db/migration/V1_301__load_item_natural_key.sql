-- load: один запис навантаження на семестр × групу × дисципліну × вид занять × поділ на підгрупи.
-- Досі це перевіряв лише Java-код без блокування, тож одночасні створення, імпорт чи виведення з версії
-- могли додати копію, що подвоювала план годин. Спершу прибираємо такі копії (лишається найстаріший
-- запис; заняття шаблонів і викладачі підгруп, яких у нього ще немає, переходять на нього, решта
-- викладачів копії видаляється каскадом), потім ключ.

-- template_lesson з'являється в V1_400: на новій базі (міграції по порядку) його ще немає, а копій теж немає
DO
$$
    BEGIN
        IF to_regclass('template_lesson') IS NOT NULL THEN
            WITH ranked AS (
                SELECT id,
                       first_value(id) OVER (PARTITION BY semester_id, group_id, discipline_id, lesson_kind,
                                                          split_subgroups
                                             ORDER BY created_at, id) AS keep_id
                FROM load_item
            )
            UPDATE template_lesson t
            SET load_item_id = r.keep_id
            FROM ranked r
            WHERE t.load_item_id = r.id
              AND r.id <> r.keep_id;
        END IF;
    END
$$;

-- викладач копії лишається, якщо в записі, що лишається, цієї підгрупи немає (з копій — найстаріша)
WITH ranked AS (
    SELECT id,
           first_value(id) OVER (PARTITION BY semester_id, group_id, discipline_id, lesson_kind, split_subgroups
                                 ORDER BY created_at, id) AS keep_id,
           row_number() OVER (PARTITION BY semester_id, group_id, discipline_id, lesson_kind, split_subgroups
                              ORDER BY created_at, id) AS position
    FROM load_item
),
moved AS (
    SELECT DISTINCT ON (r.keep_id, t.subgroup_number) t.id, r.keep_id
    FROM load_item_teacher t
    JOIN ranked r ON r.id = t.load_item_id
    WHERE r.id <> r.keep_id
      AND NOT EXISTS (SELECT 1 FROM load_item_teacher k
                      WHERE k.load_item_id = r.keep_id AND k.subgroup_number = t.subgroup_number)
    ORDER BY r.keep_id, t.subgroup_number, r.position
)
UPDATE load_item_teacher t
SET load_item_id = m.keep_id
FROM moved m
WHERE t.id = m.id;

WITH ranked AS (
    SELECT id,
           first_value(id) OVER (PARTITION BY semester_id, group_id, discipline_id, lesson_kind, split_subgroups
                                 ORDER BY created_at, id) AS keep_id
    FROM load_item
)
DELETE FROM load_item l
USING ranked r
WHERE l.id = r.id
  AND r.id <> r.keep_id;

CREATE UNIQUE INDEX uq_load_item_natural_key
    ON load_item (semester_id, group_id, discipline_id, lesson_kind, split_subgroups);
