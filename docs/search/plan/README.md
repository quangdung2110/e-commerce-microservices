# Search Service - Implementation Plan

> **Status:** Planning Complete
> **Last Updated:** June 23, 2026
> **Service:** search-service (Java 17 / Spring Boot 3.x / Elasticsearch 8.x)
> **Total Modules:** 14
> **Total Effort:** ~14 person-days

---

## Quick Start

1. Đọc [00-overview-plan.md](00-overview-plan.md) để hiểu tổng quan kiến trúc.
2. Đọc [01-project-foundation.md](01-project-foundation.md) → [14-deployment-and-operations.md](14-deployment-and-operations.md) theo thứ tự.
3. Implement theo thứ tự build order (xem [00-overview-plan.md §2.1](00-overview-plan.md#21-build-order-summary)).

## Module Index

| # | File | Description | Effort (person-days) |
|---|---|---|---|
| 00 | [00-overview-plan.md](00-overview-plan.md) | Master plan: scope, tech stack, package structure, build order | — |
| 01 | [01-project-foundation.md](01-project-foundation.md) | Maven scaffold, Spring Boot 3, base configs | 0.5 |
| 02 | [02-domain-model.md](02-domain-model.md) | ES document records, DTOs, event types, error codes | 1 |
| 03 | [03-elasticsearch-config.md](03-elasticsearch-config.md) | ES client bean, repositories, index initialization | 1 |
| 04 | [04-search-query-builders.md](04-search-query-builders.md) | Query DSL builders, filter/sort mapping | 2 |
| 05 | [05-api-controllers-public.md](05-api-controllers-public.md) | Public API controllers + services | 1.5 |
| 06 | [06-cursor-pagination.md](06-cursor-pagination.md) | CursorCodec (base64 JSON encoding/decoding) | 0.5 |
| 07 | [07-kafka-consumer-setup.md](07-kafka-consumer-setup.md) | Kafka consumer config, concurrency 5, error handling | 1 |
| 08 | [08-event-handler.md](08-event-handler.md) | EventHandler dispatch theo event_type | 3 |
| 09 | [09-product-count-worker.md](09-product-count-worker.md) | Realtime update `categories.product_count` | 1 |
| 10 | [10-internal-api-reindex.md](10-internal-api-reindex.md) | Internal API + 3-pha ReindexJob | 2 |
| 11 | [11-suggestions-rebuilder.md](11-suggestions-rebuilder.md) | Daily SuggestionsRebuilder cronjob | 1 |
| 12 | [12-draft-cleanup-job.md](12-draft-cleanup-job.md) | Off-peak DraftCleanupJob + TombstonePurger | 0.5 |
| 13 | [13-observability-and-tests.md](13-observability-and-tests.md) | Metrics, logging, health checks, Testcontainers tests | 2 |
| 14 | [14-deployment-and-operations.md](14-deployment-and-operations.md) | Docker, docker-compose, env config, runbook | 1 |
| **Total** | | | **~17 person-days** |

## Related Documents

### Design (đã complete 100%)

- [search_architecture_recommendations.md](../search_architecture_recommendations.md) — 13 quyết định kiến trúc
- [search_elasticsearch_schema.md](../search_elasticsearch_schema.md) — ES mapping + index design
- [search_events.md](../search_events.md) — 9 event types + idempotency/ordering
- [search_workers.md](../search_workers.md) — 4 workers (consumer + 3 cronjob)
- [search_workflow.md](../search_workflow.md) — 6 API endpoints + pagination
- [search_service_overview.md](../search_service_overview.md) — Tech stack, deployment, observability

### Cross-Service

- [Product Service - Events](../product/product_events.md) — Source of events for `t.product.events`, `t.category.events`
- [Review Service - Events](../review/review_events.md) — Source of events for `t.review.events`
- [CHANGELOG §Phase 22b Search](../_contracts/CHANGELOG.md#phase-22b--search-service--remove-dlq) — DLQ removal decision
- [Service Contract Matrix (SSOT)](../_contracts/CONTRACT_MATRIX.md) — Canonical event envelope

## Build Order

1. **Module 01** → Project scaffold (Maven, Java 17, Spring Boot 3)
2. **Module 02** → Domain layer (ES records, DTOs, events)
3. **Module 03** → ES client + repositories
4. **Module 04** → Query builders
5. **Module 05** → Public API controllers + services
6. **Module 06** → CursorCodec (independent util)
7. **Module 07** → Kafka consumer setup
8. **Module 08** → Event handler (9 event types)
9. **Module 09** → Product count worker
10. **Module 10** → Internal API + reindex
11. **Module 11** → Suggestions rebuilder
12. **Module 12** → Draft cleanup + tombstone purger
13. **Module 13** → Observability + tests
14. **Module 14** → Deployment + operations

## Acceptance Criteria (Master)

- [ ] All 14 modules implemented theo plan
- [ ] `mvn compile` succeeds
- [ ] `mvn test` passes (unit tests)
- [ ] `mvn verify` passes (Testcontainers integration tests)
- [ ] `mvn package` produces runnable JAR
- [ ] `docker-compose up` starts full stack
- [ ] All public API endpoints work end-to-end
- [ ] Internal API reindex works
- [ ] Cronjobs run on schedule
- [ ] Health checks work
- [ ] Metrics exposed at `/actuator/prometheus`
- [ ] Logs are JSON in prod with MDC context
- [ ] No credentials in source code

## Status Tracking

Khi implement mỗi module, update `[ ]` → `[x]` cho từng task. Cuối cùng update status thành "✅ COMPLETED" trong header của file đó.

## Notes

- Search Service là **Read Model thuần túy** — không có business logic, không có state machine, không có transaction phân tán.
- Tất cả async work thông qua Kafka consumer + 3 cronjob.
- Deployment dùng Docker (personal project, không cần K8s).
- Tech stack: Java 17 + Spring Boot 3 + Elasticsearch 8.x + Kafka 3.x.
- Authentication: JWT trust từ API Gateway (4 claims: user_id, role, request_id, session_id).
- Error handling: Phase 22b — no DLQ. Manual recovery via log + metric + Full Reindex Job.
