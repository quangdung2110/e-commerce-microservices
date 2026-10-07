-- Payment Service PostgreSQL baseline.
-- Source of truth: docs/payment/data.md and ADR-011/016/017/018/019/020.
-- PostgreSQL 15+. All money is integer VND; all financial journals are immutable.

CREATE TABLE payment_transactions (
    id VARCHAR(64) PRIMARY KEY,
    order_id BIGINT NOT NULL,
    payment_attempt_number INT NOT NULL CHECK (payment_attempt_number >= 1),
    user_id UUID NOT NULL,
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency CHAR(3) NOT NULL DEFAULT 'VND' CHECK (currency = 'VND'),
    provider VARCHAR(20) NOT NULL DEFAULT 'STRIPE' CHECK (provider = 'STRIPE'),
    provider_transaction_id VARCHAR(100),
    provider_charge_id VARCHAR(100),
    provider_status_snapshot VARCHAR(40),
    provider_synced_at TIMESTAMPTZ,
    last_provider_event_created_at TIMESTAMPTZ,
    last_provider_event_id VARCHAR(120),
    client_secret_ciphertext BYTEA,
    client_secret_key_id VARCHAR(64),
    client_expires_at TIMESTAMPTZ,
    status VARCHAR(20) NOT NULL CHECK (status IN
        ('INITIATING','PENDING','PROCESSING','SUCCESS','FAILED','EXPIRED',
         'CANCELED','REFUNDING','REFUNDED')),
    last_error_code VARCHAR(64),
    last_error_message VARCHAR(500),
    decline_count INT NOT NULL DEFAULT 0 CHECK (decline_count >= 0),
    expires_at TIMESTAMPTZ NOT NULL,
    succeeded_at TIMESTAMPTZ,
    terminal_at TIMESTAMPTZ,
    financial_state_version BIGINT NOT NULL DEFAULT 0 CHECK (financial_state_version >= 0),
    version INT NOT NULL DEFAULT 0 CHECK (version >= 0),
    requires_manual_review BOOLEAN NOT NULL DEFAULT FALSE,
    legal_hold_until TIMESTAMPTZ,
    financial_export_manifest_id VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_payment_attempt UNIQUE (order_id, payment_attempt_number),
    CONSTRAINT uq_payment_provider_txn UNIQUE (provider_transaction_id),
    CONSTRAINT ck_payment_pending_has_intent CHECK
        (status = 'INITIATING' OR provider_transaction_id IS NOT NULL),
    CONSTRAINT ck_payment_success_time CHECK
        ((status IN ('SUCCESS','REFUNDING','REFUNDED')) = (succeeded_at IS NOT NULL)),
    CONSTRAINT ck_payment_terminal_time CHECK
        ((status IN ('FAILED','EXPIRED','CANCELED')) = (terminal_at IS NOT NULL)),
    CONSTRAINT ck_payment_client_secret_fields CHECK
        ((client_secret_ciphertext IS NULL) = (client_secret_key_id IS NULL))
);

CREATE INDEX idx_payment_expiration ON payment_transactions (expires_at)
    WHERE status IN ('PENDING','PROCESSING');
CREATE INDEX idx_payment_updated_at ON payment_transactions (updated_at);
CREATE INDEX idx_payment_provider_charge ON payment_transactions (provider_charge_id)
    WHERE provider_charge_id IS NOT NULL;

