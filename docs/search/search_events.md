# Search Service - Events Consumed

**Liên kết nhanh (Related Documents):**
*   [Service Overview (Tech Stack, Deployment, Observability)](search_service_overview.md)
*   [Elasticsearch Schema (Read Store)](search_elasticsearch_schema.md)
*   [Workflows (API & Data Flow)](search_workflow.md)
*   [Background Workers & Jobs](search_workers.md)
*   [Architecture Recommendations](search_architecture_recommendations.md)

**Cross-Service References:**
*   [Product Service - Events Structure (Source of Truth)](../product/product_events.md)
*   [Product Service - Workers (Outbox Producer)](../product/product_workers.md)
*   [Review Service - Events Structure](../review/review_events.md)
*   [Service Contract Matrix (SSOT)](../_contracts/CONTRACT_MATRIX.md)
*   [Idempotency Guidelines (SSOT)](../_contracts/IDEMPOTENCY_GUIDELINES.md)
*   [CHANGELOG — Phase 22b (Search DLQ Removal)](../_contracts/CHANGELOG.md#phase-22b--search-service--remove-dlq)

---

Tài liệu này định nghĩa toàn bộ luồng sự kiện mà Search Service tiêu thụ. Search Service là Read Model thuần túy, không phát sinh event nghiệp vụ mới.

> **Trạng thái Phase đã áp dụng (đồng bộ với [CHANGELOG](../_contracts/CHANGELOG.md)):**
> - **Phase 19** (Order Service Simplification): Search Service đã loại bỏ handler riêng cho `PRODUCT_STATUS_CHANGED`. Mọi thay đổi `product_status` được propagate qua `SKU_UPDATED` với cờ `product_status_changed=true`. Xem [§3.3, §3.4](#33-product_status_changed-via-sku_updated-phase-19).
> - **Phase 21** (Order Service Schema Simplification): Search Service đã loại bỏ `t.search.metrics.events` topic + `search-metrics-indexer` consumer group + field `product_sold_count` + sort `best_selling`. Search Service giờ chỉ subscribe 3 topics.
> - **Phase 22b** (Search Service — Remove DLQ): Search Service đã loại bỏ hoàn toàn DLQ topic `t.search.dlq` + consumer group `search-dlq-processor`. Mọi lỗi không tự hồi phục được xử lý bằng `log error + metric + skip + ACK`. Xem [CHANGELOG §Phase 22b Search](../_contracts/CHANGELOG.md#phase-22b--search-service--remove-dlq).

> **Nguyên tắc đơn giản hoá:** Đa số event dùng **CHUNG 1 script PATCH** với **1 guard field `version`** duy nhất. Riêng `SKU_CREATED` dùng biến thể script (dùng `created_at` thay vì `updated_at`).
>
> **Guard Strategy — Single Timestamp (mỗi event dùng đúng 1 timestamp làm guard):** Search Service sử dụng **đúng 1 trường timestamp** làm guard ordering cho hầu hết event, cụ thể:
> - Hầu hết event: `event.updated_at` (ISO 8601 UTC) — đại diện cho thời điểm entity thay đổi lần cuối.
> - Riêng `SKU_CREATED`: `event.created_at` (ISO 8601 UTC) — vì đây là event khởi tạo, entity chưa có `updated_at`; dùng `created_at` đảm bảo idempotency cho upsert.
> - **NGOẠI LỆ (Phase 19, 21):** `REVIEW_METRICS_UPDATED` và `PRODUCT_STATUS_CHANGED` (qua `SKU_UPDATED.product_status_changed=true`) **KHÔNG dùng script guard** — chỉ dựa vào Kafka partition ordering. Partition key `product_id` đảm bảo events cùng product đến theo đúng thứ tự.
>
> Trường `version` trong Elasticsearch document lưu timestamp ISO 8601 (là `updated_at` cho đa số event, là `created_at` cho `SKU_CREATED`). Quy tắc so sánh thống nhất: `event_timestamp > current_doc.version`. Phương pháp này đơn giản, nhất quán — mỗi event chỉ cần nhớ đúng 1 timestamp để chống out-of-order.

## 1. Bản Đồ Kafka Topics

| Topic Name | Consumer Group | Producer | Events trong Topic | Mô tả |
|---|---|---|---|---|
| `t.product.events` | `search-product-indexer` | Product Service | `PRODUCT_CREATED`, `PRODUCT_UPDATED`, `SKU_UPDATED` (bao gồm `product_status_changed=true`), `PRODUCT_DELETED`, `SKU_DELETED` | Topic chính cho Product projection. **Lưu ý (Phase 19):** `PRODUCT_STATUS_CHANGED` đã được gộp vào `SKU_UPDATED` với cờ `product_status_changed=true`; không còn event riêng. |
| `t.category.events` | `search-product-indexer` | Product Service | `CATEGORY_CREATED`, `CATEGORY_UPDATED`, `CATEGORY_DELETED` | Topic cho category projection (cùng group) |
| `t.review.events` | `search-product-indexer` | Review Service | `REVIEW_METRICS_UPDATED` | Đồng bộ rating theo `product_id`. Chỉ 1 event type — Search Service consumer cho mọi review-related metric. |

> **Số consumer group = 1 (Phase 21+):**
> - `search-product-indexer` gộp product + category + review events (3 topics vào 1 group).
> - **KHÔNG có DLQ (Phase 22b).** Mọi lỗi không tự hồi phục (document_missing_exception, mapping conflict, payload validation fail) đều được xử lý bằng `log error + skip` rồi ACK event bình thường.
> - **KHÔNG subscribe `t.admin.events`** (hệ thống single-admin, không lưu field admin trên document).
> - **KHÔNG subscribe `t.search.metrics.events`** (Phase 21 — topic này đã bị loại bỏ cùng bảng `order_product_metrics` ở Order Service).

## 2. Script PATCH Chung (Áp Dụng Cho Đa Số Event)

**Phạm vi áp dụng:** Đa số event (PRODUCT_*, SKU_UPDATED, CATEGORY_*, REVIEW_*) dùng script dưới đây. Producer side wrap event payload thành cùng format `{ event_id, updated_at, indexed_at, fields: {...} }`.

**NGOẠI LỆ — KHÔNG dùng script này:**
- `SKU_CREATED`: dùng biến thể `created_at` (xem §3.5).
- `REVIEW_METRICS_UPDATED`: không dùng script guard — chỉ `_update_by_query` không script (xem §3.10).
- `PRODUCT_STATUS_CHANGED` (qua `SKU_UPDATED.product_status_changed=true`): không dùng script guard thông thường — dùng script đặc thù recompute `is_in_stock` (xem §3.3).

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
2. Validate: `skus[]` bắt buộc phải có; mỗi `sku` phải có `best_price` và `has_promotion`. Nếu thiếu → log warning + metric `search_product_payload_invalid_total` + skip.
3. Chuẩn hoá `skus[].status -> skus[].sku_status` nếu cần (backward compat).
4. Enrichment category từ `categories` alias (read): lookup theo `category_id` để lấy `category_name` và `category_path`. Nếu lookup miss → bỏ qua `category_name`/`category_path` (không ghi vào doc), log warning + metric `search_category_enrichment_miss_total{topic,event_type,category_id}`; `CATEGORY_UPDATED`/`CATEGORY_CREATED` sau đó sẽ populate lại.
5. **Data transformation:** Transform `attributes_json` → `searchable_attributes` token array; `variant_attributes_json` → `variant_attributes` token array.
6. **Resolve `thumbnail_url`:** Với mỗi SKU, lấy `product.media[0].thumbnail_url` (ưu tiên), fallback `product.media[0].url`, fallback cuối cùng `skus[i].media[0].url`. Nếu tất cả rỗng → để trống.
7. **Derive `is_in_stock`:** Tính ở application logic trước khi gọi ES: `is_in_stock = (!is_deleted && product_status == 'ACTIVE' && sku_status == 'ACTIVE')`. (Cùng công thức với script §3.3.)
8. Loop qua `skus[]`, với mỗi SKU gọi script PATCH ở trên (với `scripted_upsert: true`):
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
            "name": "iPhone 15 Pro Max 256GB",
            "category_id": "cat-smartphone",
            "category_name": "Điện thoại",
            "category_path": "dien-tu/dien-thoai",
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
9. **Sau khi xử lý xong SKU documents:** Nếu `product_status == 'ACTIVE'` (chỉ áp dụng cho product visibility), trigger `_update_by_query` trên `categories` index theo `category_id` để tăng `product_count` (xem §3.11).

### 3.2. PRODUCT_UPDATED

**Logic xử lý:**
1. Parse payload: extract `product_id`, product-level fields, `sku_ids[]`.
2. Nếu payload chứa `category_id` mới: **enrichment từ `categories` index** (lookup theo `category_id` mới) để lấy `category_name` và `category_path`. Nếu miss → skip enrichment (giữ nguyên giá trị cũ trong doc, log warning + metric `search_category_enrichment_miss_total`).
3. **Data transformation:** Transform `attributes_json` (nếu có trong payload) → `searchable_attributes` token array. (Cùng rule với §3.1.)
4. **Resolve `thumbnail_url`:** Lấy `product.media[0].thumbnail_url` (ưu tiên), fallback `product.media[0].url`. Nếu rỗng → giữ nguyên giá trị cũ trong doc (không xoá).
5. Loop qua `sku_ids[]`, với mỗi SKU gọi script PATCH (không upsert, partial update).
6. Payload `fields` chỉ chứa product-level fields (`name`, `description`, `category_id`, `category_name`, `category_path`, `searchable_attributes`, `thumbnail_url`...).
7. Các SKU-level fields (giá, status) **không bị ảnh hưởng** bởi event này.

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

### 3.3. PRODUCT_STATUS_CHANGED (via SKU_UPDATED, Phase 19+)

> **Phase 19 note:** Kể từ Phase 19, Product Service **KHÔNG phát event `PRODUCT_STATUS_CHANGED` riêng** nữa. Mọi thay đổi `product_status` được đính kèm trong `SKU_UPDATED` thông qua 2 field: `product_status_changed: true` + `new_product_status: "ACTIVE" | "HIDDEN" | ...`. Search Service xử lý logic này **trong handler SKU_UPDATED** (xem §3.4), KHÔNG có handler riêng cho `PRODUCT_STATUS_CHANGED` nữa.
>
> Logic được trình bày dưới đây chỉ để tham khảo lịch sử — KHÔNG được implement dưới dạng handler riêng.

**Logic tham khảo (kích hoạt khi SKU_UPDATED.product_status_changed=true):**

1. `_update_by_query` theo `product_id` (KHÔNG dùng script PATCH thông thường; cần recompute `is_in_stock`).
2. Bắt buộc throttle `requests_per_second=300` + `conflicts=proceed`.
3. **KHÔNG guard bằng script `updated_at > version`** (Phase 19 đơn giản hoá): chỉ dựa vào partition ordering. Vì partition key của `SKU_UPDATED` là `sku_id`, các event liên quan đến cùng product có thể đến trên nhiều partition; tuy nhiên logic FSM đảm bảo state transition từ Product Service là deterministic, nên không cần guard script.

```json
POST products_write/_update_by_query?conflicts=proceed&requests_per_second=300
{
  "query": { "term": { "product_id": "prod-uuid-001" } },
  "script": {
    "source": "ctx._source.product_status = params.new_status; ctx._source.is_in_stock = (!ctx._source.is_deleted && ctx._source.product_status == 'ACTIVE' && ctx._source.sku_status == 'ACTIVE'); ctx._source.indexed_at = params.indexed_at; ctx._source.last_event_id = params.event_id;",
    "params": {
      "new_status": "ACTIVE",
      "indexed_at": "2026-02-28T05:00:01Z",
      "event_id": "evt-003"
    }
  }
}
```

4. **Sau khi xử lý xong:** Nếu `new_status == 'ACTIVE'`, trigger `_update_by_query` trên `categories` index theo `category_id` để tăng `product_count`. Nếu `new_status == 'HIDDEN'`, giảm `product_count` (xem §3.11).

### 3.4. SKU_UPDATED

**Logic xử lý:**
1. Parse payload: `sku_id`, `product_id`, SKU fields, `changed_fields[]`, `product_status_changed`, `new_product_status`, `updated_at`.
2. **Partial update theo `changed_fields`:** chỉ include field có trong `changed_fields` vào `params.fields`.
3. Nếu `changed_fields` chứa `sku_status` hoặc `product_status`: cần recompute `is_in_stock` → dùng script đặc thù như `PRODUCT_STATUS_CHANGED` (cũng qua `_update_by_query` nếu `product_status_changed=true`).
4. Nếu `product_status_changed = true`: đồng thời update `product_status` trên toàn bộ SKU documents của product (qua `_update_by_query`) — logic §3.3.
5. **Nếu `changed_fields` chứa `media`:** resolve `thumbnail_url` mới theo rule §3.1 (ưu tiên `product.media[0].thumbnail_url`, fallback `product.media[0].url`, fallback cuối `skus[i].media[0].url` — `i` là SKU tương ứng), thêm vào `params.fields` rồi PATCH lên doc SKU đó. Nếu cả `payload.media` rỗng → giữ nguyên `thumbnail_url` cũ (không xoá).

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
2. Enrichment category từ `categories` alias (read): lookup theo `category_id` để lấy `category_name` và `category_path`. Nếu lookup miss → bỏ qua `category_name`/`category_path`, log warning + metric `search_category_enrichment_miss_total{topic,event_type,category_id}`; `CATEGORY_UPDATED`/`CATEGORY_CREATED` sau đó sẽ populate lại.
3. Transform `variant_attributes_json` → `variant_attributes` token array.
4. **Resolve `thumbnail_url`:** Lấy `payload.media[0].url` (ưu tiên vì SKU_CREATED chỉ có SKU-level media), fallback `payload.media[0].thumbnail_url` (nếu schema cho phép). Nếu rỗng → để trống.
5. **Derive `is_in_stock`:** Tính ở application logic: `is_in_stock = (!is_deleted && product_status == 'ACTIVE' && sku_status == 'ACTIVE')`. (Cùng công thức với §3.3.)
6. **Biến thể** của script PATCH chung (dùng `created_at` thay vì `updated_at`) với `scripted_upsert: true`, guard: `event.created_at > current_doc.version` (hoặc insert nếu doc chưa tồn tại).

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

> **Cronjob `Draft Cleanup Job`** (xem [Workers](search_workers.md#4-draft-cleanup-job)) sẽ dọn các Tombstone cũ sau 30 ngày.

**Sau khi xử lý xong:**
- Nếu doc trước khi xoá có `product_status = 'ACTIVE'`, trigger `_update_by_query` trên `categories` index theo `category_id` để **giảm** `product_count` (xem §3.11).
- Nếu doc trước khi xoá đã là `HIDDEN`, **không thay đổi** `product_count` (tránh double-decrement).

### 3.7. CATEGORY_CREATED

**Logic xử lý:**
1. Parse payload: `category_id`, `name`, `slug`, `parent_id`, `level`, `is_leaf`, `path`.
2. Validate `path` bắt buộc phải có; nếu thiếu → log warning + metric `search_payload_invalid_total{topic=category.events,event_type=CATEGORY_CREATED}`.
3. Index document vào `categories_write` alias (đang trỏ tới `categories_v1`) qua script PATCH với `scripted_upsert: true`.

### 3.8. CATEGORY_UPDATED

**Logic xử lý:**
1. Nhận `category_id`, `new_name`, `new_path`.
2. Hai hành động song song:
   - **Update `categories_write`** qua script PATCH (`scripted_upsert: true`).
   - **Update `products_write`** qua `_update_by_query` theo `category_id` để đồng bộ `category_name` và `category_path` cho toàn bộ SKU docs. Bắt buộc `conflicts=proceed` + `requests_per_second=300`.

> **Quy ước bất biến cấu trúc cây (BẮT BUỘC):** Sau khi `CATEGORY_CREATED`, các field cấu trúc cây (`slug`, `parent_id`, `level`, `is_leaf`) được coi là **bất biến**. Nếu admin cần thay đổi các field này (kéo theo di chuyển cây danh mục), payload `CATEGORY_UPDATED` chỉ mang `new_name` + `new_path` là **không đủ** — Search Service sẽ giữ cây cũ trong index. Trong trường hợp này, **vận hành phải chạy [Full Reindex Job](search_workers.md#2-full-reindex-job-zero-downtime-3-pha-đơn-giản) thủ công** để rebuild lại index từ PostgreSQL, đảm bảo toàn bộ SKU docs được đồng bộ `category_name`/`category_path` theo cây mới.

### 3.9. CATEGORY_DELETED

**Logic xử lý:**
1. Nhận `category_id`, `descendant_category_ids`, `updated_at`.
2. Hai hành động song song:
   - **Tombstone document** trong `categories_write` qua script PATCH với `scripted_upsert: true` (set `is_deleted = true`, `deleted_at = now`). KHÔNG gọi `_delete` trực tiếp để chặn zombie update từ event trễ.
   - **`_update_by_query`** trên `products_write` để nullify `category_*` fields và update `product_status = 'HIDDEN'` cho toàn bộ SKU docs thuộc các category bị xoá. Guard bằng `updated_at`. Bắt buộc `conflicts=proceed` + `requests_per_second=300`.
3. **KHÔNG thay đổi `categories.product_count`** (giữ nguyên để audit; các SKU docs đã được set HIDDEN nên sẽ bị filter khỏi search results).

### 3.10. REVIEW_METRICS_UPDATED

> **Phase 21 note:** Search Service **KHÔNG dùng script guard `updated_at > version`** cho event này. Partition key `product_id` đảm bảo ordering. Worker chỉ apply `_update_by_query` theo `product_id` một cách idempotent (ES tự xử lý version conflict qua `_seq_no`).

**Logic xử lý:**
1. Parse `product_id`, `average_rating`, `total_reviews`, `updated_at`.
2. `_update_by_query` theo `product_id` để update `product_rating_avg`, `product_rating_count`.
3. Bắt buộc `conflicts=proceed` + `requests_per_second=300`.
4. **Không cần script guard** vì partition ordering đảm bảo.

```json
POST products_write/_update_by_query?conflicts=proceed&requests_per_second=300
{
  "query": { "term": { "product_id": "prod-uuid-001" } },
  "script": {
    "source": "ctx._source.product_rating_avg = params.average_rating; ctx._source.product_rating_count = params.total_reviews; ctx._source.indexed_at = params.indexed_at; ctx._source.last_event_id = params.event_id;",
    "params": {
      "average_rating": 4.75,
      "total_reviews": 1251,
      "indexed_at": "2026-03-28T10:05:01Z",
      "event_id": "evt-review-001"
    }
  }
}
```

> **Các field KHÔNG xử lý:** `total_with_media` (có trong payload từ Review Service nhưng Search Service không cần). Worker chỉ đọc `product_id`, `average_rating`, `total_reviews`, `updated_at` — các field khác bị bỏ qua.

### 3.11. Categories `product_count` Realtime Update (Consolidated)

> **Mục đích:** Giữ cho `categories.product_count` luôn phản ánh số lượng product ACTIVE thuộc mỗi category, phục vụ cho navigation sidebar và facets (xem [Search Workflow §4](search_workflow.md)).

**Cơ chế:** `_update_by_query` trên `categories` index theo `category_id`, kích hoạt bởi các product event:

| Trigger Event | Hành động | Throttle | Ghi chú |
|---|---|---|---|
| `PRODUCT_CREATED` với `status = 'ACTIVE'` | `product_count += 1` | 300 rps, conflicts=proceed | Loop cùng transaction với index SKU docs |
| `PRODUCT_CREATED` với `status != 'ACTIVE'` (DRAFT) | Không thay đổi | — | DRAFT không hiển thị trên UI |
| `SKU_UPDATED` với `product_status_changed=true` (target = `ACTIVE`) | `product_count += 1` | 300 rps, conflicts=proceed | Auto-reactivate từ HIDDEN |
| `SKU_UPDATED` với `product_status_changed=true` (target = `HIDDEN`) | `product_count -= 1` | 300 rps, conflicts=proceed | Auto-hide (Inventory Phase 2 FSM) |
| `PRODUCT_DELETED` (khi doc ACTIVE trước đó) | `product_count -= 1` | 300 rps, conflicts=proceed | Soft delete |
| `PRODUCT_DELETED` (khi doc đã HIDDEN) | Không thay đổi | — | Tránh double-decrement |
| `CATEGORY_DELETED` | Không thay đổi | — | `product_count` giữ nguyên cho audit |

**Script mẫu cho tăng count:**
```json
POST categories_write/_update_by_query?conflicts=proceed&requests_per_second=300
{
  "query": { "term": { "category_id": "cat-smartphone" } },
  "script": {
    "source": "if (ctx._source.product_count == null) { ctx._source.product_count = 0; } ctx._source.product_count += 1; ctx._source.indexed_at = params.indexed_at; ctx._source.last_event_id = params.event_id;",
    "params": {
      "indexed_at": "2026-03-28T10:05:01Z",
      "event_id": "evt-001"
    }
  }
}
```

**Script mẫu cho giảm count:**
```json
POST categories_write/_update_by_query?conflicts=proceed&requests_per_second=300
{
  "query": { "term": { "category_id": "cat-smartphone" } },
  "script": {
    "source": "if (ctx._source.product_count == null) { ctx._source.product_count = 0; } if (ctx._source.product_count > 0) { ctx._source.product_count -= 1; } ctx._source.indexed_at = params.indexed_at; ctx._source.last_event_id = params.event_id;",
    "params": {
      "indexed_at": "2026-03-28T10:05:01Z",
      "event_id": "evt-del-002"
    }
  }
}
```

**Idempotency & sai số chấp nhận được:** Do `_update_by_query` arithmetic (`+= 1` / `-= 1`) có thể double-apply khi Kafka redeliver event, `product_count` có thể lệch ±1-2. Chấp nhận sai số nhỏ này (không gây ảnh hưởng UX). Nếu sai số lớn, vận hành chạy [Full Reindex Job](search_workers.md#2-full-reindex-job-zero-downtime-3-pha-đơn-giản) để reconcile hoàn toàn.


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
ELSE IF event_type IN (REVIEW_METRICS_UPDATED, PRODUCT_STATUS_CHANGED-via-SKU_UPDATED) THEN
  -- Phase 19/21: Không dùng script guard
  -- Chỉ dựa vào Kafka partition ordering
  APPLY update (idempotent qua _update_by_query)
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
| `PRODUCT_STATUS_CHANGED` (qua `SKU_UPDATED.product_status_changed=true`) | **Không dùng script guard** (Phase 19) — chỉ partition ordering |
| `SKU_UPDATED` | `updated_at > version` |
| `SKU_CREATED` | `created_at > version` (scripted upsert) |
| `PRODUCT_DELETED` / `SKU_DELETED` | `updated_at > version` |
| `CATEGORY_*` | `updated_at > version` |
| `REVIEW_METRICS_UPDATED` | **Không dùng script guard** (Phase 21) — chỉ partition ordering |
| `categories.product_count` realtime update | **Không dùng script guard** — arithmetic idempotent chấp nhận sai số ±1-2 |

> **Trade-off chấp nhận:** Metrics update (rating) có thể bị skip nếu timestamp trùng millisecond với update trước đó (vì guard so sánh `>` chứ không phải `>=`). Chấp nhận được vì Kafka đảm bảo ordering trong cùng partition và metrics có độ trễ vài giây là chấp nhận được.

### 4.3. Xử Lý Lỗi (Phase 22b — Không có DLQ)

| Loại lỗi | Xử lý |
|---|---|
| **Transient (ES timeout / 429 / 503 / network)** | Nack event → Kafka tự retry theo policy `delivery.timeout.ms` (mặc định 5 phút, có thể tăng). Không commit offset. |
| **`document_missing_exception` cho partial update** | Log error + metric `search_document_missing_total{topic,event_type,doc_id}` + skip + ACK. Không tự fetch full product để self-heal. |
| **Mapping conflict / schema poison (không retry được)** | Log error + metric `search_payload_invalid_total{topic,event_type}` + skip + ACK. |
| **Category lookup miss (cho PRODUCT_CREATED/PRODUCT_UPDATED/SKU_CREATED)** | Bỏ qua `category_name`/`category_path` (không ghi vào doc), log warning + metric `search_category_enrichment_miss_total{topic,event_type,category_id}` + ACK. `CATEGORY_UPDATED`/`CATEGORY_CREATED` sau đó sẽ populate lại. |
| **Lỗi liên tiếp / cluster ES down** | Log error liên tục + metric tăng theo thời gian. Cảnh báo dashboard qua metric; vận hành tự restart consumer / fix cluster. Không tự retry vô hạn. |

> **Tại sao KHÔNG có DLQ (Phase 22b):** Xem entry [CHANGELOG §Phase 22b Search](../_contracts/CHANGELOG.md#phase-22b--search-service--remove-dlq). Tóm tắt: single-admin system, ít downstream consumers, CDC + Debezium đảm bảo payload ở producer. Manual recovery qua log + metric + Full Reindex Job khi cần.
