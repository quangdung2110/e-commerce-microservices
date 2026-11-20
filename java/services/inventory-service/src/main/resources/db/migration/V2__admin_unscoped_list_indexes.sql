-- ADR-029: the Admin list and queue queries no longer require warehouse_id/sku_id, so each of
-- them needs an index that serves its ordering without a warehouse prefix. V1 indexes stay:
-- they still serve the warehouse-scoped variants.

-- GET /stock-movements (newest first), with and without source_operation_type.
CREATE INDEX idx_stock_movement_recorded
    ON stock_movement (recorded_at DESC, movement_id DESC);
CREATE INDEX idx_stock_movement_operation
    ON stock_movement (source_operation_type, recorded_at DESC, movement_id DESC);

-- GET /dispatch-queue, /inspection-queue, /quarantine-items across every warehouse.
CREATE INDEX idx_reservation_line_dispatch_queue_all
    ON reservation_line (committed_at, order_id, order_line_id) WHERE state = 'COMMITTED';
CREATE INDEX idx_return_receipt_line_inspection_queue_all
    ON return_receipt_line (received_at, return_receipt_id, return_receipt_line_id)
    WHERE inspected_quantity < quantity;
CREATE INDEX idx_inspection_result_quarantine_all
    ON inspection_result (inspected_at, inspection_id)
    WHERE result = 'FAIL' AND disposed_quantity < quantity;

-- GET /refund-scope-summaries (newest registration first), with and without state.
CREATE INDEX idx_refund_scope_registered
    ON refund_scope (registered_at DESC, refund_scope_id DESC);
CREATE INDEX idx_refund_scope_state_registered
    ON refund_scope (state, registered_at DESC, refund_scope_id DESC);
