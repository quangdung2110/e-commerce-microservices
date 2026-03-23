# Shipping Service (Java/Spring Boot) — Skeleton theo thiết kế `docs/shipping`

## Mục tiêu triển khai

Service được triển khai theo đúng các tài liệu trong `docs/shipping`, bao gồm:
- Dual write: PostgreSQL (`shipments`, `outbox_events`, `provider_status_mappings`, ...) + ScyllaDB (`shipment_events`).
- Shipment FSM với 8 transitions và mapping status theo từng 3PL provider.
- Adapter/Factory pattern cho GHN, NinjaVan, J&T.
- Webhook async pipeline: API ingest -> Kafka `t.shipping.webhooks.raw` -> worker chuẩn hóa -> outbox.
- Outbox polling worker produce `t.shipping.events`.
- Cron workers: rate card sync, stale shipment detector, old outbox trimmer, dead letter handler.
- Resilience per provider: circuit breaker + connection pool.

## Mapping tài liệu thiết kế

- `docs/shipping/shipping_db_design.md`
  - entities/repositories/adapter đầy đủ cho PostgreSQL + ScyllaDB.
- `docs/shipping/shipping_state_machine.md`
  - enums FSM + domain services + transition guards/retry limits.
- `docs/shipping/shipping_workflow.md`
  - controllers + use-cases orchestration cho calculate/create/cancel/tracking/webhook.
- `docs/shipping/shipping_events.md`
  - event type enums + outbox publisher + internal webhook topic + delayed escalation.
- `docs/shipping/shipping_workers.md`
  - worker classes thực thi (outbox/webhook/rate-card/stale/outbox-trim/dead-letter).
- `docs/shipping/technology_choice.md`
  - Java 21, Spring Boot, Spring State Machine, Resilience4j, Kafka, Redis, Caffeine.

## Cấu trúc package

```text
src/main/java/com/ecommerce/shipping
├── presentation      # API endpoints + request/response DTO
├── application       # use-cases + application services + ports
├── domain            # aggregate models, enums, domain services, adapter contracts
└── infrastructure    # configs, persistence, providers, kafka, workers
```

## Lưu ý tuân thủ

- Mỗi file đều có comment `Theo thiết kế ...` để truy xuất nguồn yêu cầu.
- Đã bám đầy đủ các layer: domain, application orchestration, presentation APIs, workers.

## Test

- Unit tests:
  - `src/test/java/com/ecommerce/shipping/domain/ShipmentStateMachineDomainServiceTest.java`
  - `src/test/java/com/ecommerce/shipping/application/ShippingQuoteApplicationServiceTest.java`
- Integration tests (WebMvc):
  - `src/test/java/com/ecommerce/shipping/integration/ShippingApiIntegrationTest.java`

```bash
mvn test
```

## Docker

- Build image:

```bash
docker build -t shipping-service:local .
```

- Chạy full dependencies + service local:

```bash
docker compose up -d
```

- File chính:
  - `Dockerfile`
  - `docker-compose.yml`

## Build nhanh

```bash
mvn -q -DskipTests compile
```
