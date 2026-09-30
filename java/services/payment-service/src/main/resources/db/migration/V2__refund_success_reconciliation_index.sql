-- ADR-022 / RULE-PAY-024: reconciliation re-checks SUCCESS refunds for 35 days, least recently
-- synced first, so a late reversal is detected even when its webhook was missed.
CREATE INDEX idx_refund_success_reconcile
    ON refund_transactions (provider_synced_at NULLS FIRST, finalized_at)
    WHERE status = 'SUCCESS';
