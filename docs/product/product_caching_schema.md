# Product Service - Caching Schema (Redis & Caffeine)

**Liên kết nhanh (Related Documents):**
*   [Workflows (API & Data Flow)](product_workflow.md)
*   [Background Workers & Jobs](product_workers.md)
*   [Caching Schema (Redis & Caffeine)](product_caching_schema.md)
*   [Database Schema (Write Store)](product_db_design.md)
*   [State Machine & Lifecycle](product_state_machine.md)
*   [Events Structure & Kafka Topics](product_events.md)
*   [Architecture Recommendations](product_architecture_recommendations.md)

---
Tài liệu này mô tả chi tiết chiến lược Caching áp dụng bên trong Product Service. Trong kiến trúc CQRS, Product Service nhận trọng trách làm **Write Model (Command)**. Do đó, Cache tại service này không sinh ra để phục vụ việc search/lọc sản phẩm của End-User (việc đó do Elasticsearch của Search Service lo), mà tập trung vào **5 nhiệm vụ cốt lõi** sau:

1. **Tối ưu hóa quá trình Truy vấn và Thẩm định Dữ liệu (Validation)**: Tránh query PostgreSQL liên tục cho các dữ liệu cấu hình tĩnh (Category, Attribute) khi hệ thống thẩm định dữ liệu tạo/sửa sản phẩm. Nâng cao tốc độ bằng mô hình 2-Tier Caching kết hợp cơ chế Pub/Sub Invalidation để đảm bảo tính nhất quán (Consistency).
2. **Cung cấp dữ liệu Tức thì (Point-Read) cho hệ sinh thái**: Phục vụ các API Gọi Nội Bộ (RPC/HTTP) dưới dạng các endpoint đơn và Batch cho Order, Cart, Inventory Service. Đóng vai trò như một Read-Replica In-Memory siêu tốc, ngăn chặn lỗi N+1 Query.
3. **Đảm bảo toàn vẹn Dữ liệu, Trạng thái và Phân phối Tải**: Sử dụng Redis như một hạ tầng điều phối (Coordination) chặn các request dị thường từ vòng ngoài bằng các cơ chế: Idempotency (Chống duplicate/double-click) và Rate Limiting (Chống spam).
4. **Tích hợp trực tiếp Giá Khuyến Mãi (Markdown Pricing)**: Cache giá On-The-Fly cho luồng tra cứu Checkout và Product Detail với TTL động theo thời hạn chương trình khuyến mãi.
5. **Bảo vệ Database (Phòng chống thảm hoạ Cache)**: Được tích hợp chặt chẽ cùng các Pattern chống Cache Stampede (cross-pod distributed lock là chuẩn mặc định, có thể kết hợp in-process singleflight) và Cache Penetration (trả về Empty Marker) để đảm bảo Connection Pool của PostgreSQL luôn an toàn dưới tải khủng hoặc bot đánh phá.

---

## 1. Cấu Trúc Khóa (Key Pattern) & Chức Năng

Tất cả các Redis Key của Product Service bắt đầu bằng prefix: `product:`

### 1.1 Nhóm Khóa Thẩm Định Dữ Liệu (Validation Data L1 + L2 Cache)
Đây là dữ liệu ít thay đổi nhất hệ thống, dùng để chặn các request tạo/sửa sản phẩm sai cấu hình danh mục.
Áp dụng chiến lược **2-Tier Caching (L1: Caffeine Local + L2: Redis)**. Dù áp dụng đẩy xoá cache Pub/Sub, Caffeine L1 bắt buộc phải có `expireAfterWrite = 1 phút` làm Fallback. Mức 1 phút này là đủ an toàn để cân bằng giữa hiệu năng phân tán và rủi ro lệch đồng bộ Validation Rule nếu Pod vô tình bị miss một bản tin Pub/Sub, thay vì giữ luật sai quá lâu.