CREATE TABLE refund_transactions (
    id VARCHAR(64) PRIMARY KEY,
    payment_transaction_id VARCHAR(64) NOT NULL REFERENCES payment_transactions(id),
    order_id BIGINT NOT NULL,
    refund_request_id VARCHAR(128) NOT NULL,
    command_idempotency_key VARCHAR(255) NOT NULL,
    refund_amount_minor BIGINT NOT NULL CHECK (refund_amount_minor > 0),
    currency CHAR(3) NOT NULL DEFAULT 'VND' CHECK (currency = 'VND'),
    reason VARCHAR(50) NOT NULL CHECK (reason IN
        ('CUSTOMER_RETURN_SELECTED_LINES','ADMIN_FORCE_REFUND_BEFORE_DISPATCH')),
    inventory_evidence JSONB NOT NULL CHECK (jsonb_typeof(inventory_evidence) = 'object'),
    provider_refund_id VARCHAR(100),
    provider_status_snapshot VARCHAR(40),
    provider_synced_at TIMESTAMPTZ,
    provider_failure_reason VARCHAR(100),
    last_provider_event_created_at TIMESTAMPTZ,
    last_provider_event_id VARCHAR(120),
    status VARCHAR(20) NOT NULL CHECK (status IN
        ('PENDING','PROCESSING','SUCCESS','FAILED','REVERSED')),
    accepted_at TIMESTAMPTZ,
    finalized_at TIMESTAMPTZ,
    reversed_at TIMESTAMPTZ,
    version INT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_refund_order UNIQUE (order_id),
    CONSTRAINT uq_refund_request UNIQUE (refund_request_id),
    CONSTRAINT uq_refund_command UNIQUE (command_idempotency_key),
    CONSTRAINT uq_refund_provider UNIQUE (provider_refund_id)
);

CREATE INDEX idx_refund_payment ON refund_transactions (payment_transaction_id);
CREATE INDEX idx_refund_processing ON refund_transactions (updated_at)
    WHERE status = 'PROCESSING';

CREATE OR REPLACE FUNCTION enforce_refund_against_payment()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    payment_order_id BIGINT;
    payment_amount BIGINT;
    payment_currency CHAR(3);
    payment_status VARCHAR(20);
BEGIN
    SELECT order_id, amount_minor, currency, status
      INTO payment_order_id, payment_amount, payment_currency, payment_status
      FROM payment_transactions
     WHERE id = NEW.payment_transaction_id
     FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'payment transaction % does not exist', NEW.payment_transaction_id;
    END IF;
    IF NEW.order_id <> payment_order_id OR NEW.currency <> payment_currency THEN
        RAISE EXCEPTION 'refund identity/currency does not match payment transaction';
    END IF;
    IF NEW.refund_amount_minor > payment_amount THEN
        RAISE EXCEPTION 'refund amount exceeds captured payment amount';
    END IF;
    IF payment_status NOT IN ('SUCCESS','REFUNDING') THEN
        RAISE EXCEPTION 'payment status % cannot accept a refund', payment_status;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_refund_against_payment
BEFORE INSERT OR UPDATE OF payment_transaction_id, order_id, refund_amount_minor, currency
ON refund_transactions FOR EACH ROW EXECUTE FUNCTION enforce_refund_against_payment();

CREATE TABLE payment_disputes (
    id VARCHAR(64) PRIMARY KEY,
    payment_transaction_id VARCHAR(64) NOT NULL REFERENCES payment_transactions(id),
    order_id BIGINT NOT NULL,
    provider_dispute_id VARCHAR(100) NOT NULL UNIQUE,
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency CHAR(3) NOT NULL DEFAULT 'VND' CHECK (currency = 'VND'),
    reason_code VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN
        ('OPEN','UNDER_REVIEW','WON','LOST','WARNING_CLOSED')),
    funds_state VARCHAR(20) NOT NULL DEFAULT 'UNCHANGED' CHECK
        (funds_state IN ('UNCHANGED','WITHDRAWN','REINSTATED')),
    evidence_due_at TIMESTAMPTZ,
    opened_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    last_provider_event_created_at TIMESTAMPTZ NOT NULL,
    last_provider_event_id VARCHAR(120) NOT NULL,
    version INT NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_dispute_closed_time CHECK
        ((status IN ('WON','LOST','WARNING_CLOSED')) = (closed_at IS NOT NULL))
);

CREATE INDEX idx_dispute_deadline ON payment_disputes (evidence_due_at)
    WHERE status IN ('OPEN','UNDER_REVIEW');

