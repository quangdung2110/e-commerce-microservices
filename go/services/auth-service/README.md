# Auth Service

Identity Provider của hệ thống: sở hữu credential, role, session và JWT signing
key. Service không sở hữu profile người dùng.

Implementation này bám theo bộ tài liệu thiết kế và contract:

| Nguồn chuẩn | Nội dung |
|---|---|
| [docs/auth/](../../../docs/auth/README.md) | Thiết kế: domain, workflow, data, operations |
| [docs/auth/schema/0001_initial_auth_schema.sql](../../../docs/auth/schema/0001_initial_auth_schema.sql) | Schema gốc, được sao chép vào [`migrations/`](./migrations) |
| [contracts/http/auth/v1/openapi.yaml](../../../contracts/http/auth/v1/openapi.yaml) | HTTP API public |
| [contracts/http/auth/internal/v1/openapi.yaml](../../../contracts/http/auth/internal/v1/openapi.yaml) | Internal notification delivery và notification contact API (mesh mTLS + allowlist `X-Service-Identity`) |
| [contracts/events/auth/v1/asyncapi.yaml](../../../contracts/events/auth/v1/asyncapi.yaml) | Kafka event (CloudEvents 1.0) |
| [docs/gateway/runtime-contract.md](../../../docs/gateway/runtime-contract.md) | Claim JWT, JWKS và namespace revocation dùng chung với `gateway-auth` |

## 1. Quyết định thiết kế chính

- **Dual token (ADR-001).** Access token là JWT ngắn hạn có `jti`; refresh token
  là opaque secret, chỉ lưu SHA-256 trong PostgreSQL. Thu hồi một thiết bị ghi
  `auth:revocation:denylist:{jti}`; thu hồi toàn bộ ghi
  `auth:revocation:user:{sub}:revoked_before`.
- **Rotation không grace period (ADR-002).** Một refresh token chỉ đổi thành công
  đúng một lần. Toàn bộ thao tác nằm trong một câu lệnh SQL duy nhất, nên hai
  request đồng thời chỉ có một request nhận được token pair mới.
- **JWKS overlap (ADR-003).** Chỉ một key `ACTIVE`; key cũ chuyển `PHASE_OUT` và
  chỉ `RETIRED` sau khi mọi access token nó đã ký hết hạn. Private key được mã
  hóa AES-256-GCM bằng master key cấp qua secret.
- **Redis là trạng thái bảo mật có TTL (ADR-004).** Denylist, revoke marker, rate
  limit, OTP, reset token và notification delivery state. Mọi adapter Redis
  **fail closed**: lỗi Redis làm request thất bại thay vì bỏ qua kiểm tra.
- **Direct publisher (ADR-005, ADR-006).** Event publish sau khi transaction commit, retry
  tối đa 3 lần với cùng CloudEvent `id`; retry cạn được log ở mức `error` để
  reconciliation vận hành xử lý. Không có outbox. Với xóa account, đổi email và đổi/reset
  mật khẩu, event được phát *trước* khi ghi marker Redis, nên lỗi Redis không làm mất event.
- **Ghi account là compare-and-set (ADR-008).** `UPDATE ... WHERE updated_at = `; service
  tải lại và áp dụng lại thay đổi khi thua, nên một lần đăng nhập đang chạy không thể ghi
  mật khẩu cũ hay account đã xóa trở lại.
- **Đăng nhập kiểm mật khẩu trước trạng thái (ADR-009).** `403`/`423` chỉ trả cho người đúng mật
  khẩu. Bên mở khóa (worker, login hoặc refresh) phát `USER_UNLOCKED`.
- **Secret không nằm trong Kafka.** Event chỉ mang `delivery_id`; Notification
  Service gọi internal API để lấy email và OTP/reset URL. Listener internal không tự kết thúc
  TLS: mesh thực thi mTLS và principal, Auth chỉ kiểm allowlist `X-Service-Identity` (ADR-007). Với thông báo
  không do Auth khởi phát, Notification resolve email lúc gửi qua
  `GET /internal/v1/auth/users/{user_id}/notification-contact` trên cùng listener.

## 2. Cấu trúc thư mục

```
auth-service/
├── cmd/server/                  # Entry point và wiring
├── configs/application.yaml     # Cấu hình runtime
├── migrations/                  # Schema (bản sao của docs/auth/schema)
├── internal/
│   ├── api/                     # handler, middleware, router, dto, httpx
│   ├── domain/                  # entity, vo, port, service, event
│   ├── infrastructure/          # postgres, redis, kafka, crypto
│   ├── workers/                 # cleanup, key rotation/retirement, unlock, hard delete
│   └── common/                  # config, errors, constants, clock, keybuilder, ...
└── test/
    ├── unit/                    # Business rule trên in-memory double, không cần Docker
    └── integration/             # Postgres + Redis thật qua testcontainers
```

