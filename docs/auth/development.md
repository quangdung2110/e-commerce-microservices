---
doc_id: auth-development
service: auth
scope: current
status: implemented
owners:
  - Auth Service team
evidence:
  - go/services/auth-service/go.mod
  - go/services/auth-service/Makefile
  - go/services/auth-service/docker-compose.yaml
  - go/services/auth-service/README.md
  - go/services/auth-service/test
  - docker-compose.yml
verified_against_commit: null
---

# Auth Service — Development Guide

Runtime Go 1.25, Gin, pgx/PostgreSQL, go-redis và Sarama/Kafka nằm ở `go/services/auth-service`. README
của module có bảng endpoint và lệnh chạy; file này ghi những gì cần để phát triển và kiểm thử.

## 1. Prerequisites and local dependencies

- Go 1.25 (hoặc Docker để chạy Go trong container, xem §3), PostgreSQL 16, Redis 7 và Kafka (Kafka có thể tắt
  bằng `AUTH_KAFKA_ENABLED=false`, khi đó event chỉ được log).
- Cổng local: public 8080, internal 8081 (không publish ra host). Root Compose publish public ra `8086`.
- Dependency được mock trong test: Kafka (publisher ghi log hoặc fake), Gateway (header `X-User-*` do test
  tự đặt), Notification (test gọi trực tiếp internal API). PostgreSQL và Redis là thật qua Testcontainers ở integration test.

## 2. Local run and configuration

Từ thư mục service, có hai cách:

```bash
cp .env.example .env            # đặt AUTH_KEYS_MASTER_KEY: openssl rand -base64 32
docker compose up -d            # compose riêng của Auth: Postgres, Redis, Kafka và service
curl -s localhost:8080/health/ready
```

hoặc chạy trực tiếp khi Postgres/Redis/Kafka đã sẵn sàng:

```bash
go run ./cmd/server -config ./configs/application.yaml
```

