---
doc_id: auth-operations
service: auth
scope: current
status: implemented
owners:
  - Auth Service team
evidence:
  - go/services/auth-service/configs/application.yaml
  - go/services/auth-service/internal/common/config/config.go
  - go/services/auth-service/internal/workers
  - go/services/auth-service/internal/api/handler/health_handler.go
verified_against_commit: null
---

# Auth Service — Vận hành

> Cấu hình, worker và hành vi lỗi dưới đây khớp code. Mục tiêu SLO, ngưỡng cảnh báo và
> metric Prometheus **chưa** được định nghĩa hay export (service chỉ có structured log):
> không xem chúng là cam kết production cho đến khi owner chốt giá trị đo.

## 1. SLO, health và configuration

SLI cần có: availability và latency của login/refresh, authentication error rate, refresh success rate,
thời gian hoàn tất worker, số lần publish event thất bại (retry cạn) và độ trễ ghi revocation marker. Giá trị
SLO, error budget và alert threshold được giữ là `TBD`; không đưa ra khẳng định hiệu năng khi chưa có
production evidence. (Auth không consume Kafka nên không có SLI consumer lag.)

### Health

| Endpoint | Listener | Ý nghĩa |
|---|---|---|
| `GET /health/live` | public 8080 và internal 8081 | Process còn chạy |
| `GET /health/ready`, `GET /health/startup` | public 8080 | PostgreSQL `Ping`, Redis `PING` và có đọc được signing key; một probe lỗi là `503 {"status":"DOWN"}` (probe nào lỗi chỉ ghi log). Cả ba là dependency khiến authentication không còn an toàn |

Docker/compose healthcheck dùng `/health/live`.

### Cấu hình

Mọi key của `configs/application.yaml` override được bằng biến `AUTH_` (dấu chấm thành gạch dưới, ví dụ
`AUTH_JWT_ISSUER`); danh sách phân tách bằng dấu phẩy. Chỉ các key đã có giá trị mặc định trong `config.go`
mới nhận env; secret đi qua `${...}` trong yaml: `DB_PASSWORD`, `REDIS_PASSWORD`, `AUTH_KEYS_MASTER_KEY`
(base64 của 32 byte; **bắt buộc**). `Validate()` chặn khởi động khi cấu hình vi phạm invariant.

