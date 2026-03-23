# Search Service - Caching Schema (Redis)

**Liên kết nhanh (Related Documents):**
*   [Elasticsearch Schema (Read Store)](search_elasticsearch_schema.md)
*   [Caching Schema (Redis)](search_caching_schema.md)
*   [Workflows (API & Data Flow)](search_workflow.md)
*   [Background Workers & Jobs](search_workers.md)
*   [Events Consumed & Kafka Topics](search_events.md)
*   [Architecture Recommendations](search_architecture_recommendations.md)

**Cross-Service References:**
*   [Product Service - Caching Schema](../product/product_caching_schema.md)

---

Tài liệu này mô tả chiến lược Redis Caching của Search Service. Khác với [Product Service Cache](../product/product_caching_schema.md) (phục vụ Write Validation & Point-Read nội bộ), Cache ở Search Service phục vụ 1 mục đích duy nhất: **giảm tải Elasticsearch** cho các truy vấn lặp lại từ hàng triệu End-User.

> **Nguyên tắc:** Elasticsearch vốn đã cực nhanh (< 10ms cho hầu hết query). Redis cache ở đây chỉ can thiệp cho các trường hợp **hot query** (hàng ngàn user cùng search "iphone 15" trong Flash Sale) hoặc **static data** (Category Tree, Autocomplete Suggestions) để tránh đập Elasticsearch vô ích.

## 1. Cấu Trúc Khóa (Key Pattern) & Chức Năng

Tất cả Redis Key của Search Service bắt đầu bằng prefix: `search:`

### 1.1 Nhóm Search Results Cache (Hot Query Cache)

*   `search:query:{query_hash}`
    *   **Mô tả**: Cache kết quả search cho các truy vấn phổ biến. `query_hash` = SHA-256 của normalized query string (bao gồm keyword + filters + sort + page).
    *   **Loại Dữ Liệu**: `String (JSON)`.
    *   **Khuôn mẫu JSON**:
        ```json
        {
          "valid_until": 1710500000,
          "total_hits": 1250,
          "products": [
            {
              "product_id": "prod-uuid-001",
              "name": "iPhone 15 Pro Max",
              "best_price": 28891500,
              "original_price": 34990000,
              "has_promotion": true,
              "is_in_stock": true,
              "rating_avg": 4.8,
              "sold_count": 5600,
              "category_name": "Smartphone"
            }
          ],
          "facets": {
            "categories": [{"key": "smartphone", "count": 800}],
            "price_ranges": [{"key": "20-30M", "count": 350}],
            "brands": [{"key": "Apple", "count": 200}]
          }
        }
        ```
    *   **TTL**: 60 giây (Logical Expiration Pattern). Giá trị cache được lưu 60s, nhưng thuộc tính `valid_until` (thời điểm trễ 30s) quyết định độ 'tươi' của data.
    *   **Invalidation**: Nếu Query API bốc ra object JSON và phát hiện `now() > valid_until`, Application vẫn trả về JSON stale này cho người dùng, nhưng đồng thời bắn ngầm một Async Job xuống Elasticsearch tính toán kết quả mới.
    *   **Sửa Lỗi Thundering Herd (Logical Expiration Flaw):** Lỗi nguyên thuỷ là khi nhiều Request cùng đọc trúng `now() > valid_until` thì N Goroutine sẽ cùng bay xuống Elasticsearch. Để chống Stampede triệt để, tiến trình Async Job phải chiếm được Distributed Lock `SETNX search:lock:refresh:{query_hash} 1 EX 10` (Hợp hợp Singleflight cho các tiến trình trên chung Pod). Chỉ tiến trình nào nắm được lock mới query xuống ES, các tiến trình trượt lock sẽ Return Stale Data ngay lập tức mà không làm gì thêm.
    *   **Khi nào cache**: Chỉ cache nếu query là "hot" (đếm tần suất query, chỉ cache khi `query_count > threshold` trong sliding window). Tránh cache pollution cho long-tail queries.

> **Query Normalization:** Trước khi hash, query phải được chuẩn hoá: lowercase, trim, sort filter params alphabetically. VD: `keyword=iphone&category=smartphone&sort=price_asc` → cùng hash bất kể thứ tự params.

### 1.2 Category Tree Cache

