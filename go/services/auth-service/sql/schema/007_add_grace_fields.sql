-- Migration: Add grace period and token rotation tracking to auth_sessions
-- This migration adds fields needed for Grace Period 10s token rotation

-- Add family_id for grouping tokens from the same login session
ALTER TABLE auth_sessions 
ADD COLUMN IF NOT EXISTS family_id UUID;

-- Add rotated_from to track token lineage
ALTER TABLE auth_sessions 
ADD COLUMN IF NOT EXISTS rotated_from UUID;

-- Add grace_expires_at to track when grace period ends
ALTER TABLE auth_sessions 
ADD COLUMN IF NOT EXISTS grace_expires_at TIMESTAMPTZ;

-- Add token_status to track token state
ALTER TABLE auth_sessions 
ADD COLUMN IF NOT EXISTS token_status VARCHAR(20) DEFAULT 'ACTIVE';

-- Create index on family_id for efficient queries
CREATE INDEX IF NOT EXISTS idx_sessions_family_id ON auth_sessions(family_id);

-- Create index on token_status for filtering
CREATE INDEX IF NOT EXISTS idx_sessions_token_status ON auth_sessions(token_status);

-- Create index on rotated_from for tracking token lineage
CREATE INDEX IF NOT EXISTS idx_sessions_rotated_from ON auth_sessions(rotated_from);

-- Create composite index for finding token by hash (for rotation)
CREATE INDEX IF NOT EXISTS idx_sessions_token_hash_status ON auth_sessions(refresh_token_hash, token_status) WHERE token_status = 'ACTIVE';

-- Update existing sessions to have token_status = 'ACTIVE'
UPDATE auth_sessions SET token_status = 'ACTIVE' WHERE token_status IS NULL OR token_status = '';

-- Add constraint for token_status values
ALTER TABLE auth_sessions 
ADD CONSTRAINT chk_token_status 
CHECK (token_status IN ('ACTIVE', 'USED', 'REVOKED', 'EXPIRED'));
