-- 000005_create_user_deletions.up.sql
-- Migration: Create user_deletions table for GDPR 2-Phase Deletion Event Store
-- This table stores deletion requests to support the 2-phase hard delete workflow:
-- Stage 1: Anonymize PII when USER_DELETED event arrives (KEEP record, remove PII)
-- Stage 2: Hard delete after retention window (90 days) via Compliance Worker

CREATE TABLE user_deletions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL,
    deleted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    retention_window_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    reason VARCHAR(50) NOT NULL,

    CONSTRAINT chk_deletion_reason CHECK (reason IN ('GDPR_REQUEST', 'ADMIN_DELETION'))
);

-- Index for looking up deletion by user_id
CREATE INDEX idx_user_deletions_user_id ON user_deletions(user_id);

-- Index for Compliance Worker polling (find records ready for hard delete)
CREATE INDEX idx_user_deletions_retention_expires ON user_deletions(retention_window_expires_at)
WHERE retention_window_expires_at <= NOW();

COMMENT ON TABLE user_deletions IS 'GDPR Event Store - stores deletion requests for 2-phase hard delete';
COMMENT ON INDEX idx_user_deletions_retention_expires IS 'Compliance Worker polls this to find records ready for hard delete';
