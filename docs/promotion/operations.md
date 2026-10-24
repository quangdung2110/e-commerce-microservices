---
doc_id: promotion-operations
service: promotion
scope: current
status: implemented
owners:
  - Promotion Service team
evidence:
  - contracts/http/promotion/v1/openapi.yaml
  - contracts/events/promotion/v1/asyncapi.yaml
verified_against_commit: null
---

# Promotion Service — Vận hành

> Runtime: `java/services/promotion-service` (workers trong `PromotionWorkers`, cấu hình §3,
> Debezium connector trong `src/main/resources/kafka`). SLO/alert/dashboard bên dưới là yêu
> cầu đích: service mới expose Actuator `health/metrics/prometheus` mặc định, chưa có metric
> nghiệp vụ tùy chỉnh hay dashboard đã đo.

## 1. SLI/SLO cần đo

| SLI | Mục tiêu thiết kế |
|---|---|
| Voucher discovery latency | p99 `< 15 ms` khi snapshot hit; tách cache miss |
| API error | Tách business 409 khỏi dependency/5xx |
| Claim pending age | Alert trước/sau rescue threshold 5 phút |
| Claim boundary/runtime | Claim-gate field revision drift, Redis–PostgreSQL clock drift và reject theo boundary |
| Reservation release delay | Dưới một sweep window trong điều kiện bình thường |
| Final command replay | Receipt hit/mismatch, confirm-fence age và tuổi receipt `redis_finalized_at IS NULL` |
| Campaign boundary delay | loop 250 ms–1 s; fallback 10–30 s |
| Outbox relay age | Oldest unrelayed markdown row và connector lag |
| Product markdown convergence | Outbox create → Product apply theo source revision |
| Product tombstone convergence | Product delete → terminal watermark persisted + mọi Promotion markdown rule invalidated |
| Budget drift | Qty/VND trước-sau repair và drift tái diễn |

ID user/order/promotion/claim không dùng làm metric label cardinality cao.

## 2. Health

Hiện thực (Actuator, `management.endpoint.health.probes.enabled=true`; `health,info,metrics,prometheus`):

- `/actuator/health/liveness` không phụ thuộc network.
- `/actuator/health/readiness` gồm `readinessState`, `promotionRuntime` và `voucherReadWarmup`.
  Chỉ UP khi startup warm-up voucher và rebuild runtime hoàn tất; shutdown chuyển OUT_OF_SERVICE.
  Nhóm này không fan-out kiểm PostgreSQL/Redis mỗi lần probe.
- `/actuator/health` (tổng hợp) gồm `db`, `redis`, `promotionRuntime` và `voucherReadWarmup`: `promotionRuntime` là
  `OUT_OF_SERVICE` cho tới khi `PromotionWorkers.bootstrap()` rebuild xong campaign ZSET và runtime claim
  fields/pool (startup probe bắt buộc). Healthcheck của docker-compose và router Traefik dùng
  `/actuator/health`; Dockerfile dùng `/actuator/health/liveness`.

Warm-up voucher chạy một lần trước khi nhận traffic, budget `PROMOTION_VOUCHER_WARMUP_TIMEOUT=30s`,
`PROMOTION_VOUCHER_WARMUP_ROUNDS=3` (1–10). Đường đọc chuẩn bị Hibernate wallet/public query,
snapshot version/pipeline/projection, rule AST, budget reads, ranking/display và JSON DTO. Candidate
âm chỉ tồn tại trong bộ nhớ để catalog rỗng vẫn làm nóng evaluator; không ghi promotion đó vào DB/Redis.
Runner còn gọi HTTP loopback đúng `/internal/v1/promotion-voucher-options` qua allowlist hiện có bằng
UUID kỹ thuật mới không có ví: controller, validation, converter và lazy-expiry SQL (0 dòng) cũng được
làm nóng. Không save/claim/reserve, không thay đổi wallet/budget/receipt/outbox; snapshot cache-aside có
thể ghi cache bình thường. Lỗi/budget hết làm startup thất bại và readiness đóng. Metric
`promotion_voucher_startup_warmup_seconds{outcome}` ghi kết quả. Không có keep-alive định kỳ;
timeout request Redis/Promotion/Checkout giữ nguyên.
- Redis write-path failure làm reserve/claim/Confirm-Prepare unavailable (`503`); capability/proof-gated
  legacy cleanup vẫn có thể durable commit rồi chờ projection repair. Snapshot read fallback DB.
