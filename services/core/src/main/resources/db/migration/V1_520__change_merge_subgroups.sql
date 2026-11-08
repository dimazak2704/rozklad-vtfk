-- substitution: «Об'єднати підгрупи» (MERGE_SUBGROUPS). Заняття відсутнього викладача (original_lesson_id)
-- і заняття іншої підгрупи тієї самої дисципліни на тій самій парі (swap_lesson_id) замінюються одним
-- заняттям для всієї групи з викладачем іншої підгрупи.

ALTER TABLE change DROP CONSTRAINT ck_change_kind;
ALTER TABLE change ADD CONSTRAINT ck_change_kind CHECK (kind IN ('REPLACE_TEACHER', 'REPLACE_DISCIPLINE', 'SWAP',
                                                                 'CANCEL', 'ADDITIONAL', 'ROOM_CHANGE', 'BLOCK',
                                                                 'DISCIPLINE_FINISHED', 'MERGE_SUBGROUPS'));

ALTER TABLE change DROP CONSTRAINT ck_change_swap;
-- друге заняття мають лише перестановка і об'єднання підгруп, обидві на одну пару
ALTER TABLE change ADD CONSTRAINT ck_change_swap CHECK (swap_lesson_id IS NULL
    OR (kind IN ('SWAP', 'MERGE_SUBGROUPS') AND pair_from = pair_to));

ALTER TABLE order_item DROP CONSTRAINT ck_order_item_row_type;
ALTER TABLE order_item ADD CONSTRAINT ck_order_item_row_type CHECK (row_type IN ('REPLACEMENT', 'CANCELLATION',
                                                                                 'ADDITIONAL', 'BLOCK', 'FINISHED',
                                                                                 'AS_SCHEDULED', 'ROOM_CHANGE',
                                                                                 'MERGE_SUBGROUPS'));
