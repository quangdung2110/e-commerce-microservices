-- Migration: 005_outbox_events.sql
-- Purpose: Create outbox_events table for reliable event publishing via Debezium CDC
-- Author: Auth Service Team
-- Date: 2026-05-02

-- ============================================================================
-- Table: outbox_events
-- Purpose: Transactional outbox pattern for reliable event publishing
-- Events are published to Kafka via Debezium CDC connector
-- ============================================================================
CREATE TABLE IF NOT EXISTS outbox_events (
    event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Event Identification
    event_type VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    
    -- Partitioning (for Kafka)
    partition_key UUID NOT NULL,
    
    -- Event Payload (CloudEvents format compatible)
    payload JSONB NOT NULL DEFAULT '{}',
    
    -- Metadata
    metadata JSONB DEFAULT '{}',
    
    -- Status tracking
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    error_message TEXT,
    
    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    published_at TIMESTAMP WITH TIME ZONE,
    
    -- Retry tracking
    retry_count INT DEFAULT 0,
    last_retry_at TIMESTAMP WITH TIME ZONE
);

-- Indexes for outbox_events
CREATE INDEX idx_outbox_events_created_at ON outbox_events(created_at ASC);
CREATE INDEX idx_outbox_events_status ON outbox_events(status) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_events_aggregate ON outbox_events(aggregate_type, aggregate_id);
CREATE INDEX idx_outbox_events_partition ON outbox_events(partition_key);
CREATE INDEX idx_outbox_events_event_type ON outbox_events(event_type);

-- Composite index for consumer polling
CREATE INDEX idx_outbox_events_poll ON outbox_events(status, created_at ASC) WHERE status = 'PENDING';

-- Comment on table
COMMENT ON TABLE outbox_events IS 'Transactional outbox for reliable event publishing via Debezium CDC';
COMMENT ON COLUMN outbox_events.event_type IS 'Event type (maps to Kafka topic via Debezium transform)';
COMMENT ON COLUMN outbox_events.aggregate_type IS 'Entity type that generated the event (e.g., User, Session)';
COMMENT ON COLUMN outbox_events.aggregate_id IS 'ID of the entity that generated the event';
COMMENT ON COLUMN outbox_events.partition_key IS 'Kafka partition key (usually user_id for ordering)';
COMMENT ON COLUMN outbox_events.payload IS 'CloudEvents-compliant JSON payload';
