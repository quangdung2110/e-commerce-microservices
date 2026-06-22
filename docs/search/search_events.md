# Search Service - Events Consumed

**Liên kết nhanh (Related Documents):**
*   [Elasticsearch Schema (Read Store)](search_elasticsearch_schema.md)
*   [Workflows (API & Data Flow)](search_workflow.md)
*   [Background Workers & Jobs](search_workers.md)
*   [Architecture Recommendations](search_architecture_recommendations.md)

**Cross-Service References:**
*   [Product Service - Events Structure (Source of Truth)](../product/product_events.md)
*   [Product Service - Workers (Outbox Producer)](../product/product_workers.md)

---

Tài liệu này định nghĩa toàn bộ luồng sự kiện mà Search Service tiêu thụ. Search Service là Read Model thuần túy, không phát sinh event nghiệp vụ mới.

> **Nguyên tắc đơn giản hoá:** Đa số event dùng **CHUNG 1 script PATCH** với **1 guard field `version`** duy nhất. Riêng `SKU_CREATED` dùng biến thể script (dùng `created_at` thay vì `updated_at`).
>
> **Policy shared-topic (bắt buộc):** nếu consumer nhận event hợp lệ theo envelope nhưng `event_type` không nằm trong allowlist của consumer group đó thì phải **ignore + ACK + ghi metric** (`search_event_ignored_total{topic,event_type}`), không đẩy DLQ.
>
> **Guard Strategy — Single Timestamp (mỗi event dùng đúng 1 timestamp làm guard):** Search Service sử dụng **đúng 1 trường timestamp** làm guard ordering cho mỗi event, cụ thể:
> - Hầu hết event: `event.updated_at` (ISO 8601 UTC) — đại diện cho thời điểm entity thay đổi lần cuối.
> - Riêng `SKU_CREATED`: `event.created_at` (ISO 8601 UTC) — vì đây là event khởi tạo, entity chưa có `updated_at`; dùng `created_at` đảm bảo idempotency cho upsert.
>
> Trường `version` trong Elasticsearch document lưu timestamp ISO 8601 (là `updated_at` cho đa số event, là `created_at` cho `SKU_CREATED`). Quy tắc so sánh thống nhất: `event_timestamp > current_doc.version`. Phương pháp này đơn giản, nhất quán — mỗi event chỉ cần nhớ đúng 1 timestamp để chống out-of-order.
>

## 1. Bản Đồ Kafka Topics

| Topic Name | Consumer Group | Producer | Events trong Topic | Mô tả |
|---|---|---|---|---|
| `t.product.events` | `search-product-indexer` | Product Service | `PRODUCT_CREATED`, `PRODUCT_UPDATED`, `PRODUCT_STATUS_CHANGED`, `SKU_UPDATED`, `SKU_CREATED`, `PRODUCT_DELETED`, `SKU_DELETED` | Topic chính cho Product projection |
| `t.category.events` | `search-product-indexer` | Product Service | `CATEGORY_CREATED`, `CATEGORY_UPDATED`, `CATEGORY_DELETED` | Topic cho category projection (cùng group) |
| `t.review.events` | `search-product-indexer` | Review Service | `REVIEW_METRICS_UPDATED` | Đồng bộ rating theo `product_id` |
| `t.search.dlq` | `search-dlq-processor` | Search Service (internal) | failed envelopes (parking lot) | Kênh lỗi không tự phục hồi để replay có kiểm soát |

> **Số consumer group đã giảm từ 3 xuống 2 (Phase 21):**
> - `search-product-indexer` gộp product + category + review events (3 topics vào 1 group). Admin events không nằm trong group này (xem ghi chú trên).
> - `search-dlq-processor` riêng cho `t.search.dlq` (không gộp được vì DLQ có cơ chế replay thủ công, tách biệt hoàn toàn với group trên).
> - Bỏ `search-metrics-indexer` (gộp vào `search-product-indexer` — chỉ còn 1 event type `REVIEW_METRICS_UPDATED`, gộp không gây khó khăn).
>
> **Số retry topic đã giảm từ 2 xuống 1:** Chỉ còn `t.search.dlq`. Bỏ `t.search.retry` (self-heal fetch full product). Lỗi `document_missing_exception` hiếm gặp; nếu xảy ra, đẩy thẳng DLQ + cảnh báo để admin replay thủ công.

## 2. Script PATCH Chung (Áp Dụng Cho Đa Số Event)

**Phạm vi áp dụng:** Đa số event (PRODUCT_*, SKU_UPDATED, CATEGORY_*, REVIEW_*) dùng script dưới đây. Producer side wrap event payload thành cùng format `{ event_id, updated_at, indexed_at, fields: {...} }`.

