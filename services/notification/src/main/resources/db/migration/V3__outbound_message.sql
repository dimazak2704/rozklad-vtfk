-- черга вихідних повідомлень і журнал доставки (одна таблиця: рядок = одне повідомлення)
CREATE TABLE outbound_message
(
    id                  uuid          NOT NULL,
    -- строгий порядок у межах чату: воркер бере лише найстаріше незавершене повідомлення чату
    queue_seq           bigint GENERATED ALWAYS AS IDENTITY,
    chat_id             bigint        NOT NULL,
    binding_id          uuid,
    binding_type        varchar(20),
    chat_title          varchar(200),
    purpose             varchar(40)   NOT NULL,
    kind                varchar(20)   NOT NULL,
    text                text,
    reply_markup        jsonb,
    media_ref           varchar(100),
    edit_message_id     bigint,
    correlation_id      uuid,
    order_id            uuid,
    event_id            uuid,
    dedup_key           varchar(200),
    status              varchar(20)   NOT NULL,
    attempts            integer       NOT NULL DEFAULT 0,
    next_attempt_at     timestamptz   NOT NULL,
    locked_until        timestamptz,
    last_error          varchar(1000),
    telegram_message_id bigint,
    created_at          timestamptz   NOT NULL,
    updated_at          timestamptz   NOT NULL,
    sent_at             timestamptz,
    CONSTRAINT pk_outbound_message PRIMARY KEY (id),
    CONSTRAINT uq_outbound_message_queue_seq UNIQUE (queue_seq),
    -- ідемпотентність: (eventId, chatId, частина) або (дайджест, дата, чат)
    CONSTRAINT uq_outbound_message_dedup_key UNIQUE (dedup_key),
    CONSTRAINT ck_outbound_message_status CHECK (status IN ('QUEUED', 'RETRYING', 'SENT', 'FAILED')),
    CONSTRAINT ck_outbound_message_kind CHECK (kind IN ('TEXT', 'PHOTO', 'EDIT_TEXT')),
    CONSTRAINT ck_outbound_message_purpose CHECK (purpose IN ('ORDER_GROUP', 'ORDER_TEACHER', 'ORDER_ADMIN',
        'SUBSTITUTION_REQUEST', 'SUBSTITUTION_REMINDER', 'SUBSTITUTION_STATUS', 'SUBSTITUTION_ADMIN',
        'ABSENCE_ADMIN', 'GENERATION_ADMIN', 'DIGEST', 'TEST')),
    CONSTRAINT ck_outbound_message_binding_type CHECK (binding_type IS NULL
        OR binding_type IN ('GROUP_CHAT', 'TEACHER', 'ADMIN_CHAT', 'SUBSCRIBER')),
    CONSTRAINT ck_outbound_message_payload CHECK ((kind = 'PHOTO' AND media_ref IS NOT NULL)
        OR (kind = 'TEXT' AND text IS NOT NULL)
        OR (kind = 'EDIT_TEXT' AND text IS NOT NULL AND edit_message_id IS NOT NULL)),
    CONSTRAINT ck_outbound_message_attempts CHECK (attempts >= 0)
);

CREATE INDEX idx_outbound_message_due ON outbound_message (next_attempt_at)
    WHERE status IN ('QUEUED', 'RETRYING');
CREATE INDEX idx_outbound_message_chat_pending ON outbound_message (chat_id, queue_seq)
    WHERE status IN ('QUEUED', 'RETRYING');
CREATE INDEX idx_outbound_message_order ON outbound_message (order_id) WHERE order_id IS NOT NULL;
CREATE INDEX idx_outbound_message_created_at ON outbound_message (created_at);
