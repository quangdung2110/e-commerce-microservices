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
  *   **Mô tả**: Cache kết quả search cho các truy vấn phổ biến. `query_hash` = SHA-256 của normalized query string (bao gồm `q` + filters + sort + page + size + flags).
    *   **Loại Dữ Liệu**: `String (JSON)`.
    *   **Khuôn mẫu JSON**:
        ```json
        {
          "valid_until": 1710500000,
          "stale_max_until": 1710500240,
          "total_hits": 1250,
          "products": [
            {
              "product_id": "prod-uuid-001",
              "name": "iPhone 15 Pro Max",
              "best_price": 28891500,
              "original_price": 34990000,
              "has_promotion": true,
              "is_in_stock": true,
              "product_rating_avg": 4.8,
              "product_sold_count": 5600,
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
    *   **TTL chuẩn hóa (duy nhất):**
      *   `valid_until` (logical TTL): 30 giây. Hết hạn logic thì được trả stale + refresh ngầm.
      *   Redis EX (physical TTL): 24 giờ (`EX 86400`). Mục tiêu là giữ stale data để tránh hard miss hàng loạt khi ES lỗi ngắn hạn.
      *   `stale_max_until` (stale hard-cap): tối đa `valid_until + 240s`. Quá mốc này không được tiếp tục phục vụ stale.
    *   **SWR Invalidation (đúng nghĩa Stale-While-Revalidate):** Nếu `now() > valid_until`, API trả stale data ngay và chỉ trigger refresh bất đồng bộ, không block request path.
    *   **Chống Stampede (2 tầng bắt buộc):**
      *   Tầng 1 (Pod-local): Singleflight theo `query_hash` để gom request giống nhau trong cùng Pod.
      *   Tầng 2 (Multi-pod): Redis-backed dedup qua key `search:refresh:{query_hash}` (xem chi tiết bên dưới).
    *   **Redis Dedup Key (Multi-pod, thống nhất):**
      *   Key: `search:refresh:{query_hash}` — key duy nhất thay thế cho cả `search:refresh:enqueue:` và `search:lock:refresh:`. Cùng scope `query_hash`, cùng TTL formula, gộp 2 round-trips thành 1.
      *   TTL: `max(30, 2 * es_refresh_p99_seconds)`.
      *   Semantics:
        - Scheduler: `SETNX search:refresh:{query_hash} 1 EX {ttl}` — chỉ pod thắng CAS mới được enqueue task; pod trượt dừng tại chỗ.
        - Worker (trước khi query ES): `GET search:refresh:{query_hash}` — xác nhận key còn tồn tại (tức chưa expired từ scheduler). Nếu key đã hết hạn, worker vẫn tiếp tục execute (TTL tự bảo vệ).
        - Worker (khi hoàn tất): `DEL search:refresh:{query_hash}` — reset key để mở đường refresh tiếp theo. Nếu Pod crash trước khi xóa, TTL tự hết hạn.
      *   Không cần compare-delete owner token — `DEL` đơn giản sau khi hoàn tất là đủ, vì TTL đã bảo vệ race condition.
    *   **Bounded Refresh Queue (In-memory):**
      *   Queue giới hạn cứng (ví dụ 2,000 tasks/pod), chỉ nhận task refresh hot-query.
      *   Enqueue dùng `offer` non-blocking; nếu queue full thì bỏ refresh task có kiểm soát (không block request).
      *   Nếu queue full nhưng `now() > stale_max_until`, request path phải fallback query ES trực tiếp (timeout ngắn) thay vì trả stale quá hạn.
      *   Queue full phải ghi metric `search_refresh_queue_dropped_total` + alert theo ngưỡng.
      *   Task trong queue nằm trên RAM Pod; khi Pod down, task có thể mất và được chấp nhận theo triết lý SWR.
    *   **Queue-worker semantics (bắt buộc):**
      *   Một queue-node chỉ được một worker lấy (destructive pop từ queue thread-safe).
      *   Nhiều worker không lấy cùng một queue-node.
      *   Rủi ro trùng logic chỉ đến từ enqueue trùng `query_hash`, đã được chặn bằng Redis dedup key (SETNX).
    *   **Fallback Circuit Breaker:** Nếu refresh thất bại liên tiếp 3 lần trong cửa sổ 30 giây, ghi alert và gia hạn `valid_until` thêm 60 giây nhưng không vượt `stale_max_until`.
    *   **Khi nào cache:** Chỉ cache query hot, ngưỡng chuẩn là `query_count > 50 requests / phút`.
    *   **Thuật toán đếm hot query (bắt buộc, cluster-wide):**
      *   Key: `search:qcount:{query_hash}:{minute_bucket}` (String, INCR).
      *   `minute_bucket = floor(now_unix_seconds / 60)` — chia thời gian theo block 60 giây.
      *   Mỗi request: `INCR search:qcount:{query_hash}:{current_minute_bucket}` rồi `GET search:qcount:{query_hash}:{previous_minute_bucket}` (bucket trước đó). Tổng 2 bucket = request count trong 60 giây trượt.
      *   TTL mỗi bucket: 180 giây (`EX 180`). Key tự dọn sau 3 phút không có request.
      *   Hot nếu `current_count + previous_count > 50`.
      *   Ưu điểm: chỉ 2 Redis round-trips thay vì 4 (ZADD + ZREMRANGEBYSCORE + PEXPIRE + ZCARD), không cần ZSET trimming, O(1) per request.

  > **Query Normalization (canonical, bắt buộc):**
  > 1. Chuẩn hóa chuỗi: lowercase + trim + collapse whitespace cho `q`.
  > 2. Chuẩn hóa kiểu dữ liệu: boolean -> `true/false`, số -> string không locale.
  > 3. Với `filters` map: sort theo key tăng dần; nếu value là mảng thì sort value tăng dần.
  > 4. Loại bỏ field null/rỗng khỏi payload hash.
  > 5. Serialize JSON với sorted keys cho tập tham số: `q`, `category`, `filters`, `price_min`, `price_max`, `in_stock`, `has_promotion`, `admin_id`, `sort`, `page`, `size`.
  > 6. Băm SHA-256 ra `query_hash`.

### 1.2 Category Tree Cache

*   `search:categories:tree`
  *   **Mô tả**: Toàn bộ cây danh mục với `category_id`, `name`, `slug`, `parent_id`, `level`, `product_count` (số product groups DISTINCT theo `product_id` sau bộ lọc hiển thị mặc định: `is_deleted=false`, `product_status=ACTIVE`, `sku_status=ACTIVE`, `is_in_stock=true`). Phục vụ hiển thị sidebar/breadcrumb navigation.
    *   **Loại Dữ Liệu**: `String (JSON)`.
    *   **TTL**: 1 giờ. Cây danh mục hiếm khi thay đổi.
    *   **Invalidation**: Trigger khi có biến động tập ACTIVE hoặc phân bổ category (`PRODUCT_STATE_CHANGED`, `PRODUCT_DELETED`, `PRODUCT_SKU_DELETED`, `PRODUCT_UPDATED` (invalidate luôn, không so sánh old/new fields), `CATEGORY_UPDATED`, `CATEGORY_DELETED`) hoặc tín hiệu pub/sub đồng bộ category (`channel:search:category-refresh`). Với payload legacy, dùng chính sách invalidate mỗi `PRODUCT_UPDATED`.
    *   **Compatibility mode:** Trong giai đoạn chuyển tiếp, listener có thể chấp nhận thêm `channel:product:l1-evict` để tránh miss invalidation liên service.
    *   **L1/L2 Miss Fallback (Chống Stampede Toàn Cụm):** Cả L1 (Caffeine) và L2 (Redis) bị miss thì chỉ 1 Pod được phép gọi Product Service bằng lock phân tán `SETNX search:lock:category-tree-refresh 1 EX 30`; các Pod còn lại phải dùng stale data gần nhất hoặc chờ kết quả refresh broadcast qua Pub/Sub.

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
    *   **Population**: [Search Suggestions Rebuilder](search_workers.md#8-search-suggestions-rebuilder) tính toán và warm cache hàng ngày.

### 1.4 Facet Cache (Aggregation Precomputed)

*   `search:facets:{category_id}`
    *   **Mô tả**: Pre-computed facets (bộ lọc) cho từng Category page. VD: vào trang "Điện thoại" → sidebar hiện các filter: Thương hiệu (Apple: 200, Samsung: 180...), Khoảng giá (5-10M: 500...), RAM (8GB: 300...).
    *   **Loại Dữ Liệu**: `String (JSON)`.
    *   **Khuôn mẫu JSON**:
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
    *   **TTL**: 5 phút.
    *   **Count semantics (bắt buộc):** mọi `count` trong facets phải đếm DISTINCT `product_id` để đồng nhất với danh sách sau `collapse`.
    *   **Population**: Chạy aggregation query trên Elasticsearch khi cache miss, hoặc pre-warm bởi [Facet Pre-warmer](search_workers.md#9-facet-pre-warmer).

---

## 2. Chiến Lược Cache Invalidation

| Trigger | Keys bị ảnh hưởng | Hành động |
|---|---|---|
| Product event (bất kỳ loại) | `search:query:*` | **Không chủ động invalidate** — TTL hoặc Logical Expiration tự xử lý |
| Background Worker | `search:facets:{category_id}` | Worker tự động nạp đè định kỳ 5 phút. KHÔNG xóa ngang (event-driven) để tránh thảm họa Database sập vì Cache Miss khi giá đổi liên tục. |
| Biến động category footprint (`PRODUCT_STATE_CHANGED`, `PRODUCT_DELETED`, `PRODUCT_SKU_DELETED`, `PRODUCT_UPDATED` (invalidate luôn, không so sánh old/new fields; over-invalidate là vô hại), `CATEGORY_UPDATED`, `CATEGORY_DELETED`, hoặc tín hiệu `channel:search:category-refresh`) | `search:categories:tree` | Delete/refresh có kiểm soát -> re-build ở request kế tiếp hoặc bởi worker warmup |
| Hàng ngày (Cron) | `search:suggest:*` | Rebuild toàn bộ từ analytics |

> **Philosophy:** Search cache thiên về **TTL-based expiration** hoặc Logical Expiration hơn là event-driven invalidation. Lý do: với hàng triệu sản phẩm thay đổi liên tục, việc chủ động invalidate từng query hash là bất khả thi và không cần thiết, user chấp nhận trễ 30s. Tương tự với Facet Cache, chấp nhận sidebar filter trễ 5 phút để bảo vệ Elastic Cluster.

---

## 3. Chống Cache Stampede

Hot query sử dụng stale-while-revalidate, vì vậy stampede có thể xảy ra ở pha refresh thay vì pha miss.

### 3.1 Pipeline chuẩn (Request path)
1. Request đọc `search:query:{query_hash}`.
2. Nếu fresh (`now <= valid_until`) thì trả ngay.
3. Nếu stale (`valid_until < now <= stale_max_until`) thì trả stale ngay và gọi `scheduleRefresh(query_hash)`.
4. Nếu `now > stale_max_until` thì bypass stale, query ES trực tiếp với timeout ngắn và ghi đè cache mới.

### 3.2 `scheduleRefresh(query_hash)` (Pod-local)
1. Đi qua Singleflight để gom request giống nhau trong Pod.
2. Trong callback Singleflight, thử `SETNX search:refresh:{query_hash} 1 EX {ttl}` với `ttl = max(30, 2 * es_refresh_p99_seconds)`.
3. Nếu SETNX fail (key đã tồn tại) → dừng, trả stale ngay (pod khác đã enqueue).
4. Nếu SETNX success → `offer` task vào bounded queue. Nếu queue full, xoá key Redis (`DEL search:refresh:{query_hash}`) và tăng metric drop.

### 3.3 Worker loop (Async refresh)
1. Worker `take()` task từ queue.
2. Xác nhận key còn tồn tại (`GET search:refresh:{query_hash}`). Nếu đã hết hạn (Pod crash sau khi enqueue thành công nhưng trước khi worker take), worker vẫn tiếp tục execute — TTL đã bảo vệ, không cần lock riêng.
3. Query Elasticsearch.
4. Cập nhật Redis `search:query:{query_hash}` với `valid_until` mới.
5. `DEL search:refresh:{query_hash}` trong `finally` để reset dedup key. Nếu Pod crash trước DEL, key tự hết hạn theo TTL.

### 3.4 Pod crash / restart
1. Task đang nằm trong queue RAM có thể mất khi Pod down.
2. Redis dedup key `search:refresh:{query_hash}` có TTL độc lập với Pod lifecycle — nếu Pod A enqueued task và down trước khi worker take, key vẫn còn TTL. Pod B khi receive stale request sẽ SETNX fail và không enqueue thêm. Worker của Pod B hoặc Pod C sẽ take task khi Pod A's worker không còn.
3. Đây là hành vi chấp nhận được của SWR: request kế tiếp sẽ trigger lại refresh.
4. Không bắt buộc dùng Redis Streams/Kafka refresh topic trong thiết kế hiện tại để giữ độ đơn giản vận hành.

---

## 4. Tổng Hợp Redis Key Inventory

| Key Pattern | Loại | TTL | Mục đích |
|---|---|---|---|
| `search:query:{query_hash}` | String (JSON) | Physical: 24h, Logical: 30s (`valid_until`) + stale hard-cap 240s (`stale_max_until`) | Hot search results |
| `search:qcount:{query_hash}:{minute_bucket}` | String (INCR) | TTL 180s per bucket, tự dọn sau 3 phút không có request | Đếm tần suất query theo cluster để xét hot-cache (sliding window 60s qua 2 bucket) |
| `search:refresh:{query_hash}` | String | `max(30, 2 * es_refresh_p99_seconds)` | Dedup key thống nhất (thay thế enqueue CAS + execution lock) |
| `search:categories:tree` | String (JSON) | 1h | Category navigation |
| `search:suggest:{prefix}` | String (JSON Array) | 10m | Autocomplete gợi ý |
| `search:facets:{category_id}` | String (JSON) | 5m | Pre-computed filters |
