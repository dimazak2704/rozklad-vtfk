-- ідемпотентні споживачі: оброблені eventId подій з notification.q.events
CREATE TABLE processed_event
(
    event_id     uuid        NOT NULL,
    processed_at timestamptz NOT NULL,
    CONSTRAINT pk_processed_event PRIMARY KEY (event_id)
);

CREATE INDEX idx_processed_event_processed_at ON processed_event (processed_at);
