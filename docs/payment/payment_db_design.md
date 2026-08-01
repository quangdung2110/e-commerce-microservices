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

Payment Service cần hoạt động độc lập với Order Service. Order Service chỉ biết "Đơn hàng này cần thanh toán X đồng", còn Payment Service sẽ chịu trách nhiệm gọi Stripe API (PaymentIntent + Webhook), đồng bộ trạng thái giao dịch với Stripe.

### 1.1. `payment_transactions` (Giao dịch Thanh toán)
Lưu trữ thông tin chi tiết mỗi lần người dùng cố gắng thanh toán (1 Order có thể có nhiều payment_transactions nếu họ thanh toán lỗi và thử lại).
*   `id`: `VARCHAR(50)` PRIMARY KEY (Chuỗi gen tự động, đóng vai trò là `txn_ref` gửi lên Stripe. VD: `TNX12345ABC`).
*   `order_id`: `BIGINT` (Foreign Key logic về Order Service).
*   `user_id`: `UUID` (Canonical chuẩn hóa toàn hệ thống; dùng để filter, sharding sau này). Xem [Service Contract Matrix §0.1](../_contracts/CONTRACT_MATRIX.md#01-id-convention).
*   `amount`: `DECIMAL` (Số tiền thực tế phải thanh toán).
*   `currency`: `VARCHAR(10)` (Cố định = `VND`; Stripe hỗ trợ VND ở VN region).
*   `provider`: `VARCHAR(50)` NOT NULL DEFAULT `'STRIPE'` với `CHECK (provider = 'STRIPE')` — hệ thống chỉ dùng 1 payment provider duy nhất (Stripe).
*   `provider_transaction_id`: `VARCHAR(100)` (Mã giao dịch sinh ra từ Stripe = `pi_xxx` PaymentIntent ID; dùng để đối soát với Stripe Dashboard).
*   `status`: `VARCHAR(50)` (`PENDING`, `SUCCESS`, `FAILED`, `CANCELED`, `REFUNDING`, `REFUNDED`).
*   `error_code`, `error_message`: `VARCHAR(255)` (Lưu trữ lỗi nếu thanh toán rớt để phân tích).
*   `expires_at`: `TIMESTAMPTZ` (Thời gian hết hạn của phiên thanh toán, ví dụ 15 phút, dùng cho worker dọn dẹp quét lại).
*   `created_at`, `updated_at`: `TIMESTAMPTZ`

### 1.2. `refund_transactions` (Giao dịch Hoàn tiền)
Hoàn tiền (Refund) là một quy trình tách biệt, có thể hoàn toàn phần hoặc một phần.
*   `id`: `BIGSERIAL PRIMARY KEY` hoặc `UUID`.
*   `payment_transaction_id`: `VARCHAR(50)` (Foreign Key logic đến giao dịch thanh toán gốc).
*   `order_id`: `BIGINT`
*   `refund_amount`: `DECIMAL`
*   `reason`: `VARCHAR(255)` (Lý do hoàn: `CUSTOMER_RETURN`, `ADMIN_FORCE_REFUND`, `OUT_OF_STOCK`, v.v.)
*   `provider_refund_id`: `VARCHAR(100)` (Mã refund từ Stripe = `re_xxx` Refund ID; dùng để đối soát với Stripe Dashboard).
*   `status`: `VARCHAR(50)` (`PENDING`, `PROCESSING`, `SUCCESS`, `FAILED`).
*   `idempotency_key`: `VARCHAR(255) UNIQUE` (Chống duplicate request từ Order Service).
*   `created_at`, `updated_at`: `TIMESTAMPTZ`

### 1.3. `webhook_logs` (Nhật ký Callbacks/Webhooks)
Cực kỳ quan trọng để debug khi đối tác báo đã trừ tiền nhưng app báo lỗi.
*   `id`: `BIGSERIAL PRIMARY KEY`.
*   `provider`: `VARCHAR(50)` NOT NULL DEFAULT `'STRIPE'` (cố định = Stripe, hệ thống single-provider).
*   `provider_transaction_id`: `VARCHAR(100)` (Stripe PaymentIntent ID = `pi_xxx`).
*   `stripe_event_id`: `VARCHAR(120)` (Stripe Event ID = `evt_xxx`; dùng để dedup khi Stripe retry cùng event).
*   `stripe_signature`: `TEXT` (Toàn bộ `Stripe-Signature` header nhận được, lưu để audit khi cần).
*   `event_type`: `VARCHAR(100)` (Stripe event type, VD: `payment_intent.succeeded`, `charge.refunded`).
*   `payload`: `JSONB` (Lưu nguyên vẹn raw body của Stripe gửi sang để phục hồi dữ liệu khi cần).
*   `status`: `VARCHAR(50)` (`UNPROCESSED`, `PROCESSED`, `FAILED_SIGNATURE`, `IGNORED`).
*   `idempotency_key`: `VARCHAR(255)` (Kết hợp `stripe_event_id` + `event_type` để chặn xử lý đúp).
*   `created_at`: `TIMESTAMPTZ`
*   **Constraint:** `UNIQUE(provider, provider_transaction_id, event_type)` để đảm bảm DB level từ chối duplicate webhook.

### 1.4. `payment_status_history` (Audit Trail — FSM Transition Log)
Ghi nhận mọi chuyển đổi trạng thái (FSM Transition) của `payment_transactions` và `refund_transactions` phục vụ debug, compliance, và đối soát. Xem [State Machine - Audit Trail](payment_state_machine.md#34-audit-trail-payment_status_history).
*   `id`: `BIGSERIAL PRIMARY KEY`.
*   `transaction_id`: `VARCHAR(50)` (FK logic → `payment_transactions.id` hoặc `refund_transactions.id`).
*   `transaction_type`: `VARCHAR(20)` (VD: `PAYMENT`, `REFUND`).
*   `old_status`: `VARCHAR(50)` (Trạng thái trước chuyển đổi).
*   `new_status`: `VARCHAR(50)` (Trạng thái sau chuyển đổi).
*   `triggered_by`: `VARCHAR(50)` (VD: `WEBHOOK`, `CRONJOB`, `API`, `TEMPORAL`).
*   `provider_response`: `JSONB` (Raw response từ Stripe API nếu có — dùng khi debug dispute).
*   `created_at`: `TIMESTAMPTZ`

### 1.5. Bảng Kỹ Thuật (Infrastructure)

*   **`outbox_events`**: Transactional Outbox đảm bảo gửi Kafka events an toàn sau khi DB commit. Tuân theo canonical [OUTBOX_PATTERNS.md §2.1 CDC Pattern](../_contracts/OUTBOX_PATTERNS.md#21-cdc-pattern-debezium-kafka-connect). **Cấm cột polling-style** — retry/DLQ/backoff chuyển sang Kafka Connect runtime.
    *   `event_id`: `UUID PRIMARY KEY`
    *   `schema_version`: `INT NOT NULL DEFAULT 1`
    *   `aggregate_type`: `VARCHAR(100)` (Cố định: `Payment` cho PAYMENT_*, vẫn dùng `Payment` cho REFUND_* nhưng `aggregate_id` = `refund_transaction_id`)
    *   `aggregate_id`: `VARCHAR(50)` (`payment_transactions.id` cho PAYMENT_*, `refund_transactions.id` cho REFUND_*)
    *   `event_type`: `VARCHAR(100)` (VD: `PAYMENT_SUCCESS`, `REFUND_INITIATED`)
    *   `partition_key`: `VARCHAR(255) NOT NULL` — Canonical: `order_id` để group mọi event của cùng 1 order (xem [CONTRACT_MATRIX §1](../_contracts/CONTRACT_MATRIX.md#1-kafka-topic-map-ssot))
    *   `payload`: `JSONB` (Nội dung event theo canonical envelope — xem [CONTRACT_MATRIX §0.2](../_contracts/CONTRACT_MATRIX.md#02-kafka-envelope-cloudevents-wrapper))
    *   `created_at`: `TIMESTAMPTZ NOT NULL`

*   **`payment_idempotency`**: Chặn duplicate requests từ Frontend (double-click) hoặc Kafka replay.
    *   `key`: `VARCHAR(255)`
    *   `action`: `VARCHAR(100)`
    *   `response_code`: `INT`
    *   `response_body`: `JSONB`
    *   `created_at`, `expires_at`: `TIMESTAMPTZ`
    *   **Constraint:** `PRIMARY KEY (key, action)` để tránh client dùng lại một idempotency_key cho nhiều hành động khác nhau.

---

---
## 2. Các kỹ thuật chuyên sâu (Advanced Techniques)

Payment Service cần đảm bảo các kĩ thuật độ tin cậy tuyệt đối (Reliability):

1.  **Idempotency Tuyệt Đối & Race Condition Control:**
    *   Sử dụng Distributed Lock (Redis Redisson) khóa theo `payment_transaction_id` chặn race condition giữa User Frontend và Stripe Webhook.
    *   Sử dụng Optimistic Locking (Cột `version`) trên các bảng transaction để luôn luôn đảm bảo tính nhất quán của State Machine.
2.  **Webhook Signature Verification (Bảo mật Anti-Spoofing — Stripe chuẩn):**
    *   Verify `Stripe-Signature` header theo cơ chế `t=<timestamp>,v1=<signature>` của Stripe. Tính lại HMAC-SHA256 với `signed_payload = t + "." + raw_body` dùng secret key `whsec_...` (lưu trong Vault/Secret Manager).
    *   Reject ngay webhook nếu `|now() - t| > 300 giây` (chống replay attack).
    *   Nếu signature không hợp lệ → ghi `webhook_logs.status = FAILED_SIGNATURE` và KHÔNG parse business payload.
    *   Stripe có retry tự động với exponential backoff (3-5 lần); hệ thống phải chịu được retry duplicate (xử lý qua idempotency).
3.  **Cronjob Sync & Fallback (Xử lý Drop-off Giao dịch):**
    *   Worker quét các giao dịch quá hạn `expires_at` (nhưng chưa DONE) để chủ động Query sang Stripe API (`PaymentIntent.retrieve`) nhằm cập nhật trạng thái cuối cùng, tránh trường hợp rớt mạng làm treo giao dịch của user.
4.  **Reconciliation (Đối soát Tự động):**
    *   Cronjob định kỳ (hourly) dùng Stripe API `BalanceTransaction.list` / `PaymentIntent.list` để đối soát amount/status với DB nội bộ. Phát hiện sai lệch → correction transition + outbox event.
5.  **Stripe SDK Direct Call (Single-Provider, Phase 18):**
    *   Payment Service gọi trực tiếp `com.stripe:stripe-java` SDK. KHÔNG còn Provider Adapter/Factory pattern.
    *   Khởi tạo 1 `StripeClient` singleton với API key lấy từ secret manager (Secret Manager / Vault).
    *   Toàn bộ call site (`PaymentIntent.create`, `Refund.create`, `PaymentIntent.retrieve`, `Webhook.constructEvent`) dùng chung instance này.
    *   Khuyến nghị: encapsulate Stripe-specific logic trong package `com.payment.stripe` (PaymentIntentClient, RefundClient, WebhookVerifier) để dễ refactor nếu sau này đổi provider.

---
## 3. Data Retention & Long-term Storage Strategy

Bảng `payment_transactions` sẽ grow rất nhanh (mỗi lần user thử thanh toán = 1 record). Để giữ hiệu năng query cao cho Expired Transaction Scanner và các operations hàng ngày, áp dụng chiến lược partitioning + tiered storage theo nhu cầu retention riêng của Payment Service.

> **Lưu ý Phase 16:** Order Service đã bỏ cơ chế Cold Data Archiving (xem [CHANGELOG Phase 16](../_contracts/CHANGELOG.md)). Payment Service giữ chiến lược riêng dưới đây do đặc thù retention lâu dài (>6 tháng) cho transaction log phục vụ CSKH/audit. Quyết định này **không** phụ thuộc pattern của Order.

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
