# promotion-service

Promotion Service: campaign, PLATFORM_VOUCHER toàn đơn và product markdown. Thiết kế chuẩn
nằm ở [`docs/promotion`](../../../docs/promotion/README.md); contract ở
[`contracts/http/promotion/v1`](../../../contracts/http/promotion/v1/openapi.yaml) và
[`contracts/events/promotion/v1`](../../../contracts/events/promotion/v1/asyncapi.yaml).

## Thành phần

| Package | Vai trò |
|---|---|
| `config` | `PromotionProperties`, `TrustedIdentityFilter` (Gateway user headers, `X-Service-Identity` allowlist), Redis/Kafka/clock, readiness |
| `domain` | Entity JPA cho chín bảng, enum, Rule Engine (`rule/`), discount math và validity policy (`pricing/`) |
| `infrastructure/persistence` | Spring Data repositories + `PromotionJdbc` (guarded update, advisory lock, sequence, DB clock) |
| `infrastructure/redis` | 11 key pattern (`PromotionRedisKeys`), Lua adapters (`ClaimStore`, `ReservationStore`), `RuntimeStore`, `SnapshotCache`, `ScheduleIndex`, `ReconcileLock` |
| `infrastructure/event` | CloudEvent markdown snapshot builder + causal dedup key |
| `infrastructure/kafka` | Consumer `product-deleted.v1` |
| `infrastructure/product` | Port validate Product (`HttpProductCatalogClient` / `NoopProductCatalogClient`) |
| `service` | Admin (campaign/promotion/markdown), discovery/calculate, wallet save/grant, limited claim, allocation TRY/CANCEL, final command CONFIRM/CANCEL_CONFIRMED, markdown snapshot, tombstone, schedule, runtime rebuild/reconcile |
| `worker` | Scheduler loop + DB fallback, pending-claim sweeper, reservation sweeper, receipt finalizer, budget reconciliation, trimmers, startup rebuild |
| `web` | Controllers cho 25 operation OpenAPI, DTO snake_case, RFC 9457 problem+json (route lạ/sai method/Content-Type là lỗi client, không `500`) |

Redis Lua nằm ở `src/main/resources/lua`; Flyway ở `src/main/resources/db/migration` (`V1` baseline, `V2` proof của
legacy technical cancellation);
Debezium connector ở `src/main/resources/kafka`.

## Chạy local

```bash
docker compose up -d postgres redis kafka kafka-connect product-service
```

```bash
docker run --rm -v "$PWD:/repo" -v promotion-mvn-repo:/root/.m2 -w /repo/java/services/promotion-service \
  maven:3.9-eclipse-temurin-21 mvn -q -B -DskipTests package
```

Chạy jar với profile `dev` (`SPRING_PROFILES_ACTIVE=dev`) hoặc `docker compose up promotion-service`.
Đăng ký connector theo `src/main/resources/kafka/README.md`.

## Test

Xem `docs/promotion/development.md` §1 và §3: test gồm unit, Lua trên Redis Testcontainers,
PostgreSQL + Redis integration và HTTP contract MockMvc.

Discovery/suggestion/voucher options duyệt wallet/public bằng keyset đến hết candidate, đọc snapshot và
budget theo batch, giữ top `limit` bằng heap. `promotion.discovery.batch-size` mặc định 200 (env
`PROMOTION_DISCOVERY_BATCH_SIZE`, phải >= 1) chỉ là kích thước batch, không phải trần discovery.
Public query loại ownership trước paging; API vẫn trả tối đa 50 item và không thêm cursor.
`DiscoveryIntegrationTest` kiểm chứng ranking qua 401 public promotion và Redis pipeline thật;
chi tiết quyết định ở [`ADR-020`](../../../docs/promotion/decisions/ADR-020-complete-discovery-with-batched-top-k.md).
