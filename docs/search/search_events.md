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

## 1. Bản Đồ Kafka Topics

| Topic Name | Consumer Group | Producer | Events trong Topic | Mô tả |
|---|---|---|---|---|
| `t.product.events` | `search-product-indexer` | Product Service | `PRODUCT_CREATED`, `PRODUCT_UPDATED`, `PRODUCT_STATE_CHANGED`, `PRODUCT_PRICE_UPDATED`, `PRODUCT_SKU_STATUS_CHANGED`, `PRODUCT_DELETED`, `PRODUCT_SKU_DELETED` | Topic chính cho Product projection |
| `t.category.events` | `search-category-indexer` | Product Service | `CATEGORY_UPDATED`, `CATEGORY_DELETED` | Topic chuyên biệt cho category projection |
| `t.review.events` | `search-review-metrics-sync` | Review Service | `REVIEW_METRICS_UPDATED` | Đồng bộ điểm rating và số lượng review theo thời gian thực |
| `t.seller.events` | `search-seller-profile-sync` | Seller Service | `SELLER_PROFILE_UPDATED`, `SELLER_STATUS_CHANGED` | Đồng bộ `seller_name` và trạng thái seller vào document Search |
| `t.search.metrics.events` | `search-sales-metrics-sync` | Order Service (Order Metrics Projector nội bộ) | `ORDER_PRODUCT_METRICS_UPDATED` | Đồng bộ realtime `product_sold_count` theo `product_id` |

## 2. Chi Tiết Xử Lý Theo Event Type