*   **Key Pattern**: `product:categories:tree`
    *   **Mô tả**: Toàn bộ cây danh mục của hệ thống dưới dạng JSON (dùng để check `category_id` truyền lên nằm ở lá mạng nào).
    *   **Loại Data**: `String (JSON Array Lồng)` trên Redis, Map memory trên Caffeine.
    *   **TTL**: **[H6 Fix]** 24 giờ. TTL đóng vai trò là safety net chống lại chiến lược Eviction LRU ngầm làm thủng RAM của cluster. Fast-Path invalidation vẫn dựa vào Pub/Sub.
    *   **Cách thức**: RAM của App chọc tới L1 (0.1ms). Nếu L1 rỗng, gọi L2 Redis (1ms). Nếu L2 rỗng, Fetch Database -> Lưu L2 -> Lưu L1.

*   `product:category:{category_id}:attributes`
    *   **Mô tả**: Danh sách các Thuộc tính (kèm cờ `is_required`, `is_variant`) của phần danh mục để Validate `attributes_json` mà Seller gửi lên.
    *   **Loại Dữ Liệu**: `Set (JSON)` hoặc `Hash`.
    *   **TTL**: 24 giờ. Cập nhật Cache-Aside.

### 1.2 Nhóm Khóa Point-Read Nội Bộ (Fast Access)
Order, Cart, Inventory Service không có quyền Query DB trực tiếp của Product. Họ gọi qua gRPC `GetProductSku`.

> **Boundary bắt buộc:** Các service khác không đọc Redis của Product trực tiếp; chỉ truy cập qua gRPC (`GetProductSku`, `GetBatchProductSkus`) để bảo toàn hợp đồng và versioning.

*   `product:sku:{sku_id}`
    *   **Mô tả**: Chứa thông tin denormalized quan trọng nhất của một biến thể chuẩn bị vào giỏ. (Ví dụ: Định danh, Status, Giá Niêm Yết `original_price`, Giá Khuyến Mãi `promotional_price`, Product Name, Image).
    *   **Loại Dữ Liệu**: `RedisJSON` (Ưu tiên tuyệt đối RedisJSON vì `variant_attributes` lưu dưới dạng Nested JSON, giúp Cart có thể thực hiện `JSON.MGET` thay cho lệnh HGET parse tay đắt đỏ).
    *   **Khuôn mẫu JSON**:
        ```json
        {
          "sku_id": "sku-uuid-001",
          "product_id": "prod-uuid-001",
          "sku_code": "IP15PM-256-BLK",
          "product_name": "iPhone 15 Pro Max",
          "seller_name": "Apple Flagship Store",
          "main_image_url": "https://cdn.example.com/ip15-black.jpg",
          "original_price": 34990000.00,
          "promotional_price": 28891500.00,
          "best_price": 28891500.00,
          "sku_status": "ACTIVE",
          "product_status": "ACTIVE",
          "is_available": true,
          "is_deleted": false,
          "variant_attributes": {"color": "Đen", "storage": "256GB"},
          "display_quantity": 150,
          "last_inventory_sync_at": 1709420000000,
                    "seller_id": "seller-uuid-001"
        }
        ```
    *   **TTL**: 1 tiếng (3600s).
    *   **Xử lý Invalidation**: **[M1 Fix Nhất Quán Thuật Ngữ]** Khi có cập nhật bảng cha hoặc SKU từ DB, hệ thống bắt buộc dùng OVERWRITE (Partial SET/Soft Invalidation), không dùng DEL hàng loạt. Ownership ghi cache cần tách bạch: (1) API In-band có thể ghi đè để đảm bảo Read-Your-Own-Writes; (2) với luồng async từ event hệ thống (Markdown/Inventory/FSM), `product-invalidation-group` là writer chuẩn cho `product:sku:{sku_id}` và `product:master:{product_id}`. Tránh để hai worker async độc lập cùng ghi đè một key cho cùng nguyên nhân cập nhật. Với `product:master`, bắt buộc dedupe theo `product_id` trong mỗi poll-batch và áp dụng stale guard `version/updated_at` trước khi ghi. Xem [Invalidation Consumer Worker](product_workers.md).

> **Batch Point-Read Strategy:** Khi Cart Service cần lấy thông tin N SKU cùng lúc (VD: hiển thị giỏ hàng có 10 món), API nội bộ dùng gRPC `GetBatchProductSkus` nhận danh sách `sku_ids`. Backend sử dụng Redis `JSON.MGET` để fetch N keys cùng lúc trong 1 round-trip, tránh N+1 problem. Với các SKU bị cache miss, chỉ query DB cho những SKU thiếu (partial miss), không query lại toàn bộ.

