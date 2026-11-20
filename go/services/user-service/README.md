# User Service

Bounded context sở hữu **profile**, **address book** và **preferences** của khách
hàng sau khi account đã được Auth Service xác minh. Service không sở hữu email,
credential, role hay trạng thái identity.

Implementation này bám theo [thiết kế trong `docs/user`](../../../docs/user/README.md)
và hai contract chuẩn:

- HTTP: [`contracts/http/user/v1/openapi.yaml`](../../../contracts/http/user/v1/openapi.yaml)
- Event: [`contracts/events/user/v1/asyncapi.yaml`](../../../contracts/events/user/v1/asyncapi.yaml)
  và [`contracts/events/auth/v1/asyncapi.yaml`](../../../contracts/events/auth/v1/asyncapi.yaml) (đầu vào)

## 1. Thành phần trong một binary

`cmd/server` chạy bốn thành phần, mỗi thành phần bật/tắt riêng bằng config để có
thể scale độc lập mà vẫn dùng chung một image:

| Thành phần | Bật bằng | Nhiệm vụ |
|---|---|---|
| Public API (`:8082`) | `app.api_enabled` | Profile, address, preferences của chính chủ |
| Internal API (`:8083`) | `internal.enabled` | `checkout-snapshot` cho Checkout; mesh thực thi mTLS, service kiểm allowlist `X-Service-Identity` |
| Event consumer | `kafka.consumer_enabled` | `USER_VERIFIED_SUCCESS`, `USER_DELETED` (`t.auth.events`) và `complaint-received` (`t.notification.outcomes.v1`) |
| Workers | `workers.*.enabled` | `user-retention-purge`, `user-projection-reconciliation` |

Ví dụ: một deployment chạy API-only đặt `USER_KAFKA_CONSUMER_ENABLED=false` và
tắt hai worker; một deployment worker-only đặt `USER_APP_API_ENABLED=false`.

## 2. Cấu trúc mã nguồn

```
cmd/server           điểm vào, wiring và graceful shutdown
internal/api         adapter HTTP: router, handler, DTO, middleware, problem+json
internal/common      config, logging, clock, error vocabulary, Redis key builder
internal/domain      entity, value object, port, use case (không phụ thuộc transport)
internal/infrastructure  PostgreSQL, Redis, Kafka adapter
internal/workers     scheduler có Redis lock + hai worker
migrations           schema "user" (nguồn chuẩn của dữ liệu)
test/unit            use case + entity với in-memory double (không cần Docker)
test/integration     PostgreSQL/Redis thật qua testcontainers
```

Quy tắc phụ thuộc: `api → domain ← infrastructure`. Domain không import gin,
pgx, sarama hay redis; adapter không chứa business rule.

## 3. Quyết định thiết kế được phản ánh trong code

| Quyết định | Nơi thể hiện |
|---|---|
| [ADR-001](../../../docs/user/decisions/ADR-001-profile-bootstrap-from-auth-event.md) profile bootstrap từ event Auth | `LifecycleService.Bootstrap`, insert `ON CONFLICT DO NOTHING`, id lấy từ `user_id` |
| [ADR-002](../../../docs/user/decisions/ADR-002-address-cache-aside.md) cache-aside chỉ cho address | `redis.AddressCache`, key `user:caches:addresses:{user_id}`, TTL 12h / empty 15m, đọc lỗi → fallback DB |
| [ADR-003](../../../docs/user/decisions/ADR-003-default-address-and-hard-delete.md) một default + hard delete | advisory lock theo user, partial unique index, `EnsureDeletable(count)` trả 409 khi còn address khác |
| [ADR-004](../../../docs/user/decisions/ADR-004-direct-event-publication.md) direct publish sau commit | `kafka.Publisher` retry 3 lần cùng CloudEvent id; `EventEmitter` không làm fail request |
| [ADR-005](../../../docs/user/decisions/ADR-005-gdpr-two-stage-deletion.md) anonymize + purge hai giai đoạn | `LifecycleService.Anonymize` / `PurgeDue`, bảng `user_deletions` |
| [ADR-006](../../../docs/user/decisions/ADR-006-auth-owns-account-deletion-event.md) Auth sở hữu event xóa | service **không** publish `USER_DELETED`; `t.user.events` chỉ có 2 event |
| [ADR-007](../../../docs/user/decisions/ADR-007-projection-reconciliation-without-outbox.md) reconciliation không outbox | `ReconciliationService`, cursor chỉ trong bộ nhớ, CloudEvent id mới, `updated_at` giữ nguyên |
| [ADR-008](../../../docs/user/decisions/ADR-008-internal-caller-auth-delegated-to-mesh.md) cổng internal xác thực qua mesh | `middleware.ServiceIdentity` (allowlist `internal.allowed_callers`), `RateLimitByServiceUser`; không còn TLS ở app |
| [ADR-009](../../../docs/user/decisions/ADR-009-marketing-opt-in-and-complaint-opt-out.md) marketing opt-in + complaint | `NewDefaultPreferences`, migration `0002`, `PreferencesService.DisableMarketing`, `eventHandler.handleNotification` |
| [ADR-010](../../../docs/user/decisions/ADR-010-deleted-account-invisible-to-owner.md) account đã xóa vô hình với owner | `ProfileRepository.GetActive`/`GetActiveForUpdate`, `LockUser` trong `Anonymize` |
| [ADR-011](../../../docs/user/decisions/ADR-011-address-cache-invalidation-epoch.md) epoch chống refill muộn | `AddressCache.Epoch`/`Put`/`Invalidate` (script Lua) |
| [ADR-012](../../../docs/user/decisions/ADR-012-delete-last-address.md) xóa address duy nhất | `Address.EnsureDeletable(addressesInBook)` |

