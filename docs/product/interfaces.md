---
doc_id: product-interfaces
service: product
scope: current
status: proposed
owners:
  - Product Service team
evidence:
  - contracts/grpc/ecommerce/product/v1/product_service.proto
verified_against_commit: null
---

# Product Service — Interfaces

> File này giải thích semantics của từng giao diện.
> [`product_service.proto`](../../contracts/grpc/ecommerce/product/v1/product_service.proto)
> là nguồn chuẩn cho gRPC. HTTP và Kafka event **chưa có** contract
> machine-readable; cho tới khi có, mô tả ở đây là contract gần nhất và mọi thay đổi
> phải được phản ánh vào cả hai phía.

## 1. Contract index

| Interaction | Direction | Protocol | Provider | Consumers | Contract | Status |
|---|---|---|---|---|---|---|
| Product & category admin API | Inbound | HTTP | Product | Admin client qua Gateway | Cần tạo: OpenAPI | proposed |
| Internal snapshot/export API | Inbound | HTTP | Product | Search | Cần tạo: OpenAPI | proposed |
| SKU point-read | Inbound | gRPC | Product | Cart, Order, Checkout, Promotion | [product_service.proto](../../contracts/grpc/ecommerce/product/v1/product_service.proto) | proposed |
| Product & SKU events | Outbound | Kafka | Product | Search, Notification, Promotion, Product (self) | Cần tạo: AsyncAPI | proposed |
| Category events | Outbound | Kafka | Product | Search, Product (self) | Cần tạo: AsyncAPI | proposed |
| Markdown events | Inbound | Kafka | Promotion | Product | Cần tạo: AsyncAPI | legacy_documented |
| Inventory stock events | Inbound | Kafka | Inventory | Product | Cần tạo: AsyncAPI | legacy_documented |

## 2. HTTP APIs

