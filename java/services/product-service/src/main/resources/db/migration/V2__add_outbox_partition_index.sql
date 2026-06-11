-- =============================================
-- V2__add_outbox_partition_index.sql
-- Add composite indexes for outbox table
-- =============================================

-- Composite index for partition key + created_at ordering (for efficient event polling)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_outbox_partition_created
    ON product_outbox_events(partition_key, created_at);

-- Composite index for retention cleanup by created_at
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_outbox_created_at_only
    ON product_outbox_events(created_at);
