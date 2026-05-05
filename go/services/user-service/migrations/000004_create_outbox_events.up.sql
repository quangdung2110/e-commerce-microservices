-- 000004_create_outbox_events.up.sql
-- Migration: Create outbox_events table for Transactional Outbox Pattern

CREATE TABLE outbox_events (
    event_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    event_type VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    partition_key UUID NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    CONSTRAINT chk_event_type CHECK (
        event_type IN ('USER_PROFILE_UPDATED', 'USER_PREFERENCES_UPDATED', 'USER_PROFILE_DELETED')
    )
);

-- Indexes for Debezium polling
CREATE INDEX idx_outbox_events_created_at ON outbox_events(created_at ASC);
CREATE INDEX idx_outbox_events_aggregate ON outbox_events(aggregate_type, aggregate_id);
CREATE INDEX idx_outbox_events_unprocessed ON outbox_events(created_at) WHERE payload IS NOT NULL;

-- Index for cleanup job
CREATE INDEX idx_outbox_events_cleanup ON outbox_events(created_at) 
WHERE created_at < NOW() - INTERVAL '14 days';

COMMENT ON TABLE outbox_events IS 'Transactional Outbox for CDC via Debezium';
COMMENT ON INDEX idx_outbox_events_unprocessed IS 'Debezium polls this index to pick up new events';
