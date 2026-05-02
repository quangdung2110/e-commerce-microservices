-- Migration: 007_add_grace_fields.sql
-- Purpose: Add grace period tracking fields to sessions table
-- Author: Auth Service Team
-- Date: 2026-05-02

-- ============================================================================
-- Add grace period fields to auth_sessions
-- Purpose: Track when token rotation grace period expires
-- Used to distinguish between legitimate concurrent refresh and token theft
-- ============================================================================

-- Add grace_expires_at column
ALTER TABLE auth_sessions 
ADD COLUMN IF NOT EXISTS grace_expires_at TIMESTAMP WITH TIME ZONE;

-- Add grace_token_hash column (stores hash of token that replaced this one)
ALTER TABLE auth_sessions 
ADD COLUMN IF NOT EXISTS grace_token_hash VARCHAR(255);

-- Add revocation_type column to track why session was revoked
ALTER TABLE auth_sessions 
ADD COLUMN IF NOT EXISTS revocation_type VARCHAR(50) 
CHECK (revocation_type IN ('USER_LOGOUT', 'ADMIN_LOGOUT', 'TOKEN_THEFT', 'PASSWORD_CHANGE', 'ACCOUNT_BAN', 'EXPIRED'));

-- Add index for grace period queries
CREATE INDEX IF NOT EXISTS idx_sessions_grace_expires 
ON auth_sessions(grace_expires_at) 
WHERE grace_expires_at IS NOT NULL AND is_active = FALSE;

-- Add index for revocation tracking
CREATE INDEX IF NOT EXISTS idx_sessions_revocation_type 
ON auth_sessions(revocation_type) 
WHERE revocation_type IS NOT NULL;

-- Comment on columns
COMMENT ON COLUMN auth_sessions.grace_expires_at IS 'When grace period expires for this revoked token';
COMMENT ON COLUMN auth_sessions.grace_token_hash IS 'Hash of the token that replaced this one during rotation';
COMMENT ON COLUMN auth_sessions.revocation_type IS 'Reason for session revocation';
