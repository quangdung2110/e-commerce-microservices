# Inventory Service - Database & Cache Schema Design

Tài liệu này mô tả thiết kế cơ sở dữ liệu (PostgreSQL) và bộ nhớ đệm (Redis) cho Inventory Service. Kiến trúc giải quyết các bài toán hóc búa nhất của một hệ thống E-Commerce Marketplace quy mô lớn: Overselling (Bán vượt mức), nghẽn cổ chai DB (Bottleneck) khi sự kiện Flash Sale diễn ra, quản lý đa luồng (Multi-tenant), và nguyên lý bảo vệ dữ liệu Sổ cái Kế toán (Ledger Reconciliation) khi xử lý Message Queue.

---
## 1. PostgreSQL Schema (Dữ liệu Lõi)

### 1.1. Bảng Quản lý Đa thực thể (Marketplace Multi-tenant)
Hệ thống hỗ trợ cả sản phẩm của Sàn (Platform) và của Người bán (Seller - INDIVIDUAL/CORPORATE). Hàng hóa vật lý xuất phát từ nhiều điểm kho khác nhau để phục vụ việc tối ưu phí vận chuyển (Logistics).

*   **`warehouses` (Danh sách Kho bãi):**
    *   `id`: BIGSERIAL PRIMARY KEY
    *   `owner_type`: VARCHAR(50) (PLATFORM, SELLER)
    *   `owner_id`: BIGINT (Định danh Sàn hoặc Chủ Shop)
    *   `name`: VARCHAR(255) (VD: "Kho Tân Bình", "Kho Nhà riêng Seller A")
    *   `address`, `latitude`, `longitude`: Thông tin địa chỉ tọa độ cực kỳ quan trọng để Service Vận chuyển (Logistics) tính phí ship cho người mua.
    *   `city_code`: VARCHAR(50) (Mã Tỉnh/Thành phố)
    *   `district_code`: VARCHAR(50) (Mã Quận/Huyện)
    *   `ward_code`: VARCHAR(50) (Mã Xã/Phường)

### 1.2. `physical_inventory` (Tồn kho Vật lý - Kho thực tế)
Lưu trữ số lượng hàng hóa thực tế đang nằm trên kệ tại các kho bãi. Dữ liệu này thường được đồng bộ tĩnh từ hệ thống WMS (Warehouse Management System) mỗi khi nhập hàng.
*   `id`: BIGSERIAL PRIMARY KEY
*   `sku_id`: BIGINT (Mã định danh sản phẩm/phiên bản)
*   `warehouse_id`: BIGINT (Foreign Key -> `warehouses.id`)
*   `owner_id`: BIGINT (Tenant / Chủ sở hữu món hàng)
*   `actual_quantity`: INT (Số lượng vật lý nguyên vẹn thực tế)
*   `updated_at`: TIMESTAMPTZ
*   **Unique Index (`sku_id`, `warehouse_id`)**: Một kho chỉ chứa 1 record biểu diễn số lượng vật lý của 1 SKU. 1 SKU có thể nằm ở nhiều kho khác nhau.

### 1.3. `logical_inventory` (Tồn kho Logic - Sàn Bán hàng)
Bảng cốt lõi phục vụ hiển thị bán hàng (Available To Promise). Nó Gộp (Aggregation) số lượng từ các physical warehouse. Giao diện App/Web đọc từ bảng này để hiển thị nút "Mua" hoặc "Hết hàng".
*   `id`: BIGSERIAL PRIMARY KEY
*   `sku_id`: BIGINT UNIQUE
*   `owner_id`: BIGINT (Tenant ID)
*   `total_quantity`: INT (Tổng hạn mức mang ra bán trên Sàn online từ tất cả các kho)
*   `reserved_quantity`: INT (Số lượng lượng ĐANG BỊ "Giữ chỗ" bởi các order chờ thanh toán)
*   `available_quantity`: INT GENERATED ALWAYS AS (total_quantity - reserved_quantity) STORED.
*   `version`: BIGINT
    *   *(CHÚ Ý CỐT LÕI: Cột version NÀY CHỈ DÙNG ĐỂ CHỐNG DEADLOCK CHO LUỒNG BACKOFFICE ADMIN CHẬM CHẠP (VD: 2 Admin cùng vào sửa tay số lượng kho). ĐỐI VỚI LUỒNG FLASH SALE USER MUA HÀNG TỐC ĐỘ CAO, TUYỆT ĐỐI KHÔNG ĐỌC VERSION MÀ PHẢI DÙNG LỆNH DELTA UPDATE (VD: `UPDATE logical_inventory SET reserved_quantity = reserved_quantity + 1 WHERE sku_id = X`). Lệnh này tận dụng lock Row-level tàng hình của Postgres để chèn tốc độ cao mà không văng Exception cho Client).*

