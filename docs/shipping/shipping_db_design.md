# Shipping Service - Database & Architecture Design

**Liên kết nhanh (Related Documents):**
*   [State Machine & Provider Mapping](shipping_state_machine.md)
*   [Workflows (API & Data Flow)](shipping_workflow.md)
*   [Background Workers & Jobs](shipping_workers.md)
*   [Events Structure & Kafka Topics](shipping_events.md)

---

Tài liệu này mô tả thiết kế cơ sở dữ liệu (PostgreSQL) và kiến trúc cho Shipping Service. Service này chịu trách nhiệm quản lý đối tác vận chuyển (3PL), lưu trữ bảng giá (Rate Card) để tính toán in-house siêu tốc cho Checkout/Inventory, theo dõi trạng thái vận đơn qua Webhook, và tích hợp với Saga Orchestration (Temporal).

---
## 1. Rate Card & Routing Engine (Tính phí in-house)
Lưu trữ thông tin đối tác và bảng giá để phục vụ Batch Request từ Inventory/Checkout mà không cần gọi real-time sang API của các bên giao hàng. Dữ liệu này sẽ được load lên Redis/Caffeine Cache.

### 1.1. `shipping_providers` (Đối tác Vận chuyển)
Quản lý danh sách các đối tác (GHN, NinjaVan, J&T, Viettel Post...).
*   `id`: INT PRIMARY KEY
*   `code`: VARCHAR(50) UNIQUE (VD: 'GHN', 'NINJAVAN')
*   `name`: VARCHAR(100)
*   `is_active`: BOOLEAN (Bật/tắt nhà vận chuyển)
*   `priority`: INT (Mức độ ưu tiên mặc định)
*   `api_config`: JSONB (Lưu config API Key, Secret - nên mã hóa)

### 1.2. `rate_cards` (Bảng giá vận chuyển)
Chứa bảng giá nội bộ. Có thể dùng cấu trúc Geo-hashing, Region-based, hoặc Tỉnh/Thành phổ biến ở VN.
*   `id`: BIGSERIAL PRIMARY KEY
*   `provider_id`: INT (Foreign Key -> `shipping_providers.id`)
*   `from_province_code`: VARCHAR(20) (Mã tỉnh gửi)
*   `from_district_code`: VARCHAR(20) NULL (Tùy chọn: mã quận gửi)
*   `to_province_code`: VARCHAR(20) (Mã tỉnh nhận)
*   `to_district_code`: VARCHAR(20) NULL (Tùy chọn: mã quận/huyện nhận)
*   `to_ward_code`: VARCHAR(20) NULL (Tùy chọn: mã phường/xã)
*   **Rule Fallback (Cây kế thừa):** Hệ thống sẽ ưu tiên query chi tiết nhất (Tỉnh + Huyện + Xã). Nếu không có, query lùi lên (Tỉnh + Huyện), rồi cuối cùng là (Tỉnh). Các vùng sâu vùng xa có thể cấu hình giá đặc biệt dựa trên `district_code` hoặc `ward_code`.
*   `base_weight`: INT (Trọng lượng cơ sở - gram, VD: 2000g)
*   `base_fee`: DECIMAL (Phí cơ bản, VD: 30000)
*   `step_weight`: INT (Khối lượng mỗi bước nhảy - gram, VD: 500g)
*   `step_fee`: DECIMAL (Phí cộng thêm mỗi bước nhảy, VD: 5000)
*   `estimated_days`: INT (Số ngày giao dự kiến)

### 1.3. `shipping_rules` (Quy tắc chọn vận chuyển)
Rules Engine để quyết định chọn Courier nào cho tối ưu (Smart Courier Allocation).
*   `id`: SERIAL PRIMARY KEY
*   `rule_name`: VARCHAR(100)
*   `conditions_rule`: **JSONB** (Cây Abstract Syntax Tree - AST đánh giá điều kiện tương tự Promotion Service. Bắt các biến: `order.total_amount`, `order.weight`, `user.tier`, `shipping.distance`, `shipping.region`).
*   `action_provider_id`: INT (Chọn nhà cung cấp nào nếu thỏa mãn)
*   `priority`: INT (Ưu tiên rule nào trước)
*   `is_active`: BOOLEAN

---
## 2. Quản lý Vận Đơn (Shipment Core)
Các bảng này phục vụ cho việc quản lý trạng thái kiện hàng khi Order Service bắt đầu quá trình giao hàng (thông qua Temporal Saga Orchestrator).