| Nhóm | Key (mặc định) | Ghi chú |
|---|---|---|
| Listener | `app.port` (8080), `internal.port` (8081), `internal.enabled` (true) | Hai port phải khác nhau; internal không đi qua Gateway. Root Compose publish `8086:8080` |
| Caller internal | `internal.allowed_callers` (`notification-service`) | Env `AUTH_INTERNAL_ALLOWED_CALLERS`; rỗng khi internal bật là lỗi khởi động. Không còn cấu hình TLS ở app ([ADR-007](./decisions/ADR-007-internal-caller-auth-delegated-to-mesh.md)) |
| JWT | `jwt.issuer` (`https://auth.ecommerce.local`), `jwt.audience` (`ecommerce-api`), `jwt.algorithm` (`RS256`), `jwt.access_token_ttl` (15m), `jwt.clock_skew` (30s), `jwt.key_cache_ttl` (5m), `jwt.jwks_cache_control` (`public, max-age=600`) | Issuer, audience, algorithm phải khớp tuyệt đối `gateway-auth` |
| Refresh | `refresh.sliding_ttl` (168h), `refresh.absolute_ttl` (720h), `refresh.cookie_name` (`auth_refresh`), `refresh.cookie_path` (`/api/v1/auth/sessions`), `refresh.cookie_secure` (true; yaml mẫu đặt false cho local) | `sliding_ttl` không được vượt `absolute_ttl`; production bắt buộc `cookie_secure` |
| Lockout | `security.lockout.max_failed_attempts` (5), `lock_duration` (15m), `counter_reset_after` (1h) | Bộ đếm bền trong DB theo account |
| Password | `security.password_policy.min_length` (12, không được thấp hơn), `max_length` (128); `security.argon2` (64 MiB, 3 vòng, 4 luồng) | |
| Rate limit | `security.rate_limit.*`: `login_per_ip` (20/5m), `login_per_account` (10/5m), `registration_per_ip` (10/1h), `otp_per_account` (5/15m), `password_reset_per_ip` (5/1h), `refresh_per_ip` (60/5m), `internal_per_resource` (120/1m) | Fail closed: Redis lỗi thì request bị từ chối. Chi tiết ở [Interfaces §2](./interfaces.md#rate-limit) |
| Proxy | `security.trusted_proxies` (`[]`), `security.client_ip_headers` (`[X-Real-IP]`) | Chỉ địa chỉ Gateway: root Compose `AUTH_SECURITY_TRUSTED_PROXIES=10.89.3.2/32` (địa chỉ cố định của `api-gateway`), `deploy/k8s` pod CIDR. Từ peer đó Auth đọc client ở `X-Real-IP` do Traefik đặt (hoặc frontend chuyển, [mesh ADR-004](../mesh/decisions/ADR-004-secrets-client-ip-network-split-and-internal-signing.md)); `X-Forwarded-For` không được đọc. Rỗng thì mọi request sau gateway chung một ngân sách theo IP |
| Chữ ký internal | `internal.signing_key` (rỗng), `internal.signature_tolerance` (60s) | Env `AUTH_INTERNAL_SIGNING_KEY`, chung với Notification; xem [Interfaces](./interfaces.md#listener-internal) |
| Secret ngoài development | `app.env` khác `development`/`dev` | `keys.master_key`, `keys.previous_master_keys`, `internal.signing_key` trống, ngắn hơn 32 byte, trùng giá trị dev của compose hoặc chứa dấu hiệu placeholder (`change_me`, `local`, `dev-`, `example`…) thì không khởi động (security review 2026-10-22 H1) |
| OTP | `otp.ttl` (5m), `otp.max_attempts` (5), `otp.resend_limit` (3), `otp.resend_window` (15m) | |
| Reset | `password_reset.ttl` (15m), `password_reset.url_template` (token trong fragment `#token={token}`) | |
| Notification | `notification.delivery_ttl` (30m) | Thời gian tối thiểu Notification được resolve nội dung |
| Redis | `redis.revocation_prefix` (`auth:revocation`), `redis.state_prefix` (`auth`), timeout 1s | Đổi `revocation_prefix` phải đổi đồng thời ở `gateway-auth` |
| Kafka | `kafka.enabled` (true), `kafka.brokers`, `kafka.identity_topic` (`t.auth.events`), `kafka.notification_topic` (`t.auth.notification.events`), `kafka.publish_max_attempts` (3), `publish_backoff` (200ms), `publish_timeout` (5s) | `enabled=false` chỉ log event, dùng cho local |
| Keys | `keys.rsa_bits` (2048), `keys.rotation_interval` (720h), `keys.retirement_delay` (24h, không được ngắn hơn `access_token_ttl`), `keys.master_key`, `keys.master_key_version` (1), `keys.previous_master_keys` | Mất master key đồng nghĩa mất toàn bộ signing key |
| Workers | `workers.lock_ttl` (5m), `workers.refresh_token_retention` (168h), từng worker xem §5 | |
| Database | `database.max_open_conns` (25), `min_conns` (5), `statement_timeout` (5s), `sslmode` (`disable`) | Dùng `require` hoặc `verify-full` ngoài local |

## 2. Observability và alert

Chưa có metric Prometheus; tín hiệu hiện có là log JSON có `request_id`/`trace_id`. Không dùng raw email, token hoặc
secret làm log field hoặc label; access log chỉ ghi route và status. Các dòng log cần alert:

| Log (mức) | Ý nghĩa | Việc cần làm |
|---|---|---|
| `identity event could not be published` (error) | Retry Kafka cạn, event bị mất ([ADR-005](./decisions/ADR-005-event-publication-reliability.md)); `event_type` cho biết loại. Với `USER_DELETED` phải redrive/replay thủ công cho mọi consumer sở hữu PII | Runbook event/DLQ |
| `notification delivery state could not be stored` (error) | Không lưu được state ở Redis nên Notification không có gì để resolve | Runbook cache outage |
| `could not write the revocation marker of a deleted account` và lỗi thu hồi trong đổi mật khẩu/email (error) | Thay đổi đã commit nhưng access token cũ còn sống tới 15 phút; client nhận `500` | Gọi lại logout-all cho account nếu cần |
| `worker run failed` (error), `could not acquire the worker lock` (error) | Worker lỗi hoặc không có Redis | Runbook worker |
| `readiness probe failed` (warn) | Dependency lỗi | Runbook dependency |
| `could not apply the trusted proxy list` (warn) | Cấu hình `trusted_proxies` sai | Sửa cấu hình |

| Tín hiệu | Điều kiện alert cần xác định | Owner / runbook |
|---|---|---|
| Lỗi login/refresh | Error rate liên tục vượt SLO | Auth on-call; runbook dependency authentication |
| Redis lỗi | Thao tác revocation/rate limit thất bại | Auth on-call; runbook cache outage |
| Kafka publish | Retry hết (xem log ở trên) | Auth on-call; runbook event/DLQ |
| DB pool cạn | Pool wait/error vượt ngưỡng | Auth on-call; runbook database |
| Key rotation | Rotation/retirement quá hạn hoặc nhiều active key | Security/Auth owner; runbook key lifecycle |
| `CONCURRENT_UPDATE` | Xuất hiện trong response `500` (thua 4 lần liên tiếp) | Auth on-call; hiếm, nghi có một client spam ghi vào cùng account |

## 3. Failure và degraded behavior

| Lỗi | Hành vi |
|---|---|
| PostgreSQL unavailable | Login, refresh, đăng ký và mọi thao tác ghi thất bại; readiness `DOWN` |
| Redis unavailable | **Fail closed**: login, refresh, đăng ký, reset, đổi email, rate limit và thu hồi đều trả `500 DEPENDENCY_UNAVAILABLE` (retryable); readiness `DOWN`. Không có đường fail-open nào. `gateway-auth` cũng fail closed với `503` khi không đọc được revocation |
| Redis lỗi sau khi DB đã commit (xóa account, đổi email, đổi/reset mật khẩu) | Event vẫn được phát; request trả `500`; refresh token đã revoke trong DB nhưng access token đã phát sống tới 15 phút. Có thể gọi lại logout-all ([ADR-006](./decisions/ADR-006-publish-before-revocation-marker.md)) |
| Kafka unavailable | Auth state đã commit không rollback; retry 3 lần rồi bỏ event và log `error`. Cần redrive thủ công (reconciliation chưa tự động) |
| Notification unavailable | Event notification nằm trong Kafka; delivery state giữ ≥ 30 phút. Quá TTL thì Notification nhận `410` và phải yêu cầu gửi lại |
| Mixed-version deployment | Phải giữ JWKS và event compatibility trong suốt token/key overlap. Hai phiên bản Auth cùng chạy được vì ghi account là compare-and-set |
| Không có signing key | `SIGNING_KEY_MISSING` (500), fail closed; khởi động tạo key đầu tiên trước khi mở listener |

## 4. Scaling, deployment và rollback

API instance scale theo request latency, CPU/memory (Argon2 tốn ~64 MiB mỗi lần verify đồng thời) và database pool
capacity. Auth không consume event nên không bị ràng buộc partition. Scheduled job singleton nhờ Redis lock nên có
thể chạy nhiều replica. Rollout phải backward compatible với JWT key, refresh-token data và contract đang tồn tại.
Migration hiện có một file (`0001`); thứ tự migration và rollback (`0001_...down.sql`) chạy bằng `make migrate-up` /
`migrate-down`. Đổi `jwt.*`, `redis.revocation_prefix` phải đồng bộ với `gateway-auth`.

## 5. Worker và scheduled job

Mỗi worker chạy lượt đầu ngay khi khởi động rồi lặp theo `interval`; mỗi lượt giữ Redis lock `auth:lock:{tên}` (TTL
`workers.lock_ttl` hoặc `timeout + 1 phút` nếu dài hơn), bị giới hạn bởi `timeout`, và ghi log `worker run completed`
kèm `duration` hoặc `worker run failed`. Đặt `enabled: false` (hoặc `interval` ≤ 0) để tắt.

| Worker (tên lock) | Mục đích | Interval / timeout / batch | Telemetry (log) |
|---|---|---|---|
| `refresh-token-cleanup` | Xóa token expired/revoked quá `refresh_token_retention` (168h) | 1h / 2m / 1000 | số dòng đã xóa, duration |
| `key-rotation` | Tạo signing key mới khi key `ACTIVE` quá `rotation_interval` (720h) và phase out key cũ; publish JWKS invalidation | 1h / 1m | kết quả rotation |
| `key-retirement` | Retire key `PHASE_OUT` sau `retirement_delay` (24h) | 1h / 1m | số key đã retire |
| `auto-unlock-account` | Chuyển lock đã hết hạn về `ACTIVE` và phát `USER_UNLOCKED`; bỏ qua account đã được login/refresh unlock | 1m / 1m / 500 | số account đã unlock |
| `account-hard-delete` | Hard delete account `DELETED` quá 90 ngày | 6h / 5m / 500 | số account đã xóa |

Thao tác chạy lại thủ công an toàn: mọi worker idempotent và dùng Redis lock, nên khởi động lại process hoặc chạy
thêm replica không gây hại. Chưa có endpoint hay lệnh chạy một worker theo yêu cầu.

## 6. Runbook bắt buộc

Runbook phải bao phủ event publish thất bại và redrive (đặc biệt `USER_DELETED`), cache corruption/outage, database
pool cạn, key-rotation failure, downstream outage và lỗi marker sau commit. Mỗi runbook cần symptom, chẩn đoán chỉ
đọc, mitigation, xác minh recovery và escalation owner. Chưa runbook nào được viết: đây là việc phải làm trước
production.
