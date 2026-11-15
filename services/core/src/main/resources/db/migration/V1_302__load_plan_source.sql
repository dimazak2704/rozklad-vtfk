-- load: звідки план годин запису (рішення власника 15.11.2026). AUTO — план рахує система: години на тиждень
-- лише за дні, коли група на теорії (тижні НС, практики, сесії, зборів і канікул не рахуються й не надолужуються);
-- система перераховує його, коли змінюється календар чи опублікований розклад. FIXED — число задав диспетчер
-- або імпорт (програма дисципліни), система його не чіпає.
ALTER TABLE load_item
    ADD COLUMN plan_source varchar(10) NOT NULL DEFAULT 'AUTO';

ALTER TABLE load_item
    ADD CONSTRAINT ck_load_item_plan_source CHECK (plan_source IN ('AUTO', 'FIXED'));

-- Досі план за замовчуванням був «години на тиждень × навчальні тижні семестру» (weeks_count або тижні від
-- понеділка першого до понеділка останнього тижня, округлення половини вгору). Запис з іншим числом план
-- отримав явно — він лишається таким, як є. Решту перерахує core під час запуску.
UPDATE load_item i
SET plan_source = 'FIXED'
FROM semester s
WHERE s.id = i.semester_id
  AND i.planned_hours <> round(i.hours_per_week * COALESCE(s.weeks_count,
        (date_trunc('week', s.ends_on)::date - date_trunc('week', s.starts_on)::date) / 7 + 1));
