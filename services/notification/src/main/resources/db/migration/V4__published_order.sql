-- опубліковані розпорядження з події order.published: з них публічно віддаються DOCX/PDF/PNG
CREATE TABLE published_order
(
    order_id     uuid        NOT NULL,
    number       integer,
    order_date   date        NOT NULL,
    kind         varchar(20) NOT NULL,
    parent_id    uuid,
    payload      jsonb       NOT NULL,
    payload_hash varchar(64) NOT NULL,
    event_id     uuid        NOT NULL,
    received_at  timestamptz NOT NULL,
    CONSTRAINT pk_published_order PRIMARY KEY (order_id),
    CONSTRAINT ck_published_order_kind CHECK (kind IN ('ORDER', 'AMENDMENT'))
);

CREATE INDEX idx_published_order_order_date ON published_order (order_date);
