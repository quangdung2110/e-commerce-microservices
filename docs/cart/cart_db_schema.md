# Cart Service - Database Schema Design (Cold Store)

**Liên kết nhanh (Related Documents):**
*   [Workflows (API & Data Flow)](cart_workflow.md)
*   [Background Workers & Jobs](cart_workers.md)
*   [Caching Schema (Redis-First)](cart_caching_schema.md)
*   [Database Schema (Cold Store)](cart_db_schema.md)
*   [Outbox Pattern (Redis Streams)](cart_outbox_pattern.md)
*   [Idempotency Pattern](cart_idempotency.md)
*   [Events Structure](cart_events.md)

---
Tài liệu này mô tả thiết kế của PostgreSQL cho Cart Service. Vì Cart Service mang tư tưởng **Redis-First** (Primary Storage), Database RDBMS ở đây đóng vai trò là **Cold Storage (Kho lưu trữ lạnh / Backup Store)**. 

Bản chất của bảng này sinh ra để:
1.  **Backup Data Survival:** Redis có rủi ro tràn memory bị Evict dữ liệu cũ, hoặc rớt máy chủ mất data. PostgreSQL lưu trạng thái bền vững trên ổ đĩa.
2.  **Khôi phục Cache (Cache Miss Fallback):** Nếu API kéo Redis mà Key bị hụt, hệ thống Query PostgreSQL kéo JSON Snapshot lên và build lại lên Redis trong ngầm (Goroutine/Background Thread). Xem chi tiết tại [Cache-Miss Fallback Workflow](cart_workflow.md#3-api-lấy-thông-tin-giỏ-hàng-đọc).
3.  **Phân tích Data (Analytics):** Phục vụ các team Marketing/Data (ví dụ truy vấn giỏ hàng Bị bỏ quên (Abandoned Cart) để gửi Email Marketing giảm giá). Xem [Abandoned Cart Sweeper](cart_workers.md#3-abandoned-cart-marker-batch-cronjob).

---

## 1. Mối Quan Hệ với Primary Store (Redis)

PostgreSQL **không phải** là nguồn sự thật chính (Source of Truth) — Redis mới là. Mọi thao tác CRUD diễn ra trực tiếp trên [Redis (Caching Schema)](cart_caching_schema.md). Dữ liệu được đẩy xuống PostgreSQL **bất đồng bộ** thông qua chuỗi: [Lua Script XADD](cart_caching_schema.md#31-thêm-add--cập-nhật-update-item-ngăn-chặn-race-condition) → [Redis Streams Outbox](cart_outbox_pattern.md) → [Kafka](cart_workers.md#1-outbox-relay-producer-worker) → [Postgres Sync Worker](cart_workers.md#2-postgres-sync-consumer-worker).

Đảm bảo tính luỹ đẳng (Idempotency) khi ghi qua cơ chế [Stream ID-based Upsert (primary) + Timestamp fallback](cart_idempotency.md).

## 2. Cấu Trúc Bảng (ERD)

Mô hình thiết kế phi chuẩn một phần để tối ưu hóa việc khôi phục giỏ hàng: Giữ 1 bảng tổng quan `carts` (Master) và 1 bảng chi tiết `cart_items`.

### 2.1. Bảng `carts`
Lưu trữ định danh chung của một giỏ hàng, phục vụ cho việc tracking vòng đời trạng thái.

*   `cart_id`: VARCHAR(255) PRIMARY KEY. Lưu Key gốc của Redis (VD: `{cart:user:12345}`). **Quy ước cứng:** `cart_id = Redis Key` là convention bất biến trong toàn hệ thống (DB, Events, Kafka payload). Nếu format key Redis thay đổi, cần migration đồng bộ.
*   `user_id`: VARCHAR(255) INDEX. Lưu trực tiếp `user_id` từ event payload. **Tuyệt đối KHÔNG tách chuỗi từ `cart_id`** vì anti-pattern và dễ sai nếu format key thay đổi. `user_id` phải có trong mọi event payload (thêm vào Lua Script ARGV[7]).
*   `status`: VARCHAR(50) DEFAULT 'ACTIVE' (Cập nhật qua các State). Xem [Events Structure](cart_events.md) để biết event nào kích hoạt chuyển đổi trạng thái.
    *   `ACTIVE`: Đang được sử dụng tương tác.
    *   `ABANDONED`: Khách để quá 30 ngày không checkout — được đánh dấu bởi [Abandoned Cart Marker CronJob](cart_workers.md#3-abandoned-cart-marker-batch-cronjob).
    *   `DELETED`: Giỏ hàng đã bị xóa sạch (toàn bộ items bị remove dẫn đến `HLEN == 0`). Kích hoạt bởi event [`CART_CLEARED`](cart_events.md#24-cart_cleared). Đây là **soft delete** (không hard-delete ngay). Sẽ bị hard-delete sau 3 tháng bởi [Data Archival CronJob](cart_workers.md#4-data-archival-cleaner-batch-cronjob).
    *   `CHECKED_OUT`: Giỏ hàng đã hoàn tất checkout **và rỗng hoàn toàn**. Được cập nhật khi nhận event `CART_CHECKED_OUT` (phát sau `ORDER_PAID` khi `HLEN == 0`) qua Kafka. Partial checkout (chỉ xóa một số SKU nhưng còn SKU khác) giữ trạng thái `ACTIVE`.
*   `last_event_stream_id`: VARCHAR(64) NULL. (**Trường Quan Trọng**) Lưu Redis Stream ID mới nhất đã áp dụng cho trạng thái cart (VD: `1708616400456-0`). Dùng làm guard chính chống out-of-order.
*   `last_event_time_ms`: BIGINT DEFAULT 0. Lưu Logical Event Time theo **Milliseconds Epoch** để analytics và fallback tương thích.
*   `abandoned_event_published_at`: TIMESTAMPTZ NULL. Dùng cho Cronjob ABANDONED để đánh dấu đã publish `CART_ABANDONED` thành công lên Redis Stream/Kafka. Nếu NULL, job reconcile sẽ publish bù.
*   `created_at`: TIMESTAMPTZ
*   `updated_at`: TIMESTAMPTZ (Dùng để xác định 'Abandoned' data hoặc dọn dẹp hệ thống. Cập nhật bởi DB/Worker khi ghi dòng dữ liệu)

### 2.2. Bảng `cart_items`
Lưu trữ danh sách hàng hóa bên trong 1 giỏ, tái tạo lại độ phức tạp của Json Value trên Redis.

*   `item_id`: VARCHAR(255) PRIMARY KEY (Format: `{cart_id}:{sku_id}`).
*   `cart_id`: VARCHAR(255) INDEX (Foreign Key tham chiếu ảo tới `carts.cart_id`).
*   `product_id`: VARCHAR(255) INDEX.
*   `sku_id`: VARCHAR(255) INDEX.
*   `quantity`: INT NOT NULL DEFAULT 0.
*   `unit_price_at_add`: DECIMAL(15,2) NOT NULL DEFAULT 0.00 (Lưu số tiền lúc khách vừa bắt đầu click "Thêm vào giỏ", dùng hiển thị chênh lệch giá do Promotion hay do thời gian. Lưu ở **cột riêng** vì cần query analytics và idempotency, KHÔNG đưa vào `product_snapshot`).
*   `product_snapshot`: JSONB (***Trường Quan Trọng***)
    *   Lý do: PostgreSQL không cần biết chi tiết sản phẩm. Hệ thống nhét toàn bộ các thuộc tính như `{"product_name": "Áo Bò", "image_url": "link", "variant_info": "Xanh lơ", "added_at": "1708530000123", "updated_at": "1708616400456", "is_available": true}` vào JSONB.
    *   Mục đích 1: Tái cấu trúc lại object Struct để nhét lên lại Redis. **LƯU Ý:** Khi rehydrate từ DB lên Redis, Worker phải inject thêm `unit_price_at_add` từ cột riêng vào JSON trước khi ghi. Flag `is_available` buộc phải nằm trong JSONB này.
    *   Mục đích 2: Lưu `updated_at` (Milliseconds Epoch) để làm **fallback guard** cho out-of-order khi bản ghi legacy chưa có `redis_stream_id`.
    *   Ràng buộc: `current_price` không lưu trong `product_snapshot`; đây là field runtime của API đọc giỏ.
    *   Timestamps (`added_at`, `updated_at`) lưu dạng **chuỗi số nguyên Milliseconds Epoch** để nhất quán với Redis Hash Value và tránh precision loss qua JSON.
*   `redis_stream_id`: VARCHAR(64) NULL. Lưu Redis Stream ID mới nhất đã áp dụng cho item; là khóa idempotency/out-of-order chính khi Upsert.
*   `created_at`: TIMESTAMPTZ
*   `updated_at`: TIMESTAMPTZ

> **Lưu ý về Hard Delete:** Khi nhận event `CART_ITEM_DELETED`, Worker sẽ thực hiện **Hard Delete** (xóa hẳn dòng khỏi DB) thay vì Soft Delete. Điều này đơn giản hóa logic và giảm độ phức tạp của hệ thống. Vì Redis là Source of Truth, nếu item đã bị xóa khỏi Redis thì trong DB cũng không cần giữ bản ghi.

## 3. Workflow Đồng Bộ (PostgreSQL là RDBMS phụ thuộc)

Vì dữ liệu chính chạy trên nền Redis, dữ liệu đẩy xuống PostgreSQL sẽ không làm "Chậm" lại quá trình API của người viết. Xem toàn bộ luồng tại [Cart Workflow](cart_workflow.md).

1.  **Dumb Store**: PostgreSQL trong Service này sẽ bị "Ngu hóa" (Chỉ nhận lệnh Upsert (Create or Update) chứ không bắt ràng buộc Unique rườm rà cản trở Business logic chính trên tầng Cache).
2.  **Concurrency / Lock**: Khi chạy Upsert từ Worker lên Table `cart_items`, sử dụng tính năng `ON CONFLICT` của Postgres Database. Guard chính dùng `redis_stream_id`; chỉ fallback sang `updated_at` khi bản ghi cũ chưa có stream id. Xem chi tiết cú pháp tại [Idempotency Pattern](cart_idempotency.md#23-xử-lý-sai-thứ-tự-out-of-order).
3.  **Đồng bộ bảng Master `carts`**: Trong cùng Transaction DB của Worker khi xử lý Upsert `cart_items` hoặc status event, Worker phải cập nhật bảng `carts` theo nguyên tắc:
    - Event `CART_ITEM_ADDED` / `CART_ITEM_UPDATED`: luôn set `status = 'ACTIVE'` (re-activate), cập nhật `last_event_stream_id`, `last_event_time_ms`, `updated_at` theo guard version. `user_id` lấy trực tiếp từ event payload (không parse `cart_id`).
    - Event `CART_CLEARED` / `CART_CHECKED_OUT`: chỉ đổi trạng thái khi `stream_id` mới hơn (primary), fallback theo event time cho bản ghi legacy chưa có `last_event_stream_id`.
    - Không update mù trạng thái chỉ theo `cart_id`; luôn có điều kiện thời gian/version để chặn out-of-order.

> **Lưu ý:** Bảng `cart_items` sử dụng "Foreign Key tham chiếu ảo" (Application-level reference) — **KHÔNG** có ràng buộc FK thực sự trên PostgreSQL. Do đó **CASCADE DELETE không hoạt động**. Khi cần xóa giỏ hàng, Application/Worker phải chủ động xóa `cart_items` trước rồi mới xóa `carts`. Xem [Data Archival CronJob](cart_workers.md#4-data-archival-cleaner-batch-cronjob).

## 4. Quản lý Không Gian (Archival / Cold Data Policy)

Nguy cơ: Do hệ thống là E-commerce có lượng User thay đổi trạng thái giỏ hàng liên tục (Thêm sản phẩm -> Lại xóa -> Lại Add -> Rồi bỏ đi qua sàn khác).
Bảng `carts` và `cart_items` trên PostgreSQL sẽ phình lên hàng triệu Rows cực rác.

**Giải pháp (CronJobs / Batch Data Handling):** Xem chi tiết Worker tại [Background Workers](cart_workers.md).

Quy định thời gian sống (TTL của RDBMS Database) theo trạng thái:

| Trạng thái | Điều kiện | Hành động | CronJob / Event |
|---|---|---|---|
| `ACTIVE` | `updated_at` quá 30 ngày không tương tác | Chuyển `status` → `ABANDONED` và publish event `CART_ABANDONED` qua Outbox | [Abandoned Cart Marker](cart_workers.md#3-abandoned-cart-marker-batch-cronjob) |
| `ABANDONED` | Ngay khi mark thành `ABANDONED` | Lắng nghe event `CART_ABANDONED` xử lý nghiệp vụ Notification re-engagement | Kafka `t.cart.events` |
| `DELETED` | `updated_at` quá 3 tháng | Hard Delete | [Data Archival Cleaner](cart_workers.md#4-data-archival-cleaner-batch-cronjob) |
| `CHECKED_OUT` | `updated_at` quá 3 tháng | Hard Delete | [Data Archival Cleaner](cart_workers.md#4-data-archival-cleaner-batch-cronjob) |
| `ABANDONED` | `updated_at` quá 6 tháng | Hard Delete | [Data Archival Cleaner](cart_workers.md#4-data-archival-cleaner-batch-cronjob) |

> **Quan trọng:** Vì `cart_items` **không có FK constraint thực sự** (tham chiếu ảo), khi xóa giỏ hàng phải chạy trong 1 transaction:
> 1. `DELETE FROM cart_items WHERE cart_id = ?`
> 2. `DELETE FROM carts WHERE cart_id = ?`
> 
> Không được dùng CASCADE. Xem [mục 3 - Workflow Đồng Bộ](#3-workflow-đồng-bộ-postgresql-là-rdbms-phụ-thuộc).
