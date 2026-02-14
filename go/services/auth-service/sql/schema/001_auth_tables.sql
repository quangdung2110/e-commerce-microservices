-- Auth Service Database Schema
-- Database: identity_db

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Device Type Enum
CREATE TYPE device_type AS ENUM ('MOBILE', 'DESKTOP', 'TABLET', 'UNKNOWN');

-- ============================================================================
-- Table: auth_sessions
-- Purpose: Tracks active user sessions with device fingerprinting
-- ============================================================================
CREATE TABLE auth_sessions (
    session_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL,
    
    -- Device Fingerprint
    device_id       VARCHAR(255),
    device_name     VARCHAR(255),
    device_type     device_type NOT NULL DEFAULT 'UNKNOWN',
    ip_address      INET NOT NULL,
    user_agent      TEXT,
    
    -- Geolocation (optional, from IP)
    geo_country     VARCHAR(2),
    geo_city        VARCHAR(100),
    
    -- Session State
    is_active       BOOLEAN DEFAULT TRUE,
    
    -- Security
    refresh_token_hash  VARCHAR(255) NOT NULL,
    
    -- Timestamps
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at      TIMESTAMP WITH TIME ZONE
);

-- Indexes for auth_sessions
CREATE INDEX idx_sessions_user_id ON auth_sessions(user_id);
CREATE INDEX idx_sessions_expires ON auth_sessions(expires_at) WHERE is_active = TRUE;
CREATE INDEX idx_sessions_user_device ON auth_sessions(user_id, device_id) WHERE is_active = TRUE;

-- ============================================================================
-- Table: auth_login_attempts
-- Purpose: Audit trail for login attempts (success and failure)
-- ============================================================================
CREATE TABLE auth_login_attempts (
    attempt_id      BIGSERIAL PRIMARY KEY,
    
    -- Identifier (may not have user_id if login fails)
    user_id         UUID,
    login_identifier VARCHAR(255) NOT NULL,
    
    -- Attempt Details
    attempt_type    VARCHAR(20) NOT NULL,
    is_successful   BOOLEAN NOT NULL,
    failure_reason  VARCHAR(100),
    
    -- Context
    ip_address      INET NOT NULL,
    user_agent      TEXT,
    device_id       VARCHAR(255),
    
    -- Security Signals
    is_suspicious   BOOLEAN DEFAULT FALSE,
    
    -- Timestamp
    attempted_at    TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Indexes for auth_login_attempts
CREATE INDEX idx_login_attempts_ip ON auth_login_attempts(ip_address, attempted_at DESC);
CREATE INDEX idx_login_attempts_identifier ON auth_login_attempts(login_identifier, attempted_at DESC);
CREATE INDEX idx_login_attempts_user ON auth_login_attempts(user_id, attempted_at DESC) WHERE user_id IS NOT NULL;
CREATE INDEX idx_login_attempts_suspicious ON auth_login_attempts(attempted_at DESC) WHERE is_suspicious = TRUE;

-- ============================================================================
-- Table: auth_password_reset_tokens
-- Purpose: Manages password reset tokens (one-time use)
-- ============================================================================
CREATE TABLE auth_password_reset_tokens (
    token_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL,
    
    -- Token
    token_hash      VARCHAR(255) NOT NULL UNIQUE,
    
    -- State
    is_used         BOOLEAN DEFAULT FALSE,
    used_at         TIMESTAMP WITH TIME ZONE,
    
    -- Context
    requested_ip    INET NOT NULL,
    requested_user_agent TEXT,
    
    -- Timestamps
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Indexes for auth_password_reset_tokens
CREATE INDEX idx_reset_token_hash ON auth_password_reset_tokens(token_hash) WHERE is_used = FALSE;
CREATE INDEX idx_reset_token_user ON auth_password_reset_tokens(user_id, created_at DESC);
CREATE INDEX idx_reset_token_expires ON auth_password_reset_tokens(expires_at) WHERE is_used = FALSE;