- Không có health indicator cho Kafka consumer, scheduler lag hay Debezium: giám sát qua metric/alert ở §5
  (chưa có metric nghiệp vụ tùy chỉnh). Debezium/Kafka Connect health được giám sát như dependency của event
  propagation.

## 3. Configuration

| Property (`application.yml`) | Default hiện tại |
|---|---|
| `promotion.snapshot.ttl` | 3600s |
| `promotion.discovery.batch-size` | 200 (>= 1; env `PROMOTION_DISCOVERY_BATCH_SIZE`); kích thước batch wallet/public, không phải trần candidate |
| `promotion.reservation.business-ttl` | 15m |
| `promotion.reservation.cleanup-grace` | 5m (payload TTL = business + grace; sweeper force-release `CONFIRMING` không receipt sau khi hết hạn) |
| `promotion.claim.pending-stale-after` / `sweeper-interval` | 5m / 60s |
| `promotion.claim.max-pool-size` / `pool-batch-size` | 1.000.000 / 5.000 (pre-generate tại publish) |
| Claim clock-drift threshold | Chưa có metric/alert tự động; runbook RB-03A so `TIME` Redis với `now()` PostgreSQL thủ công |
| `promotion.schedule.loop-interval` / `fallback-interval` / `fallback-lookback` | 500ms / 15s / 1d |
| `promotion.finalizer.scan-interval` / `stale-after` / `batch-size` | 60s / 30s / 200 |
| `promotion.reconciliation.interval` / `recent-window` / `lock-ttl` | 5m / 15m / 30s (chỉ promotion có wallet/receipt thay đổi trong recent window; chưa có off-peak full scan) |
| `promotion.outbox.retention` / `trim-interval` | 14d / 1h (trim theo thời gian; chưa đọc CDC watermark) |
| `promotion.receipt.retention` / `trim-interval` | 90d / 1h; không xóa receipt còn `redis_finalized_at IS NULL` |
| `promotion.kafka.product-topic` / `product-dlq-topic` / `group-id` | `t.product.events` / `t.product.events.promotion.dlq` / `promotion-product-tombstone-consumer` |
| `promotion.internal.calculation-clients` / `options-clients` | `checkout-service` / `checkout-service` |
| `promotion.internal.allocation-clients` | `order-service` (reservations/confirmations/cancellations) |
| `promotion.internal.legacy-migration-clients` | `promotion-legacy-migrator` (chỉ technical-cancellations; Order bị `403`) |
| `promotion.internal.identity-header` / `promotion.admin.{user-id-header,roles-header,required-role}` | `X-Service-Identity` / `X-User-Id`, `X-User-Roles`, `ADMIN` |
| `promotion.product.base-url` / `validation-enabled` | `http://localhost:8082` (cổng host Product publish trong compose, cho chạy ngoài Docker; `PRODUCT_BASE_URL`) / true (`PRODUCT_VALIDATION_ENABLED`). Compose đặt `http://product-service:8081` |
| `promotion.product.identity` / `timeout` | `promotion-service` (gửi trong `X-Service-Identity`) / 2s |
| `promotion.claim.pending-retry-after-seconds` | 2 (`Retry-After` của `202 CLAIM_PENDING`) |
| `promotion.schedule.batch-size` | 100 member due mỗi vòng |
| `promotion.schedule.emit-batch-size` | 200 (>= 1; env `PROMOTION_SCHEDULE_EMIT_BATCH_SIZE`); số markdown rule/product mỗi transaction khi phát START/END, không phải trần số product (ADR-021). Benchmark 10.000 product: lock giữ/chờ tối đa xấp xỉ 1 s (lô 50), 3 s (lô 200), 16 s (lô 1000); lô lớn hơn 200 không nhanh hơn |
| `promotion.kafka.retry-attempts` / `retry-backoff` / `consumer-enabled` | 4 / 500ms (exponential ×2, hết lượt → DLQ) / true (`KAFKA_CONSUMER_ENABLED`) |
| `promotion.workers.enabled` | true (`WORKERS_ENABLED`) |
| `promotion.markdown.dataschema` | `/contracts/events/promotion/v1/schemas/product-markdown-snapshot.schema.json` |
| Debezium connector | `src/main/resources/kafka/debezium-connector-promotion-outbox.json` (route `t.promotion.markdown.events`); root compose đăng ký tự động qua `kafka-connect-init` (`infra/kafka-connect/register-connectors.sh`). Connector vắng mặt ⇒ markdown không tới Product/Search (`snapshot.mode=no_data` không phát lại event cũ) |

