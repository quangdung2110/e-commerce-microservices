# Search Service - Implementation Plan Overview

> **Document Version:** 1.0
> **Date:** June 23, 2026
> **Service:** search-service (Java 17 / Spring Boot 3.x / Elasticsearch 8.x)
> **Status:** Implementation Master Plan

---

## 1. Scope & Overview

Search Service là **Read Model thuần túy** trong kiến trúc CQRS. Service consume events từ Kafka (Product, Category, Review) và project vào Elasticsearch. Mọi End-User API (search, suggest, browse, facets) đọc trực tiếp từ ES với latency < 50ms (p99).

### 1.1 Service Responsibilities

- **Search API:** Full-text search + filters + facets cho End-User browsing
- **Suggest API:** Autocomplete / search-as-you-type qua dropdown
- **Category Browsing API:** Cây danh mục với `product_count` cho navigation sidebar
- **Facets API:** Aggregation filters cho category page
- **Internal API:** Full Reindex trigger (admin only, JWT role=ADMIN)
- **Product Indexing Worker:** Kafka consumer (5 threads) → ES indexing
- **Search Suggestions Rebuilder:** Daily atomic rebuild của autocomplete index
- **Draft Cleanup Job:** Off-peak cleanup draft documents
- **Realtime `categories.product_count` Update:** `_update_by_query` arithmetic khi product visibility thay đổi

### 1.2 Technology Stack

| Component | Technology | Version | Ghi chú |
|---|---|---|---|
| Runtime | JDK | 17 (LTS) | Đồng nhất với Product/Review Service |
| Framework | Spring Boot | 3.x | Spring Boot 3 + Jakarta EE 9+ |
| Build Tool | Maven | 3.9+ | Multi-module project structure |
| ES Client | Elasticsearch Java API Client | 8.x | Official, async/reactive |
| Message Broker | Apache Kafka | 3.x | Spring Kafka abstraction |
| Cache | ES query cache (built-in) | - | Không dùng Redis |
| Boilerplate | Lombok | 1.18+ | POJO/DTO/Entity |
| Logging | Logback + Logstash encoder | 1.4+ | JSON cho prod, MDC context |
| Metrics | Micrometer | 1.12+ | Prometheus registry |
| Health Check | Spring Boot Actuator | bundled | Liveness + Readiness |
| JSON | Jackson | 2.15+ | ISO 8601 dates, snake_case |
| Testing | Testcontainers | 1.19+ | ES + Kafka containers |
| OpenAPI | springdoc-openapi | 2.x | OpenAPI 3.0 spec (no Swagger UI) |

### 1.3 Out of Scope (So với Product Service)

- ❌ **PostgreSQL** — Search Service không có DB. ES là nguồn dữ liệu duy nhất.
- ❌ **Redis** — Sử dụng ES internal query cache. Không có cache layer riêng.
- ❌ **gRPC** — Chỉ expose REST API (public + internal). Cart/Order gọi Product Service qua gRPC, không qua Search.
- ❌ **Outbox Pattern** — Search Service là consumer only, không phát event.
- ❌ **State Machine** — Không có business logic nghiệp vụ phức tạp.
- ❌ **Debezium CDC** — Không cần; Kafka consumer đọc trực tiếp từ upstream topics.

---

## 2. Module Dependency Graph