CREATE TABLE provider_operations (
    id VARCHAR(64) PRIMARY KEY,
    payment_transaction_id VARCHAR(64) NOT NULL REFERENCES payment_transactions(id),
    refund_transaction_id VARCHAR(64) REFERENCES refund_transactions(id),
    operation_type VARCHAR(10) NOT NULL CHECK (operation_type IN ('CREATE','CANCEL','REFUND')),
    provider_idempotency_key VARCHAR(255) NOT NULL,
    command_idempotency_key VARCHAR(255),
    request_fingerprint JSONB NOT NULL CHECK (jsonb_typeof(request_fingerprint) = 'object'),
    status VARCHAR(30) NOT NULL CHECK (status IN
        ('REQUESTED','PROVIDER_CONFIRMED','PROVIDER_REJECTED','UNKNOWN',
         'MANUAL_REVIEW_REQUIRED')),
    provider_object_id VARCHAR(100),
    provider_result JSONB,
    attempt_count INT NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_attempted_at TIMESTAMPTZ,
    first_dispatched_at TIMESTAMPTZ,
    last_dispatched_at TIMESTAMPTZ,
    provider_request_id VARCHAR(120),
    safe_replay_until TIMESTAMPTZ,
    recovery_deadline_at TIMESTAMPTZ NOT NULL,
    next_retry_at TIMESTAMPTZ,
    lease_owner VARCHAR(128),
    lease_token BIGINT NOT NULL DEFAULT 0 CHECK (lease_token >= 0),
    lease_expires_at TIMESTAMPTZ,
    last_heartbeat_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_provider_op_key UNIQUE (provider_idempotency_key),
    CONSTRAINT ck_provider_op_refund_link CHECK
        ((operation_type = 'REFUND') = (refund_transaction_id IS NOT NULL))
);

CREATE UNIQUE INDEX uq_provider_op_create ON provider_operations (payment_transaction_id)
    WHERE operation_type = 'CREATE';
CREATE UNIQUE INDEX uq_provider_op_cancel ON provider_operations (payment_transaction_id)
    WHERE operation_type = 'CANCEL';
CREATE UNIQUE INDEX uq_provider_op_refund ON provider_operations (refund_transaction_id)
    WHERE operation_type = 'REFUND';
CREATE INDEX idx_provider_op_recovery ON provider_operations (next_retry_at, lease_expires_at)
    WHERE status IN ('REQUESTED','UNKNOWN');

CREATE TABLE webhook_logs (
    id BIGSERIAL PRIMARY KEY,
    provider VARCHAR(20) NOT NULL DEFAULT 'STRIPE' CHECK (provider = 'STRIPE'),
    stripe_event_id VARCHAR(120) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    event_created_at TIMESTAMPTZ NOT NULL,
    api_version VARCHAR(20),
    livemode BOOLEAN NOT NULL,
    provider_object_id VARCHAR(100),
    payment_transaction_id VARCHAR(64) REFERENCES payment_transactions(id),
    refund_transaction_id VARCHAR(64) REFERENCES refund_transactions(id),
    dispute_transaction_id VARCHAR(64) REFERENCES payment_disputes(id),
    raw_body_ciphertext BYTEA,
    raw_body_key_id VARCHAR(64),
    raw_body_sha256 CHAR(64) NOT NULL,
    stripe_signature_ciphertext BYTEA,
    signing_secret_id VARCHAR(64) NOT NULL,
    verified_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(30) NOT NULL CHECK (status IN
        ('RECEIVED','PROCESSING','PROCESSED','IGNORED','RETRY_WAIT','DEAD')),
    processing_error VARCHAR(500),
    processing_attempts INT NOT NULL DEFAULT 0 CHECK (processing_attempts >= 0),
    next_retry_at TIMESTAMPTZ,
    lease_owner VARCHAR(128),
    lease_token BIGINT NOT NULL DEFAULT 0 CHECK (lease_token >= 0),
    lease_expires_at TIMESTAMPTZ,
    replay_actor VARCHAR(128),
    replay_approver VARCHAR(128),
    replay_cause_ref VARCHAR(255),
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    CONSTRAINT uq_webhook_event UNIQUE (stripe_event_id),
    CONSTRAINT ck_webhook_ciphertext_lifecycle CHECK
        ((raw_body_ciphertext IS NULL AND raw_body_key_id IS NULL AND
          stripe_signature_ciphertext IS NULL) OR
         (raw_body_ciphertext IS NOT NULL AND raw_body_key_id IS NOT NULL AND
          stripe_signature_ciphertext IS NOT NULL))
);