**NGOẠI LỆ — KHÔNG dùng script này:**
- `SKU_CREATED`: dùng biến thể `created_at` (xem §3.5).

```painless
if (ctx._source.version != null && params.updated_at <= ctx._source.version) {
  ctx.op = 'none';
  return;
}
for (entry in params.fields) {
  ctx._source[entry.key] = entry.value;
}
ctx._source.version = params.updated_at;
ctx._source.indexed_at = params.indexed_at;
ctx._source.last_event_id = params.event_id;
```

**Ý nghĩa:**
- Nếu doc chưa có `version` (lần đầu insert) → apply bình thường.
- Nếu `event.updated_at <= current_doc.version` → skip (event cũ/stale).
- Ngược lại → PATCH các field trong `params.fields` lên `_source`, cập nhật `version`, `indexed_at`, `last_event_id`.

**Đặc tính:**
- **Idempotent:** Cùng event gửi nhiều lần → chỉ apply lần đầu.
- **Out-of-order safe:** Event trễ so với doc hiện tại → skip.
- **Partial update:** Chỉ field có trong `params.fields` mới được ghi đè.

## 3. Chi Tiết Xử Lý Theo Event Type

### 3.1. PRODUCT_CREATED

**Logic xử lý:**
1. Parse payload, extract Product + danh sách SKUs.
2. Validate: `skus[]` bắt buộc phải có; mỗi `sku` phải có `best_price` và `has_promotion`. Nếu thiếu → DLQ.
3. Chuẩn hoá `skus[].status -> skus[].sku_status` nếu cần (backward compat).
4. Enrichment category từ `categories` alias (read).
5. **Data transformation:** Transform `attributes_json` → `searchable_attributes` token array; `variant_attributes_json` → `variant_attributes` token array.
6. Loop qua `skus[]`, với mỗi SKU gọi script PATCH ở trên (với `scripted_upsert: true`):
   ```json
   POST products_write/_update/{sku_id}
   {
     "scripted_upsert": true,
     "script": {
       "source": "if (ctx._source.version != null && params.updated_at <= ctx._source.version) { ctx.op = 'none'; return; } for (entry in params.fields) { ctx._source[entry.key] = entry.value; } ctx._source.version = params.updated_at; ctx._source.indexed_at = params.indexed_at; ctx._source.last_event_id = params.event_id;",
       "params": {
          "fields": {
            "sku_id": "sku-uuid-001",
            "product_id": "prod-uuid-001",
            "admin_name": "Platform Admin",
            "name": "iPhone 15 Pro Max 256GB",
            "category_id": "cat-smartphone",
            "category_name": "Điện thoại",
            "product_status": "ACTIVE",
            "sku_status": "ACTIVE",
            "original_price": 34990000,
            "promotional_price": 28891500,
            "best_price": 28891500,
            "has_promotion": true,
            "is_in_stock": true,
            "is_deleted": false,
            "searchable_attributes": ["thuonghieu:apple"],
            "variant_attributes": ["mausac:đen", "bonho:256GB"],
            "thumbnail_url": "https://cdn.example.com/ip15.jpg",
            "created_at": "2026-02-28T03:51:00Z",
            "updated_at": "2026-02-28T03:51:00Z"
          },
          "updated_at": "2026-02-28T03:51:00Z",
          "indexed_at": "2026-02-28T03:51:01Z",
          "event_id": "evt-001"
       }
     },
     "upsert": {
       "sku_id": "sku-uuid-001",
       "product_id": "prod-uuid-001",
       "version": "2026-02-28T03:51:00Z"
     }
   }
   ```

### 3.2. PRODUCT_UPDATED

**Logic xử lý:**
1. Parse payload: extract `product_id`, product-level fields, `sku_ids[]`.
2. Nếu payload chứa `category_id` mới: **enrichment category_name** từ `categories` index (lookup theo `category_id` mới); nếu lookup miss → DLQ.
3. Loop qua `sku_ids[]`, với mỗi SKU gọi script PATCH (không upsert, partial update).
4. Payload `fields` chỉ chứa product-level fields (`name`, `description`, `category_id`, `category_name`, `category_path`, `searchable_attributes`, `thumbnail_url`...).
5. Các SKU-level fields (giá, status) **không bị ảnh hưởng** bởi event này.