Mọi timestamp dùng UTC. Redis/DB money dùng integer VND.
`POST /api/v1/promotion-discovery` không có property rate-limit ở Promotion: Gateway
profile `promotion-discovery` sở hữu quota 20 request/user/phút và observability của việc throttle.

## 4. Observability

- Metrics: API latency/error; AST parse/eval; snapshot hit/miss/version drift; claim
  outcome/pending age/runtime gate revision/boundary reject/clock drift; Redis Lua codes;
  reservation expiry; campaign schedule lag/stale
  revision; `promotion.schedule.emit.pages{outcome=committed|failed|stale}` và
  `promotion.schedule.emit.snapshots{result=emitted|already_emitted}` (ADR-021); command receipt hit/mismatch; confirm fence/finalizer age; projection repair;
  outbox oldest age/rows; Debezium/consumer lag; Product stale revision drop/tombstone
  watermark + invalidation lag; budget exhaustion response/metric và drift/repair.
- Logs: correlation/workflow/event/order/promotion/claim/campaign IDs, campaign/source
  revision, Lua result, outbox dedup key và reconciliation before/after. Redact PII/token.
- Traces: DB transaction, Redis EVAL/pipeline, outbox insert, CDC propagation, rule
  parse/evaluate và Temporal activity.

Customer refund không có Promotion metric/consumer. Mọi Order event bị route nhầm tới
Promotion là integration error và không được xử lý payload.

## 5. Alerts

| Alert | Condition gợi ý | Severity |
|---|---|---|
| PostgreSQL unavailable/pool exhausted | readiness/saturation kéo dài | Critical |
| Redis claim/reserve failure | EVAL/CROSSSLOT/timeout write path | Critical |
| Pending claim stale | oldest >5m hoặc backlog tăng | High |
| Claim runtime/clock drift | claim-gate fields stale hoặc Redis–DB clock drift vượt threshold | Critical |
| Reservation cleanup failure | due backlog/repair queue tăng | High |
| Confirm fence/finalizer stuck | `CONFIRMING` vượt grace hoặc receipt `redis_finalized_at IS NULL` quá ngưỡng | High |
| Command receipt inconsistency | receipt khác wallet/budget hoặc payload mismatch spike | Critical |
| Campaign boundary delayed | vượt fallback window | High |
| Stale schedule revision spike | nhiều stale member bất thường | Warning |
| Campaign boundary emission incomplete | `promotion.schedule.emit.pages{outcome=failed}` tăng liên tục hoặc cùng member `INCOMPLETE` lặp lại nhiều chu kỳ (log WARN "incomplete") | High |
| Outbox/CDC lag | oldest row vượt propagation SLO | High |
| Product markdown divergence | expected revision/state không hội tụ | Critical |
| Product tombstone convergence lag | thiếu watermark hoặc deleted Product còn effective markdown rule | Critical |
| Budget drift repeated | cùng promotion drift sau repair | Critical |

## 6. Failure modes

