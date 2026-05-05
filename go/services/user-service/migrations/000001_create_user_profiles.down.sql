-- 000001_create_user_profiles.down.sql
-- Rollback: Drop user_profiles table

DROP TRIGGER IF EXISTS update_user_profiles_updated_at ON user_profiles;
DROP INDEX IF EXISTS idx_user_profiles_created_at;
DROP INDEX IF EXISTS idx_user_profiles_phone;
DROP TABLE IF EXISTS user_profiles;
