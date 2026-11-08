-- Нове запрошення відкликає старі невикористані запрошення того самого викладача:
-- діє лише останнє посилання, а старе не може відновити доступ до облікового запису.
ALTER TABLE invite
    ADD COLUMN revoked_at timestamptz;

CREATE INDEX idx_invite_teacher_open ON invite (teacher_id) WHERE used_at IS NULL AND revoked_at IS NULL;
