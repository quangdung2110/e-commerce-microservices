-- Inventory Release 1 baseline (docs/inventory/data.md).
-- One relational primary is the only write authority. State, command/operation identity,
-- immutable movement/audit and the transactional outbox commit in one transaction.

-- ── Master data and stock ───────────────────────────────────────────────────

CREATE TABLE warehouse (
    warehouse_id          VARCHAR(128) PRIMARY KEY,
    name                  VARCHAR(120) NOT NULL,
    address               JSONB        NOT NULL,
    is_active             BOOLEAN      NOT NULL,
    operational_state     VARCHAR(16)  NOT NULL CHECK (operational_state IN ('NORMAL', 'OUTAGE')),
    fulfillment_priority  INTEGER      NOT NULL CHECK (fulfillment_priority >= 0),
    revision              BIGINT       NOT NULL CHECK (revision >= 1),
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_warehouse_active ON warehouse (is_active, warehouse_id);

CREATE TABLE stock_position (
    warehouse_id       VARCHAR(128) NOT NULL REFERENCES warehouse (warehouse_id),
    sku_id             VARCHAR(128) NOT NULL,
    on_hand            BIGINT       NOT NULL CHECK (on_hand >= 0),
    available_to_sell  BIGINT       NOT NULL CHECK (available_to_sell >= 0),
    reserved           BIGINT       NOT NULL CHECK (reserved >= 0),
    committed          BIGINT       NOT NULL CHECK (committed >= 0),
    quarantine         BIGINT       NOT NULL CHECK (quarantine >= 0),
    revision           BIGINT       NOT NULL CHECK (revision >= 1),
    updated_at         TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (warehouse_id, sku_id),
    -- The bucket equation is a database constraint, not only an application guard.
    CONSTRAINT stock_position_bucket_equation
        CHECK (available_to_sell + reserved + committed + quarantine = on_hand)
);

CREATE INDEX idx_stock_position_sku ON stock_position (sku_id, warehouse_id);

-- ── Reservation lifecycle ──────────────────────────────────────────────────

CREATE TABLE order_reservation (
    order_id                    VARCHAR(128) PRIMARY KEY,
    allocation_plan_id          VARCHAR(128) NOT NULL,
    state                       VARCHAR(32)  NOT NULL CHECK (state IN
                                    ('RESERVED', 'COMMITTED', 'PARTIALLY_DISPATCHED', 'DISPATCHED', 'RELEASED')),
    accepted_at                 TIMESTAMPTZ  NOT NULL,
    expires_at                  TIMESTAMPTZ  NOT NULL,
    release_reason              VARCHAR(32)  CHECK (release_reason IN
                                    ('PAYMENT_FAILED', 'RESERVATION_EXPIRED', 'BUYER_CANCELLED',
                                     'ADMIN_CANCELLED', 'PRE_PAYMENT_SAGA_FAILED')),
    committed_release_reason    VARCHAR(48)  CHECK (committed_release_reason IN ('ADMIN_FORCE_REFUND_BEFORE_DISPATCH')),
    payment_reference           VARCHAR(128),
    recovery_payment_reference  VARCHAR(128),
    revision                    BIGINT       NOT NULL CHECK (revision >= 1),
    correlation_id              VARCHAR(128) NOT NULL,
    updated_at                  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_order_reservation_reserved_age ON order_reservation (accepted_at) WHERE state = 'RESERVED';

CREATE TABLE reservation_line (
    order_id       VARCHAR(128) NOT NULL REFERENCES order_reservation (order_id),
    order_line_id  VARCHAR(128) NOT NULL,
    line_no        INTEGER      NOT NULL,
    sku_id         VARCHAR(128) NOT NULL,
    quantity       BIGINT       NOT NULL CHECK (quantity >= 1),
    warehouse_id   VARCHAR(128) NOT NULL REFERENCES warehouse (warehouse_id),
    state          VARCHAR(16)  NOT NULL CHECK (state IN ('RESERVED', 'COMMITTED', 'DISPATCHED', 'RELEASED')),
    committed_at   TIMESTAMPTZ,
    PRIMARY KEY (order_id, order_line_id)
);

CREATE INDEX idx_reservation_line_dispatch_queue
    ON reservation_line (warehouse_id, committed_at, order_id, order_line_id) WHERE state = 'COMMITTED';

CREATE TABLE physical_dispatch (
    warehouse_id        VARCHAR(128) NOT NULL,
    dispatch_id         VARCHAR(128) NOT NULL,
    dispatch_line_id    VARCHAR(128) NOT NULL,
    order_id            VARCHAR(128) NOT NULL,
    order_line_id       VARCHAR(128) NOT NULL,
    sku_id              VARCHAR(128) NOT NULL,
    quantity            BIGINT       NOT NULL CHECK (quantity >= 1),
    handover_reference  VARCHAR(128) NOT NULL,
    handed_over_at      TIMESTAMPTZ  NOT NULL,
    evidence_reference  VARCHAR(512) NOT NULL,
    movement_id         VARCHAR(64)  NOT NULL,
    actor_id            VARCHAR(128) NOT NULL,
    recorded_at         TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (warehouse_id, dispatch_id, dispatch_line_id),
    UNIQUE (order_id, order_line_id),
    FOREIGN KEY (order_id, order_line_id) REFERENCES reservation_line (order_id, order_line_id)
);

-- ── Refund Scope, returns and quarantine ───────────────────────────────────

CREATE TABLE refund_scope (
    refund_scope_id    VARCHAR(128) PRIMARY KEY,
    order_id           VARCHAR(128) NOT NULL UNIQUE REFERENCES order_reservation (order_id),
    refund_request_id  VARCHAR(128) NOT NULL UNIQUE,
    state              VARCHAR(32)  NOT NULL CHECK (state IN
                           ('REGISTERED', 'IN_PROGRESS', 'INSPECTION_COMPLETED', 'CANCELLED', 'CLOSED', 'CLOSED_INCOMPLETE')),
    close_reason       VARCHAR(32)  CHECK (close_reason IN
                           ('REFUNDED', 'REJECTED', 'RETURN_WINDOW_EXPIRED', 'PACKAGE_LOST', 'CUSTOMER_WITHDREW')),
    revision           BIGINT       NOT NULL CHECK (revision >= 1),
    registered_at      TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL
);

CREATE TABLE refund_scope_line (
    refund_scope_id    VARCHAR(128) NOT NULL REFERENCES refund_scope (refund_scope_id),
    order_line_id      VARCHAR(128) NOT NULL,
    line_no            INTEGER      NOT NULL,
    sku_id             VARCHAR(128) NOT NULL,
    expected_quantity  BIGINT       NOT NULL CHECK (expected_quantity >= 1),
    received_quantity  BIGINT       NOT NULL DEFAULT 0,
    passed_quantity    BIGINT       NOT NULL DEFAULT 0,
    failed_quantity    BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (refund_scope_id, order_line_id),
    CONSTRAINT refund_scope_line_counters CHECK (
        passed_quantity >= 0 AND failed_quantity >= 0
        AND passed_quantity + failed_quantity <= received_quantity
        AND received_quantity <= expected_quantity)
);

CREATE TABLE return_receipt_line (
    return_receipt_id       VARCHAR(128) NOT NULL,
    return_receipt_line_id  VARCHAR(128) NOT NULL,
    refund_scope_id         VARCHAR(128) NOT NULL,
    order_line_id           VARCHAR(128) NOT NULL,
    sku_id                  VARCHAR(128) NOT NULL,
    warehouse_id            VARCHAR(128) NOT NULL REFERENCES warehouse (warehouse_id),
    quantity                BIGINT       NOT NULL CHECK (quantity >= 1),
    inspected_quantity      BIGINT       NOT NULL DEFAULT 0,
    package_reference       VARCHAR(128) NOT NULL,
    received_at             TIMESTAMPTZ  NOT NULL,
    evidence_reference      VARCHAR(512) NOT NULL,
    movement_id             VARCHAR(64)  NOT NULL,
    recorded_at             TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (return_receipt_id, return_receipt_line_id),
    FOREIGN KEY (refund_scope_id, order_line_id) REFERENCES refund_scope_line (refund_scope_id, order_line_id),
    CONSTRAINT return_receipt_line_inspected CHECK (inspected_quantity >= 0 AND inspected_quantity <= quantity)
);

CREATE INDEX idx_return_receipt_line_inspection_queue
    ON return_receipt_line (warehouse_id, received_at, return_receipt_id, return_receipt_line_id)
    WHERE inspected_quantity < quantity;

CREATE TABLE inspection_result (
    inspection_id           VARCHAR(128) PRIMARY KEY,
    return_receipt_id       VARCHAR(128) NOT NULL,
    return_receipt_line_id  VARCHAR(128) NOT NULL,
    refund_scope_id         VARCHAR(128) NOT NULL,
    order_line_id           VARCHAR(128) NOT NULL,
    warehouse_id            VARCHAR(128) NOT NULL,
    sku_id                  VARCHAR(128) NOT NULL,
    quantity                BIGINT       NOT NULL CHECK (quantity >= 1),
    result                  VARCHAR(8)   NOT NULL CHECK (result IN ('PASS', 'FAIL')),
    fail_reason             VARCHAR(40),
    checklist_reference     VARCHAR(128) NOT NULL,
    evidence_reference      VARCHAR(512) NOT NULL,
    -- Failed quantity consumed by DISPOSE/RETURN_TO_SUPPLIER net of reversals; HOLD never changes it.
    disposed_quantity       BIGINT       NOT NULL DEFAULT 0,
    latest_hold_reason      VARCHAR(40),
    movement_id             VARCHAR(64),
    inspected_at            TIMESTAMPTZ  NOT NULL,
    FOREIGN KEY (return_receipt_id, return_receipt_line_id)
        REFERENCES return_receipt_line (return_receipt_id, return_receipt_line_id),
    CONSTRAINT inspection_result_fail_reason CHECK ((result = 'FAIL') = (fail_reason IS NOT NULL)),
    CONSTRAINT inspection_result_disposed CHECK (
        disposed_quantity >= 0 AND disposed_quantity <= quantity AND (result = 'FAIL' OR disposed_quantity = 0))
);

CREATE INDEX idx_inspection_result_quarantine
    ON inspection_result (warehouse_id, inspected_at, inspection_id)
    WHERE result = 'FAIL' AND disposed_quantity < quantity;

CREATE TABLE quarantine_disposition (
    disposition_id                VARCHAR(128) NOT NULL,
    disposition_line_id           VARCHAR(128) NOT NULL,
    inspection_id                 VARCHAR(128) NOT NULL REFERENCES inspection_result (inspection_id),
    warehouse_id                  VARCHAR(128) NOT NULL,
    sku_id                        VARCHAR(128) NOT NULL,
    quantity                      BIGINT       NOT NULL CHECK (quantity >= 1),
    disposition                   VARCHAR(24)  NOT NULL CHECK (disposition IN ('HOLD', 'DISPOSE', 'RETURN_TO_SUPPLIER')),
    reason                        VARCHAR(40)  NOT NULL,
    physical_departure_reference  VARCHAR(128),
    departed_at                   TIMESTAMPTZ,
    evidence_reference            VARCHAR(512) NOT NULL,
    movement_id                   VARCHAR(64),
    actor_id                      VARCHAR(128) NOT NULL,
    recorded_at                   TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (disposition_id, disposition_line_id),
    CONSTRAINT quarantine_disposition_departure CHECK (
        (disposition = 'HOLD') = (physical_departure_reference IS NULL AND departed_at IS NULL))
);

CREATE TABLE disposition_reversal (
    disposition_id              VARCHAR(128) NOT NULL,
    disposition_line_id         VARCHAR(128) NOT NULL,
    reversal_sequence           INTEGER      NOT NULL CHECK (reversal_sequence >= 1),
    quantity                    BIGINT       NOT NULL CHECK (quantity >= 1),
    reason                      VARCHAR(32)  NOT NULL,
    reason_note                 VARCHAR(1000) NOT NULL,
    physically_returned_at      TIMESTAMPTZ  NOT NULL,
    physical_return_reference   VARCHAR(128) NOT NULL,
    evidence_reference          VARCHAR(512) NOT NULL,
    movement_id                 VARCHAR(64)  NOT NULL,
    actor_id                    VARCHAR(128) NOT NULL,
    recorded_at                 TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (disposition_id, disposition_line_id, reversal_sequence),
    FOREIGN KEY (disposition_id, disposition_line_id)
        REFERENCES quarantine_disposition (disposition_id, disposition_line_id)
);

-- ── Immutable evidence ──────────────────────────────────────────────────────

CREATE TABLE stock_movement (
    movement_id            VARCHAR(64)  PRIMARY KEY,
    warehouse_id           VARCHAR(128) NOT NULL,
    sku_id                 VARCHAR(128) NOT NULL,
    source_operation_type  VARCHAR(64)  NOT NULL,
    source_operation_id    VARCHAR(128) NOT NULL,
    reverses_movement_id   VARCHAR(64)  REFERENCES stock_movement (movement_id),
    reversal_sequence      INTEGER,
    delta_on_hand          BIGINT       NOT NULL,
    delta_available        BIGINT       NOT NULL,
    delta_reserved         BIGINT       NOT NULL,
    delta_committed        BIGINT       NOT NULL,
    delta_quarantine       BIGINT       NOT NULL,
    command_id             VARCHAR(128) NOT NULL,
    recorded_at            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT stock_movement_balanced CHECK (
        delta_available + delta_reserved + delta_committed + delta_quarantine = delta_on_hand),
    CONSTRAINT stock_movement_reversal_pair CHECK ((reverses_movement_id IS NULL) = (reversal_sequence IS NULL)),
    UNIQUE (reverses_movement_id, reversal_sequence)
);

CREATE INDEX idx_stock_movement_position ON stock_movement (warehouse_id, sku_id, recorded_at DESC, movement_id DESC);
CREATE INDEX idx_stock_movement_sku ON stock_movement (sku_id, recorded_at DESC, movement_id DESC);

CREATE TABLE audit_entry (
    audit_id            BIGSERIAL    PRIMARY KEY,
    recorded_at         TIMESTAMPTZ  NOT NULL,
    actor_type          VARCHAR(16)  NOT NULL CHECK (actor_type IN ('SERVICE', 'USER', 'SYSTEM')),
    actor_id            VARCHAR(128) NOT NULL,
    actor_role          VARCHAR(64),
    session_id          VARCHAR(128),
    caller_namespace    VARCHAR(128) NOT NULL,
    command_id          VARCHAR(128) NOT NULL,
    operation           VARCHAR(80)  NOT NULL,
    target_type         VARCHAR(48)  NOT NULL,
    target_id           VARCHAR(400) NOT NULL,
    reason              VARCHAR(64),
    evidence_reference  VARCHAR(512),
    before_state        JSONB,
    after_state         JSONB,
    correlation_id      VARCHAR(128) NOT NULL,
    causation_id        VARCHAR(128) NOT NULL
);

CREATE INDEX idx_audit_entry_target ON audit_entry (target_type, target_id, recorded_at);

-- Movements and audit are append-only: no UPDATE/DELETE path exists, even for operators.
CREATE FUNCTION inventory_reject_evidence_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'append-only evidence table % cannot be %', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER stock_movement_append_only BEFORE UPDATE OR DELETE ON stock_movement
    FOR EACH ROW EXECUTE FUNCTION inventory_reject_evidence_change();
CREATE TRIGGER audit_entry_append_only BEFORE UPDATE OR DELETE ON audit_entry
    FOR EACH ROW EXECUTE FUNCTION inventory_reject_evidence_change();

-- ── Command and operation identity ─────────────────────────────────────────

CREATE TABLE command_execution (
    caller_namespace  VARCHAR(128) NOT NULL,
    command_id        VARCHAR(128) NOT NULL,
    operation         VARCHAR(80)  NOT NULL,
    fingerprint       VARCHAR(64)  NOT NULL,
    result            VARCHAR(16)  CHECK (result IN ('ACCEPTED', 'REJECTED')),
    http_status       INTEGER,
    error_code        VARCHAR(64),
    outcome           VARCHAR(64),
    resource_id       VARCHAR(128),
    response_body     JSONB,
    correlation_id    VARCHAR(128) NOT NULL,
    recorded_at       TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (caller_namespace, command_id),
    -- A row is visible to other transactions only after its terminal result is written.
    CONSTRAINT command_execution_terminal CHECK (
        (result IS NULL AND response_body IS NULL)
        OR (result IS NOT NULL AND response_body IS NOT NULL AND http_status IS NOT NULL))
);

CREATE TABLE operation_identity (
    operation_key   VARCHAR(600) PRIMARY KEY,
    operation_type  VARCHAR(48)  NOT NULL,
    operation_id    VARCHAR(64)  NOT NULL UNIQUE,
    command_id      VARCHAR(128) NOT NULL,
    recorded_at     TIMESTAMPTZ  NOT NULL
);

-- ── Per-SKU availability projection ────────────────────────────────────────

CREATE TABLE sku_availability_state (
    sku_id                 VARCHAR(128) PRIMARY KEY,
    is_in_stock            BOOLEAN      NOT NULL,
    availability_revision  BIGINT       NOT NULL CHECK (availability_revision >= 1),
    evaluated_at           TIMESTAMPTZ  NOT NULL
);

CREATE TABLE sku_availability_dirty (
    sku_id               VARCHAR(128) PRIMARY KEY,
    -- Oldest unprocessed mark; coalescing never resets it (ADR-025).
    first_marked_at      TIMESTAMPTZ  NOT NULL,
    mark_seq             BIGINT       NOT NULL,
    claimed_seq          BIGINT,
    claimed_until        TIMESTAMPTZ,
    claim_started_at     TIMESTAMPTZ,
    last_correlation_id  VARCHAR(128),
    last_causation_id    VARCHAR(128)
);

CREATE INDEX idx_sku_availability_dirty_age ON sku_availability_dirty (first_marked_at);

CREATE TABLE availability_fanout_job (
    fanout_job_id       VARCHAR(64)  PRIMARY KEY,
    warehouse_id        VARCHAR(128) NOT NULL REFERENCES warehouse (warehouse_id),
    warehouse_revision  BIGINT       NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    cursor_sku_id       VARCHAR(128),
    completed_at        TIMESTAMPTZ,
    correlation_id      VARCHAR(128) NOT NULL,
    causation_id        VARCHAR(128) NOT NULL,
    UNIQUE (warehouse_id, warehouse_revision)
);

CREATE INDEX idx_availability_fanout_job_open ON availability_fanout_job (warehouse_id) WHERE completed_at IS NULL;

CREATE TABLE search_projection_readiness (
    sku_id            VARCHAR(128) PRIMARY KEY,
    catalog_revision  BIGINT       NOT NULL CHECK (catalog_revision >= 1),
    registered_at     TIMESTAMPTZ  NOT NULL,
    revoked_at        TIMESTAMPTZ
);

CREATE TABLE readiness_publication_task (
    sku_id            VARCHAR(128) NOT NULL,
    catalog_revision  BIGINT       NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    claimed_until     TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    PRIMARY KEY (sku_id, catalog_revision)
);

CREATE INDEX idx_readiness_publication_task_open ON readiness_publication_task (created_at) WHERE completed_at IS NULL;

CREATE TABLE snapshot_session (
    snapshot_id       VARCHAR(64) PRIMARY KEY,
    export_watermark  BIGINT      NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL,
    expires_at        TIMESTAMPTZ NOT NULL
);

-- ── Transactional outbox and commit-ordered export (ADR-022) ───────────────

CREATE TABLE outbox_event (
    outbox_id     BIGSERIAL    PRIMARY KEY,
    event_id      UUID         NOT NULL UNIQUE,
    topic         VARCHAR(128) NOT NULL,
    message_key   VARCHAR(128) NOT NULL,
    event_type    VARCHAR(128) NOT NULL,
    sku_id        VARCHAR(128),
    -- CloudEvent without "sequence"; the sequencer adds it.
    payload       JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    sequence      BIGINT       UNIQUE,
    sequenced_at  TIMESTAMPTZ
);

CREATE INDEX idx_outbox_event_unsequenced ON outbox_event (outbox_id) WHERE sequence IS NULL;

CREATE SEQUENCE export_sequence START WITH 1 INCREMENT BY 1;

-- The only Kafka relay (Debezium) and change-feed source.
CREATE TABLE published_event (
    sequence      BIGINT       PRIMARY KEY,
    event_id      UUID         NOT NULL UNIQUE,
    topic         VARCHAR(128) NOT NULL,
    message_key   VARCHAR(128) NOT NULL,
    event_type    VARCHAR(128) NOT NULL,
    sku_id        VARCHAR(128),
    payload       JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_published_event_type ON published_event (event_type, sequence);
CREATE INDEX idx_published_event_created ON published_event (created_at);

-- Greatest sequence removed by retention; a change cursor below it is 410.
CREATE TABLE export_retention (
    id                 SMALLINT PRIMARY KEY CHECK (id = 1),
    retention_floor    BIGINT   NOT NULL
);
INSERT INTO export_retention (id, retention_floor) VALUES (1, 0);

-- Fenced single-leader leases for in-service workers.
CREATE TABLE worker_lease (
    lease_name     VARCHAR(64)  PRIMARY KEY,
    holder         VARCHAR(128),
    fencing_token  BIGINT       NOT NULL,
    expires_at     TIMESTAMPTZ  NOT NULL
);
INSERT INTO worker_lease (lease_name, holder, fencing_token, expires_at)
VALUES ('export-sequencer', NULL, 0, TIMESTAMPTZ 'epoch');
