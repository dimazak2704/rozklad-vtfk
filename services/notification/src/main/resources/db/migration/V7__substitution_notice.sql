-- Режим NOTIFY (DOMAIN §7): викладачу повідомляють про вже підтверджену заміну, він відповідає «Добре»
-- або «Не можу». mode — як надіслано (CONFIRM — прохання, NOTIFY — повідомлення); teacher_answer — відповідь
-- на повідомлення; change_kind, original_discipline_name, reason_code — рядок «🔁 Замість …» заміни дисципліни.
ALTER TABLE substitution_request
    ADD COLUMN mode                    varchar(16) NOT NULL DEFAULT 'CONFIRM',
    ADD COLUMN teacher_answer          varchar(16),
    ADD COLUMN change_kind             varchar(24),
    ADD COLUMN original_discipline_name varchar(300),
    ADD COLUMN reason_code             varchar(16),
    ADD CONSTRAINT ck_substitution_request_mode CHECK (mode IN ('CONFIRM', 'NOTIFY')),
    ADD CONSTRAINT ck_substitution_request_teacher_answer CHECK (teacher_answer IS NULL
        OR teacher_answer IN ('CONFIRM', 'DECLINE'));

-- попередження «не підключив Telegram» тепер і для повідомлень NOTIFY (вони одразу CONFIRMED)
DROP INDEX idx_substitution_request_no_telegram;
CREATE INDEX idx_substitution_request_no_telegram ON substitution_request (no_telegram_at)
    WHERE no_telegram_at IS NOT NULL AND admin_warned_at IS NULL;

ALTER TABLE outbound_message DROP CONSTRAINT ck_outbound_message_purpose;
ALTER TABLE outbound_message
    ADD CONSTRAINT ck_outbound_message_purpose CHECK (purpose IN ('ORDER_GROUP', 'ORDER_TEACHER', 'ORDER_ADMIN',
        'SUBSTITUTION_REQUEST', 'SUBSTITUTION_NOTICE', 'SUBSTITUTION_REMINDER', 'SUBSTITUTION_STATUS',
        'SUBSTITUTION_ADMIN', 'ABSENCE_ADMIN', 'GENERATION_ADMIN', 'DIGEST', 'TEST'));
