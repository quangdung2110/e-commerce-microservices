-- Migration: 003_audit_logs.sql
-- Purpose: Create audit_logs table for security audit trail
-- Author: Auth Service Team
-- Date: 2026-05-02

-- ============================================================================
-- Table: audit_logs
-- Purpose: Comprehensive audit trail for security and compliance
-- ============================================================================
CREATE TABLE IF NOT EXISTS audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Actor Information
    actor_id VARCHAR(255) NOT NULL,
    actor_type VARCHAR(50) NOT NULL CHECK (actor_type IN ('USER', 'ADMIN', 'SYSTEM', 'GUEST')),
    
    -- Event Information
    event_type VARCHAR(100) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    -- Context Information
    ip_address VARCHAR(45),
    device_id VARCHAR(255),
    device_info VARCHAR(255),
    
    -- Severity Level
    security_level VARCHAR(50) NOT NULL DEFAULT 'INFO' CHECK (security_level IN ('INFO', 'WARNING', 'CRITICAL')),
    
    -- Event Payload (JSONB for flexibility)
    payload JSONB DEFAULT '{}',
    
    -- Category
    log_category VARCHAR(50) NOT NULL DEFAULT 'USER' CHECK (log_category IN ('USER', 'SYSTEM', 'ADMIN', 'AUTH')),
    
    -- Metadata
    request_id VARCHAR(100),
    service_name VARCHAR(50) DEFAULT 'auth-service',
    
    -- Timestamp
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Indexes for audit_logs
CREATE INDEX idx_audit_logs_actor_id ON audit_logs(actor_id);
CREATE INDEX idx_audit_logs_actor_type ON audit_logs(actor_type);
CREATE INDEX idx_audit_logs_event_type ON audit_logs(event_type);
CREATE INDEX idx_audit_logs_occurred_at ON audit_logs(occurred_at DESC);
CREATE INDEX idx_audit_logs_security_level ON audit_logs(security_level);
CREATE INDEX idx_audit_logs_log_category ON audit_logs(log_category);

-- Composite indexes for common queries
CREATE INDEX idx_audit_logs_category_occurred ON audit_logs(log_category, occurred_at DESC);
CREATE INDEX idx_audit_logs_actor_event ON audit_logs(actor_id, event_type, occurred_at DESC);
CREATE INDEX idx_audit_logs_security_time ON audit_logs(security_level, occurred_at DESC) 
    WHERE security_level != 'INFO';

-- Partial index for critical security events (quick lookups)
CREATE INDEX idx_audit_logs_critical ON audit_logs(occurred_at DESC) 
    WHERE security_level = 'CRITICAL';

-- Comment on table
COMMENT ON TABLE audit_logs IS 'Security audit trail for auth-service events';
COMMENT ON COLUMN audit_logs.actor_id IS 'Identifier of the actor (user_id, admin_id, or system component)';
COMMENT ON COLUMN audit_logs.actor_type IS 'Type of actor: USER, ADMIN, SYSTEM, or GUEST';
COMMENT ON COLUMN audit_logs.event_type IS 'Type of event (e.g., USER_LOGGED_IN, TOKEN_REFRESHED)';
COMMENT ON COLUMN audit_logs.security_level IS 'Severity: INFO (normal), WARNING (suspicious), CRITICAL (security breach)';
COMMENT ON COLUMN audit_logs.payload IS 'Additional event data in JSON format';