## 4. Chạy local

**Root stack/frontend:** từ gốc repo chạy `docker compose up -d --build user-service`.
Compose chạy `user-migrate` và chỉ start User khi migration exit 0. Khi bổ sung
migration: `docker compose run --rm user-migrate`. Root stack dùng DB `user_service`,
PostgreSQL host `15432`; public User chỉ qua Gateway `8088`, internal giữ `8083`.
Xem [development runbook](../../../docs/user/development.md) về volume cũ, profile
bootstrap và test bằng container.

**Standalone:** các lệnh dưới đây chạy từ thư mục service, dùng DB `user_db`:

```bash
cp .env.example .env
```

```bash
docker compose up -d
```

Compose publish PostgreSQL ở `5433`, Redis ở `6380`, Kafka ở `9093` để chạy song
song với `auth-service`. Hai migration (`0001`, `0002`) được nạp tự động, theo thứ tự, khi volume còn rỗng.

Chạy trực tiếp bằng Go (cần PostgreSQL/Redis/Kafka sẵn sàng):

```bash
make run
```

Áp dụng migration bằng golang-migrate trên DB đã được version tracking quản lý:

```bash
make migrate-up DB_URL="postgres://postgres:postgres@localhost:5433/user_db?sslmode=disable"
```

Standalone initdb đã nạp schema trên volume rỗng nhưng chưa có version tracking;
cần đối chiếu/baseline trước khi chuyển sang golang-migrate. Không chạy lại SQL ban
đầu hay tự force version trên dữ liệu hiện hữu. Root stack dùng `user-migrate`.

Kiểm tra service standalone đã sẵn sàng:

```bash
curl -s localhost:8082/health/ready
```

Kiểm chứng buyer qua phiên Auth/Gateway thật tại `/api/v1/users/me/profile` và
`/api/v1/users/me/addresses`. Profile phải được consumer bootstrap từ event Auth;
header identity tự đặt hoặc GET health không chứng minh buyer đã sẵn sàng checkout.

## 5. Kiểm thử

```bash
make test
```

```bash
make test-integration
```

`make test` chạy `./...` (use case, entity, config, event và handler của consumer Kafka với
in-memory double), không cần Docker.
`make test-integration` khởi động PostgreSQL và Redis bằng testcontainers nên cần
Docker daemon; suite này phủ SQL, check constraint, partial unique index, cache
serialization và toàn bộ bề mặt HTTP.

Các tình huống bắt buộc trong [development.md](../../../docs/user/development.md)
được phủ ở: ownership address, partial unique default, từ chối xóa default,
profile id lấy từ Auth event, cache hit/miss/invalidation/fallback, duplicate và
out-of-order Auth event, deletion retention, purge trước/sau deadline,
`SKIP LOCKED` với hai run đồng thời, reconciliation loại user đã xóa và giữ
nguyên `updated_at`.

## 6. Cấu hình

Mọi key trong [`configs/application.yaml`](configs/application.yaml) đều override
được bằng biến môi trường tiền tố `USER_`, ví dụ `USER_APP_PORT`,
`USER_KAFKA_BROKERS`, `USER_CACHE_ADDRESS_TTL`. Secret (`DB_PASSWORD`,
`REDIS_PASSWORD`, đường dẫn TLS) lấy từ environment, không commit.

Config được validate lúc khởi động: TTL cache, topic của cả hai nguồn event khi consumer
bật, giờ chạy reconciliation, và allowlist `internal.allowed_callers` không rỗng khi
internal API bật. Service không đòi chứng chỉ TLS: mTLS do service mesh thực thi.

## 7. Giới hạn đã biết

- Chưa export metric Prometheus; observability hiện dựa trên structured log
  (result, duration, retry, DLQ, cache fallback, oldest due record). Danh sách
  metric trong [operations.md](../../../docs/user/operations.md) chưa được đáp
  ứng đầy đủ.
- Chưa có contract test tự động chạy OpenAPI/AsyncAPI schema với
  request/response thật; integration test đang kiểm tra thủ công theo contract.
- Chưa có single-flight/lock chống cache stampede khi nhiều request cùng miss —
  đúng như phần rủi ro còn để mở trong WF-USER-004. Epoch ([ADR-011](../../../docs/user/decisions/ADR-011-address-cache-invalidation-epoch.md))
  chỉ chặn fill muộn sau invalidation.
- Validation mã địa giới chỉ kiểm tra format; quan hệ province/district/ward cần
  master-data contract riêng.
- Service đặt trần 50 address cho một profile (`constants.AddressBookLimit`) vì
  `GET /addresses` không phân trang trong contract v1; OpenAPI có `maxItems: 50` và
  vượt trần là `422 INVALID_ADDRESS`. Đổi trần thì sửa cả hai.
- Backfill của migration `0002` chỉ tắt marketing của các row owner chưa từng chạm vào;
  user chỉ đổi cờ email/in-app giữ `marketing_enabled=true` (xem ADR-009).
- Auth và Notification vẫn xác thực cổng internal bằng mTLS ở tầng ứng dụng; User đã
  chuyển sang mô hình mesh (ADR-008).
- Chưa có test race song song trên PostgreSQL cho create-address đồng thời với
  anonymization (cơ chế là advisory lock, được kiểm bằng unit test theo thứ tự gọi).
