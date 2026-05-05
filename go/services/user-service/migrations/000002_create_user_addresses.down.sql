-- 000002_create_user_addresses.down.sql
-- Rollback: Drop user_addresses table

DROP TRIGGER IF EXISTS update_user_addresses_updated_at ON user_addresses;
DROP INDEX IF EXISTS idx_user_addresses_user_list;
DROP INDEX IF EXISTS idx_unique_default_address;
DROP INDEX IF EXISTS idx_user_addresses_user_default;
DROP INDEX IF EXISTS idx_user_addresses_city;
DROP INDEX IF EXISTS idx_user_addresses_district;
DROP INDEX IF EXISTS idx_user_addresses_ward;
DROP INDEX IF EXISTS idx_user_addresses_user_id;
DROP TABLE IF EXISTS user_addresses;
