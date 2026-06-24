# Inventory Service

Single source of truth cho tồn kho vật lý trong hệ thống e-commerce. Quản lý toàn bộ vòng đời biến động tồn kho: reserve, deduct, release, restock, return, đồng thời phát route plan fulfillment cho Checkout.

## Tech Stack

| Component | Technology |
|---|---|
| Runtime | JDK 21 |
| Framework | Spring Boot 3.3.0 |
| Build | Maven (wrapper included) |
| Database | PostgreSQL 16+ (Flyway migrations) |
| Cache / Stream | Redis Cluster 7.x (Lettuce) |
| Message Broker | Apache Kafka 3.7 |
| Orchestration | Temporal Java SDK 1.35 (Worker only — workflow host: Order Service) |
| Resilience | Resilience4j 2.2 |
| Observability | Micrometer + Prometheus + OpenTelemetry |
| Testing | JUnit 5 + TestContainers (Postgres + Redis Cluster + Kafka) + ArchUnit |

## Ports & Profiles

- HTTP port: **8083**
- Profiles: `dev` (default) / `test` / `prod`
- Actuator: `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/metrics`

## Layout (Hexagonal)

```
com.platform.inventoryservice
├── InventoryServiceApplication.java
├── config/                     Spring configs (Redis/Kafka/JPA/Security/Properties)
├── common/                     CorrelationId, ErrorCode, Idempotency interceptor
├── controller/                 REST controllers (M13)
├── dto/                        Request/Response DTOs
├── service/                    Admin services (M12)
├── routing/                    Warehouse routing engine (M10)
├── temporal/                   Temporal Activities (M11)
├── worker/relay, ledger/       Background workers (M09)
├── domain/                     Pure POJO domain — entity, vo, enums, event, exception
├── port/                       Inbound + outbound port interfaces
├── infrastructure/             Adapters (JPA, Redis, Kafka, Outbox)
├── scheduled/                  Cron jobs (M14)
```

## Build & Run

```bash
# Compile + test
./mvnw -B -ntp test

# Package
./mvnw -B -ntp -DskipTests package

# Run locally (profile dev)
./mvnw spring-boot:run

# Docker build
docker build -t inventory-service:dev .
docker run -p 8083:8083 -e SPRING_PROFILES_ACTIVE=prod inventory-service:dev
```

## Design Documents

Tất cả tài liệu thiết kế ở [`docs/inventory/`](../../../docs/inventory/):

- [`00-overview-plan.md`](../../../docs/inventory/plan/00-overview-plan.md) — Master plan 15 modules
- [`01-project-foundation.md`](../../../docs/inventory/plan/01-project-foundation.md) — Module này
- `inventory_db_design.md` — Schema 5 bảng + indexes
- `inventory_redis_design.md` — Redis key patterns + Lua scripts
- `inventory_endpoints.md` — REST API surface
- `inventory_events.md` — CloudEvents payloads
- `inventory_workflow.md` — Saga flows
- `smart_routing_engine.md` — Warehouse routing algorithm
- `inventory_workers.md` — Background workers