Quy tắc phụ thuộc: `domain/` không import `infrastructure/`; adapter implement
interface trong `domain/port/`; `api/` chỉ gọi `domain/service`.

## 3. Endpoint

| Method | Path | Auth | Ghi chú |
|---|---|---|---|
| POST | `/api/v1/auth/registrations` | – | `202`, trạng thái `PENDING_VERIFICATION` |
| POST | `/api/v1/auth/email-verification-requests` | – | Luôn `202`, không tiết lộ account tồn tại (vượt ngân sách theo account thì không gửi, vẫn `202`) |
| POST | `/api/v1/auth/email-verifications` | – | `204`, OTP dùng một lần |
| POST | `/api/v1/auth/sessions` | – | Login; access token trong JSON, refresh trong cookie. Sai mật khẩu luôn `401`; `403`/`423` chỉ khi đúng mật khẩu |
| POST | `/api/v1/auth/sessions/refresh` | Cookie `auth_refresh` | Rotate; replay trả `401` |
| GET | `/api/v1/auth/sessions` | Gateway identity | Cursor pagination |
| DELETE | `/api/v1/auth/sessions` | Gateway identity | Logout all + revoke marker |
| DELETE | `/api/v1/auth/sessions/{session_id}` | Gateway identity + `X-Token-Jti` | Denylist `jti` nếu là session hiện tại |
| PUT | `/api/v1/auth/password` | Gateway identity | Revoke toàn bộ session |
| POST | `/api/v1/auth/password-reset-requests` | – | Luôn `202` |
| POST | `/api/v1/auth/password-resets` | – | Reset token single-use |
| POST | `/api/v1/auth/email-change-requests` | Gateway identity | Yêu cầu mật khẩu hiện tại |
| POST | `/api/v1/auth/email-changes/confirmations` | Gateway identity | Đổi email + revoke session |
| POST | `/api/v1/auth/accounts/me/deletion-requests` | Gateway identity | GDPR, anonymize ngay |
| POST | `/api/v1/auth/admin/accounts/{account_id}/deletion-requests` | Gateway identity + role `ADMIN` | Admin deletion |
| GET | `/.well-known/jwks.json` | – | Public key set |
| GET | `/health/live`, `/health/ready`, `/health/startup` | – | Probe |
| POST | `/internal/v1/auth/notification-deliveries/{delivery_id}/content` | `X-Service-Identity` (cổng 8081, mesh mTLS) | Chỉ Notification Service |
| GET | `/internal/v1/auth/users/{user_id}/notification-contact` | `X-Service-Identity` (cổng 8081, mesh mTLS) | Chỉ Notification Service; `email`, `email_verified`, `locale` (null ở phase 1); `404` khi account không tồn tại hoặc đã xoá |

Lỗi luôn trả `application/problem+json` theo RFC 9457 với `code` ổn định.

`X-User-Id`, `X-User-Roles`, `X-Token-Jti` chỉ đáng tin khi Gateway đã loại bỏ
header cùng tên do client gửi. Service không được expose trực tiếp ra edge.

## 4. Chạy local

```bash
cp .env.example .env
```

Sinh master key mã hóa signing key và ghi vào `.env`:

```bash
openssl rand -base64 32
```

Khởi động hạ tầng và service:

```bash
docker compose up -d
```

Chạy trực tiếp bằng Go (Postgres/Redis/Kafka đã chạy sẵn):

```bash
go run ./cmd/server -config ./configs/application.yaml
```

Kiểm tra service đã sẵn sàng:

```bash
curl -s localhost:8080/health/ready
```

Schema được nạp tự động khi volume Postgres còn trống; với database có sẵn dùng
`make migrate-up`.

Mọi key cấu hình có thể override bằng biến môi trường tiền tố `AUTH_`, ví dụ
`AUTH_JWT_ISSUER`, `AUTH_KAFKA_ENABLED=false` để chạy không cần broker.

## 5. Test

Hai tầng test, tách nhau bằng build tag `integration`.

```bash
make test
```

