-- identity: запрошення викладачів (у БД лише SHA-256 від токена); settings: ключ/значення

CREATE TABLE invite
(
    id         uuid         NOT NULL,
    teacher_id uuid         NOT NULL,
    token_hash varchar(64)  NOT NULL,
    expires_at timestamptz  NOT NULL,
    used_at    timestamptz,
    created_by varchar(100),
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL,
    version    bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_invite PRIMARY KEY (id),
    CONSTRAINT uq_invite_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_invite_teacher FOREIGN KEY (teacher_id) REFERENCES teacher (id) ON DELETE CASCADE
);

CREATE INDEX idx_invite_teacher ON invite (teacher_id);

CREATE TABLE setting
(
    key        varchar(100) NOT NULL,
    value      jsonb        NOT NULL,
    updated_at timestamptz  NOT NULL,
    updated_by varchar(100),
    CONSTRAINT pk_setting PRIMARY KEY (key)
);
