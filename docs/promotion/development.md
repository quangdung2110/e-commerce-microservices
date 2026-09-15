---
doc_id: promotion-development
service: promotion
scope: target
status: implemented
owners:
  - Promotion Service team
evidence:
  - docs/promotion/RuleEngineDemo.java
  - contracts/http/promotion/v1/openapi.yaml
verified_against_commit: null
---

# Promotion Service — Hướng dẫn phát triển

> Source module: `java/services/promotion-service` (Maven, package
> `com.platform.promotionservice`). Layout: `common/`, `config/`, `domain/{entity,enums,rule,pricing}`,
> `infrastructure/{persistence,redis,event,kafka,product}`, `service/`, `worker/`, `web/{dto}`.

## 1. Prerequisites và local dependencies

JDK 21, Spring Boot 3.3 (đồng bộ với các service Java khác), Maven, PostgreSQL 16 với
`wal_level=logical`, Redis 7 (standalone trong compose; Lua dùng hash tag nên chuyển Cluster
không đổi script), Kafka và Debezium/Kafka Connect. Product được validate đồng bộ qua
`GET /internal/v1/products/{id}/snapshot` (tắt bằng `promotion.product.validation-enabled=false`
khi chạy độc lập). Checkout/Order chưa có client; test allocation dùng HTTP trực tiếp. Không
cần Auth fixture vì Promotion không consume Auth event.

Build và test khi máy không có JDK/Maven (dùng container Maven, Testcontainers qua Docker socket):

```bash
docker run --rm -v "$PWD:/repo" -v promotion-mvn-repo:/root/.m2 -w /repo/java/services/promotion-service \
  maven:3.9-eclipse-temurin-21 mvn -q -B -DskipTests package
```

```bash
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$PWD:/repo" -v promotion-mvn-repo:/root/.m2 \
  --add-host=host.docker.internal:host-gateway -e DOCKER_HOST=unix:///var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -e TESTCONTAINERS_RYUK_DISABLED=true \
  -w /repo/java/services/promotion-service maven:3.9-eclipse-temurin-21 mvn -B test '-DargLine=-Dapi.version=1.44'
```

Mount repo root (không chỉ thư mục service) để `MarkdownSnapshotContractTest` thấy `contracts/`.
Integration test có thể trỏ vào dependency sẵn có bằng `TEST_DB_URL`, `TEST_REDIS_HOST`/`TEST_REDIS_PORT`.

## 2. Configuration

Profile `dev` trỏ vào PostgreSQL/Redis/Kafka của docker-compose; `prod` nhận env `DB_URL`,
`REDIS_HOST`, `KAFKA_BOOTSTRAP_SERVERS`, `PRODUCT_BASE_URL`. Property nghiệp vụ nằm dưới prefix
`promotion.*` (`PromotionProperties`): `internal.calculation-clients`/`allocation-clients`,
`product.*`, `snapshot.ttl`, `claim.{pending-stale-after,sweeper-interval,max-pool-size,pool-batch-size}`,
`reservation.{business-ttl,cleanup-grace,sweeper-interval}`, `finalizer.{scan-interval,stale-after,batch-size}`,
`schedule.{loop-interval,fallback-interval,batch-size,fallback-lookback}`,
`reconciliation.{interval,recent-window,lock-ttl}`, `outbox.retention`, `receipt.retention`,
`kafka.{product-topic,product-dlq-topic,group-id}`, `workers.enabled`, `kafka.consumer-enabled`.
Mọi time UTC. Wire money dùng `{amount_minor,currency}`; persistence/Redis dùng integer
VND và adapter kiểm `currency=VND` (`MoneyDto.requireVnd`). Secret đến từ environment/secret
store, không commit credential. Không cấu hình `t.promotion.budget.events`.

## 3. Tests bắt buộc

| Loại | Phạm vi |
|---|---|
| Unit | AST operators, missing/empty, integer discount/cap, campaign phase `[start,end)` |
| Domain | validity modes, immutable-definition clone, natural end giữ voucher, customer refund no-op |
| PostgreSQL | chín bảng, Product tombstone watermark/markdown invalidation, command receipt replay/hash/`redis_finalized_at`, wallet transitions, causal outbox/source revision |
| Redis | runtime claim-gate fields time/revision, claim/reserve/cancel, Confirm-Prepare/finalize Lua, integer counters, hash slot |
| Concurrency | 10.000 contender, exact claim boundary, duplicate claim, confirm/cancel/sweep races |
| Schedule | stale revision, delayed START, Redis loss, fallback/rebuild, commit-before-ZREM |
| CDC/Kafka | outbox atomicity/causal dedup, ACTIVE/INACTIVE contract theo `contracts/events/promotion/v1`, Product tombstone consumer, stale revision; chứng minh confirm/`cancel-confirmed`/claim không ghi outbox |
| HTTP | Contract test đủ 24 operation trong OpenAPI; AuthZ, discovery/suggestion/save/claim/calculate, allocation, no refund, stored response/mismatch |
| Reconciliation | expected DB state + valid reservations, one-way repair |
| End-to-end | Publish→runtime claim fields/START/END→Product; Product delete→Promotion invalidation; save→reserve→confirm fence/DB/finalize→cancel-confirmed/repair |

