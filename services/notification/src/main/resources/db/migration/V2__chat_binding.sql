-- прив'язки чатів Telegram: чат групи, викладач, службовий чат, підписник (CONTRACTS §6)
CREATE TABLE chat_binding
(
    id             uuid         NOT NULL,
    chat_id        bigint       NOT NULL,
    type           varchar(20)  NOT NULL,
    group_id       uuid,
    group_code     varchar(20),
    teacher_id     uuid,
    title          varchar(200) NOT NULL,
    digest_enabled boolean      NOT NULL DEFAULT false,
    created_at     timestamptz  NOT NULL,
    updated_at     timestamptz  NOT NULL,
    version        bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_chat_binding PRIMARY KEY (id),
    CONSTRAINT uq_chat_binding_chat_type UNIQUE (chat_id, type),
    CONSTRAINT ck_chat_binding_type CHECK (type IN ('GROUP_CHAT', 'TEACHER', 'ADMIN_CHAT', 'SUBSCRIBER')),
    CONSTRAINT ck_chat_binding_group CHECK (type NOT IN ('GROUP_CHAT', 'SUBSCRIBER')
        OR (group_id IS NOT NULL AND group_code IS NOT NULL)),
    CONSTRAINT ck_chat_binding_teacher CHECK (type <> 'TEACHER' OR teacher_id IS NOT NULL)
);

-- викладач отримує запити на заміну в одному чаті
CREATE UNIQUE INDEX uq_chat_binding_teacher ON chat_binding (teacher_id) WHERE type = 'TEACHER';
CREATE INDEX idx_chat_binding_group ON chat_binding (group_id) WHERE group_id IS NOT NULL;

-- одноразові коди прив'язки (зберігається лише SHA-256 коду)
CREATE TABLE binding_code
(
    id           uuid         NOT NULL,
    code_hash    varchar(64)  NOT NULL,
    type         varchar(20)  NOT NULL,
    group_id     uuid,
    group_code   varchar(20),
    teacher_id   uuid,
    label        varchar(200) NOT NULL,
    created_by   varchar(100) NOT NULL,
    created_at   timestamptz  NOT NULL,
    expires_at   timestamptz  NOT NULL,
    used_at      timestamptz,
    used_chat_id bigint,
    CONSTRAINT pk_binding_code PRIMARY KEY (id),
    CONSTRAINT uq_binding_code_hash UNIQUE (code_hash),
    CONSTRAINT ck_binding_code_type CHECK (type IN ('GROUP_CHAT', 'TEACHER', 'ADMIN_CHAT', 'SUBSCRIBER')),
    CONSTRAINT ck_binding_code_group CHECK (type NOT IN ('GROUP_CHAT', 'SUBSCRIBER')
        OR (group_id IS NOT NULL AND group_code IS NOT NULL)),
    CONSTRAINT ck_binding_code_teacher CHECK (type <> 'TEACHER' OR teacher_id IS NOT NULL),
    CONSTRAINT ck_binding_code_used CHECK ((used_at IS NULL) = (used_chat_id IS NULL))
);

CREATE INDEX idx_binding_code_expires_at ON binding_code (expires_at);