| Failure | Hành vi |
|---|---|
| PostgreSQL unavailable | Fail operation cần truth; không dùng Redis thay DB |
| Redis snapshot unavailable | Calculate/discovery fallback DB, latency tăng |
| Redis budget unavailable | Discovery fail-open có marker; mutation fail/retry |
| Redis mất schedule | Rebuild từ campaign DB; fallback scan bắt boundary |
| Worker crash sau outbox commit trước ZREM | Retry: dedup pre-check bỏ qua product đã phát (không lock, không tiêu sequence) rồi cleanup |
| Một lô START/END lỗi (ví dụ lock timeout, product hỏng dữ liệu) | Chỉ lô đó rollback; các lô khác vẫn commit; member giữ lại (không ZREM) và chạy lại ở vòng sau; lô lỗi vĩnh viễn lặp lại mỗi chu kỳ tới khi sửa dữ liệu, phần còn lại của campaign vẫn hội tụ |
| Campaign đổi revision/status giữa các lô | Lô kế tiếp phát hiện lệch → dừng, dọn member lỗi thời; snapshot cho thay đổi đó (nếu campaign đang active) do đường admin phát |
| Hai worker cùng xử lý một member | Mỗi product đúng một snapshot (dedup key + `ON CONFLICT`); worker thua chỉ tốn lock/revision |
| START xử lý sau END | Không phát ACTIVE; hội tụ INACTIVE |
| Kafka unavailable | DB/outbox vẫn commit; backlog tăng, CDC retry |
| Debezium lỗi | Giữ outbox, không direct-produce để bypass ordering |
| Claim API crash sau SPOP | Chưa trả success; pending worker persist guarded, không trả claim vào pool |
| Claim đến đúng/sau end boundary | Redis TIME gate từ chối tại `now >= closes_at`; không SPOP |
| Claim worker chạy sau end/pause/cancel | Persist nếu pending identity và revision-at-acquisition nhất quán; không so với current revision |
| Redis mất runtime claim fields/pool | Mutation không fail-open; rebuild từ PostgreSQL rồi mới ready |
| Redis không đóng được claim gate khi pause/cancel | Lệnh thất bại `503 PROMOTION_UNAVAILABLE`, campaign giữ `PUBLISHED`/revision; gate đã đóng một phần được mở lại; admin retry khi Redis hồi phục |
| Pause/cancel fail sau khi claim fields đã đóng | DB vẫn là PUBLISHED; hook `afterRollback` mở lại gate từ DB; nếu Redis lỗi tiếp thì gate đóng đến lần rebuild kế (restart/transition kế) — chấp nhận false reject tạm thời |
| Product tombstone duplicate/stale | Watermark không lùi, repair dangling rule nếu có; ack sau DB commit |
| Crash sau tombstone DB commit trước Kafka ack | Event giao lại: guarded no-op (watermark không lùi, rule đã invalidated); không có cache để invalidate; không emit về Product |
| DB fail sau Confirm-Prepare | Durable state chưa đổi; giữ fence trong grace để retry, hết grace đi late-payment policy |
| Crash sau confirm DB commit trước Redis finalize | Receipt đã durable với `redis_finalized_at IS NULL`; replay stored response và finalizer worker chạy lại |
| Crash sau Redis finalize trước set `redis_finalized_at` | Worker chạy lại: Lua trả already-finalized, application chỉ set marker; không double move |
| Crash sau `cancel-confirmed` DB commit trước Redis repair | Compensation vẫn complete; replay receipt, worker repair DB→Redis rồi set marker |
| Redis unavailable khi `cancel-confirmed` | DB transaction vẫn có thể commit; Redis tạm over-count used và repair sau |
| Reservation payload mất khi Confirm | Recheck durable receipt; replay nếu có, nếu không enqueue repair/late-payment policy; không đoán amount |
| Customer refund event đến Promotion | ACK/no-route theo deployment; alert contract mismatch, không mutate |

## 7. Degraded behavior

- Budget guard discovery fail-open; Reserve không fail-open.
- Snapshot miss fallback PostgreSQL; không có JVM AST cache.
- Outbox/CDC lag làm markdown downstream stale trong thời gian hữu hạn nhưng không mất
  event đã commit; replay latest current-state snapshot để hội tụ. Confirm/`cancel-confirmed`
  không phụ thuộc CDC vì finalizer chạy từ receipt.
- Public save/API eligibility dùng DB time; limited claim dùng Redis TIME gate trong cùng
  Lua với SPOP. Scheduler lag không kéo dài claim window.
- Product/Search không tự chạy voucher Rule Engine.
- Không fail-open Confirm-Prepare hoặc durable DB transaction. `cancel-confirmed` không phụ
  thuộc Redis availability để commit, nhưng không được báo success trước DB commit.

## 8. Scaling/capacity

- API stateless scale ngang.
- Discovery/voucher options scan hết candidate bằng keyset, snapshot/runtime Redis pipeline, cache miss
  DB projection theo batch và heap top `limit`; không trả kết quả bị cắt ngầm ở 200 candidate. Bộ nhớ
  `O(batchSize + limit)`, latency vẫn tăng theo cardinality và độ phức tạp AST. Đo cold/warm cache, Redis
  degraded và `EXPLAIN (ANALYZE, BUFFERS)` trên dữ liệu thực trước khi chỉnh batch size/thêm index;
  SLO discovery ở §1 chưa được xác nhận bằng load test. Cùng `now` không tạo snapshot nhất quán giữa
  mọi DB/Redis read khi publish/save/budget thay đổi đồng thời; Reserve vẫn là quyết định cuối.
- Claim partition theo claim ID; markdown partition bắt buộc theo product ID.
- SPOP O(1); pending/reservation/campaign schedule ZSET O(logN); không key scan.
- Multi-key Lua phải dùng `{promotion_id}` và bounded input/runtime.
- Campaign scheduler xử lý batch hữu hạn: tối đa `schedule.batch-size` member mỗi vòng, mỗi member theo
  lô `emit-batch-size` rule với một transaction mỗi lô (lock product chỉ giữ trong lô; ADR-021); product
  advisory lock tránh reorder cùng product. Campaign rất lớn phát ra thành từng đợt commit thay vì một cục.
