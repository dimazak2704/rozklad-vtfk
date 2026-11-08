-- substitution: заняття, які скасував блок пар. BLOCK не має одного «оригіналу» (original_lesson_id),
-- тому без цього зв'язку скасовані ним пари не показували зміну й причину, двоїлися поруч із блоком,
-- не рахувалися як пропущені й видалялися наступним оновленням як «ніким не змінені».
CREATE TABLE change_replaced_lesson (
    change_id uuid NOT NULL,
    lesson_id uuid NOT NULL,
    CONSTRAINT pk_change_replaced_lesson PRIMARY KEY (change_id, lesson_id),
    CONSTRAINT fk_change_replaced_lesson_change FOREIGN KEY (change_id) REFERENCES change (id) ON DELETE CASCADE,
    CONSTRAINT fk_change_replaced_lesson_lesson FOREIGN KEY (lesson_id) REFERENCES lesson (id) ON DELETE CASCADE
);

CREATE INDEX idx_change_replaced_lesson_lesson ON change_replaced_lesson (lesson_id);

-- уже застосовані блоки: скасовані заняття когорти в парах блоку, яких не скасувала інша зміна
INSERT INTO change_replaced_lesson (change_id, lesson_id)
SELECT c.id, l.id
FROM change c
JOIN lesson l ON l.group_id = c.group_id AND l.date = c.date AND l.pair_number BETWEEN c.pair_from AND c.pair_to
WHERE c.kind = 'BLOCK' AND c.status = 'APPLIED' AND l.status = 'CANCELLED'
  AND (c.subgroup_number = 0 OR l.subgroup_number = 0 OR l.subgroup_number = c.subgroup_number)
  AND l.change_id IS DISTINCT FROM c.id
  AND NOT EXISTS (SELECT 1 FROM change o
                  WHERE (o.original_lesson_id = l.id OR o.swap_lesson_id = l.id) AND o.status = 'APPLIED');
