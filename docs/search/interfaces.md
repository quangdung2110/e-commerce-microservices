---
doc_id: search-interfaces
service: search
scope: current
status: proposed
owners:
  - Search Service team
evidence: []
verified_against_commit: null
---

# Search Service — Giao diện tích hợp

> File này giải thích semantics. Search **chưa có contract machine-readable**:
> chưa có OpenAPI trong `contracts/http/search/` và chưa có AsyncAPI cho các topic
> tiêu thụ. Cho tới khi có, file này là contract duy nhất và mọi thay đổi interface
> phải cập nhật ở đây.

## 1. Contract index

| Interaction | Direction | Protocol | Provider | Consumer | Contract | Status |
|---|---|---|---|---|---|---|
| Search, suggest, browse, facet | Inbound | HTTP | Search | Client qua Gateway | Cần tạo: `contracts/http/search/v1/openapi.yaml` | proposed |
| Full reindex admin | Inbound | HTTP | Search | Admin qua Gateway | Cần tạo: `contracts/http/search/internal/v1/openapi.yaml` | proposed |
| Catalog và tombstone | Inbound | Kafka | Product | Search | [AsyncAPI Product v1](../../contracts/events/product/v1/asyncapi.yaml) | proposed |
| Category | Inbound | Kafka | Product | Search | [AsyncAPI Product v1](../../contracts/events/product/v1/asyncapi.yaml) | proposed |
| Tồn kho | Inbound | Kafka | Inventory | Search | [AsyncAPI Inventory v1](../../contracts/events/inventory/v1/asyncapi.yaml) | proposed |
| Rating aggregate | Inbound | Kafka | Review | Search | Cần tạo: AsyncAPI review v1 | proposed |
| Catalog export | Outbound | HTTP nội bộ | Product | Search | [Product interfaces](../product/interfaces.md) | proposed |
| Stock snapshot export | Outbound | HTTP nội bộ | Inventory | Search | [Inventory internal OpenAPI v1](../../contracts/http/inventory/internal/v1/openapi.yaml) | proposed |

Search **không phát event**. Không có mục “event phát ra” trong tài liệu này là
một khẳng định contract, không phải thiếu sót.

## 2. Quy ước chung cho HTTP

- Public API: `/api/v1/search/...`. Internal API: `/internal/v1/search/...`.
  Breaking change đi kèm version path mới chạy song song.
- Gateway xác thực JWT và forward claim đã verify. Search không tự verify chữ ký
  và không nhận identity header đến thẳng từ client.
- Search dùng claim cho logging/correlation; chỉ internal API kiểm tra
  `role = ADMIN`.
