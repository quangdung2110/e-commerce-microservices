# Search Service - Elasticsearch Schema Design (Extreme Scale)

**Liên kết nhanh (Related Documents):**
*   [Elasticsearch Schema (Read Store)](search_elasticsearch_schema.md)
*   [Caching Schema (Redis)](search_caching_schema.md)
*   [Workflows (API & Data Flow)](search_workflow.md)
*   [Background Workers & Jobs](search_workers.md)
*   [Events Consumed & Kafka Topics](search_events.md)
*   [Architecture Recommendations](search_architecture_recommendations.md)

**Cross-Service References:**
*   [Product Service - Database Schema (Write Store / Source of Truth)](../product/product_db_design.md)
*   [Product Service - Events Structure](../product/product_events.md)
*   [Product Service - Workers (Outbox Producer)](../product/product_workers.md)

---

Tài liệu này mô tả thiết kế **Elasticsearch Index** cho Search Service — phiên bản **Read Model (Query Database)** trong kiến trúc CQRS. Search Service **không sở hữu PostgreSQL**; Elasticsearch là nguồn dữ liệu duy nhất. Dữ liệu được đồng bộ bất đồng bộ (Async) từ nhiều upstream streams qua Kafka: Product (core catalog), Review (rating metrics), Order Metrics Projector (sold metrics), và admin (profile/status).

> **Nguyên tắc cốt lõi:** Search Service chỉ có 1 nhiệm vụ duy nhất: **Đọc cực nhanh**. Không có transaction phân tán; ghi vào index chỉ đến từ Kafka consumer và dùng bộ guard theo từng luồng sự kiện (`version`, `price_event_sequence_id`, `review_metrics_event_sequence_id`, `sales_metrics_event_sequence_id`, `admin_profile_version`, `admin_status_version`) để chống out-of-order.
>
> **Identity contract:*
## 1. Danh Sách Elasticsearch Indices

| Index Name | Alias | Mô tả | Document Count (ước tính) |
|---|---|---|---|
| `products_v1` | `products` (read alias), `products_write` (write alias) | Sản phẩm denormalized phẳng | Hàng triệu |
| `search_suggestions_v1` | `search_suggestions` | Autocomplete / Gợi ý tìm kiếm | Hàng trăm ngàn |

