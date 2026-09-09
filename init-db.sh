#!/bin/bash
set -e

# ── Create additional databases ────────────────────────────
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE cart_db;
    GRANT ALL PRIVILEGES ON DATABASE cart_db TO postgres;
    CREATE DATABASE auth_db;
    GRANT ALL PRIVILEGES ON DATABASE auth_db TO postgres;
    CREATE DATABASE user_service;
    GRANT ALL PRIVILEGES ON DATABASE user_service TO postgres;
    CREATE DATABASE review_service;
    GRANT ALL PRIVILEGES ON DATABASE review_service TO postgres;
EOSQL

# ── Apply Cart Service schema ─────────────────────────────
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "cart_db" <<-EOSQL
    -- 001_initial_schema.up.sql
    CREATE TABLE IF NOT EXISTS carts (
        cart_id VARCHAR(255) PRIMARY KEY,
        user_id VARCHAR(255) NOT NULL,
        status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
        last_event_stream_id VARCHAR(255),
        abandoned_event_published_at TIMESTAMPTZ,
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    CREATE TABLE IF NOT EXISTS cart_items (
        item_id VARCHAR(510) PRIMARY KEY,
        cart_id VARCHAR(255) NOT NULL REFERENCES carts(cart_id),
        product_id VARCHAR(255) NOT NULL,
        sku_id VARCHAR(255) NOT NULL,
        quantity INT NOT NULL DEFAULT 1,
        unit_price_at_add DECIMAL(15,2) NOT NULL,
        product_snapshot JSONB NOT NULL DEFAULT '{}',
        redis_stream_id VARCHAR(255),
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

    CREATE INDEX IF NOT EXISTS idx_cart_items_cart_id ON cart_items(cart_id);
    CREATE INDEX IF NOT EXISTS idx_carts_user_id ON carts(user_id);
    CREATE INDEX IF NOT EXISTS idx_carts_status ON carts(status);
    CREATE INDEX IF NOT EXISTS idx_carts_updated_at ON carts(updated_at);
EOSQL
