-- =============================================
-- V1__initial_schema.sql
-- Initial database schema for product-service
-- =============================================

-- Categories table
CREATE TABLE categories (
    category_id VARCHAR(255) PRIMARY KEY,
    parent_id VARCHAR(255),
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(255) NOT NULL UNIQUE,
    level INT NOT NULL DEFAULT 0,
    is_leaf BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_categories_parent FOREIGN KEY (parent_id) REFERENCES categories(category_id) ON DELETE SET NULL
);
CREATE INDEX idx_categories_parent_id ON categories(parent_id);
CREATE INDEX idx_categories_slug ON categories(slug);
CREATE INDEX idx_categories_level ON categories(level);

-- Attributes table
CREATE TABLE attributes (
    attribute_id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Attribute values table
CREATE TABLE attribute_values (
    value_id VARCHAR(255) PRIMARY KEY,
    attribute_id VARCHAR(255) NOT NULL,
    value VARCHAR(255) NOT NULL,
    CONSTRAINT fk_attribute_values_attribute FOREIGN KEY (attribute_id) REFERENCES attributes(attribute_id) ON DELETE CASCADE
);
CREATE INDEX idx_attribute_values_attribute_id ON attribute_values(attribute_id);

-- Category attributes junction table
CREATE TABLE category_attributes (
    id VARCHAR(255) PRIMARY KEY,
    category_id VARCHAR(255) NOT NULL,
    attribute_id VARCHAR(255) NOT NULL,
    is_required BOOLEAN NOT NULL DEFAULT FALSE,
    is_variant BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_category_attributes_category FOREIGN KEY (category_id) REFERENCES categories(category_id) ON DELETE CASCADE,
    CONSTRAINT fk_category_attributes_attribute FOREIGN KEY (attribute_id) REFERENCES attributes(attribute_id) ON DELETE CASCADE,
    CONSTRAINT uq_category_attribute UNIQUE (category_id, attribute_id)
);
CREATE INDEX idx_category_attributes_category_id ON category_attributes(category_id);
CREATE INDEX idx_category_attributes_attribute_id ON category_attributes(attribute_id);

-- Products table
CREATE TABLE products (
    product_id VARCHAR(255) PRIMARY KEY,
    category_id VARCHAR(255),
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(255) NOT NULL,
    description TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    admin_hidden BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMPTZ,
    attributes_json JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES categories(category_id) ON DELETE SET NULL,
    CONSTRAINT uq_products_slug UNIQUE (slug),
    CONSTRAINT chk_products_status CHECK (status IN ('DRAFT', 'ACTIVE', 'HIDDEN'))
);
CREATE INDEX idx_products_category_id ON products(category_id);
CREATE INDEX idx_products_status ON products(status);
CREATE INDEX idx_products_slug ON products(slug);
CREATE INDEX idx_products_is_deleted ON products(is_deleted);

-- Product SKUs table
CREATE TABLE product_skus (
    sku_id VARCHAR(255) PRIMARY KEY,
    product_id VARCHAR(255) NOT NULL,
    sku_code VARCHAR(255) NOT NULL UNIQUE,
    original_price DECIMAL(15,2) NOT NULL,
    promotional_price DECIMAL(15,2),
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMPTZ,
    display_quantity INT NOT NULL DEFAULT 0,
    last_inventory_sync_at BIGINT NOT NULL DEFAULT 0,
    variant_attributes_json JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_product_skus_product FOREIGN KEY (product_id) REFERENCES products(product_id) ON DELETE CASCADE,
    CONSTRAINT chk_product_skus_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'OUT_OF_STOCK'))
);
CREATE INDEX idx_product_skus_product_id ON product_skus(product_id);
CREATE INDEX idx_product_skus_sku_code ON product_skus(sku_code);
CREATE INDEX idx_product_skus_status ON product_skus(status);
CREATE INDEX idx_product_skus_is_deleted ON product_skus(is_deleted);
CREATE INDEX idx_product_skus_last_sync ON product_skus(last_inventory_sync_at);

-- Product media table
CREATE TABLE product_media (
    media_id VARCHAR(255) PRIMARY KEY,
    product_id VARCHAR(255) NOT NULL,
    sku_id VARCHAR(255),
    media_type VARCHAR(50) NOT NULL,
    url VARCHAR(2048) NOT NULL,
    thumbnail_url VARCHAR(2048),
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_product_media_product FOREIGN KEY (product_id) REFERENCES products(product_id) ON DELETE CASCADE,
    CONSTRAINT fk_product_media_sku FOREIGN KEY (sku_id) REFERENCES product_skus(sku_id) ON DELETE CASCADE
);
CREATE INDEX idx_product_media_product_id ON product_media(product_id);
CREATE INDEX idx_product_media_sku_id ON product_media(sku_id);
CREATE INDEX idx_product_media_sort_order ON product_media(sort_order);

-- Product markdown applied table
CREATE TABLE product_markdown_applied (
    id VARCHAR(255) PRIMARY KEY,
    product_id VARCHAR(255) NOT NULL,
    rule_id VARCHAR(255) NOT NULL,
    sku_id VARCHAR(255),
    discount_type VARCHAR(50) NOT NULL,
    discount_value DECIMAL(15,2) NOT NULL,
    max_discount_amount DECIMAL(15,2),
    calculated_price DECIMAL(15,2) NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_product_markdown_product FOREIGN KEY (product_id) REFERENCES products(product_id) ON DELETE CASCADE,
    CONSTRAINT fk_product_markdown_sku FOREIGN KEY (sku_id) REFERENCES product_skus(sku_id) ON DELETE CASCADE,
    CONSTRAINT uq_markdown_rule_sku UNIQUE (rule_id, sku_id)
);
CREATE INDEX idx_product_markdown_product_id ON product_markdown_applied(product_id);
CREATE INDEX idx_product_markdown_rule_id ON product_markdown_applied(rule_id);
CREATE INDEX idx_product_markdown_sku_id ON product_markdown_applied(sku_id);
CREATE INDEX idx_product_markdown_expires_at ON product_markdown_applied(expires_at);

-- Product outbox events table
CREATE TABLE product_outbox_events (
    event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id VARCHAR(255) NOT NULL,
    partition_key VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_outbox_partition_key ON product_outbox_events(partition_key);
CREATE INDEX idx_outbox_created_at ON product_outbox_events(created_at);
CREATE INDEX idx_outbox_aggregate_type ON product_outbox_events(aggregate_type);
