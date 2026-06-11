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
> - **Product Service** → Mọi API liên quan đến **ghi dữ liệu** và **Point-Read nội bộ** (Cart/Order cần giá chính xác thời gian thực). Tuyệt đối **KHÔNG** dùng Search Service cho Point-Read nội bộ vì Elasticsearch có độ trễ 1-3s (Eventual Consistency). Xem [Product Workflow - Point-Read](../product/product_workflow.md#5-api-point-read-đọc-chính-xác--nhanh).

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
| `filters` | query-string map | Không | Bộ lọc động: `filters[brand]=Apple&filters[ram]=8GB` |
| `price_min` | number | Không | Giá tối thiểu |
| `price_max` | number | Không | Giá tối đa |
| `in_stock` | boolean | Không | Chỉ hiện sản phẩm còn hàng (default: true) |
| `has_promotion` | boolean | Không | Chỉ hiện sản phẩm đang giảm giá |
| `sort` | string | Không | `relevance` (default), `price_asc`, `price_desc`, `newest`, `best_selling`, `rating` |
| `page` | int | Không | Trang hiện tại (default: 1) |
| `size` | int | Không | Số lượng kết quả mỗi trang (default: 20, max: 100) |

**Quy trình chuẩn kiện (Workflow):**
1. **[Input Validation & Normalization]:**
   - Validate input parameters (page > 0, size <= 100, etc.).
   - Normalize query `q`: lowercase, trim, remove extra whitespace.
  - Chuẩn hóa canonical toàn bộ tham số ảnh hưởng kết quả (`q`, `category`, `filters`, `price_min`, `price_max`, `in_stock`, `has_promotion`, `sort`, `page`, `size`) theo quy tắc tại [Caching Schema](search_caching_schema.md#11-nhóm-search-results-cache-hot-query-cache).
   - Hash normalized query + all params → `query_hash` cho cache lookup.
2. **[Redis] - Check Hot Query Cache (Kèm SWR + Redis dedup):**
   - Lệnh đọc: `GET search:query:{query_hash}`.
  - Mỗi request phải cập nhật bộ đếm hot-query cluster-wide: `INCR search:qcount:{query_hash}:{current_bucket}` + `GET search:qcount:{query_hash}:{previous_bucket}` (sliding bucket, 2 bucket = 60 giây trượt).
  - Nếu Data rỗng (Cache Miss thực sự) → Chuyển sang Bước 3. Chỉ cache khi truy vấn đủ độ hot (`> 50 requests/phút`) để tránh cache pollution từ long-tail query.
   - Nếu Cache Hit: Bóc tách trường `valid_until` bên trong JSON.
    - **Early Refresh (bắt buộc):** Khi còn <= 5 giây tới `valid_until`, kích hoạt refresh sớm với xác suất 20% để dàn tải burst.
     - Nếu `now() <= valid_until`: Trả về kết quả cho User ngay lập tức (< 1ms). **Kết thúc.**
     - Nếu `now() > valid_until` (Dữ liệu đã cũ cần lấy mới ngầm):
       - Trả về kết quả Cũ (Stale Data) cho User ngay lập tức để giữ API Latency < 1ms.
       - Gửi ngầm một tín hiệu Async vào bounded refresh queue để lấy Data mới từ Elasticsearch.
       - **Tầng 1 (Pod-local):** Singleflight gom request trùng theo `query_hash` trong cùng Pod.
     - **Tầng 2 (Multi-pod):** Worker chỉ được query Elasticsearch khi key dedup `search:refresh:{query_hash}` còn tồn tại (key được SETNX bởi scheduler, worker xác nhận bằng GET; nếu key đã expired vì TTL tự hết, worker vẫn tiếp tục execute).
       - Nếu key đã hết hạn, worker vẫn thực thi — TTL tự bảo vệ trường hợp Pod crash giữa enqueue và execution.
     - Nếu `now() > stale_max_until` thì không trả stale quá hạn; request path phải fallback query ES trực tiếp (timeout ngắn).

### 2.1. Chi tiết Refresh Scheduler

Scheduler gọi `scheduleRefresh(query_hash)` khi phát hiện cache stale. Mục tiêu: mỗi `query_hash` stale chỉ được enqueue một lần trong toàn cụm.

**Quy tắc:**
1. Đi qua Singleflight để gom request trùng `query_hash` trong cùng Pod.
2. Thử `SETNX search:refresh:{query_hash} 1 EX {ttl}` với `ttl = max(30, 2 * es_refresh_p99_seconds)`. Đây là cơ chế dedupe đa pod duy nhất — không cần in-memory state machine.
3. Nếu SETNX fail → key đã tồn tại, có pod khác đã enqueue. Dừng, không enqueue thêm.
4. Nếu SETNX success → `offer` task vào bounded queue. Nếu queue full: `DEL search:refresh:{query_hash}` (reset dedup key) và tăng metric `search_refresh_queue_dropped_total`.
5. Worker hoàn tất task (thành công hoặc lỗi) phải `DEL search:refresh:{query_hash}` trong `finally`. Nếu Pod crash trước DEL, TTL tự hết hạn và key không còn ngăn pod khác enqueue lần tiếp theo.

### 2.2. Semantics của Bounded Queue và Worker

1. Queue là thread-safe, thao tác lấy task là destructive (`take/pop`).
2. Một queue-node chỉ có một worker lấy; worker khác không thể lấy cùng queue-node đó.
3. Trùng lặp chỉ có thể đến từ enqueue nhiều node khác nhau cho cùng `query_hash`; đã được chặn bằng Redis dedup key (SETNX).
4. Queue full policy: không block request path, vẫn trả stale data, chỉ bỏ refresh task có kiểm soát và ghi metric/alert.
5. Không được phục vụ stale nếu đã vượt `stale_max_until`; phải chuyển sang query ES trực tiếp với timeout.
6. Queue nằm trên RAM Pod; nếu Pod down thì task có thể mất. Với SWR đây là chấp nhận được, vì request kế tiếp sẽ trigger refresh lại. Redis dedup key có TTL riêng nên task không bị enqueue trùng bởi pod khác trong thời gian TTL.

**Tiếp tục luồng chính API search:**
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
            "sort": [
              { "best_price": "asc" }
            ]
          }
        },
        "sort": [
          { "_score": "desc" },
          { "product_sold_count": "desc" }
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
      - `is_in_stock` ở Search là cờ sellable projection (`is_deleted=false AND product_status=ACTIVE AND sku_status=ACTIVE`), không thay cho kiểm tra tồn kho cứng ở checkout.
      - `searchable_attributes`: Thay thế hoàn toàn cho kiểu `nested`, search/filter trực tiếp trên Composite Array.
      - `collapse`: Gom nhóm các SKU (Document) thuộc cùng 1 sản phẩm (`product_id`), chỉ trả về SKU best match cho UI.
      - Các count hiển thị cho facets/category phải map từ aggregation `product_count` (DISTINCT `product_id`), không dùng trực tiếp doc_count theo SKU.

    - **Sort mapping bắt buộc:**
      - `relevance` -> `_score desc, product_sold_count desc`
      - `price_asc` -> `best_price asc, _score desc`
      - `price_desc` -> `best_price desc, _score desc`
      - `newest` -> `updated_at desc, _score desc`
      - `best_selling` -> `product_sold_count desc, _score desc`
      - `rating` -> `product_rating_avg desc, product_rating_count desc`

4. **[Response Transformation] - Chuẩn hoá kết quả:**
   - Map Elasticsearch hits → API response format. Kết quả trả về đã là SKU (First-class document).
   - `best_price` và `has_promotion` đã được tính sẵn, không cần loop ở App Level nữa.
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
           "product_sold_count": 1500,
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
5. **[Redis] - Cache kết quả (nếu hot query):**
  - Nếu query đủ "hot" → `SET search:query:{query_hash} json EX 86400`.
  - JSON phải chứa `valid_until = now + 30s` và `stale_max_until = valid_until + 240s` để điều khiển stale cap.
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
   - Query dựa trên mapping của index `search_suggestions` (đã pre-compute ranking), không truy vấn trực tiếp từ `products` trong request path.
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
Số đếm phải bám theo cùng bộ lọc hiển thị mặc định của API search list (không deleted, product active, sku active, in_stock=true) để tránh lệch UX.

**Quy trình chuẩn kiện (Workflow):**
1. **[Redis] - Check Category Cache:**
   - `GET search:categories:tree`.
   - Cache hit → trả về. **Kết thúc.**
2. **[Elasticsearch] - Aggregation Query:**
   ```json
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
3. **[Enrichment]:** Map `category_id` → thông tin đầy đủ (name, slug, parent_id, level) từ Category metadata; `product_count` lấy từ `categories.*.product_count`.
4. **[Redis] - Cache:** `SET search:categories:tree json EX 3600`.
5. **[Backend] - Trả HTTP 200.**

## 5. API: Lấy Facets cho Category Page
**Endpoint:** `GET /api/v1/search/facets/{category_id}`

Trả về danh sách bộ lọc (filters) khả dụng cho một danh mục cụ thể.

**Quy trình:**
1. **[Redis]:** `GET search:facets:{category_id}`. Hit → trả về.
2. **[Elasticsearch]:** Chạy aggregation trên sản phẩm thuộc category (brands, price ranges, dynamic attributes) với count semantics DISTINCT `product_id`.
3. **[Redis]:** `SET search:facets:{category_id} json EX 300`.
4. **[Backend]:** Trả HTTP 200. Xem [Khuôn mẫu JSON](search_caching_schema.md#14-facet-cache-aggregation-precomputed).

## 6. Phân Biệt API Search Service vs Product Service

| Tiêu chí | Search Service | Product Service |
|---|---|---|
| **Database** | Elasticsearch (Read Model) | PostgreSQL (Write Model / Source of Truth) |
| **Đối tượng gọi** | End-User (Buyer) browsing | Cart/Order Service (internal) |
| **Dữ liệu** | Denormalized, pre-computed | Normalized, transactional |
| **Tính nhất quán** | Eventual (delay 1-3s) | Strong (realtime) |
| **Use case** | Full-text search, faceted filter, autocomplete | Point-Read giá chính xác, tạo/sửa sản phẩm |
| **API prefix** | `/api/v1/search/...` | `/api/v1/products/...` |
| **Caching** | Redis (hot queries: logical 30s, physical 24h) | Redis + Caffeine (validation, 1h TTL) |