```mermaid
flowchart TD
    subgraph M0["Module 0: Foundation"]
        M00["00-overview-plan"]
    end

    subgraph M1["Module 1: Project Foundation"]
        M01["01-project-foundation"]
        M01 --> M00
    end

    subgraph M2["Module 2: Domain Model"]
        M02["02-domain-model"]
        M02 --> M01
    end

    subgraph M3["Module 3: Elasticsearch Config"]
        M03["03-elasticsearch-config"]
        M03 --> M02
    end

    subgraph M4["Module 4: Search Query Builders"]
        M04["04-search-query-builders"]
        M04 --> M03
        M04 --> M02
    end

    subgraph M5["Module 5: Public API Controllers"]
        M05["05-api-controllers-public"]
        M05 --> M04
        M05 --> M03
    end

    subgraph M6["Module 6: Cursor Pagination"]
        M06["06-cursor-pagination"]
        M06 --> M04
    end

    subgraph M7["Module 7: Kafka Consumer Setup"]
        M07["07-kafka-consumer-setup"]
        M07 --> M02
    end

    subgraph M8["Module 8: Event Handler"]
        M08["08-event-handler"]
        M08 --> M07
        M08 --> M03
    end

    subgraph M9["Module 9: Product Count Worker"]
        M09["09-product-count-worker"]
        M09 --> M08
        M09 --> M03
    end

    subgraph M10["Module 10: Internal API + Reindex"]
        M10["10-internal-api-reindex"]
        M10 --> M03
    end

    subgraph M11["Module 11: Suggestions Rebuilder"]
        M11["11-suggestions-rebuilder"]
        M11 --> M03
    end

    subgraph M12["Module 12: Draft Cleanup Job"]
        M12["12-draft-cleanup-job"]
        M12 --> M03
    end

    subgraph M13["Module 13: Observability & Tests"]
        M13["13-observability-and-tests"]
        M13 --> M01
    end

    subgraph M14["Module 14: Deployment & Operations"]
        M14["14-deployment-and-operations"]
        M14 --> M01
    end
```

### 2.1 Build Order Summary

| Order | Module | Description |
|---|---|---|
| 1 | 01-project-foundation | Maven scaffold, dependencies, base config |
| 2 | 02-domain-model | ES document records, DTOs, error codes |
| 3 | 03-elasticsearch-config | ES client bean, repositories (port/outbound) |
| 4 | 04-search-query-builders | Query DSL builders, filter/sort mapping |
| 5 | 05-api-controllers-public | Public API controllers (products, suggest, categories, facets) |
| 6 | 06-cursor-pagination | CursorCodec (base64 JSON encoding/decoding) |
| 7 | 07-kafka-consumer-setup | Kafka consumer config, concurrency 5, error handling |
| 8 | 08-event-handler | EventHandler dispatch theo event_type |
| 9 | 09-product-count-worker | Realtime update `categories.product_count` |
| 10 | 10-internal-api-reindex | Internal API + 3-pha ReindexJob |
| 11 | 11-suggestions-rebuilder | Daily SuggestionsRebuilder cronjob |
| 12 | 12-draft-cleanup-job | Off-peak DraftCleanupJob |
| 13 | 13-observability-and-tests | Metrics, logging, health checks, Testcontainers tests |
| 14 | 14-deployment-and-operations | Docker, docker-compose, env config |

---

## 3. Key Architecture Decisions

### 3.1 Read Model Pattern (CQRS)

- **Single source of truth:** Elasticsearch (3 indices: products, categories, search_suggestions)
- **Write path:** Kafka consumer `search-product-indexer` (5 threads) → ES `_bulk` + `_update_by_query`
- **Read path:** REST API → ES query (no cache layer ngoài ES internal query cache)
- **Trade-off:** Eventual consistency (1-3s lag so với PostgreSQL)

### 3.2 Guard Strategy (Single Timestamp)

| Event Type | Guard | Note |
|---|---|---|
| `PRODUCT_CREATED` | `updated_at > version` | scripted_upsert |
| `PRODUCT_UPDATED` | `updated_at > version` | partial update |
| `SKU_UPDATED` | `updated_at > version` | partial update |
| `SKU_CREATED` | `created_at > version` | scripted_upsert (entity chưa có updated_at) |
| `PRODUCT_DELETED` / `SKU_DELETED` | `updated_at > version` | tombstone |
| `CATEGORY_*` | `updated_at > version` | scripted_upsert |
| `REVIEW_METRICS_UPDATED` | **Không dùng script guard** (Phase 21) | Chỉ partition ordering theo `product_id` |
| `PRODUCT_STATUS_CHANGED` (qua `SKU_UPDATED.product_status_changed=true`) | **Không dùng script guard** (Phase 19) | Chỉ partition ordering |
| `categories.product_count` update | **Không dùng script guard** | Arithmetic idempotent chấp nhận sai số ±1-2 |