```json
POST products_write/_update/{sku_id}
{
  "script": {
    "source": "...",
    "params": {
      "fields": {
        "name": "iPhone 15 Pro Max 256GB (Updated)",
        "description": "Mô tả cập nhật...",
        "category_id": "cat-dien-thoai",
        "category_name": "Điện thoại",
        "category_path": "dien-tu/dien-thoai",
        "searchable_attributes": ["thuonghieu:apple", "xuatxu:vietnam"],
        "thumbnail_url": "https://cdn.example.com/ip15-main-updated.jpg"
      },
      "updated_at": "2026-02-28T04:15:00Z",
      "indexed_at": "2026-02-28T04:15:01Z",
      "event_id": "evt-002"
    }
  }
}
```

> **Tại sao không dùng `_update_by_query`:** Document Elasticsearch có `_id = sku_id`. `_update_by_query` update TẤT CẢ documents matching `product_id` — bao gồm cả các SKU không thuộc payload này. Loop theo `sku_ids[]` đảm bảo đúng intent.

### 3.3. PRODUCT_STATUS_CHANGED

**Logic xử lý:**
1. `_update_by_query` theo `product_id` (KHÔNG dùng script PATCH thông thường; cần recompute `is_in_stock`).
2. Bắt buộc throttle `requests_per_second=300` + `conflicts=proceed`.
3. Guard bằng script đặc thù: skip nếu `event.updated_at <= current_doc.version`.

```json
POST products_write/_update_by_query?conflicts=proceed&requests_per_second=300
{
  "query": { "term": { "product_id": "prod-uuid-001" } },
  "script": {
    "source": "if (ctx._source.version != null && params.updated_at <= ctx._source.version) { ctx.op = 'none'; return; } ctx._source.product_status = params.new_status; ctx._source.is_in_stock = (!ctx._source.is_deleted && ctx._source.product_status == 'ACTIVE' && ctx._source.sku_status == 'ACTIVE'); ctx._source.version = params.updated_at; ctx._source.indexed_at = params.indexed_at; ctx._source.last_event_id = params.event_id;",
    "params": {
      "new_status": "ACTIVE",
      "updated_at": "2026-02-28T05:00:00Z",
      "indexed_at": "2026-02-28T05:00:01Z",
      "event_id": "evt-003"
    }
  }
}
```

### 3.4. SKU_UPDATED

**Logic xử lý:**
1. Parse payload: `sku_id`, `product_id`, SKU fields, `changed_fields[]`, `product_status_changed`, `updated_at`.
2. **Partial update theo `changed_fields`:** chỉ include field có trong `changed_fields` vào `params.fields`.
3. Nếu `changed_fields` chứa `sku_status` hoặc `product_status`: cần recompute `is_in_stock` → dùng script đặc thù như `PRODUCT_STATUS_CHANGED` (cũng qua `_update_by_query` nếu `product_status_changed=true`).
4. Nếu `product_status_changed = true`: đồng thời update `product_status` trên toàn bộ SKU documents của product (qua `_update_by_query`).

**Partial update cho price fields:**
```json
POST products_write/_update/{sku_id}
{
  "script": {
    "source": "if (ctx._source.version != null && params.updated_at <= ctx._source.version) { ctx.op = 'none'; return; } for (entry in params.fields) { ctx._source[entry.key] = entry.value; } ctx._source.version = params.updated_at; ctx._source.indexed_at = params.indexed_at; ctx._source.last_event_id = params.event_id;",
    "params": {
      "fields": {
        "original_price": 33990000,
        "promotional_price": 28891500,
        "best_price": 28891500,
        "has_promotion": true
      },
      "updated_at": "2026-02-28T06:00:00Z",
      "indexed_at": "2026-02-28T06:00:01Z",
      "event_id": "evt-sku-001"
    }
  }
}
```

### 3.5. SKU_CREATED

**Logic xử lý:**
1. Parse payload: `sku_id`, `product_id`, SKU fields, `updated_at`, `created_at`.
2. Enrichment category từ `categories` alias (read).
3. Transform `variant_attributes_json` → `variant_attributes` token array.
4. **Biến thể** của script PATCH chung (dùng `created_at` thay vì `updated_at`) với `scripted_upsert: true`, guard: `event.created_at > current_doc.version` (hoặc insert nếu doc chưa tồn tại).

