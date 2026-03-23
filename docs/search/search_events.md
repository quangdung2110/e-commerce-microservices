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

> **Nguyên tắc:** Search chỉ phản chiếu dữ liệu từ Product Service. Mọi tính toán giá (`promotional_price`, `best_price`, `has_promotion`) là trách nhiệm của Product Service; Search chỉ lưu và phục vụ truy vấn.

## 1. Bản Đồ Kafka Topics

| Topic Name | Consumer Group | Producer | Events trong Topic | Mô tả |
|---|---|---|---|---|
| `t.product.events` | `search-product-indexer` | Product Service | `PRODUCT_CREATED`, `PRODUCT_UPDATED`, `PRODUCT_STATE_CHANGED`, `PRODUCT_PRICE_UPDATED`, `PRODUCT_SKU_STATUS_CHANGED`, `PRODUCT_DELETED`, `PRODUCT_SKU_DELETED`, `CATEGORY_UPDATED`, `CATEGORY_DELETED` | Topic chính cho Product projection |

## 2. Chi Tiết Xử Lý Theo Event Type

### 2.1. PRODUCT_CREATED
**Nguồn:** [Product Events - 3.1](../product/product_events.md#31-product_created)

**Logic xử lý:**
1. Parse payload, extract Product + danh sách SKUs.
2. Nếu `skus` rỗng/null -> log warning và skip event.
3. Enrichment category từ [Category Cache](search_caching_schema.md#12-category-tree-cache).
4. **Data transformation ownership (bắt buộc):** Search Worker transform `attributes_json` và `variant_attributes_json` từ raw JSON sang token arrays `searchable_attributes` / `variant_attributes`.
5. Index theo mô hình SKU-first (1 SKU = 1 document).

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
1. Parse payload: `sku_id`, `original_price`, `promotional_price`, `best_price` (nếu có), `has_promotion`, `version`.
2. Partial update document SKU.
3. **Cấm `upsert`/`doc_as_upsert` cho event partial update giá** để tránh tạo document cụt khi event đến trước `PRODUCT_CREATED`.

```json
POST products_write/_update/{sku_id}
{
  "script": {
    "source": "if (ctx._source.version != null && params.version <= ctx._source.version) { ctx.op = 'none'; return; } ctx._source.original_price = params.original_price; ctx._source.promotional_price = params.promotional_price; ctx._source.best_price = params.best_price; ctx._source.has_promotion = params.has_promotion; ctx._source.version = params.version;",
    "params": {
      "original_price": 33990000,
      "promotional_price": 28891500,
      "best_price": 28891500,
      "has_promotion": true,
      "version": 1709420000000
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

### 2.7. PRODUCT_DELETED
**Nguồn:** [Product Events - 3.6](../product/product_events.md#36-product_deleted)

**Logic xử lý:**
1. Soft-delete hoặc tombstone document thay vì hard delete.
2. Có thể dùng upsert cho tombstone event này để chống zombie khi delete đến trước create.

### 2.8. PRODUCT_SKU_DELETED
**Nguồn:** [Product Events - 3.8](../product/product_events.md#38-product_sku_deleted)

**Logic xử lý:**
1. Đánh dấu SKU `DELETED` + `is_in_stock = false` + `version`.
2. Script update có guard version. Không cập nhật các field không liên quan.

### 2.9. CATEGORY_DELETED
**Nguồn:** [Product Events - 3.9](../product/product_events.md#39-category_deleted)

**Logic xử lý:**
1. Nhận `category_version` từ event.
2. `_update_by_query` để đưa product về `HIDDEN` (hoặc policy tương đương), đồng thời cập nhật `category_version`.
3. Invalidate `search:categories:tree`.

## 3. Chiến Lược Idempotency & Ordering

### 3.1. Chống Duplicate
`PUT _doc/{id}` và `POST _update/{id}` là idempotent khi kết hợp guard version.

### 3.2. Chống Out-of-Order
Search Worker bắt buộc áp dụng quy tắc:

```text
IF event.version <= current_doc.version THEN
    SKIP event
ELSE
    APPLY event
END
```

Tương tự cho category-level update dùng `category_version`.

### 3.3. Xử Lý Lỗi
- **Elasticsearch unreachable:** nack event để Kafka retry.
- **`document_missing_exception` cho partial update:** không được bỏ qua. Log error và push vào Retry Topic hoặc DLQ; có thể trigger fetch full product để tự phục hồi.
- **Lỗi liên tiếp vượt ngưỡng:** đẩy vào `t.search.dlq` và cảnh báo vận hành.