### 3.3 Consumer Configuration

- **Consumer Group:** `search-product-indexer` (1 group chính, gộp 3 topics)
- **Concurrency:** 5 threads (1 thread per partition; 3 partitions product + 1 category + 1 review)
- **Reindex Group:** `search-reindex-{run_id}` (1 group tạm, chỉ tồn tại khi reindex)
- **DLQ:** Không có (Phase 22b). Lỗi không tự hồi phục xử lý bằng `log + metric + skip + ACK`.

### 3.4 Pagination Strategy (Cursor-based)

- **API:** Cursor-based với ES `search_after` + tie-break `sku_id asc`
- **Cursor format:** base64(JSON([sort_value, sku_id]))
- **Max page size:** 100 items
- **Default size:** 20 items
- **Timeout:** 5s cho product search, 2s cho suggest

### 3.5 Authentication

- **Public API:** JWT trust từ API Gateway. Search Service đọc 4 claims: `user_id`, `role`, `request_id`, `session_id`.
- **Internal API:** JWT với `role=ADMIN` từ Gateway. Search Service đọc `role` claim để authorize.
- **Không có shared secret fallback.**

### 3.6 Realtime `categories.product_count` Update

| Trigger | Action | Throttle |
|---|---|---|
| `PRODUCT_CREATED` (status=ACTIVE) | `product_count += 1` | 300 rps, conflicts=proceed |
| `PRODUCT_CREATED` (status=DRAFT) | Không thay đổi | — |
| `SKU_UPDATED.product_status_changed=true → ACTIVE` | `product_count += 1` | 300 rps |
| `SKU_UPDATED.product_status_changed=true → HIDDEN` | `product_count -= 1` | 300 rps |
| `PRODUCT_DELETED` (khi ACTIVE) | `product_count -= 1` | 300 rps |
| `PRODUCT_DELETED` (khi đã HIDDEN) | Không thay đổi | — |
| `CATEGORY_DELETED` | Không thay đổi (audit) | — |

**Sai số chấp nhận được:** ±1-2 do Kafka redeliver arithmetic. Reconcile qua Full Reindex Job.

---

## 4. Package Structure (Java Common Layout)

All code follows the `com.ecommerce.search` base package.

