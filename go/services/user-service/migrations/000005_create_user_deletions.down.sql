-- 000005_create_user_deletions.down.sql
-- Rollback: Drop user_deletions table

DROP INDEX IF EXISTS idx_user_deletions_retention_expires;
DROP INDEX IF EXISTS idx_user_deletions_user_id;
DROP TABLE IF EXISTS user_deletions;
