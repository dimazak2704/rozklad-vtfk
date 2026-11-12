-- Посилання для пароля (DESIGN §4.6): той самий одноразовий токен, що й запрошення, але для облікового запису
-- сервісу входу, а не для викладача. Рівно одна ціль: викладач (запрошення) або обліковий запис (пароль).
ALTER TABLE invite
    ALTER COLUMN teacher_id DROP NOT NULL,
    ADD COLUMN account_id uuid,
    ADD CONSTRAINT ck_invite_target CHECK ((teacher_id IS NULL) <> (account_id IS NULL));

CREATE INDEX idx_invite_account_open ON invite (account_id) WHERE used_at IS NULL AND revoked_at IS NULL;
