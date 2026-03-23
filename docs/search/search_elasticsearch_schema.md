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

Tài liệu này mô tả thiết kế **Elasticsearch Index** cho Search Service — phiên bản **Read Model (Query Database)** trong kiến trúc CQRS. Search Service **không sở hữu PostgreSQL**; Elasticsearch là nguồn dữ liệu duy nhất. Mọi dữ liệu được đồng bộ bất đồng bộ (Async) từ [Product Service](../product/product_db_design.md) thông qua Kafka events.

> **Nguyên tắc cốt lõi:** Search Service chỉ có 1 nhiệm vụ duy nhất: **Đọc cực nhanh**. Không có transaction phân tán; ghi vào index chỉ đến từ Kafka consumer và dùng guard `version`/`category_version` để chống out-of-order.

## 1. Danh Sách Elasticsearch Indices

| Index Name | Alias | Mô tả | Document Count (ước tính) |
|---|---|---|---|
| `products_v1` | `products` (read alias), `products_write` (write alias) | Sản phẩm denormalized phẳng | Hàng triệu |
| `search_suggestions_v1` | `search_suggestions` | Autocomplete / Gợi ý tìm kiếm | Hàng trăm ngàn |

> **Index Alias Strategy:** Sử dụng Alias để cho phép [Zero-Downtime Reindex](search_workers.md#3-full-reindex-job-zero-downtime). Khi cần thay đổi mapping, tạo `products_v2`, reindex toàn bộ dữ liệu, rồi swap alias `products` từ `v1` sang `v2`. Không gián đoạn traffic.

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
          "max_gram": 15,
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
- **`refresh_interval: 1s`**: Document mới xuất hiện trong kết quả search sau tối đa 1 giây. Acceptable trade-off giữa indexing throughput và near-real-time search.

### 2.2. Field Mapping (Document Schema: Denormalized SKU)

> **[Extreme Scale Strategy: SKU as First-Class Document]**
> Để chống chịu tải Flash Sale và tránh "Thảm họa OOM" do `nested` types, cấu trúc Document đã được làm phẳng hoàn toàn (Flattened) và xoay trục: **Mỗi Elasticsearch Document sẽ tự thân là một SKU độc lập**. Để kết nhóm hiển thị theo Product như thông thường, chúng ta sử dụng Query **Field Collapsing** (gom nhóm theo `product_id`).

```json
{
  "mappings": {
    "properties": {
      "sku_id":              { "type": "keyword" },
      "product_id":          { "type": "keyword" },
      
      "seller_id":           { "type": "keyword" },
      "category_id":         { "type": "keyword" },
      "category_version":    { "type": "long" },
      
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

      "original_price":      { "type": "long" },
      "promotional_price":    { "type": "long" },
      "best_price":          { "type": "long" },

      "is_in_stock":         { "type": "boolean" },
      "has_promotion":       { "type": "boolean" },

      "product_rating_avg":  { "type": "half_float" },
      "product_sold_count":  { "type": "integer" },

      "version":             { "type": "long" }
    }
  }
}
```

### 2.3. Giải Thích Chiến Lược Mapping Cực Hạn

| Field | Loại | Lý do & Ý nghĩa |
|---|---|---|
| `sku_id` | keyword | Là `_id` thực sự của document này. |
| `product_id` | keyword | Đóng vai trò làm Routing Key và là Pivot Key cho tính năng **Field Collapsing** (Gom cụm các sku lại thành 1 Product ở API Response). |
| `searchable_attributes` | keyword array | Mảng 1 chiều chứa chuỗi hợp thể (Composite Array Tokens) cho các thuộc tính dùng chung của toàn bộ Sản phẩm (Product-level attributes). Ví dụ: `["thuonghieu:apple", "chatlieu:titanium"]`. Dùng để hỗ trợ Filtering chung hoặc Full-text search mở rộng. **Search Worker chịu trách nhiệm transform từ `attributes_json` (raw JSON) thành token array khi consume event.** |
| `variant_attributes` | keyword array | Mảng mô tả đặc tính riêng của từng SKU (SKU-level attributes). Ví dụ: `["mau:đen", "dungluong:256GB"]`. Dùng để hỗ trợ Facet Filtering chính xác cho người dùng. **Search Worker transform từ `variant_attributes_json` sang token array.** |
| `is_in_stock` | boolean | Thay thế hoàn toàn cho `display_quantity` và `total_quantity`. Cờ boolean giảm 99.9% lưu lượng update rác vô tri vào Cluster. Chỉ update ES khi Event báo Tồn Kho rớt về 0 hoặc có hàng lại từ 0. Tồn kho thực trích xuất từ Checkout/Cart. |
| `version` | long | **Critical**: Giữ nguyên cơ chế Optimistic Concurrency cho Product Update. |
| `category_version` | long | **Lifecycle và Init:** (1) Khởi tạo bằng `categories.category_version` tại thời điểm index lần đầu (không hard-code 0). (2) Chỉ cập nhật khi nhận `CATEGORY_UPDATED`/`CATEGORY_DELETED`. (3) Track độc lập với `version` của Product document để làm OCC Guard cho update-by-query theo category. |

### 2.4. Mẫu Document Hoàn Chỉnh

```json
{
  "sku_id": "sku-uuid-001",
  "product_id": "prod-uuid-001",
  "seller_id": "seller-uuid-001",
  "category_id": "cat-smartphone",
  "category_version": 12,
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
  
  "product_rating_avg": 4.9,
  "product_sold_count": 15200,
  
  "version": 1709420000000
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
