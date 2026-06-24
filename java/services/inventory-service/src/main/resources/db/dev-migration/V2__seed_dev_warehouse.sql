-- =============================================
-- V2__seed_dev_warehouse.sql
-- Dev-only seed: 3 sample warehouses in HCM and HN.
-- This migration is loaded ONLY when spring.flyway.locations includes
-- 'classpath:db/dev-migration' (configured in application-dev.yml).
-- Production and test profiles must NOT include this folder.
-- =============================================

INSERT INTO warehouses (name, address, city_code, district_code, ward_code, is_active) VALUES
  ('HCM Central Warehouse',  '12 Nguyễn Huệ, Q1, TP.HCM',          'HCM', 'Q1',        'Bến Nghé',  TRUE),
  ('HCM District 7 Warehouse', '88 Phú Mỹ Hưng, Q7, TP.HCM',      'HCM', 'Q7',        'Tân Phú',   TRUE),
  ('HN Long Biên Warehouse',  '5 Nguyễn Văn Cừ, Long Biên, Hà Nội', 'HN',  'Long Biên',  'Ngọc Lâm',  TRUE);