*   `product:master:{product_id}`
    *   **Mô tả**: Thông tin chung của sản phẩm gốc phục vụ việc hiển thị giỏ hàng mượt mà (tên, category, seller, status).
    *   **Loại Dữ Liệu**: `RedisJSON`.
    *   **TTL**: 1 tiếng.
    *   **Metadata guard bắt buộc**: Payload phải lưu tối thiểu `version` và `updated_at` để chặn stale write.
    *   **Chiến lược ghi**: `product-invalidation-group` dedupe theo `product_id` trong poll-batch, có thể thêm debounce ngắn 50-100ms giữa các batch liền kề, và chỉ overwrite khi `(incoming.version, incoming.updated_at)` mới hơn cache hiện tại.

### 1.3 Nhóm Khóa Cơ Chế Vận Hành (Operational / Transactional)

*   `product:idempotency:{idempotency_key}`
    *   **Mô tả**: Ngăn chặn tình trạng tạo 2 Product cùng lúc do lỗi UI Double-Click hoặc Retries từ Client.
    *   **Loại Dữ Liệu**: `String (JSON token gồm state + request_hash + response)`.
    *   **TTL**: **[H1 Fix Phân Trị 2 Pha]** Pha 1 khi bắt Request POST/PUT: `SETNX` cấp phát vòng đời duy nhất 5 phút (Lease time) để hạn chế giam cầm token nếu Container crash. Pha 2 khi Transaction DB Commits: `SET XX` kéo dài TTL lên 24 giờ vĩnh viễn (Finalize).
    *   **Cơ chế**: Dùng `SETNX`. Request đầu tiên set biến thành công -> Vào DB. Request hai set xịt -> so sánh `request_hash`: nếu khác payload thì trả `409 Conflict`; nếu cùng payload thì trả response đã cache hoặc poll lại key theo backoff có jitter đến khi transaction đầu hoàn tất. (Thư viện: Redisson/Spring Data Redis). Xem chi tiết luồng tại [Product Workflow](product_workflow.md).

*   `product:rate_limit:seller:{seller_id}`
    *   **Mô tả**: Quotas/Rate Limit chặn bão Spam tạo sản phẩm ảo. (VD: 1 Seller chỉ được tạo tối đa N sản phẩm / 1 ngày, hoặc / 1 Phút).
    *   **Loại Dữ Liệu**: Sliding window / Redis INCR.
    *   **TTL**: 1 Ngày.

### 1.4 Nhóm Khóa Tích Hợp Giá Khuyến Mãi (Markdown Cache)

