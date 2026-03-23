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
5. **Bảo vệ Database (Phòng chống thảm hoạ Cache)**: Được tích hợp chặt chẽ cùng các Pattern chống Cache Stampede (áp dụng Local Mutex khi hết TTL) và Cache Penetration (trả về Empty Marker) để đảm bảo Connection Pool của PostgreSQL luôn an toàn dưới tải khủng hoặc bot đánh phá.

---

## 1. Cấu Trúc Khóa (Key Pattern) & Chức Năng

Tất cả các Redis Key của Product Service bắt đầu bằng prefix: `product:`

### 1.1 Nhóm Khóa Thẩm Định Dữ Liệu (Validation Data L1 + L2 Cache)
Đây là dữ liệu ít thay đổi nhất hệ thống, dùng để chặn các request tạo/sửa sản phẩm sai cấu hình danh mục.
Áp dụng chiến lược **2-Tier Caching (L1: Caffeine Local + L2: Redis)**. Dù áp dụng đẩy xoá cache Pub/Sub, Caffeine L1 bắt buộc phải có `expireAfterWrite = 1 phút` làm Fallback. Mức 1 phút này là đủ an toàn để cân bằng giữa hiệu năng phân tán và rủi ro lệch đồng bộ Validation Rule nếu Pod vô tình bị miss một bản tin Pub/Sub, thay vì giữ luật sai quá lâu.

*   `product:categories:tree`
    *   **Mô tả**: Toàn bộ cây danh mục của hệ thống dưới dạng JSON (dùng để check `category_id` truyền lên nằm ở lá mạng nào).
    *   **Loại Dữ Liệu**: `String (JSON)` trên Redis, Map memory trên Caffeine.
    *   **TTL**: Redis TTL có thể vô hạn, nhưng L1 Caffeine TTL là 1 phút.
    *   **Cách thức**: RAM của App chọc tới L1 (0.1ms). Nếu L1 rỗng, gọi L2 Redis (1ms). Nếu L2 rỗng, Fetch Database -> Lưu L2 -> Lưu L1.

*   `product:category:{category_id}:attributes`
    *   **Mô tả**: Danh sách các Thuộc tính (kèm cờ `is_required`, `is_variant`) của phần danh mục để Validate `attributes_json` mà Seller gửi lên.
    *   **Loại Dữ Liệu**: `Set (JSON)` hoặc `Hash`.
    *   **TTL**: 24 giờ. Cập nhật Cache-Aside.

### 1.2 Nhóm Khóa Point-Read Nội Bộ (Fast Access)
Order, Cart, Inventory Service không có quyền Query DB trực tiếp của Product. Họ gọi qua API `/api/v1/products/skus/{id}`.

