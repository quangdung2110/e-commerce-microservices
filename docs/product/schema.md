---
doc_id: product-schema
service: product
scope: current
status: accepted_design
date: 2026-09-01
owners:
  - Product Service team
---

# Product Service — Schema

DDL để review nằm tại [schema.sql](./schema.sql). Đây là thiết kế đích; migration
Flyway phải được tạo theo hướng additive trước khi implementation chuyển sang schema này.

## 1. Ownership và boundary

| Bounded context | Source of truth | Product lưu gì? |
|---|---|---|
| Catalog, SKU, category, attribute, media, giá niêm yết | Product | Dữ liệu gốc |
| Tồn kho, availability, reservation, deduct | Inventory | Không lưu quantity, stock status hay stock timestamp |
| Markdown rule và voucher | Promotion | Một projection markdown đang hiệu lực để tính giá công bố |
| UI browse/search | Search | Không lưu trong Product |

Product không consume event Inventory và không có Redis cache/projection tồn kho.
Cart kiểm tra catalog và giá ở Product; Checkout reserve trực tiếp tại Inventory là
quyết định cuối cùng về tồn kho. Search consume catalog/price từ Product và stock UI
từ Inventory theo hai nguồn độc lập.

## 2. Quy ước chung

- ID Catalog dùng UUIDv7 do application sinh.
- Giá VND dùng `BIGINT`.
- `created_at` và `updated_at` dùng `TIMESTAMPTZ`; application đặt `updated_at` khi sửa.
- Product và SKU hard delete. Không có `is_deleted`, `admin_hidden`, `OUT_OF_STOCK`,
  `display_quantity`, `available_quantity`, `last_inventory_sync_at`, Redis cache,
  inventory worker, inbox hay idempotency table trong Product.
- `products.catalog_revision` tăng đúng một lần cho mỗi thay đổi observable của Product
  aggregate. Mọi catalog event của Product/SKU mang revision này và có Kafka key là
  `product_id`.
- `product_markdown_projection.source_revision` do Promotion cấp, tăng đơn điệu theo
  `product_id`; event cũ hoặc trùng không được ghi đè projection mới hơn.

## 3. Schema vật lý

### Catalog configuration

| Bảng | Nội dung chính |
|---|---|
| `categories` | `category_id`, `parent_id`, `name`, `slug`, timestamps. `slug` unique; self-FK `ON DELETE RESTRICT`. |
| `attributes` | `attribute_id`, `code`, `name`, `value_type`, timestamps. `code` unique; type `TEXT`, `NUMBER`, `BOOLEAN`, `SELECT`. |
| `attribute_options` | Option của attribute `SELECT`; unique `(attribute_id, code)`. |
| `category_attributes` | Mapping category–attribute, gồm `is_required`, `is_variant`. |

Application cấm cycle, chỉ cho gán Product vào category lá, và chỉ xóa category khi
không còn category con lẫn Product. `path` và `is_leaf` là giá trị dẫn xuất, không lưu.

### Product aggregate

| Bảng | Nội dung chính |
|---|---|
| `products` | Product root, category, nội dung, `status`, `catalog_revision`, timestamps. |
| `product_skus` | SKU, `base_price_vnd`, `selling_price_vnd`, saleability do admin quản lý (`ACTIVE`/`INACTIVE`). |
| `product_media` | Media cấp Product hoặc SKU; composite FK bảo đảm SKU phải thuộc đúng Product. |

Mỗi Product phải có ít nhất một SKU. Command tạo Product insert Product và SKU trong
cùng transaction; command xóa SKU cuối cùng bị từ chối ở application.

`selling_price_vnd` là giá Product công bố. Admin chỉ sửa `base_price_vnd`; Product
tính lại `selling_price_vnd` khi base price hoặc markdown thay đổi.

### Markdown projection

`product_markdown_projection` có đúng một row cho một Product: logical reference tới
`promotion_rule_id`, `source_revision`, trạng thái `ACTIVE`/`INACTIVE`, cấu hình giảm
giá, khoảng hiệu lực và `received_at`. Row được giữ lại ở `INACTIVE` để replay event cũ
không kích hoạt lại giá cũ.

Khi nhận snapshot activate/update, Product lock Product, chỉ upsert nếu
`source_revision` mới hơn, tính lại giá toàn bộ SKU và ghi outbox trong cùng transaction.
Khi deactivate, Product đổi projection sang `INACTIVE` và trả `selling_price_vnd` về
`base_price_vnd`. Một scheduled safety check chỉ dọn projection đã quá `ends_at`; nó
không thay thế schedule/outbox của Promotion.

### Transactional outbox

`product_outbox_events` lưu `event_id`, `event_type`, `aggregate_type`, `aggregate_id`,
`partition_key`, `payload`, `created_at`. Event thuộc Product aggregate, kể cả event
SKU, dùng `aggregate_type = Product`, `aggregate_id = product_id` và
`partition_key = product_id`; event Category dùng `Category` và `category_id`.
Không đặt FK từ outbox tới aggregate để tombstone còn tồn tại sau hard delete.

## 4. Indexes và toàn vẹn

- Unique: `categories.slug`, `attributes.code`, `(attribute_options.attribute_id, code)`,
  `products.slug`, `product_skus.sku_code`, `product_markdown_projection.promotion_rule_id`.
- Index: `categories(parent_id)`, `products(category_id, status)`,
  `product_skus(product_id)`, media sort indexes, markdown `(state, ends_at)`, outbox
  `(created_at)` và `(partition_key, created_at)`.
- Không thêm JSONB index khi chưa có query thực tế chứng minh nhu cầu.

## 5. Transaction, delete và concurrency

Mỗi command Product thực hiện: validate → lock `products` → lock SKU liên quan → mutate
→ tăng `catalog_revision` một lần → build snapshot event → insert outbox → commit.

`DRAFT -> ACTIVE` yêu cầu ít nhất một SKU `ACTIVE`. `ACTIVE <-> HIDDEN` là quyết định
của admin và không đọc Inventory. gRPC trả catalog-unavailable khi Product hoặc SKU
không `ACTIVE`, không trả unavailable vì stock.

Xóa SKU chỉ được phép khi Product còn SKU khác; xóa Product cascade SKU/media/projection.
Trước commit phải ghi `SKU_DELETED` hoặc `PRODUCT_DELETED` tombstone. Search xóa document
khi nhận tombstone và không upsert document mới chỉ từ stock event. Inventory phải có
contract consume tombstone hoặc kiểm tra catalog trước reserve để từ chối SKU đã xóa.

## 6. Contracts liên service

- gRPC SKU trả identity, catalog saleability, `base_price_vnd`, `selling_price_vnd`,
  `catalog_revision`, `updated_at`; không trả quantity hay stock status.
- Product event là snapshot catalog đầy đủ, mang `catalog_revision`, key `product_id`.
- Promotion gửi snapshot `PRODUCT_MARKDOWN_ACTIVATED`, `PRODUCT_MARKDOWN_UPDATED`,
  `PRODUCT_MARKDOWN_DEACTIVATED` với `product_id`, `promotion_rule_id`,
  `source_revision`, `starts_at`, `ends_at`.
- Search guard catalog bằng `catalog_revision`, stock bằng version/sequence của Inventory,
  và review bằng revision riêng; không dùng một timestamp chung cho mọi nguồn.

Machine-readable OpenAPI, AsyncAPI và proto phải được version hóa trước khi rollout
implementation; tài liệu này là nguồn semantic cho tới thời điểm đó.
