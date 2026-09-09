-- Review Service — initial schema
-- doc_id: review-schema-0001
--
-- PostgreSQL 14+ with wal_level = logical. This schema is the executable source
-- of truth for Review persistence; keep docs/review/data.md in sync with it.

CREATE SCHEMA IF NOT EXISTS review;
SET search_path TO review, public;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------------------
-- 1. Types
-- ---------------------------------------------------------------------------

CREATE TYPE eligibility_status AS ENUM (
    'PENDING', -- the buyer may still create the one review
    'CLAIMED', -- a review was created; edit quota belongs to reviews.edit_count
    'EXPIRED'  -- the buyer did not create a review before expires_at
);

CREATE TYPE review_status AS ENUM ('APPROVED', 'HIDDEN_BY_REPORT', 'REMOVED');
CREATE TYPE report_reason AS ENUM ('SPAM', 'OFFENSIVE', 'FAKE', 'OTHER');
CREATE TYPE report_status AS ENUM ('PENDING', 'ACTIONED', 'DISMISSED');
CREATE TYPE review_media_type AS ENUM ('IMAGE', 'VIDEO');

CREATE TYPE review_media_asset_state AS ENUM (
    'ISSUED',         -- upload URL issued, object not verified yet
    'VERIFIED',       -- object existence and metadata were verified
    'ATTACHED',       -- attached to one active review_media row
    'DELETE_PENDING', -- storage worker must delete an unattached/detached object
    'DELETED'
);

CREATE TYPE review_media_deletion_reason AS ENUM (
    'UNATTACHED_EXPIRED', -- attachment deadline elapsed before attachment
    'REPLACED',           -- detached by a review media replacement
    'TAKEDOWN'            -- detached by a legal takedown
);

CREATE TYPE privacy_erasure_status AS ENUM ('RECEIVED', 'ANONYMIZED', 'PURGED', 'FAILED');
CREATE TYPE rating_export_session_status AS ENUM ('ACTIVE', 'EXPIRED');

-- ---------------------------------------------------------------------------
-- 2. review_eligibilities
--    Exactly one entitlement per purchased SKU in one globally unique order.
-- ---------------------------------------------------------------------------

CREATE TABLE review_eligibilities (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Opaque identifier owned by Order. Stored as text, never parsed, ordered by
    -- or reasoned about: its internal form is Order's decision to change.
    order_id              VARCHAR(64) NOT NULL,
    product_id            VARCHAR(64) NOT NULL,
    sku_id                VARCHAR(64) NOT NULL,
    variant_name_snapshot VARCHAR(255),
    owner_user_id         UUID,
    owner_erased_at       TIMESTAMPTZ,
    status                eligibility_status NOT NULL DEFAULT 'PENDING',
    -- Business timestamp copied from ORDER_COMPLETED. It is deliberately distinct
    -- from created_at because the event may be delivered after the review window.
    completed_at          TIMESTAMPTZ NOT NULL,
    expires_at            TIMESTAMPTZ NOT NULL,
    claimed_at            TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_eligibility_order_product_sku UNIQUE (order_id, product_id, sku_id),
    -- Parent key for the composite FK in reviews. It prevents a review from
    -- carrying a product/SKU different from its entitlement.
    CONSTRAINT uq_eligibility_id_product_sku UNIQUE (id, product_id, sku_id),
    CONSTRAINT ck_eligibility_expiry_after_completion CHECK (expires_at > completed_at),
    CONSTRAINT ck_eligibility_owner_erasure_consistent CHECK (
        (owner_user_id IS NOT NULL AND owner_erased_at IS NULL) OR
        (owner_user_id IS NULL AND owner_erased_at IS NOT NULL)
    ),
    CONSTRAINT ck_eligibility_state_consistent CHECK (
        (status = 'PENDING' AND claimed_at IS NULL) OR
        (status = 'CLAIMED' AND claimed_at IS NOT NULL) OR
        (status = 'EXPIRED' AND claimed_at IS NULL)
    )
);

CREATE INDEX ix_eligibility_owner_pending
    ON review_eligibilities (owner_user_id, expires_at)
    WHERE status = 'PENDING' AND owner_user_id IS NOT NULL;
CREATE INDEX ix_eligibility_expiry_sweep
    ON review_eligibilities (expires_at) WHERE status = 'PENDING';
