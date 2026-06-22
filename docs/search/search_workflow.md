# Search Service - Workflows (API & Data Flow)

**Liên kết nhanh (Related Documents):**
*   [Elasticsearch Schema (Read Store)](search_elasticsearch_schema.md)
*   [Background Workers & Jobs](search_workers.md)
*   [Events Consumed & Kafka Topics](search_events.md)
*   [Architecture Recommendations](search_architecture_recommendations.md)

**Cross-Service References:**
*   [Product Service - Workflow (Point-Read API)](../product/product_workflow.md#5-api-point-read-đọc-chính-xác--nhanh)
*   [Product Service - Events](../product/product_events.md)

---

Tài liệu này mô tả chi tiết trình tự các bước thực thi cho tất cả API public-facing của Search Service.

> **Nguyên tắc phân chia trách nhiệm:**
> - **Search Service** → Mọi API liên quan đến **tìm kiếm, lọc, gợi ý** cho End-User (Buyer browsing).
> - **Product Service** → Mọi API liên quan đến **ghi dữ liệu** và **Point-Read nội bộ** (Cart/Order cần giá chính xác thời gian thực). Tuyệt đối **KHÔNG** dùng Search Service cho Point-Read nội bộ vì Elasticsearch có độ trễ 1-3s (Eventual Consistency). Xem [Product Workflow - Point-Read](../product/product_workflow.md#5-api-point-read-đọc-chính-xác--nhanh).

## 1. Gợi Ý Cấu Trúc Base API Path
`[GET/POST] /api/v1/search/...`

## 2. API: Tìm Kiếm Sản Phẩm (Full-Text Search + Filters + Facets)

**Endpoint:** `GET /api/v1/search/products`

API cốt lõi nhất của Search Service — phục vụ trang Danh sách sản phẩm, trang kết quả tìm kiếm, và mọi luồng browsing của End-User.

**Query Parameters:**
| Param | Loại | Bắt buộc | Mô tả |
|---|---|---|---|
| `q` | string | Không | Từ khóa tìm kiếm (VD: "iphone 15 pro max") |
| `category` | string | Không | Category ID hoặc slug để lọc theo danh mục |
| `filters` | query-string map | Không | Bộ lọc động: `filters[brand]=Apple&filters[ram]=8GB` |
| `price_min` | number | Không | Giá tối thiểu |
| `price_max` | number | Không | Giá tối đa |
| `in_stock` | boolean | Không | Chỉ hiện sản phẩm còn hàng (default: true) |
| `has_promotion` | boolean | Không | Chỉ hiện sản phẩm đang giảm giá |
| `sort` | string | Không | `relevance` (default), `price_asc`, `price_desc`, `newest`, `rating` |
| `page` | int | Không | Trang hiện tại (default: 1) |
| `size` | int | Không | Số lượng kết quả mỗi trang (default: 20, max: 100) |

**Quy trình chuẩn kiện (Workflow):**

1. **[Input Validation & Normalization]:**
   - Validate input parameters (page > 0, size <= 100, etc.).
   - Normalize query `q`: lowercase, trim, remove extra whitespace.
   - Chuẩn hoá canonical toàn bộ tham số (`q`, `category`, `filters`, `price_min`, `price_max`, `in_stock`, `has_promotion`, `sort`, `page`, `size`).

2. **[Elasticsearch] - Build & Execute Query:**
   - Xây dựng Elasticsearch query DSL:
     ```json
     {
       "query": {
         "bool": {
           "must": [
             {
               "multi_match": {
                 "query": "iphone 15 pro max",
                 "fields": ["name^3", "name.keyword^2", "searchable_attributes"],
                 "type": "best_fields",
                 "fuzziness": "AUTO"
               }
             }
           ],
           "filter": [
             { "term": { "is_deleted": false } },
             { "term": { "product_status": "ACTIVE" } },
             { "term": { "sku_status": "ACTIVE" } },
             { "term": { "is_in_stock": true } },
             { "term": { "category_id": "cat-smartphone" } },
             { "range": { "best_price": { "gte": 20000000, "lte": 40000000 } } },
             { "term": { "searchable_attributes": "thuonghieu:apple" } },
             { "term": { "variant_attributes": "mausac:titan-tu-nhien" } }
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
          { "_score": "desc" }
        ],
       "from": 0,
       "size": 20,
       "aggs": {
         "categories": {
           "terms": { "field": "category_id", "size": 20 },
           "aggs": {
             "product_count": {
               "cardinality": {
                 "field": "product_id",
                 "precision_threshold": 40000
               }
             }
           }
         },
         "price_ranges": {
           "range": {
             "field": "best_price",
             "ranges": [
               { "key": "Dưới 5 triệu", "to": 5000000 },
               { "key": "5 - 15 triệu", "from": 5000000, "to": 15000000 },
               { "key": "15 - 30 triệu", "from": 15000000, "to": 30000000 },
               { "key": "Trên 30 triệu", "from": 30000000 }
             ]
           },
           "aggs": {
             "product_count": {
               "cardinality": {
                 "field": "product_id",
                 "precision_threshold": 40000
               }
             }
           }
         }
       }
     }
     ```
   - **Giải thích Query:**
     - `multi_match` với `fuzziness: AUTO`: cho phép typo (VD: "iphnoe" vẫn match "iPhone").
     - `name^3`: Boost trọng số tên sản phẩm so với attributes.
     - `filter` context: Các điều kiện lọc cứng, được ES cache bitset tự động.
     - `in_stock`: khi tham số `in_stock=false` thì bỏ `term is_in_stock=true` khỏi filter.
     - `is_in_stock` ở Search là cờ sellable projection, không thay cho kiểm tra tồn kho cứng ở checkout.
     - `searchable_attributes`: Thay thế hoàn toàn cho kiểu `nested`, search/filter trực tiếp trên Composite Array.
     - `collapse`: Gom nhóm các SKU theo `product_id`, chỉ trả về SKU best match cho UI.
     - Mọi count trong facets phải đếm DISTINCT `product_id` qua `product_count` cardinality aggregation.

- **Sort mapping bắt buộc:**
      - `relevance` → `_score desc`
      - `price_asc` → `best_price asc, _score desc`
      - `price_desc` → `best_price desc, _score desc`
      - `newest` → `updated_at desc, _score desc`
      - `rating` → `product_rating_avg desc, product_rating_count desc`

3. **[Response Transformation] - Chuẩn hoá kết quả:**
   - Map Elasticsearch hits → API response format. Kết quả trả về đã là SKU (First-class document).
   - `best_price` và `has_promotion` đã được tính sẵn, không cần loop ở App Level.
   - `discount_percentage` (nếu trả ra API) phải tính theo công thức duy nhất: `floor(((original_price - best_price) / original_price) * 100)` khi `original_price > 0` và `best_price < original_price`; ngược lại trả `0`.
   - **Response JSON:**
     ```json
     {
       "total": 1100,
       "total_skus": 1250,
       "total_products": 1100,
       "page": 1,
       "size": 20,
"items": [
          {
            "product_id": "prod-123",
            "sku_id": "sku-abc",
            "name": "iPhone 15 Pro Max 256GB - Titan Tự nhiên",
            "thumbnail_url": "https://img.cdn/iphone15.jpg",
            "original_price": 31900000,
            "best_price": 29500000,
            "discount_percentage": 7,
            "product_rating_avg": 4.9,
            "is_in_stock": true
          }
        ],
       "facets": {
         "count_semantics": "DISTINCT_PRODUCT",
         "categories": [{ "value": "Smartphone", "count": 800 }],
         "price_ranges": [{ "label": "15 - 30 triệu", "count": 350 }],
         "brands": [{ "value": "Apple", "count": 200 }]
       },
       "pagination_note": "size là số product groups sau collapse; mỗi item có thể kèm nhiều SKU trong inner_hits"
     }
     ```

4. **[Backend] - Trả HTTP 200:**
   - Trả JSON response cho Frontend.


## 3. API: Gợi Ý Tìm Kiếm (Autocomplete / Search-as-you-type)

**Endpoint:** `GET /api/v1/search/suggest`

User đang gõ trên thanh tìm kiếm → hiện dropdown gợi ý real-time.

**Query Parameters:**
| Param | Loại | Bắt buộc | Mô tả |
|---|---|---|---|
| `q` | string | Có | Prefix đang gõ (VD: "ip", "iph", "iphon") |
| `limit` | int | Không | Số lượng gợi ý (default: 8) |

**Quy trình chuẩn kiện (Workflow):**

1. **[Validation]:** `q` phải có ít nhất 2 ký tự.
2. **[Elasticsearch] - Query Suggestions Index:**
   ```json
   {
     "query": {
       "match": { "text": { "query": "iph" } }
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
   - Query dựa trên index `search_suggestions` (đã pre-compute ranking).
3. **[Backend] - Trả HTTP 200:**
   ```json
   {
     "suggestions": [
       { "text": "iphone 15 pro max", "type": "PRODUCT" },
       { "text": "iphone 14", "type": "PRODUCT" },
       { "text": "ipad air", "type": "PRODUCT" },
       { "text": "Điện thoại", "type": "CATEGORY" }
     ]
   }
   ```

**Latency budget:** p50 = 8ms, p99 = 25ms.

## 4. API: Duyệt Danh Mục (Category Browsing)

**Endpoint:** `GET /api/v1/search/categories`

Trả về cây danh mục với số lượng sản phẩm ACTIVE cho navigation sidebar.
Số đếm phải bám theo cùng bộ lọc hiển thị mặc định của API search list (không deleted, product active, sku active, in_stock=true) để tránh lệch UX.

**Quy trình chuẩn kiện (Workflow):**

1. **[Elasticsearch] - Query Categories Index:**
   ```json
   GET categories/_search
   {
     "size": 1000,
     "sort": [{ "level": "asc" }, { "category_id": "asc" }]
   }
   ```
   - Lấy toàn bộ cây danh mục từ alias `categories` (đang trỏ tới `categories_v1`).
   - Nếu muốn số đếm realtime theo filter hiển thị mặc định, chạy thêm aggregation:
     ```json
     POST products/_search
     {
       "size": 0,
       "query": {
         "bool": {
           "filter": [
             { "term": { "is_deleted": false } },
             { "term": { "product_status": "ACTIVE" } },
             { "term": { "sku_status": "ACTIVE" } },
             { "term": { "is_in_stock": true } }
           ]
         }
       },
       "aggs": {
         "categories": {
           "terms": { "field": "category_id", "size": 500 },
           "aggs": {
             "product_count": {
               "cardinality": { "field": "product_id", "precision_threshold": 40000 }
             }
           }
         }
       }
     }
     ```
2. **[Enrichment]:** Map `category_id` → thông tin đầy đủ (name, slug, parent_id, level) từ Categories index; `product_count` lấy từ `categories.*.product_count` aggregation (DISTINCT `product_id`).
3. **[Backend] - Trả HTTP 200.**


## 5. API: Lấy Facets cho Category Page

**Endpoint:** `GET /api/v1/search/facets/{category_id}`

Trả về danh sách bộ lọc (filters) khả dụng cho một danh mục cụ thể.

**Quy trình:**

1. **[Elasticsearch] - Aggregation Query:**
   - Chạy aggregation trên sản phẩm thuộc category (brands, price ranges, dynamic attributes) với count semantics DISTINCT `product_id`.
   - Mỗi `terms` aggregation cần inner `cardinality` aggregation trên `product_id` để đảm bảo count chính xác.
2. **[Backend] - Trả HTTP 200.** Xem [Khuôn mẫu JSON](#51-khuôn-mẫu-response-facets).

### 5.1. Khuôn mẫu Response (Facets)

```json
{
  "category_id": "cat-smartphone",
  "count_semantics": "DISTINCT_PRODUCT",
  "filters": [
    {
      "name": "Thương hiệu",
      "type": "CHECKBOX",
      "options": [
        { "value": "Apple", "count": 200 },
        { "value": "Samsung", "count": 180 },
        { "value": "Xiaomi", "count": 150 }
      ]
    },
    {
      "name": "Khoảng giá",
      "type": "RANGE",
      "options": [
        { "value": "0-5000000", "label": "Dưới 5 triệu", "count": 300 },
        { "value": "5000000-15000000", "label": "5 - 15 triệu", "count": 500 }
      ]
    }
  ],
  "total_products": 1250
}
```


## 6. Phân Biệt API Search Service vs Product Service

| Tiêu chí | Search Service | Product Service |
|---|---|---|
| **Database** | Elasticsearch (Read Model) | PostgreSQL (Write Model / Source of Truth) |
| **Đối tượng gọi** | End-User (Buyer) browsing | Cart/Order Service (internal) |
| **Dữ liệu** | Denormalized, pre-computed | Normalized, transactional |
| **Tính nhất quán** | Eventual (delay 1-3s) | Strong (realtime) |
| **Use case** | Full-text search, faceted filter, autocomplete | Point-Read giá chính xác, tạo/sửa sản phẩm |
| **API prefix** | `/api/v1/search/...` | `/api/v1/products/...` |
| **Caching** | Không có (ES query cache nội bộ) | Redis + Caffeine (validation, 1h TTL) |