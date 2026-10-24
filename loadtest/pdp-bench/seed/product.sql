-- Synthetic catalog for the PDP benchmark, run against the bench product_service database after Product's Flyway
-- migrations. Deterministic: every id derives from md5 of a stable label, so a rerun builds the same catalog.
--
--   :products  number of ACTIVE products (psql -v products=10000)
--
-- Shape (close to a storefront, sized after docs/performance/search-scale-benchmark.md):
--   - 3-level category tree, 4 roots x 4 x 5 = 80 leaves; products spread over the leaves;
--   - 4 attributes mapped on every leaf: brand (SELECT) and material (TEXT) as specs, color and size (SELECT) as
--     variant axes;
--   - 1-4 ACTIVE SKUs per product (2.5 on average), 20% of SKUs on a markdown price;
--   - 4-8 product images plus one image per SKU, a ~700 character description.
-- Destructive for the target database: it empties the catalog tables first. Run it only on the bench database.

\set ON_ERROR_STOP on
BEGIN;

TRUNCATE product_media, product_skus, products, category_attributes, attribute_options, attributes, categories
    CASCADE;

CREATE TEMP TABLE leaf AS
SELECT row_number() OVER (ORDER BY r, m, l) - 1 AS leaf_no, r, m, l FROM
    generate_series(1, 4) r, generate_series(1, 4) m, generate_series(1, 5) l;

INSERT INTO categories (category_id, parent_id, name, slug)
SELECT md5('cat-r' || r)::uuid, NULL, 'Ngành hàng ' || r, 'nganh-hang-' || r FROM generate_series(1, 4) r;
INSERT INTO categories (category_id, parent_id, name, slug)
SELECT md5('cat-r' || r || '-m' || m)::uuid, md5('cat-r' || r)::uuid, 'Nhóm ' || r || '.' || m,
       'nhom-' || r || '-' || m
FROM generate_series(1, 4) r, generate_series(1, 4) m;
INSERT INTO categories (category_id, parent_id, name, slug)
SELECT md5('cat-r' || r || '-m' || m || '-l' || l)::uuid, md5('cat-r' || r || '-m' || m)::uuid,
       'Danh mục ' || r || '.' || m || '.' || l, 'danh-muc-' || r || '-' || m || '-' || l
FROM leaf;

INSERT INTO attributes (attribute_id, code, name, value_type) VALUES
    (md5('attr-brand')::uuid, 'brand', 'Thương hiệu', 'SELECT'),
    (md5('attr-material')::uuid, 'material', 'Chất liệu', 'TEXT'),
    (md5('attr-color')::uuid, 'color', 'Màu sắc', 'SELECT'),
    (md5('attr-size')::uuid, 'size', 'Kích cỡ', 'SELECT');

INSERT INTO attribute_options (option_id, attribute_id, code, label, sort_order)
SELECT md5('opt-brand-' || b)::uuid, md5('attr-brand')::uuid, 'b' || b, 'Thương hiệu ' || b, b
FROM generate_series(1, 8) b;
INSERT INTO attribute_options (option_id, attribute_id, code, label, sort_order)
SELECT md5('opt-color-' || c.code)::uuid, md5('attr-color')::uuid, c.code, c.label, c.ord
FROM (VALUES ('den', 'Đen', 1), ('trang', 'Trắng', 2), ('do', 'Đỏ', 3), ('xanh', 'Xanh', 4), ('vang', 'Vàng', 5))
    AS c(code, label, ord);
INSERT INTO attribute_options (option_id, attribute_id, code, label, sort_order)
SELECT md5('opt-size-' || s.code)::uuid, md5('attr-size')::uuid, s.code, upper(s.code), s.ord
FROM (VALUES ('s', 1), ('m', 2), ('l', 3), ('xl', 4)) AS s(code, ord);

INSERT INTO category_attributes (category_id, attribute_id, is_required, is_variant)
SELECT md5('cat-r' || r || '-m' || m || '-l' || l)::uuid, md5('attr-' || a.code)::uuid, false, a.variant
FROM leaf, (VALUES ('brand', false), ('material', false), ('color', true), ('size', true)) AS a(code, variant);

INSERT INTO products (product_id, category_id, name, slug, description, attributes_json, status, catalog_revision)
SELECT md5('p-' || i)::uuid,
       (SELECT md5('cat-r' || r || '-m' || m || '-l' || l)::uuid FROM leaf WHERE leaf_no = i % 80),
       'Sản phẩm benchmark ' || i,
       'bench-p-' || lpad(i::text, 6, '0'),
       repeat('Mô tả sản phẩm benchmark số ' || i || ', chất liệu bền, đóng gói cẩn thận, giao nhanh. ', 9),
       jsonb_build_object('brand', 'b' || (1 + i % 8), 'material', 'cotton pha ' || (i % 7) || '%'),
       'ACTIVE', 1 + i % 5
FROM generate_series(1, :products) i;

INSERT INTO product_skus (sku_id, product_id, sku_code, base_price_vnd, selling_price_vnd, status,
                          variant_attributes_json)
SELECT md5('s-' || i || '-' || k)::uuid, md5('p-' || i)::uuid,
       'BENCH-' || i || '-' || k,
       (100000 + (i * 7919 + k * 104729) % 1900000) / 1000 * 1000,
       CASE WHEN (i + k) % 5 = 0
            THEN (100000 + (i * 7919 + k * 104729) % 1900000) / 1000 * 800
            ELSE (100000 + (i * 7919 + k * 104729) % 1900000) / 1000 * 1000 END,
       'ACTIVE',
       jsonb_build_object('color', (ARRAY['den', 'trang', 'do', 'xanh', 'vang'])[1 + (i + k) % 5],
                          'size', (ARRAY['s', 'm', 'l', 'xl'])[k])
FROM generate_series(1, :products) i, generate_series(1, 4) k
WHERE k <= 1 + i % 4;

INSERT INTO product_media (media_id, product_id, sku_id, media_type, storage_key, alt_text, sort_order)
SELECT md5('m-' || i || '-' || n)::uuid, md5('p-' || i)::uuid, NULL, 'IMAGE',
       'products/' || md5('p-' || i) || '/gallery-' || n || '.jpg', 'Ảnh ' || n || ' của sản phẩm ' || i, n
FROM generate_series(1, :products) i, generate_series(1, 8) n
WHERE n <= 4 + i % 5;

INSERT INTO product_media (media_id, product_id, sku_id, media_type, storage_key, alt_text, sort_order)
SELECT md5('ms-' || s.sku_id)::uuid, s.product_id, s.sku_id, 'IMAGE',
       'products/' || s.product_id || '/sku-' || s.sku_code || '.jpg', 'Ảnh ' || s.sku_code, 0
FROM product_skus s;

COMMIT;

ANALYZE;
SELECT (SELECT count(*) FROM products) AS products, (SELECT count(*) FROM product_skus) AS skus,
       (SELECT count(*) FROM product_media) AS media;
