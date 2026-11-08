-- Режим NOTIFY (DOMAIN §7): заміна підтверджена одразу, викладачу лише повідомляють, і він відповідає
-- «Добре» або «Не можу». teacher_answer — остання відповідь призначеного викладача (CONFIRM = «Підтверджую»
-- або «Добре», DECLINE = «Не можу»); NULL — ще не відповідав у поточному раунді.
ALTER TABLE change DROP CONSTRAINT ck_change_approval_mode;
ALTER TABLE change
    ADD CONSTRAINT ck_change_approval_mode CHECK (approval_mode IN ('AUTO', 'CONFIRM', 'NOTIFY'));

ALTER TABLE change ADD COLUMN teacher_answer varchar(16);
ALTER TABLE change
    ADD CONSTRAINT ck_change_teacher_answer CHECK (teacher_answer IS NULL OR teacher_answer IN ('CONFIRM', 'DECLINE'));
