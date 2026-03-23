# Order Service - Database & Architecture Design

**Liên kết nhanh (Related Documents):**
*   [State Machine & Lifecycle](order_state_machine.md)
*   [Temporal Saga Design](temporal_saga_design.md)
*   [CQRS & Elasticsearch](cqrs_elasticsearch_design.md)
*   [Cold Data Archiving](cold_data_archiving_design.md)
*   [Workflows (API & Data Flow)](order_workflow.md)
*   [Background Workers & Jobs](order_workers.md)
*   [Events Structure & Kafka Topics](order_events.md)

---

Tài liệu này mô tả thiết kế cơ sở dữ liệu (PostgreSQL) cho Order Service, chịu trách nhiệm quản lý vòng đời đơn hàng, tách kiện hàng (Order Splitting) tối ưu chi phí vận chuyển, và điều phối giao dịch phân tán (Distributed Saga).

---
## 1. Thiết kế Master-Sub Order (Tách Kiện Hàng)

Để giải quyết bài toán định tuyến thông minh, tối ưu chi phí vận chuyển và thời gian giao hàng từ nhiều điểm kho khác nhau, kiến trúc Đơn hàng được chia làm 2 cấp: Bảng `orders` (Master) và `order_parcels` (Sub-order/Kiện hàng).

### 1.1. `orders` (Master Order)
Lưu trữ thông tin tổng quát của giao dịch để khách hàng thanh toán 1 lần duy nhất.
*   `id`: BIGSERIAL PRIMARY KEY
*   `user_id`: BIGINT (Người mua, dùng để Sharding/Partitioning sau này)
*   `total_amount`: DECIMAL (Tổng tiền hàng)
*   `total_shipping_fee`: DECIMAL (Tổng phí ship cộng dồn từ các kiện)
*   `total_discount`: DECIMAL (Tổng giảm giá từ Promotion)
*   `final_amount`: DECIMAL (Tổng tiền khách phải trả)
*   `payment_method`: VARCHAR(50) (VNPAY, MOMO, ZALOPAY) — **denormalized for read-model/UI only**
*   `payment_status`: VARCHAR(50) (PENDING, PAID, FAILED, REFUNDING, REFUNDED, REFUND_FAILED) — **denormalized for read-model/UI only**
*   `status`: VARCHAR(50) (CREATED, PENDING_PAYMENT, PAID, PROCESSING, COMPLETED, CANCELLED, RETURN_REQUESTED, REFUNDED)
*   `version`: BIGINT DEFAULT 1 (Optimistic Locking cho FSM)
*   `created_at`, `updated_at`: TIMESTAMPTZ

> **Nguồn sự thật (Source of Truth):** Trạng thái và phương thức thanh toán thuộc quyền sở hữu của Payment Service. Hai cột `payment_method`, `payment_status` tại Order chỉ là bản sao phục vụ query nhanh. Cần có reconciliation job định kỳ để phát hiện và sửa drift dữ liệu giữa Order và Payment.

### 1.2. `order_parcels` (Sub-order / Kiện hàng)
Mỗi Order có thể tách thành 1 hoặc nhiều Parcel dựa trên Tọa độ Kho hàng và quyết định của khách hàng lúc Checkout (Smart Routing).
*   `id`: BIGSERIAL PRIMARY KEY
*   `order_id`: BIGINT (Foreign Key -> `orders.id`)
*   `seller_id`: BIGINT (Chủ shop của kiện hàng, phục vụ phân quyền Seller APIs)
*   `warehouse_id`: BIGINT (Kho xuất hàng)
*   `shipping_fee`: DECIMAL (Phí vận chuyển riêng của kiện này)
*   `tracking_number`: VARCHAR(100) (Mã vận đơn của đối tác - GHN/NinjaVan)
*   `courier_code`: VARCHAR(50) (Đơn vị vận chuyển)
*   `status`: VARCHAR(50) (PREPARING, PACKAGED, HANDED_OVER, IN_TRANSIT, DELIVERY_FAILED, DELIVERED, RETURNED)
*   `version`: BIGINT DEFAULT 1 (Optimistic Locking cho FSM)
*   `estimated_delivery_date`: DATE (Ngày dự kiến giao hàng)
*   `shipping_address_snapshot`: JSONB NOT NULL — Snapshot địa chỉ giao hàng tại thời điểm tạo đơn từ Checkout session. Format: `{"name": "...", "phone": "...", "address": "...", "province_code": "...", "district_code": "...", "ward_code": "..."}`

