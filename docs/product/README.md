---
doc_id: product-overview
service: product
scope: current
status: proposed
owners:
  - Product Service team
evidence:
  - docs/product/scripts/inventory_fast_sync.lua
verified_against_commit: null
---

# Product Service — Service Overview

> Điểm bắt đầu để hiểu Product Service. Tài liệu này được refactor từ bộ tài liệu
> thiết kế cũ trong `docs/product`; nội dung chưa được đối chiếu với implementation
> nên trạng thái vẫn là `proposed`.

## 1. Service status

- Trạng thái service: `running` — service đã được triển khai thành code tại
  `java/services/product-service`.
- Runtime: Java 21, Spring Boot 3.x, Spring Statemachine, Spring Data JPA,
  Spring Kafka, gRPC; PostgreSQL làm write store, Redis (RedisJSON) làm cache,
  Debezium CDC làm cơ chế phát event.
- Trạng thái tài liệu: `proposed`. Không mục nào trong bộ tài liệu này được đánh
  dấu `verified`; mọi tham chiếu tới code là ứng viên evidence cần xác minh.
- Contract adoption: `pilot`. Chỉ gRPC point-read đã có contract machine-readable
  ([`product_service.proto`](../../contracts/grpc/ecommerce/product/v1/product_service.proto));
  HTTP admin API và Kafka event chưa có OpenAPI/AsyncAPI.

## 2. Mục tiêu

Product Service là hệ thống quản trị danh mục hàng hóa của nền tảng. Service giữ
định nghĩa chuẩn của sản phẩm, biến thể (SKU), danh mục và thuộc tính; quyết định
sản phẩm nào được phép bán; và tính sẵn giá hiển thị (`best_price`) để mọi service
downstream phản chiếu thay vì tự tính lại.

Kết quả nghiệp vụ: nền tảng có một nguồn dữ liệu sản phẩm nhất quán cho Search
(index), Cart/Checkout/Order (point-read khi mua hàng) và Promotion (áp markdown),
với độ trễ đồng bộ có giới hạn và có thể quan sát được.

## 3. Boundary

Product Service chịu trách nhiệm:

- Vòng đời sản phẩm và SKU: tạo, cập nhật, chuyển trạng thái, xóa mềm.
- Cây danh mục, thuộc tính và ràng buộc validation theo danh mục.
- Tính và lưu `promotional_price`/`best_price` từ markdown rule nhận được.
- Phát event thay đổi sản phẩm và danh mục cho toàn hệ thống.
- Cung cấp point-read SKU cho các service nội bộ.

Product Service **không** chịu trách nhiệm:

- Tồn kho thật, giữ hàng và trừ kho — thuộc Inventory Service.
- Định nghĩa và vòng đời markdown rule, voucher, flash sale — thuộc Promotion Service.
- Tìm kiếm, lọc, facet và ranking cho end-user — thuộc Search Service (read model).
- Hiển thị giỏ hàng, tính tổng đơn và thanh toán — thuộc Cart/Checkout/Order.

Transaction boundary chỉ bao gồm dữ liệu do Product sở hữu. Không service nào được
đọc hoặc ghi trực tiếp database, và cũng không đọc trực tiếp Redis, của Product.

## 4. Data ownership

| Kho lưu trữ | Dữ liệu | Nguồn chân lý |
|---|---|---|
| PostgreSQL | Product, SKU, media, category, attribute, markdown applied, outbox | Product Service |
| Redis | Point-read cache, markdown marker, pending sync queue, lock | Trạng thái phái sinh, TTL-bound |
| Kafka | Event thay đổi sản phẩm và danh mục | Không phải database nguồn |

Hai nhóm dữ liệu nằm trong database Product nhưng **không** do Product sở hữu:

- `product_skus.display_quantity` là bản sao tồn kho; source of truth ở Inventory Service.
- `product_markdown_applied` là bản chiếu của markdown rule; source of truth ở Promotion Service.

Chi tiết tại [Data Design](./data.md).

## 5. Capabilities

- Quản trị sản phẩm: tạo bản nháp, cập nhật, thêm/sửa/xóa mềm SKU, quản lý media.
- Quản trị danh mục và thuộc tính, kể cả cascade khi danh mục bị xóa.
- Điều khiển vòng đời sản phẩm qua state machine `DRAFT` → `ACTIVE` → `HIDDEN`.
- Tính giá markdown và duy trì `best_price` khi rule bắt đầu, đổi hoặc kết thúc.
- Đồng bộ tồn kho hiển thị từ Inventory và tự ẩn/hiện sản phẩm theo tồn kho.
- Point-read SKU đơn và batch cho các service nội bộ.
- Phát event CQRS cho Search, Notification và Promotion qua outbox + Debezium CDC.
- Cung cấp snapshot/export nội bộ để Search reindex và replay.

## 6. Dependencies

| Direction | Service/System | Protocol | Purpose | Required/Optional | Contract |
|---|---|---|---|---|---|
| Inbound | Admin client qua API Gateway | HTTP | Quản trị sản phẩm và danh mục | Required | [Interfaces](./interfaces.md) |
| Inbound | Cart, Order, Checkout, Promotion | gRPC | Point-read SKU | Required | [product_service.proto](../../contracts/grpc/ecommerce/product/v1/product_service.proto) |
| Inbound | Search Service | HTTP internal | Snapshot, export, category tree cho reindex | Required for reindex | [Interfaces](./interfaces.md) |
| Inbound | Promotion Service | Kafka | Markdown rule lifecycle | Required for pricing | [Interfaces](./interfaces.md) |
| Inbound | Inventory Service | Kafka | Thay đổi tồn kho | Required for availability | [Interfaces](./interfaces.md) |
| Outbound | PostgreSQL | Database | Write store và outbox | Required | [Data](./data.md) |
| Outbound | Redis | Cache | Point-read cache, pending queue, lock | Required | [Data](./data.md) |
| Outbound | Kafka + Debezium CDC | Events | Phát event sản phẩm và danh mục | Required | [Interfaces](./interfaces.md) |
| Outbound | Search, Notification, Promotion | Kafka consumer | Đồng bộ read model và phản ứng nghiệp vụ | Optional by workflow | [Interfaces](./interfaces.md) |

