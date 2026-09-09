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

> Chưa có runtime config/deployment/observability source. Mục tiêu dưới đây là yêu cầu
> thiết kế cần được hiện thực hóa và đo kiểm, không phải số liệu production.

## 1. SLO và SLI

| SLI | Mục tiêu/điều cần đo |
|---|---|
| Voucher discovery latency | p99 `< 15 ms` khi snapshot hit; miss dự kiến có thể ~16 ms |
| Rule evaluation | Thiết kế cũ kỳ vọng `< 1 ms`; profile đầy đủ parse N rule ~1–2,5 ms |
| Discovery DB query | Ước tính 3–5 ms với wallet index |
| Snapshot Redis | Ước tính 0,5–1 ms; budget guard 0,5–1,5 ms |
| API availability/error | Chưa chốt target; tách business 409 khỏi 5xx |
| Consumer lag | Theo topic/group và tuổi message lâu nhất |
| Pending claim age | Alert trước/sau ngưỡng rescue 5 phút |
| Reservation expiry delay | Thời gian từ expire_at đến release/repair, target dưới một sweep window |
| Markdown expiry delay | Near-real-time loop 250 ms–1 s; fallback 10–30 s |
| Budget drift | Count/amount trước và sau repair, lặp lại theo promotion |

Metric label không dùng `user_id`, `order_id`, `promotion_id`, `claim_id` làm label
cardinality cao; các ID này chỉ nằm trong structured log/trace có kiểm soát.

## 2. Health endpoints

Endpoint/path chưa được thiết kế. Tối thiểu cần:

- Liveness không phụ thuộc network dependency.
- Readiness API fail khi PostgreSQL không dùng được; quyết định Redis/Kafka nào làm fail
  readiness phải theo capability (Discovery có degraded budget guard, Reserve thì không).
- Consumer readiness phản ánh broker/subscription; scheduler readiness phản ánh lock/store.
- Startup probe chờ migration và rebuild markdown expiry index khi cần.

## 3. Configuration

Tên property/env chính xác phải lấy từ implementation tương lai.

| Property logic | Purpose | Required | Default thiết kế | Secret | Restart |
|---|---|---|---|---|---|
| PostgreSQL URL/credential | Persistence | Yes | none | credential: Yes | Yes |
| Redis endpoint/credential | Hot path/cache | Yes | none | credential: Yes | Yes |
| Kafka brokers/security | Producer/consumer | Yes | none | credential: Yes | Yes |
| Snapshot TTL | Rule snapshot | No | 3600s | No | tùy implementation |
| Reservation business TTL | Budget hold | No | 15m | No | No nếu dynamic |
| Reservation cleanup grace | Giữ payload cho sweep biên | Yes | Chưa chốt | No | No nếu dynamic |
| Claim flag TTL | Chống claim lặp | No | 30d | No | No |
| Pending rescue age | Republish stale claim | No | 5m | No | No |
| Discovery rate limit | Chống bot | No | 20/user/min | No | No |
| Idempotency retention | Cleanup | No | 45d, không dưới refund window +15d | No | No |
| Consumer groups/topics | Routing | Yes | Theo bảng worker | No | Yes |

Không commit secret thật. RedisBloom phải được cài nếu bật Bloom pre-check.

## 4. Observability

- Metrics: API RPS/latency/error; snapshot hit/miss/version drift; AST parse/eval error;
  budget guard OK/DEGRADED/exhausted; reserve outcomes; Redis Lua result code; producer
  send failure; consumer lag/retry/parking-lot; claim pending age; reservation expiry;
  markdown schedule lag; budget drift/repair/failure; cleanup count.
- Structured logs: request/correlation/workflow ID, event ID, order/promotion/claim ID,
  action scope, rule/budget version, Lua code, before/after repair. Redact token/PII.
- Trace spans: DB transaction, Redis pipeline/EVAL, Kafka send/consume, rule parse/evaluate,
  Temporal activity, reconciliation rebuild.
