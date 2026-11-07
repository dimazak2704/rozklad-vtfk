-- shared: реєстр публікацій подій Spring Modulith 2.x (transactional outbox).
-- DDL узято з spring-modulith-events-jdbc 2.1.2 (schemas/v2/schema-postgresql.sql);
-- маппінг сутності JpaEventPublication перевіряється Hibernate (ddl-auto=validate).
CREATE TABLE event_publication
(
    id                     uuid                     NOT NULL,
    listener_id            text                     NOT NULL,
    event_type             text                     NOT NULL,
    serialized_event       text                     NOT NULL,
    publication_date       timestamp with time zone NOT NULL,
    completion_date        timestamp with time zone,
    status                 text,
    completion_attempts    integer,
    last_resubmission_date timestamp with time zone,
    CONSTRAINT pk_event_publication PRIMARY KEY (id)
);

CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON event_publication (completion_date);
