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
>
> **Policy shared-topic (bắt buộc):** nếu consumer nhận event hợp lệ theo envelope nhưng `event_type` không nằm trong allowlist của consumer group đó thì phải **ignore + ACK + ghi metric** (`search_event_ignored_total{topic,event_type}`), không đẩy DLQ.
>
> **Guard Strategy — Timestamp-Only:** Search Service sử dụng duy nhất `event.updated_at` (ISO 8601 UTC) làm guard ordering cho tất cả event streams. Trường `version` trong Elasticsearch document lưu timestamp ISO 8601. So sánh: `event.updated_at > current_doc.version`. Event `PRODUCT_DELETED` luôn apply (tombstone) để đảm bảo xóa. Phương pháp này đơn giản, nhất quán, không cần quản lý nhiều sequence field.

## 1. Bản Đồ Kafka Topics

|| Topic Name | Consumer Group | Producer | Events trong Topic | Mô tả |
||---|---|---|---|---|
|| `t.product.events` | `search-product-indexer` | Product Service | `PRODUCT_CREATED`, `PRODUCT_UPDATED`, `PRODUCT_STATUS_CHANGED`, `SKU_UPDATED`, `PRODUCT_DELETED`, `SKU_DELETED`, `SKU_CREATED` | Topic chính cho Product projection |
|| `t.category.events` | `search-category-indexer` | Product Service | `CATEGORY_CREATED`, `CATEGORY_UPDATED`, `CATEGORY_DELETED` | Topic chuyên biệt cho category projection |
|| `t.review.events` | `search-review-metrics-sync` | Review Service | `REVIEW_METRICS_UPDATED` | Đồng bộ điểm rating và số lượng review theo thời gian thực |
|| `t.search.metrics.events` | `search-sales-metrics-sync` | Order Service (Order Metrics Projector nội bộ) | `ORDER_PRODUCT_METRICS_UPDATED` | Đồng bộ realtime `product_sold_count` theo `product_id` |
|| `t.search.retry` | `search-retry-replayer` | Search Service (internal) | retry envelopes (self-heal) | Kênh nội bộ xử lý lỗi có thể phục hồi như `document_missing_exception` |
|| `t.search.dlq` | `search-dlq-processor` | Search Service (internal) | failed envelopes (parking lot) | Kênh lỗi không tự phục hồi hoặc vượt ngưỡng retry để replay có kiểm soát |

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
7. **Cấm dùng Index thuần tuý:** Bắt buộc sử dụng Scripted Upsert với Guard Timestamp để chống lỗi Zombie Hole nếu event `PRODUCT_DELETED` hoặc `PRODUCT_UPDATED` đến trước. Chỉ Insert/Update nếu `event.updated_at > current_doc.version` (so sánh timestamp ISO).