CREATE INDEX idx_webhook_claim ON webhook_logs (next_retry_at, lease_expires_at)
    WHERE status IN ('RECEIVED','RETRY_WAIT','PROCESSING');
CREATE INDEX idx_webhook_object ON webhook_logs (provider_object_id, event_type);

CREATE TABLE payment_status_history (
    id BIGSERIAL PRIMARY KEY,
    payment_transaction_id VARCHAR(64) NOT NULL REFERENCES payment_transactions(id),
    refund_transaction_id VARCHAR(64) REFERENCES refund_transactions(id),
    dispute_transaction_id VARCHAR(64) REFERENCES payment_disputes(id),
    transaction_type VARCHAR(10) NOT NULL CHECK
        (transaction_type IN ('PAYMENT','REFUND','DISPUTE')),
    transition_id VARCHAR(8) NOT NULL,
    old_status VARCHAR(20),
    new_status VARCHAR(20) NOT NULL,
    financial_state_version BIGINT NOT NULL CHECK (financial_state_version >= 0),
    triggered_by VARCHAR(20) NOT NULL CHECK (triggered_by IN
        ('API','WEBHOOK','SCHEDULER','RECONCILIATION','MANUAL')),
    cause_ref VARCHAR(255),
    actor VARCHAR(128),
    provider_response JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_history_aggregate_link CHECK
        ((transaction_type = 'PAYMENT' AND refund_transaction_id IS NULL AND dispute_transaction_id IS NULL) OR
         (transaction_type = 'REFUND' AND refund_transaction_id IS NOT NULL AND dispute_transaction_id IS NULL) OR
         (transaction_type = 'DISPUTE' AND refund_transaction_id IS NULL AND dispute_transaction_id IS NOT NULL))
);

CREATE INDEX idx_history_timeline
    ON payment_status_history (payment_transaction_id, created_at);