### 2.1. PRODUCT_CREATED
**Nguồn:** [Product Events - 3.1](../product/product_events.md#31-product_created)

**Logic xử lý:**
1. Parse payload, extract Product + danh sách SKUs.
2. Nếu `skus` rỗng/null -> log warning và skip event.
3. Enrichment category từ [Category Cache](search_caching_schema.md#12-category-tree-cache).
4. **Data transformation ownership (bắt buộc):** Search Worker transform `attributes_json` và `variant_attributes_json` từ raw JSON sang token arrays `searchable_attributes` / `variant_attributes`.
5. **Cấm dùng Index thuần tuý:** Bắt buộc sử dụng Scripted Upsert với Guard Version để chống lỗi Zombie Hole nếu event `PRODUCT_DELETED` hoặc `PRODUCT_UPDATED` đến trước. Chỉ Insert/Update nếu `event.version > current_doc.version`.

### 2.2. PRODUCT_UPDATED
**Nguồn:** [Product Events - 3.2](../product/product_events.md#32-product_updated)

**Logic xử lý:**
1. Update toàn bộ trường Product-level và SKU-level theo payload mới nhất.
2. Guard out-of-order bằng script: chỉ apply khi `event.version > current_doc.version`.
3. **Không tự tính lại giá:** Search Worker chỉ ghi đúng giá trị `original_price`, `promotional_price`, `best_price`, `has_promotion` nhận từ payload. Không có phép tính business pricing ở Search.

### 2.3. PRODUCT_STATE_CHANGED
**Nguồn:** [Product Events - 3.3](../product/product_events.md#33-product_state_changed)

**Logic xử lý:**
1. Update `product_status` cho tất cả SKU thuộc `product_id` bằng `_update_by_query`.
2. Guard bằng `version` để chặn stale update.

### 2.4. PRODUCT_PRICE_UPDATED
**Nguồn:** [Product Events - 3.4](../product/product_events.md#34-product_price_updated)

**Logic xử lý:**
1. Parse payload: `sku_id`, `original_price`, `promotional_price`, `best_price` (nếu có), `has_promotion`, `version`, `event_sequence_id`.
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

### 2.5. PRODUCT_SKU_STATUS_CHANGED
**Nguồn:** [Product Events - 3.5](../product/product_events.md#35-product_sku_status_changed)

**Logic xử lý:**
1. Parse payload: `sku_id`, `new_status`, `version`.
2. Map `is_in_stock = (new_status == 'ACTIVE')`.
3. Partial update chỉ các trường trạng thái + version.

```json
POST products_write/_update/{sku_id}
{
  "script": {
    "source": "if (ctx._source.version != null && params.version <= ctx._source.version) { ctx.op = 'none'; return; } ctx._source.sku_status = params.sku_status; ctx._source.is_in_stock = params.is_in_stock; ctx._source.version = params.version;",
    "params": {
      "sku_status": "OUT_OF_STOCK",
      "is_in_stock": false,
      "version": 8
    }
  }
}
```

### 2.6. CATEGORY_UPDATED
**Nguồn:** [Product Events - 3.7](../product/product_events.md#37-category_updated)

**Topic:** `t.category.events`

**Logic xử lý:**
1. Nhận `category_id`, `new_name`, `new_path`, `category_version`.
2. Chạy `_update_by_query` và guard bằng `category_version` để tránh race condition ghi đè tên cũ.

```json
POST products_write/_update_by_query
{
  "query": { "term": { "category_id": "cat-smartphone" } },
  "script": {
    "source": "if (ctx._source.category_version != null && params.category_version <= ctx._source.category_version) { ctx.op = 'none'; return; } ctx._source.category_name = params.new_name; ctx._source.category_path = params.new_path; ctx._source.category_version = params.category_version;",
    "params": {
      "new_name": "Điện thoại di động",
      "new_path": "dien-tu/dien-thoai-di-dong",
      "category_version": 12
    }
  }
}
```

> Script update có guard version, không cập nhật các field không liên quan.

### 2.7. PRODUCT_DELETED / PRODUCT_SKU_DELETED
**Kafka Topic:** `t.product.events`
**Hành động Elasticsearch:** Delete / Tombstone.
*   **Thực thi Tombstone & [X2 Fix Loop Tường Minh]**: Payload của Event chứa mảng `sku_ids`. Mặc dù event đại diện cho 1 Product bị xóa, Worker bóc tách xử lý BẮT BUỘC phải thực thi vòng lặp qua từng `sku_id` (Vì Elasticsearch Schema cấu trúc theo hướng từng SKU làm 1 Document `_id`). Worker gọi Bulk API chứa N lệnh Scripted Upsert Tombstone ứng với N mã SKU. (Upsert 1 document giả mang cờ `is_deleted=true` và `version` mới nhất). KHÔNG được gọi lệnh `POST _update/prod-uuid-001` vì Index hoạt động bằng `sku_id`.
*   Chiến lược Tombstone Zero-Downtime này chặn trường hợp document bị xóa, nhưng do network lag, event tạo cũ (hoặc edit) lại tới trễ đào mồ sống dậy SKU. Update Script sẽ bẻ gãy Event trễ bằng `event.version > ctx._source.version`.
*   Cronjob làm sạch rác (ILM/Cleanup Job) sẽ dọn dẹp các Tombstone cũ sau 30 ngày. Đồng thời Worker gọi Xóa Suggestion Cache.

### 2.8. REVIEW_METRICS_UPDATED
**Nguồn:** Review Service.

**Topic:** `t.review.events`

**Logic xử lý:**
1. Parse payload: `product_id`, `average_rating`, `total_reviews`, `updated_at`, `event_sequence_id`.
2. Chạy `_update_by_query` theo `product_id` để update rating field cho toàn bộ SKU docs của product.
3. Guard thứ tự theo `event_sequence_id` (ưu tiên) để chặn stale event. Chỉ fallback sang `updated_at` khi producer chưa phát `event_sequence_id` vì lý do tương thích ngược.

```json
POST products_write/_update_by_query
{
  "query": { "term": { "product_id": "prod-uuid-001" } },
  "script": {
    "source": "if (params.event_sequence_id != null) { if (ctx._source.review_metrics_event_sequence_id != null && params.event_sequence_id <= ctx._source.review_metrics_event_sequence_id) { ctx.op = 'none'; return; } } else { if (ctx._source.review_metrics_updated_at != null && params.updated_at <= ctx._source.review_metrics_updated_at) { ctx.op = 'none'; return; } } ctx._source.product_rating_avg = params.average_rating; ctx._source.product_rating_count = params.total_reviews; ctx._source.review_metrics_updated_at = params.updated_at; ctx._source.review_metrics_event_sequence_id = params.event_sequence_id;",
    "params": {
      "average_rating": 4.75,
      "total_reviews": 1251,
      "updated_at": "2026-03-28T10:05:00Z",
      "event_sequence_id": 1743156000123
    }
  }
}
```

4. Nếu ES unavailable: retry theo policy chung; quá ngưỡng đẩy `t.search.dlq`.

### 2.9. SELLER_PROFILE_UPDATED / SELLER_STATUS_CHANGED
**Nguồn:** Seller Service.

**Topic:** `t.seller.events`

**Logic xử lý:**
1. `SELLER_PROFILE_UPDATED`: parse `seller_id`, `seller_name`, `profile_version`.
2. Chạy `_update_by_query` theo `seller_id`, chỉ apply khi `profile_version > seller_profile_version` để tránh ghi đè tên cũ.
3. `SELLER_STATUS_CHANGED`: parse `seller_id`, `new_status`, `status_version`, `changed_at`, `visibility_policy`.
4. Luôn đồng bộ `seller_status` và guard theo `status_version` (fallback `changed_at` khi producer chưa phát version vì tương thích ngược).
5. Chỉ cập nhật `product_status=HIDDEN` khi `visibility_policy=HIDE_LISTINGS`; nếu không có cờ policy, Search chỉ cập nhật metadata `seller_status`.

### 2.10. ORDER_PRODUCT_METRICS_UPDATED
**Nguồn:** Order Metrics Projector.

**Topic:** `t.search.metrics.events`

**Logic xử lý:**
1. Parse `product_id`, `total_sold`, `event_sequence_id`.
2. `_update_by_query` theo `product_id` để set `product_sold_count = total_sold`.
3. Guard bằng `event_sequence_id` để chặn event metrics cũ, đồng thời persist vào `sales_metrics_event_sequence_id` để dùng cho lần so sánh tiếp theo.

### 2.11. CATEGORY_DELETED
**Nguồn:** [Product Events - 3.9](../product/product_events.md#39-category_deleted)

**Topic:** `t.category.events`

**Logic xử lý:**
1. Nhận `category_version` từ event.
2. **CẤM TUYỆT ĐỐI** tự ý `_update_by_query` đưa `product_status` của các sản phẩm mồ côi về `HIDDEN` (Hành động này vi phạm cực độ nguyên lý CQRS Schema). Search Service KHÔNG CÓ QUYỀN tự thay đổi State của Sản phẩm nếu ở Source Database chưa thay đổi thực sự. Việc đổi State thành ẨN mồ côi sẽ được Worker của Product Service xử lý và tự động Push sang thông qua các Event `PRODUCT_STATE_CHANGED` theo ngay sau.
3. Ở bước này, Search Service chỉ chạy `_update_by_query` để xoá tên (nullify `category_name`, `category_path`) và nhích `category_version` của các Document liên đới để giữ consistency cấu trúc.
4. Invalidate `search:categories:tree`.

## 3. Chiến Lược Idempotency & Ordering

### 3.1. Chống Duplicate
`PUT _doc/{id}` và `POST _update/{id}` là idempotent khi kết hợp guard version.

### 3.2. Chống Out-of-Order
Search Worker bắt buộc áp dụng quy tắc:

```text
IF event_type = PRODUCT_PRICE_UPDATED AND event.event_sequence_id <= current_doc.price_event_sequence_id THEN
  SKIP event
ELSE IF event_type = PRODUCT_PRICE_UPDATED AND event.version < current_doc.version THEN
  SKIP event
ELSE IF event_type = REVIEW_METRICS_UPDATED AND event.event_sequence_id <= current_doc.review_metrics_event_sequence_id THEN
  SKIP event
ELSE IF event_type = ORDER_PRODUCT_METRICS_UPDATED AND event.event_sequence_id <= current_doc.sales_metrics_event_sequence_id THEN
  SKIP event
ELSE IF event_type = SELLER_PROFILE_UPDATED AND event.profile_version <= current_doc.seller_profile_version THEN
  SKIP event
ELSE IF event_type = SELLER_STATUS_CHANGED AND event.status_version <= current_doc.seller_status_version THEN
  SKIP event
ELSE IF event_type IN (CATEGORY_UPDATED, CATEGORY_DELETED) AND event.category_version <= current_doc.category_version THEN
  SKIP event
ELSE IF event.version <= current_doc.version THEN
  SKIP event
ELSE
    APPLY event
END
```

Tương tự cho category-level update dùng `category_version`.

### 3.3. Xử Lý Lỗi
- **Elasticsearch unreachable:** nack event để Kafka retry.
- **`document_missing_exception` cho partial update:** không được bỏ qua. Log error và push vào Retry Topic hoặc DLQ; có thể trigger fetch full product để tự phục hồi.
- **Retry policy chuẩn:** retry tối đa 5 lần theo exponential backoff (1s -> 2s -> 4s -> 8s -> 16s).
- **Lỗi liên tiếp vượt ngưỡng:** đẩy vào `t.search.dlq` và cảnh báo vận hành.