*   `product:markdown:active:{sku_id}`
    *   **Mô tả**: Cache giá khuyến mãi đang active cho SKU cụ thể. Dùng cho API Detail Page (On-The-Fly pricing) khi cần check nhanh giá giảm hiện tại mà không cần query DB. Xem [Promotion DB Design - On-The-Fly](../promotion/promotion_db_design.md#22-xử-lý-zero-latency-tính-toán-best-deal-mà-không-cần-discount_items-mapping).
    *   **Loại Dữ Liệu**: `String (JSON)`.
    *   **Khuôn mẫu JSON**:
        ```json
        {
          "rule_id": 42,
          "discount_type": "PERCENTAGE",
          "discount_value": 15.00,
          "calculated_price": 28891500.00,
          "expires_at": "2026-03-07T23:59:59Z"
        }
        ```
    *   **TTL**: Đặt bằng thời gian còn lại của rule (`expires_at - NOW()`). Redis tự xoá khi rule hết hạn.
    *   **Invalidation**: [Markdown Price Worker](product_workers.md#5-markdown-price-calculator-worker) set/del key này khi rule active hoặc ended. Worker này chỉ quản lý key markdown marker; key point-read `product:sku:{sku_id}` vẫn do `product-invalidation-group` refresh theo `PRODUCT_PRICE_UPDATED`.

---

### 2. Chiến lược Đồng bộ L1 & L2 Cache (Cache Invalidation)
Khi Admin cập nhật Cây Danh Mục (Category Tree) hoặc Thuộc Tính bắt buộc, hệ thống **Product Service (Master)** phải phát tin nhắn broadcast qua Redis Pub/Sub:
- **Pub/Sub Channel**: **[X3 Fix]** Tách thành kênh định danh rạch ròi: `channel:product:l1-evict` (Kênh chuyên dùng cho nội bộ hạm đội Pod Product vứt Caffeine L1). Channel mở rộng `channel:search:category-refresh` dành riêng cho hệ Search Service.
- **Message Format JSON**: Bắt buộc schema Payload rõ ràng `{"type": "CATEGORY_TREE", "data_id": "cat-uuid..."}` thay vì bắn tín hiệu rỗng sinh Exception Parsing.
- **Hành động (After-Commit Hook)**: CỰC KỲ QUAN TRỌNG: Lệnh đẩy Pub/Sub **BẮT BUỘC** phải được thực thi tại phase `After-Transaction-Commit`. Nếu đẩy trong In-Transaction, Kafka/L1 sẽ bị xóa, và Request ngay lúc đó lấy data từ DB sẽ bị rơi vào khoảng Dirty Read (lấy phải dữ liệu lúc chưa commit hoàn toàn). Các Pod nhận tín hiệu -> decode Type -> gọi `categoryLocalCache.invalidateAll()`. Khóa Redis `product:categories:tree` sẽ bị xóa cục bộ, Request API kế tiếp sẽ mồi lại Cache.

---

## 3. Quản Trị Hệ Quả Của Cache Miss
Product Service chứa hàng chục triệu mã SKU. Việc "đập" tất cả vào Redis là bất khả thi về dung lượng RAM.

*   **Lazy Loading (Cache-Aside)**: Cho dữ liệu `product:sku:{id}`. Khi bị Query API, hệ thống kiểm tra cache trước. Rỗng thì mới query DB rồi Set ngược lại với TTL cố định.
*   **Chống Stampede (Hiệu ứng dẫm đạp)**: Nếu một đợt Flash Sale / Traffic ảo ập tới một sản phẩm đang hết TTL (Cache Miss) trên Redis, cơ chế chuẩn là cross-pod distributed lock (`SETNX product:lock:fetch:{sku_id}` với lease ngắn) để chỉ 1 pod được query DB. Các pod còn lại poll lại Redis theo backoff ngắn. In-process singleflight chỉ là lớp bổ sung trong nội bộ từng pod.
*   **Chống Cache Penetration (Truy vấn Không tồn tại)**: Với các `sku_id` không tồn tại trong DB, hệ thống cache giá trị rỗng (empty marker) với TTL ngắn (60s) để tránh request giả mạo liên tục đập xuống DB. Optional: sử dụng Bloom Filter để check nhanh sự tồn tại của SKU trước khi chọc Redis/DB.

---

## 4. Tổng Hợp Redis Key Inventory

| Key Pattern | Loại | TTL | Invalidation | Mục đích |
|---|---|---|---|---|
| `product:categories:tree` | String (JSON) | 24h | Pub/Sub L1 + TTL fallback | Cây danh mục validate |
| `product:category:{id}:attributes` | Hash/Set | 24h | Cache-Aside | Thuộc tính theo Category |
| `product:sku:{sku_id}` | RedisJSON | 1h | In-band API overwrite + `product-invalidation-group` (fallback async) | Point-Read cho Cart/Order (Partial update theo field, không serialize blob string toàn khối) |
| `product:master:{product_id}` | RedisJSON | 1h | Partial Update + dedupe theo `product_id` + stale guard `version/updated_at` | Info sản phẩm gốc |
| `product:idempotency:{key}` | String (JSON) | 24h | Tự hết hạn | Chống duplicate request |
| `product:rate_limit:seller:{id}` | Counter | 1d | Tự hết hạn | Rate limit tạo SP |
| `product:markdown:active:{sku_id}` | String (JSON) | Dynamic | Markdown Worker DEL/SET (marker key) | Giá khuyến mãi active |
