---
doc_id: product-data
service: product
scope: current
status: accepted_design
owners:
  - Product Service team
---

# Product Service — Data Design

DDL để review nằm tại [schema/schema.sql](./schema/schema.sql). Đây là thiết kế đích;
migration Flyway phải được tạo theo hướng additive trước khi implementation chuyển sang
schema này.

## 1. Ownership và boundary

| Bounded context | Source of truth | Product lưu gì? |
|---|---|---|
| Catalog, SKU, category, attribute, media, giá niêm yết | Product | Dữ liệu gốc |
| Tồn kho, availability, reservation, deduct | Inventory | Không lưu quantity, stock status hay stock timestamp |
| Markdown rule và voucher | Promotion | Một projection markdown đang hiệu lực để tính giá công bố |
| UI browse/search | Search | Không lưu trong Product |

Product không consume event Inventory và không có Redis cache/projection tồn kho. Cart
kiểm tra catalog và giá ở Product; Checkout lấy allocation preview, còn Order Reserve
tại Inventory là quyết định cuối cùng về tồn kho. Search nhận stock UI qua Inventory event/export; Product
Detail BFF nhận advisory stock UI qua Inventory batch-read sau khi đọc catalog.

## 2. Quy ước chung

- ID Catalog dùng UUIDv7 do application sinh; giá VND dùng `BIGINT`.
- `created_at` và `updated_at` dùng `TIMESTAMPTZ`; application đặt `updated_at` khi sửa.
- Product/SKU hard delete. Không có `is_deleted`, `admin_hidden`, `OUT_OF_STOCK`,
  `display_quantity`, `available_quantity`, `last_inventory_sync_at`, Redis cache,
  inventory worker, inbox hay idempotency table trong Product.
- `products.catalog_revision` tăng đúng một lần cho mỗi thay đổi observable của Product
  aggregate. Mọi catalog event Product/SKU mang revision này và có Kafka key `product_id`.
- `categories.category_revision` tăng đúng một lần cho mỗi thay đổi observable của
  Category hoặc mapping category–attribute; Category event dùng revision này làm ordering
  guard, không dùng `updated_at` để ghi đè event cũ/trùng.
- `product_markdown_projection.source_revision` do Promotion cấp, tăng đơn điệu theo
  Product; event cũ/trùng không được ghi đè projection mới hơn.

## 3. Schema vật lý

### 3.1 Catalog configuration

| Bảng | Cột/chức năng chính | Constraint |
|---|---|---|
| `categories` | `category_id`, `parent_id`, `name`, `slug`, `category_revision`, timestamps | `slug` unique; self-FK `ON DELETE RESTRICT` |
| `attributes` | `attribute_id`, `code`, `name`, `value_type`, timestamps | `code` unique; `TEXT`, `NUMBER`, `BOOLEAN`, `SELECT` |
| `attribute_options` | Option của attribute `SELECT` | Unique `(attribute_id, code)` |
| `category_attributes` | Mapping category–attribute | PK `(category_id, attribute_id)`, `is_required`, `is_variant` |

Application cấm category cycle, chỉ cho gán Product vào category lá, và chỉ xóa category
khi không còn category con lẫn Product. `path` và `is_leaf` là giá trị dẫn xuất, không lưu.

### 3.2 Product aggregate

| Bảng | Cột/chức năng chính | Constraint |
|---|---|---|
| `products` | Product root, category, nội dung, `status`, `catalog_revision`, timestamps | `slug` unique, CHECK kebab-case chữ thường `^[a-z0-9]+(-[a-z0-9]+)*$` (PRODUCT-BR-013); status `DRAFT`/`ACTIVE`/`HIDDEN` |
| `product_skus` | SKU, `base_price_vnd`, `selling_price_vnd`, saleability admin | `sku_code` unique; status `ACTIVE`/`INACTIVE`; selling trong `[0, base]` |
| `product_media` | Media Product hoặc SKU | Composite FK bảo đảm SKU thuộc đúng Product |

Mỗi Product có ít nhất một SKU. Command tạo Product insert Product/SKU trong cùng
transaction; application từ chối xóa SKU cuối cùng. Admin chỉ sửa `base_price_vnd`;
Product tính `selling_price_vnd` khi base price hoặc markdown thay đổi.

`thumbnail_storage_key` không là cột persist riêng. Khi dựng SKU snapshot, Product
resolve từ `product_media`: chọn `IMAGE` của SKU có `sort_order` nhỏ nhất, fallback
sang `IMAGE` Product-level có `sort_order` nhỏ nhất, còn lại là `null`. Vì rule này
dựa trên ownership catalog, downstream không được tự derive từ `media[]`. Cùng giá
trị đó cũng đi kèm mọi response SKU của HTTP API và của export nội bộ, nên full
reindex không lệch so với luồng event.

