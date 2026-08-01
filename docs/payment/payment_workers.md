# Payment Service - Background Workers & Jobs

**Liên kết nhanh (Related Documents):**
* [Database Schema](payment_db_design.md)
* [State Machine](payment_state_machine.md)
* [Workflows (API & Data Flow)](payment_workflow.md)
* [Events Structure & Kafka Topics](payment_events.md)
* [Refund Boundary Decision (ADR)](refund_boundary_decision.md)

---

Tài liệu này mô tả các worker nền của Payment Service để đảm bảo reliability cho các luồng payment/refund bất đồng bộ.

## 1. Outbox CDC Connector (Producer)

**Mục tiêu:** Publish event từ `payment_outbox` (append-only) lên Kafka theo canonical **CDC Pattern** (xem [OUTBOX_PATTERNS.md §2.1](../_contracts/OUTBOX_PATTERNS.md#21-cdc-pattern-debezium-kafka-connect)). **Outbox Polling Worker cũ đã deprecated** — retry/DLQ/backoff chuyển sang Kafka Connect runtime.

### Luồng xử lý

1. Application INSERT row vào `payment_outbox` trong cùng transaction nghiệp vụ.
2. Debezium CDC đọc WAL, route `aggregate_type=Payment` → topic `t.payment.events` với `partition_key = order_id`.
3. Retry/DLQ/backoff: do Kafka Connect quản lý, không phải application.

> **Migration note:** Service Payment hiện đang trong quá trình migrate từ Polling Pattern (legacy) sang CDC. Xem chi tiết tại [OUTBOX_PATTERNS.md §4 Migration path](../_contracts/OUTBOX_PATTERNS.md#4-migration-path-for-payment).

## 2. Webhook Processor Worker (Kafka Consumer - Internal)

**Mục tiêu:** Xử lý webhook raw một cách bất đồng bộ, chống duplicate và race-condition.

### Luồng xử lý

1. Consume từ topic raw webhook nội bộ.
  2. Verify `Stripe-Signature` header theo cơ chế `t=timestamp,v1=signature` của Stripe. Tính HMAC-SHA256 với `signed_payload = timestamp + "." + raw_body` dùng secret key `whsec_...` (lấy từ Vault/Secret Manager), so sánh với `v1`. Reject ngay nếu `|now - timestamp| > 300s` (chống replay). Nếu thất bại, ghi `FAILED_SIGNATURE` vào `webhook_logs`.
  3. Parse payload, map Stripe event type → FSM transition:
     - `payment_intent.succeeded` → T2 (PAYMENT_SUCCESS_CONFIRMED)
     - `payment_intent.payment_failed` → T3 (PAYMENT_FAILED_CONFIRMED)
     - `payment_intent.canceled` → T5 (PAYMENT_CANCELED)
     - `charge.refunded` → R2 (REFUND_SUCCESS_CONFIRMED)
  4. Check Idempotency bằng `stripe_event_id` (event ID bắt đầu bằng `evt_`). Stripe có thể retry cùng event; `webhook_logs.UNIQUE(provider, provider_transaction_id, event_type)` chặn được, nhưng thêm check `stripe_event_id` để dedup cross-attempt. Thực hiện `INSERT INTO webhook_logs ... ON CONFLICT (provider, provider_transaction_id, event_type) DO NOTHING`. Nếu insert trả về 0 rows -> Webhook đã được xử lý -> Cắt chuỗi, báo thành công cho consumer.
5. Update DB transaction (`payment_transactions` dùng optimistic lock `version`) + insert outbox event trong cùng 1 ACID transaction.

### Quy tắc bắt buộc

1. Webhook API endpoint phải trả 2xx nhanh (< 500ms), worker mới xử lý logic nặng.
2. Worker phải an toàn với duplicate/out-of-order nhờ Idempotency và DB Unique Constraint (Stripe retry 3-5 lần với exponential backoff).
3. Nếu gặp lỗi tạm thời (lock DB/Stripe API timeout), retry theo cơ chế consumer/requeue có exponential backoff.

## 3. Payment Expiration Scanner (CronJob)

**Mục tiêu:** Đưa các transaction quá hạn về `EXPIRED`, tránh treo phiên thanh toán.

### Luồng xử lý

1. Quét `payment_transactions` có `status IN (PENDING, PROCESSING)` và `expires_at <= NOW()`.
2. Validate lại trạng thái với provider nếu cần (tránh false expire khi webhook đến trễ).
3. Apply transition sang `EXPIRED`.
4. Ghi `payment_status_history` + outbox `PAYMENT_EXPIRED`.

### Cấu hình gợi ý

- Run mỗi 1-2 phút.
- Batch theo partition hiện tại để tối ưu query.

## 4. Reconciliation Worker (Hourly — Stripe API)

**Mục tiêu:** Phát hiện và sửa sai lệch giữa dữ liệu nội bộ và Stripe.

### Luồng xử lý

1. Lấy tập giao dịch cần đối soát theo cửa sổ thời gian (VD: hourly cron).
2. Gọi Stripe API `BalanceTransaction.list` / `PaymentIntent.list` để lấy trạng thái chuẩn.
3. So khớp amount/currency/status với DB nội bộ.
4. Ghi mismatch report.
5. Với mismatch hợp lệ, tạo correction transition + outbox event.

### Đầu ra

- Báo cáo mismatch theo ngày.
- Danh sách case cần xử lý thủ công.
- Chỉ số drift rate cho vận hành.

## 5. Refund Status Poller (CronJob)

**Mục tiêu:** Theo dõi các refund đang `PROCESSING` để chốt trạng thái cuối.

> **Phase 18 (Stripe):** Với Stripe, webhook `charge.refunded` thường đến trong vài giây đến vài phút. CronJob vẫn chạy để cover case webhook miss (VD: network blip khi Stripe retry, Stripe API outage).

### Luồng xử lý

1. Quét `refund_transactions` có `status = PROCESSING` (và `updated_at` > `1 mins` để tránh query gấp).
2. Acquire Redis Distributed Lock: `lock:payment:refund_poll:{refund_transaction_id}` để chặn race-condition giữa nhiều instance.
3. Query Stripe refund status qua `Refund.retrieve(re_xxx)`.
4. Nếu success: transition `PROCESSING -> SUCCESS`, payment `REFUNDING -> REFUNDED`, emit `REFUND_SUCCESS`.
5. Nếu failed: transition `PROCESSING -> FAILED`, payment `REFUNDING -> SUCCESS`, emit `REFUND_FAILED`.
6. Nếu Stripe báo reverse (e.g. account invalid): ghi audit + emit `REFUND_REVERSED` + đẩy manual queue.

### Cấu hình gợi ý

- Poll mỗi 5-15 phút tuỳ Stripe SLA (refund thường settled trong vài giây đến vài ngày tùy issuing bank).
- Retry với jitter để tránh thundering herd.

## 6. Webhook Auto-Retry Worker (CronJob)

**Mục tiêu:** Xử lý lại các webhook bị rơi vào trạng thái lỗi (`FAILED_SIGNATURE` do sai key/outage hoặc `UNPROCESSED`).

### Luồng xử lý
1. Quét `webhook_logs` với `status IN ('FAILED_SIGNATURE', 'UNPROCESSED')`.
2. Kiểm tra `retry_count` < `max_retries` và có backoff phù hợp.
3. Đẩy lại webhook vào Kafka topic `t.payment.webhooks.raw` với ID giữ nguyên để xử lý lại.

## 7. Dead-letter Replay Worker (Manual/On-demand)

**Mục tiêu:** Cho phép replay an toàn các event/webhook đã rơi vào DEAD/DLQ.

### Luồng xử lý

1. Chọn message theo filter (time range, event type, order_id).
2. Re-validate idempotency trước replay.
3. Replay theo batch nhỏ có rate limit.
4. Ghi audit replay để phục vụ forensic.

## 7. Observability & SLO cho Workers

### Metrics tối thiểu

1. Outbox publish success rate.
2. Webhook processing lag.
3. Refund processing age (`now - refund.created_at`) cho trạng thái `PROCESSING`.
4. DLQ/dead events count.
5. Reconciliation mismatch rate.

### SLO gợi ý

1. 99.9% outbox event publish trong < 60s sau commit.
2. 99% webhook hợp lệ được xử lý trong < 2 phút.
3. 99% refund có trạng thái cuối trong SLA Stripe cam kết (thường vài giây cho `succeeded`, vài ngày cho issuing bank finalization).

## 8. Runbook sự cố (rút gọn)

1. Webhook backlog tăng:
- Kiểm tra consumer lag, scale worker, tạm giảm batch size nếu DB lock cao.

2. Refund stuck ở `PROCESSING`:
- Chạy poller thủ công theo `order_id`.
- So khớp Stripe API (`Refund.retrieve(re_xxx)`) hoặc Stripe Dashboard.
- Nếu cần, đưa vào manual queue và phát thông báo vận hành.

3. Outbox DEAD tăng đột biến:
- Kiểm tra Kafka availability/topic ACL.
- Replay theo batch sau khi khôi phục hạ tầng.

4. Mismatch reconciliation tăng:
- Freeze auto-correction cho event type liên quan.
- Mở chế độ review thủ công đến khi xác định root cause.
