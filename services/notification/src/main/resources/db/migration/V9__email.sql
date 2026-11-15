-- e-mail: другий канал сповіщень поряд із Telegram (docs/modules/notification.md «E-mail»).
-- Два кроки, як у черзі Telegram: подія пише намір (email_notice) у своїй транзакції, воркер потім знаходить
-- адресатів (core), рендерить лист і кладе його в email_message (= журнал листів).

-- намір: хто має отримати лист (викладач заміни, усі диспетчери, конкретний обліковий запис) і знімок даних
CREATE TABLE email_notice
(
    id              uuid          NOT NULL,
    kind            varchar(40)   NOT NULL,
    target          varchar(16)   NOT NULL,
    teacher_id      uuid,
    account_id      uuid,
    -- id заміни / генерації / розпорядження
    correlation_id  uuid,
    lesson_date     date,
    lesson_ends_at  time,
    model           jsonb         NOT NULL,
    event_id        uuid,
    -- ідемпотентність: {eventId}:mail:{частина}, нагадування reminder:{заміна}:{строк}:mail
    dedup_key       varchar(200)  NOT NULL,
    status          varchar(16)   NOT NULL,
    attempts        integer       NOT NULL DEFAULT 0,
    next_attempt_at timestamptz   NOT NULL,
    locked_until    timestamptz,
    last_error      varchar(1000),
    created_at      timestamptz   NOT NULL,
    updated_at      timestamptz   NOT NULL,
    CONSTRAINT pk_email_notice PRIMARY KEY (id),
    CONSTRAINT uq_email_notice_dedup_key UNIQUE (dedup_key),
    CONSTRAINT ck_email_notice_target CHECK (target IN ('TEACHER', 'ADMINS', 'ACCOUNT')),
    CONSTRAINT ck_email_notice_status CHECK (status IN ('PENDING', 'RETRYING', 'EXPANDED', 'FAILED')),
    CONSTRAINT ck_email_notice_who CHECK ((target = 'TEACHER' AND teacher_id IS NOT NULL)
        OR (target = 'ACCOUNT' AND account_id IS NOT NULL)
        OR target = 'ADMINS'),
    CONSTRAINT ck_email_notice_attempts CHECK (attempts >= 0)
);

CREATE INDEX idx_email_notice_due ON email_notice (next_attempt_at) WHERE status IN ('PENDING', 'RETRYING');
CREATE INDEX idx_email_notice_created_at ON email_notice (created_at);

-- один лист одній людині; текст рендериться під час розгортання наміру, тож журнал показує саме те, що пішло
CREATE TABLE email_message
(
    id              uuid          NOT NULL,
    notice_id       uuid          NOT NULL,
    kind            varchar(40)   NOT NULL,
    account_id      uuid          NOT NULL,
    account_name    varchar(200),
    teacher_id      uuid,
    to_address      varchar(254)  NOT NULL,
    subject         varchar(250)  NOT NULL,
    body_html       text          NOT NULL,
    body_text       text          NOT NULL,
    correlation_id  uuid,
    lesson_date     date,
    lesson_ends_at  time,
    status          varchar(16)   NOT NULL,
    attempts        integer       NOT NULL DEFAULT 0,
    -- затримка «осідання» листів про заміну: новіший лист тієї ж заміни замінює ще не надісланий
    not_before      timestamptz   NOT NULL,
    next_attempt_at timestamptz   NOT NULL,
    locked_until    timestamptz,
    last_error      varchar(1000),
    smtp_message_id varchar(250),
    -- {noticeId}:{accountId}
    dedup_key       varchar(200)  NOT NULL,
    created_at      timestamptz   NOT NULL,
    updated_at      timestamptz   NOT NULL,
    sent_at         timestamptz,
    CONSTRAINT pk_email_message PRIMARY KEY (id),
    CONSTRAINT fk_email_message_notice FOREIGN KEY (notice_id) REFERENCES email_notice (id) ON DELETE CASCADE,
    CONSTRAINT uq_email_message_dedup_key UNIQUE (dedup_key),
    CONSTRAINT ck_email_message_status CHECK (status IN ('QUEUED', 'RETRYING', 'SENT', 'FAILED', 'SUPERSEDED')),
    CONSTRAINT ck_email_message_attempts CHECK (attempts >= 0)
);

CREATE INDEX idx_email_message_due ON email_message (next_attempt_at) WHERE status IN ('QUEUED', 'RETRYING');
CREATE INDEX idx_email_message_change ON email_message (correlation_id, account_id) WHERE correlation_id IS NOT NULL;
CREATE INDEX idx_email_message_notice ON email_message (notice_id);
CREATE INDEX idx_email_message_created_at ON email_message (created_at);

-- налаштування листів облікового запису (sub токена): головний перемикач і лише відхилення від типових
-- значень ({"ABSENCE_REPORTED": true}), тож нове типове значення дійде до всіх, хто його не змінював
CREATE TABLE email_preference
(
    account_id uuid        NOT NULL,
    enabled    boolean     NOT NULL DEFAULT true,
    overrides  jsonb       NOT NULL DEFAULT '{}'::jsonb,
    updated_at timestamptz NOT NULL,
    version    bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_email_preference PRIMARY KEY (account_id),
    CONSTRAINT ck_email_preference_overrides CHECK (jsonb_typeof(overrides) = 'object')
);
