-- Order Service baseline schema (docs/order/data.md, ADR-011..020).
-- Every FSM write locks in the order: orders -> reconciliation/refund/operation rows ->
-- order_items/lines by ascending ID -> checkpoint/message/notification ledger/outbox.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------------------------------------------------------------------------
-- Master order, delivery units and immutable line snapshots
-- ---------------------------------------------------------------------------
CREATE SEQUENCE orders_id_seq START WITH 100001;

CREATE TABLE orders (
    id                                BIGINT PRIMARY KEY DEFAULT nextval('orders_id_seq'),
    order_code                        VARCHAR(32)  NOT NULL UNIQUE,
    user_id                           UUID         NOT NULL,
    checkout_token                    VARCHAR(255) NOT NULL UNIQUE,
    pricing_hash                      VARCHAR(71)  NOT NULL,
    items_hash                        VARCHAR(71)  NOT NULL,
    promotion_campaign_revision       BIGINT       NOT NULL CHECK (promotion_campaign_revision >= 0),
    total_amount_minor                BIGINT       NOT NULL CHECK (total_amount_minor >= 0),
    total_discount_minor              BIGINT       NOT NULL CHECK (total_discount_minor >= 0),
    final_amount_minor                BIGINT       NOT NULL CHECK (final_amount_minor >= 0),
    inventory_allocation_plan_id      VARCHAR(128) NOT NULL,
    status                            VARCHAR(50)  NOT NULL,
    payment_status                    VARCHAR(50)  NOT NULL,
    saga_phase                        VARCHAR(50)  NOT NULL,
    active_payment_attempt_number     INT          NULL,
    active_payment_transaction_id     VARCHAR(120) NULL,
    captured_payment_transaction_id   VARCHAR(120) NULL UNIQUE,
    inventory_reservation_expires_at  TIMESTAMPTZ  NULL,
    payment_deadline_at               TIMESTAMPTZ  NOT NULL,
    payment_deadline_locked_at        TIMESTAMPTZ  NULL,
    cancel_reason                     TEXT         NULL,
    cancel_reason_detail              VARCHAR(100) NULL,
    return_reason                     VARCHAR(100) NULL,
    buyer_return_locked_at            TIMESTAMPTZ  NULL,
    last_notification_sequence_id     BIGINT       NOT NULL DEFAULT 0,
    last_event_sequence_id            BIGINT       NOT NULL DEFAULT 0,
    paid_at                           TIMESTAMPTZ  NULL,
    completed_at                      TIMESTAMPTZ  NULL,
    cancelled_at                      TIMESTAMPTZ  NULL,
    refunded_at                       TIMESTAMPTZ  NULL,
    created_at                        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_orders_final_amount CHECK (final_amount_minor = total_amount_minor - total_discount_minor)
);

CREATE INDEX idx_orders_buyer_created_id ON orders (user_id, created_at DESC, id DESC);
CREATE INDEX idx_orders_created_id ON orders (created_at DESC, id DESC);
CREATE INDEX idx_orders_status_payment_created ON orders (status, payment_status, created_at DESC, id DESC);
CREATE INDEX idx_orders_final_amount_minor_created ON orders (final_amount_minor, created_at DESC, id DESC);

CREATE TABLE order_items (
    id                         BIGSERIAL PRIMARY KEY,
    order_id                   BIGINT       NOT NULL REFERENCES orders(id),
    warehouse_id               VARCHAR(128) NOT NULL,
    status                     VARCHAR(50)  NOT NULL,
    delivery_address_snapshot  JSONB        NOT NULL,
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                 TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_order_items_warehouse UNIQUE (order_id, warehouse_id)
);

