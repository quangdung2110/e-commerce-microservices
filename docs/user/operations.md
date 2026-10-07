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

Runtime local hiện có trong [development.md](./development.md): Go service, root
`user-migrate`, public listener 8082 và internal 8083. `/health/live` dùng GET;
readiness/startup kiểm tra PostgreSQL required, Redis optional. Docker probe dùng
GET readiness; Kafka consumer healthy phải kiểm tra riêng qua lag/bootstrap, không
suy ra từ probe. SLO/metric/production acceptance bên dưới vẫn cần bằng chứng đo.

Cần định nghĩa SLI cho HTTP availability/latency/error rate, Postgres pool,
Redis fallback/cache error, Kafka producer failure, Auth-event consumer lag/DLQ,
age/count retention record quá hạn và projection reconciliation duration/failure.
SLO, error budget và owner chưa được chốt.

Liveness chỉ xác nhận process còn chạy. Readiness phải kiểm tra các dependency
cần thiết cho API ghi (PostgreSQL); Kafka consumer readiness và Redis cache nên
được báo riêng để tránh loại API healthy chỉ vì cache lỗi. Cache unavailable phải
degrade sang DB; PostgreSQL unavailable không ready. Tên env var, secret, default
và yêu cầu restart phải được lập bảng từ runtime configuration đã xác minh.

## 2. Quan sát hệ thống và cảnh báo

Metrics tối thiểu: HTTP request/error/latency theo route/status; DB pool/timeout;
address cache hit/miss/fallback/invalidation failure; Kafka consume/publish result,
lag, retry và DLQ; event duplicate; anonymization/hard-delete result, oldest due
record, reconciliation scanned/published/failed/duration. Log cấu trúc cần
correlation/request ID, event ID và hashed/redacted user reference; không log
profile, phone hoặc full address. Trace cần bao phủ HTTP command, DB transaction,
Redis, Kafka consume/publish và worker batch.

| Cảnh báo | Điều kiện cần chốt bằng số đo | Mức độ | Runbook |
|---|---|---|---|
| Auth-event consumer lag | Lag/oldest event vượt SLO | Cao | Consumer lag |
| Profile/preference event publish failure | Retry cạn hoặc reconciliation backlog | Cao | Event publishing |
| Cache invalidation failure | Error rate vượt ngưỡng trong cửa sổ | Trung bình | Cache degradation |
| DB pool exhaustion | Pool saturation/timeout vượt ngưỡng | Cao | Database exhaustion |
| GDPR purge overdue | Oldest due retention record vượt SLO | Cao | Compliance worker |
| Projection reconciliation failed | Job không hoàn tất hoặc publish failure cạn | Cao | Event publishing |
| DLQ non-empty | Bất kỳ message chưa triage | Cao | DLQ handling |

## 3. Hành vi khi lỗi và suy giảm

| Lỗi | Hành vi dự kiến |
|---|---|
| PostgreSQL unavailable | Reject read/write phụ thuộc DB; consumer không ack; worker retry theo policy |
| Redis unavailable/corrupt | Address read fallback DB; mutation commit vẫn thành công, invalidation lỗi được ghi metric/alert |
| Kafka producer unavailable | DB command profile/preference vẫn commit theo direct-publish design; retry/reconciliation phải phát hiện projection/event gap |
| Kafka consumer unavailable/lag | API còn hoạt động; profile bootstrap/deletion bị trì hoãn, lag/DLQ alert |
| Reconciliation worker unavailable | Projection có thể stale lâu hơn một ngày; alert và chạy lại job, không phát sự kiện suy đoán từ log |
| Mixed-version deployment | Chỉ rollout field/event backward compatible; consumer bỏ qua optional unknown field |

## 4. Scaling, triển khai và rollback

API scale theo RPS, latency và PostgreSQL pool. Consumer scale theo Kafka
partition/lag, không phá ordering cùng `user_id`. Compliance worker không chạy
chồng cùng một retention row nhờ row claim/lock; reconciliation worker chỉ có một
run active toàn service để tránh full sweep trùng. Deployment cần migration
backward-compatible trước application; event field mới optional trước; rollback
không được quay về version không đọc được data/event mới. Chưa có load test,
partition count hoặc capacity baseline được xác minh.

## 5. Worker và tác vụ định kỳ

| Tác vụ | Trigger | Concurrency/idempotency | Quan sát hệ thống |
|---|---|---|---|
| `user-auth-lifecycle-consumer` | Kafka `t.auth.events` liên tục | Keyed `user_id`; PK/unique business key; ACK chỉ sau commit; retry tối đa 10 lần theo exponential backoff 1s→5m, sau đó `t.auth.events.dlq` | Lag, duplicate, result, retry, DLQ |
| `user-retention-purge` | Mỗi giờ theo UTC | Batch tối đa 100; `FOR UPDATE SKIP LOCKED`; deadline check và transaction từng record | Due/processed/failed/oldest due, lock contention |
| `user-projection-reconciliation` | Mỗi ngày ngoài giờ cao điểm | Một run active; full sweep active profile/preferences theo batch; event corrective có ID mới, `updated_at` giữ nguyên | Scanned/published/failed/duration, Kafka throttle |
| DLQ redrive | Thao tác vận hành có phê duyệt | Không tự động redrive; sửa nguyên nhân, đánh giá poison message rồi replay có kiểm soát | DLQ age/count, replay result |

## 6. Runbook bắt buộc

- **Consumer lag/DLQ:** kiểm tra broker health, partition assignment, oldest event
  và error class; không bỏ message hoặc auto-redrive. Sau khi sửa nguyên nhân,
  đánh giá poison message và replay có phê duyệt với dedup; xác nhận lag/DLQ giảm
  và profile/deletion được reconcile.
- **Cache degradation/corruption:** xác nhận Redis error, bypass hoặc xóa key
  phạm vi một user khi an toàn, không flush namespace rộng; kiểm tra fallback DB
  và invalidation metric.
- **Database exhaustion:** dừng scale mù quáng, kiểm tra pool/query/lock, giảm
  traffic khi cần, khôi phục pool rồi xác nhận write và consumer retry thành công.
- **Direct event publishing failure:** xác định metric publish failure; để
  `user-projection-reconciliation` phát current snapshot của profile/preferences
  trong sweep kế tiếp hoặc chạy lại job có phê duyệt. Không tự phát event suy đoán
  từ log PII và không phát lại `USER_DELETED`.
- **Compliance worker backlog:** kiểm tra clock/deadline, lock và DB errors; chạy
  batch có giới hạn sau khi phê duyệt, xác nhận không delete trước hạn.
