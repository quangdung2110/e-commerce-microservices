-- ADR-021: delivery is confirmed by an Admin command on Order, not by a Delivery service
-- fact. The proof/digest/event columns of the removed Delivery contract become the Admin
-- confirmation record (reference, actor); the reference is a carrier receipt or tracking code
-- that several lines of one shipment may share, so it is no longer unique.

ALTER TABLE order_item_lines DROP CONSTRAINT IF EXISTS order_item_lines_delivery_proof_id_key;
ALTER TABLE order_item_lines RENAME COLUMN delivery_proof_id TO delivery_reference;
ALTER TABLE order_item_lines DROP COLUMN IF EXISTS delivery_proof_sha256;
ALTER TABLE order_item_lines DROP COLUMN IF EXISTS delivery_event_id;
ALTER TABLE order_item_lines ADD COLUMN delivery_confirmed_by UUID NULL;
ALTER TABLE order_item_lines ADD COLUMN delivery_confirmation_legacy BOOLEAN NOT NULL DEFAULT FALSE;

-- V1 allowed historical deliveries without a proof/reference. Preserve their state
-- and actual timestamp; never invent a tracking reference or an Admin identity.
-- Every pre-existing delivered line came from the old source, not the new command.
UPDATE order_item_lines SET delivery_confirmation_legacy = TRUE
 WHERE fulfillment_status = 'DELIVERED';

-- New deliveries require a complete Admin record. Historical deliveries keep their
-- original reference (including NULL), but still require the recorded delivery time.
ALTER TABLE order_item_lines ADD CONSTRAINT ck_order_item_lines_delivery_record CHECK (
    (fulfillment_status = 'DELIVERED' AND delivered_at IS NOT NULL AND (
        (delivery_confirmation_legacy AND delivery_confirmed_by IS NULL) OR
        (NOT delivery_confirmation_legacy AND delivery_reference IS NOT NULL AND delivery_confirmed_by IS NOT NULL)
    )) OR
    (fulfillment_status <> 'DELIVERED' AND delivered_at IS NULL AND delivery_reference IS NULL
        AND delivery_confirmed_by IS NULL AND NOT delivery_confirmation_legacy));

-- The exception is a frozen migration classification, not a command option. A new
-- row cannot opt in and an existing row cannot change its classification later.
CREATE FUNCTION guard_legacy_delivery_confirmation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.delivery_confirmation_legacy THEN
            RAISE EXCEPTION 'Legacy delivery classification is migration-only' USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.delivery_confirmation_legacy IS DISTINCT FROM OLD.delivery_confirmation_legacy THEN
        RAISE EXCEPTION 'Legacy delivery classification is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_legacy_delivery_confirmation
    BEFORE INSERT OR UPDATE OF delivery_confirmation_legacy ON order_item_lines
    FOR EACH ROW EXECUTE FUNCTION guard_legacy_delivery_confirmation();

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