### 1.4. `inventory_reservations` (Danh sách Cấp phát Giữ chỗ Đa điểm)
Bảng lưu dấu vết Đơn hàng (Order) nào đang chiếm dụng số lượng tồn kho bao nhiêu VÀ TỪ KHO NÀO (Phục vụ Smart Order Routing & Split Order). Phục vụ Timeout trả hàng hoặc Khấu trừ đứt.
*   `id`: BIGSERIAL PRIMARY KEY
*   `order_id`: BIGINT (Index - Mã đơn hàng gốc Master Order)
*   `parcel_id`: BIGINT (Index - Mã kiện hàng phụ Sub-order nếu đơn bị tách kiện)
*   `warehouse_id`: BIGINT NULL (Kho xuất hàng cụ thể được cấp phát bởi Smart Routing; với Fast-Lane/Hot SKU thì có thể `NULL` ở pha Reserve và được gán sau ở pha DOM Re-routing)
*   `sku_id`: BIGINT
*   `quantity`: INT (Lượng hàng giữ chỗ. VD: 2 chiếc iPhone)
*   `status`: VARCHAR(50) (RESERVED, DEDUCTED, CANCELLED)
*   `expires_at`: TIMESTAMPTZ (Hạn chót thanh toán, VD: reserved_at + 15 mins. Delay Queue của Kafka sẽ đối chiếu mốc này).

### 1.5. `inventory_ledger` (Sổ cái Phân tán - Audit Trail)
Ghi nhận TẤT CẢ mọi dòng chảy thao tác làm thay đổi rổ hàng (Append-only). Đây là "Nguồn Chân Lý" (Source of Truth) cho tính toàn vẹn tồn kho.
*   `id`: BIGSERIAL PRIMARY KEY
*   `sku_id`: BIGINT (Index)
*   `reference_type`: VARCHAR(50) (ORDER_RESERVED, ORDER_CANCELLED, ORDER_DEDUCTED, VENDOR_RESTOCK)
*   `quantity_change`: INT (Mức bù trừ: +10, -2, ...)
*   `balance_after`: INT (Số dư `available_quantity` sau giao dịch).
*   *Lưu ý Kiến trúc:* Bảng này không bao giờ được nhận lệnh INSERT trực tiếp từ các HTTP Request của hàng ngàn User mua hàng (sẽ gây thắt cổ chai cục bộ vì phải khoá dòng để tính `balance_after`). Việc Insert bảng này được uỷ thác hoàn toàn cho Kafka Worker chạy đằng sau dưới dạng **Bulk Insert Batching** (Chi tiết tại Phần 3).

### 1.6. Hệ thống Xử lý Sự cố Sổ cái (DLQ & Waiting Room)
Giải quyết triệt để lỗi "Sự kiện Độc" (Poison Pill) khi consume Kafka mà không làm Sổ cái bị sai lệch hay xả rác hàng loạt ra DLQ.
*   **`suspended_sku_locks` (Bảng khóa SKU bền vững):**
    *   Thay cho danh sách đen trong RAM, lock trạng thái `SUSPENDED` của `sku_id` được lưu bền vững trong DB.
    *   Cột: `sku_id` (PK), `lock_reason`, `locked_at`, `unlocked_at`, `status`.
    *   Worker phải check bảng này trước khi xử lý batch để đảm bảo sau restart vẫn giữ đúng ordering.
*   **`suspended_events` (Phòng chờ / Parking Lot):** 
    *   Lưu trữ những sự kiện đến sau của một `sku_id` đang bị phong tỏa (Do một sự kiện đến trước của chính SKU đó bị lỗi văng vào DLQ). 
    *   Cột: `id`, `sku_id`, `event_payload` (JSONB), `sequence_order`, `status` (PARKED).
    *   Giúp vạch rõ chuỗi nhân quả, các sự kiện sau không bị tính sai `balance_after` hay bị vứt oan uổng vào bãi rác DLQ.

### 1.7. `inventory_idempotency` (Bảng Chống lặp Message - Saga)
Đảm bảo Kafka at-least-once delivery không làm cộng dồn sai số tiền và tồn kho.
*   `event_id`: VARCHAR(255) PRIMARY KEY (Kafka Message Correlation ID)
*   `action_type`: VARCHAR(50)
*   `processed_at`: TIMESTAMPTZ