*   `search:categories:tree`
    *   **Mô tả**: Toàn bộ cây danh mục với `category_id`, `name`, `slug`, `parent_id`, `level`, `product_count` (số sản phẩm ACTIVE). Phục vụ hiển thị sidebar/breadcrumb navigation.
    *   **Loại Dữ Liệu**: `String (JSON)`.
    *   **TTL**: 1 giờ. Cây danh mục hiếm khi thay đổi.
    *   **Invalidation**: Khi Search Service nhận được sản phẩm mới/xoá → product_count thay đổi → rebuild cache. Hoặc subscribe kênh `channel:product:cache:evict` từ [Product Service Pub/Sub](../product/product_caching_schema.md#2-chiến-lược-đồng-bộ-l1--l2-cache-cache-invalidation).
    *   **L1 Local Cache**: Nếu Search Service dùng JVM (Spring Boot), nên nhét thêm Caffeine L1 (TTL 5 phút) trước Redis để giảm round-trip.

### 1.3 Autocomplete / Suggestions Cache

*   `search:suggest:{prefix}`
    *   **Mô tả**: Cache kết quả gợi ý cho từng prefix nhập. VD: user gõ "ip" → trả về ["iPhone 15 Pro Max", "iPhone 14", "iPad Air"]. `prefix` = 2-4 ký tự đầu tiên (lowercase, normalized).
    *   **Loại Dữ Liệu**: `String (JSON Array)`.
    *   **Khuôn mẫu JSON**:
        ```json
        [
          { "text": "iphone 15 pro max", "type": "PRODUCT", "weight": 5600 },
          { "text": "iphone 14", "type": "PRODUCT", "weight": 3200 },
          { "text": "ipad air", "type": "PRODUCT", "weight": 1800 },
          { "text": "Điện thoại", "type": "CATEGORY", "weight": 50000 }
        ]
        ```
    *   **TTL**: 10 phút. Suggestions không thay đổi thường xuyên.
    *   **Population**: [Search Suggestions Rebuilder](search_workers.md#4-search-suggestions-rebuilder) tính toán và warm cache hàng ngày.

### 1.4 Facet Cache (Aggregation Precomputed)

*   `search:facets:{category_id}`
    *   **Mô tả**: Pre-computed facets (bộ lọc) cho từng Category page. VD: vào trang "Điện thoại" → sidebar hiện các filter: Thương hiệu (Apple: 200, Samsung: 180...), Khoảng giá (5-10M: 500...), RAM (8GB: 300...).
    *   **Loại Dữ Liệu**: `String (JSON)`.
    *   **Khuôn mẫu JSON**:
        ```json
        {
          "category_id": "cat-smartphone",
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
    *   **TTL**: 5 phút.
    *   **Population**: Chạy aggregation query trên Elasticsearch khi cache miss, hoặc pre-warm bởi [Facet Pre-warmer](search_workers.md#5-facet-pre-warmer-cronjob).

---

## 2. Chiến Lược Cache Invalidation

| Trigger | Keys bị ảnh hưởng | Hành động |
|---|---|---|
| Product event (bất kỳ loại) | `search:query:*` | **Không chủ động invalidate** — TTL hoặc Logical Expiration tự xử lý |
| Background Worker | `search:facets:{category_id}` | Worker tự động nạp đè định kỳ 5 phút. KHÔNG xóa ngang (event-driven) để tránh thảm họa Database sập vì Cache Miss khi giá đổi liên tục. |
| Product created/deleted | `search:categories:tree` | Delete → re-build khi request tiếp theo |
| Hàng ngày (Cron) | `search:suggest:*` | Rebuild toàn bộ từ analytics |

> **Philosophy:** Search cache thiên về **TTL-based expiration** hoặc Logical Expiration hơn là event-driven invalidation. Lý do: với hàng triệu sản phẩm thay đổi liên tục, việc chủ động invalidate từng query hash là bất khả thi và không cần thiết, user chấp nhận trễ 30s. Tương tự với Facet Cache, chấp nhận sidebar filter trễ 5 phút để bảo vệ Elastic Cluster.

---

## 3. Chống Cache Stampede

Khi một hot query hết TTL thực tế (Không ứng dụng Logical Expiration):

**Giải pháp: Local Lock / Singleflight**
1. Khi cache miss, **1 request** duy nhất acquire lock ở mức Tầng Ứng Dụng (Application Memory, ví dụ Golang Singleflight).
2. Request đó query Elasticsearch, set cache mới lên Redis.
3. Hàng ngàn requests khác đang đợi lock, sau khi lock mở, sẽ lập tức nhận kết quả chung từ bộ nhớ và phi ngược lại Client, không gây áp lực rác lên DB hay Redis.

---

## 4. Tổng Hợp Redis Key Inventory

| Key Pattern | Loại | TTL | Mục đích |
|---|---|---|---|
| `search:query:{hash}` | String (JSON) | 60s | Hot search results (Logical Expiration 30s) |
| `search:categories:tree` | String (JSON) | 1h | Category navigation |
| `search:suggest:{prefix}` | String (JSON Array) | 10m | Autocomplete gợi ý |
| `search:facets:{category_id}` | String (JSON) | 5m | Pre-computed filters |
| `search:product:{product_id}` | String (JSON) | 30m | Product detail cache (optional) |
