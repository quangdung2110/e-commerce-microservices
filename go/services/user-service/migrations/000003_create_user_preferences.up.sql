-- 000003_create_user_preferences.up.sql
-- Migration: Create user_preferences table

CREATE TABLE user_preferences (
    user_id UUID PRIMARY KEY,
    language VARCHAR(10) NOT NULL DEFAULT 'vi',
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    email_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    in_app_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    marketing_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    CONSTRAINT fk_user_preferences_profile 
        FOREIGN KEY (user_id) 
        REFERENCES user_profiles(id) 
        ON DELETE CASCADE,
    CONSTRAINT chk_language CHECK (language IN ('vi', 'en', 'zh', 'ja', 'ko')),
    CONSTRAINT chk_currency CHECK (currency IN ('VND', 'USD', 'EUR', 'JPY', 'CNY'))
);

-- Index for admin queries
CREATE INDEX idx_user_preferences_language ON user_preferences(language);

-- Trigger for auto-updating updated_at
CREATE TRIGGER update_user_preferences_updated_at
    BEFORE UPDATE ON user_preferences
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE user_preferences IS 'Stores user app preferences for notifications and display';
