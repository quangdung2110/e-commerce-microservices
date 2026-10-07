-- Markdown projection theo contract state-discriminated của Promotion Events v1
-- (contracts/events/promotion/v1, docs/product/data.md §3.3): INACTIVE là tombstone
-- chỉ mang inactive_reason; rule/discount/interval đều null.

ALTER TABLE product_markdown_projection
    DROP CONSTRAINT chk_markdown_period,
    DROP CONSTRAINT chk_markdown_configuration,
    DROP CONSTRAINT product_markdown_projection_promotion_rule_id_key,
    ALTER COLUMN promotion_rule_id DROP NOT NULL,
    ALTER COLUMN discount_type DROP NOT NULL,
    ALTER COLUMN starts_at DROP NOT NULL,
    ALTER COLUMN ends_at DROP NOT NULL,
    ADD COLUMN inactive_reason VARCHAR(32) NULL;

-- Row INACTIVE đã tồn tại từ V1 (đang mang rule cũ) trở thành tombstone EXPIRED.
UPDATE product_markdown_projection
SET promotion_rule_id = NULL, discount_type = NULL, discount_bps = NULL, fixed_discount_vnd = NULL,
    max_discount_vnd = NULL, starts_at = NULL, ends_at = NULL, inactive_reason = 'EXPIRED'
WHERE state = 'INACTIVE';

ALTER TABLE product_markdown_projection
    ADD CONSTRAINT chk_markdown_inactive_reason CHECK (
        inactive_reason IS NULL OR inactive_reason IN ('NO_ACTIVE_RULE', 'EXPIRED', 'ADMIN_DISABLED')),
    ADD CONSTRAINT chk_markdown_state_shape CHECK (
        (state = 'ACTIVE' AND promotion_rule_id IS NOT NULL AND discount_type IS NOT NULL
         AND starts_at IS NOT NULL AND ends_at IS NOT NULL AND starts_at < ends_at AND inactive_reason IS NULL)
        OR
        (state = 'INACTIVE' AND promotion_rule_id IS NULL AND discount_type IS NULL AND discount_bps IS NULL
         AND fixed_discount_vnd IS NULL AND max_discount_vnd IS NULL AND starts_at IS NULL AND ends_at IS NULL
         AND inactive_reason IS NOT NULL)),
    ADD CONSTRAINT chk_markdown_configuration CHECK (
        discount_type IS NULL
        OR (discount_type = 'PERCENTAGE' AND discount_bps BETWEEN 1 AND 10000
            AND fixed_discount_vnd IS NULL AND (max_discount_vnd IS NULL OR max_discount_vnd > 0))
        OR (discount_type = 'FIXED_VND' AND fixed_discount_vnd > 0
            AND discount_bps IS NULL AND max_discount_vnd IS NULL));

-- Partial unique thay cho UNIQUE cũ: tombstone có promotion_rule_id NULL (docs/product/data.md §4).
CREATE UNIQUE INDEX uq_product_markdown_projection_rule
    ON product_markdown_projection (promotion_rule_id) WHERE promotion_rule_id IS NOT NULL;
