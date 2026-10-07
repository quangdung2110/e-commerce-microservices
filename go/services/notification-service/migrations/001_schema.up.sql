-- Notification Service schema. Design: docs/notification/09-data-model.md.
-- Additions agreed at implementation time (kept in sync with the doc):
--   notification_request.event_time, .source_ref
--   notification_delivery.subject, .source_ref
--   ops_control / ops_idempotency / ops_audit
--   SKIPPED delivery rows for channels dropped by Decide (skip_reason).

-- ---------------------------------------------------------------------------
-- Dedup ledgers (not partitioned: unique keys must not include time)
-- ---------------------------------------------------------------------------
CREATE TABLE ingest_dedup (
    source       text        NOT NULL,
    event_id     text        NOT NULL,
    received_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (source, event_id)
);
CREATE INDEX idx_ingest_dedup_purge ON ingest_dedup (received_at);

CREATE TABLE delivery_dedup (
    dedup_key   text        NOT NULL,
    channel     text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (dedup_key, channel)
);
CREATE INDEX idx_delivery_dedup_purge ON delivery_dedup (created_at);

CREATE TABLE provider_event_dedup (
    provider           text        NOT NULL,
    provider_event_id  text        NOT NULL,
    received_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, provider_event_id)
);
CREATE INDEX idx_provider_event_dedup_purge ON provider_event_dedup (received_at);

-- ---------------------------------------------------------------------------
-- notification_request (partitioned by month on received_at)
-- ---------------------------------------------------------------------------
CREATE TABLE notification_request (
    id               uuid        NOT NULL,
    source           text        NOT NULL,
    event_id         text        NOT NULL,
    event_type       text        NOT NULL,
    event_time       timestamptz,
    subject          text,
    input_kind       text        NOT NULL CHECK (input_kind IN ('request', 'fact')),
    template         text,
    lane             text        CHECK (lane IN ('critical', 'transactional', 'bulk')),
    user_id          uuid,
    dedup_key        text,
    expires_at       timestamptz,
    template_data    jsonb,
    source_ref       text,
    correlation_id   text,
    traceparent      text,
    decision         text        NOT NULL CHECK (decision IN ('DECIDED', 'SKIPPED', 'FAILED', 'EXPIRED', 'IGNORED')),
    decision_reason  text,
    received_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id, received_at)
) PARTITION BY RANGE (received_at);

CREATE INDEX idx_request_user     ON notification_request (user_id, received_at DESC);
CREATE INDEX idx_request_subject  ON notification_request (subject) WHERE subject IS NOT NULL;
CREATE INDEX idx_request_decision ON notification_request (decision, received_at) WHERE decision <> 'DECIDED';
CREATE INDEX idx_request_id       ON notification_request (id);

-- ---------------------------------------------------------------------------
-- notification_delivery (queue + state; partitioned by month on created_at)
-- ---------------------------------------------------------------------------
CREATE TABLE notification_delivery (
    id                   uuid        NOT NULL,
    request_id           uuid        NOT NULL,
    user_id              uuid,
    subject              text,
    lane                 text        NOT NULL CHECK (lane IN ('critical', 'transactional', 'bulk')),
    channel              text        NOT NULL CHECK (channel IN ('email', 'in_app')),
    template             text        NOT NULL,
    template_version     text,
    status               text        NOT NULL CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'RETRY', 'FAILED', 'DELIVERED', 'BOUNCED', 'EXPIRED', 'SKIPPED')),
    attempt              int         NOT NULL DEFAULT 0,
    next_attempt_at      timestamptz NOT NULL,
    lease_until          timestamptz,
    locked_by            text,
    dedup_key            text,
    ordering_key         text,
    expires_at           timestamptz NOT NULL,
    provider             text        CHECK (provider IN ('ses', 'mailpit', 'inbox')),
    provider_message_id  text,
    possibly_duplicate   boolean     NOT NULL DEFAULT false,
    last_error_code      text,
    last_error           text,
    skip_reason          text,
    source_ref           text,
    sent_at              timestamptz,
    delivered_at         timestamptz,
    finished_at          timestamptz,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

-- Only rows waiting to be claimed: small, always in RAM (02-pipeline.md §5.1).
CREATE INDEX idx_delivery_ready
    ON notification_delivery (lane, channel, next_attempt_at)
    WHERE status IN ('PENDING', 'RETRY');
CREATE INDEX idx_delivery_leased
    ON notification_delivery (lease_until)
    WHERE status = 'SENDING';
CREATE INDEX idx_delivery_provider_msg
    ON notification_delivery (provider, provider_message_id)
    WHERE provider_message_id IS NOT NULL;
