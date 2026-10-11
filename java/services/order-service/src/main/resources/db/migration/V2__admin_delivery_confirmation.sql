-- ADR-021: delivery is confirmed by an Admin command on Order, not by a Delivery service
-- fact. The proof/digest/event columns of the removed Delivery contract become the Admin
-- confirmation record (reference, actor); the reference is a carrier receipt or tracking code
-- that several lines of one shipment may share, so it is no longer unique.

ALTER TABLE order_item_lines DROP CONSTRAINT IF EXISTS order_item_lines_delivery_proof_id_key;
ALTER TABLE order_item_lines RENAME COLUMN delivery_proof_id TO delivery_reference;
ALTER TABLE order_item_lines DROP COLUMN IF EXISTS delivery_proof_sha256;
ALTER TABLE order_item_lines DROP COLUMN IF EXISTS delivery_event_id;
ALTER TABLE order_item_lines ADD COLUMN delivery_confirmed_by UUID NULL;

-- A line is DELIVERED only with its confirmation record.
ALTER TABLE order_item_lines ADD CONSTRAINT ck_order_item_lines_delivery_record CHECK (
    (fulfillment_status = 'DELIVERED') = (delivered_at IS NOT NULL AND delivery_reference IS NOT NULL));

-- Candidates for the "dispatched but not confirmed" age metric.
CREATE INDEX idx_oil_dispatched_unconfirmed ON order_item_lines (dispatched_at)
    WHERE fulfillment_status = 'DISPATCHED';

-- PENDING_PREREQUISITE only parked Delivery proofs that arrived before their dispatch.
UPDATE order_consumed_events
   SET status = 'REJECTED', reject_reason = 'DELIVERY_SOURCE_REMOVED', updated_at = NOW()
 WHERE status = 'PENDING_PREREQUISITE';
ALTER TABLE order_consumed_events DROP CONSTRAINT IF EXISTS order_consumed_events_status_check;
ALTER TABLE order_consumed_events ADD CONSTRAINT order_consumed_events_status_check
    CHECK (status IN ('RECEIVED', 'PENDING_IDENTITY_BIND', 'ROUTED', 'APPLIED', 'REJECTED'));
DROP INDEX IF EXISTS idx_order_consumed_events_parked;
CREATE INDEX idx_order_consumed_events_parked ON order_consumed_events (order_id, status)
    WHERE status = 'PENDING_IDENTITY_BIND';
