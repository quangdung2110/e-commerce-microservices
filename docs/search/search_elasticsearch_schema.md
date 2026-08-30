# Search Service - Elasticsearch Schema Design

**Liên kết nhanh (Related Documents):**
*   [Service Overview (Tech Stack, Deployment, Observability)](search_service_overview.md)
*   [Workflows (API & Data Flow)](search_workflow.md)
*   [Background Workers & Jobs](search_workers.md)
*   [Events Consumed & Kafka Topics](search_events.md)
*   [Architecture Recommendations](search_architecture_recommendations.md)

**Cross-Service References:**
*   [Product Service - Database Schema (Write Store / Source of Truth)](../product/data.md)
*   [Product Service - Events Structure](../product/interfaces.md)
*   [Review Service - Events Structure](../review/review_events.md)
*   [Service Contract Matrix (SSOT)](../_contracts/CONTRACT_MATRIX.md)

---

Tài liệu này mô tả thiết kế **Elasticsearch Index** cho Search Service — phiên bản **Read Model (Query Database)** trong kiến trúc CQRS. Search Service **không sở hữu PostgreSQL** và **không sở hữu Redis**; Elasticsearch là nguồn dữ liệu duy nhất, phục vụ trực tiếp cho mọi API request.

Dữ liệu được đồng bộ bất đồng bộ (Async) từ nhiều upstream streams qua Kafka: Product (core catalog), Category (taxonomy) và Review (rating metrics).