CREATE INDEX idx_delivery_request ON notification_delivery (request_id);
CREATE INDEX idx_delivery_user    ON notification_delivery (user_id, created_at DESC);
CREATE INDEX idx_delivery_subject ON notification_delivery (subject, created_at DESC) WHERE subject IS NOT NULL;
CREATE INDEX idx_delivery_id      ON notification_delivery (id);
CREATE INDEX idx_delivery_failed  ON notification_delivery (lane, finished_at) WHERE status = 'FAILED';

-- autovacuum tuning (09-data-model.md §4.2) cannot be set on a partitioned
-- parent; notification_ensure_partition (002) applies it to every partition.

-- ---------------------------------------------------------------------------
-- provider_event (Track queue; partitioned by month on received_at)
-- ---------------------------------------------------------------------------
CREATE TABLE provider_event (
    id                   bigint      GENERATED ALWAYS AS IDENTITY,
    provider             text        NOT NULL,
    provider_event_id    text        NOT NULL,
    provider_message_id  text,
    event_type           text        NOT NULL,
    occurred_at          timestamptz,
    raw                  jsonb       NOT NULL,
    status               text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSED', 'UNMATCHED', 'IGNORED')),
    attempt              int         NOT NULL DEFAULT 0,
    next_attempt_at      timestamptz NOT NULL DEFAULT now(),
    received_at          timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id, received_at)
) PARTITION BY RANGE (received_at);

CREATE INDEX idx_provider_event_ready
    ON provider_event (next_attempt_at)
    WHERE status IN ('PENDING', 'UNMATCHED');
CREATE INDEX idx_provider_event_msg ON provider_event (provider_message_id);

-- ---------------------------------------------------------------------------
-- user_preference (replica), suppression
-- ---------------------------------------------------------------------------
CREATE TABLE user_preference (
    user_id            uuid        PRIMARY KEY,
    email_enabled      boolean     NOT NULL,
    in_app_enabled     boolean     NOT NULL,
    marketing_enabled  boolean     NOT NULL,
    timezone           text,
    locale             text,
    version            bigint      NOT NULL,
    synced_at          timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE suppression (
    id                 uuid        PRIMARY KEY,
    user_id            uuid,
    email_hash         text        NOT NULL,
    reason             text        NOT NULL CHECK (reason IN ('HARD_BOUNCE', 'COMPLAINT', 'SOFT_BOUNCE', 'MANUAL')),
    source             text        NOT NULL CHECK (source IN ('ses', 'ops')),
    until              timestamptz,
    evidence_event_id  bigint,
    superseded_at      timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_suppression_user ON suppression (user_id) WHERE superseded_at IS NULL;
CREATE INDEX idx_suppression_hash ON suppression (email_hash) WHERE superseded_at IS NULL;

-- ---------------------------------------------------------------------------
-- inbox_item (in-app read model; partitioned by month on created_at)
-- ---------------------------------------------------------------------------
CREATE TABLE inbox_item (
    id           uuid        NOT NULL,
    user_id      uuid        NOT NULL,
    template     text        NOT NULL,
    title        text        NOT NULL,
    body         text        NOT NULL,
    deep_link    text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    read_at      timestamptz,
    archived_at  timestamptz,
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE INDEX idx_inbox_user_list   ON inbox_item (user_id, created_at DESC, id DESC) WHERE archived_at IS NULL;
CREATE INDEX idx_inbox_user_unread ON inbox_item (user_id) WHERE read_at IS NULL AND archived_at IS NULL;
CREATE INDEX idx_inbox_id          ON inbox_item (id);

-- ---------------------------------------------------------------------------
-- outbox (Debezium Outbox Event Router column names)
-- ---------------------------------------------------------------------------
CREATE TABLE outbox (
    id             uuid        PRIMARY KEY,
    aggregatetype  text        NOT NULL,
    aggregateid    text        NOT NULL,
    type           text        NOT NULL,
    payload        jsonb       NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    published_at   timestamptz
);
CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_published   ON outbox (published_at) WHERE published_at IS NOT NULL;

-- ---------------------------------------------------------------------------
-- ops: control switches, idempotency, audit (16-operations.md §2)
-- ---------------------------------------------------------------------------
CREATE TABLE ops_control (
    key         text        PRIMARY KEY,
    value       jsonb       NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    updated_by  text
);

CREATE TABLE ops_idempotency (
    key           text        PRIMARY KEY,
    request_hash  text        NOT NULL,
    status        int,
    response      jsonb,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_ops_idempotency_purge ON ops_idempotency (created_at);

CREATE TABLE ops_audit (
    id          uuid        PRIMARY KEY,
    principal   text        NOT NULL,
    operation   text        NOT NULL,
    params      jsonb       NOT NULL,
    result      text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_ops_audit_time ON ops_audit (created_at);
