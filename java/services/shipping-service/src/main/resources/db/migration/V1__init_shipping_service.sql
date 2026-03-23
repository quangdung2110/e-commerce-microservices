-- Theo thiết kế shipping_db_design.md: schema khởi tạo PostgreSQL shipping service (skeleton phase).
CREATE TABLE IF NOT EXISTS shipping_providers (
    id INT PRIMARY KEY,
    code VARCHAR(50) UNIQUE NOT NULL,
    name VARCHAR(100) NOT NULL,
    is_active BOOLEAN NOT NULL,
    priority INT NOT NULL,
    api_config JSONB
);

CREATE TABLE IF NOT EXISTS rate_cards (
    id BIGSERIAL PRIMARY KEY,
    provider_id INT NOT NULL REFERENCES shipping_providers(id),
    from_province_code VARCHAR(20) NOT NULL,
    from_district_code VARCHAR(20),
    to_province_code VARCHAR(20) NOT NULL,
    to_district_code VARCHAR(20),
    to_ward_code VARCHAR(20),
    base_weight INT NOT NULL,
    base_fee NUMERIC(19,2) NOT NULL,
    step_weight INT NOT NULL,
    step_fee NUMERIC(19,2) NOT NULL,
    estimated_days INT NOT NULL
);

CREATE TABLE IF NOT EXISTS shipping_rules (
    id SERIAL PRIMARY KEY,
    rule_name VARCHAR(100) NOT NULL,
    conditions_rule JSONB NOT NULL,
    action_provider_id INT NOT NULL REFERENCES shipping_providers(id),
    priority INT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true
);

CREATE TABLE IF NOT EXISTS rate_card_change_log (
    id BIGSERIAL PRIMARY KEY,
    provider_id INT NOT NULL REFERENCES shipping_providers(id),
    rate_card_id BIGINT,
    change_type VARCHAR(30) NOT NULL,
    old_value JSONB,
    new_value JSONB,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS shipments (
    id BIGSERIAL PRIMARY KEY,
    parcel_id BIGINT UNIQUE NOT NULL,
    order_id BIGINT NOT NULL,
    provider_id INT NOT NULL REFERENCES shipping_providers(id),
    tracking_number VARCHAR(100) UNIQUE,
    sender_info JSONB,
    receiver_info JSONB,
    package_info JSONB,
    standardized_status VARCHAR(50) NOT NULL,
    provider_status VARCHAR(100),
    shipping_fee NUMERIC(19,2),
    cod_amount NUMERIC(19,2),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS provider_status_mappings (
    id BIGSERIAL PRIMARY KEY,
    provider_id INT NOT NULL REFERENCES shipping_providers(id),
    provider_status_code VARCHAR(100) NOT NULL,
    standardized_status VARCHAR(50) NOT NULL,
    UNIQUE(provider_id, provider_status_code)
);

CREATE TABLE IF NOT EXISTS idempotency_keys (
    key VARCHAR(255) PRIMARY KEY,
    action VARCHAR(100) NOT NULL,
    locked_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS webhook_idempotency (
    id BIGSERIAL PRIMARY KEY,
    provider VARCHAR(50) NOT NULL,
    provider_event_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(provider, provider_event_id)
);

CREATE TABLE IF NOT EXISTS outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    max_retries INT NOT NULL DEFAULT 5,
    published_at TIMESTAMPTZ,
    next_retry_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
