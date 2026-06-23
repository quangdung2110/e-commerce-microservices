# Search Service - Workflows (API & Data Flow)

**Liên kết nhanh (Related Documents):**
*   [Service Overview (Tech Stack, Deployment, Observability)](search_service_overview.md)
*   [Elasticsearch Schema (Read Store)](search_elasticsearch_schema.md)
*   [Background Workers & Jobs](search_workers.md)
*   [Events Consumed & Kafka Topics](search_events.md)
*   [Architecture Recommendations](search_architecture_recommendations.md)

**Cross-Service References:**
*   [Product Service - Workflow (Background Workers)](../product/product_workflow.md)
*   [Product Service - Events](../product/product_events.md)
*   [Service Contract Matrix (SSOT)](../_contracts/CONTRACT_MATRIX.md)

---

Tài liệu này mô tả chi tiết trình tự các bước thực thi cho tất cả API public-facing của Search Service.

> **Trạng thái Phase đã áp dụng (đồng bộ với [CHANGELOG](../_contracts/CHANGELOG.md)):**
> - **Phase 21**: Bỏ sort `best_selling` + field `product_sold_count` khỏi API public.
> - **Phase 22b**: Bỏ DLQ-related error codes khỏi API responses.
> - **Pagination strategy:** Cursor-based với ES `search_after` (thay cho offset `page`/`size` để tránh giới hạn 10000 result window).
> - **Auth:** JWT trust từ API Gateway (Search Service không tự validate signature).
> - **Error format:** Canonical `{ error_code, message, details }` theo contract chung.

> **Nguyên tắc phân chia trách nhiệm:**
> - **Search Service** → Mọi API liên quan đến **tìm kiếm, lọc, gợi ý** cho End-User (Buyer browsing).
> - **Product Service** → Mọi API liên quan đến **ghi dữ liệu** và **Point-Read nội bộ** (Cart/Order cần giá chính xác thời gian thực). Tuyệt đối **KHÔNG** dùng Search Service cho Point-Read nội bộ vì Elasticsearch có độ trễ 1-3s (Eventual Consistency).

## 0. Cross-Cutting Concerns (Áp dụng cho mọi API)

### 0.1. Authentication & JWT Claims

**Auth model:** API Gateway xác thực JWT, sau đó forward request đến Search Service với các claims trong header `X-User-Claims` (Gateway-injected) hoặc trong body claim của JWT signature (Gateway verify rồi pass-through).

**Search Service trust 4 claims từ Gateway (cho End-User API):**
- `user_id` (UUID): dùng cho audit log, structured logging (MDC context).
- `role` (BUYER | ADMIN): dùng cho authorization check.
- `request_id` (UUID): dùng cho distributed tracing, log correlation.
- `session_id` (UUID): dùng cho analytics, debugging.

**Nếu JWT missing hoặc invalid:** Gateway trả `401 UNAUTHORIZED` trước khi request đến Search Service. Search Service không bao giờ nhận request không có JWT.

### 0.2. CORS & Security Headers

**CORS policy (chỉ dev hiện tại):** Wildcard `*` cho mọi origin. Production sẽ restrict sau khi frontend xác định domain chính thức.

**Security headers (mọi response):**
- `X-Content-Type-Options: nosniff`
- `X-Frame-Options: DENY`
- `Strict-Transport-Security: max-age=31536000; includeSubDomains`
- `Referrer-Policy: no-referrer`

### 0.3. Error Response Format (Canonical)

Mọi error response tuân theo format chuẩn toàn hệ thống:

```json
{
  "error_code": "INVALID_QUERY_PARAMETER",
  "message": "Query parameter 'size' must be between 1 and 100",
  "details": {
    "parameter": "size",
    "received_value": 500,
    "allowed_range": "1-100"
  },
  "request_id": "req-uuid-12345"
}
```

**Common error codes:**

| HTTP Status | Error Code | Mô tả |
|---|---|---|
| 400 | `INVALID_QUERY_PARAMETER` | Query param sai format / vượt range |
| 400 | `INVALID_CURSOR` | Cursor base64 decode fail hoặc expired |
| 401 | `UNAUTHORIZED` | JWT missing/invalid (do Gateway trả, không đến Search) |
| 403 | `FORBIDDEN` | Role không đủ quyền |
| 404 | `CATEGORY_NOT_FOUND` | Category ID không tồn tại |
| 422 | `INVALID_LANGUAGE` | Ngôn ngữ không hỗ trợ |
| 500 | `INTERNAL_ERROR` | Lỗi không xác định |
| 503 | `ELASTICSEARCH_UNAVAILABLE` | ES cluster down / timeout |
| 504 | `SEARCH_TIMEOUT` | ES query vượt timeout (5s product / 2s suggest) |

