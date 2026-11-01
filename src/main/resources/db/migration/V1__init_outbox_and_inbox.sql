-- Transactional Outbox / Idempotent Inbox.
--
-- These two tables are what makes "never call KafkaTemplate.send inside a business
-- transaction" enforceable instead of aspirational: the event is written here, in the same
-- database transaction as the state change, and a separate relay publishes it afterwards.

CREATE TABLE outbox_events
(
    id           UUID PRIMARY KEY,
    tenant_id    UUID         NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    topic        VARCHAR(255) NOT NULL,
    event_type   VARCHAR(255) NOT NULL,
    payload_json TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    processed_at TIMESTAMPTZ,
    -- Optimistic lock. NOT NULL with no DEFAULT on purpose: Spring Data JDBC always supplies
    -- the value, and a DEFAULT would quietly mask a mapper that forgot to carry it. The
    -- mapper must pass null for a new row so Spring Data JDBC issues an INSERT, not an UPDATE.
    version      BIGINT       NOT NULL
);

-- Partial index: the relay only ever asks for unprocessed rows, so the index stays small
-- no matter how large the table grows.
CREATE INDEX idx_outbox_unprocessed
    ON outbox_events (created_at ASC)
    WHERE processed_at IS NULL;

-- Deduplication for at-least-once delivery. The PRIMARY KEY is the whole mechanism:
-- the consumer does INSERT ... ON CONFLICT DO NOTHING and checks the affected row count,
-- which is atomic. A SELECT-then-INSERT would race between concurrent consumers.
CREATE TABLE inbox_events
(
    event_id     UUID PRIMARY KEY,
    topic        VARCHAR(255) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL
);
