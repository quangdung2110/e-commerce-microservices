# Payment Service - Database & Architecture Design

**Liên kết nhanh (Related Documents):**
*   [State Machine (Payment + Refund FSM)](payment_state_machine.md)
*   [Workflows (API & Data Flow)](payment_workflow.md)
*   [Background Workers & Jobs](payment_workers.md)
*   [Events Structure & Kafka Topics](payment_events.md)
*   [Refund Boundary Decision (ADR)](refund_boundary_decision.md)

---

Tài liệu này mô tả thiết kế cơ sở dữ liệu (PostgreSQL) cho Payment Service và kiến trúc xử lý thanh toán, hoàn tiền (Refund), đảm bảo tính an toàn, toàn vẹn dữ liệu và khả năng mở rộng.

---
## 1. Thiết kế Cơ sở dữ liệu (Database Schema)

Payment Service cần hoạt động độc lập với Order Service. Order Service chỉ biết "Đơn hàng này cần thanh toán X đồng", còn Payment Service sẽ chịu trách nhiệm gọi cổng thanh toán, đồng bộ trạng thái giao dịch với đối tác.

### 1.1. `payment_transactions` (Giao dịch Thanh toán)
Lưu trữ thông tin chi tiết mỗi lần người dùng cố gắng thanh toán (1 Order có thể có nhiều payment_transactions nếu họ thanh toán lỗi và thử lại).
*   `id`: `VARCHAR(50)` PRIMARY KEY (Chuỗi gen tự động, đóng vai trò là `txn_ref` gửi lên cổng thanh toán. VD: `TNX12345ABC`).
*   `order_id`: `BIGINT` (Foreign Key logic về Order Service).
*   `user_id`: `BIGINT` (Dùng để filter, sharding sau này).
*   `amount`: `DECIMAL` (Số tiền thực tế phải thanh toán).
*   `currency`: `VARCHAR(10)` (VD: `VND`, `USD`).
*   `provider`: `VARCHAR(50)` (VD: `VNPAY`, `MOMO`, `STRIPE`, `ZALOPAY`).
*   `provider_transaction_id`: `VARCHAR(100)` (Mã giao dịch sinh ra từ phía đối tác để tiện đối soát/Reconciliation).
*   `status`: `VARCHAR(50)` (`PENDING`, `SUCCESS`, `FAILED`, `CANCELED`, `REFUNDING`, `REFUNDED`).
*   `version`: `BIGINT` DEFAULT 1 (Optimistic Locking cho FSM, chống race-condition từ webhook).
*   `error_code`, `error_message`: `VARCHAR(255)` (Lưu trữ lỗi nếu thanh toán rớt để phân tích).
*   `expires_at`: `TIMESTAMPTZ` (Thời gian hết hạn của phiên thanh toán, ví dụ 15 phút, dùng cho worker dọn dẹp quét lại).
*   `created_at`, `updated_at`: `TIMESTAMPTZ`

### 1.2. `refund_transactions` (Giao dịch Hoàn tiền)
Hoàn tiền (Refund) là một quy trình tách biệt, có thể hoàn toàn phần hoặc một phần.
*   `id`: `BIGSERIAL PRIMARY KEY` hoặc `UUID`.
*   `payment_transaction_id`: `VARCHAR(50)` (Foreign Key logic đến giao dịch thanh toán gốc).
*   `order_id`: `BIGINT`
*   `refund_amount`: `DECIMAL`
*   `reason`: `VARCHAR(255)` (Lý do hoàn: User Cancel, Out of Stock, Return, v.v.)
*   `provider_refund_id`: `VARCHAR(100)` (Mã đối soát hoàn tiền từ đối tác).
*   `status`: `VARCHAR(50)` (`PENDING`, `PROCESSING`, `SUCCESS`, `FAILED`).
*   `created_at`, `updated_at`: `TIMESTAMPTZ`

### 1.3. `webhook_logs` (Nhật ký Callbacks/Webhooks)
Cực kỳ quan trọng để debug khi đối tác báo đã trừ tiền nhưng app báo lỗi.
*   `id`: `BIGSERIAL PRIMARY KEY`.
*   `provider`: `VARCHAR(50)`
*   `payload`: `JSONB` (Lưu nguyên vẹn raw body của đối tác gửi sang để phục hồi dữ liệu khi cần).
*   `status`: `VARCHAR(50)` (`UNPROCESSED`, `PROCESSED`, `FAILED_SIGNATURE`, `IGNORED`).
*   `idempotency_key`: `VARCHAR(255)` (Kết hợp `provider_transaction_id` + `status` của webhook để chặn xử lý đúp).
*   `created_at`: `TIMESTAMPTZ`