-- Privacy lookup by subject. The index above cannot serve it: it is partial on
-- PENDING, while a review only exists once its eligibility is CLAIMED, so both
-- the immediate anonymize and the hard purge would scan the table. Partial on
-- NOT NULL so an already-purged row leaves the index instead of bloating it.
CREATE INDEX ix_eligibility_owner_erasure
    ON review_eligibilities (owner_user_id) WHERE owner_user_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. reviews
--    Current public representation. Its product/SKU are intentional, verified
--    denormalization for the hot public read path.
-- ---------------------------------------------------------------------------

CREATE TABLE reviews (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    eligibility_id        UUID NOT NULL,
    product_id            VARCHAR(64) NOT NULL,
    sku_id                VARCHAR(64) NOT NULL,
    author_display_name   VARCHAR(255) NOT NULL,
    author_anonymized_at  TIMESTAMPTZ,
    rating                SMALLINT NOT NULL,
    content               TEXT,
    edit_count            SMALLINT NOT NULL DEFAULT 0,
    status                review_status NOT NULL DEFAULT 'APPROVED',
    active_report_count   INTEGER NOT NULL DEFAULT 0,
    has_media             BOOLEAN NOT NULL DEFAULT FALSE,
    -- Start of the current moderation cycle: set when active_report_count rises
    -- from zero, and overwritten when a later report opens a new cycle. It is
    -- the sort key of the admin moderation queue for both of its states, so a
    -- case keeps one stable "opened at" meaning whether or not it got hidden.
    first_reported_at     TIMESTAMPTZ,
    -- When the report threshold last hid this review. Output only, never a sort
    -- key. Deliberately retained after a dismissal restores the review, so a
    -- repeatedly brigaded review stays visible in audit. Every index over these
    -- two columns is partial on status, so a stale value is inert.
    hidden_at             TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_review_eligibility UNIQUE (eligibility_id),
    CONSTRAINT fk_review_eligibility_product_sku
        FOREIGN KEY (eligibility_id, product_id, sku_id)
        REFERENCES review_eligibilities (id, product_id, sku_id) ON DELETE RESTRICT,
    CONSTRAINT ck_review_rating_range CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT ck_review_edit_count_range CHECK (edit_count BETWEEN 0 AND 1),
    CONSTRAINT ck_review_content_valid CHECK (
        content IS NULL OR char_length(btrim(content)) BETWEEN 1 AND 5000
    ),
    CONSTRAINT ck_review_active_report_count_non_negative CHECK (active_report_count >= 0),
    -- One-way implications: a hidden review must know when it was hidden and an
    -- open case must know when it opened, but leaving either state does not
    -- erase that history.
    CONSTRAINT ck_review_hidden_at_present CHECK (
        status <> 'HIDDEN_BY_REPORT' OR hidden_at IS NOT NULL
    ),
    CONSTRAINT ck_review_first_reported_at_present CHECK (
        active_report_count = 0 OR first_reported_at IS NOT NULL
    )
);

CREATE INDEX ix_review_product_recent
    ON reviews (product_id, created_at DESC, id DESC) WHERE status = 'APPROVED';
CREATE INDEX ix_review_product_sku_recent
    ON reviews (product_id, sku_id, created_at DESC, id DESC) WHERE status = 'APPROVED';
CREATE INDEX ix_review_product_rating_recent
    ON reviews (product_id, rating, created_at DESC, id DESC) WHERE status = 'APPROVED';
CREATE INDEX ix_review_product_with_media
    ON reviews (product_id, created_at DESC, id DESC)
    WHERE status = 'APPROVED' AND has_media = TRUE;
CREATE INDEX ix_review_aggregate_recount
    ON reviews (product_id, rating) WHERE status = 'APPROVED';
-- Every index above is partial on APPROVED with no report predicate, so without
-- these two the admin moderation queue would sequentially scan the whole review
-- table. They back the two states of that one queue and share the same keyset,
-- so paging means the same thing before and after a review gets hidden.
CREATE INDEX ix_review_moderation_reported
    ON reviews (first_reported_at, id)
    WHERE status = 'APPROVED' AND active_report_count > 0;
CREATE INDEX ix_review_moderation_hidden
    ON reviews (first_reported_at, id) WHERE status = 'HIDDEN_BY_REPORT';

