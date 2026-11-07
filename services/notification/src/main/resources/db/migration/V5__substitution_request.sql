-- запити на підтвердження заміни: потрібні, щоб редагувати повідомлення й нагадувати після дедлайну
CREATE TABLE substitution_request
(
    change_id             uuid         NOT NULL,
    teacher_id            uuid         NOT NULL,
    lesson_date           date         NOT NULL,
    pair                  integer      NOT NULL,
    starts_at             time,
    ends_at               time,
    group_code            varchar(20),
    subgroup              integer      NOT NULL DEFAULT 0,
    discipline_name       varchar(300),
    original_teacher_name varchar(200),
    room_name             varchar(100),
    deadline              timestamptz,
    status                varchar(30)  NOT NULL,
    reminded_at           timestamptz,
    created_at            timestamptz  NOT NULL,
    updated_at            timestamptz  NOT NULL,
    version               bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_substitution_request PRIMARY KEY (change_id),
    CONSTRAINT ck_substitution_request_status CHECK (status IN ('DRAFT', 'PENDING_CONFIRMATION', 'CONFIRMED',
        'DECLINED', 'CANCELLED', 'APPLIED'))
);

CREATE INDEX idx_substitution_request_deadline ON substitution_request (deadline)
    WHERE status = 'PENDING_CONFIRMATION' AND reminded_at IS NULL AND deadline IS NOT NULL;

-- надіслані викладачу повідомлення (запит і нагадування), які редагуються до фінального стану
CREATE TABLE substitution_message
(
    id         uuid        NOT NULL,
    change_id  uuid        NOT NULL,
    chat_id    bigint      NOT NULL,
    message_id bigint      NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT pk_substitution_message PRIMARY KEY (id),
    CONSTRAINT fk_substitution_message_request FOREIGN KEY (change_id)
        REFERENCES substitution_request (change_id) ON DELETE CASCADE,
    CONSTRAINT uq_substitution_message_chat_message UNIQUE (chat_id, message_id)
);

CREATE INDEX idx_substitution_message_change_id ON substitution_message (change_id);