```
com.ecommerce.search/
├── SearchServiceApplication.java
├── config/                    # Spring configs (ES, Kafka, Security, Observability)
├── controller/                # REST controllers
│   ├── public/                # Public API
│   │   ├── ProductSearchController.java    # /api/v1/search/products
│   │   ├── SuggestController.java          # /api/v1/search/suggest
│   │   ├── CategoryController.java         # /api/v1/search/categories
│   │   └── FacetController.java            # /api/v1/search/facets/{id}
│   └── internal/              # Internal API
│       └── ReindexController.java          # /internal/v1/search/reindex
├── service/                   # Application services
│   ├── ProductSearchService.java
│   ├── SuggestService.java
│   ├── CategoryService.java
│   ├── FacetService.java
│   └── ReindexService.java
├── worker/                    # Background workers + cronjobs
│   ├── ProductIndexingWorker.java          # Kafka consumer (5 threads)
│   ├── EventHandler.java                   # dispatch theo event_type
│   ├── ProductCountUpdater.java            # realtime categories.product_count
│   ├── SuggestionRebuilder.java            # daily cronjob
│   ├── DraftCleanupJob.java                # off-peak cronjob
│   └── ReindexJob.java                     # 3-pha zero-downtime
├── repository/                # ES query builders (port/outbound)
│   ├── ProductSearchRepository.java
│   ├── SuggestRepository.java
│   ├── CategoryRepository.java
│   └── es/                    # ES client beans + index operations
│       ├── ElasticsearchClientConfig.java
│       ├── IndexInitializer.java           # tạo index với mapping nếu chưa có
│       └── UpdateByQueryExecutor.java      # _update_by_query wrapper
├── domain/                    # Pure domain (records only, no infrastructure deps)
│   ├── document/              # ES document records
│   │   ├── ProductDocument.java
│   │   ├── CategoryDocument.java
│   │   └── SuggestionDocument.java
│   ├── event/                 # Kafka event types
│   │   ├── CloudEventEnvelope.java
│   │   ├── ProductCreatedEvent.java
│   │   ├── ProductUpdatedEvent.java
│   │   ├── SkuUpdatedEvent.java
│   │   ├── SkuCreatedEvent.java
│   │   ├── ProductDeletedEvent.java
│   │   ├── CategoryCreatedEvent.java
│   │   ├── CategoryUpdatedEvent.java
│   │   ├── CategoryDeletedEvent.java
│   │   └── ReviewMetricsUpdatedEvent.java
│   └── exception/             # Custom exceptions
│       ├── SearchException.java
│       └── InvalidCursorException.java
├── dto/                       # Request/Response DTOs
│   ├── ProductSearchResponse.java
│   ├── SuggestResponse.java
│   ├── CategoryTreeResponse.java
│   ├── FacetResponse.java
│   ├── ReindexTriggerResponse.java
│   ├── ReindexStatusResponse.java
│   └── ErrorResponse.java
├── util/                      # Shared utilities
│   ├── CursorCodec.java                    # base64(JSON([sort_value, sku_id]))
│   ├── JwtClaimUtil.java                   # đọc X-User-Claims header
│   ├── TimeUtils.java
│   └── JsonUtils.java
├── exception/                 # Global exception handler
│   └── GlobalExceptionHandler.java
└── common/                    # Shared constants
    ├── ServiceConstants.java
    ├── ErrorCode.java
    └── EventType.java                       # PRODUCT_CREATED, SKU_UPDATED, etc.
```

> **Domain Layer Rule:** `domain/` package KHÔNG được phụ thuộc vào bất kỳ infrastructure nào (Elasticsearch, Kafka, Spring). Chỉ chứa plain Java records.

---

## 5. Module Files

| # | File | Description |
|---|---|---|
| 1 | `00-overview-plan.md` | This file |
| 2 | `01-project-foundation.md` | Project scaffold, Maven pom.xml, base configs |
| 3 | `02-domain-model.md` | ES document records, DTOs, event types, error codes |
| 4 | `03-elasticsearch-config.md` | ES client bean, repositories, index initialization |
| 5 | `04-search-query-builders.md` | Query DSL builders, filter/sort mapping |
| 6 | `05-api-controllers-public.md` | Public API controllers + DTOs |
| 7 | `06-cursor-pagination.md` | CursorCodec (base64 JSON encoding/decoding) |
| 8 | `07-kafka-consumer-setup.md` | Kafka consumer config, concurrency 5, error handling |
| 9 | `08-event-handler.md` | EventHandler dispatch theo event_type |
| 10 | `09-product-count-worker.md` | Realtime update `categories.product_count` |
| 11 | `10-internal-api-reindex.md` | Internal API + 3-pha ReindexJob |
| 12 | `11-suggestions-rebuilder.md` | Daily SuggestionsRebuilder cronjob |
| 13 | `12-draft-cleanup-job.md` | Off-peak DraftCleanupJob |
| 14 | `13-observability-and-tests.md` | Metrics, logging, health checks, Testcontainers tests |
| 15 | `14-deployment-and-operations.md` | Docker, docker-compose, env config |

---

## 6. Key Design Principles