## 7. Critical invariants

- `PRODUCT-INV-001`: Product là system of record cho product, SKU và price snapshot;
  `display_quantity` chỉ dùng để hiển thị, không được dùng để giữ hoặc trừ kho.
- `PRODUCT-INV-002`: Mọi thay đổi dữ liệu `products`, `product_skus` hoặc `categories`
  phải ghi outbox record trong cùng transaction; không phát event ngoài transaction.
- `PRODUCT-INV-003`: `partition_key` của outbox không được rỗng; event cấp Product
  dùng `product_id`, event cấp SKU dùng `sku_id`, event cấp Category dùng `category_id`.
- `PRODUCT-INV-004`: `product:sku:{sku_id}` chỉ có một writer bất đồng bộ duy nhất là
  Event Handler Worker; mọi lần ghi cache phải qua staleness guard.
- `PRODUCT-INV-005`: `AUTO_REACTIVATE_IN_STOCK` không bao giờ được chạy khi
  `admin_hidden = true` hoặc `is_deleted = true`.
- `PRODUCT-INV-006`: Mọi transaction chạm nhiều bảng phải lock theo thứ tự
  Product → SKU để tránh deadlock AB-BA.
- `PRODUCT-INV-007`: `best_price = LEAST(original_price, COALESCE(promotional_price, original_price))`
  do Product tính; admin không được set `promotional_price` thủ công.
- `PRODUCT-INV-008`: Event đến trễ không được ghi đè dữ liệu mới hơn; mọi consumer và
  mọi lần ghi cache phải so sánh mốc thời gian trước khi apply.

Giải thích và nơi enforcement tại [Domain Model](./domain.md).

## 8. Document map

| Document | Nội dung | Khi nào cần đọc |
|---|---|---|
| [Architecture](./architecture.md) | Component, processing, consistency và concurrency model | Thay đổi cấu trúc service |
| [Domain](./domain.md) | Ngôn ngữ nghiệp vụ, rule, state machine, error | Thay đổi business behavior |
| [Interfaces](./interfaces.md) | HTTP, gRPC và event semantics | Tích hợp với service |
| [Workflows](./workflows.md) | Luồng end-to-end và failure path | Sửa một use case |
| [Data](./data.md) | Ownership, schema, cache, outbox | Sửa schema hoặc dữ liệu |
| [Operations](./operations.md) | SLO, worker, failure mode, runbook | Deploy hoặc xử lý sự cố |
| [Development](./development.md) | Chạy, build, test local và Definition of Done | Phát triển service |
| [Decisions](./decisions/README.md) | Architecture Decision Records | Đánh giá trade-off |

## 9. Known gaps

Contract:

- HTTP admin/internal API chưa có OpenAPI; Kafka event chưa có AsyncAPI hoặc JSON
  Schema. Bộ tài liệu này đang là mô tả gần nhất với một contract.
- `product_service.proto` hiện chỉ có một field `price` (Money) và
  `available_quantity`, trong khi nghiệp vụ yêu cầu point-read trả `original_price`,
  `promotional_price`, `best_price` và `has_promotion`. Khác biệt này phải được giải
  quyết ở contract trước khi đánh dấu `implemented`.
- Tên RPC trong tài liệu cũ (`GetProductSku`, `GetBatchProductSkus`) không khớp
  contract (`GetSku`, `GetBatchSkus`). Bộ tài liệu này dùng tên theo contract.

Quyết định được chốt trong refactor này và cần xác minh lại với implementation:

- Quy ước timestamp trong cache: `last_inventory_sync_at` là epoch millis,
  `price_updated_at` là ISO 8601 — chốt tại [Data](./data.md#7-caching). Tài liệu cũ
  mô tả mâu thuẫn giữa hai định dạng.
- Tên event chuẩn là `PRODUCT_STATUS_CHANGED`; `PRODUCT_STATE_CHANGED` bị loại bỏ.
- Consumer group chuẩn là `product-event-handler-group`;
  `product-invalidation-group` bị loại bỏ.
- Phase 2 của Inventory Sync được mô tả là conditional update theo mốc
  `last_inventory_sync_at`, không phải optimistic locking bằng cột `version`.
- Cache `product:sku:{sku_id}` sau Phase 2 được refresh qua `SKU_UPDATED`, không phải
  qua `PRODUCT_STATUS_CHANGED` như một đoạn trong tài liệu cũ mô tả.

Dữ liệu và vận hành:

- `categories.path` được dùng trong event và internal API nhưng chưa có trong mô tả
  bảng `categories`; cần chốt là cột vật lý hay giá trị tính lúc đọc.
- `product_status_history` được nhắc trong tài liệu chọn công nghệ cũ nhưng không tồn
  tại trong thiết kế dữ liệu; hiện coi như không có bảng này.
- SLO, alert threshold, schedule và giá trị cấu hình vận hành đang là `TBD`.
- Chưa có contract test, provider/consumer test hoặc CI compatibility check cho bất
  kỳ interface nào của service.
