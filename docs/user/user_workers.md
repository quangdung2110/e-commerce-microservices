# User Service - Background Workers and Jobs

**Liên kết nhanh (Related Documents):**
* [Workflows (API & Data Flow)](user_workflow.md)
* [Background Workers & Jobs](user_workers.md)
* [Caching Schema (Redis)](user_caching_schema.md)
* [Database Schema (Write Store)](user_db_design.md)
* [State Machine & Lifecycle](user_state_machine.md)
* [Events Structure & Kafka Topics](user_events.md)
* [Architecture Recommendations](user_architecture_recommendations.md)

---

Tài liệu này liệt kê đầy đủ các worker của User Service, cùng trách nhiệm, input/output và quy tắc vận hành.

## 1. Tổng hợp topics liên quan

| Hướng | Topic | Consumer Group | Mô tả |
|---|---|---|---|
| Produce | `t.user.events` | - | Phát các event vòng đời user |
| Consume | `t.auth.events` | `user-auth-sync-group` | Đồng bộ sự kiện account từ Auth |
| Consume | `t.order.events` | `user-order-stats-group` | Cập nhật user stats từ order |
| Consume | `t.payment.events` | `user-payment-sync-group` | Đồng bộ metadata thanh toán |
| Consume | `t.user.events` | `user-cache-selfheal-group` | Self-consume để sửa lệch cache |

## 2. Outbox relay worker / CDC pipeline

### Sứ mệnh

Đưa records từ `user_outbox_events` lên Kafka an toàn, giữ at-least-once semantics.

### Input

- Bản ghi outbox từ CDC hoặc poller.

### Output

- Message lên `t.user.events` với partition key là `user_id`.

### Quy trình

1. Đọc records mới từ outbox stream.
2. Serialize theo CloudEvents.
3. Publish lên Kafka.
4. Ghi metric outbox lag và publish success/failure.

## 3. KYC verification worker

### Sứ mệnh

Xử lý hồ sơ KYC đang `PENDING` và cập nhật kết quả về hệ thống.

### Input

- Bản ghi `kyc_documents` status `PENDING`.
- Callback từ provider (nếu có).

### Output

- Cập nhật trạng thái KYC trong DB.
- Event `KYC_VERIFICATION_APPROVED` hoặc `KYC_VERIFICATION_REJECTED`.

### Quy trình

1. Lấy batch hồ sơ pending.
2. Gọi provider/manual queue.
3. Mở transaction: cập nhật `kyc_documents`, `users.kyc_status`, outbox event.
4. Commit và refresh cache `user:kyc:{user_id}`.

## 4. Deletion retention worker

### Sứ mệnh

Thực thi anonymization cho các user đã hết retention window.

### Input

- `user_deletion_requests` có `retention_window_expires_at <= now()`.

### Output

- User được anonymize theo policy.
- Event `ACCOUNT_DATA_RETENTION_EXPIRED`.

### Quy trình

1. Quét các requests quá hạn.
2. Mở transaction:
   - Mask PII trong bảng `users`.
   - Cập nhật `is_legally_deleted = true`.
   - Đổi trạng thái deletion request.
   - Ghi compliance audit.
   - Ghi outbox event.
3. Commit và emit metrics.

## 5. Cache self-heal worker

### Sứ mệnh

Tự tiêu thụ event `t.user.events` để sửa drift cache.

### Input

- Event user lifecycle từ Kafka.

### Output

- Overwrite các key cache liên quan.

### Quy tắc bắt buộc

1. Không được ghi business DB trong worker này.
2. So sánh `state_version`/`updated_at` trước khi ghi đè cache.
3. Dùng pipeline cho batch updates.

## 6. Data portability export worker

### Sứ mệnh

Xuất dữ liệu người dùng theo yêu cầu compliance.

### Input

- Export request jobs.

### Output

- Gói dữ liệu JSON/CSV trong object storage.
- Audit trail và thông báo kết quả.

### Quy trình

1. Nhận export job.
2. Tổng hợp dữ liệu từ User + read APIs liên quan (nếu được phép).
3. Tạo archive và upload object storage.
4. Ghi audit + gửi notification.

## 7. Failure handling, retry, DLQ

1. Retry lỗi tạm thời theo exponential backoff.
2. Lỗi payload/schema cho vào DLQ.
3. Có công cụ replay DLQ theo `event_type` và time range.
4. Với poison event, hỗ trợ parking-lot để phân tích trước khi replay.

## 8. Monitoring và cảnh báo

1. `user_worker_process_total{worker,event_type,status}`.
2. `user_worker_duration_ms{worker}`.
3. `user_dlq_total{worker,event_type}`.
4. `user_outbox_lag_seconds`.
5. `user_cache_selfheal_total{event_type}`.