- Causal markdown dedup key gồm product/campaign/revision/trigger, không gồm resulting
  state; source revision chỉ làm ordering guard.
- PostgreSQL pool/index, Redis memory, outbox growth và Kafka partition cần load test.

## 9. Deployment/rollback

1. Flyway: `V1__promotion_baseline.sql` tạo chín bảng, gồm `catalog_product_tombstones`,
   `promotion_command_receipts`, constraints, sequence/index; `V2__legacy_migration_proof.sql` thêm hai cột proof
   của receipt (additive, CHECK `NOT VALID` nên không chặn DB đã có receipt cũ). Deploy `V2` cùng bản có guard.
2. Deploy Product migration/consumer chấp nhận ACTIVE full payload và INACTIVE tombstone
   trước Promotion producer; contract đã production thì dùng v2 dual-read.
3. Cấu hình outbox connector/ACL/topic và kiểm thử commit→CDC.
4. Deploy receipt reader/schema (gồm `redis_finalized_at` và partial index) trước writer;
   deploy Confirm-Prepare/receipt-driven finalizer và DB-first `cancel-confirmed`. Deploy Product-tombstone consumer, causal dedup writer và Lua đọc
   claim-gate fields từ runtime hash; preload claim/budget, rebuild runtime claim fields/pool
   và campaign schedule.
5. Canary discovery rồi mutation; bật schedule/fallback/outbox theo feature flag.
6. Chỉ trim legacy keys/tables sau dual-read/drift verification; migration destructive cần
   kế hoạch riêng.

Không xóa offset, outbox, pending claim, reservation hoặc ZSET thủ công thiếu runbook.

## 10. Runbooks

- **RB-01 DB:** kiểm tra health/pool/lock → giảm traffic/khôi phục → xác nhận transaction
  và outbox atomicity.
- **RB-02 Redis mutation:** kiểm tra cluster slot/EVAL/latency → dừng unsafe mutation →
  restore/rebuild → reconciliation.
- **RB-03 Claim:** đọc pending + DB claim/wallet → chạy lại guarded persistence bằng
  immutable claim ID → chỉ cleanup sau DB commit; kiểm acquisition identity/revision-at-
  acquisition, không so current revision hoặc dùng processing time.
- **RB-03A Claim runtime:** so PostgreSQL campaign với runtime claim fields/revision/Redis
  TIME → dừng claim nếu stale/drift → rebuild fields/pool → test exact boundary rồi mới
  ready.
- **RB-04 Reservation:** đọc payload/index + DB wallet/receipt → release/retry/repair; không
  sửa counter mù.
- **RB-04A Confirm fence:** đọc reservation `CONFIRMING` + command receipt/wallet → finalize
  matching receipt hoặc reconcile; thiếu evidence không increment/release mù; chỉ set
  `redis_finalized_at` sau khi Redis đã hội tụ, không set tay để "tắt" alert.
- **RB-05 Campaign schedule:** đọc DB revision/status/time trước ZSET → rebuild missing
  members → chạy fallback → xác nhận outbox state. Member `INCOMPLETE` (log "incomplete", counter
  `outcome=failed`): tìm lô lỗi qua log WARN "page starting at rule N failed", đọc nguyên nhân (lock
  timeout, rule/product hỏng), sửa dữ liệu hoặc invalidate rule hỏng rồi để vòng sau chạy lại; không xóa
  member thủ công và không chèn outbox tay. Đối chiếu `promotion_outbox_events` theo
  `dedup_key LIKE '%:trigger:{START|END}'` với số rule chưa invalidated của campaign để biết còn thiếu product nào.
- **RB-06 Outbox/CDC:** dừng trimmer → kiểm tra WAL/connector/offset/topic → resume/replay
  latest current-state snapshot → xác nhận Product source revision.
- **RB-07 Reconciliation:** dựng expected từ DB + valid reservations → lock/re-read →
  repair Redis một chiều, audit before/after.
- **RB-08 Legacy technical cleanup:** authenticate only as `promotion-legacy-migrator` (mọi caller khác, kể cả
  `order-service`, nhận `403`; thiếu/sai `X-Legacy-Migration-Proof` cũng `403` và không ghi gì), verify
  the approved immutable migration record and its SHA-256 proof that the historical flow never
  crossed the Payment financial watermark, then issue matching header/body proof plus stable
  legacy cancel key/hash → retry DB-first `cancel-confirmed` → replay stored response and verify
  wallet SAVED/EXPIRED, durable budget and Redis convergence. Refuse `order-service` or any
  missing/mismatched proof. Active post-capture Order cases route to Order forward repair/
  financial reconciliation, never this runbook.