Biến môi trường bắt buộc: `AUTH_KEYS_MASTER_KEY` (base64 của 32 byte), `DB_PASSWORD`; `REDIS_PASSWORD` chỉ rỗng được
với Redis không đặt mật khẩu. Redis của `docker-compose.yml` gốc bật `requirepass` vì giữ reset token, OTP và danh sách
thu hồi ([mesh ADR-002](../mesh/decisions/ADR-002-local-runtime-exposure-and-broker-auth.md)); mật khẩu dev là
`x-redis-password` trong compose (đổi bằng `REDIS_PASSWORD` trong `.env`), và `docker exec redis redis-cli` tự xác thực.
Kafka của compose bắt SASL/SCRAM ([mesh ADR-003](../mesh/decisions/ADR-003-kafka-sasl-scram-and-acls.md)): `AUTH_KAFKA_SASL_USERNAME`/`AUTH_KAFKA_SASL_PASSWORD`
(principal `auth-service`, chỉ ghi `t.auth.events` và `t.auth.notification.events`); để trống thì kết nối PLAINTEXT.
Mọi key cấu hình override được bằng tiền tố `AUTH_` (xem bảng ở [Operations §1](./operations.md#cấu-hình)). Biến không có
tiền tố và không có default trong `config.go` bị bỏ qua im lặng. Không commit master key, mật khẩu hay file `.env`.
Schema được nạp tự động trong compose riêng của Auth khi volume Postgres còn trống; với database có sẵn dùng
`make migrate-up` (cần golang-migrate).

**Root Compose** (`docker-compose.yml` ở gốc repo): `init-db.sh` chỉ tạo database `auth_db` rỗng, **chưa có bước nạp
schema Auth** (khác `user-migrate`). Trên volume mới phải nạp tay trước khi chạy `auth-service`:

```bash
docker exec -i postgres psql -U postgres -d auth_db -v ON_ERROR_STOP=1 < docs/auth/schema/0001_initial_auth_schema.sql
docker compose up postgres-init      # chuyển chủ sở hữu object vừa nạp sang role auth_service
docker compose up -d --build auth-service gateway-auth
```

Root Compose cho Auth đăng nhập bằng role `auth_service` (chủ `auth_db`), không bằng superuser
([mesh ADR-004](../mesh/decisions/ADR-004-secrets-client-ip-network-split-and-internal-signing.md)); `postgres-init` chạy mỗi lần
`docker compose up` nên bước thứ hai chỉ cần khi stack đã chạy sẵn. Compose cũng đặt `AUTH_INTERNAL_SIGNING_KEY` (dev, chung với
Notification) và `AUTH_SECURITY_TRUSTED_PROXIES`; `app.env: development` trong yaml cho phép master key và signing key dev, môi
trường khác thì không.

Dữ liệu test local: `bash frontend/scripts/create-dev-account.sh` (buyer; `DEV_ADMIN=1` cấp thêm role `ADMIN` bằng
cách ghi vào `auth.account_roles`, theo [seed-admin.sql.example](./schema/seed-admin.sql.example)). Script đọc OTP từ
Redis `auth:delivery:*`.

## 3. Build, quality and tests

Hai tầng test, tách bằng build tag `integration`. Từ thư mục service, khi có Go:

```bash
make test               # unit (go test -race -cover ./...), không cần Docker
make test-integration   # PostgreSQL 16 + Redis 7 qua Testcontainers, cần Docker daemon
```

Khi máy không có Go, chạy trong container (PowerShell, từ thư mục `go/services/auth-service`):

```powershell
docker run --rm -e GOTOOLCHAIN=local -v "${PWD}:/app" -w /app golang:1.25 sh -c "gofmt -l . ; go vet ./... && go test -race -count=1 ./test/unit/..."
docker run --rm -e GOTOOLCHAIN=local -e DOCKER_API_VERSION=1.44 -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -e TESTCONTAINERS_RYUK_DISABLED=true --add-host=host.docker.internal:host-gateway -v /var/run/docker.sock:/var/run/docker.sock -v "${PWD}:/app" -w /app golang:1.25 go test -tags=integration -count=1 -timeout=15m ./test/integration/...
```

Bộ test phủ: rotation đồng thời một winner, replay refresh token, lockout và tự mở khóa, OTP single-use, đổi email gắn với
địa chỉ mới, xóa account GDPR, vòng đời signing key, lỗi Redis sau commit, ghi đè bản cũ (lost update) và đua giữa các
lần lưu, không lộ account qua resend/reset/login, listener internal (allowlist, rate limit theo tài nguyên), cấu hình và
toàn bộ WF-AUTH-001…007 qua HTTP thật. Fixture không chứa production data hay PII. Integration test nạp migration thẳng
vào Postgres nên schema lệch với repository sẽ làm fail bộ test.

## 4. Schema và contract development

Schema ban đầu nằm tại [`schema/0001_initial_auth_schema.sql`](./schema/0001_initial_auth_schema.sql) và phải giống từng
byte với `go/services/auth-service/migrations/0001_initial_auth_schema.up.sql`; admin bootstrap dùng
[`schema/seed-admin.sql.example`](./schema/seed-admin.sql.example). Khi thêm migration: thêm cặp `.up.sql`/`.down.sql` mới,
cập nhật bản trong `docs/auth/schema/`, chạy lại integration test, và cân nhắc backward compatibility với phiên bản đang
chạy. Contract nằm tại [`contracts/http/auth/v1/openapi.yaml`](../../contracts/http/auth/v1/openapi.yaml),
[`contracts/http/auth/internal/v1/openapi.yaml`](../../contracts/http/auth/internal/v1/openapi.yaml) và
[`contracts/events/auth/v1`](../../contracts/events/auth/v1/asyncapi.yaml). Validate bằng `pwsh ./contracts/tools/validate-contracts.ps1` (cần PowerShell 7) như [contracts/README.md](../../contracts/README.md) mô tả; chưa có CI check chống breaking change.

## 5. Debugging and Definition of Done

Debug local dùng log JSON (`AUTH_LOGGING_FORMAT=console` cho dễ đọc) đã redaction: không có email, token hay secret trong log.
Muốn xem OTP/reset URL của một lần gửi, đọc `auth:delivery:{delivery_id}` trong Redis bằng `redis-cli` ở môi trường local;
không bao giờ làm việc đó ở môi trường thật. Một thay đổi hoàn thành khi build/vet/test (unit và integration) pass,
contract/schema/workflow liên quan được cập nhật, compatibility được kiểm tra, ADR được thêm khi có quyết định kiến trúc, và
trạng thái/evidence tài liệu được cập nhật.