-- ---------------------------------------------------------------------------
-- 4. review_media_assets and review_media
--    An uploaded object has an explicit lifecycle. URLs are generated at read
--    time and never persisted because presigned URLs expire.
-- ---------------------------------------------------------------------------

CREATE TABLE review_media_assets (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id       UUID,
    owner_erased_at     TIMESTAMPTZ,
    object_key          VARCHAR(512) NOT NULL,
    media_type          review_media_type NOT NULL,
    upload_state        review_media_asset_state NOT NULL DEFAULT 'ISSUED',
    content_type        VARCHAR(100),
    byte_size           BIGINT,
    checksum            VARCHAR(128),
    -- In v1 this is both the signed-upload instruction expiry and the deadline
    -- for attaching this otherwise-unattached asset to a review.
    expires_at          TIMESTAMPTZ NOT NULL,
    verified_at         TIMESTAMPTZ,
    attached_at         TIMESTAMPTZ,
    deletion_reason     review_media_deletion_reason,
    delete_requested_at TIMESTAMPTZ,
    deleted_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_media_asset_object_key UNIQUE (object_key),
    CONSTRAINT ck_media_asset_owner_erasure_consistent CHECK (
        (owner_user_id IS NOT NULL AND owner_erased_at IS NULL) OR
        (owner_user_id IS NULL AND owner_erased_at IS NOT NULL)
    ),
    CONSTRAINT ck_media_asset_byte_size_non_negative CHECK (byte_size IS NULL OR byte_size >= 0),
    CONSTRAINT ck_media_asset_verified_state CHECK (
        (upload_state = 'ISSUED' AND verified_at IS NULL) OR
        (upload_state IN ('VERIFIED', 'ATTACHED') AND verified_at IS NOT NULL) OR
        -- An expired ISSUED asset may never have had an object, while an expired
        -- VERIFIED asset retains verification metadata until storage deletion.
        upload_state IN ('DELETE_PENDING', 'DELETED')
    ),
    CONSTRAINT ck_media_asset_deletion_lifecycle CHECK (
        (upload_state IN ('ISSUED', 'VERIFIED', 'ATTACHED')
            AND deletion_reason IS NULL
            AND delete_requested_at IS NULL
            AND deleted_at IS NULL) OR
        (upload_state = 'DELETE_PENDING'
            AND deletion_reason IS NOT NULL
            AND delete_requested_at IS NOT NULL
            AND deleted_at IS NULL) OR
        (upload_state = 'DELETED'
            AND deletion_reason IS NOT NULL
            AND delete_requested_at IS NOT NULL
            AND deleted_at IS NOT NULL)
    )
);

-- Privacy lookup by subject; there is no other index on owner_user_id.
CREATE INDEX ix_media_asset_owner_erasure
    ON review_media_assets (owner_user_id) WHERE owner_user_id IS NOT NULL;
CREATE INDEX ix_media_asset_expiry_sweep
    ON review_media_assets (expires_at) WHERE upload_state IN ('ISSUED', 'VERIFIED');
CREATE INDEX ix_media_asset_delete_sweep
    ON review_media_assets (delete_requested_at) WHERE upload_state = 'DELETE_PENDING';

CREATE TABLE review_media (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id   UUID NOT NULL REFERENCES reviews (id) ON DELETE RESTRICT,
    asset_id    UUID NOT NULL UNIQUE REFERENCES review_media_assets (id) ON DELETE RESTRICT,
    sort_order  SMALLINT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_media_review_sort UNIQUE (review_id, sort_order),
    CONSTRAINT ck_media_sort_order_non_negative CHECK (sort_order >= 0)
);

CREATE INDEX ix_media_review ON review_media (review_id, sort_order);

-- ---------------------------------------------------------------------------
-- 5. Moderation
-- ---------------------------------------------------------------------------