**Unit** (`test/unit/`, 107 test): chạy use case thật trên in-memory double,
không cần Docker. Bao phủ business rule: rotation một lần duy nhất, replay
refresh token, lockout và tự mở khóa (kèm thông báo một lần), OTP single-use, đổi email gắn với địa chỉ
mới, xóa account GDPR với hạn 90 ngày, vòng đời signing key, lỗi Redis sau commit, ghi đè bản cũ (lost update),
không lộ account qua resend/reset/login, listener internal và cấu hình.

```bash
make test-integration
```

**Integration** (`test/integration/`, 61 test): dựng PostgreSQL 16 và Redis 7
bằng testcontainers, nên cần Docker daemon đang chạy. Bao phủ những thứ chỉ
dependency thật mới chứng minh được:

| Nhóm | Nội dung |
|---|---|
| Repository | SQL của cả 3 repo, mapping lỗi unique constraint, transaction rollback, CHECK constraint 90 ngày, compare-and-set của `accounts` (bản cũ không ghi được, không hồi sinh account đã xóa) |
| Rotation | Đổi token, replay, clamp theo absolute expiry, và **8 request đồng thời chỉ 1 thành công** |
| Redis | 4 Lua script: rate limit, OTP (single-use + subject binding + attempt budget), reset token, distributed lock |
| Crypto | Argon2id PHC round trip, AES-256-GCM + master-key version, ký JWT → JWKS → verify như gateway làm |
| HTTP | Toàn bộ workflow WF-AUTH-001…007 qua HTTP thật: status code, problem+json, cookie attribute, denylist `jti`, revoke marker |

Migration được nạp thẳng vào container Postgres, nên schema lệch với repository
sẽ làm fail bộ test.

Khi máy chưa cài Go, cả hai bộ test chạy được trong container:

```bash
docker run --rm -v "$PWD:/src" -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal --add-host=host.docker.internal:host-gateway -w /src golang:1.25-alpine go test -tags integration -count=1 ./...
```

## 6. Cấu hình quan trọng

| Nhóm | Vì sao cần chú ý |
|---|---|
| `jwt.issuer`, `jwt.audience`, `jwt.algorithm` | Phải khớp tuyệt đối cấu hình `gateway-auth` |
| `redis.revocation_prefix` | Namespace `auth:revocation:*` dùng chung; Gateway chỉ có quyền đọc |
| `keys.master_key` | Secret bắt buộc; mất key đồng nghĩa mất toàn bộ signing key |
| `keys.retirement_delay` | Phải ≥ `jwt.access_token_ttl`, nếu không token đang sống sẽ mất key verify |
| `refresh.cookie_secure` | Bắt buộc `true` ngoài môi trường local |
| `security.trusted_proxies` | Rỗng nghĩa là không tin header proxy; khai báo CIDR gateway khi chạy cluster |
| `internal.allowed_callers` | Allowlist `X-Service-Identity` của listener internal (mặc định `notification-service`); rỗng thì không khởi động. Auth không tự kết thúc TLS |

## 7. Khoảng trống đã biết

- **Denylist TTL.** Gateway chỉ chuyển tiếp `jti` đã verify, không kèm `exp`, nên
  entry denylist dùng TTL bằng access TTL + clock skew (15m30s). Đây là cận trên: một
  entry có thể sống lâu hơn token vài phút, tốn bộ nhớ nhưng không bao giờ cho
  token đã thu hồi đi qua.
- **`revoked_before` theo giây.** `iat` và marker đều có độ phân giải một giây và Gateway từ chối
  `iat <= marker`, nên token cấp trong cùng giây với lần thu hồi cũng bị từ chối. Chủ ý: không
  thể phân biệt token cấp ngay trước hay ngay sau lúc thu hồi, và bên an toàn là từ chối cả hai.
- **Root Compose chưa nạp schema Auth.** `init-db.sh` chỉ tạo database `auth_db` rỗng; trên volume
  mới phải nạp `docs/auth/schema/0001_initial_auth_schema.sql` bằng tay (xem
  [development.md](../../../docs/auth/development.md)).
- **Session hiện tại khi revoke.** Gateway luôn chuyển tiếp claim `sid` thành
  `X-User-Session-Id` (runtime-contract mục 6.1), nên đó là đường nhận diện
  chính; cookie `auth_refresh` chỉ còn là fallback khi service được gọi trực
  tiếp trong mạng nội bộ.
- **Mất event sau khi commit.** Theo ADR-005, retry cạn được log `error`; cần
  alert và quy trình reconciliation trước khi lên production.
- **Chưa có metric.** Observability hiện chỉ ở dạng structured log; SLO, dashboard
  và runbook trong [operations.md](../../../docs/auth/operations.md) vẫn là `TBD`.
