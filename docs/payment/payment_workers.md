# Payment Service - Background Workers & Jobs

**Liên kết nhanh (Related Documents):**
* [Database Schema](payment_db_design.md)
* [State Machine](payment_state_machine.md)
* [Workflows (API & Data Flow)](payment_workflow.md)
* [Events Structure & Kafka Topics](payment_events.md)
* [Refund Boundary Decision (ADR)](refund_boundary_decision.md)

---

Tài liệu này mô tả các worker nền của Payment Service để đảm bảo reliability cho các luồng payment/refund bất đồng bộ.

## 1. Outbox Polling Worker (Producer)

**Mục tiêu:** Publish event từ `outbox_events` lên Kafka theo nguyên tắc after-commit.

### Luồng xử lý

1. Query batch event `status = PENDING` và `next_retry_at <= NOW()`.
2. Publish vào topic tương ứng (`t.payment.events`).
3. Thành công: cập nhật `status = PUBLISHED`, `published_at = NOW()`.
4. Thất bại: tăng `retry_count`, tính `next_retry_at` theo exponential backoff.
5. Quá `max_retries`: chuyển `status = DEAD`.

### Cấu hình gợi ý

- Batch size: 200-500 events/lần poll.
- Poll interval: 200-500ms.
- Backoff: 1s -> 2s -> 4s -> 8s -> 16s.

## 2. Webhook Processor Worker (Kafka Consumer - Internal)

**Mục tiêu:** Xử lý webhook raw một cách bất đồng bộ, chống duplicate và race-condition.

### Luồng xử lý

1. Consume từ topic raw webhook nội bộ.
2. Verify signature (HMAC/RSA) trước mọi business logic. Nếu thất bại, ghi `FAILED_SIGNATURE` vào `webhook_logs`.
3. Parse payload, map provider status -> FSM transition.
4. Check Idempotency bằng DB Constraint: Thực hiện `INSERT INTO webhook_logs ... ON CONFLICT (provider, provider_transaction_id, event_type) DO NOTHING`. Nếu insert trả về 0 rows -> Webhook đã được xử lý -> Cắt chuỗi, báo thành công cho consumer.
5. Update DB transaction (`payment_transactions` dùng optimistic lock `version`) + insert outbox event trong cùng 1 ACID transaction.

### Quy tắc bắt buộc

1. Webhook API endpoint phải trả 2xx nhanh, worker mới xử lý logic nặng.
2. Worker phải an toàn với duplicate/out-of-order nhờ Idempotency và DB Unique Constraint.
3. Nếu gặp lỗi tạm thời (lock DB/Provider timeout), retry theo cơ chế consumer/requeue có exponential backoff.

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

## 4. Reconciliation Worker (Daily/Hourly)

**Mục tiêu:** Phát hiện và sửa sai lệch giữa dữ liệu nội bộ và provider.

### Luồng xử lý

1. Lấy tập giao dịch cần đối soát theo cửa sổ thời gian.
2. Gọi API/report từ provider để lấy trạng thái chuẩn.
3. So khớp amount/currency/status với DB.
4. Ghi mismatch report.
5. Với mismatch hợp lệ, tạo correction transition + outbox event.

### Đầu ra

- Báo cáo mismatch theo ngày.
- Danh sách case cần xử lý thủ công.
- Chỉ số drift rate cho vận hành.

## 5. Refund Status Poller (CronJob)

**Mục tiêu:** Theo dõi các refund đang `PROCESSING` để chốt trạng thái cuối.

### Luồng xử lý

1. Quét `refund_transactions` có `status = PROCESSING` (và `updated_at` > `1 mins` để tránh query gấp).
2. Acquire Redis Distributed Lock: `lock:payment:refund_poll:{refund_transaction_id}` để chặn race-condition giữa nhiều instance.
3. Query provider refund status theo `provider_refund_id`.
4. Nếu success: transition `PROCESSING -> SUCCESS`, payment `REFUNDING -> REFUNDED`, emit `REFUND_SUCCESS`.
5. Nếu failed: transition `PROCESSING -> FAILED`, payment `REFUNDING -> SUCCESS`, emit `REFUND_FAILED`.
6. Nếu provider báo reverse (nếu có): ghi audit + emit `REFUND_REVERSED` + đẩy manual queue.

### Cấu hình gợi ý

- Poll mỗi 5-15 phút tùy provider SLA.
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
3. 99% refund có trạng thái cuối trong SLA provider cam kết.

## 8. Runbook sự cố (rút gọn)

1. Webhook backlog tăng:
- Kiểm tra consumer lag, scale worker, tạm giảm batch size nếu DB lock cao.

2. Refund stuck ở `PROCESSING`:
- Chạy poller thủ công theo `order_id`.
- So khớp provider API/report.
- Nếu cần, đưa vào manual queue và phát thông báo vận hành.

3. Outbox DEAD tăng đột biến:
- Kiểm tra Kafka availability/topic ACL.
- Replay theo batch sau khi khôi phục hạ tầng.

4. Mismatch reconciliation tăng:
- Freeze auto-correction cho event type liên quan.
- Mở chế độ review thủ công đến khi xác định root cause.
