-- Migration: Add last_activity column to auth_sessions table
-- Purpose: Track session activity to optimize DB writes and detect impossible travel

ALTER TABLE auth_sessions 
ADD COLUMN last_activity TIMESTAMP WITH TIME ZONE DEFAULT NOW();

-- Index for recent activity queries
CREATE INDEX idx_sessions_last_activity ON auth_sessions(user_id, last_activity DESC) WHERE is_active = TRUE;

-- Comment
COMMENT ON COLUMN auth_sessions.last_activity IS 'Last activity timestamp, updated via ActivityTracker middleware (throttled to 5min intervals via Redis)';
