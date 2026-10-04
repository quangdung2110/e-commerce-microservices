# Inventory Service

Implementation của [docs/inventory](../../../docs/inventory/README.md) theo
[ADR-023](../../../docs/inventory/decisions/ADR-023-java-spring-boot-postgresql-runtime.md):
Java 21, Spring Boot 3.3, PostgreSQL 16 (Flyway, SQL tường minh trên đường ghi có khoá),
transactional outbox + export sequencer + Debezium relay.

## Cấu trúc

| Package | Vai trò |
|---|---|
| `domain` | Bucket model, transition table, trạng thái reservation/Refund Scope, allocation policy. Java thuần, không phụ thuộc framework. |
| `command` | `CommandExecutor`: một transaction cho mỗi command theo thứ tự data.md §4 — claim `command_execution`, write guard, savepoint, body, kết quả bền (accepted hoặc durable rejection), retry contention tối đa 2 lần rồi `503 INVENTORY_BUSY`. |
| `store` | Repository SQL: lock theo thứ tự cố định (warehouse → root/lines → scope records → stock position theo `(warehouse_id, sku_id)`). |
| `service` | Workflow (Reserve/Commit/Release/recovery/pre-dispatch release, Refund Scope), ADMIN (warehouse, sellable stock, dispatch, returns), availability read, Search export, CloudEvent outbox. |
| `worker` | Availability projector, warehouse fan-out, readiness publisher, export sequencer có fencing, reconciliation (chỉ phát hiện), retention, backlog/backpressure. |
| `web` | Bốn surface: `/internal/inventory/v1` (Workflow + BFF read), `/api/v1/admin/inventory`, `/exports/inventory/v1`; `IdentityFilter` kiểm `X-Service-Identity` allowlist và `X-User-Roles: ADMIN`. |

Flyway `V1__inventory_baseline.sql` đặt bucket equation và counter của Refund Scope thành
CHECK constraint; `stock_movement` và `audit_entry` có trigger chặn UPDATE/DELETE.

## Cấu hình chính

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | `jdbc:postgresql://localhost:15432/inventory_service` | Primary authoritative store. |
| `INVENTORY_WRITE_FREEZE` | `false` | Công tắc fail-closed thủ công: mọi mutation trả `503 INVENTORY_UNAVAILABLE`. |
| `INVENTORY_OUTBOX_MAX_UNSEQUENCED` | `100000` | Ngưỡng backpressure của outbox chưa được sequence. |
| `WORKERS_ENABLED` | `true` | `false` chạy profile chỉ phục vụ API; worker có thể chạy ở process riêng cùng artifact. |

Allowlist service identity nằm ở `inventory.identity.*` trong `application.yml`.

## Chạy local

`init-db.sh` chỉ chạy khi volume PostgreSQL được tạo lần đầu. Với volume có sẵn:

```bash
docker exec -it postgres psql -U postgres -c "CREATE DATABASE inventory_service"
```

```bash
docker compose up -d --build inventory-service
```

Service lắng nghe cổng host `8094`; ADMIN đi qua gateway tại
`http://localhost:8088/api/v1/admin/inventory`. Đăng ký Debezium connector theo
[src/main/resources/kafka/README.md](src/main/resources/kafka/README.md).

## Test

Unit test domain và integration test (Testcontainers PostgreSQL) bao phủ: Reserve
all-or-nothing/exact-plan, replay và `IDEMPOTENCY_KEY_REUSED`, Commit không bị chặn bởi
`expires_at`, recovery chỉ cho `RESERVATION_EXPIRED`, pre-dispatch release so với
dispatch, hot-SKU không oversell, ADMIN warehouse/receipt/adjustment/reversal, Refund Scope
tới `INSPECTION_COMPLETED`/`CLOSED`/`CLOSED_INCOMPLETE`/`CANCELLED`, quarantine
HOLD/DISPOSE/reversal, BFF read với ngân sách 30 giây, fan-out, readiness, sequencer có
fencing, snapshot/change feed, write freeze và CloudEvent khớp JSON Schema trong
`contracts/`.

```bash
MSYS_NO_PATHCONV=1 docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "//c/Users/ASUS/MyProjects/e-commerce-microservices://repo" -v "inventory-mvn-repo://root/.m2" --add-host=host.docker.internal:host-gateway -e DOCKER_HOST=unix:///var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -e TESTCONTAINERS_RYUK_DISABLED=true -w //repo/java/services/inventory-service maven:3.9-eclipse-temurin-21 mvn -B test '-DargLine=-Dapi.version=1.44'
```

## Ngoài phạm vi code hiện tại

- MFA cho ADMIN là release gate production (ADR-024); chưa có assertion từ Auth/gateway.
- RPO/RTO, load test, failover drill và phê duyệt retention là gate vận hành trong
  [development.md](../../../docs/inventory/development.md).