### 1.4. `payment_status_history` (Audit Trail — FSM Transition Log)
Ghi nhận mọi chuyển đổi trạng thái (FSM Transition) của `payment_transactions` và `refund_transactions` phục vụ debug, compliance, và đối soát. Xem [State Machine - Audit Trail](payment_state_machine.md#34-audit-trail-payment_status_history).
*   `id`: `BIGSERIAL PRIMARY KEY`.
*   `transaction_id`: `VARCHAR(50)` (FK logic → `payment_transactions.id` hoặc `refund_transactions.id`).
*   `transaction_type`: `VARCHAR(20)` (VD: `PAYMENT`, `REFUND`).
*   `old_status`: `VARCHAR(50)` (Trạng thái trước chuyển đổi).
*   `new_status`: `VARCHAR(50)` (Trạng thái sau chuyển đổi).
*   `triggered_by`: `VARCHAR(50)` (VD: `WEBHOOK`, `CRONJOB`, `API`, `TEMPORAL`).
*   `provider_response`: `JSONB` (Raw response từ cổng thanh toán nếu có — dùng khi debug dispute).
*   `created_at`: `TIMESTAMPTZ`

### 1.5. Bảng Kỹ Thuật (Infrastructure)

*   **`outbox_events`**: Transactional Outbox đảm bảo gửi Kafka events an toàn sau khi DB commit. Xem [Outbox Polling Worker](payment_workers.md#1-outbox-polling-worker-producer).
    *   `id`: `UUID PRIMARY KEY`
    *   `aggregate_type`: `VARCHAR(100)` (VD: `PAYMENT`, `REFUND`)
    *   `aggregate_id`: `VARCHAR(50)` (`payment_transactions.id` hoặc `refund_transactions.id`)
    *   `event_type`: `VARCHAR(100)` (VD: `PAYMENT_SUCCESS`, `REFUND_INITIATED`)
    *   `payload`: `JSONB` (Nội dung event)
    *   `status`: `VARCHAR(20) DEFAULT 'PENDING'` — `PENDING` / `PUBLISHED` / `FAILED` / `DEAD`
    *   `retry_count`: `INT DEFAULT 0` (Số lần retry publish lên Kafka)
    *   `max_retries`: `INT DEFAULT 5` (Giới hạn retry trước khi chuyển `DEAD`)
    *   `published_at`: `TIMESTAMPTZ` (Thời điểm publish thành công)
    *   `next_retry_at`: `TIMESTAMPTZ` (Thời điểm retry tiếp theo — exponential backoff)
    *   `created_at`: `TIMESTAMPTZ`

*   **`payment_idempotency`**: Chặn duplicate requests từ Frontend (double-click) hoặc Kafka replay.
    *   `key`: `VARCHAR(255) PRIMARY KEY`
    *   `action`: `VARCHAR(100)`
    *   `response_code`: `INT`
    *   `response_body`: `JSONB`
    *   `created_at`, `expires_at`: `TIMESTAMPTZ`

---
## 2. Kiến trúc Xử lý Refund (Hoàn tiền)

Việc tách tiến trình Hoàn tiền (Refund) ra thành một Refund Service độc lập là cần thiết khi hệ thống lớn, nhưng để tối ưu chi phí bảo trì và tránh overhead (chi phí mạng gRPC/Kafka, Duplicate Data, Distributed Transaction) trong giai đoạn đầu, hệ thống áp dụng kiến trúc **2 Giai Đoạn (Phases)** tiến hóa phân quyền:

### Phase 1: Modular Monolith (Giữ trong Payment Service)
Trong giai đoạn đầu, tiến trình Hoàn tiền được thiết kế như một **Logical Boundary** (module/package) riêng biệt nằm bên trong Payment Service.

*   **Tổ chức Code:** Tạo package/module/folder riêng bên trong Payment Service, ví dụ `src/refund/` tách biệt với `src/payment/`.
*   **Tương tác:** Tương tác giữa `Payment` và `Refund` bên trong code thông qua Interface nội bộ thay vì gọi trực tiếp sang nhau.
*   **Lợi ích:** Dễ code, dễ deploy, tận dụng được Local Transaction (ACID DB lock) để xử lý concurrency, dữ liệu `Refund` gần với `Payment`. Khi logic phình to, có thể dễ dàng "bứng" folder `src/refund/` thành 1 service mới mà không tốn nhiều công refactor.
*   **Logic Hoạt Động:** Đa phần tập trung vào Auto-Refund khi Order Cancelled hoặc Payment lỗi timeout.

### Phase 2: Microservice Độc lập (Refund Service)
Khi hệ thống chạm ngưỡng Scale, mảng CSKH và Kế toán cần một giao diện CMS đồ sộ để quản lý khiếu nại (Dispute / Chargeback / Return & Refund) theo từng cấp độ (Luồng duyệt tiền phân cấp). Lúc này sẽ bóc tách `src/refund/` thành **Refund Service**.

*   **Kích hoạt:** `Order Service` phát kiện hàng bị trả về (Return) -> Bắn Kafka Event `OrderReturned`.
*   **Quy trình Xét duyệt (Workflow):** `Refund Service` bắt Event, tự động sinh ra một `RefundRequest (Status = PENDING_APPROVAL)`. Dashboard nội bộ cho phép Nhân viên Kho vào xác nhận "Hàng không hỏng hóc", Kế toán duyệt lệnh.
*   **Thực thi:** `Refund Service` mới gọi gRPC (hoặc Call API) sang `Payment Service` với thông điệp: *"Tiền này an toàn, hãy thực thi lệnh Refund với Bank/VNPAY đi"*.

### 2.1. Quyết định kiến trúc hiện tại (03/2026)

Theo dữ liệu vận hành hiện tại:

*   Tỷ lệ refund thấp (`< 2%` trên tổng giao dịch thanh toán).
*   Chưa có nhu cầu workflow duyệt refund nhiều cấp (Kho/CSKH/Kế toán/Compliance).
*   Chưa tách ownership team Refund khỏi team Payment.

=> **Quyết định hiện tại: giữ Refund trong Payment Service theo mô hình Modular Boundary (Phase 1).**

Lý do: Tách Refund Service quá sớm sẽ tăng overhead hệ phân tán (distributed lock, idempotency chéo service, reconciliation chéo service, contract drift), trong khi chưa tạo ra lợi ích vận hành tương xứng.

### 2.2. Thực tiễn tham chiếu từ hệ thống lớn

Tham chiếu từ Stripe, Adyen, PayPal, Square, Amazon Pay cho thấy các điểm chung sau:

1.  Refund luôn là **asynchronous lifecycle** (không coi API response ban đầu là kết quả cuối cùng).
2.  Luôn cần **idempotency key** cho API refund để chống duplicate request.
3.  **Webhook không đảm bảo thứ tự tuyệt đối** và có thể retry/duplicate; hệ thống phải tự dedup.
4.  Có thể phát sinh trạng thái **failed/reversed sau khi đã accepted/processing**.
5.  Luôn có lớp **reconciliation** để sửa lệch giữa DB nội bộ và PSP.

Thiết kế hiện tại của Payment Service đã bám sát các nguyên tắc này: Outbox, Idempotency, Webhook Signature Verification, Retry, Cronjob Sync.

### 2.3. Trigger tách sang Refund Service (Phase 2)

Chỉ chuyển sang Phase 2 khi đạt ít nhất 1 trong các điều kiện chính dưới đây trong 2 chu kỳ liên tiếp:

| Trigger | Ngưỡng gợi ý | Ý nghĩa |
|:---|:---|:---|
| Refund volume tăng cao | `refund_ratio > 5%` | Khối lượng nghiệp vụ refund đủ lớn để tách scale độc lập |
| Workflow phức tạp | `manual/dispute refund > 1%` và cần multi-step approval | Cần domain riêng cho dispute, chargeback, duyệt nhiều cấp |
| Tách ownership | Team Refund vận hành độc lập team Payment | Cần ranh giới deploy/incident/SLA rõ ràng |
| Áp lực vận hành | Backlog refund xử lý thủ công tăng theo xu hướng | Cần service riêng để giảm MTTR và tách runbook |

### 2.4. Bất biến kiến trúc cần giữ khi tách service

Khi bóc tách `src/refund/` thành Refund Service, các bất biến sau **không được thay đổi**:

1.  **Ordering key:** Event Refund vẫn partition theo `order_id`.
2.  **Idempotency:** Giữ dedup ở cả API layer và consumer layer.
3.  **Security:** Webhook signature verification bắt buộc trước mọi state transition.
4.  **Outbox contract:** Event chỉ được phát sau DB commit thành công.
5.  **Reconciliation:** Vẫn phải có job đối soát độc lập với provider.

---
## 3. Các kỹ thuật chuyên sâu (Advanced Techniques)

Payment Service cần đảm bảo các kĩ thuật độ tin cậy tuyệt đối (Reliability):

1.  **Idempotency Tuyệt Đối & Race Condition Control:**
    *   Sử dụng Distributed Lock (Redis Redisson) khóa theo `payment_transaction_id` chặn race condition giữa User Frontend và Webhook Bank.
    *   Sử dụng Optimistic Locking (Cột `version`) trên các bảng transaction để luôn luôn đảm bảo tính nhất quán của State Machine.
2.  **Webhook Signature Verification (Bảo mật Anti-Spoofing):**
    *   Xác minh HMAC-SHA256 hoặc RSA signature từ request header của tất cả mọi webhook từ đối tác trước khi parse body để chống Fake Payload tấn công vào DB.
3.  **Cronjob Sync & Fallback (Xử lý Drop-off Giao dịch):**
    *   Worker quét các giao dịch quá hạn `expires_at` (nhưng chưa DONE) để chủ động Query sang API của Đối tác nhằm cập nhật trạng thái cuối cùng, tránh trường hợp rớt mạng làm treo giao dịch của user.
4.  **Reconciliation (Đối soát Tự động):**
    *   Tự động tải file CSV/SFTP Settlement từ đối tác vào cuối ngày để join dữ liệu kiểm tra chênh lệch (Discrepancies).

---
## 4. Cold Data Archiving Strategy

Bảng `payment_transactions` sẽ grow rất nhanh (mỗi lần user thử thanh toán = 1 record). Để giữ hiệu năng query cao cho Expired Transaction Scanner và các operations hàng ngày, áp dụng chiến lược archiving tương tự [Order Service Cold Data Archiving](../order/cold_data_archiving_design.md).

### 4.1. Table Partitioning (Monthly)

```sql
-- Partition payment_transactions theo created_at
ALTER TABLE payment_transactions RENAME TO payment_transactions_old;

CREATE TABLE payment_transactions (
    -- ... (giữ nguyên schema Section 1.1) ...
) PARTITION BY RANGE (created_at);

-- Auto-create monthly partitions (sử dụng pg_partman extension)
-- Hoặc tạo thủ công:
CREATE TABLE payment_transactions_2026_01
    PARTITION OF payment_transactions
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');

CREATE TABLE payment_transactions_2026_02
    PARTITION OF payment_transactions
    FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');
-- ... repeat per month
```

**Lợi ích:**
- **Expired Transaction Scanner** chỉ query partition hiện tại → giảm scan range 10-50x so với table đầy đủ.
- **Archive dễ dàng:** Detach partition > 6 tháng → pg_dump → S3 → DROP.
- **Index hiệu quả hơn:** Mỗi partition có index riêng, nhỏ hơn index toàn cục.

> **Lưu ý:** `refund_transactions`, `webhook_logs`, `payment_status_history` cũng nên partition theo `created_at` khi volume cao. Ưu tiên `payment_transactions` trước vì là bảng lớn nhất.

### 4.2. Archival CronJob (Weekly)

**Loại hình:** CronJob Chủ nhật 3:00 AM.

**Chi tiết luồng hoạt động:**
1. Quét partitions > 6 tháng tuổi.
2. `pg_dump` partition → upload S3: `s3://payment-archive/YYYY/MM/payment_transactions_YYYY_MM.sql.gz`.
3. Verify S3 upload checksum (SHA-256 match).
4. Detach: `ALTER TABLE payment_transactions DETACH PARTITION payment_transactions_YYYY_MM`.
5. DROP partition (chỉ sau khi verify S3 integrity pass).
6. Log archive event cho audit trail.

### 4.3. Tiered Read API

Khi CSKH cần tra cứu giao dịch cũ > 6 tháng:
1. **API check DB trước** (partition hiện tại) → Nếu tìm thấy → Trả kết quả ngay.
2. **DB miss** → Query **S3/Athena** (dùng AWS Athena hoặc tương đương scan file Parquet/SQL trên S3) → Trả kết quả (chậm hơn ~2-5s nhưng chấp nhận được cho use case CSKH).
3. **Response format** giống nhau cho cả hot data (DB) và cold data (S3) — client không cần phân biệt.
