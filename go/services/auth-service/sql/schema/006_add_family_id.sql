-- Migration: 006_add_family_id.sql
-- Purpose: Add family_id to sessions table for token lineage tracking
-- Author: Auth Service Team
-- Date: 2026-05-02

-- ============================================================================
-- Add family_id column to auth_sessions
-- Purpose: Track token lineage for refresh token rotation
-- All tokens in a family are invalidated when theft is detected
-- ============================================================================

-- Add family_id column
ALTER TABLE auth_sessions 
ADD COLUMN IF NOT EXISTS family_id UUID;

-- Add replaced_by column for token chain tracking
ALTER TABLE auth_sessions 
ADD COLUMN IF NOT EXISTS replaced_by VARCHAR(255);

-- Create default family_id for existing sessions
UPDATE auth_sessions 
SET family_id = gen_random_uuid() 
WHERE family_id IS NULL;

-- Set family_id as NOT NULL after populating existing rows
ALTER TABLE auth_sessions 
ALTER COLUMN family_id SET NOT NULL;

-- Create index for family lookups
CREATE INDEX IF NOT EXISTS idx_sessions_family_id ON auth_sessions(family_id);

-- Composite index for user + family queries
CREATE INDEX IF NOT EXISTS idx_sessions_user_family ON auth_sessions(user_id, family_id);

-- Comment on columns
COMMENT ON COLUMN auth_sessions.family_id IS 'Token family ID - all tokens in a family are invalidated together';
COMMENT ON COLUMN auth_sessions.replaced_by IS 'Session ID that replaced this token in rotation';