### 2.1. `shipments` (Kiện hàng / Vận đơn)
Tương ứng 1-1 với `order_parcels` bên Order Service. Quản lý toàn bộ lifecycle của 1 lần gửi hàng.
*   `id`: BIGSERIAL PRIMARY KEY
*   `parcel_id`: BIGINT UNIQUE (ID của kiện hàng từ Order Service)
*   `order_id`: BIGINT (Để đối soát)
*   `provider_id`: INT (Foreign Key -> `shipping_providers.id`)
*   `tracking_number`: VARCHAR(100) UNIQUE (Mã vận đơn thật từ 3PL trả về)
*   `sender_info`: JSONB (Tên, SĐT, Địa chỉ Kho)
*   `receiver_info`: JSONB (Tên, SĐT, Địa chỉ Khách)
*   `package_info`: JSONB (Trọng lượng, Kích thước)
*   `standardized_status`: VARCHAR(50) (Trạng thái chuẩn hóa)
    *   *enum: PENDING, READY_TO_SHIP, IN_TRANSIT, DELIVERED, RETURNED, FAILED, CANCELLED*
*   `provider_status`: VARCHAR(50) (Trạng thái gốc trả về từ 3PL)
*   `shipping_fee`: DECIMAL (Phí ship thực tế 3PL tính)
*   `cod_amount`: DECIMAL (Tiền thu hộ)
*   `created_at`, `updated_at`: TIMESTAMPTZ

### 2.2. Chuyển `shipment_events` sang ScyllaDB (Lịch sử Tracking)
Bảng Tracking History là tác vụ write-heavy (do bão Webhook từ 3PL) và read-heavy (khách F5 làm mới liên tục). Không nên lưu trong PostgreSQL vì nó sẽ phình to gấp 10-20 lần bảng `shipments`, làm chậm database lõi. 
Thay vào đó, thiết kế **ScyllaDB / Cassandra (Wide-Column NoSQL)**:
*   **Keyspace**: `shipping_keyspace`
*   **Table**: `shipment_events`
*   **Partition Key (`shipment_id`)**: Đảm bảo tất cả hành trình của cùng 1 kiện hàng nằm chung 1 Node, query 1 phát lấy ra cả mảng log.
*   **Clustering Key (`occurred_at` DESC)**: Lịch sử tự động được sắp xếp giảm dần.
*   *Columns:* `shipment_id` (BigInt), `occurred_at` (Timestamp), `standardized_status` (Text), `provider_status` (Text), `description` (Text), `location` (Text).

---
## 3. Hạ tầng cho Saga & Webhook 

### 3.1. `idempotency_keys` (Chống lặp Webhook)
Chống bão Webhook (Webhook Storm) từ 3P, đảm bảo 1 sự kiện update status chỉ được xử lý 1 lần.
*   `key`: VARCHAR(255) PRIMARY KEY (VD: `hash(tracking_number + provider_status_code + occurred_at)`)
*   `action`: VARCHAR(100) (VD: "UPDATE_SHIPMENT_STATUS")
*   `locked_at`: TIMESTAMPTZ
*   `expires_at`: TIMESTAMPTZ (Có thể TTL sau 3-7 ngày)

### 3.2. `outbox_events` (Transactional Outbox)
Sử dụng nếu thay đổi trạng thái kiện hàng cần báo cho Temporal (Orchestrator) qua Kafka. Xem [Outbox Polling Worker](shipping_workers.md#1-outbox-polling-worker-producer).
*   `id`: UUID PRIMARY KEY
*   `aggregate_type`: VARCHAR(100) (VD: "Shipment")
*   `aggregate_id`: BIGINT (`shipments.id`)
*   `event_type`: VARCHAR(100) (VD: "SHIPMENT_CREATED", "SHIPMENT_STATUS_UPDATED", "SHIPMENT_DELIVERED")
*   `payload`: JSONB
*   `status`: VARCHAR(20) DEFAULT 'PENDING' — `PENDING` / `PUBLISHED` / `FAILED` / `DEAD`
*   `retry_count`: INT DEFAULT 0 (Số lần retry publish lên Kafka)
*   `max_retries`: INT DEFAULT 5
*   `published_at`: TIMESTAMPTZ (Thời điểm publish thành công)
*   `next_retry_at`: TIMESTAMPTZ
*   `created_at`: TIMESTAMPTZ

---
## 4. Kiến trúc & Workflow Kỹ thuật
*   **Performance Pricing (Giá cước):** Endpoint `POST /v1/shipping/calculate-batch` sử dụng memory cache (Rate Card) giúp độ trễ <20ms, an toàn khi load cao.
*   **Adapter Pattern:** Code Java/Go sẽ có `ShippingProviderAdapter` interface (hàm `CreateOrder`, `CancelOrder`, `GetTracking`). Việc xử lý API từng bên nằm gọn trong class implementation. Trạng thái của từng bên được map vào chuẩn `standardized_status`.
*   **Bão Webhook:** Khi nhận request từ 3PL, lưu thô vào Kafka Topic `t.shipping.webhooks.raw` và xử lý bất đồng bộ kết hợp bảng `idempotency_keys`.
*   **Saga Orchestration (Temporal):** Shipping Service chỉ chạy Worker lắng nghe Activity Task từ Temporal (Ví dụ: task `CreateShippingLabel`). Nếu tạo lỗi, quăng exception báo cáo cho Temporal để nó điều phối rollback các service khác.