- Các metric bắt buộc từ thiết kế cũ:
  `promotion_budget_drift_count`, `promotion_budget_repair_count`,
  `promotion_budget_repair_failed_count`, `snapshot_cache.hit/miss`.

## 5. Alerts

| Alert | Condition gợi ý | Severity | Owner | Runbook |
|---|---|---|---|---|
| PostgreSQL unavailable/pool exhausted | readiness fail hoặc saturation kéo dài | Critical | Promotion/DB | RB-01 |
| Redis reserve/claim failure | EVAL errors/timeout trên write path | Critical | Promotion/Cache | RB-02 |
| Snapshot miss spike | >20%/5m bình thường hoặc >50% sau deploy | Warning | Promotion | RB-03 |
| Consumer lag/parking-lot | age vượt SLA hoặc có payload mismatch | High | Promotion/Event owner | RB-04 |
| Pending claim stale | oldest >5m hoặc backlog tăng | High | Promotion | RB-05 |
| Reservation cleanup failure | expired backlog/repair queue tăng | High | Promotion | RB-06 |
| Markdown expiry delayed | delay > fallback window | High | Promotion/Product | RB-07 |
| Budget drift repeated | cùng promotion drift sau nhiều repair | Critical | Promotion/DB | RB-08 |
| Direct-produce failure | send fail sau DB commit | High | Promotion/downstream | RB-09 |

Ngưỡng production ngoài các giá trị nêu trên và on-call owner cụ thể chưa chốt.

## 6. Failure modes

| Failure | Hành vi |
|---|---|
| PostgreSQL unavailable | Fail command/query cần DB; không tự lấy Redis làm source of truth |
| Redis snapshot unavailable | Discovery/calculate có thể đọc DB; latency tăng |
| Redis budget unavailable | Discovery guard fail-open; Reserve/confirm/cancel phải fail/retry, không đoán |
| Redis mất data | Rebuild snapshot/budget/schedule; claim/reservation thiếu payload vào recovery/audit |
| Kafka unavailable | DB write có thể đã commit nhưng event direct-produce bị mất; alert/recovery thủ công |
| Claim API crash sau SPOP | Pending sweeper republish, không trả claim về pool |
| DB fail sau Redis confirm/refund | Không coi complete; replay/repair/reconciliation theo order/promotion |
| Reservation payload mất | Clean index, enqueue reconciliation; không giả vờ release đủ |
| Rule AST hỏng | Từ chối create/update; nếu dữ liệu đã hỏng thì fail rule có telemetry |
| Mixed version | Consumer phải version-check; unsupported schema không xử lý như v1 |

## 7. Degraded behavior

- Discovery có thể tiếp tục khi Budget Guard Redis timeout: trả `DEGRADED`; Reserve vẫn
  là authoritative nên correctness không đổi, UX có thể stale.
- Snapshot cache lỗi có thể fallback PostgreSQL; không có local JVM AST cache.
- Kafka lỗi không nên rollback DB command đã commit, nhưng downstream markdown/analytics
  có thể stale/mất event vì chưa có outbox.
- Product/Search không được tự tính voucher để bù Promotion outage.
- Không fail-open Reserve, confirm, cancel hoặc refund mutation.

## 8. Scaling và capacity

- API stateless scale theo RPS/CPU/Redis latency/DB pool.
- Claim consumer scale theo Kafka partitions; partition key claim ID. Order refund theo
  order ID; markdown theo rule ID.
- Batch mặc định: pending/reservation tối đa 1.000; markdown expiry tối đa 200.
- Claim SPOP O(1), pending/schedule ZSET O(logN). Không quét toàn Hash/keyspace.
- Snapshot response giới hạn 20 eligible + 10 ineligible.
- Redis Lua chặn event loop trong thời gian chạy; script phải bounded. Redis Cluster
  hash-slot của multi-key script là constraint chưa giải quyết.
- PostgreSQL pool/index và Kafka partition count phải được capacity test; tuyên bố “hàng
  trăm nghìn TPS” của thiết kế cũ chưa có benchmark nên không phải SLO.

