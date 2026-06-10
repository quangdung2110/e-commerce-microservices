# Product Service - Caching Schema (Redis)

**Liên kết nhanh (Related Documents):**
*   [Workflows (API & Data Flow)](product_workflow.md)
*   [Background Workers & Jobs](product_workers.md)
*   [Caching Schema (Redis)](product_caching_schema.md)
*   [Database Schema (Write Store)](product_db_design.md)
*   [State Machine & Lifecycle](product_state_machine.md)
*   [Events Structure & Kafka Topics](product_events.md)
*   [Architecture Recommendations](product_architecture_recommendations.md)

---
Tài liệu này mô tả chi tiết chiến lược Caching áp dụng bên trong Product Service. Trong kiến trúc CQRS, Product Service nhận trọng trách làm **Write Model (Command)**. Do đó, Cache tại service này không sinh ra để phục vụ việc search/lọc sản phẩm của End-User (việc đó do Elasticsearch của Search Service lo), mà tập trung vào **3 nhiệm vụ cốt lõi** sau:

1. **Cung cấp dữ liệu Tức thì (Point-Read) cho hệ sinh thái**: Phục vụ các API Gọi Nội Bộ (RPC/HTTP) dưới dạng các endpoint đơn và Batch cho Order, Cart, Inventory Service. Đóng vai trò như một Read-Replica In-Memory siêu tốc, ngăn chặn lỗi N+1 Query.
2. **Tích hợp trực tiếp Giá Khuyến Mãi (Markdown Pricing)**: Cache giá On-The-Fly cho luồng tra cứu Checkout và Product Detail với TTL động theo thời hạn chương trình khuyến mãi.

---

## 1. Nhóm Khóa Point-Read Nội Bộ (Fast Access)
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
          "main_image_url": "https://cdn.example.com/ip15-black.jpg",
          "original_price": 34990000.00,
          "promotional_price": 28891500.00,
          "sku_status": "ACTIVE",
          "product_status": "ACTIVE",
          "is_available": true,
          "is_deleted": false,
          "variant_attributes": {"color": "Đen", "storage": "256GB"},
          "display_quantity": 150,
          "last_inventory_sync_at": 1709420000000,
        }
        ```
    *   **TTL**: 1 tiếng (3600s).
    *   **Xử lý Invalidation**: Khi có cập nhật SKU từ DB, hệ thống bắt buộc dùng OVERWRITE (Soft Invalidation), không dùng DEL hàng loạt để tránh Cache Stampede. Writer cho `product:sku:{sku_id}`:
    * **In-band API**: Xử lý ngay trong API trả HTTP response (PRODUCT_STATE_CHANGED, PRODUCT_DELETED, PRODUCT_UPDATED).
    * **Event Handler Worker**: Chỉ phụ trách refresh khi nhận `PRODUCT_PRICE_UPDATED` (từ Markdown Worker). Xem [Event Handler Worker](product_workers.md#2-event-handler-worker).

> **Batch Point-Read Strategy:** Khi Cart Service cần lấy thông tin N SKU cùng lúc (VD: hiển thị giỏ hàng có 10 món), API nội bộ dùng gRPC `GetBatchProductSkus` nhận danh sách `sku_ids`. Backend sử dụng Redis `JSON.MGET` để fetch N keys cùng lúc trong 1 round-trip, tránh N+1 problem. Với các SKU bị cache miss, chỉ query DB cho những SKU thiếu (partial miss), không query lại toàn bộ.

## 2. Nhóm Khóa Tích Hợp Giá Khuyến Mãi (Markdown Cache)

> **Contract nhất quán:** `rule_id` trong cache markdown luôn lưu dạng string. Nếu upstream gửi số, worker phải normalize sang string trước khi ghi.

*   `product:markdown:active:{sku_id}`
    *   **Mô tả**: Cache giá khuyến mãi đang active cho SKU cụ thể. Dùng cho API Detail Page (On-The-Fly pricing) khi cần check nhanh giá giảm hiện tại mà không cần query DB. Xem [Promotion DB Design - On-The-Fly](../promotion/promotion_db_design.md#22-xử-lý-zero-latency-tính-toán-best-deal-mà-không-cần-discount_items-mapping).
    *   **Loại Dữ Liệu**: `String (JSON)`.
    *   **Khuôn mẫu JSON**:
        ```json
                {
                    "rule_id": "42",
                    "discount_type": "PERCENTAGE",
                    "discount_value": 15.00,
                    "calculated_price": 28891500.00,
                    "expires_at": "2026-03-07T23:59:59Z"
                }
        ```
    *   **TTL**: Đặt bằng thời gian còn lại của rule (`expires_at - NOW()`). Redis tự xoá khi rule hết hạn.
    *   **Invalidation**: [Markdown Price Worker](product_workers.md#4-markdown-price-calculator-worker) set/del key này khi rule active hoặc ended. Worker này chỉ quản lý key markdown marker; key point-read `product:sku:{sku_id}` do [Event Handler](product_workers.md#2-event-handler-worker) refresh theo `PRODUCT_PRICE_UPDATED`.

---

## 3. Nhóm Khóa Inventory Sync (Pending Sync)

| Key Pattern | Loại | TTL | Invalidation | Mục đích |
|---|---|---|---|---|
| `product:inventory:pending` | Redis Sorted Set (ZSET) | 24h | Ack tự động sau Phase 2 DB sync | Pending sync queue cho Two-Phase Lock-Free Inventory Sync. Global ZSET dùng chung cho tất cả SKU. Xem [Inventory Sync Optimization](inventory_sync_optimization.md) |

> **Chi tiết Two-Phase Inventory Sync**: Phase 1 ghi vào ZSET này sau khi update cache thành công. Phase 2 drain ZSET để sync xuống PostgreSQL. Xem [Inventory Sync Worker](product_workers.md#5-inventory-sync-worker-two-phase-lock-free-architecture).

---

## 4. Tổng Hợp Redis Key Inventory

| Key Pattern | Loại | TTL | Writer | Mục đích |
|---|---|---|---|---|
| `product:sku:{sku_id}` | RedisJSON | 1h | In-band API (PRODUCT_STATE_CHANGED, PRODUCT_DELETED, PRODUCT_UPDATED) + Event Handler (PRODUCT_PRICE_UPDATED từ Markdown) | Point-Read cho Cart/Order |
| `product:markdown:active:{sku_id}` | String (JSON) | Dynamic | Markdown Worker DEL/SET (marker key) | Giá khuyến mãi active |
| `product:inventory:pending` | ZSET | 24h | Ack tự động sau Phase 2 DB sync | Pending sync queue cho Inventory Sync (Two-Phase) |