### 3.3 Markdown projection

`product_markdown_projection` có một row cho mỗi Product và luôn giữ
`source_revision`, trạng thái `ACTIVE`/`INACTIVE`, `inactive_reason` và `received_at`.
`ACTIVE` bắt buộc logical `promotion_rule_id`, cấu hình giảm giá và khoảng hiệu lực;
`INACTIVE` là tombstone nên các field rule/discount/interval đều null. Row tombstone được
giữ để replay event cũ không kích hoạt giá cũ.

Khi nhận ACTIVE, Product lock Product, chỉ upsert nếu `source_revision` mới hơn,
reprice tất cả SKU và ghi outbox trong cùng transaction. Khi nhận INACTIVE, projection
advance revision, clear rule configuration và `selling_price_vnd` trở về `base_price_vnd`.
Promotion là owner duy nhất của schedule: Product không có safety scheduler. Consumer có
apply-time expiry guard; event `ACTIVE` đến sau `ends_at` vẫn advance `source_revision`
nhưng materialize thành `INACTIVE/EXPIRED`, nên không làm giá cũ có hiệu lực lại. Consumer
lag/DLQ cần được Promotion/operations khắc phục bằng replay snapshot mới nhất.

### 3.4 Transactional outbox

`product_outbox_events` lưu `event_id`, `event_type`, `aggregate_type`, `aggregate_id`,
`partition_key`, `payload`, `created_at`. Event Product/SKU đều dùng
`aggregate_type = Product`, `aggregate_id = product_id`, `partition_key = product_id`.
Event Category dùng Category/category ID và `category_revision`. Không đặt FK từ outbox
tới aggregate để tombstone còn tồn tại sau hard delete.

## 4. Indexes và toàn vẹn

- Unique: `categories.slug`, `attributes.code`, `(attribute_options.attribute_id, code)`,
  `products.slug`, `product_skus.sku_code`; partial unique markdown
  `promotion_rule_id WHERE promotion_rule_id IS NOT NULL`.
- Index: `categories(parent_id)`, `products(category_id, status)`,
  `product_skus(product_id)`, media sort indexes, outbox `(created_at)` và
  `(partition_key, created_at)`.
- Không thêm JSONB index khi chưa có query thực tế chứng minh nhu cầu.

## 5. Transaction, outbox và lifecycle

Mỗi command Product: validate → lock `products` → lock SKU liên quan → mutate → tăng
`catalog_revision` một lần → build snapshot event → insert outbox → commit. Markdown
consumer chỉ apply snapshot nếu `source_revision` mới hơn projection hiện có.

Không có inbox/idempotency table. Inbound markdown event là state snapshot nên idempotent
qua `source_revision`; outbound event có `event_id` ổn định để consumer dedupe. Không áp
dụng mô hình này cho command tích lũy trong tương lai nếu chưa có idempotency storage riêng.

Application insert outbox → PostgreSQL WAL → Debezium → Kafka. Product không có retention
worker; cho tới khi Platform/DB lifecycle có policy được phê duyệt, row outbox được giữ lại.
Một policy tương lai không được xóa row trong thời gian recovery đã cam kết cho Debezium/
downstream và phải được vận hành ngoài Product application.

Export nội bộ dùng cho Search tạo replay boundary trước scan: Category trả offsets của
`t.category.events`, Product trả offsets của `t.product.events`. Search import toàn bộ
snapshot rồi replay inclusive; snapshot và event dùng cùng `category_revision` hoặc
`catalog_revision` nên overlap/duplicate là no-op. Boundary này không làm PostgreSQL và
Kafka thành một transaction; publisher lag vẫn phải được quan sát trước cutover.

Hard delete Product cascade SKU/media/markdown projection. Command chụp `sku_ids`, tính
revision tombstone kế tiếp và ghi tombstone trước khi delete. Search xóa document từ
tombstone và không tạo document mới chỉ từ stock event. Inventory phải consume tombstone
hoặc có guard reserve tương đương để từ chối SKU đã xóa.

Category rename, đổi slug hoặc đổi `parent_id` làm path dẫn xuất của cả subtree thay đổi.
Product ghi category snapshot cùng `CATALOG_REINDEX_REQUESTED` trong một transaction
outbox. Search coalesce request và full reindex từ export category/Product; Product không
fan-out event theo mọi descendant hay Product trong transaction admin.

## 6. Security và privacy

Product không chứa PII khách hàng. Outbox chứa snapshot catalog nên quyền đọc outbox tương
đương quyền đọc dữ liệu Product. Credential PostgreSQL/Kafka lấy từ secret manager và
không xuất hiện trong tài liệu, event hay log.
