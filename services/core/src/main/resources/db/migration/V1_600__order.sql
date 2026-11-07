-- order: розпорядження (order — зарезервоване слово, тому order_document) та їхні рядки

CREATE TABLE order_document
(
    id                 uuid         NOT NULL,
    semester_id        uuid         NOT NULL,
    number             integer,
    date               date         NOT NULL,
    kind               varchar(16)  NOT NULL DEFAULT 'ORDER',
    parent_id          uuid,
    status             varchar(16)  NOT NULL DEFAULT 'DRAFT',
    title              varchar(300) NOT NULL,
    weekday_label      varchar(20),
    parity_label       varchar(20),
    signatory_position varchar(200),
    signatory_name     varchar(200),
    notes              jsonb        NOT NULL DEFAULT '[]'::jsonb,
    published_at       timestamptz,
    published_by       varchar(100),
    created_at         timestamptz  NOT NULL,
    updated_at         timestamptz  NOT NULL,
    version            bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_order_document PRIMARY KEY (id),
    CONSTRAINT fk_order_document_semester FOREIGN KEY (semester_id) REFERENCES semester (id),
    CONSTRAINT fk_order_document_parent FOREIGN KEY (parent_id) REFERENCES order_document (id),
    CONSTRAINT ck_order_document_kind CHECK (kind IN ('ORDER', 'AMENDMENT')),
    CONSTRAINT ck_order_document_status CHECK (status IN ('DRAFT', 'PUBLISHED')),
    CONSTRAINT ck_order_document_parent CHECK ((kind = 'ORDER') = (parent_id IS NULL)),
    CONSTRAINT ck_order_document_number CHECK (number IS NULL OR number > 0),
    CONSTRAINT ck_order_document_published CHECK (status <> 'PUBLISHED'
        OR (number IS NOT NULL AND published_at IS NOT NULL)),
    CONSTRAINT ck_order_document_notes CHECK (jsonb_typeof(notes) = 'array')
);

-- нумерація наскрізна в межах семестру; номер присвоюється під час публікації
CREATE UNIQUE INDEX uq_order_document_number ON order_document (semester_id, number) WHERE number IS NOT NULL;
-- одне основне розпорядження на дату (доповнення мають kind = AMENDMENT)
CREATE UNIQUE INDEX uq_order_document_main_per_date ON order_document (semester_id, date) WHERE kind = 'ORDER';
CREATE INDEX idx_order_document_semester ON order_document (semester_id);
CREATE INDEX idx_order_document_date ON order_document (date);
CREATE INDEX idx_order_document_parent ON order_document (parent_id);

-- денормалізований текстовий знімок (документ, що має юридичну силу)
CREATE TABLE order_item
(
    id              uuid        NOT NULL,
    order_id        uuid        NOT NULL,
    section         varchar(16) NOT NULL DEFAULT 'CHANGES',
    position        integer     NOT NULL,
    group_code      varchar(16) NOT NULL,
    pair_label      varchar(16) NOT NULL,
    from_discipline varchar(300),
    from_teacher    varchar(200),
    reason_text     varchar(100),
    to_discipline   varchar(300),
    to_teacher      varchar(200),
    room_text       varchar(100),
    row_type        varchar(16) NOT NULL,
    change_id       uuid,
    CONSTRAINT pk_order_item PRIMARY KEY (id),
    CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES order_document (id) ON DELETE CASCADE,
    CONSTRAINT fk_order_item_change FOREIGN KEY (change_id) REFERENCES change (id) ON DELETE SET NULL,
    CONSTRAINT ck_order_item_section CHECK (section IN ('CHANGES', 'ROOM_CHANGES')),
    CONSTRAINT ck_order_item_position CHECK (position >= 0),
    CONSTRAINT ck_order_item_row_type CHECK (row_type IN ('REPLACEMENT', 'CANCELLATION', 'ADDITIONAL', 'BLOCK',
                                                          'FINISHED', 'AS_SCHEDULED', 'ROOM_CHANGE'))
);

CREATE INDEX idx_order_item_order ON order_item (order_id, section, position);
CREATE INDEX idx_order_item_change ON order_item (change_id);

ALTER TABLE change
    ADD CONSTRAINT fk_change_order FOREIGN KEY (order_id) REFERENCES order_document (id) ON DELETE SET NULL;

CREATE INDEX idx_change_order ON change (order_id);