- **RB-09 Product tombstone:** đọc event/catalog revision + durable watermark + affected
  rules/campaigns → kiểm tra shared product lock/monotonic guard → retry invalidation
  (event giao lại hoặc DLQ replay) → xác nhận không emit event loop. Không có cache hay schedule theo
  product cần xoá.

## 11. Workers/jobs

| Worker/job | Trigger | Idempotency/recovery |
|---|---|---|
| Pending claim persistence worker | API inline + Redis pending scan | validate identity/revision-at-acquisition, không so current revision; guarded claim/wallet; cleanup sau commit |
| Pending claim sweeper | mỗi 1m, stale 5m | gọi persistence bằng cùng claim ID; không cần Kafka topic riêng |
| Reservation sweeper | mỗi 1m | per-promotion index + Lua; missing payload → repair |
| Final-command finalizer | inline sau commit + quét receipt `redis_finalized_at IS NULL` quá ngưỡng | `CONFIRM`: matching `CONFIRMING` only; legacy `CANCEL_CONFIRMED`: DB→Redis repair; duplicate no-op, missing → reconcile; set marker sau hội tụ |
| Campaign scheduler | 250ms–1s | DB revision/time + outbox dedup; phát theo lô nhỏ, mỗi lô một transaction, dedup pre-check; ZREM chỉ sau khi mọi lô xong (ADR-021) |
| Campaign fallback | 10–30s | DB source; repair missing/stale schedule |
| Schedule bootstrap | startup/failover | rebuild ZSET từ PostgreSQL |
| Claim runtime/pool bootstrap | startup/failover | rebuild claim-gate fields + AVAILABLE pool từ PostgreSQL; mutation blocked đến ready |
| Product tombstone consumer | `product-deleted.v1` | persist monotonic watermark + guarded invalidation; ack sau commit; no reverse event |
| Outbox CDC relay | WAL | at-least-once; connector retry/DLQ; chỉ markdown snapshot |
| Budget reconciliation | mỗi 5m cho promotion có wallet/receipt đổi trong 15m (chưa có off-peak full scan) | DB→Redis, per-promotion `reconcile_lock` |
| Snapshot monitor | chưa implement | metric only, không auto-warm hàng loạt |
| Wallet expiry normalization | lazy/optional batch | eligibility luôn đọc `expired_at` |
| Outbox trimmer | policy schedule | CDC watermark + replay horizon guard |
| Command receipt trimmer | policy schedule | `retain_until` + Temporal retry/late-recovery guard |

Không có Order refund consumer, Auth consumer hay coupon producer. Command receipt cleanup
tách khỏi outbox trimmer, không xóa receipt còn nằm trong retry/recovery horizon và không
xóa receipt còn `redis_finalized_at IS NULL`. Không có budget-exhausted producer/topic;
exhaustion chỉ được đo từ synchronous response và metrics.

## Request ID, trace ID và deadline của caller

Theo [contracts ADR-002](../../contracts/decisions/ADR-002-correlation-and-trace-propagation.md) và [ADR-003](../../contracts/decisions/ADR-003-deadline-propagation.md), thêm [ADR-022](./decisions/ADR-022-request-correlation-and-caller-deadline.md). Log trong
phạm vi một request có `request_id` và `trace_id` (MDC do `RequestCorrelationFilter` đặt, hiển thị bằng `logging.pattern.console`); log của
tác vụ nền không có hai trường này. `X-Request-Id` sai hoặc thiếu bị thay, không bao giờ `400`, và được
trả lại trong response.

Counter `http.server.caller_deadline_exceeded{route}` đếm request kết thúc sau khi caller đã thôi chờ
(`X-Request-Timeout-Ms`), kèm một dòng WARN `request outlived the caller deadline`. Số này tăng đều nghĩa là
Promotion trả lời chậm hơn timeout của caller: xem p99 của route trước, rồi mới xét timeout của caller. Ngưỡng
cảnh báo đặt theo số đo p99 từ load test, không đặt trước. Lời gọi Product snapshot gửi `X-Request-Id`, trace context và `X-Request-Timeout-Ms` (= `promotion.product.timeout`, 2 giây).