> **Nguyên tắc cốt lõi:** Search Service chỉ có 1 nhiệm vụ duy nhất: **Đọc cực nhanh**. Không có transaction phân tán; ghi vào index chỉ đến từ Kafka consumer.
>
> **Guard ordering (đơn giản hoá):** Hầu hết event dùng **đúng 1 trường timestamp** làm guard, lưu vào field `version` (ISO 8601 UTC) của Elasticsearch document. Quy tắc: `event_timestamp > current_doc.version`. Cụ thể:
> - Đa số event: dùng `event.updated_at`.
> - Riêng `SKU_CREATED`: dùng `event.created_at` (event khởi tạo, entity chưa có `updated_at`).
> - **NGOẠI LỆ (Phase 21):** `REVIEW_METRICS_UPDATED` và `PRODUCT_STATUS_CHANGED` (qua `SKU_UPDATED.product_status_changed=true`) KHÔNG dùng script guard — chỉ dựa vào Kafka partition ordering. Xem [Search Events §3.3, §3.10](search_events.md) để biết chi tiết.
>
> **Lưu ý về admin:** Hệ thống chỉ có 1 admin (toàn quyền, không bao giờ bị ban). Search Service **không subscribe** `t.admin.events` và **không lưu trữ bất kỳ field admin nào** trong document. Xem [Search Events §3](search_events.md) cuối file để biết chi tiết.
>
> **Trạng thái Phase đã áp dụng (đồng bộ với [CHANGELOG](../_contracts/CHANGELOG.md)):**
> - **Phase 19**: Search Service xử lý `product_status` thay đổi qua `SKU_UPDATED` (không cần handler riêng cho `PRODUCT_STATUS_CHANGED`).
> - **Phase 21**: Bỏ `t.search.metrics.events` topic + `search-metrics-indexer` consumer group + field `product_sold_count` (giờ Search Service chỉ cần 3 topics: `t.product.events`, `t.category.events`, `t.review.events`).
> - **Phase 22b**: Bỏ hoàn toàn `t.search.dlq` + `search-dlq-processor`. Mọi lỗi không tự hồi phục xử lý bằng `log + metric + skip + ACK`.
>
> **Lưu ý về `search_suggestions`:** Index này **không có write alias** vì không có luồng incremental update. Suggestions Rebuilder build hằng ngày tạo `search_suggestions_v{N+1}` mới hoàn toàn (xem [Workers §3](search_workers.md#3-search-suggestions-rebuilder)), ghi trực tiếp vào index versioned, sau đó atomic alias swap `search_suggestions` → `search_suggestions_v{N+1}`. Vì rebuild nguyên tử, index này **không cần field `version`** (khác với `products` và `categories`).

> **Index Alias Strategy:** Sử dụng Alias để cho phép Zero-Downtime Reindex. Khi cần thay đổi mapping, tạo `products_v2`, reindex toàn bộ dữ liệu, rồi swap alias `products` từ `v1` sang `v2`.

## 1. Danh Sách Elasticsearch Indices

| Index Name | Alias | Mô tả | Document Count (ước tính) | `version` field |
|---|---|---|---|---|
| `products_v1` | `products` (read alias), `products_write` (write alias) | Sản phẩm denormalized phẳng | Hàng triệu | ✅ Có (guard ordering) |
| `categories_v1` | `categories` (read alias), `categories_write` (write alias) | Cây danh mục + product_count | Hàng trăm | ✅ Có (guard ordering) |
| `search_suggestions_v1` | `search_suggestions` (read alias) | Autocomplete / Gợi ý tìm kiếm | Hàng trăm ngàn | ❌ Không (rebuild nguyên tử) |

> **Lưu ý về `search_suggestions`:** Index này **không có write alias** vì không có luồng incremental update. Suggestions Rebuilder build hằng ngày tạo `search_suggestions_v{N+1}` mới hoàn toàn (xem [Workers §3](../search/search_workers.md#3-search-suggestions-rebuilder)), ghi trực tiếp vào index versioned, sau đó atomic alias swap `search_suggestions` → `search_suggestions_v{N+1}`.

> **Index Alias Strategy:** Sử dụng Alias để cho phép Zero-Downtime Reindex. Khi cần thay đổi mapping, tạo `products_v2`, reindex toàn bộ dữ liệu, rồi swap alias `products` từ `v1` sang `v2`.

## 2. Products Index — Mapping & Document Structure

- **Tránh tính toán và JOIN dữ liệu:** Dữ liệu được "phi chuẩn hóa" (denormalized) dựa trên chiến lược **SKU-First (Biến thể làm trung tâm)**. Giá trị `best_price` và cờ `is_in_stock` (còn hàng) được lưu sẵn để Elasticsearch duyệt siêu tốc.
- **Hiệu Năng Cực Hạn (Extreme Scale):** Loại bỏ hoàn toàn kiểu dữ liệu `nested` để chặn đứng "Thảm hoạ OOM". Sử dụng Composite Keyword Token array cho phép tìm kiếm/filter tổ hợp thuộc tính mà không cần JOIN.
- **Bảo vệ hệ thống (Zero-downtime):** Alias Strategy (`products`) để dễ dàng nâng cấp cấu trúc và đổi index mà không làm gián đoạn người dùng.

### 2.1. Index Settings

```json
{
  "settings": {
    "number_of_shards": 3,
    "number_of_replicas": 1,
    "refresh_interval": "1s",
    "analysis": {
      "analyzer": {
        "vietnamese_analyzer": {
          "type": "custom",
          "tokenizer": "icu_tokenizer",
          "filter": ["icu_folding", "lowercase", "vi_stop"]
        },
        "autocomplete_analyzer": {
          "type": "custom",
          "tokenizer": "autocomplete_tokenizer",
          "filter": ["icu_folding", "lowercase"]
        },
        "autocomplete_search_analyzer": {
          "type": "custom",
          "tokenizer": "icu_tokenizer",
          "filter": ["icu_folding", "lowercase"]
        }
      },
      "tokenizer": {
        "autocomplete_tokenizer": {
          "type": "edge_ngram",
          "min_gram": 2,
          "max_gram": 20,
          "token_chars": ["letter", "digit"]
        }
      },
      "filter": {
        "vi_stop": {
          "type": "stop",
          "stopwords": ["và", "của", "là", "cho", "với", "trong", "này", "có", "được", "các"]
        }
      }
    }
  }
}
```

**Giải thích cấu hình:**
- **`icu_tokenizer`**: Tách từ chính xác cho tiếng Việt (xử lý dấu, Unicode).
- **`icu_folding`**: Chuẩn hoá Unicode — "phở" matches "pho".
- **`edge_ngram`**: Sinh prefix tokens cho autocomplete. VD: "iPhone" → ["ip", "iph", "ipho", "iphon", "iphone"].
- **`vi_stop`**: Loại bỏ stop words tiếng Việt.
- **`refresh_interval: 1s` (mặc định)**: Document mới xuất hiện trong kết quả search sau tối đa 1 giây. Khi ingest spike lớn, có thể tăng tạm `3s-5s` để tăng throughput indexing, rồi trả về `1s` sau khi ổn định.

### 2.2. Field Mapping (Document Schema: Denormalized SKU)

> **[Extreme Scale Strategy: SKU as First-Class Document]**
> Mỗi Elasticsearch Document là một SKU độc lập. Để kết nhóm hiển thị theo Product, sử dụng Query **Field Collapsing** (gom nhóm theo `product_id`).

```json
{
  "mappings": {
    "properties": {
      "sku_id":              { "type": "keyword" },
      "product_id":          { "type": "keyword" },
      "category_id":         { "type": "keyword" },
      "category_name":       { "type": "keyword" },
      "category_path":       { "type": "keyword" },

      "name": {
        "type": "text",
        "analyzer": "vietnamese_analyzer",
        "fields": {
          "autocomplete": { "type": "text", "analyzer": "autocomplete_analyzer", "search_analyzer": "autocomplete_search_analyzer" },
          "keyword": { "type": "keyword" }
        }
      },

      "description":         { "type": "text", "analyzer": "vietnamese_analyzer", "index_options": "offsets" },
      "thumbnail_url":       { "type": "keyword" },
      "product_status":      { "type": "keyword" },
      "sku_status":          { "type": "keyword" },

      "searchable_attributes": { "type": "keyword" },
      "variant_attributes":    { "type": "keyword" },

      "original_price":      { "type": "scaled_float", "scaling_factor": 100 },
      "promotional_price":   { "type": "scaled_float", "scaling_factor": 100 },
      "best_price":          { "type": "scaled_float", "scaling_factor": 100 },

      "is_in_stock":         { "type": "boolean" },
      "has_promotion":       { "type": "boolean" },
      "is_deleted":          { "type": "boolean" },
      "deleted_at":          { "type": "date" },

      "product_rating_avg":  { "type": "half_float" },
      "product_rating_count": { "type": "integer" },

      "created_at":          { "type": "date" },
      "updated_at":          { "type": "date" },
      "indexed_at":          { "type": "date" },
      "last_event_id":       { "type": "keyword" },

      "version":             { "type": "date" }
    }
  }
}
```

### 2.3. Giải Thích Mapping

| Field | Loại | Lý do & Ý nghĩa |
|---|---|---|
| `sku_id` | keyword | Là `_id` thực sự của document này. |
| `product_id` | keyword | Pivot Key cho **Field Collapsing** và các phép đếm DISTINCT product-level ở API Response. |
| `searchable_attributes` | keyword array | Composite tokens cho các thuộc tính dùng chung của Product (Product-level). Ví dụ: `["thuonghieu:apple", "chatlieu:titanium"]`. **Search Worker transform từ `attributes_json` sang token array khi consume event.** |
| `variant_attributes` | keyword array | Mô tả đặc tính riêng của từng SKU. Ví dụ: `["mausac:đen", "dungluong:256GB"]`. **Search Worker transform từ `variant_attributes_json` sang token array.** |
| `is_in_stock` | boolean | Cờ khả dụng bán hàng trong search projection: `is_deleted=false AND product_status=ACTIVE AND sku_status=ACTIVE`. Được recompute khi nhận `SKU_UPDATED` (bao gồm cả trường hợp `product_status_changed=true`). Không phải nguồn tồn kho realtime để chốt đơn. |
| `is_deleted`, `deleted_at` | boolean/date | Tombstone chặn zombie update khi event đến trễ sau delete. |
| `version` | date | **Guard ordering chính.** Lưu timestamp phù hợp với event ghi: `updated_at` cho đa số event, `created_at` cho `SKU_CREATED`. Quy tắc: `event_timestamp > current_doc.version`. Xem [Events §4](search_events.md#4-chiến-lược-idempotency--ordering). |

**Count semantics cho browse/facets (bắt buộc):** Vì index là SKU-first, mọi count hiển thị cho category/facets ở API phải đếm DISTINCT `product_id` (không dùng trực tiếp `doc_count` theo SKU).

### 2.4. Mẫu Document Hoàn Chỉnh

```json
{
  "sku_id": "sku-uuid-001",
  "product_id": "prod-uuid-001",
  "category_id": "cat-smartphone",
  "category_name": "Điện thoại",
  "category_path": "dien-tu/dien-thoai",
  "name": "iPhone 15 Pro Max 256GB Chính Hãng",
  "description": "iPhone 15 Pro Max chính hãng, bảo hành 12 tháng",
  "thumbnail_url": "https://cdn.example.com/ip15.jpg",
  "product_status": "ACTIVE",
  "sku_status": "ACTIVE",

  "searchable_attributes": [
    "thuonghieu:apple",
    "xuatxu:vietnam"
  ],
  "variant_attributes": [
    "mausac:đen",
    "bonho:256GB"
  ],

  "original_price": 34990000,
  "promotional_price": 28891500,
  "best_price": 28891500,

  "is_in_stock": true,
  "has_promotion": true,
  "is_deleted": false,
  "deleted_at": null,

  "product_rating_avg": 4.9,
  "product_rating_count": 1200,

  "created_at": "2026-02-28T03:51:00Z",
  "updated_at": "2026-03-28T10:05:00Z",
  "indexed_at": "2026-03-28T10:05:01Z",
  "last_event_id": "123e4567-e89b-12d3-a456-426614174003",

  "version": "2026-03-28T10:05:00Z"
}
```

### 2.5. Demo Tính Năng Field Collapsing (Gom Nhóm)

```json
GET products/_search
{
  "query": {
    "bool": {
      "must": [
        { "match": { "name": "iPhone 15" } },
        { "term": { "variant_attributes": "mausac:đen" } }
      ]
    }
  },
  "collapse": {
    "field": "product_id",
    "inner_hits": {
      "name": "matching_skus",
      "size": 3,
      "sort": [{ "best_price": "asc" }]
    }
  },
  "sort": [
    { "best_price": "asc" },
    { "sku_id": "asc" }
  ]
}
```

Kết quả: trả về 1 document đại diện cho `iPhone 15` (SKU rẻ nhất màu đen), kèm `inner_hits` tối đa 3 biến thể cùng màu đen. **Tie-break `sku_id asc`** ở cuối sort đảm bảo pagination ổn định khi dùng `search_after` (xem [Search Workflow §2](search_workflow.md)).

## 3. Categories Index — Mapping & Document Structure

Update qua `t.category.events`. `product_count` được update **realtime** bằng `_update_by_query` theo `category_id` khi nhận các `t.product.events` liên quan (xem [Search Events §3.11](search_events.md#311-categories-product_count-realtime-update-consolidated)).

### 3.1. Index Settings

```json
{
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 1,
    "refresh_interval": "5s"
  }
}
```

### 3.2. Field Mapping

```json
{
  "mappings": {
    "properties": {
      "category_id": { "type": "keyword" },
      "name": {
        "type": "text",
        "analyzer": "vietnamese_analyzer",
        "fields": { "keyword": { "type": "keyword" } }
      },
      "slug":      { "type": "keyword" },
      "parent_id": { "type": "keyword" },
      "level":     { "type": "integer" },
      "is_leaf":   { "type": "boolean" },
      "path":      { "type": "keyword" },
      "product_count": { "type": "long" },
      "is_deleted": { "type": "boolean" },
      "deleted_at": { "type": "date" },
      "version":   { "type": "date" }
    }
  }
}
```

### 3.3. Mẫu Document

```json
{
  "category_id": "cat-smartphone",
  "name": "Điện thoại",
  "slug": "dien-thoai",
  "parent_id": "cat-dien-tu",
  "level": 2,
  "is_leaf": true,
  "path": "dien-tu/dien-thoai",
  "product_count": 1250,
  "is_deleted": false,
  "version": "2026-03-28T10:05:00Z"
}
```

### 3.4. Cơ Chế Cập Nhật `product_count` Realtime

`product_count` trên categories index được cập nhật **realtime** qua `_update_by_query` theo `category_id` trên chính `categories` index mỗi khi có product event thay đổi trạng thái visibility:

| Trigger Event | Hành động trên `categories` index | Chi tiết |
|---|---|---|
| `PRODUCT_CREATED` (status = `ACTIVE`) | Tăng `product_count` | `_update_by_query` theo `category_id` + script `ctx._source.product_count += 1` |
| `PRODUCT_CREATED` (status ≠ `ACTIVE`, ví dụ DRAFT) | Không thay đổi | DRAFT không hiển thị trên UI |
| `SKU_UPDATED` với `product_status_changed=true` (target = `ACTIVE`) | Tăng `product_count` | Auto-reactivate từ `HIDDEN` → `ACTIVE` |
| `SKU_UPDATED` với `product_status_changed=true` (target = `HIDDEN`) | Giảm `product_count` | Auto-hide từ `ACTIVE` → `HIDDEN` |
| `PRODUCT_DELETED` (trước khi là HIDDEN) | Giảm `product_count` | Soft delete |
| `PRODUCT_DELETED` (đã là HIDDEN trước đó) | Không thay đổi | Tránh double-decrement |
| `CATEGORY_DELETED` | Đánh dấu `is_deleted=true` | `product_count` giữ nguyên (audit); docs thuộc category đã được nullify `category_*` và set HIDDEN |

**Throttle bắt buộc:** Mọi `_update_by_query` trên `categories` index dùng `requests_per_second=300` + `conflicts=proceed` (giống pattern diện rộng ở products).

**Idempotency:** Vì partition key của `t.product.events` là `product_id` (Product-level) hoặc `sku_id` (SKU-level), các event của cùng product đảm bảo ordering. Nếu có cả `PRODUCT_DELETED` (đã HIDDEN trước đó) → idempotent skip. Nếu duplicate do retry → `_update_by_query` với `ctx._source.product_count` arithmetic có thể sai lệch (tăng 2 lần thay vì 1) — **chấp nhận** sai số nhỏ này vì Full Reindex Job sẽ reconcile khi cần; đánh đổi giữa simplicity và perfect accuracy.

## 4. Search Suggestions Index: Mapping & Ranking Contract

Index `search_suggestions_v1` (hiện tại — pattern `search_suggestions_v{N}` cho mọi version tương lai) phục vụ autocomplete. Read-model riêng, không reuse mapping của `products_v{N}` để tránh query nặng không cần thiết.

### 4.1. Index Settings

```json
{
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 1,
    "refresh_interval": "5s",
    "analysis": {
      "analyzer": {
        "suggest_index_analyzer": {
          "type": "custom",
          "tokenizer": "suggest_edge_ngram_tokenizer",
          "filter": ["icu_folding", "lowercase"]
        },
        "suggest_search_analyzer": {
          "type": "custom",
          "tokenizer": "icu_tokenizer",
          "filter": ["icu_folding", "lowercase"]
        }
      },
      "tokenizer": {
        "suggest_edge_ngram_tokenizer": {
          "type": "edge_ngram",
          "min_gram": 2,
          "max_gram": 20,
          "token_chars": ["letter", "digit"]
        }
      }
    }
  }
}
```

### 4.2. Field Mapping

```json
{
  "mappings": {
    "properties": {
      "suggestion_id": { "type": "keyword" },
      "text": {
        "type": "text",
        "analyzer": "suggest_index_analyzer",
        "search_analyzer": "suggest_search_analyzer",
        "fields": {
          "keyword": { "type": "keyword" }
        }
      },
      "text_normalized": { "type": "keyword" },
      "type": { "type": "keyword" },
      "source": { "type": "keyword" },
      "scope": { "type": "keyword" },
      "category_id": { "type": "keyword" },
      "product_id": { "type": "keyword" },
      "weight": { "type": "long" },
      "product_rating_avg": { "type": "half_float" },
      "product_rating_count": { "type": "integer" },
      "product_count": { "type": "long" },
      "build_version": { "type": "long" },
      "updated_at": { "type": "date" }
    }
  }
}
```

> **Tại sao không có field `version`:** Suggestions Rebuilder chạy daily build nguyên tử (tạo `search_suggestions_v{N+1}` mới hoàn toàn → bulk index đầy đủ → atomic alias swap). Không có partial update nên không cần guard ordering. `build_version` (long) đã đủ để identify build batch khi cần debug.

### 4.3. Ranking Contract

1. `weight` là khóa xếp hạng chính, tính ở Suggestions Rebuilder và lưu sẵn trong document.
2. Nếu `weight` bằng nhau, query phải tie-break ổn định theo:
   - `text.keyword` tăng dần.
3. Không tính ranking động trong API path; API chỉ đọc index đã pre-compute để giữ latency thấp.

### 4.4. Mẫu Document

**Mẫu PRODUCT type (chỉ CATEGORY type mới dùng `product_count`):**
```json
{
  "suggestion_id": "sgg:d4f9f7d7f5f2",
  "text": "iphone 15 pro max",
  "text_normalized": "iphone 15 pro max",
  "type": "PRODUCT",
  "source": "PRODUCTS_ALIAS",
  "scope": "GLOBAL",
  "category_id": "cat-smartphone",
  "product_id": "prod-uuid-001",
  "weight": 91520,
  "product_rating_avg": 4.9,
  "product_rating_count": 1200,
  "build_version": 2026032901,
  "updated_at": "2026-03-29T03:10:00Z"
}
```

**Mẫu CATEGORY type (có dùng `product_count`):**
```json
{
  "suggestion_id": "sgg:cat-smartphone",
  "text": "điện thoại",
  "text_normalized": "dien thoai",
  "type": "CATEGORY",
  "source": "CATEGORIES_ALIAS",
  "scope": "GLOBAL",
  "category_id": "cat-smartphone",
  "weight": 125000,
  "product_count": 1250,
  "build_version": 2026032901,
  "updated_at": "2026-03-29T03:10:00Z"
}
```

### 4.5. Query Template (Autocomplete)

```json
GET search_suggestions/_search
{
  "query": {
    "match": { "text": { "query": "iph" } }
  },
  "sort": [
    { "weight": "desc" },
    { "text.keyword": "asc" }
  ],
  "size": 8,
  "_source": ["text", "type", "weight"]
}
```

## 5. Scaling Elasticsearch Cluster

**Cấu hình khuyến nghị theo quy mô (do Search chịu toàn bộ traffic đọc):**

| Quy mô catalog | Shard/Index | Replica | Data Nodes | RAM/Node | p99 target |
|---|---|---|---|---|---|
| < 1M SKU / 1K req/s | 3 | 1 | 5 | 16GB | 50ms |
| 1-5M SKU / 5K req/s | 5 | 1 | 15 | 32GB | 80ms |
| 5-10M SKU / 10K req/s | 10 | 2 | 30 | 64GB | 120ms |

**Best Practices:**
- **Shard sizing:** Mỗi shard nên chứa 10-50GB data.
- **Dedicated Master Nodes:** 3 master-eligible nodes riêng, không chạy data.
- **JVM Heap:** Cấp 50% RAM cho JVM heap (tối đa 31GB). 50% còn lại cho filesystem cache.
- **Coordinator nodes:** Tách riêng data và coordinator nodes khi cluster > 10 nodes. (Có thể dùng read replica cho search-serving khi scale > 10K req/s — sẽ benchmark sau khi có load thực tế.)
- **Tune caches:** `indices.queries.cache.size: 15%`, `indices.requests.cache.size: 5%`.
- **Index per version:** Pattern `products_v1`, `products_v2` + alias swap cho zero-downtime schema changes.

**Timeout config cho Search Service client:**
- Product search API: 5s timeout (fail-fast nếu vượt → trả 503).
- Suggest API: 2s timeout (p99 budget 25ms; 2s là safety net cho cold start).
- `_update_by_query` diện rộng: 30s timeout (chấp nhận chậm hơn vì batch).