- Mọi response lỗi dùng một hình dạng chung: `error_code`, `message`, `details`,
  `request_id`. Danh sách `error_code` và mapping HTTP nằm ở
  [Domain §8](./domain.md#8-domain-errors).
- Mọi response mang `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
  `Referrer-Policy: no-referrer` và HSTS. CORS hiện mở cho mọi origin ở môi trường
  phát triển và **phải** được siết theo domain frontend trước production.

## 3. HTTP API

### 3.1 `GET /api/v1/search/products`

Truy vấn cốt lõi: trang kết quả tìm kiếm và mọi trang danh sách sản phẩm.

- **Caller:** buyer qua Gateway; không yêu cầu quyền nghiệp vụ.
- **Tham số:** `q`, `category`, `filters[<code>]`, `price_min`, `price_max`,
  `in_stock` (mặc định `true`), `has_promotion`, `sort`, `cursor`, `size`
  (mặc định 20, tối đa 100).
- **Sort:** `relevance` (mặc định), `price_asc`, `price_desc`, `newest`, `rating`.
  Mọi sort kết thúc bằng tie-break `sku_id asc` (`SEARCH-BR-006`).
  Range và sort theo giá dùng `selling_price_vnd`.
- **Precondition:** không có. Kết quả rỗng là kết quả hợp lệ.
- **Pagination:** cursor-based trên `search_after`. Cursor là base64 của sort
  values thuộc hit cuối cùng, trong đó `sku_id` luôn là tie-break cuối. Cursor
  mang fingerprint của toàn bộ query/filter/sort đã chuẩn hóa (trừ `size`); dùng
  lại nó sau khi đổi bộ tham số trả `INVALID_CURSOR`. `next_cursor = null` nghĩa
  là hết dữ liệu. Không hỗ trợ nhảy tới trang bất kỳ
  ([ADR-007](./decisions/ADR-007-cursor-pagination.md)).
- **Đơn vị kết quả:** mỗi item là đúng một SKU khớp query, mang cả field
  product-level đã denormalize để UI dựng product card. Cùng một `product_id` có
  thể xuất hiện nhiều lần nếu nhiều SKU của Product cùng khớp. API không trả
  `inner_hits` và không hứa “một Product duy nhất” trên toàn bộ pagination;
  điều này cho phép `search_after` hoạt động đúng với relevance, giá và rating
  ([ADR-012](./decisions/ADR-012-sku-results-with-search-after.md)).
- **Total:** `total` là số SKU khớp chính xác. Count trong facet vẫn là DISTINCT
  `product_id`; client không được suy diễn đó là số trang hay tổng số item Product.
- **Facet:** đi kèm response, mọi count là DISTINCT `product_id`; response khai báo
  `count_semantics = DISTINCT_PRODUCT` để client không tự diễn giải.
- **Lọc bắt buộc phía server:** `is_deleted = false`, `product_status = ACTIVE`,
  `sku_status = ACTIVE`. Client không thể tắt các filter này.
- **Idempotency:** GET thuần đọc, không có side effect.
- **Giới hạn:** `size` tối đa 100; timeout truy vấn Elasticsearch 5s, vượt thì trả
  `SEARCH_TIMEOUT`.

### 3.2 `GET /api/v1/search/suggest`

Dropdown search-as-you-type.

- **Tham số:** `q` (bắt buộc, tối thiểu 2 ký tự), `limit` (mặc định 8, tối đa 20).
- Đọc index `search_suggestions` đã pre-compute; ranking theo `weight` giảm dần,
  tie-break `text.keyword` tăng dần. Không tính ranking động trong đường request.
- `q` ngắn hơn 2 ký tự trả `INVALID_QUERY_PARAMETER`.
- Timeout 2s; suggest lỗi không được làm hỏng trang tìm kiếm chính.

### 3.3 `GET /api/v1/search/categories`

Trả cây danh mục cho sidebar điều hướng.

- Đọc toàn bộ index `categories`, dựng cây trong bộ nhớ theo `parent_id`/`level`.
- `product_count` đọc trực tiếp từ document, không aggregate theo request; số này
  được job recompute làm mới nên có thể trễ so với kết quả search.
- Danh mục `is_deleted = true` không xuất hiện trong cây.

### 3.4 `GET /api/v1/search/facets/{category_id}`

Bộ lọc khả dụng cho một trang danh mục.

- Aggregation trên `products` giới hạn trong nhánh danh mục, cùng bộ filter hiển
  thị mặc định như API search để số đếm không lệch với danh sách.
- Mỗi `terms` aggregation kèm `cardinality` trên `product_id`.
- `category_id` không tồn tại trả `CATEGORY_NOT_FOUND`.

### 3.5 `POST /internal/v1/search/reindex`

Trigger full reindex bất đồng bộ.

- **Auth:** claim `role = ADMIN` do Gateway chuyển tiếp; thiếu quyền trả `FORBIDDEN`.
- **Body:** lý do và người trigger, phục vụ audit.
- **Precondition:** cluster khỏe và còn đủ dung lượng cho một index đầy đủ kèm
  replica; đang có run chưa kết thúc thì trả `REINDEX_ALREADY_RUNNING`.
- **Hành vi:** trả `202 Accepted` kèm `run_id` và `status_url` ngay lập tức; công
  việc chạy nền theo [WF-SEARCH-009](./workflows.md).
- **Idempotency:** không tự dedupe theo body; ràng buộc “chỉ một run đang chạy”
  là cơ chế chống trigger trùng.

### 3.6 `GET /internal/v1/search/reindex/{run_id}`

Đọc trạng thái một lần reindex: pha hiện tại, tiến độ build, lag catch-up, thời
điểm bắt đầu/kết thúc và thông điệp lỗi nếu có. Trả `REINDEX_RUN_NOT_FOUND` khi
`run_id` không tồn tại.

### 3.7 Health

`GET /actuator/health/liveness` phản ánh tiến trình còn sống.
`GET /actuator/health/readiness` chỉ `UP` khi kết nối Elasticsearch dùng được và
consumer đã subscribe. Chi tiết ở [Operations §2](./operations.md#2-health-endpoints).

## 4. Events tiêu thụ

Năm topic, một consumer group chính `search-indexer`. Group tạm
`search-reindex-{run_id}` chỉ tồn tại trong pha catch-up của một lần reindex.

Quy tắc chung cho mọi topic:

- Event có `type` ngoài allowlist thì ignore, ACK và ghi metric
  `search_event_ignored_total{topic,event_type}`. Search chia sẻ topic với consumer
  khác nên đây là hành vi bình thường, không phải lỗi.
- Field lạ trong payload được bỏ qua ở tầng deserialize; field bắt buộc thiếu là
  contract violation, ghi metric và bỏ qua event.
- Không có DLQ ([ADR-005](./decisions/ADR-005-no-dead-letter-queue.md)): lỗi tạm
  thời thì không commit offset để Kafka gửi lại; lỗi không tự hồi phục thì ghi log,
  ghi metric, bỏ qua và ACK.
- Offset chỉ commit khi mọi record trong batch đã apply hoặc đã được phân loại là
  lỗi không tự hồi phục.

### 4.1 `t.product.events` — catalog

Producer: Product, qua outbox và Debezium, at-least-once. Contract đích là
CloudEvents 1.0 trong `contracts/events/product/v1`; Kafka key `product_id`,
`id` là event id, `sequence = catalog_revision`, snapshot nằm trong
`data.product` và `data.sku`. `data.sku.thumbnail_storage_key` luôn hiện diện: Product
đã resolve ảnh SKU trước, rồi ảnh Product; `null` nghĩa là Search phải trả placeholder.

| Event | Hành động của Search | Ghi chú |
|---|---|---|
| `com.ecommerce.catalog.sku-upserted.v1` | Upsert đúng một SKU snapshot | `data.product` + `data.sku`; không chạm stock/rating |
| `com.ecommerce.catalog.sku-deleted.v1` | Tombstone đúng một SKU | Không tạo document mới |
| `com.ecommerce.catalog.product-deleted.v1` | Tombstone toàn bộ `data.sku_ids` | Không tạo document mới |

Idempotency và ordering: guard `catalog_revision` (xem [Data §5](./data.md#5-ordering-guard)).
Duplicate và event cũ bị bỏ qua trong script, không cần dedupe store.

### 4.2 `t.category.events` — danh mục

Producer: Product. Key `category_id`; contract đích là CloudEvents Category v1 với
`sequence = category_revision`. `category-upserted` mang `path` và `is_leaf` đã
được Product dẫn xuất; Search materialize `ancestor_ids`, `ancestor_slugs`, level
và node category. `category-deleted` tombstone node. Search không `_update_by_query`
theo path và không tự dựng lại tree ([ADR-013](./decisions/ADR-013-product-category-snapshots.md)).

### 4.3 `t.catalog.control.events` — category reindex control

Product phát `com.ecommerce.catalog.reindex-requested.v1` khi rename, đổi slug hoặc
đổi parent category. Search coalesce các request, export category trước Product rồi
chạy full reindex/catch-up; event control không cập nhật trực tiếp SKU document.

### 4.4 `t.inventory.stock.events.v1` — tồn kho

Contract đích là [Inventory AsyncAPI v1](../../contracts/events/inventory/v1/asyncapi.yaml):
CloudEvents `com.ecommerce.inventory.stock-snapshot.v1`, key `sku_id`, `sequence =
stock_revision`, và payload `product_id`, `sku_id`, `available_quantity`,
`stock_revision`, `observed_at`. Search chỉ apply revision lớn hơn. `t.inventory.events`
và `INVENTORY_STOCK_CHANGED` là adapter legacy compatibility, không có ordering/recovery
guarantee của vNext và không là input mặc định sau rollout.

Event này **không được tạo document**. Document chưa tồn tại hoặc đang tombstone
thì bỏ qua và ghi metric — đó là trạng thái bình thường khi SKU vừa bị xóa hoặc
chưa kịp được index ([`SEARCH-INV-005`](./README.md#7-critical-invariants)).
Consumer phải kiểm tra cặp `product_id` + `sku_id` để chặn drift.

### 4.5 `t.review.events` — rating

Producer: Review. Key `product_id`. Search chỉ xử lý `REVIEW_METRICS_UPDATED` và
chỉ đọc `product_id`, điểm trung bình, tổng số review cùng timestamp. Áp dụng bằng
`_update_by_query` theo `product_id` với `conflicts=proceed` và throttle, guard
`review_version`. Không upsert.

## 5. Contract nội bộ Search gọi ra

Full reindex đọc `GET /internal/v1/categories/export` trước, rồi
`GET /internal/v1/products/export` của Product và
[`GET /internal/v1/inventory/stock-snapshots`](../../contracts/http/inventory/internal/v1/openapi.yaml)
để backfill catalog/stock. Search replay stock topic từ `replay_from_offsets` sau import.
Không có lời gọi đồng bộ nào khác ra ngoài trong hot path.

## 6. Compatibility

- Thêm field optional vào event là thay đổi tương thích: Search bỏ qua field lạ.
- Xóa field, đổi ngữ nghĩa field, đổi Kafka key hoặc đổi ý nghĩa của
  `catalog_revision` là breaking change và phải phối hợp với Search trước khi
  rollout.
- Thêm `event_type` mới vào topic đang dùng là tương thích: Search ignore và ACK.
- Với HTTP, thêm tham số optional và field response mới là tương thích; đổi hình
  dạng response, đổi mã lỗi hoặc đổi ngữ nghĩa cursor là breaking và cần
  `/api/v2`.
- Cursor không phải contract ổn định giữa các version: client phải coi nó là chuỗi
  đục và không được tự sinh.
- Trước khi bất kỳ interaction nào đạt `implemented`: phải có OpenAPI/AsyncAPI
  tương ứng trong `contracts/`, contract test giữa Search và producer, và kiểm tra
  breaking change trong CI.
