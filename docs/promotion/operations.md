---
doc_id: promotion-operations
service: promotion
scope: target
status: proposed
owners:
  - Promotion Service team
evidence: []
verified_against_commit: null
---

# Promotion Service — Vận hành

> Chưa có runtime config/deployment/observability source. Đây là yêu cầu vận hành đích,
> không phải số liệu production đã đo.

## 1. SLI/SLO cần đo

| SLI | Mục tiêu thiết kế |
|---|---|
| Voucher discovery latency | p99 `< 15 ms` khi snapshot hit; tách cache miss |
| API error | Tách business 409 khỏi dependency/5xx |
| Claim pending age | Alert trước/sau rescue threshold 5 phút |
| Claim boundary/runtime | Claim-gate field revision drift, Redis–PostgreSQL clock drift và reject theo boundary |
| Reservation release delay | Dưới một sweep window trong điều kiện bình thường |
| Final command replay | Receipt hit/mismatch, confirm-fence age và Redis-finalize delay |
| Campaign boundary delay | loop 250 ms–1 s; fallback 10–30 s |
| Outbox relay age | Oldest unrelayed row và connector lag |
| Product markdown convergence | Outbox create → Product apply theo source revision |
| Product tombstone convergence | Product delete → terminal watermark persisted + mọi Promotion markdown rule invalidated |
| Budget drift | Qty/VND trước-sau repair và drift tái diễn |

ID user/order/promotion/claim không dùng làm metric label cardinality cao.

## 2. Health

- Liveness không phụ thuộc network.
- API readiness fail khi PostgreSQL không dùng được. Redis write-path failure làm
  reserve/claim/Confirm-Prepare unavailable; `cancel-confirmed` vẫn có thể durable commit
  rồi chờ projection repair. Snapshot read có thể fallback DB.
- Consumer readiness phản ánh broker/subscription.
- Scheduler readiness phản ánh PostgreSQL, Redis và độ trễ fallback.
- Startup probe chờ migration, campaign ZSET và runtime claim fields/pool rebuild bắt buộc.
- Debezium/Kafka Connect health được giám sát như dependency của event propagation.

## 3. Configuration

| Property logic | Default/constraint |
|---|---|
| Snapshot TTL | 3600s |
| Reservation business TTL | 15m |
| Reservation cleanup grace | Phải dài hơn race/retry window; chưa chốt số |
| Pending rescue age | 5m |
| Claim clock-drift threshold | Phải nhỏ hơn boundary correctness SLO; chưa chốt số |
| Campaign schedule loop | 250ms–1s |
| Campaign DB fallback | 10–30s |
| Outbox retention | Theo CDC watermark + outbound event replay horizon |
| Command receipt retention | Lớn hơn Temporal retry + late-recovery horizon; chưa chốt số |
| Topic/group/connector | Bắt buộc cấu hình rõ, không hard-code secret |

Mọi timestamp dùng UTC. Redis/DB money dùng integer VND.
Voucher discovery không có property rate-limit ở Promotion: Gateway profile
`promotion-discovery` sở hữu quota 20 request/user/phút và observability của việc throttle.

## 4. Observability

- Metrics: API latency/error; AST parse/eval; snapshot hit/miss/version drift; claim
  outcome/pending age/runtime gate revision/boundary reject/clock drift; Redis Lua codes;
  reservation expiry; campaign schedule lag/stale
  revision; command receipt hit/mismatch; confirm fence/finalizer age; projection repair;
  outbox oldest age/rows; Debezium/consumer lag; Product stale revision drop/tombstone
  watermark + invalidation lag; budget exhaustion response/metric và drift/repair.
- Logs: correlation/workflow/event/order/promotion/claim/campaign IDs, campaign/source
  revision, Lua result, outbox dedup key và reconciliation before/after. Redact PII/token.
- Traces: DB transaction, Redis EVAL/pipeline, outbox insert, CDC propagation, rule
  parse/evaluate và Temporal activity.

Customer refund không có Promotion metric/consumer. Có thể đo số `ORDER_REFUNDED` bị
gửi nhầm Promotion ở contract gateway như integration error, không xử lý payload.

## 5. Alerts