> **Index Alias Strategy:** Sử dụng Alias để cho phép [Zero-Downtime Reindex](search_workers.md#7-full-reindex-job-zero-downtime). Khi cần thay đổi mapping, tạo `products_v2`, reindex toàn bộ dữ liệu, rồi swap alias `products` từ `v1` sang `v2`. Không gián đoạn traffic.

## 2. Products Index — Mapping & Document Structure

- **Tránh tính toán và JOIN dữ liệu:** Dữ liệu được "phi chuẩn hóa" (denormalized) dựa trên chiến lược **SKU-First (Biến thể làm trung tâm)**. Giá trị `best_price` và cờ `is_in_stock` (còn hàng) được lưu sẵn để Elasticsearch duyệt siêu tốc.
- **Hiệu Năng Cực Hạn (Extreme Scale):** Loại bỏ hoàn toàn kiểu dữ liệu `nested` để chặn đứng "Thảm họa OOM" (Out of Memory) sinh ra từ Block Join. Hỗ trợ tìm kiếm thông qua các chuỗi hợp thể sinh sẵn (Composite Keyword Token) giúp tìm kiếm tổ hợp Mọi tính năng cực kỳ mượt mà.
- **Bảo vệ hệ thống (Zero-downtime):** Ứng dụng Alias Strategy (`products`) để dễ dàng nâng cấp cấu trúc, đồng bộ dữ liệu ngầm trên index mới và chuyển đổi Alias mà không làm gián đoạn người dùng.

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
- **`icu_tokenizer`**: Tách từ chính xác cho tiếng Việt (xử lý dấu, Unicode). Vượt trội hơn `standard` tokenizer cho ngôn ngữ Đông Nam Á.
- **`icu_folding`**: Chuẩn hóa Unicode — "phở" matches "pho", "điện thoại" matches "dien thoai".
- **`edge_ngram`**: Sinh ra prefix tokens cho autocomplete. VD: "iPhone" → ["ip", "iph", "ipho", "iphon", "iphone"]. Cho phép user gõ "iph" đã thấy gợi ý.
- **`vi_stop`**: Loại bỏ stop words tiếng Việt khỏi full-text search để tăng precision.
- **`refresh_interval: 1s` (mặc định)**: Document mới xuất hiện trong kết quả search sau tối đa 1 giây. Khi ingest spike lớn, có thể tăng tạm `3s-5s` để tăng throughput indexing, rồi trả về `1s` sau khi ổn định.

### 2.2. Field Mapping (Document Schema: Denormalized SKU)

> **[Extreme Scale Strategy: SKU as First-Class Document]**
> Để chống chịu tải Flash Sale và tránh "Thảm họa OOM" do `nested` types, cấu trúc Document đã được làm phẳng hoàn toàn (Flattened) và xoay trục: **Mỗi Elasticsearch Document sẽ tự thân là một SKU độc lập**. Để kết nhóm hiển thị theo Product như thông thường, chúng ta sử dụng Query **Field Collapsing** (gom nhóm theo `product_id`).

```json
{
  "mappings": {
    "properties": {
      "sku_id":              { "type": "keyword" },
      "product_id":          { "type": "keyword" },
      
      "platform_id":           { "type": "keyword" },
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
      "review_metrics_updated_at": { "type": "date" },
      "review_metrics_event_sequence_id": { "type": "long" },
      "product_sold_count":  { "type": "integer" },
      "sales_metrics_event_sequence_id": { "type": "long" },
      "platform_name":         { "type": "keyword" },
      "price_event_sequence_id": { "type": "long" },
      "created_at":          { "type": "date" },
      "updated_at":          { "type": "date" },
      "indexed_at":          { "type": "date" },
      "last_event_id":       { "type": "keyword" },
      "last_event_timestamp": { "type": "date" },

      "version":             { "type": "long" }
    }
  }
}
```

### 2.3. Giải Thích Chiến Lược Mapping Cực Hạn

| Field | Loại | Lý do & Ý nghĩa |
|---|---|---|
| `sku_id` | keyword | Là `_id` thực sự của document này. |
| `product_id` | keyword | Pivot Key cho **Field Collapsing** và các phép đếm DISTINCT product-level ở API Response. |
| `searchable_attributes` | keyword array | Mảng 1 chiều chứa chuỗi hợp thể (Composite Array Tokens) cho các thuộc tính dùng chung của toàn bộ Sản phẩm (Product-level attributes). Ví dụ: `["thuonghieu:apple", "chatlieu:titanium"]`. Dùng để hỗ trợ Filtering chung hoặc Full-text search mở rộng. **Search Worker chịu trách nhiệm transform từ `attributes_json` (raw JSON) thành token array khi consume event.** |
| `variant_attributes` | keyword array | Mảng mô tả đặc tính riêng của từng SKU (SKU-level attributes). Ví dụ: `["mau:đen", "dungluong:256GB"]`. Dùng để hỗ trợ Facet Filtering chính xác cho người dùng. **Search Worker transform từ `variant_attributes_json` sang token array.** |
| `is_in_stock` | boolean | Cờ khả dụng bán hàng trong search projection, tính theo công thức sellable `is_deleted=false AND product_status=ACTIVE AND sku_status=ACTIVE` (được recompute khi nhận `PRODUCT_SKU_STATUS_CHANGED` và `PRODUCT_STATE_CHANGED`). Không phải nguồn tồn kho realtime để chốt đơn; Checkout/Inventory vẫn là nguồn kiểm tra tồn kho cứng. |
| `is_deleted`, `deleted_at` | boolean/date | Cờ Tombstone để chặn zombie update khi event đến trễ sau delete. |
| `version` | long | **Critical**: Giữ nguyên cơ chế Optimistic Concurrency cho Product Update. |
| `price_event_sequence_id` | long | Guard thứ tự riêng cho `PRODUCT_PRICE_UPDATED` ở cấp SKU. |
| `review_metrics_event_sequence_id` | long | Guard thứ tự riêng cho `REVIEW_METRICS_UPDATED`, ưu tiên hơn timestamp. |
| `sales_metrics_event_sequence_id` | long | Guard thứ tự riêng cho `ORDER_PRODUCT_METRICS_UPDATED` theo `product_id`. |
| `last_event_id`, `last_event_timestamp`, `indexed_at` | keyword/date/date | Phục vụ audit, replay DLQ, và điều tra drift dữ liệu. |

**Count semantics cho browse/facets (bắt buộc):** Vì index là SKU-first, mọi count hiển thị cho category/facets ở API phải đếm DISTINCT `product_id` (không dùng trực tiếp `doc_count` theo SKU).

### 2.4. Mẫu Document Hoàn Chỉnh

```json
{
  "sku_id": "sku-uuid-001",
  "product_id": "prod-uuid-001",
  "category_id": "cat-smartphone",
  "name": "iPhone 15 Pro Max 256GB Chính Hãng",
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
  
  "product_rating_avg": 4.9,
  "product_rating_count": 1200,
  "product_sold_count": 15200,
  "sales_metrics_event_sequence_id": 1709420011,
    "admin_status": "VERIFIED",
  "admin_profile_version": 12,
  "admin_status_version": 7,

  "price_event_sequence_id": 1709420001,
  "review_metrics_event_sequence_id": 1709420009,
  "created_at": "2026-02-28T03:51:00Z",
  "updated_at": "2026-03-28T10:05:00Z",
  "indexed_at": "2026-03-28T10:05:01Z",
  "last_event_id": "123e4567-e89b-12d3-a456-426614174003",
  "last_event_timestamp": "2026-03-28T10:05:00Z",
  
  "version": 4
}
```

### 2.5. Demo Tính Năng Field Collapsing (Gom Nhóm)

Khi Website muốn hiển thị dạng danh sách Sản Phẩm (chứ không phải danh sách SKU tràn lan nát bộ lọc), API Search sẽ gửi Query kèm `collapse`:

```json
GET products_v1/_search
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
  "sort": [{ "best_price": "asc" }]
}
```
*   **Kết quả:** Sẽ trả về 1 kết quả đại diện cho `iPhone 15` (sản phẩm rẻ nhất màu đen), và trong `inner_hits` sẽ chứa thêm tối đa 3 loại dung lượng khác nhau cùng thuộc màu đen, giúp Web UI tha hồ "mô tả" mà không cần gọi thêm API!

## 3. Search Suggestions Index: Mapping & Ranking Contract

Index `search_suggestions_vN` phục vụ autocomplete. Đây là read-model riêng, không reuse mapping của `products_vN` để tránh query nặng không cần thiết.

### 3.1. Index Settings

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

### 3.2. Field Mapping

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
      "query_count_7d": { "type": "integer" },
      "product_sold_count_30d": { "type": "integer" },
      "ctr_30d": { "type": "half_float" },
      "conversion_rate_30d": { "type": "half_float" },
      "build_version": { "type": "long" },
      "updated_at": { "type": "date" }
    }
  }
}
```

### 3.3. Ranking Contract

1. `weight` là khóa xếp hạng chính, tính ở Suggestions Rebuilder và lưu sẵn trong document.
2. Nếu `weight` bằng nhau, query phải tie-break ổn định theo:
   - `query_count_7d` giảm dần,
   - `text.keyword` tăng dần.
3. Không tính ranking động trong API path; API chỉ đọc index đã pre-compute để giữ latency thấp.

### 3.4. Mẫu Document

```json
{
  "suggestion_id": "sgg:d4f9f7d7f5f2",
  "text": "iphone 15 pro max",
  "text_normalized": "iphone 15 pro max",
  "type": "PRODUCT",
  "source": "ANALYTICS",
  "scope": "GLOBAL",
  "category_id": "cat-smartphone",
  "product_id": "prod-uuid-001",
  "weight": 91520,
  "query_count_7d": 14500,
  "product_sold_count_30d": 4200,
  "ctr_30d": 0.31,
  "conversion_rate_30d": 0.072,
  "build_version": 2026032901,
  "updated_at": "2026-03-29T03:10:00Z"
}
```

### 3.5. Query Template (Autocomplete)

```json
GET search_suggestions/_search
{
  "query": {
    "bool": {
      "must": [
        {
          "match": {
            "text": {
              "query": "iph"
            }
          }
        }
      ]
    }
  },
  "sort": [
    { "weight": "desc" },
    { "query_count_7d": "desc" },
    { "text.keyword": "asc" }
  ],
  "size": 8,
  "_source": ["text", "type", "weight"]
}
```
