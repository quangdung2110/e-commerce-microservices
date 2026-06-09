# Search Service - Events Consumed (Updated for Extreme Scale)

**Liên kết nhanh (Related Documents):**
*   [Elasticsearch Schema (Read Store)](search_elasticsearch_schema.md)
*   [Caching Schema (Redis)](search_caching_schema.md)
*   [Workflows (API & Data Flow)](search_workflow.md)
*   [Background Workers & Jobs](search_workers.md)
*   [Events Consumed & Kafka Topics](search_events.md)
*   [Architecture Recommendations](search_architecture_recommendations.md)

**Cross-Service References:**
*   [Product Service - Events Structure (Source of Truth)](../product/product_events.md)
*   [Product Service - Workers (Outbox Producer)](../product/product_workers.md)

---

Tài liệu này định nghĩa toàn bộ luồng sự kiện mà Search Service tiêu thụ. Search Service là Read Model thuần túy, không phát sinh event nghiệp vụ mới.

> **Nguyên tắc:** Search phản chiếu dữ liệu từ Source Services theo ranh giới ownership. Product data đến từ Product Service; review metrics đến từ Review Service. Search không tự tính business logic ngoài payload contract nhận được.

> **Policy shared-topic (bắt buộc):** nếu consumer nhận event hợp lệ theo envelope nhưng `event_type` không nằm trong allowlist của consumer group đó thì phải **ignore + ACK + ghi metric** (`search_event_ignored_total{topic,event_type}`), không đẩy DLQ.

## 1. Bản Đồ Kafka Topics

| Topic Name | Consumer Group | Producer | Events trong Topic | Mô tả |
|---|---|---|---|---|
| `t.product.events` | `search-product-indexer` | Product Service | `PRODUCT_CREATED`, `PRODUCT_UPDATED`, `PRODUCT_STATE_CHANGED`, `PRODUCT_PRICE_UPDATED`, `PRODUCT_SKU_STATUS_CHANGED`, `PRODUCT_DELETED`, `PRODUCT_SKU_DELETED` | Topic chính cho Product projection |
| `t.category.events` | `search-category-indexer` | Product Service | `CATEGORY_UPDATED`, `CATEGORY_DELETED` | Topic chuyên biệt cho category projection |
| `t.review.events` | `search-review-metrics-sync` | Review Service | `REVIEW_METRICS_UPDATED` | Đồng bộ điểm rating và số lượng review theo thời gian thực |
| `t.search.metrics.events` | `search-sales-metrics-sync` | Order Service (Order Metrics Projector nội bộ) | `ORDER_PRODUCT_METRICS_UPDATED` | Đồng bộ realtime `product_sold_count` theo `product_id` |
| `t.search.retry` | `search-retry-replayer` | Search Service (internal) | retry envelopes (self-heal) | Kênh nội bộ xử lý lỗi có thể phục hồi như `document_missing_exception` |
| `t.search.dlq` | `search-dlq-processor` | Search Service (internal) | failed envelopes (parking lot) | Kênh lỗi không tự phục hồi hoặc vượt ngưỡng retry để replay có kiểm soát |

## 2. Chi Tiết Xử Lý Theo Event Type

