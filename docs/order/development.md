---
doc_id: order-development
service: order
scope: target
status: implemented
owners:
  - Order Service team
evidence:
  - java/services/order-service/src/main/java/com/ecommerce/order
verified_against_commit: null
---

# Order Service — Hướng dẫn phát triển

> Hướng dẫn build, test và chạy local cho `java/services/order-service`. Checklist kiểm thử
> ở §5 giữ nguyên làm acceptance; bảng "Hiện trạng" cho biết phần nào đã có test tự động.

## 1. Điều kiện tiên quyết

Java 21 + Maven 3.9 (hoặc Docker với image `maven:3.9-eclipse-temurin-21`), Docker cho
Testcontainers, PostgreSQL 16 (`pg_trgm`), Kafka + Kafka Connect (`debezium/connect:3.0.0.Final`),
Redis 7 (tùy chọn) và Temporal server 1.25 (`temporalio/auto-setup:1.25.2`) + UI.
Runtime/framework đã chốt ở [ADR-020](./decisions/ADR-020-release-1-implementation-clarifications.md):
Spring Boot 3.3, JDBC + Flyway, Spring Kafka, Temporal Java SDK 1.25.1.

| Thư mục | Nội dung |
|---|---|
| `src/main/java/com/ecommerce/order/web` | Controller public/internal, `IdentityFilter`, problem+json |
| `.../service` | Saga activities (`CheckoutSaga`, `RefundSaga`, `ScopeFinalization`), command/query/search, outbox/notification, reconciliation |
| `.../workflow` | Bốn Temporal workflow + `OrderActivities` |
| `.../messaging` | Payment/Inventory intake (durable inbox), Kafka listeners, receipt observer |
| `.../worker` | Workflow dispatcher (SignalWithStart), closed-target resolver, reconcilers, trimmer |
| `.../store` | SQL tường minh, row lock/CAS |
| `src/main/resources/db/migration` | V1 baseline, V2 Admin delivery confirmation, V3 submission checkpoint NOTIFY trigger |
| `src/main/resources/kafka` | Debezium connector multi-destination outbox |

## 2. Chạy local và dependency