### 0.4. Health Check Endpoints

**Endpoints (Spring Boot Actuator):**
- `GET /actuator/health/liveness` — Process alive (chỉ check JVM running). 200 nếu OK.
- `GET /actuator/health/readiness` — Sẵn sàng nhận traffic. Check: ES cluster connect OK + Kafka consumer subscribed.

**Cách test:**
```bash
curl -i http://search-service:8080/actuator/health/liveness
# Expected: HTTP/1.1 200 OK
# {"status":"UP"}

curl -i http://search-service:8080/actuator/health/readiness
# Expected: HTTP/1.1 200 OK nếu ES + Kafka OK
# Expected: HTTP/1.1 503 Service Unavailable nếu có dependency fail
```

## 1. Gợi ý Cấu Trúc Base API Path

**Public API (End-User):** `[GET/POST] /api/v1/search/...`
**Internal API (Admin):** `[GET/POST] /internal/v1/search/...`

API versioning: Path versioning `/api/v1/...` (breaking change → `/api/v2/...` chạy song song).

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
| `cursor` | string (base64) | Không | Cursor từ response trước (lần đầu gọi = không có) |
| `size` | int | Không | Số lượng kết quả mỗi trang (default: 20, max: 100) |

> **Pagination:** Cursor-based với ES `search_after`. Cursor là chuỗi base64 chứa JSON `[sort_value, sku_id]` (mảng sort values cuối cùng của response trước). Tie-break bằng `sku_id asc` ở cuối sort đảm bảo pagination ổn định (xem [Architecture Recommendations §3](search_architecture_recommendations.md)).

**Quy trình chuẩn kiện (Workflow):**

1. **[Input Validation & Normalization]:**
   - Validate input parameters (size 1-100, etc.).
   - Normalize query `q`: lowercase, trim, remove extra whitespace.
   - Chuẩn hoá canonical toàn bộ tham số (`q`, `category`, `filters`, `price_min`, `price_max`, `in_stock`, `has_promotion`, `sort`, `cursor`, `size`).
   - **Nếu `cursor` có:** decode base64 → parse JSON `[sort_value, sku_id]`. Nếu parse fail → trả `400 INVALID_CURSOR`.

2. **[Elasticsearch] - Build & Execute Query:**
   - Xây dựng Elasticsearch query DSL với `search_after`:
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
         "sort": [{ "best_price": "asc" }, { "sku_id": "asc" }]
       }
     },
     "sort": [
       { "best_price": "asc" },
       { "sku_id": "asc" }
     ],
     "search_after": [<sort_value_from_cursor>, "<sku_id_from_cursor>"],
     "size": 20,
     "track_total_hits": true,
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
     - **Mọi sort đều có `sku_id asc` ở cuối** làm tie-break để pagination ổn định.
     - Mọi count trong facets phải đếm DISTINCT `product_id` qua `product_count` cardinality aggregation.
     - `track_total_hits: true` để trả về `total` chính xác (mặc định ES cap 10000; với search_after, không còn bị giới hạn này).

- **Sort mapping bắt buộc (mỗi sort đều có tie-break `sku_id asc` ở cuối):**
   - `relevance` → `_score desc, sku_id asc`
   - `price_asc` → `best_price asc, sku_id asc`
   - `price_desc` → `best_price desc, sku_id asc`
   - `newest` → `updated_at desc, sku_id asc`
   - `rating` → `product_rating_avg desc, product_rating_count desc, sku_id asc`

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
     "next_cursor": "WzI5NTAwMDAwLCJza3UtYWJjIl0=",
     "facets": {
       "count_semantics": "DISTINCT_PRODUCT",
       "categories": [{ "value": "Smartphone", "count": 800 }],
       "price_ranges": [{ "label": "15 - 30 triệu", "count": 350 }],
       "brands": [{ "value": "Apple", "count": 200 }]
     },
     "pagination_note": "next_cursor = null kết thúc pagination. Mỗi item có thể kèm nhiều SKU trong inner_hits"
   }
   ```

4. **[Backend] - Trả HTTP 200.**

   **Cursor encoding:** Cursor là base64(JSON([sort_value, sku_id])). Khi response cuối cùng (`next_cursor = null`) → pagination kết thúc.


## 3. API: Gợi Ý Tìm Kiếm (Autocomplete / Search-as-you-type)

**Endpoint:** `GET /api/v1/search/suggest`

User đang gõ trên thanh tìm kiếm → hiện dropdown gợi ý real-time.

**Query Parameters:**
| Param | Loại | Bắt buộc | Mô tả |
|---|---|---|---|
| `q` | string | Có | Prefix đang gõ (VD: "ip", "iph", "iphon") |
| `limit` | int | Không | Số lượng gợi ý (default: 8, max: 20) |

**Quy trình chuẩn kiện (Workflow):**

1. **[Validation]:** `q` phải có ít nhất 2 ký tự. Nếu < 2 → trả `400 INVALID_QUERY_PARAMETER`.
2. **[Elasticsearch] - Query Suggestions Index:**
   ```json
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

