-- =============================================
-- V3__partial_unique_outbox_return_received.sql
-- Ensure each (order_id, refund_request_id) emits INVENTORY_RETURN_RECEIVED_ALL
-- exactly once. Prevents race condition when two concurrent transactions both
-- observe pending_count = 0 at refund completion check.
-- aggregate_id = order_id for INVENTORY_RETURN_RECEIVED_ALL.
-- =============================================

CREATE UNIQUE INDEX uq_inventory_outbox_return_received
    ON inventory_outbox (aggregate_id, refund_request_id)
    WHERE event_type = 'INVENTORY_RETURN_RECEIVED_ALL';
