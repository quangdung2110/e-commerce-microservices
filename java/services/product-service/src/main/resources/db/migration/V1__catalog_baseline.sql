-- =============================================================================
-- V1__catalog_baseline.sql
--
-- Baseline schema cho Product Service theo docs/product/schema/schema.sql.
-- Không có is_deleted, quantity, stock timestamp, inbox hay idempotency table:
-- Inventory là source of truth duy nhất cho tồn kho (docs/product/data.md §1).
--
-- UUIDv7 do application sinh; giá VND dùng BIGINT.
-- =============================================================================

CREATE TABLE categories (
    category_id UUID PRIMARY KEY,
    parent_id UUID NULL REFERENCES categories(category_id) ON DELETE RESTRICT,
    name VARCHAR(160) NOT NULL,
    slug VARCHAR(160) NOT NULL UNIQUE,
    category_revision BIGINT NOT NULL DEFAULT 1 CHECK (category_revision > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_categories_parent_id ON categories(parent_id);

CREATE TABLE attributes (
    attribute_id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    value_type VARCHAR(16) NOT NULL CHECK (value_type IN ('TEXT', 'NUMBER', 'BOOLEAN', 'SELECT')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE attribute_options (
    option_id UUID PRIMARY KEY,
    attribute_id UUID NOT NULL REFERENCES attributes(attribute_id) ON DELETE RESTRICT,
    code VARCHAR(64) NOT NULL,
    label VARCHAR(120) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0 CHECK (sort_order >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_attribute_options_attribute_code UNIQUE (attribute_id, code)
);
CREATE INDEX idx_attribute_options_attribute_sort ON attribute_options(attribute_id, sort_order);

CREATE TABLE category_attributes (
    category_id UUID NOT NULL REFERENCES categories(category_id) ON DELETE RESTRICT,
    attribute_id UUID NOT NULL REFERENCES attributes(attribute_id) ON DELETE RESTRICT,
    is_required BOOLEAN NOT NULL DEFAULT FALSE,
    is_variant BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (category_id, attribute_id)
);
CREATE INDEX idx_category_attributes_attribute_id ON category_attributes(attribute_id);

CREATE TABLE products (
    product_id UUID PRIMARY KEY,
    category_id UUID NOT NULL REFERENCES categories(category_id) ON DELETE RESTRICT,
    name VARCHAR(255) NOT NULL,
    slug VARCHAR(180) NOT NULL UNIQUE,
    description TEXT NULL,
    attributes_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'ACTIVE', 'HIDDEN')),
    catalog_revision BIGINT NOT NULL DEFAULT 1 CHECK (catalog_revision > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_products_attributes_json_object CHECK (jsonb_typeof(attributes_json) = 'object')
);
CREATE INDEX idx_products_category_status ON products(category_id, status);

CREATE TABLE product_skus (
    sku_id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES products(product_id) ON DELETE CASCADE,
    sku_code VARCHAR(64) NOT NULL UNIQUE,
    base_price_vnd BIGINT NOT NULL CHECK (base_price_vnd > 0),
    selling_price_vnd BIGINT NOT NULL CHECK (selling_price_vnd >= 0 AND selling_price_vnd <= base_price_vnd),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    variant_attributes_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_product_skus_variant_json_object CHECK (jsonb_typeof(variant_attributes_json) = 'object'),
    CONSTRAINT uq_product_skus_product_sku UNIQUE (product_id, sku_id)
);
CREATE INDEX idx_product_skus_product_id ON product_skus(product_id);

CREATE TABLE product_media (
    media_id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES products(product_id) ON DELETE CASCADE,
    sku_id UUID NULL,
    media_type VARCHAR(16) NOT NULL CHECK (media_type IN ('IMAGE', 'VIDEO')),
    storage_key VARCHAR(1024) NOT NULL CHECK (length(storage_key) > 0),
    alt_text VARCHAR(255) NULL,
    sort_order INTEGER NOT NULL DEFAULT 0 CHECK (sort_order >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_product_media_sku_same_product
        FOREIGN KEY (product_id, sku_id)
        REFERENCES product_skus(product_id, sku_id)
        ON DELETE CASCADE
);
CREATE INDEX idx_product_media_product_sort ON product_media(product_id, sort_order);
CREATE INDEX idx_product_media_sku_sort ON product_media(sku_id, sort_order) WHERE sku_id IS NOT NULL;

-- Một row cho mỗi Product. Row ở trạng thái INACTIVE được giữ lại để replay
-- event cũ không kích hoạt lại giá cũ (docs/product/data.md §3.3).
CREATE TABLE product_markdown_projection (
    product_id UUID PRIMARY KEY REFERENCES products(product_id) ON DELETE CASCADE,
    promotion_rule_id BIGINT NOT NULL UNIQUE,
    source_revision BIGINT NOT NULL CHECK (source_revision > 0),
    state VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVE', 'INACTIVE')),
    discount_type VARCHAR(16) NOT NULL CHECK (discount_type IN ('PERCENTAGE', 'FIXED_VND')),
    discount_bps INTEGER NULL,
    fixed_discount_vnd BIGINT NULL,
    max_discount_vnd BIGINT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_markdown_period CHECK (starts_at < ends_at),
    CONSTRAINT chk_markdown_configuration CHECK (
        (discount_type = 'PERCENTAGE' AND discount_bps BETWEEN 1 AND 10000
         AND fixed_discount_vnd IS NULL AND (max_discount_vnd IS NULL OR max_discount_vnd > 0))
        OR
        (discount_type = 'FIXED_VND' AND fixed_discount_vnd > 0
         AND discount_bps IS NULL AND max_discount_vnd IS NULL)
    )
);

-- Không đặt FK từ outbox tới aggregate: tombstone phải tồn tại sau hard delete.
CREATE TABLE product_outbox_events (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL CHECK (aggregate_type IN ('Product', 'Category')),
    aggregate_id UUID NOT NULL,
    partition_key UUID NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_product_outbox_events_created_at ON product_outbox_events(created_at);
CREATE INDEX idx_product_outbox_events_partition_created ON product_outbox_events(partition_key, created_at);
