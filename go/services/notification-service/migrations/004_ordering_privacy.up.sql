-- Order ordering + deleted-user tombstones (09-data-model.md §3, §4.2, §10;
-- 05-ordering.md §4 cách 3; D-05). None of the new tables holds a raw order
-- subject or user id: keys are versioned HMACs (11-privacy.md §2).

-- ---------------------------------------------------------------------------
-- Per-source monotonic sequence on requests and deliveries
-- ---------------------------------------------------------------------------
ALTER TABLE notification_request ADD COLUMN source_sequence bigint;

CREATE INDEX idx_request_subject_sequence ON notification_request (source, subject, source_sequence)
    WHERE source_sequence IS NOT NULL;

ALTER TABLE notification_delivery ADD COLUMN source text;
ALTER TABLE notification_delivery ADD COLUMN source_sequence bigint;

-- Backfill rows written before this migration from their request.
UPDATE notification_delivery d
SET    source = r.source
FROM   notification_request r
WHERE  r.id = d.request_id
  AND  d.source IS NULL;
UPDATE notification_delivery SET source = 'unknown' WHERE source IS NULL;

ALTER TABLE notification_delivery ALTER COLUMN source SET NOT NULL;

-- ---------------------------------------------------------------------------
-- Highest Order notification sequence per (source, HMAC(subject)).
-- No TTL in phase 1: purging it earlier than the replay horizon would let an
-- old Order command come back after ingest_dedup expired.
-- ---------------------------------------------------------------------------
CREATE TABLE notification_subject_sequence (
    source                 text        NOT NULL,
    subject_key_version    smallint    NOT NULL,
    subject_key_hash       char(64)    NOT NULL,
    highest_sequence       bigint      NOT NULL CHECK (highest_sequence >= 1),
    highest_event_id       text        NOT NULL,
    highest_payload_sha256 text        NOT NULL,
    updated_at             timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (source, subject_key_version, subject_key_hash)
);

-- ---------------------------------------------------------------------------
-- Per-Order serialisation of provider calls. One row per ordering key, reused;
-- an expired lease is taken over by the next acquirer or removed by the reaper.
-- ---------------------------------------------------------------------------
CREATE TABLE notification_ordering_lease (
    ordering_key text        PRIMARY KEY,
    delivery_id  uuid        NOT NULL,
    lease_until  timestamptz NOT NULL,
    locked_by    text        NOT NULL
);

CREATE INDEX idx_ordering_lease_until ON notification_ordering_lease (lease_until);

-- ---------------------------------------------------------------------------
-- user.deleted tombstones: replay of old events must not rebuild an inbox.
-- ---------------------------------------------------------------------------
CREATE TABLE deleted_user_tombstone (
    user_key_version smallint    NOT NULL,
    user_key_hash    char(64)    NOT NULL,
    deleted_at       timestamptz NOT NULL,
    PRIMARY KEY (user_key_version, user_key_hash)
);