### 2.1. PRODUCT_CREATED
**Nguồn:** [Product Events - 3.1](../product/product_events.md#31-product_created)

**Logic xử lý:**
1. Parse payload, extract Product + danh sách SKUs.
2. Nếu `skus` rỗng/null -> đánh dấu contract violation, đẩy `t.search.dlq` (không được skip im lặng vì sẽ gây drift read-model).
3. Enrichment category từ [Category Cache](search_caching_schema.md#12-category-tree-cache).
4. **Data transformation ownership (bắt buộc):** Search Worker transform `attributes_json` và `variant_attributes_json` từ raw JSON sang token arrays `searchable_attributes` / `variant_attributes`.
5. **Chuẩn hoá field trạng thái SKU:** ưu tiên đọc `skus[].sku_status`; nếu payload tương thích ngược chỉ có `skus[].status` thì map alias `status -> sku_status` trước khi index.
6. **Contract giá trong snapshot:** `skus[]` bắt buộc có `best_price` và `has_promotion`; thiếu field thì đánh dấu contract violation và đẩy `t.search.dlq` (không tự tính business pricing).
7. **Cấm dùng Index thuần tuý:** Bắt buộc sử dụng Scripted Upsert với Guard Version để chống lỗi Zombie Hole nếu event `PRODUCT_DELETED` hoặc `PRODUCT_UPDATED` đến trước. Chỉ Insert/Update nếu `event.version > current_doc.version`.

### 2.2. PRODUCT_UPDATED
**Nguồn:** [Product Events - 3.2](../product/product_events.md#32-product_updated)

**Logic xử lý:**
1. Update toàn bộ trường Product-level và SKU-level theo payload mới nhất.
2. **Chuẩn hoá field trạng thái SKU:** ưu tiên `skus[].sku_status`; nếu payload cũ chỉ có `skus[].status` thì map alias `status -> sku_status` trước khi index.
3. **Contract giá trong snapshot:** mọi phần tử `skus[]` bắt buộc có `best_price` và `has_promotion`; thiếu field thì đánh dấu contract violation và đẩy `t.search.dlq`.
4. Guard out-of-order bằng script: chỉ apply khi `event.version > current_doc.version`.
5. **Không tự tính lại giá:** Search Worker chỉ ghi đúng giá trị `original_price`, `promotional_price`, `best_price`, `has_promotion` nhận từ payload. Không có phép tính business pricing ở Search.

### 2.3. PRODUCT_STATE_CHANGED
**Nguồn:** [Product Events - 3.3](../product/product_events.md#33-product_state_changed)

**Logic xử lý:**
1. Update `product_status` cho tất cả SKU thuộc `product_id` bằng `_update_by_query`.
2. Guard bằng `version` để chặn stale update.
3. Bắt buộc throttle `_update_by_query` bằng `requests_per_second` (khuyến nghị 300-500) + `conflicts=proceed` để tránh spike CPU Elasticsearch.
4. Recompute `is_in_stock` theo trạng thái hợp lệ để bán: `is_deleted == false AND product_status == 'ACTIVE' AND sku_status == 'ACTIVE'`.

```json
POST products_write/_update_by_query?conflicts=proceed&requests_per_second=300
{
  "query": { "term": { "product_id": "prod-uuid-001" } },
  "script": {
    "source": "if (ctx._source.version != null && params.version <= ctx._source.version) { ctx.op = 'none'; return; } ctx._source.product_status = params.new_status; ctx._source.is_in_stock = (!ctx._source.is_deleted && ctx._source.product_status == 'ACTIVE' && ctx._source.sku_status == 'ACTIVE'); ctx._source.version = params.version;",
    "params": {
      "new_status": "ACTIVE",
      "version": 4
    }
  }
}
```

### 2.4. PRODUCT_PRICE_UPDATED
**Nguồn:** [Product Events - 3.4](../product/product_events.md#34-product_price_updated)

**Logic xử lý:**
1. Parse payload: `sku_id`, `original_price`, `promotional_price`, `best_price`, `has_promotion`, `version`, `event_sequence_id`.
   - `best_price` là field Product Service tính sẵn theo contract; Search chỉ ghi nhận, không tự tính business pricing.
   - `event_sequence_id` là field bắt buộc cho guard thứ tự ở cấp SKU-event.
2. Partial update document SKU.
3. **Cấm `upsert`/`doc_as_upsert` cho event partial update giá** để tránh tạo document cụt khi event đến trước `PRODUCT_CREATED`.

```json
POST products_write/_update/{sku_id}
{
  "script": {
    "source": "if (ctx._source.price_event_sequence_id != null && params.event_sequence_id <= ctx._source.price_event_sequence_id) { ctx.op = 'none'; return; } if (ctx._source.version != null && params.version < ctx._source.version) { ctx.op = 'none'; return; } ctx._source.original_price = params.original_price; ctx._source.promotional_price = params.promotional_price; ctx._source.best_price = params.best_price; ctx._source.has_promotion = params.has_promotion; ctx._source.price_event_sequence_id = params.event_sequence_id; ctx._source.version = params.version;",
    "params": {
      "original_price": 33990000,
      "promotional_price": 28891500,
      "best_price": 28891500,
      "has_promotion": true,
      "version": 4,
      "event_sequence_id": 1709420001
    }
  }
}
```

> Nếu gặp `document_missing_exception`: không được skip. Log error và đẩy event vào Retry Topic/DLQ hoặc trigger fetch full product để self-healing.
>
> Nếu payload thiếu `event_sequence_id` hoặc `best_price`, Worker phải đánh dấu contract violation và đẩy message vào DLQ/parking lot (không apply partial update mù).

### 2.5. PRODUCT_SKU_STATUS_CHANGED
**Nguồn:** [Product Events - 3.5](../product/product_events.md#35-product_sku_status_changed)

**Logic xử lý:**
1. Parse payload: `sku_id`, `new_status`, `version`.
2. Map `is_in_stock` theo công thức sellable: `(new_status == 'ACTIVE') AND (current_doc.product_status == 'ACTIVE') AND (current_doc.is_deleted == false)`.
3. `is_in_stock` trong Search không phải số lượng tồn kho realtime ở kho; kiểm tra tồn kho cứng vẫn thuộc Checkout/Inventory path.
4. Partial update chỉ các trường trạng thái + version.

```json
POST products_write/_update/{sku_id}
{
  "script": {
    "source": "if (ctx._source.version != null && params.version <= ctx._source.version) { ctx.op = 'none'; return; } ctx._source.sku_status = params.sku_status; ctx._source.is_in_stock = (params.sku_status == 'ACTIVE' && ctx._source.product_status == 'ACTIVE' && !ctx._source.is_deleted); ctx._source.version = params.version;",
    "params": {
      "sku_status": "OUT_OF_STOCK",
      "version": 8
    }
  }
}
```

### 2.6. CATEGORY_UPDATED
**Nguồn:** [Product Events - 3.7](../product/product_events.md#37-category_updated)

**Topic:** `t.category.events`

**Logic xử lý:**
1. Nhận `category_id`, `new_name`, `new_path`.
2. Chạy `_update_by_query` để update `category_name` và `category_path` cho toàn bộ product documents thuộc `category_id`.
3. Bắt buộc dùng `conflicts=proceed` + `requests_per_second` cho batch lớn; parse kết quả và retry có kiểm soát nếu có shard failure.

```json
POST products_write/_update_by_query?conflicts=proceed&requests_per_second=300
{
  "query": { "term": { "category_id": "cat-smartphone" } },
  "script": {
    "source": "ctx._source.category_name = params.new_name; ctx._source.category_path = params.new_path;",
    "params": {
      "new_name": "Điện thoại di động",
      "new_path": "dien-tu/dien-thoai-di-dong"
    }
  }
}
```

> **Kết quả `_update_by_query` phải được kiểm tra:**
> - `version_conflicts` có thể chấp nhận khi guard chặn stale events.
> - `failures` không rỗng phải được phân loại retry/dlq, không được coi là thành công toàn phần.

### 2.7. PRODUCT_DELETED / PRODUCT_SKU_DELETED
**Kafka Topic:** `t.product.events`
**Hành động Elasticsearch:** Delete / Tombstone.
*   **`PRODUCT_DELETED`**: Payload chứa mảng `sku_ids`. Worker BẮT BUỘC loop theo từng `sku_id` và gọi Bulk API để scripted-upsert tombstone cho từng SKU document (`_id = sku_id`).
*   **`PRODUCT_SKU_DELETED`**: Payload chứa **một** `sku_id` (không phải `sku_ids`). Worker chỉ update/tombstone đúng document `_id = sku_id` đó, không mở vòng lặp theo mảng.
*   `PRODUCT_SKU_DELETED` bắt buộc có `payload.version`; worker phải normalize `payload.version` thành guard version trước khi scripted-upsert tombstone.
*   Chiến lược Tombstone Zero-Downtime này chặn trường hợp document bị xóa, nhưng do network lag, event tạo cũ (hoặc edit) lại tới trễ đào mồ sống dậy SKU. Update Script sẽ bẻ gãy Event trễ bằng `event.version > ctx._source.version`.
*   Cronjob làm sạch rác (ILM/Cleanup Job) sẽ dọn dẹp các Tombstone cũ sau 30 ngày. Đồng thời Worker gọi Xóa Suggestion Cache.

```json
POST products_write/_update/{sku_id}
{
  "scripted_upsert": true,
  "script": {
    "source": "if (ctx._source.version != null && params.version <= ctx._source.version) { ctx.op = 'none'; return; } ctx._source.is_deleted = true; ctx._source.deleted_at = params.deleted_at; ctx._source.version = params.version; ctx._source.is_in_stock = false;",
    "params": {
      "version": 9,
      "deleted_at": "2026-03-24T10:00:00Z"
    }
  },
  "upsert": {
    "sku_id": "sku-uuid-002",
    "product_id": "prod-uuid-001",
    "is_deleted": true,
    "deleted_at": "2026-03-24T10:00:00Z",
    "is_in_stock": false,
    "version": 9
  }
}
```

> **Bulk partial failure policy:** khi xử lý `PRODUCT_DELETED` theo nhiều `sku_id`, các item failed phải được tách retry có chọn lọc; không được fail cả batch đã thành công.

### 2.8. REVIEW_METRICS_UPDATED
**Nguồn:** Review Service.

**Topic:** `t.review.events`

**Logic xử lý:**
1. Parse payload: `product_id`, `average_rating`, `total_reviews`, `updated_at`.
2. Resolve `event_sequence_id` theo thứ tự ưu tiên tương thích ngược:
   - ưu tiên `envelope.event_sequence_id` (contract chuẩn),
   - fallback `payload.event_sequence_id` nếu producer cũ chưa mirror đầy đủ.
   - nếu cả hai cùng có nhưng khác nhau -> contract violation, chuyển `t.search.dlq`.
3. Chạy `_update_by_query` theo `product_id` để update rating field cho toàn bộ SKU docs của product, bắt buộc dùng `conflicts=proceed` + `requests_per_second` để tránh spike CPU.
4. `event_sequence_id` là bắt buộc theo contract Review, dùng làm guard ordering duy nhất.
5. `updated_at` chỉ lưu cho observability/debug, không tham gia quyết định apply/skip.

```json
POST products_write/_update_by_query?conflicts=proceed&requests_per_second=300
{
  "query": { "term": { "product_id": "prod-uuid-001" } },
  "script": {
    "source": "if (params.event_sequence_id == null) { ctx.op = 'none'; return; } if (ctx._source.review_metrics_event_sequence_id != null && params.event_sequence_id <= ctx._source.review_metrics_event_sequence_id) { ctx.op = 'none'; return; } ctx._source.product_rating_avg = params.average_rating; ctx._source.product_rating_count = params.total_reviews; ctx._source.review_metrics_updated_at = params.updated_at; ctx._source.review_metrics_event_sequence_id = params.event_sequence_id;",
    "params": {
      "average_rating": 4.75,
      "total_reviews": 1251,
      "updated_at": "2026-03-28T10:05:00Z",
      "event_sequence_id": 1743156000123
    }
  }
}
```

6. Nếu thiếu `event_sequence_id` sau bước resolve: đánh dấu contract violation và park vào `t.search.dlq` (không fallback timestamp).
7. Nếu ES unavailable: retry theo policy chung; quá ngưỡng đẩy `t.search.dlq`.
8. Kết quả `_update_by_query` phải parse `failures`: nếu có lỗi shard/script thì tách retry có chọn lọc hoặc chuyển `t.search.dlq`; `version_conflicts` do guard có thể chấp nhận.

### 2.9. ORDER_PRODUCT_METRICS_UPDATED
**Nguồn:** Order Metrics Projector.

**Topic:** `t.search.metrics.events`

**Logic xử lý:**
1. Parse `product_id`, `total_sold`, `event_sequence_id`.
2. `_update_by_query` theo `product_id` để set `product_sold_count = total_sold`, bắt buộc dùng `conflicts=proceed` + `requests_per_second`.
3. Guard bằng `event_sequence_id` để chặn event metrics cũ, đồng thời persist vào `sales_metrics_event_sequence_id` để dùng cho lần so sánh tiếp theo.
4. Parse kết quả `_update_by_query`; nếu `failures` không rỗng thì retry có kiểm soát hoặc chuyển `t.search.dlq`.

### 2.10. CATEGORY_DELETED
**Nguồn:** [Product Events - 3.9](../product/product_events.md#39-category_deleted)

**Topic:** `t.category.events`

**Logic xử lý:**
1. **CẤM TUYỆT ĐỐI** tự ý `_update_by_query` đưa `product_status` của các sản phẩm mồ côi về `HIDDEN` (Hành động này vi phạm cực độ nguyên lý CQRS Schema). Search Service KHÔNG CÓ QUYỀN tự thay đổi State của Sản phẩm nếu ở Source Database chưa thay đổi thực sự. Việc đổi State thành Ẩn mồ côi sẽ được Worker của Product Service xử lý và tự động Push sang thông qua các Event `PRODUCT_STATE_CHANGED` theo ngay sau.
2. Ở bước này, Search Service chỉ chạy `_update_by_query` để xoá tên (nullify `category_name`, `category_path`) của các Document liên đới để giữ consistency cấu trúc; bắt buộc dùng `conflicts=proceed` + `requests_per_second`.
3. Invalidate `search:categories:tree`.
4. Parse kết quả `_update_by_query`: `version_conflicts` có thể chấp nhận, `failures` phải retry có chọn lọc hoặc vào `t.search.dlq`.

## 3. Chiến Lược Idempotency & Ordering

### 3.1. Chống Duplicate
`PUT _doc/{id}` và `POST _update/{id}` là idempotent khi kết hợp guard version.

### 3.2. Chống Out-of-Order
Search Worker bắt buộc áp dụng quy tắc:

```text
IF event_type = PRODUCT_PRICE_UPDATED AND event.event_sequence_id IS NULL THEN
  SKIP event (contract violation -> DLQ)
ELSE IF event_type = PRODUCT_PRICE_UPDATED AND event.event_sequence_id <= current_doc.price_event_sequence_id THEN
  SKIP event
ELSE IF event_type = PRODUCT_PRICE_UPDATED AND event.version < current_doc.version THEN
  SKIP event
ELSE IF event_type = REVIEW_METRICS_UPDATED AND event.event_sequence_id <= current_doc.review_metrics_event_sequence_id THEN
  SKIP event
ELSE IF event_type = REVIEW_METRICS_UPDATED AND event.event_sequence_id IS NULL THEN
  SKIP event
ELSE IF event_type = ORDER_PRODUCT_METRICS_UPDATED AND event.event_sequence_id <= current_doc.sales_metrics_event_sequence_id THEN
  SKIP event
ELSE IF event_type IN (CATEGORY_UPDATED, CATEGORY_DELETED) THEN
  APPLY event
ELSE IF event_type <> PRODUCT_PRICE_UPDATED AND event.version IS NOT NULL AND event.version <= current_doc.version THEN
  SKIP event
ELSE
    APPLY event
END
```

**Lưu ý bắt buộc cho `PRODUCT_PRICE_UPDATED`:** Event có `version` bằng current document vẫn hợp lệ nếu `event_sequence_id` lớn hơn current `price_event_sequence_id`.

Category-level update không dùng guard riêng vì hiếm khi xảy ra race condition.

### 3.3. Ma Trận Guard Theo Event Stream (Bắt buộc)

| Event stream | Field guard chính | Vì sao không dùng chung một sequence toàn cục |
|---|---|---|
| Product aggregate events (`PRODUCT_CREATED`, `PRODUCT_UPDATED`, `PRODUCT_STATE_CHANGED`, `PRODUCT_SKU_STATUS_CHANGED`, `PRODUCT_DELETED`, `PRODUCT_SKU_DELETED`) | `version` | Đây là version nghiệp vụ của aggregate Product do Product Service quản lý. |
| Price stream (`PRODUCT_PRICE_UPDATED`) | `price_event_sequence_id` (+ fence `version`) | Một transaction có thể phát nhiều event giá với cùng `version`; cần sequence riêng cấp SKU-event. |
| Review metrics (`REVIEW_METRICS_UPDATED`) | `review_metrics_event_sequence_id` | Stream độc lập từ Review Service, cadence khác Product stream. |
| Sales metrics (`ORDER_PRODUCT_METRICS_UPDATED`) | `sales_metrics_event_sequence_id` | Stream độc lập từ Order Metrics Projector, partition theo `product_id`. |
| Category projection (`CATEGORY_UPDATED`, `CATEGORY_DELETED`) | *(không dùng guard riêng)* | Category update hiếm khi xảy ra race condition; sử dụng `updated_at` của event hoặc apply bất kỳ. |

**Nguyên tắc cứng:** không so sánh chéo sequence/version giữa các stream khác ownership.

**Lưu ý normalize guard:** với `PRODUCT_SKU_DELETED`, guard `version` lấy từ `payload.version`.

### 3.4. Xử Lý Lỗi
- **Elasticsearch unreachable (timeout/429/503):** nack event để Kafka retry theo policy transient.
- **`document_missing_exception` cho partial update:** không được bỏ qua. Retry inline tối đa 2 attempt tổng (lần đầu + 1 retry), sau đó đẩy vào `t.search.retry` để self-healing fetch full product; quá ngưỡng tổng mới vào `t.search.dlq`.
- **Retry budget chuẩn (thống nhất toàn Search):**
  - Inline consumer: tối đa 2 attempt tổng.
  - Retry replayer (`t.search.retry`): tối đa 3 attempt.
  - Tổng tối đa: 5 attempt trước khi park `t.search.dlq`.
- **Lỗi liên tiếp vượt ngưỡng:** đẩy vào `t.search.dlq` và cảnh báo vận hành.