1. **Read Model First:** Search Service chỉ đọc từ ES. Không có business logic, không có state machine, không có transaction phân tán.
2. **Elasticsearch Single Source:** ES là database duy nhất. Không có PostgreSQL, không có Redis.
3. **Idempotency by Guard:** Mỗi event dùng `version` field (date type) làm guard ordering. Ngoại lệ theo Phase 19/21 cho `REVIEW_METRICS_UPDATED` và `PRODUCT_STATUS_CHANGED-via-SKU_UPDATED` (chỉ partition ordering).
4. **Atomic Reindex:** Suggestions Rebuilder build nguyên tử mỗi ngày (tạo index versioned mới + atomic alias swap). Không cần guard cho index này.
5. **Stateless:** Search Service không lưu state. Scale horizontal dễ dàng. Kafka consumer group rebalance tự động.
6. **JWT Trust:** API Gateway verify, Search Service đọc claims. Không tự validate signature.
7. **Cursor Pagination:** ES `search_after` + tie-break `sku_id asc` đảm bảo pagination ổn định. Cursor là base64(JSON).
8. **Eventual Consistency:** Độ trễ 1-3s so với PostgreSQL chấp nhận được cho use case browsing.
9. **No DLQ (Phase 22b):** Manual recovery qua log + metric + Full Reindex Job. Trade-off: đơn giản hoá infrastructure.
10. **Schema Versioning:** CloudEvents envelope `schema_version = 1`. Additive changes only. `@JsonIgnoreProperties(ignoreUnknown = true)`.

---

## 7. Implementation Effort Estimate

| Module | Effort (person-days) | Risk |
|---|---|---|
| 01-project-foundation | 0.5 | Low |
| 02-domain-model | 1 | Low |
| 03-elasticsearch-config | 1 | Medium |
| 04-search-query-builders | 2 | High (complex query DSL) |
| 05-api-controllers-public | 1.5 | Medium |
| 06-cursor-pagination | 0.5 | Low |
| 07-kafka-consumer-setup | 1 | Medium |
| 08-event-handler | 3 | High (event-specific logic + 11 event types) |
| 09-product-count-worker | 1 | Medium |
| 10-internal-api-reindex | 2 | High (3-pha logic) |
| 11-suggestions-rebuilder | 1 | Medium |
| 12-draft-cleanup-job | 0.5 | Low |
| 13-observability-and-tests | 2 | Medium (Testcontainers setup) |
| 14-deployment-and-operations | 1 | Low (Docker compose) |
| **Total** | **~18 person-days** | — |

---

## 8. Acceptance Criteria (Master)

- [ ] `mvn compile` succeeds without errors
- [ ] `mvn test` passes (unit + integration tests)
- [ ] `mvn package` builds runnable JAR
- [ ] `docker-compose up` starts ES + Kafka + Zookeeper + Search Service
- [ ] Health check endpoints return 200 (liveness + readiness)
- [ ] Public API endpoints return correct responses:
  - [ ] `GET /api/v1/search/products?q=iphone` returns search results
  - [ ] `GET /api/v1/search/suggest?q=iph` returns autocomplete
  - [ ] `GET /api/v1/search/categories` returns category tree
  - [ ] `GET /api/v1/search/facets/{id}` returns facets
- [x] Internal API works:
  - [x] `POST /internal/v1/search/reindex` with JWT ADMIN returns 202 + run_id
  - [x] `GET /internal/v1/search/reindex/{run_id}` returns status
- [ ] Kafka consumer indexes events:
  - [ ] Testcontainers test: produce event → verify ES doc updated
- [ ] Cursor pagination works:
  - [ ] Test: 50 results paginated via cursor returns all 50
- [ ] Observability:
  - [ ] Prometheus metrics exposed at `/actuator/prometheus`
  - [ ] 5 essential metrics work (HTTP, ES query, Kafka lag, indexing, errors)
  - [ ] Logs are JSON format with MDC context
- [ ] Real-time `categories.product_count`:
  - [ ] Test: PRODUCT_CREATED → product_count increases
  - [ ] Test: PRODUCT_DELETED → product_count decreases
