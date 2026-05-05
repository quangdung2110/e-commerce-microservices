-- 000002_create_user_addresses.up.sql
-- Migration: Create user_addresses table

CREATE TABLE user_addresses (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    recipient_name VARCHAR(200) NOT NULL,
    phone_number VARCHAR(20) NOT NULL,
    address_line VARCHAR(500) NOT NULL,
    ward_id VARCHAR(20) NOT NULL,
    district_id VARCHAR(20) NOT NULL,
    city_id VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    CONSTRAINT fk_user_addresses_user_profile 
        FOREIGN KEY (user_id) 
        REFERENCES user_profiles(id) 
        ON DELETE CASCADE,
    CONSTRAINT chk_address_phone_format 
        CHECK (phone_number ~ '^\+?[0-9]{9,15}$')
);

-- Indexes for common queries
CREATE INDEX idx_user_addresses_user_id ON user_addresses(user_id);
CREATE INDEX idx_user_addresses_ward ON user_addresses(ward_id);
CREATE INDEX idx_user_addresses_district ON user_addresses(district_id);
CREATE INDEX idx_user_addresses_city ON user_addresses(city_id);
CREATE INDEX idx_user_addresses_user_default ON user_addresses(user_id, is_default) WHERE is_default = TRUE;

-- CRITICAL: Partial Unique Index for default address constraint
CREATE UNIQUE INDEX idx_unique_default_address 
ON user_addresses (user_id) 
WHERE is_default = TRUE;

-- Composite index for listing addresses (default first)
CREATE INDEX idx_user_addresses_user_list 
ON user_addresses (user_id, is_default DESC, created_at DESC);

-- Trigger for auto-updating updated_at
CREATE TRIGGER update_user_addresses_updated_at
    BEFORE UPDATE ON user_addresses
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE user_addresses IS 'Stores user shipping addresses - Hard Delete policy (Order Service snapshots)';
COMMENT ON INDEX idx_unique_default_address IS 'Ensures only 1 default address per user at database level';