> **Lý do snapshot:** Tách biệt hoàn toàn với User Address Service. Khi user thay đổi/xóa địa chỉ sau khi đặt hàng, Shipping Service và CSKH vẫn có thể tra cứu chính xác địa chỉ giao hàng gốc cho từng kiện.
>
> **Source of Truth:** Lấy từ `checkout:session:{user_id}.shipping_address` tại thời điểm Order Service nhận Checkout Submit payload.

### 1.3. `order_items` (Order Snapshot / Chi tiết Sản phẩm)
Lưu snapshot tĩnh của thông tin sản phẩm tại thời điểm mua, không phụ thuộc vào Product Service trong tương lai. Map với từng Parcel cụ thể.
*   `id`: BIGSERIAL PRIMARY KEY
*   `parcel_id`: BIGINT (Foreign Key -> `order_parcels.id`)
*   `sku_id`: BIGINT
*   `product_id`: BIGINT
*   `quantity`: INT
*   `unit_price`: DECIMAL (Đơn giá lúc mua)
*   `snapshot_name`: VARCHAR(255) (Tên sản phẩm lúc mua)
*   `snapshot_image_url`: VARCHAR(255) (Hình ảnh lúc mua)
*   `snapshot_attributes`: JSONB (Ví dụ: `{"color": "Red", "size": "XL"}`)

### 1.4. `order_promotions` (Chi tiết Áp dụng Khuyến mãi)
Lưu dấu vết các voucher/deal đã dùng cho đơn hàng này để đối soát hoặc hỗ trợ hoàn voucher khi hủy đơn.
*   `id`: BIGSERIAL PRIMARY KEY
*   `order_id`: BIGINT
*   `promotion_id`: BIGINT
*   `promotion_type`: VARCHAR(50) (CART_DISCOUNT, SHIPPING_DISCOUNT)
*   `discount_amount`: DECIMAL (Số tiền đã giảm)
*   `platform_funding`: DECIMAL (Phần giảm giá do Platform tài trợ)
*   `seller_funding`: DECIMAL (Phần giảm giá do Seller tài trợ)

---
## 2. Quản lý trạng thái phức tạp với State Machine (FSM)

Để tránh tình trạng cập nhật trạng thái chồng chéo và Spaghetti code, Order Service sử dụng mô hình **Finite State Machine (FSM)** kết hợp **Optimistic Locking**:

*   **Định nghĩa Trạng thái (States):**
    *   **Master Order:** `CREATED` -> `PENDING_PAYMENT` -> `PAID` -> `PROCESSING` -> `COMPLETED` (hoặc rẽ nhánh sang `CANCELLED`, `RETURN_REQUESTED`, `REFUNDED`).
    *   **Parcel:** `PREPARING` -> `PACKAGED` -> `HANDED_OVER` -> `IN_TRANSIT` -> `DELIVERED` -> `RETURNED` (nhánh lỗi: `IN_TRANSIT` -> `DELIVERY_FAILED` -> `IN_TRANSIT|RETURNED`).
*   **Optimistic Locking (`version`):**
    Mọi lệnh Update trạng thái bắt buộc kiểm tra `version`. Ví dụ:
    `UPDATE orders SET status = 'PAID', version = version + 1 WHERE id = 123 AND version = X;`
    Nếu request đến sau mang version cũ hơn (do request trước đã đổi version), lệnh update sẽ thất bại, tránh việc FSM bị ngắt luồng sai (Ví dụ: Order đã `PAID` không được phép nhảy về `CANCELLED` vì quá hạn thanh toán chậm).

---
## 3. Infrastructure & Distributed Saga

Order Service là Orchestrator, kết nối với Payment, Inventory, và Promotion. Để đảm bảo tính nhất quán cuối (Eventual Consistency) và hỗ trợ giao dịch phân tán (Saga), bên cạnh Temporal Workflow, chúng ta cần các bảng phục vụ Event-Driven Architecture nội bộ.

