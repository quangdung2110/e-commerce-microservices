-- Migration: Create audit_logs table
-- This table stores security audit logs for compliance and security monitoring

CREATE TABLE IF NOT EXISTS audit_logs (
    -- Primary key
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Actor information
    actor_id VARCHAR(255) NOT NULL,
    actor_type VARCHAR(50) NOT NULL, -- USER, ADMIN, SYSTEM, GUEST
    
    -- Event information
    event_type VARCHAR(100) NOT NULL, -- USER_LOGGED_IN, TOKEN_REFRESHED, etc.
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    
    -- Request context
    ip_address VARCHAR(45), -- Supports IPv6
    device_id VARCHAR(255),
    device_info TEXT,
    
    -- Security information
    security_level VARCHAR(20) NOT NULL, -- INFO, WARNING, CRITICAL
    request_id VARCHAR(255),
    
    -- Payload (flexible JSON data)
    payload JSONB,
    
    -- Categorization
    log_category VARCHAR(50) NOT NULL, -- USER, SYSTEM, ADMIN, AUTH
    
    -- Service metadata
    service_name VARCHAR(100) NOT NULL DEFAULT 'auth-service',
    
    -- Timestamp
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Indexes for common queries
CREATE INDEX IF NOT EXISTS idx_audit_logs_actor_id ON audit_logs(actor_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_event_type ON audit_logs(event_type);
CREATE INDEX IF NOT EXISTS idx_audit_logs_occurred_at ON audit_logs(occurred_at);
CREATE INDEX IF NOT EXISTS idx_audit_logs_security_level ON audit_logs(security_level);
CREATE INDEX IF NOT EXISTS idx_audit_logs_log_category ON audit_logs(log_category);

-- Composite indexes for common query patterns
CREATE INDEX IF NOT EXISTS idx_audit_logs_actor_event ON audit_logs(actor_id, event_type);
CREATE INDEX IF NOT EXISTS idx_audit_logs_time_level ON audit_logs(occurred_at, security_level);
CREATE INDEX IF NOT EXISTS idx_audit_logs_category_level ON audit_logs(log_category, security_level);

-- Partial indexes for performance
CREATE INDEX IF NOT EXISTS idx_audit_logs_critical ON audit_logs(occurred_at) 
WHERE security_level = 'CRITICAL';

CREATE INDEX IF NOT EXISTS idx_audit_logs_auth_events ON audit_logs(occurred_at) 
WHERE log_category = 'AUTH';

-- Add comments
COMMENT ON TABLE audit_logs IS 'Security audit log for compliance and monitoring';
COMMENT ON COLUMN audit_logs.actor_id IS 'Identifier of the actor (user_id, system, etc.)';
COMMENT ON COLUMN audit_logs.actor_type IS 'Type of actor: USER, ADMIN, SYSTEM, GUEST';
COMMENT ON COLUMN audit_logs.event_type IS 'Type of event: LOGIN, LOGOUT, TOKEN_REFRESH, etc.';
COMMENT ON COLUMN audit_logs.security_level IS 'Security severity: INFO, WARNING, CRITICAL';
COMMENT ON COLUMN audit_logs.log_category IS 'Log category: USER, SYSTEM, ADMIN, AUTH';
