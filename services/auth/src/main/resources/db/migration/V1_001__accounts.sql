-- Облікові записи сервісу входу (accounts, ролі, прив'язки Google). Ролі людей — лише ADMIN і TEACHER;
-- ролі сервісів задаються в налаштуваннях клієнтів, не тут.

CREATE TABLE account (
    id                  uuid PRIMARY KEY,                     -- = колишній id користувача Keycloak, якщо переноситься
    username            varchar(50),
    email               varchar(254),
    display_name        varchar(200) NOT NULL,
    password_hash       varchar(100),                         -- '{bcrypt}$2a$12$…'; NULL = без входу паролем
    password_changed_at timestamptz,
    enabled             boolean      NOT NULL DEFAULT true,
    disabled_reason     varchar(20)  CHECK (disabled_reason IN ('REVOKED', 'DEMO_OFF')),
    disabled_at         timestamptz,
    demo                boolean      NOT NULL DEFAULT false,
    last_login_at       timestamptz,
    created_at          timestamptz  NOT NULL,
    created_by          varchar(100) NOT NULL,
    updated_at          timestamptz  NOT NULL,
    updated_by          varchar(100) NOT NULL,
    version             bigint       NOT NULL DEFAULT 0,
    CONSTRAINT ck_account_identifier CHECK (username IS NOT NULL OR email IS NOT NULL),
    CONSTRAINT ck_account_username CHECK (username IS NULL OR username ~ '^[a-z][a-z0-9._-]{2,49}$'),
    CONSTRAINT ck_account_email CHECK (email IS NULL OR (email = lower(email) AND position('@' IN email) > 1)),
    CONSTRAINT ck_account_reason CHECK (disabled_reason IS NULL OR NOT enabled)
);
CREATE UNIQUE INDEX uq_account_username ON account (username) WHERE username IS NOT NULL;
CREATE UNIQUE INDEX uq_account_email ON account (email) WHERE email IS NOT NULL;

CREATE TABLE account_role (
    account_id uuid        NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    role       varchar(20) NOT NULL CHECK (role IN ('ADMIN', 'TEACHER')),
    PRIMARY KEY (account_id, role)
);
CREATE INDEX ix_account_role_role ON account_role (role);

-- Прив'язка Google за незмінним 'sub'
CREATE TABLE federated_identity (
    id            uuid PRIMARY KEY,
    account_id    uuid         NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    provider      varchar(20)  NOT NULL CHECK (provider IN ('google')),
    subject       varchar(255) NOT NULL,
    email_at_link varchar(254) NOT NULL,
    hosted_domain varchar(255),
    linked_at     timestamptz  NOT NULL,
    last_login_at timestamptz,
    CONSTRAINT uq_federated_subject UNIQUE (provider, subject),
    CONSTRAINT uq_federated_account_provider UNIQUE (account_id, provider)
);
