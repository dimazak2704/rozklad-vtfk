-- Довірені пристрої (OWASP «device cookies»): браузер, з якого вже був успішний вхід паролем, отримує
-- cookie з випадковим токеном; тут лише SHA-256 токена і логіна. Спроби з такого браузера мають власний
-- лічильник блокування, тож чужі невдачі з тієї ж мережі (спільний NAT коледжу) не зачиняють вхід.
CREATE TABLE login_device (
    token_hash      bytea PRIMARY KEY,
    identifier_hash bytea NOT NULL,
    created_at      timestamptz NOT NULL
);
CREATE INDEX ix_login_device_created ON login_device (created_at);

-- спроба з довіреного пристрою: його token_hash; NULL — звичайна спроба (ключ — логін і мережа)
ALTER TABLE login_attempt ADD COLUMN device_hash bytea;
CREATE INDEX ix_login_attempt_device ON login_attempt (device_hash, occurred_at DESC) WHERE device_hash IS NOT NULL;

-- Глобальний ритм логіна після багатьох невдач з усіх мереж: не більше однієї перевірки пароля за
-- slowdown-delay. Рядок займає атомарний INSERT … ON CONFLICT, тож паралельні спроби не проходять разом.
CREATE TABLE login_gate (
    identifier_hash bytea PRIMARY KEY,
    next_attempt_at timestamptz NOT NULL
);
