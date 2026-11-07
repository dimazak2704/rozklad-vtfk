-- substitution: заняття, яке переставляється (SWAP), і час нагадування про непідтверджену заміну

ALTER TABLE change
    ADD COLUMN swap_lesson_id uuid,
    ADD COLUMN reminded_at    timestamptz,
    ADD CONSTRAINT fk_change_swap_lesson FOREIGN KEY (swap_lesson_id) REFERENCES lesson (id),
    -- переставити можна лише разом із заміною на одну пару
    ADD CONSTRAINT ck_change_swap CHECK (swap_lesson_id IS NULL OR (kind = 'SWAP' AND pair_from = pair_to));

CREATE INDEX idx_change_swap_lesson ON change (swap_lesson_id);
