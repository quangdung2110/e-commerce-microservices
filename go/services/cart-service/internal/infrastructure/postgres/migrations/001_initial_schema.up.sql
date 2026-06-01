-- Cart Service Initial Schema
-- Ref: docs/cart/cart_db_schema.md, Module 07

-- Table: carts (master record)
CREATE TABLE IF NOT EXISTS carts (
    cart_id                      VARCHAR(255) PRIMARY KEY,
    user_id                      VARCHAR(255) NOT NULL,
    status                       VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    last_event_stream_id         VARCHAR(64) NULL,
    abandoned_event_published_at TIMESTAMPTZ NULL,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_carts_user_id ON carts(user_id);
CREATE INDEX IF NOT EXISTS idx_carts_status ON carts(status);
CREATE INDEX IF NOT EXISTS idx_carts_updated_at ON carts(updated_at);
CREATE INDEX IF NOT EXISTS idx_carts_status_updated ON carts(status, updated_at);

-- Table: cart_items
CREATE TABLE IF NOT EXISTS cart_items (
    item_id              VARCHAR(255) PRIMARY KEY,  -- Format: {cart_id}:{sku_id}
    cart_id              VARCHAR(255) NOT NULL,
    product_id           VARCHAR(255) NOT NULL,
    sku_id               VARCHAR(255) NOT NULL,
    quantity             INT NOT NULL DEFAULT 0,
    unit_price_at_add    DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    product_snapshot     JSONB NOT NULL,
    redis_stream_id      VARCHAR(64) NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_cart_items_cart_id ON cart_items(cart_id);
CREATE INDEX IF NOT EXISTS idx_cart_items_product_id ON cart_items(product_id);
CREATE INDEX IF NOT EXISTS idx_cart_items_sku_id ON cart_items(sku_id);
CREATE INDEX IF NOT EXISTS idx_cart_items_redis_stream_id ON cart_items(redis_stream_id);

-- Note: No FK constraint on cart_id (application-level reference only)
-- Note: No CASCADE DELETE (must delete cart_items manually before carts)
