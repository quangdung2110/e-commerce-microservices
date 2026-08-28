---
doc_id: user-operations
service: user
scope: target
status: proposed
owners:
  - User Service team
evidence: []
verified_against_commit: null
---

# User Service — Vận hành

> Mục tiêu SLO, cấu hình cụ thể và runbook automation chưa được xác minh. Không
> xem các mục dưới đây là cam kết production cho đến khi owner chốt giá trị đo.

## 1. SLO, health và cấu hình

Cần định nghĩa SLI cho HTTP availability/latency/error rate, Postgres pool,
Redis fallback/cache error, Kafka producer failure, Auth-event consumer lag/DLQ
và age/count retention record quá hạn. SLO, error budget và owner chưa được chốt.

Liveness chỉ xác nhận process còn chạy. Readiness phải kiểm tra các dependency
cần thiết cho API ghi (PostgreSQL); Kafka consumer readiness và Redis cache nên
được báo riêng để tránh loại API healthy chỉ vì cache lỗi. Cache unavailable phải
degrade sang DB; PostgreSQL unavailable không ready. Tên env var, secret, default
và yêu cầu restart phải được lập bảng từ runtime configuration đã xác minh.

## 2. Quan sát hệ thống và cảnh báo

Metrics tối thiểu: HTTP request/error/latency theo route/status; DB pool/timeout;
address cache hit/miss/fallback/invalidation failure; Kafka consume/publish result,
lag, retry và DLQ; event duplicate; anonymization/hard-delete result và oldest
due record. Log cấu trúc cần correlation/request ID, event ID và hashed/redacted
user reference; không log profile, phone hoặc full address. Trace cần bao phủ HTTP
command, DB transaction, Redis, Kafka consume/publish và worker batch.

| Cảnh báo | Điều kiện cần chốt bằng số đo | Mức độ | Runbook |
|---|---|---|---|
| Auth-event consumer lag | Lag/oldest event vượt SLO | Cao | Consumer lag |
| Profile/preference event publish failure | Retry cạn hoặc reconciliation backlog | Cao | Event publishing |
| Cache invalidation failure | Error rate vượt ngưỡng trong cửa sổ | Trung bình | Cache degradation |
| DB pool exhaustion | Pool saturation/timeout vượt ngưỡng | Cao | Database exhaustion |
| GDPR purge overdue | Oldest due retention record vượt SLO | Cao | Compliance worker |
| DLQ non-empty | Bất kỳ message chưa triage | Cao | DLQ handling |

## 3. Hành vi khi lỗi và suy giảm

| Lỗi | Hành vi dự kiến |
|---|---|
| PostgreSQL unavailable | Reject read/write phụ thuộc DB; consumer không ack; worker retry theo policy |
| Redis unavailable/corrupt | Address read fallback DB; mutation commit vẫn thành công, invalidation lỗi được ghi metric/alert |
| Kafka producer unavailable | DB command profile/preference vẫn commit theo direct-publish design; retry/reconciliation phải phát hiện projection/event gap |
| Kafka consumer unavailable/lag | API còn hoạt động; profile bootstrap/deletion bị trì hoãn, lag/DLQ alert |
| Mixed-version deployment | Chỉ rollout field/event backward compatible; consumer bỏ qua optional unknown field |

## 4. Scaling, triển khai và rollback

API scale theo RPS, latency và PostgreSQL pool. Consumer scale theo Kafka
partition/lag, không phá ordering cùng `user_id`. Compliance worker không chạy
chồng batch nếu chưa có row claim/distributed lock. Deployment cần migration
backward-compatible trước application; event field mới optional trước; rollback
không được quay về version không đọc được data/event mới. Chưa có load test,
partition count hoặc capacity baseline được xác minh.

## 5. Worker và tác vụ định kỳ

| Tác vụ | Trigger | Concurrency/idempotency | Quan sát hệ thống |
|---|---|---|---|
| Auth-event consumer | Kafka `t.auth.events` | Keyed `user_id`; dedup event/business key; retry + DLQ | Lag, duplicate, success/failure |
| Compliance hard-delete | Schedule (thiết kế cũ đề xuất daily 02:00) | Row claim/lock; deadline check; per-record transaction | Due/processed/failed/oldest due |
| Event reconciliation | Chưa được thiết kế | Cần chốt nguồn đối chiếu và replay an toàn | Missing-event/backlog metric |

## 6. Runbook bắt buộc

- **Consumer lag/DLQ:** kiểm tra broker health, partition assignment, oldest event
  và error class; không bỏ message; replay sau khi fix với dedup; xác nhận lag/DLQ
  giảm và profile/deletion được reconcile.
- **Cache degradation/corruption:** xác nhận Redis error, bypass hoặc xóa key
  phạm vi một user khi an toàn, không flush namespace rộng; kiểm tra fallback DB
  và invalidation metric.
- **Database exhaustion:** dừng scale mù quáng, kiểm tra pool/query/lock, giảm
  traffic khi cần, khôi phục pool rồi xác nhận write và consumer retry thành công.
- **Direct event publishing failure:** xác định committed mutation không có event,
  dùng công cụ reconciliation đã được chấp thuận để republish idempotently; không
  tự phát event suy đoán từ log PII.
- **Compliance worker backlog:** kiểm tra clock/deadline, lock và DB errors; chạy
  batch có giới hạn sau khi phê duyệt, xác nhận không delete trước hạn.
