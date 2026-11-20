-- A window can contain only a refund reversal or a processing fee credit.
-- Financial journals retain positive line amounts; reconciliation net totals are signed.
ALTER TABLE reconciliation_runs
    DROP CONSTRAINT reconciliation_runs_refunded_amount_minor_check,
    DROP CONSTRAINT reconciliation_runs_fee_amount_minor_check;