| Alert | Condition gợi ý | Severity |
|---|---|---|
| PostgreSQL unavailable/pool exhausted | readiness/saturation kéo dài | Critical |
| Redis claim/reserve failure | EVAL/CROSSSLOT/timeout write path | Critical |
| Pending claim stale | oldest >5m hoặc backlog tăng | High |
| Claim runtime/clock drift | claim-gate fields stale hoặc Redis–DB clock drift vượt threshold | Critical |
| Reservation cleanup failure | due backlog/repair queue tăng | High |
| Confirm fence/finalizer stuck | `CONFIRMING` vượt grace hoặc receipt commit chưa hội tụ Redis | High |
| Command receipt inconsistency | receipt khác wallet/budget/outbox hoặc payload mismatch spike | Critical |
| Campaign boundary delayed | vượt fallback window | High |
| Stale schedule revision spike | nhiều stale member bất thường | Warning |
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
| Worker crash sau outbox commit trước ZREM | Retry trùng dedup key rồi cleanup |
| START xử lý sau END | Không phát ACTIVE; hội tụ INACTIVE |
| Kafka unavailable | DB/outbox vẫn commit; backlog tăng, CDC retry |
| Debezium lỗi | Giữ outbox, không direct-produce để bypass ordering |
| Claim API crash sau SPOP | Chưa trả success; pending worker persist guarded, không trả claim vào pool |
| Claim đến đúng/sau end boundary | Redis TIME gate từ chối tại `now >= closes_at`; không SPOP |
| Claim worker chạy sau end/pause/cancel | Persist nếu pending identity và revision-at-acquisition nhất quán; không so với current revision |
| Redis mất runtime claim fields/pool | Mutation không fail-open; rebuild từ PostgreSQL rồi mới ready |
| Pause/cancel fail sau khi claim fields đã đóng | DB vẫn là PUBLISHED; rebuild/reopen từ DB, chấp nhận false reject tạm thời |
| Product tombstone duplicate/stale | Watermark không lùi, repair dangling rule nếu có; ack sau DB commit |
| Crash sau tombstone DB commit trước cache invalidation | Replay guarded no-op rồi invalidate/rebuild; không emit về Product |
| DB fail sau Confirm-Prepare | Durable state chưa đổi; giữ fence trong grace để retry, hết grace đi late-payment policy |
| Crash sau confirm DB commit trước Redis finalize | Receipt/outbox đã durable; replay stored response và kick finalizer/repair |
| Crash sau `cancel-confirmed` DB commit trước Redis repair | Compensation vẫn complete; replay receipt và repair DB→Redis |
| Redis unavailable khi `cancel-confirmed` | DB transaction vẫn có thể commit; Redis tạm over-count used và repair sau |
| Reservation payload mất khi Confirm | Recheck durable receipt; replay nếu có, nếu không enqueue repair/late-payment policy; không đoán amount |
| Customer refund event đến Promotion | ACK/no-route theo deployment; alert contract mismatch, không mutate |

## 7. Degraded behavior

- Budget guard discovery fail-open; Reserve không fail-open.
- Snapshot miss fallback PostgreSQL; không có JVM AST cache.
- Outbox/CDC lag làm markdown downstream stale trong thời gian hữu hạn nhưng không mất
  event đã commit; replay latest current-state snapshot để hội tụ.
- Public save/API eligibility dùng DB time; limited claim dùng Redis TIME gate trong cùng
  Lua với SPOP. Scheduler lag không kéo dài claim window.
- Product/Search không tự chạy voucher Rule Engine.
- Không fail-open Confirm-Prepare hoặc durable DB transaction. `cancel-confirmed` không phụ
  thuộc Redis availability để commit, nhưng không được báo success trước DB commit.

## 8. Scaling/capacity

- API stateless scale ngang.
- Claim partition theo claim ID; markdown partition bắt buộc theo product ID.
- SPOP O(1); pending/reservation/campaign schedule ZSET O(logN); không key scan.
- Multi-key Lua phải dùng `{promotion_id}` và bounded input/runtime.
- Campaign scheduler xử lý batch hữu hạn; product advisory lock tránh reorder cùng product.
- Causal markdown dedup key gồm product/campaign/revision/trigger, không gồm resulting
  state; source revision chỉ làm ordering guard.
