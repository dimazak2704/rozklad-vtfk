-- Спроби входу: вхід для блокування і журнал. Ні паролів, ні сирих логінів тут немає: лише SHA-256
-- нормалізованого логіна. Без FK на account, щоб журнал переживав видалення облікового запису.
CREATE TABLE login_attempt (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at     timestamptz NOT NULL,
    method          varchar(10) NOT NULL CHECK (method IN ('PASSWORD', 'GOOGLE')),
    identifier_hash bytea,
    account_id      uuid,
    ip              inet,
    ip_prefix       cidr,                         -- /24 для IPv4, /64 для IPv6
    outcome         varchar(30) NOT NULL CHECK (outcome IN ('SUCCESS', 'BAD_CREDENTIALS', 'LOCKED', 'THROTTLED',
                    'DISABLED', 'REVOKED', 'NOT_ALLOWED', 'DOMAIN_MISMATCH', 'EMAIL_UNVERIFIED', 'IDENTITY_CONFLICT',
                    'PROVIDER_ERROR'))
);
CREATE INDEX ix_login_attempt_identifier ON login_attempt (identifier_hash, ip_prefix, occurred_at DESC);
CREATE INDEX ix_login_attempt_identifier_all ON login_attempt (identifier_hash, occurred_at DESC);
CREATE INDEX ix_login_attempt_prefix ON login_attempt (ip_prefix, occurred_at DESC);
CREATE INDEX ix_login_attempt_account ON login_attempt (account_id, occurred_at DESC);
