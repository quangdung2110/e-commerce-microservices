-- =============================================
-- V3__add_debezium_metadata.sql
-- Add source column for Debezium CDC outbox pattern
-- =============================================

-- Add __source column to outbox table for Debezium to identify the originating service
ALTER TABLE product_outbox_events
    ADD COLUMN IF NOT EXISTS __source VARCHAR(100);

-- Add __processed column for Debezium SMT outbox pattern
-- This column is used by the outbox single message transform to track processed events
ALTER TABLE product_outbox_events
    ADD COLUMN IF NOT EXISTS __processed BOOLEAN NOT NULL DEFAULT FALSE;

-- Add index for efficient polling by processed status (for alternative polling-based approaches)
CREATE INDEX IF NOT EXISTS idx_outbox_processed
    ON product_outbox_events(__processed, created_at)
    WHERE __processed = FALSE;
