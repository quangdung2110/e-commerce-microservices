-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- =====================================================
-- USERS TABLE
-- =====================================================
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    roles JSONB NOT NULL DEFAULT '["USER"]',
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING_VERIFICATION',
    locked_at TIMESTAMP WITH TIME ZONE,
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    email_verified_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    revision BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT users_status_check CHECK (
        status IN ('PENDING_VERIFICATION', 'ACTIVE', 'LOCKED', 'BANNED')
    ),
    CONSTRAINT users_failed_login_check CHECK (
        failed_login_attempts >= 0
    )
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_status ON users(status);
CREATE INDEX idx_users_locked_at ON users(locked_at) WHERE locked_at IS NOT NULL;
CREATE INDEX idx_users_created_at ON users(created_at);

COMMENT ON TABLE users IS 'User credentials and account status - Auth Service bounded context';
COMMENT ON COLUMN users.status IS 'PENDING_VERIFICATION: Chờ xác thực email, ACTIVE: Hoạt động, LOCKED: Tạm khóa, BANNED: Cấm vĩnh viễn';

-- =====================================================
-- REFRESH TOKENS TABLE
-- =====================================================
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    family_id UUID NOT NULL DEFAULT gen_random_uuid(),
    replaced_by UUID,
    device_id VARCHAR(255) NOT NULL,
    device_info VARCHAR(255),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT refresh_tokens_token_hash_length CHECK (
        length(token_hash) >= 64 AND length(token_hash) <= 128
    )
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_token_hash ON refresh_tokens(token_hash);
CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens(family_id);
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens(expires_at);
CREATE INDEX idx_refresh_tokens_revoked_at ON refresh_tokens(revoked_at) WHERE revoked_at IS NOT NULL;
CREATE INDEX idx_refresh_tokens_device_id ON refresh_tokens(device_id);
CREATE INDEX idx_refresh_tokens_user_active ON refresh_tokens(user_id, revoked_at, expires_at)
    WHERE revoked_at IS NULL AND expires_at > NOW();

COMMENT ON TABLE refresh_tokens IS 'Refresh token storage with rotation tracking for Grace Period 10s';
COMMENT ON COLUMN refresh_tokens.token_hash IS 'SHA-256 hash of opaque refresh token';
COMMENT ON COLUMN refresh_tokens.family_id IS 'Groups tokens from same login session';
COMMENT ON COLUMN refresh_tokens.replaced_by IS 'Points to new token that replaced this one';

-- =====================================================
-- JWT KEYS TABLE
-- =====================================================
CREATE TABLE jwt_keys (
    kid VARCHAR(100) PRIMARY KEY,
    public_key TEXT NOT NULL,
    private_key TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    rotated_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,

    CONSTRAINT jwt_keys_status_check CHECK (
        status IN ('ACTIVE', 'PHASE_OUT', 'RETIRED')
    )
);

CREATE INDEX idx_jwt_keys_status ON jwt_keys(status);
CREATE INDEX idx_jwt_keys_created_at ON jwt_keys(created_at);
CREATE INDEX idx_jwt_keys_rotated_at ON jwt_keys(rotated_at) WHERE rotated_at IS NOT NULL;

COMMENT ON TABLE jwt_keys IS 'JWKS keystore - stores RSA key pairs for JWT signing';
COMMENT ON COLUMN jwt_keys.status IS 'ACTIVE: Current signing key, PHASE_OUT: Old key during rotation, RETIRED: Deprecated';
COMMENT ON COLUMN jwt_keys.kid IS 'Key ID used in JWT header';

-- =====================================================
-- OUTBOX EVENTS TABLE
-- =====================================================
CREATE TABLE outbox_events (
    event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    partition_key UUID NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_outbox_events_aggregate_type ON outbox_events(aggregate_type);
CREATE INDEX idx_outbox_events_aggregate_id ON outbox_events(aggregate_id);
CREATE INDEX idx_outbox_events_partition_key ON outbox_events(partition_key);
CREATE INDEX idx_outbox_events_created_at ON outbox_events(created_at);
CREATE INDEX idx_outbox_events_event_type ON outbox_events(event_type);

COMMENT ON TABLE outbox_events IS 'Transactional outbox for reliable event publishing via Debezium CDC';
COMMENT ON COLUMN outbox_events.partition_key IS 'Kafka partition key for ordered delivery per user';

-- =====================================================
-- AUDIT LOGS TABLE
-- =====================================================
CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id VARCHAR(255) NOT NULL,
    actor_type VARCHAR(50) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    ip_address VARCHAR(45),
    device_id VARCHAR(255),
    device_info VARCHAR(255),
    security_level VARCHAR(50) NOT NULL DEFAULT 'INFO',
    payload JSONB,
    log_category VARCHAR(50) NOT NULL DEFAULT 'USER',

    CONSTRAINT audit_logs_actor_type_check CHECK (
        actor_type IN ('USER', 'ADMIN', 'SYSTEM', 'GUEST')
    ),
    CONSTRAINT audit_logs_security_level_check CHECK (
        security_level IN ('INFO', 'WARNING', 'CRITICAL')
    ),
    CONSTRAINT audit_logs_log_category_check CHECK (
        log_category IN ('USER', 'SYSTEM')
    )
);

CREATE INDEX idx_audit_logs_actor_id ON audit_logs(actor_id);
CREATE INDEX idx_audit_logs_actor_type ON audit_logs(actor_type);
CREATE INDEX idx_audit_logs_event_type ON audit_logs(event_type);
CREATE INDEX idx_audit_logs_occurred_at ON audit_logs(occurred_at DESC);
CREATE INDEX idx_audit_logs_security_level ON audit_logs(security_level);
CREATE INDEX idx_audit_logs_log_category ON audit_logs(log_category);
CREATE INDEX idx_audit_logs_actor_event ON audit_logs(actor_id, event_type, occurred_at DESC);
CREATE INDEX idx_audit_logs_payload ON audit_logs USING GIN (payload);

COMMENT ON TABLE audit_logs IS 'Security audit trail - records all sensitive events';
COMMENT ON COLUMN audit_logs.actor_type IS 'USER: Regular user, ADMIN: System admin, SYSTEM: Background jobs, GUEST: Anonymous';
COMMENT ON COLUMN audit_logs.security_level IS 'INFO: Normal, WARNING: Suspicious activity, CRITICAL: Security breach';
COMMENT ON COLUMN audit_logs.log_category IS 'USER: User data (6-month retention), SYSTEM: System events (permanent)';

-- =====================================================
-- TRIGGERS
-- =====================================================
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

COMMENT ON FUNCTION update_updated_at_column() IS 'Automatically updates the updated_at column on UPDATE';
