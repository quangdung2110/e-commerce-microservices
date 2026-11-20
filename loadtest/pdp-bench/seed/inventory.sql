-- Availability read model for the SKUs of seed/product.sql, run against the bench inventory_service database after
-- Inventory's Flyway migrations. 85% of SKUs in stock; evaluated recently, nothing dirty, so every read is a
-- known IN_STOCK/OUT_OF_STOCK (a full, cacheable PDP response).
--
--   :products  same value as for product.sql
-- Destructive for the target database. Run it only on the bench database.

\set ON_ERROR_STOP on
BEGIN;
TRUNCATE sku_availability_state, sku_availability_dirty;

INSERT INTO sku_availability_state (sku_id, is_in_stock, availability_revision, evaluated_at)
SELECT md5('s-' || i || '-' || k)::uuid::text, (i * 4 + k) % 20 >= 3, 1, now()
FROM generate_series(1, :products) i, generate_series(1, 4) k
WHERE k <= 1 + i % 4;
COMMIT;

ANALYZE;
SELECT count(*) AS availability_rows FROM sku_availability_state;