- PostgreSQL pool/index, Redis memory, outbox growth và Kafka partition cần load test.

## 9. Deployment/rollback

1. Tạo additive migration cho chín bảng, gồm `catalog_product_tombstones`,
   `promotion_command_receipts`, constraints, sequence/index.
2. Deploy Product migration/consumer chấp nhận ACTIVE full payload và INACTIVE tombstone
   trước Promotion producer; contract đã production thì dùng v2 dual-read.
3. Cấu hình outbox connector/ACL/topic và kiểm thử commit→CDC.
4. Deploy receipt reader/schema trước writer; deploy Confirm-Prepare/finalizer và DB-first
   `cancel-confirmed`. Deploy Product-tombstone consumer, causal dedup writer và Lua đọc
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
- **RB-04 Reservation:** đọc payload/index + DB wallet/outbox → release/retry/repair; không
  sửa counter mù.
- **RB-04A Confirm fence:** đọc reservation `CONFIRMING` + command receipt/wallet → finalize
  matching receipt hoặc reconcile; thiếu evidence không increment/release mù.
- **RB-05 Campaign schedule:** đọc DB revision/status/time trước ZSET → rebuild missing
  members → chạy fallback → xác nhận outbox state.
- **RB-06 Outbox/CDC:** dừng trimmer → kiểm tra WAL/connector/offset/topic → resume/replay
  latest current-state snapshot → xác nhận Product source revision.
- **RB-07 Reconciliation:** dựng expected từ DB + valid reservations → lock/re-read →
  repair Redis một chiều, audit before/after.
- **RB-08 Technical compensation:** xác minh order chưa hoàn tất, `CONFIRM` receipt và
  stable cancel key/hash → retry DB-first `cancel-confirmed` → replay stored response rồi
  kiểm tra wallet SAVED/EXPIRED, durable budget và Redis convergence.
- **RB-09 Product tombstone:** đọc event/catalog revision + durable watermark + affected
  rules/campaigns → kiểm tra shared product lock/monotonic guard → retry invalidation →
  invalidate cache/schedule → xác nhận không emit event loop.

## 11. Workers/jobs

| Worker/job | Trigger | Idempotency/recovery |
|---|---|---|
| Pending claim persistence worker | API inline + Redis pending scan | validate identity/revision-at-acquisition, không so current revision; guarded claim/wallet; cleanup sau commit |
| Pending claim sweeper | mỗi 1m, stale 5m | gọi persistence bằng cùng claim ID; không cần Kafka topic riêng |
| Reservation sweeper | mỗi 1m | per-promotion index + Lua; missing payload → repair |
| Confirm finalizer | outbox/receipt trigger | matching `CONFIRMING` only; duplicate no-op, missing → reconcile |
| Campaign scheduler | 250ms–1s | DB revision/time + outbox dedup; ZREM sau commit |
| Campaign fallback | 10–30s | DB source; repair missing/stale schedule |
| Schedule bootstrap | startup/failover | rebuild ZSET từ PostgreSQL |
| Claim runtime/pool bootstrap | startup/failover | rebuild claim-gate fields + AVAILABLE pool từ PostgreSQL; mutation blocked đến ready |
| Product tombstone consumer | `product-deleted.v1` | persist monotonic watermark + guarded invalidation; ack sau commit; no reverse event |
| Outbox CDC relay | WAL | at-least-once; connector retry/DLQ |
| Budget reconciliation | recent 15m + off-peak full scan | DB→Redis, per-promotion lock |
| Snapshot monitor | mỗi 5m | metric only, không auto-warm hàng loạt |
| Wallet expiry normalization | lazy/optional batch | eligibility luôn đọc `expired_at` |
| Outbox trimmer | policy schedule | CDC watermark + replay horizon guard |
| Command receipt trimmer | policy schedule | `retain_until` + Temporal retry/late-recovery guard |

Không có Order refund consumer. Command receipt cleanup tách khỏi outbox trimmer và không
xóa receipt còn nằm trong retry/recovery horizon. Không có budget-exhausted producer/topic;
exhaustion chỉ được đo từ synchronous response và metrics.