*   `product:sku:{sku_id}`
    *   **Mô tả**: Chứa thông tin denormalized quan trọng nhất của một biến thể chuẩn bị vào giỏ. (Ví dụ: Định danh, Status, Giá Niêm Yết `original_price`, Giá Khuyến Mãi `promotional_price`, Product Name, Image).
    *   **Loại Dữ Liệu**: `RedisJSON` (hoặc `Hash`).
    *   **Khuôn mẫu JSON**:
        ```json
        {
          "sku_id": "sku-uuid-001",
          "product_id": "prod-uuid-001",
          "sku_code": "IP15PM-256-BLK",
          "product_name": "iPhone 15 Pro Max",
          "main_image_url": "https://cdn.example.com/ip15-black.jpg",
          "original_price": 34990000.00,
          "promotional_price": 28891500.00,
          "sku_status": "ACTIVE",
          "product_status": "ACTIVE",
          "variant_attributes": {"color": "Đen", "storage": "256GB"},
          "display_quantity": 150,
          "seller_id": "seller-uuid-001"
        }
        ```
    *   **TTL**: 1 tiếng (3600s).
    *   **Invalidation**: Nếu Seller Update tên, giá, đổi trạng thái, hoặc ảnh qua API `PUT/PATCH` của Product Service -> Xóa ngay Key này qua Cache-Evict (In-band) hoặc Worker nghe Kafka xoá (Out-of-band). Xem [Invalidation Consumer Worker](product_workers.md#2-invalidation-consumer-worker-xoá-cache-ngầm---optional).

> **Batch Point-Read Strategy:** Khi Cart Service cần lấy thông tin N SKU cùng lúc (VD: hiển thị giỏ hàng có 10 món), API nên hỗ trợ endpoint batch `POST /api/v1/products/skus/batch` nhận danh sách `sku_ids`. Backend sử dụng Redis `MGET` để fetch N keys cùng lúc trong 1 round-trip, tránh N+1 problem. Với các SKU bị cache miss, chỉ query DB cho những SKU thiếu (partial miss), không query lại toàn bộ.

*   `product:master:{product_id}`
    *   **Mô tả**: Thông tin chung của sản phẩm gốc phục vụ việc hiển thị giỏ hàng mượt mà (tên, category, seller, status).
    *   **Loại Dữ Liệu**: `String (JSON)`.
    *   **TTL**: 1 tiếng.

### 1.3 Nhóm Khóa Cơ Chế Vận Hành (Operational / Transactional)

*   `product:idempotency:{idempotency_key}`
    *   **Mô tả**: Ngăn chặn tình trạng tạo 2 Product cùng lúc do lỗi UI Double-Click hoặc Retries từ Client.
    *   **Loại Dữ Liệu**: `String (JSON Response)`.
    *   **TTL**: 24 Giờ.
    *   **Cơ chế**: Dùng `SETNX`. Request đầu tiên set biến thành công -> Vào DB. Request hai set xịt -> Bị dội ngược 409 Conflict hoặc lấy Response từ payload Cache. (Thư viện: Redisson/Spring Data Redis). Xem chi tiết luồng tại [Product Workflow](product_workflow.md). Tính Luỹ đẳng của Product sẽ được giữ tại Memory thay vì chọc Update ở PostgreSQL.

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
    *   **Invalidation**: [Markdown Price Worker](product_workers.md#5-markdown-price-calculator-worker) set key khi rule active, delete key khi rule expired.

---

## 2. Chiến Lược Đồng Bộ L1 / L2 Cache (Cache Invalidation)

Vì `category_attributes` hoặc `categories_tree` được nhét vào L1 (Bộ nhớ trong của JVM) để tăng max Speed, phát sinh rủi ro Caching Cũ (Stale Data) giữa vô số Instance (Máy chủ số 1 đã biết cây danh mục đổi, nhưng máy chủ 2 vẫn chứa cây cũ trong RAM).

**Giải pháp Invalidation (L1 Pub-Sub):**
1. Admin Sửa cấu hình Danh Mục trên bảng `categories`.
2. Hệ thống gọi lệnh `DEL product:categories:tree` (Xoá L2 ở vùng chung Redis).
3. Hệ thống Product Service (Instance chịu trách nhiệm đổi Database) sẽ gửi một Broadcast Message lên Redis Pub/Sub channel (VD: `channel:product:cache:evict`).
4. **TẤT CẢ** các Product Service Instances còn lại đang subscribe channel này lắng nghe. Lấy lệnh -> Bắn lệnh `CaffeineCacheManager.clear("categoryTree")`.
5. Đợt Request sau tới từ bất kỳ Máy nào, Local L1 rỗng, Redis L2 Rỗng -> Chọc DB nạp lại mới cả 2 Tier.

---

## 3. Quản Trị Hệ Quả Của Cache Miss
Product Service chứa hàng chục triệu mã SKU. Việc "đập" tất cả vào Redis là bất khả thi về dung lượng RAM.

*   **Lazy Loading (Cache-Aside)**: Cho dữ liệu `product:sku:{id}`. Khi bị Query API, hệ thống kiểm tra cache trước. Rỗng thì mới query DB rồi Set ngược lại với TTL cố định.
*   **Chống Stampede (Hiệu ứng dẫm đạp)**: Nếu một đợt Flash Sale / Traffic ảo ập tới một sản phẩm đang hết TTL (Cache Miss) trên Redis, hệ thống chỉ cho 1 Thread được phép phi xuống Database Fetch bằng Local Lock/Mutex bên trong code. Đám luồng còn lại Wait 1-2ms và lấy kết quả nạp sau 1 vòng. Chắn đứng Connection Pool của PostgreSQL không bị sập.
*   **Chống Cache Penetration (Truy vấn Không tồn tại)**: Với các `sku_id` không tồn tại trong DB, hệ thống cache giá trị rỗng (empty marker) với TTL ngắn (60s) để tránh request giả mạo liên tục đập xuống DB. Optional: sử dụng Bloom Filter để check nhanh sự tồn tại của SKU trước khi chọc Redis/DB.

---

## 4. Tổng Hợp Redis Key Inventory

| Key Pattern | Loại | TTL | Invalidation | Mục đích |
|---|---|---|---|---|
| `product:categories:tree` | String (JSON) | Vĩnh viễn | Pub/Sub L1 | Cây danh mục validate |
| `product:category:{id}:attributes` | Hash/Set | 24h | Cache-Aside | Thuộc tính theo Category |
| `product:sku:{sku_id}` | RedisJSON/Hash | 1h | Partial Update (Worker) | Point-Read cho Cart/Order (Sử dụng Partial Update hoặc Hash để tránh Data Drift, CẤM gán full snapshot string) |
| `product:master:{product_id}` | RedisJSON/Hash | 1h | Partial Update | Info sản phẩm gốc |
| `product:idempotency:{key}` | String (JSON) | 24h | Tự hết hạn | Chống duplicate request |
| `product:rate_limit:seller:{id}` | Counter | 1d | Tự hết hạn | Rate limit tạo SP |
| `product:markdown:active:{sku_id}` | String (JSON) | Dynamic | Worker DEL/SET | Giá khuyến mãi active |