```json
POST products_write/_update/{sku_id}
{
  "scripted_upsert": true,
  "script": {
    "source": "if (ctx._source.version != null && params.created_at <= ctx._source.version) { ctx.op = 'none'; return; } for (entry in params.fields) { ctx._source[entry.key] = entry.value; } ctx._source.version = params.created_at; ctx._source.indexed_at = params.indexed_at; ctx._source.last_event_id = params.event_id;",
    "params": {
      "fields": {
        "sku_id": "sku-uuid-003",
        "product_id": "prod-uuid-002",
        "name": "iPhone 15 Pro Max 512GB",
        "category_id": "cat-smartphone",
        "category_name": "Điện thoại",
        "category_path": "dien-tu/dien-thoai",
        "product_status": "ACTIVE",
        "sku_status": "ACTIVE",
        "original_price": 39990000,
        "promotional_price": 34990000,
        "best_price": 34990000,
        "has_promotion": true,
        "is_in_stock": true,
        "is_deleted": false,
        "variant_attributes": ["mausac:trang", "bonho:512GB"],
        "thumbnail_url": "https://cdn.example.com/ip15-512.jpg",
        "created_at": "2026-03-01T08:00:00Z",
        "updated_at": "2026-03-01T08:00:00Z"
      },
      "created_at": "2026-03-01T08:00:00Z",
      "indexed_at": "2026-03-01T08:00:01Z",
      "event_id": "evt-sku-created-001"
    }
  },
  "upsert": {
    "sku_id": "sku-uuid-003",
    "product_id": "prod-uuid-002",
    "version": "2026-03-01T08:00:00Z"
  }
}
```

### 3.6. PRODUCT_DELETED / SKU_DELETED

**Hành động Elasticsearch:** Tombstone (set `is_deleted=true`, `is_in_stock=false`).

- **`PRODUCT_DELETED`**: Payload chứa mảng `sku_ids`. Worker loop theo từng `sku_id` và gọi script PATCH với `scripted_upsert: true`, fields = `{ is_deleted: true, is_in_stock: false, deleted_at: ... }`.
- **`SKU_DELETED`**: Payload chứa **một** `sku_id`. Chỉ update đúng document `_id = sku_id` đó.

```json
POST products_write/_update/{sku_id}
{
  "scripted_upsert": true,
  "script": {
    "source": "if (ctx._source.version != null && params.updated_at <= ctx._source.version) { ctx.op = 'none'; return; } ctx._source.is_deleted = true; ctx._source.deleted_at = params.deleted_at; ctx._source.is_in_stock = false; ctx._source.version = params.updated_at; ctx._source.indexed_at = params.indexed_at; ctx._source.last_event_id = params.event_id;",
    "params": {
      "updated_at": "2026-03-24T10:00:00Z",
      "deleted_at": "2026-03-24T10:00:00Z",
      "indexed_at": "2026-03-24T10:00:01Z",
      "event_id": "evt-del-002"
    }
  },
  "upsert": {
    "sku_id": "sku-uuid-002",
    "product_id": "prod-uuid-001",
    "is_deleted": true,
    "is_in_stock": false,
    "version": "2026-03-24T10:00:00Z"
  }
}
```

