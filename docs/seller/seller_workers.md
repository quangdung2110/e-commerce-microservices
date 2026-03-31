# Seller Service - Background Workers and Jobs

**Liên kết nhanh (Related Documents):**
* [Workflows (API & Data Flow)](seller_workflow.md)
* [Background Workers & Jobs](seller_workers.md)
* [Caching Schema (Redis + L1)](seller_caching_schema.md)
* [Database Schema (Write Store)](seller_db_design.md)
* [State Machine & Lifecycle](seller_state_machine.md)
* [Events Structure & Kafka Topics](seller_events.md)
* [Architecture Recommendations](seller_architecture_recommendations.md)

---

Tài liệu này mô tả các worker của Seller Service, tập trung vào verification, commission, payout và self-healing.

## 1. Tổng hợp topics liên quan

| Hướng | Topic | Consumer Group | Mô tả |
|---|---|---|---|
| Produce | `t.seller.events` | - | Phát toàn bộ seller lifecycle events |
| Consume | `t.order.events` | `seller-commission-group` | Input tính commission và payout accrual |
| Consume | `t.review.events` | `seller-performance-group` | Input chấm điểm chất lượng seller |
| Consume | `t.payment.events` | `seller-payout-status-group` | Callback kết quả payout |
| Consume | `t.inventory.events` | `seller-fulfillment-group` | Input năng lực fulfillment |
| Consume | `t.seller.events` | `seller-cache-selfheal-group` | Tự đồng bộ lại cache |

## 2. Outbox relay worker / CDC

### Sứ mệnh

Đảm bảo tất cả outbox records của Seller Service được publish lên Kafka đầy đủ và đúng thứ tự partition key.

### Input

- Bản ghi `seller_outbox_events` từ CDC hoặc poller.

### Output

- Message lên `t.seller.events` với key `seller_id`.

### Quy trình

1. Đọc outbox records.
2. Serialize theo CloudEvents.
3. Publish Kafka.
4. Theo dõi outbox lag và publish failures.

## 3. Verification worker

### Sứ mệnh

Xử lý hồ sơ xác minh seller và cập nhật lifecycle status.

### Input

- Verification submissions.
- Review commands từ admin/compliance.

### Output

- Trạng thái verification được cập nhật.
- Event `SELLER_VERIFICATION_STARTED`/`SELLER_VERIFICATION_COMPLETED`.

### Quy trình

1. Lấy hồ sơ cần xử lý.
2. Validate tài liệu và chính sách.
3. Mở transaction:
   - update `seller_verifications`.
   - update `sellers.status`/`verification_status` + `version`.
   - insert `seller_verification_history`.
   - insert outbox event.
4. Commit và refresh cache status/profile.

## 4. Commission calculator worker

### Sứ mệnh

Tính commission dựa trên events đơn hàng.

### Input

- `ORDER_PAID`, `ORDER_COMPLETED`, `ORDER_REFUNDED`.

### Output

- Ghi ledger commission.
- Cập nhật payout accrual.
- Event `SELLER_COMMISSION_CALCULATED`.

### Quy trình

1. Dedup event theo `event_id`.
2. Resolve commission rule (L1/L2 cache fallback DB).
3. Transaction theo order:
   - insert/update `seller_commission_ledger`.
   - adjust payout accrual.
   - update performance counters (nếu có policy).
   - insert outbox event.
4. Commit, refresh payout summary cache.

## 5. Payout processor worker

### Sứ mệnh

Thực thi settlement theo kỳ và đồng bộ trạng thái payout.

### Input

- Scheduler trigger theo chu kỳ.
- `PAYOUT_SUCCESS`/`PAYOUT_FAILED` callbacks.

### Output

- Cập nhật `seller_payouts`.
- Event payout tương ứng.

### Quy trình

1. Quét accrual theo kỳ.
2. Tạo payout records trạng thái `PENDING`.
3. Gọi payment provider với bounded concurrency.
4. Nhận callback và cập nhật trạng thái.
5. Ghi outbox event payout completed/failed.

## 6. Performance aggregator worker

### Sứ mệnh

Tổng hợp tín hiệu vận hành để chấm điểm seller.

### Input

- Sự kiện từ order/review/inventory.

### Output

- Cập nhật `seller_performance_metrics`.
- Event `SELLER_PERFORMANCE_SCORE_UPDATED`.

### Quy trình

1. Aggregate dữ liệu theo cửa sổ thời gian.
2. Tính score theo trọng số.
3. Update metrics + `performance_version`.
4. Emit event cho downstream.

## 7. Suspension monitor worker

### Sứ mệnh

Tự động suspend seller khi vi phạm ngưỡng chất lượng.

### Input

- Dữ liệu từ bảng metrics.

### Output

- Transition trạng thái seller.
- Event `SELLER_STATUS_CHANGED`.

### Quy trình

1. Chạy periodic scan sellers.
2. Nếu score dưới ngưỡng và thỏa policy, trigger `AUTO_SUSPEND_LOW_HEALTH`.
3. Ghi audit, outbox, cache refresh.

## 8. Cache self-heal worker

### Sứ mệnh

Tự tiêu thụ seller events để sửa drift cache L1/L2.

### Quy tắc bắt buộc

1. Không ghi business DB trong worker này.
2. Compare version/timestamp trước khi overwrite cache.
3. Publish L1 evict signal sau khi update L2.

## 9. Failure handling, retry, DLQ

1. Retry lỗi tạm thời theo exponential backoff.
2. Lỗi payload/schema đi DLQ.
3. Có parking-lot cho poison events.
4. Có công cụ replay có kiểm soát.

## 10. Monitoring và cảnh báo

1. `seller_worker_process_total{worker,event_type,status}`.
2. `seller_worker_duration_ms{worker}`.
3. `seller_dlq_total{worker,event_type}`.
4. `seller_payout_failure_ratio`.
5. `seller_commission_lag_seconds`.
