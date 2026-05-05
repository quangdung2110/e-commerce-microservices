-- 000001_create_user_profiles.up.sql
-- Migration: Create user_profiles table

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE user_profiles (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    phone_number VARCHAR(20) UNIQUE,
    date_of_birth DATE,
    gender VARCHAR(10) NOT NULL DEFAULT 'UNKNOWN',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    CONSTRAINT chk_gender CHECK (gender IN ('MALE', 'FEMALE', 'OTHER', 'UNKNOWN')),
    CONSTRAINT chk_phone_format CHECK (phone_number IS NULL OR phone_number ~ '^\+?[0-9]{9,15}$')
);

-- Indexes for common queries
CREATE INDEX idx_user_profiles_phone ON user_profiles(phone_number);
CREATE INDEX idx_user_profiles_created_at ON user_profiles(created_at DESC);

-- Trigger for auto-updating updated_at
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_user_profiles_updated_at
    BEFORE UPDATE ON user_profiles
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE user_profiles IS 'Stores customer profile information - 1:1 mapping with auth.users';