> **Cronjob `Draft Cleanup Job`** (xem [Workers](search_workers.md#5-draft-cleanup-job)) sẽ dọn các Tombstone cũ sau 30 ngày.

### 3.7. CATEGORY_CREATED

**Logic xử lý:**
1. Parse payload: `category_id`, `name`, `slug`, `parent_id`, `level`, `is_leaf`, `path`.
2. Validate `path` bắt buộc phải có; nếu thiếu → DLQ.
3. Index document vào `categories_write` alias (đang trỏ tới `categories_v1`) qua script PATCH với `scripted_upsert: true`.

### 3.8. CATEGORY_UPDATED

**Logic xử lý:**
1. Nhận `category_id`, `new_name`, `new_path`.
2. Hai hành động song song:
   - **Update `categories_write`** qua script PATCH (`scripted_upsert: true`).
   - **Update `products_write`** qua `_update_by_query` theo `category_id` để đồng bộ `category_name` và `category_path` cho toàn bộ SKU docs. Bắt buộc `conflicts=proceed` + `requests_per_second=300`.

### 3.9. CATEGORY_DELETED

**Logic xử lý:**
1. Nhận `category_id`, `descendant_category_ids`, `updated_at`.
2. Hai hành động song song:
   - **Tombstone document** trong `categories_write` qua script PATCH với `scripted_upsert: true` (set `is_deleted = true`, `deleted_at = now`). KHÔNG gọi `_delete` trực tiếp để chặn zombie update từ event trễ.
   - **`_update_by_query`** trên `products_write` để nullify `category_*` fields và update `product_status = 'HIDDEN'` cho toàn bộ SKU docs thuộc các category bị xoá. Guard bằng `updated_at`. Bắt buộc `conflicts=proceed` + `requests_per_second=300`.

### 3.10. REVIEW_METRICS_UPDATED

**Logic xử lý:**
1. Parse `product_id`, `average_rating`, `total_reviews`, `updated_at`.
2. `_update_by_query` theo `product_id` để update `product_rating_avg`, `product_rating_count`.
3. Guard bằng `updated_at` (script đặc thù vì metrics update không qua script PATCH thường).
4. Bắt buộc `conflicts=proceed` + `requests_per_second=300`.

```json
POST products_write/_update_by_query?conflicts=proceed&requests_per_second=300
{
  "query": { "term": { "product_id": "prod-uuid-001" } },
  "script": {
    "source": "if (ctx._source.version != null && params.updated_at <= ctx._source.version) { ctx.op = 'none'; return; } ctx._source.product_rating_avg = params.average_rating; ctx._source.product_rating_count = params.total_reviews; ctx._source.version = params.updated_at; ctx._source.indexed_at = params.indexed_at; ctx._source.last_event_id = params.event_id;",
    "params": {
      "average_rating": 4.75,
      "total_reviews": 1251,
      "updated_at": "2026-03-28T10:05:00Z",
      "indexed_at": "2026-03-28T10:05:01Z",
      "event_id": "evt-review-001"
    }
  }
}
```

> **Về admin events (Admin Service):** Search Service **không consume** `ADMIN_PROFILE_UPDATED` / `ADMIN_STATUS_CHANGED`. Hệ thống chỉ có 1 admin duy nhất (toàn quyền, không bao giờ bị ban), nên không cần đồng bộ `admin_name` / `admin_status` realtime lên Elasticsearch. `admin_name` được snapshot 1 lần từ `PRODUCT_CREATED` payload lúc index; khi admin đổi tên hiển thị → chạy [Full Reindex Job](search_workers.md#2-full-reindex-job-zero-downtime-3-pha-đơn-giản) để refresh toàn bộ index. Nếu trong tương lai cần multi-admin hoặc admin có thể bị suspend/banned, cần thêm lại `_update_by_query theo admin_id` với guard `admin_*_version` như pattern cũ.

## 4. Chiến Lược Idempotency & Ordering

### 4.1. Chống Duplicate
Script PATCH + guard timestamp = idempotent. Gửi lại event nhiều lần → chỉ apply lần đầu.

### 4.2. Chống Out-of-Order (Single Timestamp Guard)

**Nguyên tắc đơn giản:** Mỗi event dùng **đúng 1 timestamp** làm guard. Đa số event dùng `event.updated_at` so với `current_doc.version`. Riêng `SKU_CREATED` dùng `event.created_at` (event khởi tạo).

```text
IF event_type IN (PRODUCT_DELETED, SKU_DELETED) THEN
  -- Tombstone cũng apply guard
  IF event.updated_at > current_doc.version THEN
    APPLY tombstone
  ELSE
    SKIP stale tombstone
  END
ELSE IF event_type = SKU_CREATED THEN
  IF event.created_at > current_doc.version THEN
    APPLY upsert
  ELSE
    SKIP event (stale)
  END
ELSE
  -- Tất cả event khác: guard bằng updated_at
  IF event.updated_at > current_doc.version THEN
    APPLY event
  ELSE
    SKIP event (stale)
  END
END
```

**Guard áp dụng đồng nhất:**

| Event | Guard |
|---|---|
| `PRODUCT_CREATED` | `updated_at > version` (scripted upsert) |
| `PRODUCT_UPDATED` | `updated_at > version` |
| `PRODUCT_STATUS_CHANGED` | `updated_at > version` |
| `SKU_UPDATED` | `updated_at > version` |
| `SKU_CREATED` | `created_at > version` (scripted upsert) |
| `PRODUCT_DELETED` / `SKU_DELETED` | `updated_at > version` |
| `CATEGORY_*` | `updated_at > version` |
| `REVIEW_METRICS_UPDATED` | `updated_at > version` |

> **Trade-off chấp nhận:** Metrics update (rating/sold) có thể bị skip nếu timestamp trùng millisecond với update trước đó (vì guard so sánh `>` chứ không phải `>=`). Chấp nhận được vì Kafka đảm bảo ordering trong cùng partition và metrics có độ trễ vài giây là chấp nhận được.

### 4.3. Xử Lý Lỗi

- **Elasticsearch unreachable (timeout/429/503):** nack event để Kafka retry theo policy transient.
- **`document_missing_exception` cho partial update:** Không tự fetch full product để self-heal (đã bỏ `t.search.retry`). Đẩy thẳng `t.search.dlq` + cảnh báo để admin replay thủ công.
- **Lỗi dữ liệu không tự hồi phục (mapping conflict/schema poison):** đẩy `t.search.dlq` kèm payload + metadata.
- **Lỗi liên tiếp vượt ngưỡng:** đẩy `t.search.dlq` + alert vận hành.