Hiện có trong `src/test/java` (33 test, chạy bằng lệnh ở §1):

| Test class | Bao phủ |
|---|---|
| `RuleEvaluatorTest`, `DomainRulesTest` | AST operators/missing/empty/numeric, validator reject, discount math, phase `[start,end)`, ba validity mode |
| `TrustedIdentityFilterTest` | Admin role, user identity, workload allowlist theo nhóm route |
| `MarkdownSnapshotContractTest` | CloudEvent ACTIVE (percentage/fixed) và INACTIVE hợp lệ với JSON Schema trong `contracts/`; causal dedup key |
| `LuaScriptTest` (Redis Testcontainers) | Claim gate missing/disabled/stale/window, SPOP + pending, reserve replay/mismatch/exhausted, prepare fence, finalize move + no double move, cancel/DRIFT/force, hash tag |
| `VoucherLifecycleIntegrationTest` (PostgreSQL + Redis) | Publish in-window → ACTIVE outbox; pause → ADMIN_DISABLED; immutable definition; discovery/save/suggest/calculate; reserve replay/mismatch; confirm receipt + finalize marker; same-key mismatch; cancel-confirmed SAVED + repair; final command không ghi outbox; refund no-op |
| `ClaimAndTombstoneIntegrationTest` | Pool pre-generate tại publish, gate revision fence, DB commit trước success, replay, OUT_OF_STOCK, pause đóng gate; Redis loss rebuild + sweeper; tombstone watermark/invalidation/stale duplicate/mutation reject/no reverse event; clone identity |
| `HttpContractIntegrationTest` (MockMvc) | 403/401 problem+json, 201 + ETag/Location, 412 revision mismatch, merge-patch, list cursor, save 201/200 no-store, claim mode mismatch, calculate/reserve/confirm/technical-cancel/cancel wire shape, Idempotency-Key mismatch 400 |

Chưa có: load 10.000 contender, race test đa luồng, schedule worker với Redis thật theo thời
gian, Debezium/Kafka E2E, Temporal fixture.

Phải có negative integration test chứng minh `ORDER_REFUNDED` không mutate wallet/budget
và Promotion không yêu cầu `promotion_refund_allocations[]`.

## 4. Rule Engine

`RuleEngineDemo.java` chỉ là executable example. Production phải có JSON Schema,
Jackson sealed hierarchy/Visitor, depth/size limits, numeric safety và golden/property
tests. `rule_schema_version` version hóa AST; `campaigns.revision` chỉ fence optimistic
concurrency/cache/schedule. Published business definition mới phải dùng cloned identity,
không update row cũ. Không cache object AST trong JVM.

## 5. Database migration

Baseline migration tạo:

- `campaigns`
- `promotions`
- `promotion_budgets`
- `coupon_claims`
- `user_coupon_wallets`
- `markdown_rules`
- `catalog_product_tombstones`
- `promotion_command_receipts` (gồm `redis_finalized_at` và partial index
  `WHERE redis_finalized_at IS NULL`)
- `promotion_outbox_events`
- sequence cho markdown `source_revision`
- CHECK, FK, unique/partial unique và query indexes trong `data.md`

`markdown_rules` phải có invalidation overlay (`invalidated_at`, reason,
`source_product_revision`) với all-null/all-non-null CHECK. Nó không biến published
definition thành mutable configuration.

Không tạo `coupon_usages` hoặc khôi phục bảng event-idempotency tổng quát.
`promotion_command_receipts` chỉ phục vụ durable `CONFIRM`/`CANCEL_CONFIRMED` retry và
stored response. Nếu migrate từ prototype cũ, phải backfill current wallet/budget/receipt
state trước khi drop table; không suy diễn customer refund thành technical compensation.

## 6. Redis implementation

- Mọi multi-key promotion Lua dùng `{promotion_id}`.
- Claim Lua dùng Redis TIME, kiểm claim-gate fields trong runtime hash
  (`enabled`/revision/`open <= now < close`), rồi mới ghi claimed-user + pending acquisition
  time/revision atomically với SPOP.
- Runtime claim fields/pool rebuild từ PostgreSQL khi startup/Redis loss; stale gate không
  fail-open.
- Reserve index là per-promotion để cùng cluster slot.
- Confirm-Prepare chỉ fence `RESERVED→CONFIRMING` + extend grace, không move counter.
- Confirm preflight receipt trước Redis; Prepare missing phải recheck receipt trước khi trả
  not-found. Matching `CONFIRMING` cùng payload là Prepare replay.
- Confirm finalizer chỉ move reserved→used khi có matching durable receipt; missing payload
  enqueue reconciliation, không increment mù. Sau khi Lua báo hội tụ mới set
  `redis_finalized_at`; worker quét receipt còn `NULL` và mọi bước phải idempotent.
- Không có Redis-first `cancel-confirmed`; sau DB commit chỉ trigger projection repair rồi
  set `redis_finalized_at`.