### 2.1. `outbox_events` (Transactional Outbox)
Đảm bảo Event được gửi đi một cách tin cậy sau khi CSDL thay đổi (At-least-once delivery). Schema đồng bộ chuẩn với [Payment](../payment/payment_db_design.md), [Shipping](../shipping/shipping_db_design.md), và [Promotion](../promotion/promotion_db_design.md) outbox.
*   `id`: UUID PRIMARY KEY
*   `aggregate_type`: VARCHAR(100) (VD: "Order", "OrderParcel")
*   `aggregate_id`: BIGINT
*   `event_type`: VARCHAR(100) (VD: "ORDER_CREATED", "ORDER_PAID", "ORDER_CANCELLED")
*   `payload`: JSONB (Nội dung event)
*   `status`: VARCHAR(20) NOT NULL DEFAULT 'PENDING' — Lifecycle: `PENDING` → `PUBLISHED` (thành công) | `PENDING` → `FAILED` (Kafka lỗi, sẽ retry) | `FAILED` → `DEAD` (quá max_retries)
*   `retry_count`: INT NOT NULL DEFAULT 0 — Số lần đã retry publish lên Kafka
*   `max_retries`: INT NOT NULL DEFAULT 5 — Giới hạn retry trước khi chuyển `DEAD`
*   `published_at`: TIMESTAMPTZ — Thời điểm publish thành công lên Kafka
*   `next_retry_at`: TIMESTAMPTZ — Exponential backoff schedule cho lần retry tiếp theo
*   `created_at`: TIMESTAMPTZ

**Indexes:**
```sql
-- Hỗ trợ Outbox Polling Worker quét nhanh events cần xử lý
CREATE INDEX idx_outbox_pending ON outbox_events (status, next_retry_at)
    WHERE status IN ('PENDING', 'FAILED');
```

> **Tại sao cần 5 fields bổ sung?** [Outbox Polling Worker](order_workers.md#1-outbox-polling-worker-producer) tham chiếu `status = 'PENDING'`, `PUBLISHED`, `FAILED`, `DEAD` và `retry_count < max_retries` trong SQL query. Thiếu các fields này, Worker không thể retry khi Kafka fail → events mất vĩnh viễn.

### 2.2. `idempotency_keys` (Chống lặp Request)
Chặn các lượt submit trung lặp (VD: user ấn thanh toán 2 lần do lag, hoặc webhook payment gửi lại nhiều lần).
*   `key`: VARCHAR(255) PRIMARY KEY (Có thể là Checkout-Token hoặc Kafka Message ID)
*   `action`: VARCHAR(100) (VD: "CREATE_ORDER", "PAYMENT_WEBHOOK")
*   `response_code`: INT (Status code đã trả về trước đó)
*   `response_body`: JSONB (Nội dung API trả về trước đó)
*   `locked_at`: TIMESTAMPTZ
*   `expires_at`: TIMESTAMPTZ

### 2.3. `order_status_history` (Audit Trail FSM)
Ghi log mọi transition trạng thái của Master Order và Parcel để phục vụ điều tra sự cố, compliance và đối soát.
*   `id`: BIGSERIAL PRIMARY KEY
*   `order_id`: BIGINT (Index)
*   `parcel_id`: BIGINT NULL (Index, nullable khi transition ở cấp Master Order)
*   `old_status`: VARCHAR(50)
*   `new_status`: VARCHAR(50)
*   `changed_by`: VARCHAR(100) (user_id/admin_id/system/temporal_workflow_id)
*   `changed_by_type`: VARCHAR(20) (`USER`, `ADMIN`, `SYSTEM`, `TEMPORAL`)
*   `reason`: VARCHAR(255) NULL
*   `metadata`: JSONB NULL (provider response, signal payload, extra context)
*   `created_at`: TIMESTAMPTZ

---
## 4. Kiến trúc Mở rộng (Scalability & Performance)

Để đảm bảo hệ thống Order Service có thể xử lý lượng tải khổng lồ (Read-Heavy) và lưu trữ lâu dài mà không suy giảm hiệu năng RDBMS, hai kỹ thuật chuyên sâu được áp dụng:

### 4.1. Tìm kiếm và Lọc với CQRS & Elasticsearch
Mọi API phục vụ việc tìm kiếm văn bản (Full-text) hoặc lọc đa chiều (Multiple filters) không được thi hành trên PostgreSQL. Dữ liệu Đơn hàng được "Phi chuẩn hóa" thành Document phẳng và đồng bộ liên tục sang Elasticsearch.
📖 *Chi tiết kiến trúc:* Xem file `docs/order/cqrs_elasticsearch_design.md`

### 4.2. Lưu trữ Dữ liệu Lạnh (Cold Data Archiving)
Bảng `orders` được giữ ở mức tinh gọn (Lean) nhờ cơ chế Job chạy nền. Đơn hàng đạt trạng thái `COMPLETED/CANCELLED` và cũ hơn 6-12 tháng sẽ được tự động đóng gói (Archive) chuyển qua Cold Storage (ví dụ S3 + Tiered Read API) và xóa hẳn khỏi Database Bán Hàng chính.
📖 *Chi tiết quy trình:* Xem file `docs/order/cold_data_archiving_design.md`