Stack compose ở root đã có `temporal`, `temporal-ui` (http://localhost:8233) và
`order-service` (http://localhost:8095, Traefik route `/api/v1/orders/**`,
`/api/v1/admin/orders/**`, `/api/v1/order-operations/**`):

```bash
docker compose up -d postgres redis zookeeper kafka kafka-connect temporal temporal-ui
```

Volume Postgres đã khởi tạo trước khi có Order thì `init-db.sh` không chạy lại; tạo DB một lần:

```bash
docker compose exec postgres psql -U postgres -c "CREATE DATABASE order_service"
```

```bash
docker compose up -d --build order-service inventory-service promotion-service
```

Payment chạy theo profile riêng (`docker compose --profile payment up -d payment-service`).
Đăng ký Debezium connector theo `src/main/resources/kafka/README.md`. Checkout có runtime
(`docker compose up -d checkout-service`, [checkout/development.md](../checkout/development.md)); khi
chỉ thử Order, vẫn có thể gọi `POST /internal/v1/checkout-order-submissions` thủ công với
`X-Service-Identity: checkout-service` trên service network/cổng 8095. Không có Delivery service: để
thử hoàn tất đơn, ghi physical dispatch ở Inventory Admin rồi gọi
`POST /api/v1/admin/orders/{order_id}/delivery-confirmations` (nút "Xác nhận đã giao" ở trang chi tiết đơn
của Admin, [ADR-021](./decisions/ADR-021-admin-delivery-confirmation.md)).

## 3. Cấu hình local

Toàn bộ property/env và default nằm ở [operations.md §2](./operations.md#2-cấu-hình).
Không có secret Stripe trong Order. Tắt thành phần khi debug: `TEMPORAL_WORKER_ENABLED=false`,
`ORDER_CONSUMERS_ENABLED=false`, `WORKERS_ENABLED=false`, `ORDER_REDIS_MIRROR_ENABLED=false`.

## 4. Build, lint và test

Build (không cần JDK cài sẵn):

```bash
docker run --rm -v "$PWD/java/services/order-service:/app" -v order-mvn-repo:/root/.m2 -w /app maven:3.9-eclipse-temurin-21 mvn -B -DskipTests package
```

Toàn bộ test (unit + integration Testcontainers + Temporal in-memory; mount repo root để
contract test đọc `contracts/`; trên Docker Engine mới cần ghim API version):

```bash
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$PWD:/repo" -v order-mvn-repo:/root/.m2 --add-host=host.docker.internal:host-gateway -e DOCKER_HOST=unix:///var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -e TESTCONTAINERS_RYUK_DISABLED=true -w /repo/java/services/order-service maven:3.9-eclipse-temurin-21 mvn -B test -DargLine=-Dapi.version=1.44
```

Có `TEST_DB_URL` thì test dùng PostgreSQL đó thay cho Testcontainers. Surefire chạy cả
`*Test` lẫn `*IT`.

### Hiện trạng kiểm thử tự động

| Nhóm | Test |
|---|---|
| Domain | `DomainRulesTest` (largest remainder, normalizer `đ`, tab/ui status, cancel reason mapping, JCS), `CheckoutRulesTest` (hash, invariants), `HttpRemoteTest` (classify unknown/rejected) |
| Checkout/payment | `CheckoutFlowIT`: happy path tới `COMPLETED` (dispatch rồi Admin delivery confirmation), replay/409, Promotion `409 PROMOTION_RECALCULATION_NEEDED`, Inventory `REJECT_ALL`, deadline cancel + capture-after-terminal, Buyer cancel idempotent, capture thắng cancel, FAILED retry attempt, duplicate/stale fact, Admin delivery confirmation (cần dispatch trước, 403/400/422/404, all-or-nothing, replay/409, đúng một `order.completed.v1`, gauge quá hạn), post-capture repair + manual fulfilment |
| Refund | `RefundFlowIT`: return một lần + refund đúng một lần + close `REFUNDED`, scope sai/chưa giao, refund fail + external settlement, reversal sau success, timeout 30 ngày + no-payout close-incomplete, Admin pre-dispatch reject/accept, finalization `RECOVERY_REQUIRED` rồi hoàn tất |
| Read/search | `QueryIT`: owner/404/admin/403/401, card/keyset/`q` không dấu |
| Contract | Response HTTP validate với `contracts/http/order/**` (OpenAPI 3.1 components); outbox validate với `order-paid`, `order-completed`, `notification-requested` JSON Schema |
| Migration | `DeliveryMigrationIT`: V1 có delivery lịch sử thiếu reference → V2 thành công, giữ nguyên dữ liệu/không phát outbox, cờ legacy không thể tạo mới hoặc đổi; delivery mới cần đầy đủ bản ghi Admin |
| Async submission | `SubmissionWaitersTest`, `SubmissionNotificationIT`: bounded waiters/executor, deadline/context, same-token replay, batch repair, PostgreSQL commit/rollback/CAS, hai replica LISTEN, MockMvc async response/headers ([ADR-027](./decisions/ADR-027-async-submission-checkpoint-waiters.md)) |
| Dispatcher | `WorkflowDispatcherTest`, `WorkflowDispatchStoreIT`: 8 RPC có giới hạn, ACK theo lô/straggler, retry từng row, shutdown, lease renewal, PostgreSQL rollback/reclaim, owner/generation fencing và `SKIP LOCKED` ([ADR-028](./decisions/ADR-028-parallel-workflow-starts-and-batched-ack.md)) |
| Message lanes | `WorkflowMessageLanesTest`, `WorkflowDispatchStoreIT`: một message đang bay mỗi target theo `created_at`, start/message chia 8 slot luân phiên, ACK message theo lô chung transaction với start, defer lane sau head `RETRY`, closed target trên coordinator, renewal/shutdown; PostgreSQL: fencing hai owner và cùng pod khác generation, rollback/reclaim, hai dispatcher thật cùng DB và reclaim pod đã chết ([ADR-029](./decisions/ADR-029-parallel-workflow-messages-per-target-lanes.md)) |

MockMvc helper `AbstractIntegrationTest.call` phải đợi async result rồi `asyncDispatch` cho submission
POST; validation/identity rejection vẫn đồng bộ. Khi thêm test controller, không đọc response body
trước async dispatch. PostgreSQL test dùng DB riêng/Testcontainers, không thử fault bằng cách dừng
Order hoặc PostgreSQL của stack người dùng đang chạy.

Kết quả 2026-10-16: Order **97 test pass**; Checkout **164 test, 0 failure/error, 1 live test skip**;
V1→V3 migration, commit/rollback notification, reconnect và hai replica được kiểm chứng với
PostgreSQL test thật. Chi tiết và lệnh tái chạy:
[async-submission-verification-2026-10-16.md](./async-submission-verification-2026-10-16.md).

Sau ADR-028, Order suite có **109 test, 0 failure/error, 1 benchmark opt-in skip**;
11 test mới bao phủ dispatcher/claim fencing trên PostgreSQL thật. Benchmark dispatcher có
[báo cáo so sánh một thread và tám worker](../performance/order-dispatcher-parallel-benchmark-2026-10-16.md).

Sau ADR-029 (2026-10-20), Order suite có **124 test, 0 failure/error, 1 benchmark opt-in skip**
(chạy với `TEST_DB_URL` trỏ tới PostgreSQL test mới); 8 unit test lane và 6 test PostgreSQL thật mới.
So sánh với ADR-028 trong cùng phiên:
[báo cáo message lanes](../performance/order-dispatcher-message-lanes-benchmark-2026-10-20.md).

Chưa có: Temporal replay test trên history thật, consumer contract test, Debezium/Kafka
E2E tự động, load test.

## 5. Kiểm thử

### Unit test

- Master/item/refund FSM, invalid transition và buyer-tab precedence.
- Financial case outcome versus scope-finalization status; no-payout
  `CLOSED_INCOMPLETE`, external full-payout `EXTERNALLY_REMEDIATED`, zero-receipt
  cancellation mapping, frozen reason after reversal.
- Pricing snapshot PLATFORM-only; customer refund không tạo promotion allocation/call.
- Pre-payment compensation versus post-capture forward-repair/reconciliation, and deterministic replay logic.
- Pre-payment Inventory/Promotion compensations both run when either fails; case envelope
  remains open until all required obligations resolve.
- Cursor encode/decode, visibility/filter mapping, pinned accent normalization,
  deterministic primary line và item/remaining counts.

### Integration test

- PostgreSQL transaction: state + sequence + multi-destination outbox + durable
  inbox/workflow-message/dispatch/checkpoint atomicity; fixed lock-order and row-lock/CAS races.
- Inventory/Promotion accepted receipt persist exact expiry; immutable payment deadline
  lấy minimum có clock-skew safety, gồm race reserve gần hết hạn và replay expiry mismatch.
- Buyer return first-write-wins; unique checkout/refund transaction constraints.
- Search class A/B với `pg_trgm` trên `snapshot_name_search`, view tab
  DISPATCHED/DELIVERED và equal timestamp cursor.
- Request replay mirror hit/miss/outage/TTL expiry; duplicate events must be safe without
  Redis; durable inbox/dispatch/checkpoint remains the authority.
- Debezium outbox at-least-once, destination allowlist, both topics, connector restart,
  exact receipt observer và trimmer refusal when a receipt is missing.
- Notification command sequence: concurrent milestones cấp sequence tăng đơn điệu dưới
  order-row lock; durable command ledger khiến duplicate `dedup_key` không tăng counter
  kể cả sau outbox trim, payload collision bị quarantine; provider contract từ chối thiếu
  sequence và Notification test supersede/serialize theo cùng `source + subject`.
- Temporal crash/resume, timer/signal race, activity retry, workflow identity; inject
  crash after DB commit before RPC and after successful `SignalWithStart` before message
  acknowledgement; force `Continue-As-New` before dispatcher redelivery to prove carried
  high-watermark/recent-ID dedup + DB activity idempotency prevent reapply.
- Closed-target dispatch: test already-applied, state-superseded, open-obligation reroute,
  ambiguous-evidence quarantine và xác nhận Workflow ID reuse không start saga cũ.
- Financial result/no-payout decision atomically commits frozen scope intent and dispatch;
  accepted close with response loss recovers the same key; retry exhaustion/restart
  retains durable work even on financial terminal cases. Generation advances only after
  an audited decision and proven rejection; operation is not completed before receipt.
- Promotion final activity: same-key stored response, different-payload conflict, crash
  sau durable receipt trước Redis convergence; assert no active post-capture `CANCEL_FINAL`.

### Contract/consumer/provider test

Implementation phải test mọi HTTP/event/activity semantics liệt kê ở
`interfaces.md`: unknown field, duplicate, unsupported version, route theo
order/workflow ID, canonical string order ID của cả hai outbound event, unique paid
SKU list, nullable completed-item variant
snapshot, voucher toàn đơn snapshot, selected full-quantity scope, InspectionCompleted
PASS/FAIL full evidence persistence, atomic admin pre-dispatch release attempt/case boundary,
Checkout item `category_ids` persistence và exact Promotion Reserve context reconstruction,
Payment attempt↔transaction/amount/currency/per-transaction financial-version validation,
FAILED-only retry before immutable deadline with both holds still accepted,
refund reverse from `REFUNDED`, và đủ 5 dispute facts chỉ đi reconciliation case.
Also test all three scope-finalization Activities/guards/reasons, conditional
  `CLOSE_RETURN_WITHOUT_REFUND` payload and evidence/identity checks, optional API
  `scope_finalization`, `CLOSED_INCOMPLETE` and `EXTERNALLY_REMEDIATED` case enums, exact
  external payout amount/currency/customer + latest Payment-version fencing. No-receipt case uses cancel;
received-but-uninspected fails; Admin finalization is `NOT_REQUIRED`.
Payment fixtures phải dùng full structured CloudEvents, exact
namespaced `type`, canonical `source`, matching `dataschema` và Kafka key
`data.order_id`; unknown/legacy type phải được ACK/ignore theo policy, không dispatch
bằng alias uppercase. Refund amount là tổng immutable selected-line net amount được
phân bổ discount theo largest remainder; multiplicity không deferred.

### End-to-end/failure injection

Chạy checkout success, từng pre-payment failure, post-capture forward-repair failure,
cancel-versus-capture, unknown remote outcome/query replay, timeout cancel, anomalous
success after terminal non-capture (reconciliation only), multi-warehouse physical
dispatch and the separate Admin delivery confirmation (rejected before dispatch, all-or-nothing, replay),
Buyer one-shot return, rejected/accepted admin attempt, Inventory 30-day timeout/manual
verification, refund success/fail/reverse after `REFUNDED`, dispute
opened/funds-withdrawn/reinstated/won/lost theo thứ tự lệch mà không đổi FSM, external
financial remediation chỉ đóng đúng financial obligation khi có settlement evidence và
accounting reference, không phát Order fact hoặc đổi terminal FSM, cùng với
Redis/Kafka/DB/Temporal outage.
Add terminal success/failure close, no automatic close at 30 days, approved partial/no
receipt closure without Payment, receipt/inspection race with pending decision, late
completion blocked from payout, durable 409/manual correction, wrong closure receipt,
and reversal before/after Inventory closure without reopening/repeating refund.
Timer dài cần test clock/time skipping, không chờ thật.

## 6. Contract code generation

Inventory's [ADR-020](../inventory/decisions/ADR-020-command-receipts-and-minimal-order-events.md)
requires exactly two Inventory inbound event types: physical dispatch and selected-scope
inspection completion. Verify reservation commands progress without Kafka acknowledgments,
response-loss/crash recovery uses the persisted original ID/body, retry exhaustion keeps
durable repair, and historical receipts are distinguished from current reservation state.
Retain duplicate/reordered dispatch and inspection consumer tests and the refund gate.

Order không generate code từ contract: DTO request là record viết tay (bean validation +
`fail-on-unknown-properties`), response được dựng tường minh và **được kiểm bằng test** với
chính schema OpenAPI/JSON Schema trong `contracts/` (`ContractSchemas`). Đổi contract mà quên
code sẽ làm integration test đỏ. Chưa có CI drift check tự động ngoài các test này.

## 7. Database migration

`V1__order_baseline.sql` tạo toàn bộ schema/table/FK/CHECK/index/view/extension của
`data.md` (kể cả `pg_trgm`, `v_orders_buyer_tab`, inbox, dispatch/message, checkpoint,
reconciliation, ledger/outbox/receipt). Flyway chạy khi service khởi động. Repository không
có dữ liệu Order lịch sử nên các bước backfill dưới đây chỉ áp dụng nếu sau này import từ
hệ thống cũ:
Migration phải add/backfill durable inbox, workflow dispatch/message, operation, saga
checkpoint, numbered payment attempt identity/version, refund evidence, reconciliation
case/obligation/evidence/decision and admin attempt tables before retiring historical
idempotency storage. Index trigram lớn nên tạo theo kế hoạch không khóa production.
Add finalization status/generation/receipt metadata, generation-keyed checkpoints and
the unresolved-finalization index. Backfill Admin to `NOT_REQUIRED`; query/recover
Inventory evidence for historical Buyer terminal cases without reissuing Payment or
inventing command receipts. Version the refund workflow change for replay and register
`OrderRefundScopeFinalizationWorkflow` on the existing refund queue before dispatch.

Migration mới là file `V{n}__*.sql` additive; không có down migration và không xoá order
production. Integration test chạy Flyway trên PostgreSQL thật nên query tham chiếu cột thiếu
sẽ fail ngay. Seed order đa kho: `Fixtures.defaultLines()` trong test.

## 8. Test data và fixture

Fixture tối thiểu gồm order một/nhiều kho; trạng thái master/item/payment; promotion
snapshot; buyer/admin; selected scope A×2/C×3 from A×2/B×1/C×3; cùng timestamp; tên tiếng Việt có/không dấu;
payment success/fail/canceled/expired with version and wrong-identity variants; refund
processing/success/fail/reverse-after-success; Inventory command uncertain/accepted/rejected,
physical-dispatch/return duplicate. Dùng UUID/số điện thoại/address giả, không dùng PII production.

## 9. Gỡ lỗi

Ưu tiên trace bằng `correlation_id`, WorkflowID, event ID và order ID. Inspect
Temporal history trước khi retry/manual signal; inspect dispatch, durable inbox, remote
command checkpoint, row lock/state/outbox in read-only transaction; kiểm tra Kafka offset/lag
và request-replay TTL mà không dump PII.
Không update FSM trực tiếp để “gỡ kẹt” vì sẽ bỏ sequence/outbox/compensation.

## 10. Lỗi local thường gặp

- `order_service` chưa tồn tại vì volume Postgres tạo trước khi `init-db.sh` có Order: tạo
  thủ công (§2).
- Testcontainers báo "Could not find a valid Docker environment" khi chạy trong container
  Maven: thiếu `-Dapi.version=1.44` hoặc `TESTCONTAINERS_HOST_OVERRIDE` (§4).
- Submission trả `503 SUBMISSION_IN_PROGRESS` lặp lại: Inventory/Promotion/Payment không tới
  được; xem log `outcome unknown ... same command will be replayed` và Temporal UI.
- Payload Temporal phải là record thuần: getter dạng `isX()` cần `@JsonIgnore`, nếu không
  Jackson tạo field lạ và workflow task fail khi deserialize.
- Extension `pg_trgm` chưa bật, application normalizer lệch version hoặc trigram index
  trên `snapshot_name_search` không dùng được.
- Temporal worker đăng ký sai task queue/activity hoặc workflow nondeterministic.
- Kafka Connect không route outbox/partition key đúng.
- Redis key namespace/TTL sai làm duplicate hoặc conflict giả.
- Mock downstream không giữ stable idempotency behavior.
- Query tham chiếu timestamp/case state chưa có trong migration.

## 11. Definition of Done

Một thay đổi chỉ hoàn tất khi build/lint/test tương ứng pass; migration và rollback
được kiểm tra; FSM/state-outbox atomicity không bị phá; workflow replay-compatible;
idempotency/race/failure path có test; interface/data/workflow/operations docs được
cập nhật; breaking decision có ADR mới; PII/secret không log; evidence/frontmatter
được cập nhật trước khi nâng trạng thái lên `verified`.
