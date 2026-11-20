-- Stock for the Reserve hot-SKU benchmark, run against the bench inventory_service database after Inventory's Flyway
-- migrations and while Inventory is stopped. Every variant starts from exactly this state.
--
--   one warehouse BENCH-WH-1 (active, NORMAL);
--   BENCH-HOT        the flash-sale SKU: every hot Reserve locks this one stock_position row;
--   BENCH-COLD-0001..:cold_skus  control SKUs in the same warehouse, each touched rarely.
-- Stock is large enough that no step can run out (no REJECT_ALL): the benchmark measures contention, not stock-outs.
--
--   :cold_skus  number of control SKUs (default in the driver: 2000)
-- Destructive for the target database. Run it only on the bench database.

\set ON_ERROR_STOP on
BEGIN;
TRUNCATE warehouse, stock_position, order_reservation, reservation_line, physical_dispatch, refund_scope,
         refund_scope_line, return_receipt_line, inspection_result, quarantine_disposition, disposition_reversal,
         stock_movement, audit_entry, command_execution, operation_identity, sku_availability_state,
         sku_availability_dirty, availability_fanout_job, search_projection_readiness, readiness_publication_task,
         snapshot_session, outbox_event, published_event
         RESTART IDENTITY CASCADE;

INSERT INTO warehouse (warehouse_id, name, address, is_active, operational_state, fulfillment_priority, revision,
                       created_at, updated_at)
VALUES ('BENCH-WH-1', 'Bench warehouse 1',
        '{"line1":"1 Bench Street","ward":"Ward 1","district":"District 1","city":"Ho Chi Minh City","country":"VN"}',
        true, 'NORMAL', 1, 1, now(), now());

INSERT INTO stock_position (warehouse_id, sku_id, on_hand, available_to_sell, reserved, committed, quarantine,
                            revision, updated_at)
SELECT 'BENCH-WH-1', sku, 1000000000000, 1000000000000, 0, 0, 0, 1, now()
FROM (SELECT 'BENCH-HOT' AS sku
      UNION ALL
      SELECT 'BENCH-COLD-' || lpad(i::text, 4, '0') FROM generate_series(1, :cold_skus) i) s;
COMMIT;

ANALYZE;
SELECT count(*) AS stock_positions FROM stock_position;