CREATE TABLE review_reports (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id             UUID NOT NULL REFERENCES reviews (id) ON DELETE RESTRICT,
    reporter_user_id      UUID,
    reporter_erased_at    TIMESTAMPTZ,
    reason                report_reason NOT NULL,
    detail                TEXT,
    status                report_status NOT NULL DEFAULT 'PENDING',
    resolved_by_admin_id  UUID,
    resolved_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_reporter_erasure_consistent CHECK (
        (reporter_user_id IS NOT NULL AND reporter_erased_at IS NULL) OR
        (reporter_user_id IS NULL AND reporter_erased_at IS NOT NULL)
    ),
    CONSTRAINT ck_report_resolution_consistent CHECK (
        (status = 'PENDING' AND resolved_by_admin_id IS NULL AND resolved_at IS NULL) OR
        (status = 'ACTIONED' AND resolved_by_admin_id IS NULL AND resolved_at IS NOT NULL) OR
        (status = 'DISMISSED' AND resolved_by_admin_id IS NOT NULL AND resolved_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_report_review_reporter_active_identity
    ON review_reports (review_id, reporter_user_id)
    WHERE reporter_user_id IS NOT NULL AND status <> 'DISMISSED';
-- Batch loads the reports of each review on a moderation queue page. There is no
-- index on report created_at alone: the queue is keyed on the review, so reports
-- are never scanned globally.
CREATE INDEX ix_report_review_status ON review_reports (review_id, status);
-- Privacy lookup by subject. The unique index above leads with review_id, so it
-- cannot seek on reporter_user_id alone.
CREATE INDEX ix_report_reporter_erasure
    ON review_reports (reporter_user_id) WHERE reporter_user_id IS NOT NULL;

CREATE TABLE review_admin_replies (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id           UUID NOT NULL UNIQUE REFERENCES reviews (id) ON DELETE RESTRICT,
    created_by_admin_id UUID NOT NULL,
    updated_by_admin_id UUID NOT NULL,
    content             TEXT NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_admin_reply_content_not_blank CHECK (length(btrim(content)) > 0)
);

CREATE TABLE review_takedowns (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id             UUID NOT NULL UNIQUE REFERENCES reviews (id) ON DELETE RESTRICT,
    legal_case_id         VARCHAR(128) NOT NULL,
    taken_down_by_admin_id UUID NOT NULL,
    reason                TEXT NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_takedown_reason_not_blank CHECK (length(btrim(reason)) > 0)
);

CREATE INDEX ix_takedown_case ON review_takedowns (legal_case_id);

-- ---------------------------------------------------------------------------
-- 6. Derived rating projection. No FK: this is asynchronous, rebuildable data.
-- ---------------------------------------------------------------------------

CREATE TABLE product_rating_aggregates (
    product_id       VARCHAR(64) PRIMARY KEY,
    total_1_star     BIGINT NOT NULL DEFAULT 0,
    total_2_star     BIGINT NOT NULL DEFAULT 0,
    total_3_star     BIGINT NOT NULL DEFAULT 0,
    total_4_star     BIGINT NOT NULL DEFAULT 0,
    total_5_star     BIGINT NOT NULL DEFAULT 0,
    total_with_media BIGINT NOT NULL DEFAULT 0,
    total_reviews    BIGINT NOT NULL DEFAULT 0,
    average_rating   NUMERIC(3,2) NOT NULL DEFAULT 0.00,
    review_revision  BIGINT NOT NULL DEFAULT 1,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_aggregate_buckets_non_negative CHECK (
        total_1_star >= 0 AND total_2_star >= 0 AND total_3_star >= 0 AND
        total_4_star >= 0 AND total_5_star >= 0 AND total_with_media >= 0
    ),
    CONSTRAINT ck_aggregate_total_matches_buckets CHECK (
        total_reviews = total_1_star + total_2_star + total_3_star + total_4_star + total_5_star
    ),
    CONSTRAINT ck_aggregate_media_within_total CHECK (total_with_media <= total_reviews),
    CONSTRAINT ck_aggregate_average_range CHECK (average_rating BETWEEN 0 AND 5),
    CONSTRAINT ck_aggregate_revision_positive CHECK (review_revision >= 1)
);

CREATE INDEX ix_aggregate_updated_at ON product_rating_aggregates (updated_at);

-- ---------------------------------------------------------------------------
-- 7. Consumer receipts and privacy lifecycle
-- ---------------------------------------------------------------------------

CREATE TABLE review_event_receipts (
    consumer_name    VARCHAR(100) NOT NULL,
    event_id         UUID NOT NULL,
    source_topic     VARCHAR(200) NOT NULL,
    source_partition INTEGER,
    source_offset    BIGINT,
    processed_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (consumer_name, event_id),
    CONSTRAINT uq_event_receipt_source_position
        UNIQUE (consumer_name, source_topic, source_partition, source_offset),
    CONSTRAINT ck_event_receipt_partition_offset CHECK (
        (source_partition IS NULL AND source_offset IS NULL) OR
        (source_partition >= 0 AND source_offset >= 0)
    )
);

CREATE TABLE privacy_erasure_requests (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_event_id   UUID NOT NULL UNIQUE,
    subject_user_id   UUID,
    -- HMAC of the canonical UUID. It stays after raw subject_user_id is purged
    -- so late/replayed input cannot reintroduce a direct identifier.
    subject_match_key BYTEA NOT NULL,
    subject_key_version SMALLINT NOT NULL DEFAULT 1,
    deletion_reason   VARCHAR(50) NOT NULL,
    deleted_at        TIMESTAMPTZ NOT NULL,
    hard_delete_after TIMESTAMPTZ NOT NULL,
    status            privacy_erasure_status NOT NULL DEFAULT 'RECEIVED',
    anonymized_at     TIMESTAMPTZ,
    purged_at         TIMESTAMPTZ,
    failure_detail    TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_privacy_subject_match_key UNIQUE (subject_match_key),
    CONSTRAINT ck_privacy_hard_delete_after_deletion
        CHECK (hard_delete_after >= deleted_at),
    CONSTRAINT ck_privacy_subject_match_key_sha256
        CHECK (octet_length(subject_match_key) = 32 AND subject_key_version > 0),
    CONSTRAINT ck_privacy_purge_identity_consistent CHECK (
        (status <> 'PURGED' AND subject_user_id IS NOT NULL) OR
        (status = 'PURGED' AND subject_user_id IS NULL AND purged_at IS NOT NULL)
    ),
    CONSTRAINT ck_privacy_status_timestamps_consistent CHECK (
        (status = 'RECEIVED' AND anonymized_at IS NULL AND purged_at IS NULL) OR
        (status = 'ANONYMIZED' AND anonymized_at IS NOT NULL AND purged_at IS NULL) OR
        (status = 'PURGED' AND anonymized_at IS NOT NULL AND purged_at IS NOT NULL) OR
        (status = 'FAILED')
    )
);

CREATE INDEX ix_privacy_hard_delete_sweep
    ON privacy_erasure_requests (hard_delete_after)
    WHERE status = 'ANONYMIZED';

-- ---------------------------------------------------------------------------
-- 8. Search rating-export sessions
-- ---------------------------------------------------------------------------

CREATE TABLE rating_export_sessions (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    requested_by VARCHAR(100) NOT NULL,
    status       rating_export_session_status NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_export_session_expiry_after_creation CHECK (expires_at > created_at)
);

CREATE INDEX ix_export_session_expiry_sweep
    ON rating_export_sessions (expires_at) WHERE status = 'ACTIVE';

CREATE TABLE rating_export_session_offsets (
    session_id         UUID NOT NULL REFERENCES rating_export_sessions (id) ON DELETE CASCADE,
    topic              VARCHAR(200) NOT NULL,
    partition          INTEGER NOT NULL,
    replay_from_offset BIGINT NOT NULL,
    captured_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (session_id, topic, partition),
    CONSTRAINT ck_export_offset_non_negative CHECK (partition >= 0 AND replay_from_offset >= 0)
);

-- ---------------------------------------------------------------------------
-- 9. Outbox. It is append-only CDC source, not a publish-status queue.
--
--    `payload` MUST be a complete CloudEvents 1.0 envelope, not a bare `data`
--    object: the Debezium EventRouter only routes topic/key/header, it never
--    builds the business envelope (see ADR-003). Every other column is a
--    projection of that envelope kept for CDC routing and operator queries;
--    the envelope is the source of truth and is what reaches the wire verbatim.
-- ---------------------------------------------------------------------------

CREATE TABLE review_outbox (
    event_id       UUID NOT NULL DEFAULT gen_random_uuid(),
    event_type     VARCHAR(100) NOT NULL,
    -- REVIEW carries aggregate_id = review_id and covers the five lifecycle
    -- facts plus both notification requests, whose CloudEvents subject is also
    -- reviews/{review_id}. REVIEW_METRIC carries aggregate_id = product_id.
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id   VARCHAR(64) NOT NULL,
    partition_key  VARCHAR(64) NOT NULL,
    correlation_id VARCHAR(128),
    payload        JSONB NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (event_id, created_at),
    CONSTRAINT ck_outbox_aggregate_type CHECK (
        aggregate_type IN ('REVIEW', 'REVIEW_METRIC')
    ),
    -- The envelope attributes that are constant for every Review event. `time`
    -- is only checked for presence: casting text to timestamptz is STABLE, not
    -- IMMUTABLE, so it cannot appear in a CHECK. Contract tests assert that
    -- payload.time equals the transaction now() that created_at defaults to.
    -- Presence uses jsonb_exists() rather than the `?` operator so a JDBC-based
    -- migration runner cannot mistake it for a bind-parameter placeholder.
    CONSTRAINT ck_outbox_payload_is_cloudevent CHECK (
        jsonb_typeof(payload) = 'object'
        AND payload->>'specversion' IS NOT DISTINCT FROM '1.0'
        AND payload->>'source' IS NOT DISTINCT FROM '/services/review'
        AND payload->>'datacontenttype' IS NOT DISTINCT FROM 'application/json'
        AND jsonb_exists(payload, 'subject')
        AND jsonb_exists(payload, 'dataschema')
        AND jsonb_exists(payload, 'time')
        AND jsonb_exists(payload, 'data')
    ),
    -- Keep the routing projection and the envelope from ever drifting apart.
    -- event_id is compared as text so a malformed payload raises this
    -- constraint instead of an opaque uuid cast error. Every comparison here and
    -- above uses IS NOT DISTINCT FROM because a plain `=` against a missing JSON
    -- key yields NULL, and a CHECK treats NULL as success: `payload->>'id' = ...`
    -- would silently admit an envelope with no id at all.
    CONSTRAINT ck_outbox_columns_mirror_payload CHECK (
        event_id::text IS NOT DISTINCT FROM lower(payload->>'id')
        AND event_type IS NOT DISTINCT FROM payload->>'type'
        AND partition_key IS NOT DISTINCT FROM payload->'data'->>'product_id'
        AND correlation_id IS NOT DISTINCT FROM payload->>'correlationid'
    ),
    -- Enforces the 16 KiB x-max-payload-bytes declared by the AsyncAPI channel
    -- at write time. An oversized payload fails its domain transaction rather
    -- than committing a row that CDC can never publish.
    CONSTRAINT ck_outbox_payload_within_channel_limit CHECK (
        octet_length(payload::text) <= 16384
    )
) PARTITION BY RANGE (created_at);

-- Bootstrap the current and following UTC month. The scheduler must maintain
-- this rolling two-month window; hard-coded calendar partitions are forbidden.
DO $$
DECLARE
    month_start DATE := date_trunc('month', now() AT TIME ZONE 'UTC')::DATE;
    partition_start DATE;
    partition_end DATE;
    partition_name TEXT;
    i INTEGER;
BEGIN
    FOR i IN 0..1 LOOP
        partition_start := (month_start + (i || ' month')::INTERVAL)::DATE;
        partition_end := (partition_start + INTERVAL '1 month')::DATE;
        partition_name := format('review_outbox_y%sm%s', to_char(partition_start, 'YYYY'), to_char(partition_start, 'MM'));
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS review.%I PARTITION OF review.review_outbox FOR VALUES FROM (%L) TO (%L)',
            partition_name, partition_start, partition_end
        );
    END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- 10. Automatic updated_at maintenance and Debezium publication
-- ---------------------------------------------------------------------------

CREATE FUNCTION review.set_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_eligibility_updated_at
    BEFORE UPDATE ON review_eligibilities FOR EACH ROW EXECUTE FUNCTION review.set_updated_at();
CREATE TRIGGER trg_review_updated_at
    BEFORE UPDATE ON reviews FOR EACH ROW EXECUTE FUNCTION review.set_updated_at();
CREATE TRIGGER trg_media_asset_updated_at
    BEFORE UPDATE ON review_media_assets FOR EACH ROW EXECUTE FUNCTION review.set_updated_at();
CREATE TRIGGER trg_admin_reply_updated_at
    BEFORE UPDATE ON review_admin_replies FOR EACH ROW EXECUTE FUNCTION review.set_updated_at();
CREATE TRIGGER trg_privacy_erasure_updated_at
    BEFORE UPDATE ON privacy_erasure_requests FOR EACH ROW EXECUTE FUNCTION review.set_updated_at();

CREATE PUBLICATION review_outbox_publication FOR TABLE review_outbox
    WITH (publish_via_partition_root = true);