Base path: `/api/v1/products`, `/api/v1/categories`, `/api/v1/internal/products`.
Toàn bộ API quản trị yêu cầu JWT role `ADMIN` do Gateway xác thực; internal API dùng
service-to-service authentication. Mọi ghi đều đồng bộ, không nhận idempotency key từ
client: tính idempotent được bảo đảm bằng ràng buộc dữ liệu (`sku_code` unique,
guard FSM) chứ không bằng replay cache — đây là giới hạn đã biết, xem
[Known gaps](./README.md#9-known-gaps).

| Nhóm | Endpoint | Mục đích | Precondition | Hậu quả |
|---|---|---|---|---|
| Product write | `POST /api/v1/products` | Tạo product `DRAFT` cùng SKU và media ban đầu | Category lá hợp lệ, đủ thuộc tính bắt buộc, mọi `original_price > 0` | Ghi DB + outbox `PRODUCT_CREATED` |
| Product write | `PUT /api/v1/products/{id}` | Cập nhật thông tin cấp product | Product tồn tại, chưa xóa mềm | Ghi DB + outbox `PRODUCT_UPDATED`, refresh cache in-band |
| Product write | `DELETE /api/v1/products/{id}` | Xóa mềm product | Trạng thái `DRAFT` hoặc `HIDDEN` | Ghi DB + outbox `PRODUCT_DELETED`, dọn markdown và cache |
| Product write | `PATCH /api/v1/products/{id}/status` | Chuyển FSM `SUBMIT`/`HIDE`/`SHOW` | Guard tại [Domain](./domain.md#6-state-machines) | Ghi DB + outbox `PRODUCT_STATUS_CHANGED`, refresh cache in-band |
| SKU write | `POST /api/v1/products/{id}/skus` | Thêm SKU vào product đã có | `sku_code` chưa tồn tại, `original_price > 0` | Ghi DB + outbox `SKU_CREATED`, refresh cache in-band |
| SKU write | `PUT /api/v1/products/{id}/skus/{sku_id}` | Partial update SKU | Payload có ít nhất một field; SKU thuộc product | Ghi DB + outbox `SKU_UPDATED`, tính lại markdown nếu đổi giá |
| SKU write | `DELETE /api/v1/products/{id}/skus/{sku_id}` | Xóa mềm SKU | SKU thuộc product | Ghi DB + outbox `SKU_DELETED`, dọn markdown và cache |
| Product read | `GET /api/v1/products`, `GET /api/v1/products/{id}` | Danh sách có phân trang/lọc và chi tiết cho admin | — | Chỉ đọc PostgreSQL, không đọc cache point-read |
| Category write | `POST`/`PUT`/`DELETE /api/v1/categories[/{id}]` | Quản trị danh mục | Không tạo vòng lặp cha-con; xóa chỉ khi không còn product `ACTIVE` | Ghi DB + outbox `CATEGORY_*` |
| Category read | `GET /api/v1/categories`, `/tree`, `/{id}` | Danh sách phẳng, cây phân cấp và chi tiết | — | Chỉ đọc |
| Internal | `GET /api/v1/internal/products/categories/tree` | Snapshot cây danh mục cho Search | — | Chỉ đọc, không side effect |
| Internal | `GET /api/v1/internal/products/export` | Export phân trang cho full reindex, hỗ trợ `updated_since` | Sắp xếp ổn định `product_id ASC, sku_id ASC` | Chỉ đọc, an toàn khi replay |
| Internal | `GET /api/v1/internal/products/{product_id}/snapshot` | Rebuild một document khi Search gặp `document_missing_exception` | — | Chỉ đọc |

Error semantics: mọi domain error tại [Domain](./domain.md#8-domain-errors) phải được
map nhất quán sang HTTP status; lỗi hạ tầng trả 5xx và không được lộ chi tiết nội bộ.
Giới hạn kích thước payload, số SKU tối đa mỗi request và rate limit của admin API là
`TBD`.

## 3. gRPC APIs

Service `ecommerce.product.v1.ProductService`, provider là Product Service.

| RPC | Consumer | Mục đích | Deadline khuyến nghị | Retry | Idempotency |
|---|---|---|---|---|---|
| `GetSku` | Cart, Order, Checkout, Promotion | Point-read một SKU | 500 ms | An toàn retry trong retry budget của caller | Read-only |
| `GetBatchSkus` | Cart | Point-read tối đa 100 `sku_id` đã khử trùng lặp | 1 s | An toàn retry | Read-only |

Hành vi: đọc `product:sku:{sku_id}` trước; cache miss thì lấy khóa chống stampede và
query DB rồi nạp lại cache. `GetBatchSkus` chỉ query DB cho phần miss. Chi tiết luồng
tại [WF-PRODUCT-006](./workflows.md#wf-product-006--point-read-sku-qua-grpc).

Kết quả không tìm thấy hoặc không khả dụng được trả bằng `SkuNotFound` và
`SkuUnavailable` (kèm `SkuUnavailableReason`), không dùng gRPC status code cho các
tình huống nghiệp vụ này. Caller phải bọc circuit breaker và không được suy ra giá
khi thiếu dữ liệu.

Khác biệt đã biết giữa contract và nghiệp vụ: proto hiện chỉ có một `price` (Money)
và `available_quantity`, trong khi mô hình giá của Product gồm `original_price`,
`promotional_price`, `best_price` và `has_promotion`. Cho tới khi contract được mở
rộng, consumer không có đủ dữ liệu để hiển thị giá gạch ngang. Đây là gap ở mức
contract, không phải ở mức tài liệu.

## 4. Events phát ra

Hai topic, đều được Debezium forward từ bảng outbox. Message theo chuẩn CloudEvents
với `event_id`, `event_type`, `aggregate_type`, `aggregate_id`, `partition_key`,
`correlation_id`, `timestamp` (ISO 8601 UTC), `source = product-service`,
`schema_version = 1` và `payload`.

| Topic | Partition key | Consumers |
|---|---|---|
| `t.product.events` | `product_id` với event cấp Product, `sku_id` với event cấp SKU | Search, Notification, Promotion, `product-event-handler-group` |
| `t.category.events` | `category_id` | Search, `product-event-handler-group` |

| Event | Trigger | Payload chính | Consumer dự kiến |
|---|---|---|---|
| `PRODUCT_CREATED` | Tạo product thành công | Snapshot đầy đủ product + `skus[]` + media | Search |
| `PRODUCT_UPDATED` | Cập nhật cấp product | Snapshot product-level + `sku_ids[]` | Search, Product (self, gián tiếp) |
| `PRODUCT_STATUS_CHANGED` | FSM đổi trạng thái do admin | `old_status`, `new_status`, `sku_ids[]`, `changed_by` | Search, Notification, Promotion |
| `PRODUCT_DELETED` | Xóa mềm product | `product_id`, `sku_ids[]`, `updated_at` | Search, Promotion |
| `SKU_CREATED` | Thêm SKU vào product đã tồn tại | Snapshot SKU đầy đủ kèm `product_name`, `category_id`, giá | Search, Product (self) |
| `SKU_UPDATED` | Mọi thay đổi cấp SKU: giá, trạng thái, media | Snapshot SKU đầy đủ + `changed_fields[]` + `change_source` | Search, Product (self) |
| `SKU_DELETED` | Xóa mềm SKU | `sku_id`, `product_id`, enrichment fields, `updated_at` | Search, Promotion |
| `CATEGORY_CREATED` | Tạo danh mục | Category + `path` | Search |
| `CATEGORY_UPDATED` | Đổi tên/slug/metadata | `old_name`, `new_name`, `new_path` | Search |
| `CATEGORY_DELETED` | Xóa danh mục | `category_id`, `descendant_category_ids[]` | Search, Product (self) |

Quy tắc bắt buộc cho producer:

- Event cấp product mang snapshot đầy đủ tại thời điểm commit, không phải delta từ
  client. Backend tự truy vấn `sku_ids[]` trong cùng transaction, không nhận từ FE.
- Mọi `skus[]` trong `PRODUCT_CREATED` phải có đủ `original_price`,
  `promotional_price`, `best_price`, `has_promotion` để Search không phải tự tính giá.
- `SKU_UPDATED` phải có `changed_fields[]` liệt kê đúng field đã đổi, và
  `change_source` là bắt buộc khi `sku_status` nằm trong `changed_fields`. Khi FSM đổi
  luôn trạng thái product, payload mang thêm `product_status_changed = true` và
  `new_product_status`; consumer xử lý cả hai cấp trong một event mà không cần
  `PRODUCT_STATUS_CHANGED` riêng.
- Mọi event mang `updated_at` để consumer guard out-of-order. Search dùng đúng
  `updated_at` làm guard, không dùng version number.
- `partition_key` được validate NOT NULL và NOT EMPTY trước khi insert outbox.

Delivery guarantee: outbox + CDC cho at-least-once. Consumer phải idempotent và
deduplicate theo `event_id`, đồng thời guard theo `updated_at`.

`SKU_UPDATED` là kết quả của việc hợp nhất hai event cũ `SKU_PRICE_UPDATED` và
`SKU_STATUS_CHANGED` ([ADR-006](./decisions/ADR-006-consolidated-sku-updated-event.md));
hai tên cũ không còn tồn tại ở bất kỳ đâu.

## 5. Events tiêu thụ

| Event | Producer | Topic / consumer group | Hành động | Idempotency | Lỗi |
|---|---|---|---|---|---|
| `MARKDOWN_CREATED` | Promotion | `t.promotion.markdown.events` / `product-markdown-group` | Tính và ghi `promotional_price`, upsert mapping, phát `SKU_UPDATED` | Unique `(rule_id, sku_id)`; áp lại cho cùng giá là no-op | Retry có giới hạn, sau đó DLQ |
| `MARKDOWN_UPDATED` | Promotion | như trên | Query `original_price` live rồi tính lại giá | Như trên | Như trên |
| `MARKDOWN_ENDED` | Promotion | như trên | Reset `promotional_price = NULL`, xóa mapping và marker | Không còn mapping thì no-op và ack | Như trên |
| `INVENTORY_STOCK_CHANGED` | Inventory | `t.inventory.events` / `product-inventory-fast-sync-group` | Phase 1 cập nhật cache bằng Lua CAS và đăng ký pending sync | Guard theo `last_inventory_sync_at` | Ack chỉ sau khi Redis ghi thành công |
| `SKU_CREATED` | Product (self) | `t.product.events` / `product-event-handler-group` | Refresh `product:sku:{sku_id}` | Guard theo `created_at` trong cache | Retry transient, sau đó DLQ |
| `SKU_UPDATED` | Product (self) | như trên | Refresh `product:sku:{sku_id}` cho mọi nguồn thay đổi | Guard theo `changed_fields` và mốc thời gian tương ứng | như trên |
| `CATEGORY_DELETED` | Product (self) | `t.category.events` / `product-event-handler-group` | Chuyển product thuộc danh mục và subtree sang `HIDDEN` trong DB | Update tới trạng thái đích là idempotent | Retry, sau đó DLQ |

Quy tắc chung cho consumer:

- Chỉ xử lý event trong allowlist của từng group; event lạ phải skip kèm metric và
  đưa sang DLQ, không im lặng bỏ qua.
- Chuẩn hóa `rule_id` về string ngay tại consumer boundary.
- Offset chỉ được commit sau khi record đã xử lý xong hoặc đã vào DLQ.
- Lỗi transient (Redis timeout, DB tạm lỗi, network) thì không commit offset và retry
  với exponential backoff kèm jitter, tối đa 5 lần.
- Nhánh `SKU_CREATED`/`SKU_UPDATED` của Event Handler chỉ được đọc DB và ghi Redis.
  Ghi DB trong nhánh này sẽ sinh outbox mới và tạo vòng lặp self-consume vô tận.
- Nhánh `CATEGORY_DELETED` chỉ đọc và ghi DB, không chạm Redis.

Product Service không có bảng inbox: deduplication dựa hoàn toàn vào staleness guard
và tính idempotent của thao tác. Hệ quả là một event bị xử lý lại sẽ ghi lại cùng
giá trị, chứ không bị chặn ở tầng lưu trữ.

## 6. Compatibility

- Thay đổi schema event chỉ theo hướng additive: thêm optional field, không xóa
  field, không đổi ngữ nghĩa của field đang tồn tại.
- Breaking change phải tạo `event_type` hoặc version mới và rollout song song cho tới
  khi mọi consumer đã chuyển.
- Consumer không được throw khi gặp giá trị lạ trong `changed_fields` hoặc
  `change_source`; giá trị chưa biết phải được bỏ qua an toàn.
- `schema_version` hiện cố định là `1` cho mọi message.
- Contract test liên service cần bao phủ tối thiểu: `PRODUCT_CREATED`,
  `PRODUCT_UPDATED`, `PRODUCT_STATUS_CHANGED`, `SKU_CREATED`, `SKU_UPDATED`,
  `SKU_DELETED`, `CATEGORY_UPDATED`, `CATEGORY_DELETED`, `INVENTORY_STOCK_CHANGED`.
  Hiện chưa có test nào tồn tại.
- Điều kiện để chuyển trạng thái contract sang `implemented`: có OpenAPI cho HTTP, có
  AsyncAPI/JSON Schema cho event, có lint và breaking-change check trong CI, và proto
  đã được mở rộng đủ mô hình giá.
