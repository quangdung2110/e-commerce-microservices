-- Migration: 004_jwt_keys.sql
-- Purpose: Create jwt_keys table for JWKS key management
-- Author: Auth Service Team
-- Date: 2026-05-02

-- ============================================================================
-- Table: jwt_keys
-- Purpose: Store RSA key pairs for JWT signing (JWKS)
-- ============================================================================
CREATE TABLE IF NOT EXISTS jwt_keys (
    kid VARCHAR(100) PRIMARY KEY,
    
    -- Key Material (PEM format)
    public_key TEXT NOT NULL,
    private_key_encrypted TEXT NOT NULL, -- AES-256-GCM encrypted with master key
    
    -- Key Metadata
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'PHASE_OUT', 'RETIRED')),
    algorithm VARCHAR(20) NOT NULL DEFAULT 'RS256',
    
    -- Timestamps
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    rotated_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    
    -- Version for optimistic locking
    version INT NOT NULL DEFAULT 1,
    
    -- Additional metadata
    key_size_bits INT NOT NULL DEFAULT 4096,
    created_by VARCHAR(100) DEFAULT 'system'
);

-- Indexes for jwt_keys
CREATE INDEX idx_jwt_keys_status ON jwt_keys(status);
CREATE INDEX idx_jwt_keys_expires ON jwt_keys(expires_at);
CREATE INDEX idx_jwt_keys_created_at ON jwt_keys(created_at DESC);

-- Comment on table
COMMENT ON TABLE jwt_keys IS 'RSA key pairs for JWT signing with JWKS support';
COMMENT ON COLUMN jwt_keys.kid IS 'Key ID used in JWT kid header';
COMMENT ON COLUMN jwt_keys.public_key IS 'PEM-encoded RSA public key';
COMMENT ON COLUMN jwt_keys.private_key_encrypted IS 'AES-256-GCM encrypted private key (decrypt with AUTH_MASTER_KEY env var)';
COMMENT ON COLUMN jwt_keys.status IS 'Key status: ACTIVE (for signing), PHASE_OUT (transitioning), RETIRED (for verification only)';