CREATE TABLE order_item_lines (
    id                              BIGSERIAL PRIMARY KEY,
    order_id                        BIGINT        NOT NULL REFERENCES orders(id),
    order_item_id                   BIGINT        NOT NULL REFERENCES order_items(id),
    inventory_allocation_plan_id    VARCHAR(128)  NOT NULL,
    allocation_plan_line_reference  VARCHAR(64)   NOT NULL,
    allocated_warehouse_id          VARCHAR(128)  NOT NULL,
    sku_id                          VARCHAR(255)  NOT NULL,
    product_id                      VARCHAR(255)  NOT NULL,
    quantity                        INT           NOT NULL CHECK (quantity > 0),
    unit_price_minor                BIGINT        NOT NULL CHECK (unit_price_minor >= 0),
    discount_share_minor            BIGINT        NOT NULL CHECK (discount_share_minor >= 0),
    net_amount_minor                BIGINT        NOT NULL CHECK (net_amount_minor >= 0),
    fulfillment_status              VARCHAR(50)   NOT NULL,
    dispatch_event_id               UUID          NULL,
    dispatch_reservation_revision   BIGINT        NULL,
    dispatched_at                   TIMESTAMPTZ   NULL,
    delivery_proof_id               VARCHAR(128)  NULL UNIQUE,
    delivery_proof_sha256           VARCHAR(64)   NULL,
    delivery_event_id               UUID          NULL,
    delivered_at                    TIMESTAMPTZ   NULL,
    snapshot_name                   VARCHAR(255)  NOT NULL,
    snapshot_variant_name           VARCHAR(255)  NULL,
    snapshot_image_url              VARCHAR(2048) NULL,
    snapshot_name_search            TEXT          NOT NULL,
    snapshot_attributes             JSONB         NOT NULL DEFAULT '{}'::jsonb,
    snapshot_category_ids           JSONB         NOT NULL DEFAULT '[]'::jsonb,
    updated_at                      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_order_item_lines_net CHECK (net_amount_minor = quantity::BIGINT * unit_price_minor - discount_share_minor),
    -- Unique within the order only: the same line_reference (= sku_id) recurs across orders.
    CONSTRAINT uq_order_item_lines_reference UNIQUE (order_id, allocation_plan_line_reference)
);

CREATE INDEX idx_oil_order ON order_item_lines (order_id, order_item_id, id);
CREATE INDEX idx_oil_snapshot_name_search ON order_item_lines USING gin (snapshot_name_search gin_trgm_ops);

CREATE TABLE order_promotions (
    id                      BIGSERIAL PRIMARY KEY,
    order_id                BIGINT       NOT NULL REFERENCES orders(id),
    promotion_id            VARCHAR(128) NOT NULL,
    discount_amount_minor   BIGINT       NOT NULL CHECK (discount_amount_minor >= 0),
    reserved_at             TIMESTAMPTZ  NULL,
    reservation_expires_at  TIMESTAMPTZ  NULL,
    CONSTRAINT uq_order_promotions UNIQUE (order_id, promotion_id)
);

