-- 000004_create_outbox_events.down.sql
-- Rollback: Drop outbox_events table

DROP INDEX IF EXISTS idx_outbox_events_cleanup;
DROP INDEX IF EXISTS idx_outbox_events_unprocessed;
DROP INDEX IF EXISTS idx_outbox_events_aggregate;
DROP INDEX IF EXISTS idx_outbox_events_created_at;
DROP TABLE IF EXISTS outbox_events;