## 9. Deployment và rollback

1. Tạo migration additive/constraint/index trước code phụ thuộc.
2. Deploy consumer chấp nhận version mới trước producer.
3. Deploy API/worker, rebuild Redis snapshot/budget/markdown index, kiểm tra drift.
4. Bật scheduler/consumer theo feature flag; canary discovery rồi mutation path.
5. Rollback code chỉ khi schema backward-compatible; không rollback migration destructive.

Direct-produce và Redis key-format changes cần dual-read/write hoặc drain/migration plan.
Không xóa Redis key/index hoặc Kafka offset thủ công khi chưa snapshot và có runbook.

## 10. Runbooks

- **RB-01 DB:** kiểm tra health/pool/locks read-only → giảm traffic/khôi phục DB → xác
  nhận API và transaction idempotency → escalate DB owner.
- **RB-02 Redis mutation:** kiểm tra cluster/slot/latency/EVAL errors → dừng mutation
  nếu invariants không bảo đảm → restore/rebuild → chạy reconciliation → xác nhận counter.
- **RB-03 Cache:** so hit/miss/version, Redis failover và TTL → không warm hàng loạt;
  cho cache-aside tự hồi hoặc invalidate có kiểm soát → xác nhận hit rate.
- **RB-04 Consumer:** kiểm tra lag/event version/payload hash → retry transient; mismatch
  vào parking-lot, không overwrite → replay đúng scope → xác nhận DB gate.
- **RB-05 Claim:** đọc oldest pending và claim DB → republish giữ claim ID/event ID mới;
  không SADD vé trở lại → chỉ cleanup sau DB ack.
- **RB-06 Reservation:** kiểm tra DB FINAL gate rồi payload/index → release khi đủ payload;
  thiếu payload enqueue repair → xác nhận reserved giảm và idempotency tồn tại.
- **RB-07 Markdown:** kiểm tra DB status/end, ZSET, lock, Kafka/Product lag → chạy fallback
  guarded → replay state phù hợp → xác nhận Product price.
- **RB-08 Reconciliation:** dựng expected DB + valid reservations → lock/re-read → repair
  Redis một chiều → audit before/after; drift tái diễn escalate.
- **RB-09 Lost event:** xác định DB commit và consumer state → phối hợp replay snapshot/event
  có business ID/version phù → xác nhận downstream; không bịa event từ Redis.

## 11. Workers và scheduled jobs

| Worker/job | Trigger | Concurrency/idempotency | Retry/cleanup |
|---|---|---|---|
| Direct producer | Sau DB commit | Event/business ID | Best-effort; alert send fail |
| Order refund consumer | `t.order.events`, group `promo-order-consumer` | DB event/action gate; order key | retry transient; mismatch parking-lot |
| Auth consumer | `t.auth.events`, group `promo-auth-consumer` | unique wallet | optional/feature flag |
| Product state consumer | `t.product.events`, group `promo-product-state-consumer` | event+product guard | ACTIVE mới xử lý |
| Claim consumer | coupon topic, `promo-claim-consumer` | event+claim guard | cleanup pending sau commit |
| Snapshot monitor | mỗi 5m | metric-only | không auto-warm |
| Pending claim sweeper | mỗi 1m, stale 5m | batch/replay-safe | giữ pending tới ack |
| Reservation sweeper | mỗi 1m, batch 1000 | DB FINAL + Lua | missing payload → repair |
| Markdown scheduler | 250ms–1s, batch 200 | Redis lock + DB guard | ZREM sau commit |
| Markdown fallback | 10–30s | `SKIP LOCKED` + DB guard | self-heal index miss |
| Budget reconciliation | 15m + daily full scan | per-promotion lock/re-read | DB→Redis, audit |
| Claim cleanup | nightly | campaign/end retention guard | hard delete >30d |
| Idempotency cleanup | daily | retention boundary | keep ≥ refund+15d, default 45d |