-- ---------------------------------------------------------------------------
-- Checkout submission (ADR-020 §11) and payment attempts
-- ---------------------------------------------------------------------------
CREATE TABLE order_checkout_submissions (
    checkout_token  UUID        PRIMARY KEY,
    user_id         UUID        NOT NULL,
    request_digest  CHAR(64)    NOT NULL,
    request         JSONB       NOT NULL,
    state           VARCHAR(32) NOT NULL
        CHECK (state IN ('PREPARING', 'PAYMENT_READY', 'RECALCULATION_REQUIRED', 'FAILED')),
    order_id        BIGINT      NULL REFERENCES orders(id),
    failure         JSONB       NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE order_payment_attempts (
    order_id                      BIGINT       NOT NULL REFERENCES orders(id),
    payment_attempt_number        INT          NOT NULL CHECK (payment_attempt_number >= 1),
    payment_transaction_id        VARCHAR(120) NOT NULL UNIQUE,
    amount_minor                  BIGINT       NOT NULL CHECK (amount_minor >= 0),
    currency                      VARCHAR(3)   NOT NULL DEFAULT 'VND' CHECK (currency = 'VND'),
    terminal_status               VARCHAR(32)  NULL
        CHECK (terminal_status IN ('FAILED', 'EXPIRED', 'CANCELED', 'SUCCESS')),
    terminal_reason_code          VARCHAR(64)  NULL,
    last_financial_state_version  BIGINT       NOT NULL DEFAULT 0,
    operation_id                  UUID         NULL,
    created_at                    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    resolved_at                   TIMESTAMPTZ  NULL,
    PRIMARY KEY (order_id, payment_attempt_number)
);

-- ---------------------------------------------------------------------------
-- Operations, workflow dispatch/messages, inbox and Saga checkpoints (§3.9)
-- ---------------------------------------------------------------------------
CREATE TABLE order_operations (
    operation_id        UUID         PRIMARY KEY,
    order_id            BIGINT       NOT NULL REFERENCES orders(id),
    owner_principal_id  VARCHAR(128) NOT NULL,
    owner_role          VARCHAR(16)  NOT NULL CHECK (owner_role IN ('BUYER', 'ADMIN')),
    operation_type      VARCHAR(40)  NOT NULL,
    idempotency_key     VARCHAR(128) NOT NULL,
    request_digest      CHAR(64)     NOT NULL,
    status              VARCHAR(32)  NOT NULL
        CHECK (status IN ('ACCEPTED', 'RUNNING', 'SUCCEEDED', 'REJECTED', 'FAILED', 'MANUAL_REVIEW_REQUIRED')),
    result_reference    VARCHAR(512) NULL,
    problem             JSONB        NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at        TIMESTAMPTZ  NULL,
    CONSTRAINT uq_order_operations_scope UNIQUE (owner_principal_id, operation_type, idempotency_key)
);

CREATE INDEX idx_order_operations_order ON order_operations (order_id, operation_type, status);

CREATE TABLE order_workflow_dispatches (
    dispatch_id     UUID         PRIMARY KEY,
    order_id        BIGINT       NULL,
    workflow_id     VARCHAR(255) NOT NULL,
    workflow_type   VARCHAR(100) NOT NULL,
    task_queue      VARCHAR(100) NOT NULL,
    input           JSONB        NOT NULL,
    input_digest    CHAR(64)     NOT NULL,
    status          VARCHAR(16)  NOT NULL
        CHECK (status IN ('PENDING', 'IN_FLIGHT', 'RETRY', 'STARTED', 'QUARANTINED')),
    attempts        INT          NOT NULL DEFAULT 0,
    available_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    lease_owner     VARCHAR(128) NULL,
    lease_until     TIMESTAMPTZ  NULL,
    last_error      TEXT         NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_order_workflow_dispatches_status ON order_workflow_dispatches (status, available_at, created_at);
-- At most one live start request per workflow ID.
CREATE UNIQUE INDEX uq_order_workflow_dispatches_live ON order_workflow_dispatches (workflow_id)
    WHERE status IN ('PENDING', 'IN_FLIGHT', 'RETRY');

CREATE TABLE order_workflow_messages (
    message_id              VARCHAR(255) PRIMARY KEY,
    order_id                BIGINT       NULL,
    target_workflow_id      VARCHAR(255) NOT NULL,
    workflow_type           VARCHAR(100) NOT NULL,
    task_queue              VARCHAR(100) NOT NULL,
    start_input             JSONB        NOT NULL,
    message_type            VARCHAR(64)  NOT NULL,
    payload                 JSONB        NOT NULL,
    payload_digest          CHAR(64)     NOT NULL,
    status                  VARCHAR(16)  NOT NULL
        CHECK (status IN ('PENDING', 'IN_FLIGHT', 'RETRY', 'DELIVERED', 'QUARANTINED')),
    delivery_outcome        VARCHAR(16)  NULL
        CHECK (delivery_outcome IN ('SIGNALLED', 'ALREADY_APPLIED', 'STALE_NOOP', 'REROUTED')),
    replacement_message_id  VARCHAR(255) NULL,
    attempts                INT          NOT NULL DEFAULT 0,
    available_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    lease_owner             VARCHAR(128) NULL,
    lease_until             TIMESTAMPTZ  NULL,
    last_error              TEXT         NULL,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    delivered_at            TIMESTAMPTZ  NULL
);

CREATE INDEX idx_order_workflow_messages_status ON order_workflow_messages (status, available_at, created_at);

CREATE TABLE order_consumed_events (
    source           VARCHAR(64)  NOT NULL,
    event_id         VARCHAR(128) NOT NULL,
    event_type       VARCHAR(160) NOT NULL,
    order_id         BIGINT       NULL,
    stream_id        VARCHAR(255) NOT NULL,
    source_revision  BIGINT       NOT NULL,
    payload_sha256   CHAR(64)     NOT NULL,
    payload          JSONB        NOT NULL,
    status           VARCHAR(32)  NOT NULL
        CHECK (status IN ('RECEIVED', 'PENDING_IDENTITY_BIND', 'PENDING_PREREQUISITE', 'ROUTED', 'APPLIED', 'REJECTED')),
    reject_reason    VARCHAR(100) NULL,
    received_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (source, event_id)
);

-- Semantic uniqueness is stream-scoped (INV-ORD-029); a colliding event is stored REJECTED.
CREATE UNIQUE INDEX uq_order_consumed_events_semantic
    ON order_consumed_events (source, event_type, stream_id, source_revision)
    WHERE status <> 'REJECTED';
CREATE INDEX idx_order_consumed_events_parked ON order_consumed_events (order_id, status)
    WHERE status IN ('PENDING_IDENTITY_BIND', 'PENDING_PREREQUISITE');

CREATE TABLE order_saga_checkpoints (
    id               UUID         PRIMARY KEY,
    order_id         BIGINT       NOT NULL REFERENCES orders(id),
    workflow_lane    VARCHAR(40)  NOT NULL,
    phase            VARCHAR(40)  NOT NULL,
    operation        VARCHAR(60)  NOT NULL,
    resource_id      VARCHAR(128) NOT NULL DEFAULT '',
    generation       INT          NOT NULL DEFAULT 0,
    command_id       VARCHAR(255) NOT NULL UNIQUE,
    request          JSONB        NOT NULL,
    request_digest   CHAR(64)     NOT NULL,
    outcome          VARCHAR(16)  NOT NULL
        CHECK (outcome IN ('PENDING', 'ACCEPTED', 'REJECTED', 'UNKNOWN')),
    error_code       VARCHAR(64)  NULL,
    response         JSONB        NULL,
    response_digest  CHAR(64)     NULL,
    attempts         INT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_order_saga_checkpoints_identity
        UNIQUE (order_id, workflow_lane, phase, operation, resource_id, generation)
);

-- ---------------------------------------------------------------------------
-- Lifetime refund case, selected lines and Admin pre-dispatch attempts (§3.6-3.7)
-- ---------------------------------------------------------------------------
CREATE TABLE order_refund_cases (
    order_id                                   BIGINT       PRIMARY KEY REFERENCES orders(id),
    refund_request_id                          VARCHAR(128) NOT NULL UNIQUE,
    workflow_id                                VARCHAR(255) NOT NULL,
    trigger_source                             VARCHAR(40)  NOT NULL
        CHECK (trigger_source IN ('API_RETURN', 'ADMIN_FORCE_REFUND')),
    reason_code                                VARCHAR(100) NOT NULL,
    reason_details                             TEXT         NULL,
    request_principal_id                       VARCHAR(128) NOT NULL,
    request_idempotency_key                    VARCHAR(255) NOT NULL,
    request_digest                             VARCHAR(64)  NOT NULL,
    operation_id                               UUID         NULL,
    case_status                                VARCHAR(40)  NOT NULL
        CHECK (case_status IN ('INITIATED', 'WAITING_INVENTORY_SCOPE_INSPECTION', 'WAITING_INVENTORY_RELEASE',
            'WAITING_PAYMENT_REFUND_RESULT', 'MANUAL_VERIFICATION_REQUIRED', 'SUCCEEDED', 'FAILED',
            'EXTERNALLY_REMEDIATED', 'CLOSED_INCOMPLETE')),
    refund_mode                                VARCHAR(40)  NOT NULL
        CHECK (refund_mode IN ('BUYER_RETURN_SELECTED_LINES', 'ADMIN_PRE_DISPATCH_FULL_ORDER')),
    refund_amount_locked_minor                 BIGINT       NOT NULL CHECK (refund_amount_locked_minor >= 0),
    inventory_refund_scope_id                  VARCHAR(128) NULL UNIQUE,
    inventory_scope_registered_revision        BIGINT       NULL,
    inventory_scope_inspection_completed_at    TIMESTAMPTZ  NULL,
    inventory_inspection_event_id              UUID         NULL UNIQUE,
    inventory_inspection_payload_hash          VARCHAR(128) NULL,
    inventory_scope_revision                   BIGINT       NULL,
    inventory_inspection_lines                 JSONB        NULL,
    inventory_scope_finalization_status        VARCHAR(32)  NOT NULL DEFAULT 'NOT_REQUESTED'
        CHECK (inventory_scope_finalization_status IN ('NOT_REQUIRED', 'NOT_REQUESTED', 'PENDING',
            'RECOVERY_REQUIRED', 'CONFLICT', 'COMPLETED')),
    inventory_scope_finalization_generation    INT          NOT NULL DEFAULT 0
        CHECK (inventory_scope_finalization_generation >= 0),
    inventory_scope_finalization_operation     VARCHAR(20)  NULL
        CHECK (inventory_scope_finalization_operation IN ('CLOSE', 'CLOSE_INCOMPLETE', 'CANCEL')),
    inventory_scope_finalization_reason        VARCHAR(40)  NULL,
    inventory_scope_finalization_command_id    VARCHAR(128) NULL UNIQUE,
    inventory_scope_finalization_decision_id   UUID         NULL,
    inventory_scope_terminal_state             VARCHAR(32)  NULL
        CHECK (inventory_scope_terminal_state IN ('CLOSED', 'CLOSED_INCOMPLETE', 'CANCELLED')),
    inventory_scope_finalization_response_revision BIGINT   NULL,
    inventory_scope_finalization_response_hash VARCHAR(128) NULL,
    inventory_scope_finalized_at               TIMESTAMPTZ  NULL,
    inventory_committed_release_at             TIMESTAMPTZ  NULL,
    inventory_release_command_id               VARCHAR(128) NULL UNIQUE,
    inventory_release_correlation_id           VARCHAR(128) NULL,
    inventory_release_reservation_revision     BIGINT       NULL,
    inventory_released_order_line_ids          JSONB        NULL,
    inventory_release_response_hash            VARCHAR(128) NULL,
    refund_command_id                          VARCHAR(255) NULL UNIQUE,
    refund_transaction_id                      VARCHAR(120) NULL UNIQUE,
    refund_failure_code                        VARCHAR(64)  NULL,
    refund_reversal_reason_code                VARCHAR(64)  NULL,
    manual_verification_reason                 VARCHAR(64)  NULL,
    started_at                                 TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    scope_locked_at                            TIMESTAMPTZ  NULL,
    resolved_at                                TIMESTAMPTZ  NULL,
    updated_at                                 TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_refund_case_resolved CHECK (
        (case_status IN ('SUCCEEDED', 'FAILED', 'EXTERNALLY_REMEDIATED', 'CLOSED_INCOMPLETE')) = (resolved_at IS NOT NULL)),
    CONSTRAINT ck_refund_case_admin_finalization CHECK (
        refund_mode <> 'ADMIN_PRE_DISPATCH_FULL_ORDER' OR inventory_scope_finalization_status = 'NOT_REQUIRED')
);

CREATE INDEX idx_order_refund_cases_status_updated ON order_refund_cases (case_status, updated_at DESC);
CREATE INDEX idx_order_refund_cases_finalization ON order_refund_cases (inventory_scope_finalization_status, updated_at);

CREATE TABLE order_refund_case_lines (
    refund_case_order_id           BIGINT NOT NULL REFERENCES order_refund_cases(order_id),
    order_line_id                  BIGINT NOT NULL REFERENCES order_item_lines(id),
    expected_quantity              INT    NOT NULL CHECK (expected_quantity > 0),
    unit_price_snapshot_minor      BIGINT NOT NULL CHECK (unit_price_snapshot_minor >= 0),
    discount_share_snapshot_minor  BIGINT NOT NULL CHECK (discount_share_snapshot_minor >= 0),
    net_amount_snapshot_minor      BIGINT NOT NULL CHECK (net_amount_snapshot_minor >= 0),
    CHECK (net_amount_snapshot_minor =
        expected_quantity::BIGINT * unit_price_snapshot_minor - discount_share_snapshot_minor),
    PRIMARY KEY (refund_case_order_id, order_line_id)
);

CREATE TABLE order_admin_refund_attempts (
    attempt_id            UUID         PRIMARY KEY,
    order_id              BIGINT       NOT NULL REFERENCES orders(id),
    workflow_id           VARCHAR(255) NOT NULL UNIQUE,
    inventory_command_id  VARCHAR(128) NOT NULL UNIQUE,
    operation_id          UUID         NOT NULL,
    request_digest        CHAR(64)     NOT NULL,
    reason_code           VARCHAR(100) NOT NULL,
    reason_details        TEXT         NULL,
    actor_id              VARCHAR(128) NOT NULL,
    status                VARCHAR(32)  NOT NULL
        CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'RECOVERY_REQUIRED')),
    outcome_digest        CHAR(64)     NULL,
    error_code            VARCHAR(64)  NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_order_admin_refund_attempts_active ON order_admin_refund_attempts (order_id)
    WHERE status IN ('PENDING', 'RECOVERY_REQUIRED');

-- ---------------------------------------------------------------------------
-- Financial reconciliation envelope, obligations, evidence and decisions (§3.8)
-- ---------------------------------------------------------------------------
CREATE TABLE order_financial_reconciliation_cases (
    order_id       BIGINT      PRIMARY KEY REFERENCES orders(id),
    case_id        UUID        NOT NULL UNIQUE,
    case_revision  BIGINT      NOT NULL DEFAULT 1 CHECK (case_revision >= 1),
    case_status    VARCHAR(20) NOT NULL CHECK (case_status IN ('OPEN', 'EVIDENCE_PENDING', 'DECIDED', 'CLOSED')),
    owner_queue    VARCHAR(40) NOT NULL DEFAULT 'FINANCE_OPS',
    opened_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    closed_at      TIMESTAMPTZ NULL
);

CREATE TABLE order_reconciliation_obligations (
    obligation_id              UUID         PRIMARY KEY,
    case_id                    UUID         NOT NULL REFERENCES order_financial_reconciliation_cases(case_id),
    order_id                   BIGINT       NOT NULL REFERENCES orders(id),
    obligation_type            VARCHAR(48)  NOT NULL,
    resource_id                VARCHAR(128) NOT NULL,
    status                     VARCHAR(20)  NOT NULL CHECK (status IN ('OPEN', 'EVIDENCE_PENDING', 'DECIDED', 'CLOSED')),
    revision                   BIGINT       NOT NULL DEFAULT 1,
    detail                     VARCHAR(255) NULL,
    resolution_type            VARCHAR(48)  NULL,
    accounting_case_reference  VARCHAR(128) NULL,
    opened_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                 TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    closed_at                  TIMESTAMPTZ  NULL
);

CREATE UNIQUE INDEX uq_order_reconciliation_obligations_active
    ON order_reconciliation_obligations (order_id, obligation_type, resource_id)
    WHERE status <> 'CLOSED';
CREATE INDEX idx_order_reconciliation_obligations_case ON order_reconciliation_obligations (case_id, status, updated_at);

CREATE TABLE order_reconciliation_evidence (
    evidence_id    UUID         PRIMARY KEY,
    obligation_id  UUID         NOT NULL REFERENCES order_reconciliation_obligations(obligation_id),
    source         VARCHAR(32)  NOT NULL
        CHECK (source IN ('ORDER_CHECKPOINT', 'PAYMENT_RECONCILIATION', 'INVENTORY_COMMAND', 'PROMOTION_RECEIPT')),
    reference      VARCHAR(255) NOT NULL,
    sha256         CHAR(64)     NOT NULL,
    version        BIGINT       NULL,
    detail         JSONB        NULL,
    recorded_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_order_reconciliation_evidence UNIQUE (obligation_id, source, reference)
);

CREATE TABLE order_reconciliation_decisions (
    decision_id                UUID         PRIMARY KEY,
    obligation_id              UUID         NOT NULL REFERENCES order_reconciliation_obligations(obligation_id),
    case_id                    UUID         NOT NULL,
    order_id                   BIGINT       NOT NULL,
    expected_case_revision     BIGINT       NOT NULL,
    resolution_type            VARCHAR(48)  NOT NULL,
    reason_code                VARCHAR(100) NOT NULL,
    request_digest             CHAR(64)     NOT NULL,
    request                    JSONB        NOT NULL,
    actor_id                   UUID         NOT NULL,
    workload_principal         VARCHAR(128) NOT NULL,
    accounting_case_reference  VARCHAR(128) NULL,
    response                   JSONB        NOT NULL,
    created_at                 TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ---------------------------------------------------------------------------
-- Notification command ledger, multi-destination outbox and receipts (§3.10-3.11)
-- ---------------------------------------------------------------------------
CREATE TABLE order_notification_commands (
    order_id               BIGINT       NOT NULL REFERENCES orders(id),
    dedup_key              VARCHAR(255) NOT NULL,
    notification_sequence  BIGINT       NOT NULL CHECK (notification_sequence >= 1),
    event_id               UUID         NOT NULL UNIQUE,
    template_id            VARCHAR(80)  NOT NULL,
    payload                JSONB        NOT NULL,
    payload_sha256         CHAR(64)     NOT NULL,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (order_id, dedup_key),
    CONSTRAINT uq_order_notification_sequence UNIQUE (order_id, notification_sequence)
);

CREATE TABLE outbox_events (
    event_id           UUID         PRIMARY KEY,
    schema_version     INT          NOT NULL DEFAULT 1,
    aggregate_type     VARCHAR(50)  NOT NULL DEFAULT 'Order',
    aggregate_id       VARCHAR(255) NOT NULL,
    message_class      VARCHAR(32)  NOT NULL CHECK (message_class IN ('INTEGRATION_FACT', 'NOTIFICATION_COMMAND')),
    destination_topic  VARCHAR(255) NOT NULL
        CHECK (destination_topic IN ('t.order.events', 't.notification.transactional.v1')),
    event_type         VARCHAR(160) NOT NULL,
    partition_key      VARCHAR(255) NOT NULL,
    dedup_key          VARCHAR(255) NULL,
    payload            JSONB        NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_outbox_dedup_key CHECK ((message_class = 'NOTIFICATION_COMMAND') = (dedup_key IS NOT NULL))
);

CREATE INDEX idx_outbox_created_at ON outbox_events (created_at DESC);
CREATE UNIQUE INDEX uq_outbox_notification_dedup ON outbox_events (destination_topic, dedup_key)
    WHERE message_class = 'NOTIFICATION_COMMAND';

CREATE TABLE outbox_delivery_receipts (
    event_id           UUID         NOT NULL,
    destination_topic  VARCHAR(255) NOT NULL,
    kafka_partition    INT          NOT NULL,
    kafka_offset       BIGINT       NOT NULL,
    observed_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (event_id, destination_topic)
);

-- ---------------------------------------------------------------------------
-- Buyer tab read model (§6.2): derived, never a source of truth.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_orders_buyer_tab AS
SELECT o.id AS order_id, o.user_id, o.status AS order_status,
       o.payment_status, o.created_at, o.final_amount_minor,
       CASE
         WHEN o.status IN ('PENDING_PAYMENT', 'CANCELLING') THEN 'TO_PAY'
         WHEN o.status IN ('PAID', 'PROCESSING')
          AND NOT EXISTS (SELECT 1 FROM order_item_lines oil
                          WHERE oil.order_id = o.id
                            AND oil.fulfillment_status IN ('DISPATCHED', 'DELIVERED'))
          THEN 'TO_SHIP'
         WHEN o.status IN ('PAID', 'PROCESSING') THEN 'TO_RECEIVE'
         WHEN o.status = 'COMPLETED' THEN 'COMPLETED'
         WHEN o.status = 'CANCELLED' THEN 'CANCELLED'
         ELSE 'RETURN_REFUND'
       END AS buyer_tab
FROM orders o;
