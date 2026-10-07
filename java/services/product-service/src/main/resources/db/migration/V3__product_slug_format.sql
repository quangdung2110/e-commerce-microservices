-- PRODUCT-BR-013: slug Product là kebab-case ASCII chữ thường, dài 1–180.
--
-- Migration không tự sửa slug hiện có: đổi slug phải đi qua admin API để tăng
-- catalog_revision và phát sku-upserted, nếu không Search giữ slug cũ và link PDP
-- gãy mà không ai biết. Vì vậy khi còn slug sai, migration dừng trước khi đổi bất
-- cứ thứ gì (Flyway chạy cả file trong một transaction trên PostgreSQL) và liệt kê
-- chúng để admin sửa rồi chạy lại.
DO $$
DECLARE
    offending TEXT;
BEGIN
    SELECT string_agg(product_id::text || ' => ' || quote_literal(slug), ', ' ORDER BY slug)
    INTO offending
    FROM products
    WHERE slug !~ '^[a-z0-9]+(-[a-z0-9]+)*$';

    IF offending IS NOT NULL THEN
        RAISE EXCEPTION 'PRODUCT-BR-013: fix these product slugs through the admin API before migrating: %',
            offending;
    END IF;
END
$$;

ALTER TABLE products
    ADD CONSTRAINT chk_products_slug_format CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$');
