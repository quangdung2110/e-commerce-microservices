# Search Service - Trình Tự Nghiệp Vụ API (Workflows for Extreme Scale)

**Liên kết nhanh (Related Documents):**
*   [Elasticsearch Schema (Read Store)](search_elasticsearch_schema.md)
*   [Caching Schema (Redis)](search_caching_schema.md)
*   [Workflows (API & Data Flow)](search_workflow.md)
*   [Background Workers & Jobs](search_workers.md)
*   [Events Consumed & Kafka Topics](search_events.md)
*   [Architecture Recommendations](search_architecture_recommendations.md)

**Cross-Service References:**
*   [Product Service - Workflow (Point-Read API)](../product/product_workflow.md#5-api-point-read-đọc-chính-xác--nhanh)
*   [Product Service - Events](../product/product_events.md)

---

Tài liệu này mô tả chi tiết trình tự các bước thực thi cho tất cả API public-facing của Search Service. Khác với [Product Service Workflow](../product/product_workflow.md) (PostgreSQL-First, Write Model), Search Service tuân thủ kiến trúc **Elasticsearch-First, Read-Only Model**.

> **Nguyên tắc phân chia trách nhiệm:**
> - **Search Service** → Mọi API liên quan đến **tìm kiếm, lọc, gợi ý** cho End-User (Buyer browsing).
> - **Product Service** → Mọi API liên quan đến **ghi dữ liệu** (Seller CRUD) và **Point-Read nội bộ** (Cart/Order cần giá chính xác thời gian thực). Tuyệt đối **KHÔNG** dùng Search Service cho Point-Read nội bộ vì Elasticsearch có độ trễ 1-3s (Eventual Consistency). Xem [Product Workflow - Point-Read](../product/product_workflow.md#5-api-point-read-đọc-chính-xác--nhanh).

## 1. Gợi Ý Cấu Trúc Base API Path
`[GET/POST] /api/v1/search/...`

## 2. API: Tìm Kiếm Sản Phẩm (Full-Text Search + Filters + Facets)
**Endpoint:** `GET /api/v1/search/products`

Đây là API cốt lõi nhất của Search Service — phục vụ trang Danh sách sản phẩm, trang kết quả tìm kiếm, và mọi luồng browsing của End-User.

**Query Parameters:**
| Param | Loại | Bắt buộc | Mô tả |
|---|---|---|---|
| `q` | string | Không | Từ khóa tìm kiếm (VD: "iphone 15 pro max") |
| `category` | string | Không | Category ID hoặc slug để lọc theo danh mục |
| `filters` | object/string | Không | Bộ lọc động: `filters[brand]=Apple&filters[ram]=8GB` |
| `price_min` | number | Không | Giá tối thiểu |
| `price_max` | number | Không | Giá tối đa |
| `in_stock` | boolean | Không | Chỉ hiện sản phẩm còn hàng (default: true) |
| `has_promotion` | boolean | Không | Chỉ hiện sản phẩm đang giảm giá |
| `seller_id` | string | Không | Lọc theo gian hàng Seller |
| `sort` | string | Không | `relevance` (default), `price_asc`, `price_desc`, `newest`, `best_selling`, `rating` |
| `page` | int | Không | Trang hiện tại (default: 1) |
| `size` | int | Không | Số lượng kết quả mỗi trang (default: 20, max: 100) |

**Quy trình chuẩn kiện (Workflow):**
1. **[Input Validation & Normalization]:**
   - Validate input parameters (page > 0, size <= 100, etc.).
   - Normalize query `q`: lowercase, trim, remove extra whitespace.
   - Hash normalized query + all params → `query_hash` cho cache lookup.
2. **[Redis] - Check Hot Query Cache (Kèm Logical Expiration & Distributed Lock):**
   - Lệnh đọc: `GET search:query:{query_hash}`.
   - Nếu Data rỗng (Cache Miss thực sự) → Chuyển sang Bước 3, nhưng phải có lệnh đếm Sliding Window `INCR` trước, nếu truy vấn đủ độ Hot thì khi Elasticsearch lấy được data mới ghi `SET` vào Cache (Bảo vệ Ram khởi rác Long-tail Query).
   - Nếu Cache Hit: Bóc tách trường `valid_until` bên trong JSON.
     - Nếu `now() <= valid_until`: Trả về kết quả cho User ngay lập tức (< 1ms). **Kết thúc.**
     - Nếu `now() > valid_until` (Dữ liệu đã cũ cần lấy mới ngầm): 
       - Trả về kết quả Cũ (Stale Data) cho User ngay lập tức để giữ API Latency < 1ms.
       - Gửi ngầm một tín hiệu Async (Goroutine) để lấy Data mới từ Elasticsearch. 
       - **[Chống Thundering Herd]:** Thread Async này TRƯỚC KHI Query Elasticsearch BẮT BUỘC phải lấy Lock: `SETNX search:lock:refresh:{query_hash} 1 EX 10`. Chỉ Thread nào lấy Lock thành công mới được nhảy đến Bước 3 để Query ElasticSearch và nạp đè `SET` lại Cache. Các Thread thất bại Lock bị hủy ngay tắp lự.
3. **[Elasticsearch] - Build & Execute Query:**
    - Xây dựng Elasticsearch query DSL:
      ```json
      {
        "query": {
          "bool": {
            "must": [
              {
                "multi_match": {
                  "query": "iphone 15 pro max",
                  "fields": ["name^3", "name.raw^2", "searchable_attributes"],
                  "type": "best_fields",
                  "fuzziness": "AUTO"
                }
              }
            ],
            "filter": [
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
          "field": "product_id"
        },
        "sort": [
          { "_score": "desc" },
          { "product_sold_count": "desc" }
        ],
        "from": 0,
        "size": 20,
        "aggs": {
          "categories": { "terms": { "field": "category_id", "size": 20 } },
          "price_ranges": {
            "range": {
              "field": "best_price",
              "ranges": [
                { "key": "Dưới 5 triệu", "to": 5000000 },
                { "key": "5 - 15 triệu", "from": 5000000, "to": 15000000 },
                { "key": "15 - 30 triệu", "from": 15000000, "to": 30000000 },
                { "key": "Trên 30 triệu", "from": 30000000 }
              ]
            }
          }
        }
      }
      ```
    - **Giải thích Query:**
      - `multi_match` với `fuzziness: AUTO`: cho phép typo (VD: "iphnoe" vẫn match "iPhone").
      - `name^3`: Boost trọng số tên sản phẩm so với attributes.
      - `filter` context: Các điều kiện lọc cứng, được ES cache bitset tự động.
      - `searchable_attributes`: Thay thế hoàn toàn cho kiểu `nested`, search/filter trực tiếp trên Composite Array.
      - `collapse`: Gom nhóm các SKU (Document) thuộc cùng 1 sản phẩm (`product_id`), chỉ trả về SKU best match cho UI.

4. **[Response Transformation] - Chuẩn hoá kết quả:**
   - Map Elasticsearch hits → API response format. Kết quả trả về đã là SKU (First-class document).
   - `best_price` và `has_promotion` đã được tính sẵn, không cần loop ở App Level nữa.
   - **Response JSON:**
     ```json
     {
       "total": 1250,
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
           "sold_count": 1500,
           "rating": 4.9,
           "is_in_stock": true
         }
       ],
       "facets": {
         "categories": [{ "value": "Smartphone", "count": 800 }],
         "price_ranges": [{ "label": "15 - 30 triệu", "count": 350 }],
         "brands": [{ "value": "Apple", "count": 200 }]
       }
     }
     ```
5. **[Redis] - Cache kết quả (nếu hot query):**
   - Nếu query đủ "hot" → `SET search:query:{hash} json EX 30`.
6. **[Backend] - Trả HTTP 200:**
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
2. **[Redis] - Check Suggestion Cache:**
   - Normalize prefix: lowercase, trim.
   - `GET search:suggest:{prefix}` (chỉ lấy 2-4 ký tự đầu làm cache key).
   - Cache hit → filter + limit → trả về. **Kết thúc.**
3. **[Elasticsearch] - Query Suggestions Index:**
   ```json
   {
     "query": {
       "bool": {
         "must": [
           { "match": { "text": { "query": "iph", "analyzer": "standard" } } }
         ]
       }
     },
     "sort": [{ "weight": "desc" }],
     "size": 8,
     "_source": ["text", "type", "weight"]
   }
   ```
4. **[Redis] - Cache kết quả:**
   - `SET search:suggest:{prefix} json EX 600` (10 phút).
5. **[Backend] - Trả HTTP 200:**
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

## 4. API: Duyệt Danh Mục (Category Browsing)
**Endpoint:** `GET /api/v1/search/categories`

Trả về cây danh mục với số lượng sản phẩm ACTIVE cho navigation sidebar.

**Quy trình chuẩn kiện (Workflow):**
1. **[Redis] - Check Category Cache:**
   - `GET search:categories:tree`.
   - Cache hit → trả về. **Kết thúc.**
2. **[Elasticsearch] - Aggregation Query:**
   ```json
   {
     "size": 0,
     "query": { "term": { "product_status": "ACTIVE" } },
     "aggs": {
       "categories": {
         "terms": { "field": "category_id", "size": 500 }
       }
     }
   }
   ```
3. **[Enrichment]:** Map `category_id` → thông tin đầy đủ (name, slug, parent_id, level) từ Category metadata.
4. **[Redis] - Cache:** `SET search:categories:tree json EX 3600`.
5. **[Backend] - Trả HTTP 200.**

## 5. API: Lấy Facets cho Category Page
**Endpoint:** `GET /api/v1/search/facets/{category_id}`

Trả về danh sách bộ lọc (filters) khả dụng cho một danh mục cụ thể.

**Quy trình:**
1. **[Redis]:** `GET search:facets:{category_id}`. Hit → trả về.
2. **[Elasticsearch]:** Chạy aggregation trên sản phẩm thuộc category (brands, price ranges, dynamic attributes).
3. **[Redis]:** `SET search:facets:{category_id} json EX 300`.
4. **[Backend]:** Trả HTTP 200. Xem [Khuôn mẫu JSON](search_caching_schema.md#14-facet-cache-aggregation-precomputed).

## 6. Phân Biệt API Search Service vs Product Service

| Tiêu chí | Search Service | Product Service |
|---|---|---|
| **Database** | Elasticsearch (Read Model) | PostgreSQL (Write Model / Source of Truth) |
| **Đối tượng gọi** | End-User (Buyer) browsing | Seller CRUD, Cart/Order Service (internal) |
| **Dữ liệu** | Denormalized, pre-computed | Normalized, transactional |
| **Tính nhất quán** | Eventual (delay 1-3s) | Strong (realtime) |
| **Use case** | Full-text search, faceted filter, autocomplete | Point-Read giá chính xác, tạo/sửa sản phẩm |
| **API prefix** | `/api/v1/search/...` | `/api/v1/products/...` |
| **Caching** | Redis (hot queries, 30s TTL) | Redis + Caffeine (validation, 1h TTL) |