- Campaign schedule dùng member chứa revision; worker không destructive pop.
- Amount dùng `HINCRBY`, không `HINCRBYFLOAT`.
- Lua phải versioned, bounded và có structured result codes.

## 7. Outbox/contract generation

Outbox payload là CloudEvent hoàn chỉnh; connector route theo `event_type`/topic và giữ
partition key. Outbox không replay inbound command; command receipt không relay Kafka và là
trigger nội bộ duy nhất cho finalizer/repair. Pin connector/generator version; CI kiểm
schema compatibility và fixture. Kafka contract chuẩn là
[`contracts/events/promotion/v1/asyncapi.yaml`](../../contracts/events/promotion/v1/asyncapi.yaml)
với một message phát `com.ecommerce.promotion.product-markdown-snapshot.v1`: ACTIVE mang
full rule, INACTIVE chỉ mang tombstone reason. Causal dedup key phải chứa
product/campaign/revision/trigger và không chứa resulting state; `source_revision` chỉ
ordering. Không tạo ba delta event legacy, coupon topic hoặc budget-exhausted event/topic.

Consumer `com.ecommerce.catalog.product-deleted.v1` phải guard catalog revision, commit
terminal watermark + invalidation trước ack, serialize với mọi markdown mutation bằng cùng
product advisory lock và không phát INACTIVE ngược lại Product đã xóa.

Promotion HTTP phải generate/validate theo
[`contracts/http/promotion/v1/openapi.yaml`](../../contracts/http/promotion/v1/openapi.yaml).
Contract không được chứa refund endpoint hoặc SKU-level voucher allocation; CI lint
OpenAPI và chạy provider/consumer contract tests trước khi nâng adoption status.

## 8. Test data

Fixture tối thiểu:

- campaign DRAFT/UPCOMING/ACTIVE/ENDED/PAUSED/CANCELLED, clone identity và stale revision;
- voucher percentage/fixed, ba validity mode, limited/public/direct grant;
- runtime claim-gate fields disabled/stale, end−1 ms/end/end+1 ms, processing sau end, `CLAIM_PENDING`,
  DB-commit-before-success, Redis-loss rebuild và clock drift;
- wallet SAVED/USED/EXPIRED, technical compensation còn hạn/hết hạn;
- command receipt confirm/cancel-confirmed, same-key same/different hash và key khác cùng
  business operation; confirm retry sau Redis cleanup và race preflight-miss/finalizer;
- finite/unlimited qty/VND budget, stale/missing reservation;
- markdown nhiều campaign cùng product/cùng revision/state, source revision out-of-order,
  ACTIVE full payload và INACTIVE tombstone;
- Product tombstone duplicate/out-of-order, tombstone-before-mutation/mutation-before-tombstone
  và rule đã invalidated;
- Buyer selected-line hoặc Admin pre-dispatch refund event làm Promotion no-op.

Promotion fixture must treat Order's one lifetime selected-line refund and its amount
as external input: no Promotion call, allocation or mutation is permitted.

## 9. Debugging

- Trace correlation/workflow/event/order/promotion/claim/campaign ID.
- Inspect command receipt (`redis_finalized_at`) + wallet/budget trước Redis counter.
- Inspect runtime claim fields/pending/reservation/schedule bằng read-only command.
- Kiểm tra DB campaign revision/time trước khi sửa ZSET.
- Kiểm tra connector offset/outbox age/Product source revision khi markdown stale.
- Kiểm tra Product tombstone watermark/catalog revision + invalidation overlay trước khi retry.
- Không SADD claim, xóa reservation/outbox/index hoặc sửa counter thủ công ngoài runbook.

## 10. Definition of Done

- Build/lint/unit/integration/concurrency/contract/load tests pass.
- Migration và Redis key/Lua version có rollout/rollback plan.
- OpenAPI v1, AsyncAPI v1 (`contracts/events/promotion/v1`), JSON Schema và examples nhất
  quán; `pwsh ./contracts/tools/validate-contracts.ps1` pass.
- Mọi crash boundary fence–DB receipt/wallet/budget–Redis finalizer/repair–set
  `redis_finalized_at` có replay/reconciliation test.
- Campaign không activate sớm, stale job no-op và fallback hội tụ.
- Claim không SPOP ngoài hard interval/revision; API success chỉ sau DB commit và pending
  worker persist đúng acquisition evidence.
- Published definition chỉ thay bằng clone identity; wallet cũ không đổi rule/expiry.
- Markdown causal dedup không collision, ACTIVE/INACTIVE đúng schema, Product tombstone
  không để dangling effective rule hoặc tạo reverse event loop.
- Không tồn tại budget-exhausted hay coupon producer/topic; sync error và telemetry vẫn
  được test.
- Confirm và technical compensation commit PostgreSQL-first, replay stored response và
  hội tụ Redis qua receipt `redis_finalized_at`; customer refund không thay đổi Promotion.
- Dashboard, alerts, runbooks, CDC retention/watermark và security review hoàn tất.
- Chỉ nâng `implemented/verified` khi có source/test evidence.