CREATE TABLE outbox_events (
    event_id UUID PRIMARY KEY,
    aggregate_type VARCHAR(50) NOT NULL DEFAULT 'Payment' CHECK (aggregate_type = 'Payment'),
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    partition_key VARCHAR(64) NOT NULL,
    schema_version INT NOT NULL DEFAULT 2 CHECK (schema_version = 2),
    headers JSONB NOT NULL CHECK (jsonb_typeof(headers) = 'object'),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    commit_lsn PG_LSN NOT NULL DEFAULT pg_current_wal_lsn(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_outbox_created_at ON outbox_events (created_at);

CREATE TABLE payment_idempotency (
    idempotency_key VARCHAR(255) NOT NULL,
    action VARCHAR(30) NOT NULL CHECK (action IN
        ('INITIATE','CANCEL','REFUND','REVIEW_CLAIM','REVIEW_RESOLVE',
         'WEBHOOK_REPLAY','RECONCILE','FREEZE')),
    request_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('IN_PROGRESS','COMPLETED')),
    resource_id VARCHAR(64),
    response_code INT,
    response_body JSONB,
    lease_owner VARCHAR(128),
    lease_token BIGINT NOT NULL DEFAULT 0 CHECK (lease_token >= 0),
    lease_expires_at TIMESTAMPTZ,
    last_heartbeat_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (idempotency_key, action),
    CONSTRAINT ck_idempotency_completion CHECK
        ((status = 'COMPLETED') =
         (completed_at IS NOT NULL AND response_code IS NOT NULL AND resource_id IS NOT NULL)),
    CONSTRAINT ck_idempotency_active_lease CHECK
        (status = 'COMPLETED' OR (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL))
);

CREATE INDEX idx_idempotency_expiry ON payment_idempotency (expires_at);

CREATE TABLE payment_review_cases (
    id VARCHAR(64) PRIMARY KEY,
    payment_transaction_id VARCHAR(64) NOT NULL REFERENCES payment_transactions(id),
    refund_transaction_id VARCHAR(64) REFERENCES refund_transactions(id),
    reason_code VARCHAR(50) NOT NULL CHECK (reason_code IN
        ('REFUND_FAILED_AFTER_ACCEPT','REFUND_REVERSED','CAPTURE_AFTER_CANCEL',
         'AMOUNT_MISMATCH','PROVIDER_IDENTITY_UNBOUND','PROVIDER_CREATE_REJECTED',
         'WEBHOOK_RETRY_EXHAUSTED','PROVIDER_RETRIEVE_REJECTED',
         'DISPUTE_FUNDS_TIE_UNRESOLVED',
         'RECONCILIATION_DRIFT','DISPUTE_OPENED')),
    case_status VARCHAR(20) NOT NULL CHECK
        (case_status IN ('OPEN','IN_PROGRESS','RESOLVED','ESCALATED')),
    evidence JSONB NOT NULL CHECK
        (jsonb_typeof(evidence) = 'array' AND jsonb_array_length(evidence) >= 1),
    resolution_type VARCHAR(40) CHECK (resolution_type IN
        ('CORRECTION_APPLIED','NO_ACTION_NEEDED','MANUAL_REFUND_OUTSIDE_SYSTEM',
         'ESCALATED_TO_ORDER','ESCALATED_TO_ACCOUNTING')),
    resolution_note TEXT,
    opened_by VARCHAR(20) NOT NULL CHECK
        (opened_by IN ('WEBHOOK','SCHEDULER','RECONCILIATION','API')),
    opened_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    assigned_to VARCHAR(128),
    resolved_by VARCHAR(128),
    resolved_at TIMESTAMPTZ,
    CONSTRAINT ck_review_resolution CHECK
        ((case_status IN ('RESOLVED','ESCALATED')) = (resolution_type IS NOT NULL))
);

CREATE UNIQUE INDEX uq_review_open_per_payment
    ON payment_review_cases (payment_transaction_id)
    WHERE case_status IN ('OPEN','IN_PROGRESS');
CREATE INDEX idx_review_queue ON payment_review_cases (opened_at)
    WHERE case_status IN ('OPEN','IN_PROGRESS');

CREATE OR REPLACE FUNCTION enforce_review_evidence_append_only()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    old_prefix JSONB;
BEGIN
    SELECT COALESCE(jsonb_agg(value ORDER BY ordinality), '[]'::jsonb)
      INTO old_prefix
      FROM jsonb_array_elements(NEW.evidence) WITH ORDINALITY
     WHERE ordinality <= jsonb_array_length(OLD.evidence);
    IF jsonb_array_length(NEW.evidence) < jsonb_array_length(OLD.evidence)
       OR old_prefix <> OLD.evidence THEN
        RAISE EXCEPTION 'review evidence is append-only';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_review_evidence_append_only
BEFORE UPDATE OF evidence ON payment_review_cases
FOR EACH ROW EXECUTE FUNCTION enforce_review_evidence_append_only();

CREATE TABLE reconciliation_runs (
    id BIGSERIAL PRIMARY KEY,
    lane VARCHAR(20) NOT NULL CHECK (lane IN ('OBJECT_STATE','BALANCE_LEDGER','CONTROL')),
    window_from TIMESTAMPTZ NOT NULL,
    window_to TIMESTAMPTZ NOT NULL,
    provider_cursor_from VARCHAR(255),
    provider_cursor_to VARCHAR(255),
    status VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
    auto_correction_frozen BOOLEAN NOT NULL DEFAULT FALSE,
    scanned_count INT NOT NULL DEFAULT 0 CHECK (scanned_count >= 0),
    drift_count INT NOT NULL DEFAULT 0 CHECK (drift_count >= 0),
    corrected_count INT NOT NULL DEFAULT 0 CHECK (corrected_count >= 0),
    review_opened_count INT NOT NULL DEFAULT 0 CHECK (review_opened_count >= 0),
    captured_amount_minor BIGINT NOT NULL DEFAULT 0 CHECK (captured_amount_minor >= 0),
    refunded_amount_minor BIGINT NOT NULL DEFAULT 0 CHECK (refunded_amount_minor >= 0),
    disputed_amount_minor BIGINT NOT NULL DEFAULT 0 CHECK (disputed_amount_minor >= 0),
    fee_amount_minor BIGINT NOT NULL DEFAULT 0 CHECK (fee_amount_minor >= 0),
    payout_amount_minor BIGINT NOT NULL DEFAULT 0 CHECK (payout_amount_minor >= 0),
    error_message VARCHAR(500),
    requested_by VARCHAR(128),
    cause_ref VARCHAR(255),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    CONSTRAINT ck_reconciliation_window CHECK (window_from < window_to),
    CONSTRAINT ck_reconciliation_completion CHECK
        ((status = 'RUNNING') = (finished_at IS NULL))
);

CREATE UNIQUE INDEX uq_reconciliation_singleton ON reconciliation_runs ((1))
    WHERE status = 'RUNNING';

CREATE TABLE financial_journals (
    id VARCHAR(64) PRIMARY KEY,
    payment_transaction_id VARCHAR(64) REFERENCES payment_transactions(id),
    refund_transaction_id VARCHAR(64) REFERENCES refund_transactions(id),
    dispute_transaction_id VARCHAR(64) REFERENCES payment_disputes(id),
    journal_type VARCHAR(30) NOT NULL CHECK (journal_type IN
        ('CAPTURE','REFUND','REFUND_REVERSAL','DISPUTE_WITHDRAWAL',
         'DISPUTE_REINSTATEMENT','PROCESSING_FEE','PAYOUT_SETTLEMENT','REVERSAL')),
    provider_balance_transaction_id VARCHAR(120) UNIQUE,
    source_type VARCHAR(30) NOT NULL,
    source_id VARCHAR(120) NOT NULL,
    reversal_of_journal_id VARCHAR(64) REFERENCES financial_journals(id),
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency CHAR(3) NOT NULL DEFAULT 'VND' CHECK (currency = 'VND'),
    occurred_at TIMESTAMPTZ NOT NULL,
    provider_observed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_financial_journal_source UNIQUE (source_type, source_id),
    CONSTRAINT ck_financial_journal_reversal CHECK
        ((journal_type = 'REVERSAL') = (reversal_of_journal_id IS NOT NULL))
);

CREATE INDEX idx_financial_journal_time ON financial_journals (occurred_at, journal_type);

CREATE TABLE financial_ledger_entries (
    journal_id VARCHAR(64) NOT NULL REFERENCES financial_journals(id),
    line_no SMALLINT NOT NULL CHECK (line_no >= 1),
    account_code VARCHAR(40) NOT NULL CHECK (account_code IN
        ('PROVIDER_RECEIVABLE','CUSTOMER_PAYMENT_CLEARING','CUSTOMER_REFUND_CLEARING',
         'DISPUTE_HOLD_OR_LOSS','PROCESSING_FEE_EXPENSE','BANK_SETTLEMENT_CLEARING')),
    direction VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
    currency CHAR(3) NOT NULL DEFAULT 'VND' CHECK (currency = 'VND'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (journal_id, line_no)
);

CREATE OR REPLACE FUNCTION reject_immutable_financial_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('payment.retention_mode', TRUE) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION '% is append-only; post a reversal journal instead', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER trg_financial_journals_immutable
BEFORE UPDATE OR DELETE ON financial_journals
FOR EACH ROW EXECUTE FUNCTION reject_immutable_financial_mutation();

CREATE TRIGGER trg_financial_ledger_entries_immutable
BEFORE UPDATE OR DELETE ON financial_ledger_entries
FOR EACH ROW EXECUTE FUNCTION reject_immutable_financial_mutation();

CREATE OR REPLACE FUNCTION check_financial_journal_balanced()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    target_journal_id VARCHAR(64);
    line_count BIGINT;
    debit_total NUMERIC;
    credit_total NUMERIC;
    currency_count BIGINT;
    header_amount BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'financial_journals' THEN
        target_journal_id := NEW.id;
    ELSE
        target_journal_id := NEW.journal_id;
    END IF;
    SELECT COUNT(*),
           COALESCE(SUM(amount_minor) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount_minor) FILTER (WHERE direction = 'CREDIT'), 0),
           COUNT(DISTINCT currency)
      INTO line_count, debit_total, credit_total, currency_count
      FROM financial_ledger_entries
     WHERE journal_id = target_journal_id;
    SELECT amount_minor INTO header_amount FROM financial_journals WHERE id = target_journal_id;
    IF line_count < 2 OR currency_count <> 1 OR debit_total <> credit_total
       OR debit_total <> header_amount THEN
        RAISE EXCEPTION 'journal % is not balanced or does not match header amount', target_journal_id;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ck_financial_journal_balanced_header
AFTER INSERT ON financial_journals DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION check_financial_journal_balanced();

CREATE CONSTRAINT TRIGGER ck_financial_journal_balanced_lines
AFTER INSERT ON financial_ledger_entries DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION check_financial_journal_balanced();

CREATE OR REPLACE FUNCTION reject_append_only_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('payment.retention_mode', TRUE) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER trg_payment_history_append_only
BEFORE UPDATE OR DELETE ON payment_status_history
FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();

CREATE TRIGGER trg_payment_outbox_append_only
BEFORE UPDATE ON outbox_events
FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();

CREATE OR REPLACE PROCEDURE post_financial_journal(
    p_id VARCHAR(64),
    p_payment_transaction_id VARCHAR(64),
    p_refund_transaction_id VARCHAR(64),
    p_dispute_transaction_id VARCHAR(64),
    p_journal_type VARCHAR(30),
    p_provider_balance_transaction_id VARCHAR(120),
    p_source_type VARCHAR(30),
    p_source_id VARCHAR(120),
    p_reversal_of_journal_id VARCHAR(64),
    p_amount_minor BIGINT,
    p_currency CHAR(3),
    p_occurred_at TIMESTAMPTZ,
    p_provider_observed_at TIMESTAMPTZ,
    p_lines JSONB
)
LANGUAGE plpgsql AS $$
BEGIN
    IF jsonb_typeof(p_lines) <> 'array' OR jsonb_array_length(p_lines) < 2 THEN
        RAISE EXCEPTION 'p_lines must be a JSON array with at least two entries';
    END IF;
    INSERT INTO financial_journals (
        id, payment_transaction_id, refund_transaction_id, dispute_transaction_id,
        journal_type, provider_balance_transaction_id, source_type, source_id,
        reversal_of_journal_id, amount_minor, currency, occurred_at, provider_observed_at
    ) VALUES (
        p_id, p_payment_transaction_id, p_refund_transaction_id, p_dispute_transaction_id,
        p_journal_type, p_provider_balance_transaction_id, p_source_type, p_source_id,
        p_reversal_of_journal_id, p_amount_minor, p_currency, p_occurred_at,
        p_provider_observed_at
    );
    INSERT INTO financial_ledger_entries
        (journal_id, line_no, account_code, direction, amount_minor, currency)
    SELECT p_id,
           (entry.ordinality)::SMALLINT,
           entry.value->>'account_code',
           entry.value->>'direction',
           (entry.value->>'amount_minor')::BIGINT,
           COALESCE(entry.value->>'currency', p_currency)
      FROM jsonb_array_elements(p_lines) WITH ORDINALITY AS entry(value, ordinality);
END;
$$;
