# User Service - Architecture Recommendations

**Liên kết nhanh (Related Documents):**
* [Workflows (API & Data Flow)](user_workflow.md)
* [Background Workers & Jobs](user_workers.md)
* [Caching Schema (Redis)](user_caching_schema.md)
* [Database Schema (Write Store)](user_db_design.md)
* [State Machine & Lifecycle](user_state_machine.md)
* [Events Structure & Kafka Topics](user_events.md)
* [Design Index](user_design.md)

---

Tài liệu này tổng hợp các khuyến nghị kiến trúc cốt lõi cho User Service nhằm đảm bảo hiệu năng, tính nhất quán, và khả năng mở rộng ở môi trường production.

## 1. Boundary và Ownership

1. User Service là source of truth cho profile, addresses, preferences, KYC status, và privacy lifecycle.
2. Auth Service sở hữu credentials/session/tokens; User Service không can thiệp vào password/token internals.
3. Order Service lưu immutable snapshot tại thời điểm tạo đơn, không overwrite theo profile hiện tại.
4. Review Service chỉ xử lý anonymization theo `ACCOUNT_DELETED`, không đồng bộ rename profile.
5. Seller onboarding bắt buộc kiểm tra KYC qua gRPC contract `CheckUserKycStatus`.

## 2. Eventing và Ordering

1. Topic produce chính: `t.user.events`, partition key là `user_id`.
2. Mọi event bắt buộc theo CloudEvents envelope.
3. Event schema chỉ thay đổi theo hướng additive; breaking change phải version hoá.
4. Consumer downstream phải idempotent theo `event_id` và có stale guard theo `state_version` hoặc `updated_at`.
5. Outbox event phải được ghi trong cùng transaction với business update.

## 3. Consistency và Concurrency

1. Các update nghiệp vụ quan trọng phải tăng `users.version`.
2. Lock ordering chuẩn: lock `users` trước, lock `user_addresses` sau.
3. Default address constraints bắt buộc enforce bằng partial unique indexes.
4. Không hard-delete ngay khi user yêu cầu xoá; bắt buộc retention trước anonymization.
5. KYC approve/reject phải atomic giữa update state và outbox publish.

## 4. Caching Strategy

1. Redis cache-aside là mặc định.
2. Soft invalidation (`SET` overwrite) là chuẩn; hạn chế `DEL` trên hot keys.
3. Batch point-read (`GetUsersByIds`) bắt buộc MGET/pipeline để tránh N+1.
4. TTL có jitter cho profile cache để giảm synchronized expiration.
5. Có singleflight và distributed lock cho hot profile keys.

## 5. Contract Governance

1. Contract-first cho REST/gRPC/events trước khi triển khai code.
2. Mọi thay đổi payload phải có contract tests với Order, Notification, Review, Seller.
3. Event bắt buộc có `correlation_id` để truy vết xuyên service.
4. PII trong payload event phải tối thiểu hóa theo nguyên tắc need-to-know.
5. Compliance events (`ACCOUNT_DELETED`, `ACCOUNT_DATA_RETENTION_EXPIRED`) là mandatory contracts.

## 6. Security và Compliance

1. KYC docs và sensitive fields phải mã hoá at-rest.
2. Audit logs bắt buộc cho profile update, KYC decisions, và deletion/anonymization.
3. Deletion flow gồm 2 pha: soft delete + retention; sau đó anonymization + retention-expired event.
4. Data portability endpoint cần hỗ trợ chế độ async/export package.
5. Internal APIs phải trả dữ liệu đã anonymize với legally deleted users.

## 7. Operability

1. Bắt buộc theo dõi metrics: p95/p99 latency, cache hit ratio, outbox lag, consumer lag, DLQ growth.
2. Có runbook cho DLQ replay, deletion reconciliation, cache rebuild.
3. Canary rollout theo tenant cohorts hoặc traffic percentage trước khi full rollout.
