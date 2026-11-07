-- importer: завдання імпорту, вміст файлів (режим DATABASE), облік викликів ШІ

CREATE TABLE import_job
(
    id           uuid         NOT NULL,
    kind         varchar(32)  NOT NULL,
    status       varchar(16)  NOT NULL,
    file_name    varchar(255) NOT NULL,
    content_type varchar(150),
    file_type    varchar(8)   NOT NULL,
    file_size    bigint       NOT NULL,
    sha256       varchar(64)  NOT NULL,
    storage      varchar(16)  NOT NULL,
    storage_path varchar(500),
    semester_id  uuid,
    options      jsonb,
    draft        jsonb,
    -- зауваження розбору файлу; report = зауваження + рядки зіставлення з каталогом
    issues       jsonb,
    report       jsonb,
    summary      jsonb,
    mappings     jsonb,
    ai           jsonb,
    result       jsonb,
    error        text,
    created_by   varchar(100) NOT NULL,
    created_at   timestamptz  NOT NULL,
    updated_at   timestamptz  NOT NULL,
    parsed_at    timestamptz,
    applied_at   timestamptz,
    version      bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_import_job PRIMARY KEY (id),
    CONSTRAINT ck_import_job_kind CHECK (kind IN ('TIMETABLE_XLSX', 'DISCIPLINE_PROGRAM', 'CURRICULUM', 'ORDER_DOCUMENT')),
    CONSTRAINT ck_import_job_status CHECK (status IN
        ('UPLOADED', 'PARSING', 'NEEDS_REVIEW', 'READY', 'APPLYING', 'APPLIED', 'FAILED')),
    CONSTRAINT ck_import_job_file_type CHECK (file_type IN ('XLSX', 'DOCX', 'DOC', 'PDF', 'JPEG', 'PNG', 'WEBP', 'GIF')),
    CONSTRAINT ck_import_job_storage CHECK (storage IN ('DATABASE', 'FILESYSTEM')),
    CONSTRAINT ck_import_job_file_size CHECK (file_size > 0),
    -- у режимі FILESYSTEM шлях обов'язковий
    CONSTRAINT ck_import_job_storage_path CHECK (storage = 'DATABASE' OR storage_path IS NOT NULL)
);

CREATE INDEX idx_import_job_created_at ON import_job (created_at DESC);
CREATE INDEX idx_import_job_kind_status ON import_job (kind, status);
CREATE INDEX idx_import_job_status ON import_job (status);

CREATE TABLE import_file
(
    job_id  uuid  NOT NULL,
    content bytea NOT NULL,
    CONSTRAINT pk_import_file PRIMARY KEY (job_id),
    -- DEFERRABLE: файл і завдання зберігаються в одній транзакції в довільному порядку
    CONSTRAINT fk_import_file_job FOREIGN KEY (job_id) REFERENCES import_job (id) ON DELETE CASCADE
        DEFERRABLE INITIALLY DEFERRED
);

-- один рядок на виклик моделі; usage_date — день за Києвом (для денного бюджету)
CREATE TABLE ai_usage
(
    id            uuid           NOT NULL,
    occurred_at   timestamptz    NOT NULL,
    usage_date    date           NOT NULL,
    purpose       varchar(32)    NOT NULL,
    model         varchar(100)   NOT NULL,
    input_tokens  bigint         NOT NULL DEFAULT 0,
    output_tokens bigint         NOT NULL DEFAULT 0,
    cost_usd      numeric(12, 6) NOT NULL DEFAULT 0,
    outcome       varchar(16)    NOT NULL,
    job_id        uuid,
    CONSTRAINT pk_ai_usage PRIMARY KEY (id),
    CONSTRAINT fk_ai_usage_job FOREIGN KEY (job_id) REFERENCES import_job (id) ON DELETE SET NULL,
    CONSTRAINT ck_ai_usage_purpose CHECK (purpose IN
        ('ORDER_DOCUMENT', 'CURRICULUM', 'PROGRAM_HOURS', 'ABSENCE_TEXT')),
    CONSTRAINT ck_ai_usage_outcome CHECK (outcome IN ('SUCCESS', 'REFUSED', 'TRUNCATED', 'INVALID', 'FAILED')),
    CONSTRAINT ck_ai_usage_tokens CHECK (input_tokens >= 0 AND output_tokens >= 0),
    CONSTRAINT ck_ai_usage_cost CHECK (cost_usd >= 0)
);

CREATE INDEX idx_ai_usage_date ON ai_usage (usage_date);
CREATE INDEX idx_ai_usage_job ON ai_usage (job_id) WHERE job_id IS NOT NULL;
