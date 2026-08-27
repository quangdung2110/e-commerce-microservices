---
doc_id: auth-operations
service: auth
scope: target
status: proposed
owners:
  - Auth Service team
evidence: []
verified_against_commit: null
---

# Auth Service — Vận hành

> File này trả lời: “Service hỏng thì phát hiện và xử lý thế nào?”

## 1. SLO, health và configuration

Availability, latency, authentication error rate, refresh success rate, Kafka
consumer lag và thời gian hoàn tất worker là các SLI cần có. Giá trị SLO, error
budget và alert threshold được giữ là `TBD`; không đưa ra khẳng định hiệu năng
khi chưa có production evidence.

Runtime contract sau này phải định nghĩa liveness, readiness và startup probe.
Readiness cần tính đến dependency khiến việc authentication không còn an toàn,
đặc biệt là credential storage và signing-key access. Configuration phải ghi JWT/
refresh TTL, lockout threshold/window, OTP/reset TTL, Redis/Kafka/PostgreSQL
endpoint, key material/KMS reference và event topic. Không ghi giá trị thật hoặc
tên secret trước khi xác minh; secret không bao giờ xuất hiện trong file này.

## 2. Observability và alert

Telemetry bắt buộc gồm correlation ID của request/workflow, `user_id` chỉ khi
privacy policy cho phép, event ID và key ID. Không dùng raw email, token hoặc
secret làm metric label cardinality cao hay log field.

| Tín hiệu | Điều kiện alert cần xác định | Owner / runbook |
|---|---|---|
| Lỗi login/refresh | Error rate liên tục vượt SLO | Auth on-call; runbook dependency authentication |
| Redis lỗi | Thao tác revocation/rate limit thất bại | Auth on-call; runbook cache outage |
| Kafka publish/consumer lag | Publish retry hết hoặc lag tăng | Auth on-call; runbook event/DLQ |
| DB pool cạn | Pool wait/error vượt ngưỡng | Auth on-call; runbook database |
| Key rotation | Rotation/retirement quá hạn hoặc nhiều active key | Security/Auth owner; runbook key lifecycle |

## 3. Failure và degraded behavior

Database hoặc signing-key lỗi phải dừng các operation không thể cấp hay revoke
credential an toàn. Redis lỗi cần một quyết định bảo mật rõ ràng: fail-open có
thể chấp nhận access token đã revoke, còn fail-closed có thể chặn traffic hợp lệ;
tài liệu không tự chọn một trong hai. Kafka/Notification/User lỗi không được
rollback Auth state đã commit, nhưng cần durable retry hoặc reconciliation theo
ADR-005. Mixed-version deployment phải giữ JWKS và event compatibility trong suốt
token/key overlap.

## 4. Scaling, deployment và rollback

API instance scale theo request latency, CPU/memory và database pool capacity.
Consumer chỉ scale đến số topic partition mà vẫn giữ ordering theo user. Scheduled
job cần một leader dùng distributed lock. Rollout phải backward compatible với
JWT key, refresh-token data và contract đang tồn tại; thứ tự schema migration và
rollback vẫn cần được ghi từ deployment configuration.

## 5. Worker và scheduled job

| Worker | Mục đích | Concurrency / an toàn | Telemetry bắt buộc |
|---|---|---|---|
| `refresh-token-cleanup-cron` | Xóa token expired/revoked sau retention | Một lượt chạy qua distributed lock; batch idempotent | deleted count, duration, failure |
| `key-rotation-worker` | Tạo signing key mới và phase out key cũ | Lock; không bao giờ để thiếu active key | active key age, rotation result |
| `key-retirement-worker` | Retire key sau maximum token lifetime | Xác minh overlap trước khi retire | retirement result, blocked key age |
| `auto-unlock-account-worker` | Chuyển lock đã hết hạn về `ACTIVE` | State transition idempotent | unlocked count, lag/failure |
| `account-hard-delete-worker` | Hard delete account `DELETED` quá 90 ngày | Batch idempotent; dùng `idx_accounts_hard_delete` | deleted count, duration, failure |

Schedule, timeout, retry, backoff, retention và quy trình replay là `TBD` đến khi
có runtime configuration. Mỗi worker cần quy trình chạy lại thủ công an toàn trước
khi được production verification.

## 6. Runbook bắt buộc

Runbook phải bao phủ consumer lag/DLQ, cache corruption/outage, database pool cạn,
reconciliation failure, key-rotation failure và downstream outage. Mỗi runbook
cần symptom, chẩn đoán chỉ đọc, mitigation, xác minh recovery và escalation owner.
Không runbook nào được đánh dấu hoàn chỉnh trong refactor chỉ dựa trên thiết kế này.