### 1.8. `outbox_events` (Transactional Outbox cho Inventory)
Đảm bảo các event cần đồng nhất với DB write (đặc biệt `INVENTORY_QUANTITY_CHANGED`) được publish đáng tin cậy sau khi transaction commit.
*   `id`: UUID PRIMARY KEY
*   `aggregate_type`: VARCHAR(100) (VD: `Inventory`)
*   `aggregate_id`: VARCHAR(255) (`sku_id`)
*   `event_type`: VARCHAR(100) (VD: `INVENTORY_QUANTITY_CHANGED`)
*   `payload`: JSONB
*   `status`: VARCHAR(20) DEFAULT 'PENDING' (`PENDING`, `PUBLISHED`, `FAILED`, `DEAD`)
*   `retry_count`: INT DEFAULT 0
*   `max_retries`: INT DEFAULT 5
*   `next_retry_at`: TIMESTAMPTZ NULL
*   `published_at`: TIMESTAMPTZ NULL
*   `error_message`: TEXT NULL
*   `created_at`: TIMESTAMPTZ

---
## 2. Redis Caching Schema (Chống thắt cổ chai Flash Sale)

Bảo vệ Database khỏi bão traffic ngầm (Over-selling) trong Flash Sale bằng sức mạnh của In-memory Caching.  

> **Lưu ý Quan trọng:** Phần kiến trúc Redis Caching (L1/L2), chiến lược phân rổ chống Hot-Key (Bucketing) và cấu trúc Lua Scripts nguyên tử chi tiết đã được tách rời sang tài liệu chuyên biệt để dễ quản lý: **[Xem chi tiết tại: inventory_redis_design.md](inventory_redis_design.md)**

---
## 4. Kiến trúc Event Streaming và Xử lý Sổ cái Khối lượng lớn (Batching Ledger)

Cơ chế tính toán và ghi nhận `inventory_ledger` là tinh hoa kiến trúc của Service này, giải quyết bài toán Throughput (Chậm trễ) và Toàn vẹn dữ liệu.

### 4.1. Vòng lặp Kafka Long Polling & Bulk Insert
Luồng ghi Sổ cái không bao giờ dội DB bằng API trực tiếp. Trình tự Worker đục khoét Kafka Partition:
1.  **Chờ dữ liệu (Long Polling):** Worker gọi `poll(100ms)`. Vào kì bão Flash Sale, Kafka chứa sẵn đầy queue sẽ trả ngay về một lô **100 Events** (0ms độ trễ).
2.  **Gom nhóm & Tính toán RAM (~3ms):** 
    * Worker lặp qua 100 events, dùng thuật toán Gom nhóm `Map<sku_id, List<Events>>`.
    * Cầm List SKU ra DB SELECT Nhanh để lấy mốc `balance_after` mới nhất làm vốn khởi điểm.
    * Thực hiện phép cộng trừ tuyến tính trên RAM cho 100 dòng này.
3.  **Bulk Insert vào DB (~10ms):** Sử dụng `executeBatch()` của JDBC nguyên cục 100 record chọc xuống Postgres. Nhanh và hiệu quả vượt bật.
**=> Tốc độ lên tới ~6.000 Giao dịch/Giây/Thread mà DB không hề thấy nóng.**

### 4.2. Xử lý Lỗi "Thuốc độc" (Poison Pill) với "Phòng chờ Parking Lot"
Nếu 1 luồng Bulk Insert gặp sự cố `BatchUpdateException` (Vi phạm DB Index), toàn bộ Batch bị Rollback. Lúc này phải Fallback để truy lùng thủ phạm thay vì làm tắt nghẽn hệ thống.

1.  **Phát hiện và Cô lập:** 
    * Worker chuyển sang Insert One-By-One. Bắt được đúng Event của 'iPhone' ném ra Exception.
    * Worker ném Event này vào **DLQ** (Để người xem) và ghi lock vào **`suspended_sku_locks`** (status=`SUSPENDED`).
2.  **Đưa vào Phòng Chờ (Không xả rác DLQ):**
    * Khi duyệt đến các Event tiếp theo của Batch, nếu gặp Event của 'iPhone', Worker phát hiện ngay chuỗi nhân quả đã bị gãy do án phong tỏa.
    * Thay vì cắm đầu ném hàng loạt các Event đi sau vô tội vào DLQ, Worker mượt mà ghi chúng vào DB **Bảng Phòng Chờ `suspended_events` (Parking Lot)**.
    * Các kiện hàng đi sau của hãng khác (Samsung, Xiaomi) trong Batch vẫn được tính và Insert DB trơn tru. Sổ cái an toàn tuyệt đối, Partition ko bị block.
3.  **Vòng lặp Tự phục hồi (Auto-Recovery):**
    * Sau khi Kỹ sư vận hành fix lỗi Event 'iPhone' trên DLQ và Unlock.
    * Một Cronjob/Worker quét phòng chờ `suspended_events` thấy 'iPhone' đã trở lại bình thường. Nó tự động bốc các sự kiện lưu cữu ra rã đông, tái tính `balance_after` và ghi bổ sung vào Sổ cái `inventory_ledger`. Luồng 'iPhone' hòa máu tươi trở lại hệ thống một cách trong suốt.