**Latency budget:** p50 = 8ms, p99 = 25ms. Client timeout: 2s (fail-fast nếu vượt).

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
   - Sử dụng trực tiếp field `product_count` lưu sẵn trong index `categories` để đảm bảo tốc độ phản hồi tức thì (O(1)), tránh việc gọi aggregation nặng trên index `products`. Tuy số liệu có thể trễ vài giây so với realtime filter (Eventual Consistency do `_update_by_query` arithmetic), nhưng hoàn toàn chấp nhận được cho UX phần duyệt danh mục.
2. **[Enrichment]:** Xây dựng cấu trúc cây (Tree) trên bộ nhớ (App-level) từ mảng danh mục phẳng trả về. Trả kết quả JSON cho UI.
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

## 6. Internal API: Full Reindex (Admin)

> **Auth:** JWT với `role=ADMIN` từ API Gateway. Search Service đọc claim `role` để authorize. Request không có role=ADMIN → 403 FORBIDDEN.

### 6.1. Trigger Full Reindex (Async)

**Endpoint:** `POST /internal/v1/search/reindex`
**Auth:** JWT role=ADMIN
**Mode:** Async — trả 202 Accepted + run_id ngay lập tức. Job chạy background.

Xem chi tiết ở [Workers §2](search_workers.md#2-full-reindex-job-zero-downtime-3-pha-đơn-giản).

**Request:**
```bash
POST /internal/v1/search/reindex
Authorization: Bearer <JWT_ADMIN>
Content-Type: application/json

{
  "reason": "Quarterly mapping refresh",
  "triggered_by": "admin@company.com"
}
```

**Response 202 Accepted:**
```json
{
  "run_id": "reindex-2026-06-22T15-30-00-abc123",
  "status": "STARTED",
  "started_at": "2026-06-22T15:30:00Z",
  "estimated_duration_minutes": 60,
  "status_url": "/internal/v1/search/reindex/reindex-2026-06-22T15-30-00-abc123"
}
```

### 6.2. Poll Reindex Status

**Endpoint:** `GET /internal/v1/search/reindex/{run_id}`
**Auth:** JWT role=ADMIN

**Response 200 OK:**
```json
{
  "run_id": "reindex-2026-06-22T15-30-00-abc123",
  "status": "BUILDING" | "CATCHING_UP" | "CUTOVER" | "COMPLETED" | "FAILED" | "ROLLED_BACK",
  "current_phase": "CATCHING_UP",
  "progress": {
    "build_docs_written": 850000,
    "build_total_estimated": 1500000,
    "catchup_lag_seconds": 5,
    "cutover_completed_at": null
  },
  "started_at": "2026-06-22T15:30:00Z",
  "finished_at": null,
  "error_message": null
}
```

## 7. Phân Biệt API Search Service vs Product Service

| Tiêu chí | Search Service | Product Service |
|---|---|---|
| **Database** | Elasticsearch (Read Model) | PostgreSQL (Write Model / Source of Truth) |
| **Đối tượng gọi** | End-User (Buyer) browsing | Cart/Order Service (internal) |
| **Dữ liệu** | Denormalized, pre-computed | Normalized, transactional |
| **Tính nhất quán** | Eventual (delay 1-3s) | Strong (realtime) |
| **Use case** | Full-text search, faceted filter, autocomplete | Point-Read giá chính xác, tạo/sửa sản phẩm |
| **API prefix (public)** | `/api/v1/search/...` | `/api/v1/products/...` |
| **API prefix (internal)** | `/internal/v1/search/...` (reindex) | `/internal/v1/products/...` |
| **Pagination** | Cursor-based (`search_after`) | Offset-based (`page`/`size`) |
| **Caching** | Không có (ES query cache nội bộ) | Redis + Caffeine (validation, 1h TTL) |
| **Auth (public)** | JWT trust từ Gateway | JWT validation (nội bộ) |
| **Auth (internal)** | JWT role=ADMIN (reindex) | Service token (gRPC internal) |
