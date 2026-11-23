-- The provider workflow applies this only to its disposable Compose stack.
\set category '018f0000-0000-7000-8000-000000000001'
\set product '018f0000-0000-7000-8000-000000000002'
\set sku '018f0000-0000-7000-8000-000000000003'

BEGIN;
INSERT INTO categories (category_id, name, slug)
VALUES (:'category', 'Provider verification', 'provider-verification');
INSERT INTO products (product_id, category_id, name, slug, status)
VALUES (:'product', :'category', 'Provider verification item', 'provider-verification-item', 'ACTIVE');
INSERT INTO product_skus (sku_id, product_id, sku_code, base_price_vnd, selling_price_vnd)
VALUES (:'sku', :'product', 'PROVIDER-VERIFICATION-SKU', 11900000, 11900000);
COMMIT;

\connect inventory_service
BEGIN;
INSERT INTO warehouse (warehouse_id, name, address, is_active, operational_state,
                       fulfillment_priority, revision, created_at, updated_at)
VALUES ('provider-verification', 'Provider verification', '{}', true, 'NORMAL', 1, 1, now(), now());
INSERT INTO stock_position (warehouse_id, sku_id, on_hand, available_to_sell, reserved,
                            committed, quarantine, revision, updated_at)
VALUES ('provider-verification', :'sku', 10, 10, 0, 0, 0, 1, now());
INSERT INTO sku_availability_state (sku_id, is_in_stock, availability_revision, evaluated_at)
VALUES (:'sku', true, 1, now());
COMMIT;