### 2.2. PRODUCT_UPDATED
**Nguồn:** [Product Events - 3.2](../product/product_events.md#32-product_updated)

**Logic xử lý:**
1. Parse payload: extract `product_id`, product-level fields (`name`, `description`, `category_id`, `attributes_json`, `media`), và `sku_ids[]`.
2. Validate: `sku_ids[]` bắt buộc phải có trong payload.
3. **Bulk partial update theo `sku_ids[]`:** Loop qua từng `sku_id` trong mảng, gọi `_update` trên document `_id = sku_id` để set các product-level fields. Payload chỉ chứa product-level fields, không chứa SKU-level fields — script chỉ update đúng các trường có mặt trong payload.
4. **Contract giá trong snapshot:** Payload không còn chứa SKU-level fields. Các trường giá (`original_price`, `promotional_price`, `best_price`, `has_promotion`) và trạng thái (`sku_status`) không bị ảnh hưởng bởi event này.
5. Guard out-of-order bằng script: chỉ apply khi `event.updated_at > current_doc.version`.
6. **Không tự tính lại giá:** Search Worker chỉ ghi đúng giá trị nhận được từ payload. Không có phép tính business pricing ở Search.

```json
-- Loop qua từng sku_id trong sku_ids[]
POST products_write/_update/{sku_id}
{
  "script": {
    "source": "if (ctx._source.version != null && params.updated_at <= ctx._source.version) { ctx.op = 'none'; return; } if (params.name != null) ctx._source.name = params.name; if (params.description != null) ctx._source.description = params.description; if (params.category_id != null) ctx._source.category_id = params.category_id; if (params.attributes_json != null) ctx._source.attributes_json = params.attributes_json; if (params.media != null) ctx._source.media = params.media; ctx._source.version = params.updated_at;",
    "params": {
      "name": "iPhone 15 Pro Max 256GB (Updated)",
      "description": "Mô tả cập nhật...",
      "category_id": "cat-dien-thoai",
      "attributes_json": { "material": "Titanium Grade 5", "origin": "Vietnam" },
      "media": [{ "media_type": "IMAGE", "url": "https://cdn.example.com/ip15-main-updated.jpg", "thumbnail_url": "https://cdn.example.com/ip15-main-updated-thumb.jpg", "sort_order": 1 }],
      "updated_at": "2026-02-28T04:15:00Z"
    }
  }
}
```

> **Tại sao không dùng `_update_by_query`:** Document Elasticsearch có `_id = sku_id`, mỗi document đại diện cho 1 SKU. `_update_by_query` update TẤT CẢ documents matching `product_id` — bao gồm cả các SKU mới được thêm hoặc những SKU không thuộc payload này. Việc loop theo `sku_ids[]` đảm bảo chỉ update đúng các document tương ứng với product snapshot tại thời điểm event được phát.

### 2.3. PRODUCT_STATUS_CHANGED
**Nguồn:** [Product Events - 3.3](../product/product_events.md#33-product_state_changed)

**Logic xử lý:**
1. Update `product_status` cho tất cả SKU thuộc `product_id` bằng `_update_by_query`.
2. Guard bằng `updated_at` timestamp: chỉ apply khi `event.updated_at > current_doc.version`.
3. Bắt buộc throttle `_update_by_query` bằng `requests_per_second` (khuyến nghị 300-500) + `conflicts=proceed` để tránh spike CPU Elasticsearch.
4. Recompute `is_in_stock` theo trạng thái hợp lệ để bán: `is_deleted == false AND product_status == 'ACTIVE' AND sku_status == 'ACTIVE'`.

```json
POST products_write/_update_by_query?conflicts=proceed&requests_per_second=300
{
  "query": { "term": { "product_id": "prod-uuid-001" } },
  "script": {
    "source": "if (ctx._source.version != null && params.updated_at <= ctx._source.version) { ctx.op = 'none'; return; } ctx._source.product_status = params.new_status; ctx._source.is_in_stock = (!ctx._source.is_deleted && ctx._source.product_status == 'ACTIVE' && ctx._source.sku_status == 'ACTIVE'); ctx._source.version = params.updated_at;",
    "params": {
      "new_status": "ACTIVE",
      "updated_at": "2026-02-28T05:00:00Z"
    }
  }
}
```


### 2.4. SKU_UPDATED
**Nguồn:** [Product Events - 3.4](../product/product_events.md#34-sku_updated)

**Logic xử lý:**
1. Parse payload: `sku_id`, `product_id`, các trường SKU, `changed_fields[]`, `product_status_changed`, `updated_at`.
2. **Partial update theo `changed_fields`:**
   - Nếu `changed_fields` chứa price fields (`original_price`, `promotional_price`, `best_price`, `has_promotion`): update các trường giá trên document SKU.
   - Nếu `changed_fields` chứa `sku_status`: update `sku_status`, recompute `is_in_stock`.
   - Nếu `changed_fields` chứa `product_status` (FSM trigger): update `product_status` và recompute `is_in_stock`.
   - Nếu `changed_fields` chứa `variant_attributes` hoặc `media`: update các trường tương ứng.
3. Guard: chỉ apply khi `event.updated_at > current_doc.version`.
4. **Cấm `upsert`/`doc_as_upsert`** cho event partial update để tránh tạo document cụt khi event đến trước `PRODUCT_CREATED`/`SKU_CREATED`.
5. Nếu `product_status_changed = true`: đồng thời update `product_status = new_product_status` trên toàn bộ SKU documents của product bằng `_update_by_query` (theo `product_id`).

```json
-- Khi changed_fields chỉ chứa price fields
POST products_write/_update/{sku_id}
{
  "script": {
    "source": "if (ctx._source.version != null && params.updated_at <= ctx._source.version) { ctx.op = 'none'; return; } if (params.changed_fields.includes('original_price')) ctx._source.original_price = params.original_price; if (params.changed_fields.includes('promotional_price')) ctx._source.promotional_price = params.promotional_price; if (params.changed_fields.includes('best_price')) ctx._source.best_price = params.best_price; if (params.changed_fields.includes('has_promotion')) ctx._source.has_promotion = params.has_promotion; ctx._source.version = params.updated_at;",
    "params": {
      "original_price": 33990000,
      "promotional_price": 28891500,
      "best_price": 28891500,
      "has_promotion": true,
      "changed_fields": ["original_price", "promotional_price", "best_price", "has_promotion"],
      "updated_at": "2026-02-28T06:00:00Z"
    }
  }
}

-- Khi changed_fields chứa sku_status (FSM trigger - bulk update theo product_id)
POST products_write/_update_by_query?conflicts=proceed&requests_per_second=300
{
  "query": { "term": { "product_id": "prod-uuid-001" } },
  "script": {
    "source": "if (ctx._source.version != null && params.updated_at <= ctx._source.version) { ctx.op = 'none'; return; } ctx._source.sku_status = params.new_sku_status; ctx._source.is_in_stock = (!ctx._source.is_deleted && params.product_status == 'ACTIVE' && ctx._source.sku_status == 'ACTIVE'); ctx._source.version = params.updated_at;",
    "params": {
      "new_sku_status": "OUT_OF_STOCK",
      "product_status": "HIDDEN",
      "updated_at": "2026-02-28T09:00:00Z"
    }
  }
}
```

> **Nếu `product_status_changed = true`:** Script phải đồng thời set `product_status = params.new_product_status` trên toàn bộ SKU documents. Downstream không cần consume thêm `PRODUCT_STATUS_CHANGED` riêng cho luồng FSM.
>
> Nếu gặp `document_missing_exception`: không được skip. Log error và đẩy event vào Retry Topic/DLQ hoặc trigger fetch full product để self-healing.

### 2.5. CATEGORY_CREATED
**Nguồn:** [Product Events - 3.9](../product/product_events.md#39-category_created)

**Topic:** `t.category.events`

**Logic xử lý:**
1. Parse payload: `category_id`, `name`, `slug`, `parent_id`, `level`, `is_leaf`, `path`.
2. Validate: `path` bắt buộc phải có trong payload. Nếu thiếu → đánh dấu contract violation, đẩy `t.search.dlq`.
3. Invalidate `search:categories:tree` trong Redis (toàn bộ category tree cache vì cấu trúc cây thay đổi).
4. Không cần update documents trên Elasticsearch — không có product document nào reference đến category mới tại thời điểm tạo. Category tree cache sẽ được rebuild bởi Search Category Sync Worker.

> **Tại sao không cần `_update_by_query`:** Khi category mới được tạo, chưa có sản phẩm nào thuộc category này (hoặc nếu có, chúng sẽ được update thông qua `PRODUCT_CREATED`/`PRODUCT_UPDATED` event). Event này chỉ phục vụ rebuild category tree cache và Search facet data.

### 2.6. CATEGORY_UPDATED
**Nguồn:** [Product Events - 3.10](../product/product_events.md#310-category_updated)

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

### 2.7. PRODUCT_DELETED / SKU_DELETED
**Kafka Topic:** `t.product.events`
**Hành động Elasticsearch:** Delete / Tombstone.
*   **`PRODUCT_DELETED`**: Payload chứa mảng `sku_ids`. Worker BẮT BUỘC loop theo từng `sku_id` và gọi Bulk API để scripted-upsert tombstone cho từng SKU document (`_id = sku_id`).
*   **`SKU_DELETED`**: Payload chứa **một** `sku_id` (không phải `sku_ids`). Worker chỉ update/tombstone đúng document `_id = sku_id` đó, không mở vòng lặp theo mảng.
*   `PRODUCT_SKU_DELETED` bắt buộc có `payload.updated_at`; worker phải dùng `payload.updated_at` làm guard timestamp trước khi scripted-upsert tombstone.
*   Chiến lược Tombstone Zero-Downtime này chặn trường hợp document bị xóa, nhưng do network lag, event tạo cũ (hoặc edit) lại tới trễ đào mồ sống dậy SKU. Update Script sẽ bẻ gãy Event trễ bằng `event.updated_at > ctx._source.version`.
*   Cronjob làm sạch rác (ILM/Cleanup Job) sẽ dọn dẹp các Tombstone cũ sau 30 ngày. Đồng thời Worker gọi Xóa Suggestion Cache.

```json
POST products_write/_update/{sku_id}
{
  "scripted_upsert": true,
  "script": {
    "source": "if (ctx._source.version != null && params.updated_at <= ctx._source.version) { ctx.op = 'none'; return; } ctx._source.is_deleted = true; ctx._source.deleted_at = params.deleted_at; ctx._source.version = params.updated_at; ctx._source.is_in_stock = false;",
    "params": {
      "updated_at": "2026-03-24T10:00:00Z",
      "deleted_at": "2026-03-24T10:00:00Z"
    }
  },
  "upsert": {
    "sku_id": "sku-uuid-002",
    "product_id": "prod-uuid-001",
    "is_deleted": true,
    "deleted_at": "2026-03-24T10:00:00Z",
    "is_in_stock": false,
    "version": "2026-03-24T10:00:00Z"
  }
}
```

> **Bulk partial failure policy:** khi xử lý `PRODUCT_DELETED` theo nhiều `sku_id`, các item failed phải được tách retry có chọn lọc; không được fail cả batch đã thành công.

### 2.8. REVIEW_METRICS_UPDATED
**Nguồn:** Review Service.

**Topic:** `t.review.events`

**Logic xử lý:**
1. Parse payload: `product_id`, `average_rating`, `total_reviews`, `updated_at`.
2. Guard: chỉ apply khi `event.updated_at > current_doc.review_metrics_updated_at`. Nếu document chưa có field này (first event), cho apply.
3. Chạy `_update_by_query` theo `product_id` để update rating field cho toàn bộ SKU docs của product, bắt buộc dùng `conflicts=proceed` + `requests_per_second` để tránh spike CPU.
4. `updated_at` vừa dùng làm guard vừa lưu vào document cho lần so sánh tiếp theo.
5. Nếu ES unavailable: retry theo policy chung; quá ngưỡng đẩy `t.search.dlq`.
6. Kết quả `_update_by_query` phải parse `failures`: nếu có lỗi shard/script thì tách retry có chọn lọc hoặc chuyển `t.search.dlq`; `version_conflicts` do guard có thể chấp nhận.

```json
POST products_write/_update_by_query?conflicts=proceed&requests_per_second=300
{
  "query": { "term": { "product_id": "prod-uuid-001" } },
  "script": {
    "source": "if (ctx._source.review_metrics_updated_at != null && params.updated_at <= ctx._source.review_metrics_updated_at) { ctx.op = 'none'; return; } ctx._source.product_rating_avg = params.average_rating; ctx._source.product_rating_count = params.total_reviews; ctx._source.review_metrics_updated_at = params.updated_at;",
    "params": {
      "average_rating": 4.75,
      "total_reviews": 1251,
      "updated_at": "2026-03-28T10:05:00Z"
    }
  }
}
```

### 2.9. ORDER_PRODUCT_METRICS_UPDATED
**Nguồn:** Order Metrics Projector.

**Topic:** `t.search.metrics.events`

**Logic xử lý:**
1. Parse `product_id`, `total_sold`, `updated_at`.
2. Guard: chỉ apply khi `event.updated_at > current_doc.sales_metrics_updated_at`. Nếu document chưa có field này, cho apply.
3. `_update_by_query` theo `product_id` để set `product_sold_count = total_sold`, bắt buộc dùng `conflicts=proceed` + `requests_per_second`.
4. Parse kết quả `_update_by_query`; nếu `failures` không rỗng thì retry có kiểm soát hoặc chuyển `t.search.dlq`.

```json
POST products_write/_update_by_query?conflicts=proceed&requests_per_second=300
{
  "query": { "term": { "product_id": "prod-uuid-001" } },
  "script": {
    "source": "if (ctx._source.sales_metrics_updated_at != null && params.updated_at <= ctx._source.sales_metrics_updated_at) { ctx.op = 'none'; return; } ctx._source.product_sold_count = params.total_sold; ctx._source.sales_metrics_updated_at = params.updated_at;",
    "params": {
      "total_sold": 15200,
      "updated_at": "2026-03-28T10:05:00Z"
    }
  }
}
```

### 2.11. CATEGORY_DELETED
**Nguồn:** [Product Events - 3.12](../product/product_events.md#312-category_deleted)

**Topic:** `t.category.events`

**Logic xử lý:**
1. Nhận `category_id`, `descendant_category_ids`, `updated_at`, `deleted_at`.
2. Chạy `_update_by_query` để nullify `category_name`, `category_path` và **đồng thời** update `product_status = 'HIDDEN'` cho toàn bộ product documents thuộc các category bị xoá. Guard bằng `updated_at` timestamp: chỉ apply khi `event.updated_at > current_doc.version`.
3. Recompute `is_in_stock` đồng thời: `is_deleted == false AND product_status == 'ACTIVE' AND sku_status == 'ACTIVE'`.
4. Bắt buộc dùng `conflicts=proceed` + `requests_per_second`; parse kết quả `_update_by_query` để tách retry có chọn lọc hoặc vào `t.search.dlq`.
5. Invalidate `search:categories:tree`.

```json
POST products_write/_update_by_query?conflicts=proceed&requests_per_second=300
{
  "query": {
    "bool": {
      "should": [
        { "term": { "category_id": "cat_001" } },
        { "terms": { "category_id": ["cat_001_a", "cat_001_b"] } }
      ]
    }
  },
  "script": {
    "source": "if (ctx._source.version != null && params.updated_at <= ctx._source.version) { ctx.op = 'none'; return; } ctx._source.category_name = null; ctx._source.category_path = null; ctx._source.category_id = null; ctx._source.product_status = 'HIDDEN'; ctx._source.is_in_stock = false; ctx._source.version = params.updated_at;",
    "params": {
      "updated_at": "2026-03-24T10:00:00Z"
    }
  }
}
```

> **Tại sao Search được phép update `product_status` từ `CATEGORY_DELETED`:** Khác với các event khác, `CATEGORY_DELETED` là event DUY NHẤT mà Product Service đã xử lý Write Model (đổi `status = HIDDEN` tại PostgreSQL) trước khi phát event. Search Service vì thế có quyền phản chiếu thay đổi này lên Elasticsearch trong cùng một bước `_update_by_query`. Việc không còn event `PRODUCT_STATE_CHANGED` riêng cho luồng `CATEGORY_DELETED` giúp loại bỏ ordering problem giữa 2 topic.

### 2.10. SKU_CREATED
**Nguồn:** [Product Events - 3.5](../product/product_events.md#35-sku_created)

**Topic:** `t.product.events`

**Logic xử lý:**
1. Parse payload: `sku_id`, `product_id`, `sku_code`, `original_price`, `promotional_price`, `best_price`, `has_promotion`, `sku_status`, `product_status`, `variant_attributes_json`, `media`, `updated_at`, `created_at`.
2. Enrichment category từ [Category Cache](search_caching_schema.md#12-category-tree-cache).
3. **Data transformation ownership (bắt buộc):** Search Worker transform `variant_attributes_json` từ raw JSON sang token arrays `variant_attributes` cho Elasticsearch.
4. Chuẩn hoá field trạng thái SKU: ưu tiên `sku_status`.
5. **Contract giá:** Payload bắt buộc có `best_price` và `has_promotion`; Search chỉ ghi nhận, không tự tính business pricing.
6. **Scripted upsert document:** Dùng `_update` với `upsert: true` để tạo document mới tại `_id = sku_id`. Guard timestamp: `event.created_at > current_doc.version` (hoặc cho phép insert nếu document chưa tồn tại).
7. Recompute `is_in_stock`: `is_deleted == false AND product_status == 'ACTIVE' AND sku_status == 'ACTIVE'`.

```json
POST products_write/_update/{sku_id}
{
  "scripted_upsert": true,
  "script": {
    "source": "if (ctx._source.version != null && params.created_at <= ctx._source.version) { ctx.op = 'none'; return; } ctx._source.sku_id = params.sku_id; ctx._source.product_id = params.product_id; ctx._source.sku_code = params.sku_code; ctx._source.name = params.product_name; ctx._source.category_id = params.category_id; ctx._source.category_name = params.category_name; ctx._source.original_price = params.original_price; ctx._source.promotional_price = params.promotional_price; ctx._source.best_price = params.best_price; ctx._source.has_promotion = params.has_promotion; ctx._source.sku_status = params.sku_status; ctx._source.product_status = params.product_status; ctx._source.variant_attributes = params.variant_attributes; ctx._source.media = params.media; ctx._source.is_in_stock = (!params.is_deleted && params.product_status == 'ACTIVE' && params.sku_status == 'ACTIVE'); ctx._source.is_deleted = params.is_deleted; ctx._source.version = params.created_at;",
    "params": {
      "sku_id": "sku-uuid-003",
      "product_id": "prod-uuid-001",
      "product_name": "iPhone 15 Pro Max 256GB",
      "category_id": "cat-dien-thoai",
      "category_name": "Điện thoại",
      "sku_code": "IP15PM-256-WHT",
      "original_price": 34990000,
      "promotional_price": null,
      "best_price": 34990000,
      "has_promotion": false,
      "sku_status": "ACTIVE",
      "product_status": "DRAFT",
      "variant_attributes": ["color:Trắng", "storage:256GB"],
      "media": [{ "media_type": "IMAGE", "url": "https://cdn.example.com/ip15-white.jpg", "sort_order": 1 }],
      "is_deleted": false,
      "created_at": "2026-06-10T21:00:00Z"
    }
  },
  "upsert": {
    "sku_id": "sku-uuid-003",
    "product_id": "prod-uuid-001",
    "name": "iPhone 15 Pro Max 256GB",
    "category_id": "cat-dien-thoai",
    "original_price": 34990000,
    "best_price": 34990000,
    "has_promotion": false,
    "sku_status": "ACTIVE",
    "product_status": "DRAFT",
    "is_deleted": false,
    "is_in_stock": false,
    "version": "2026-06-10T21:00:00Z"
  }
}
```

> **Phân biệt với PRODUCT_CREATED:** `PRODUCT_CREATED` nhận `payload.skus[]` và loop để index N documents — dùng khi tạo sản phẩm mới kèm SKUs trong 1 transaction. `SKU_CREATED` chỉ index 1 document duy nhất — dùng khi thêm SKU vào sản phẩm đã tồn tại. Cả hai đều dùng scripted upsert để tạo document mới.

## 3. Chiến Lược Idempotency & Ordering

### 3.1. Chống Duplicate
`PUT _doc/{id}` và `POST _update/{id}` là idempotent khi kết hợp guard timestamp.

### 3.2. Chống Out-of-Order (Timestamp-Only Guard)

**Nguyên tắc đơn giản:** Search Service dùng **DUY NHẤT** `event.updated_at` (ISO 8601 UTC) làm guard ordering cho mọi event stream. Không có ngoại lệ nào dùng sequence ID làm guard.

```text
IF event_type = PRODUCT_DELETED OR event_type = PRODUCT_SKU_DELETED THEN
  -- Tombstone luôn apply guard timestamp
  IF event.updated_at > current_doc.version THEN
    APPLY tombstone
  ELSE
    SKIP stale tombstone
  END
ELSE
  -- Tất cả event khác: guard bằng timestamp
  IF event.updated_at > current_doc.version THEN
    APPLY event
  ELSE
    SKIP event (stale)
  END
END
```

**Chi tiết guard theo event type:**

| Event | Guard | Chi tiết |
|---|---|---|
| `PRODUCT_CREATED` | `updated_at > version` | Scripted upsert — Insert nếu chưa có doc |
| `PRODUCT_UPDATED` | `updated_at > version` | Bulk partial update theo `sku_ids[]` — chỉ update product-level fields, không ảnh hưởng SKU-level fields |
| `PRODUCT_STATE_CHANGED` | `updated_at > version` | `_update_by_query`, recompute `is_in_stock` |
| `SKU_UPDATED` | `updated_at > version` | Partial update theo `changed_fields[]`: price fields, `sku_status`, `product_status`. Bulk `_update_by_query` (FSM trigger) hoặc partial update (admin). Đồng thời update `product_status`/`is_in_stock` nếu `product_status_changed = true` |
| `SKU_CREATED` | `created_at > version` (hoặc insert nếu doc chưa tồn tại) | Scripted upsert — tạo document SKU mới trên Elasticsearch |
| `PRODUCT_DELETED` | `updated_at > version` | Tombstone — bảo đảm delete |
| `SKU_DELETED` | `updated_at > version` (từ `payload.updated_at`) | Tombstone SKU document |
| `CATEGORY_UPDATED` | *(không guard)* | Category update hiếm khi race; apply bất kỳ |
| `CATEGORY_DELETED` | `updated_at > version` | Nullify category metadata + update `product_status = HIDDEN` |
| `REVIEW_METRICS_UPDATED` | `updated_at > review_metrics_updated_at` | Guard riêng field |
| `ORDER_PRODUCT_METRICS_UPDATED` | `updated_at > sales_metrics_updated_at` | Guard riêng field |

**Về precision timestamp:** Elasticsearch `date` type lưu precision đến millisecond. Product Service phải đảm bảo `updated_at` ghi vào PostgreSQL và payload event là TIMESTAMPTZ có precision tương đương. Nếu hai event có cùng timestamp (cùng millisecond), event đến sau trong Kafka stream sẽ có cơ hội được apply nếu guard không reject — đây là trade-off chấp nhận được vì Kafka đảm bảo ordering trong cùng partition.


### 3.3. Xử Lý Lỗi
- **Elasticsearch unreachable (timeout/429/503):** nack event để Kafka retry theo policy transient.
- **`document_missing_exception` cho partial update:** không được bỏ qua. Retry inline tối đa 2 attempt tổng (lần đầu + 1 retry), sau đó đẩy vào `t.search.retry` để self-healing fetch full product; quá ngưỡng tổng mới vào `t.search.dlq`.
- **Retry budget chuẩn (thống nhất toàn Search):**
  - Inline consumer: tối đa 2 attempt tổng.
  - Retry replayer (`t.search.retry`): tối đa 3 attempt.
  - Tổng tối đa: 5 attempt trước khi park `t.search.dlq`.
- **Lỗi liên tiếp vượt ngưỡng:** đẩy vào `t.search.dlq` và cảnh báo vận hành.
