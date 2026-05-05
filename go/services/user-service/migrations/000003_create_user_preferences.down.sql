-- 000003_create_user_preferences.down.sql
-- Rollback: Drop user_preferences table

DROP TRIGGER IF EXISTS update_user_preferences_updated_at ON user_preferences;
DROP INDEX IF EXISTS idx_user_preferences_language;
DROP TABLE IF EXISTS user_preferences;
