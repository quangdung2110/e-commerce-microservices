---
doc_id: user-development
service: user
scope: current
status: implemented
owners:
  - User Service team
evidence:
  - go/services/user-service/go.mod
  - go/services/user-service/Makefile
  - go/services/user-service/cmd/server/main.go
  - go/services/user-service/migrations/0001_initial_user_schema.up.sql
  - docker-compose.yml
verified_against_commit: null
---

# User Service — Hướng dẫn phát triển

Runtime Go 1.25, Gin, pgx/PostgreSQL, Redis và Sarama/Kafka nằm ở
`go/services/user-service`. Profile/address/preferences và checkout snapshot đã có.
[Checkout runbook](../frontend/checkout-local-runbook.md) ghi gate tích hợp riêng.

## 1. Build và test

PowerShell từ **gốc repo**, không cần Go trên host:

```powershell
docker run --rm -e GOTOOLCHAIN=local -v "${PWD}/go/services/user-service:/app" -w /app golang:1.25 go test -race ./test/unit/...
docker run --rm -e GOTOOLCHAIN=local -v "${PWD}/go/services/user-service:/app" -w /app golang:1.25 go vet ./...
docker compose build user-service
```

Đổi dependency: chạy `go mod tidy` bằng Go 1.25, review `go.mod`/`go.sum` và build
Docker cùng module graph. Integration suite dùng PostgreSQL/Redis Testcontainers
riêng và reset fixture; không trỏ vào database ứng dụng:

```powershell
docker run --rm -e GOTOOLCHAIN=local -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -v /var/run/docker.sock:/var/run/docker.sock -v "${PWD}/go/services/user-service:/app" -w /app golang:1.25 go test -tags=integration -count=1 -timeout=15m ./test/integration/...
```

Test kiểm tra ownership, unique default, xóa default bị từ chối, profile bootstrap,
cache fallback, deletion/lifecycle và reconciliation. Contract/load/production
metrics còn thiếu bằng chứng được ghi trong README module.

## 2. Root Compose: frontend và Checkout

```powershell
docker compose up -d --build user-service
```

`user-migrate` dùng `migrate/migrate:v4.18.3` sau PostgreSQL healthy; User chỉ start
khi migration exit 0. Version tracking hoạt động cả khi volume đã tồn tại. Root
`init-db.sh` tạo DB `user_service` trên volume mới; volume cũ thiếu DB cần tạo riêng
DB trước khi migrate. Khi thêm migration hoặc triển khai lại, chạy tường minh:

```powershell
docker compose run --rm user-migrate
```

Root DB: host `localhost:15432`, container `postgres:5432`, database `user_service`.
User không publish host port. Public API 8082 đi qua Gateway
`localhost:8088/api/v1/users/me/*`; internal snapshot 8083 chỉ trong mạng backend.

Schema từng được nạp bằng SQL thủ công mà thiếu `schema_migrations` cần dừng và
đối chiếu schema/version trước khi baseline. Không tự force version, chạy down
migration hoặc xóa volume để bỏ qua lỗi. Root schema rỗng migrate bình thường.

## 3. Standalone stack

Từ thư mục service, `docker compose up -d`: DB `user_db`, PostgreSQL host `5433`,
Redis `6380`, Kafka `9093`, public API host `8082`. Standalone nạp SQL ban đầu qua
initdb trên volume rỗng nhưng chưa có version tracking; không chạy lại migration
đầu tiên khi chưa baseline. `make migrate-up DB_URL=...` dành cho DB golang-migrate
quản lý; mặc định host standalone là `localhost:5433/user_db`. Root dùng mục 2.

Chạy `make run` trên host cần override DB/Redis/Kafka theo stack; Kafka advertised
listener phải truy cập được từ process host.

## 4. Config và buyer

Mọi key trong `configs/application.yaml` override qua `USER_`; secret lấy từ
`DB_PASSWORD`, `REDIS_PASSWORD`. Env thay đổi cần recreate process. Production
bắt buộc mTLS cho internal API; root dev dùng mạng nội bộ.

| Biến root stack | Giá trị / tác dụng |
|---|---|
| USER_DATABASE_HOST / USER_DATABASE_NAME | postgres / user_service |
| USER_REDIS_HOST | redis |
| USER_KAFKA_BROKERS | kafka:9092 |
| USER_KAFKA_CONSUMER_ENABLED | Bật consumer để bootstrap profile từ Auth |
| USER_INTERNAL_ENABLED | Bật checkout snapshot trên cổng nội bộ 8083 |

Buyer phải qua Auth/Gateway. Consumer xử lý `USER_VERIFIED_SUCCESS` tạo profile
bằng Auth user_id; login không tự tạo profile. Kiểm tra profile/addresses qua phiên
thật, tạo địa chỉ hợp lệ rồi Preview. Account cũ thiếu profile: kiểm tra retention,
consumer group/offset trước khi replay được hỗ trợ; không giả identity hay chèn row.

## 5. Health, debug và giới hạn

`/health/live`: process. `/health/ready` và `/health/startup`: probes runtime;
PostgreSQL required, Redis optional/degraded. Health không chứng minh buyer đã được
bootstrap; đọc log consumer và dữ liệu đã lưu. Xem [README module](../../go/services/user-service/README.md)
cho metrics/contract test còn thiếu. Không log phone/address/credential/full event.
Schema chuẩn nằm trong migration/OpenAPI; thay đổi phải đồng bộ code và tài liệu.
