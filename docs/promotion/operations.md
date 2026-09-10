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
| Reservation release delay | Dưới một sweep window trong điều kiện bình thường |
| Campaign boundary delay | loop 250 ms–1 s; fallback 10–30 s |
| Outbox relay age | Oldest unrelayed row và connector lag |
| Product markdown convergence | Outbox create → Product apply theo source revision |
| Budget drift | Qty/VND trước-sau repair và drift tái diễn |

ID user/order/promotion/claim không dùng làm metric label cardinality cao.

## 2. Health

- Liveness không phụ thuộc network.
- API readiness fail khi PostgreSQL không dùng được. Redis write-path failure làm
  reserve/claim unavailable; snapshot read có thể fallback DB.
- Consumer readiness phản ánh broker/subscription.
- Scheduler readiness phản ánh PostgreSQL, Redis và độ trễ fallback.
- Startup probe chờ migration và campaign ZSET rebuild bắt buộc.
- Debezium/Kafka Connect health được giám sát như dependency của event propagation.

## 3. Configuration

| Property logic | Default/constraint |
|---|---|
| Snapshot TTL | 3600s |
| Reservation business TTL | 15m |
| Reservation cleanup grace | Phải dài hơn race/retry window; chưa chốt số |
| Pending rescue age | 5m |
| Campaign schedule loop | 250ms–1s |
| Campaign DB fallback | 10–30s |
| Discovery rate limit | 20/user/min |
| Outbox retention | Theo CDC watermark + replay/technical-compensation horizon |
| Topic/group/connector | Bắt buộc cấu hình rõ, không hard-code secret |

Mọi timestamp dùng UTC. Redis/DB money dùng integer VND.

## 4. Observability

- Metrics: API latency/error; AST parse/eval; snapshot hit/miss/version drift; claim
  outcome/pending age; Redis Lua codes; reservation expiry; campaign schedule lag/stale
  revision; fallback repair; outbox oldest age/rows; Debezium/consumer lag; Product stale
  revision drop; budget drift/repair.
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
| Reservation cleanup failure | due backlog/repair queue tăng | High |
| Campaign boundary delayed | vượt fallback window | High |
| Stale schedule revision spike | nhiều stale member bất thường | Warning |
| Outbox/CDC lag | oldest row vượt propagation SLO | High |
| Product markdown divergence | expected revision/state không hội tụ | Critical |
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
| Claim API crash sau SPOP | Pending sweeper republish, không trả claim vào pool |
| DB fail sau Redis confirm/cancel-confirmed | Không coi complete; replay/reconcile |
| Reservation payload mất | Enqueue repair; không đoán amount |
| Customer refund event đến Promotion | ACK/no-route theo deployment; alert contract mismatch, không mutate |

## 7. Degraded behavior

- Budget guard discovery fail-open; Reserve không fail-open.
- Snapshot miss fallback PostgreSQL; không có JVM AST cache.
- Outbox/CDC lag làm markdown downstream stale trong thời gian hữu hạn nhưng không mất
  event đã commit; replay full snapshot để hội tụ.
- API eligibility dùng DB time nên scheduler lag không kéo dài claim window.
- Product/Search không tự chạy voucher Rule Engine.
- Không fail-open confirm/cancel/`cancel-confirmed` mutation.

## 8. Scaling/capacity

- API stateless scale ngang.
- Claim partition theo claim ID; markdown partition bắt buộc theo product ID.
- SPOP O(1); pending/reservation/campaign schedule ZSET O(logN); không key scan.
- Multi-key Lua phải dùng `{promotion_id}` và bounded input/runtime.
- Campaign scheduler xử lý batch hữu hạn; product advisory lock tránh reorder cùng product.
- PostgreSQL pool/index, Redis memory, outbox growth và Kafka partition cần load test.

## 9. Deployment/rollback

1. Tạo additive migration cho bảy bảng, constraints, sequence/index.
2. Deploy Product consumer tương thích full snapshot trước producer nếu contract đổi.
3. Cấu hình outbox connector/ACL/topic và kiểm thử commit→CDC.
4. Deploy API/worker; preload claim/budget và rebuild campaign schedule.
5. Canary discovery rồi mutation; bật schedule/fallback/outbox theo feature flag.
6. Chỉ trim legacy keys/tables sau dual-read/drift verification; migration destructive cần
   kế hoạch riêng.

Không xóa offset, outbox, pending claim, reservation hoặc ZSET thủ công thiếu runbook.

## 10. Runbooks

- **RB-01 DB:** kiểm tra health/pool/lock → giảm traffic/khôi phục → xác nhận transaction
  và outbox atomicity.
- **RB-02 Redis mutation:** kiểm tra cluster slot/EVAL/latency → dừng unsafe mutation →
  restore/rebuild → reconciliation.
- **RB-03 Claim:** đọc pending + DB claim/wallet → republish immutable claim ID → chỉ
  cleanup sau DB ack.
- **RB-04 Reservation:** đọc payload/index + DB wallet/outbox → release/retry/repair; không
  sửa counter mù.
- **RB-05 Campaign schedule:** đọc DB revision/status/time trước ZSET → rebuild missing
  members → chạy fallback → xác nhận outbox state.
- **RB-06 Outbox/CDC:** dừng trimmer → kiểm tra WAL/connector/offset/topic → resume/replay
  full snapshot → xác nhận Product source revision.
- **RB-07 Reconciliation:** dựng expected từ DB + valid reservations → lock/re-read →
  repair Redis một chiều, audit before/after.
- **RB-08 Technical compensation:** xác minh order chưa hoàn tất, confirm evidence và
  dedup key → retry `cancel-confirmed` → kiểm tra wallet SAVED/EXPIRED + budget.

## 11. Workers/jobs

| Worker/job | Trigger | Idempotency/recovery |
|---|---|---|
| Claim consumer | internal claim topic | guarded claim/wallet; pending cleanup sau commit |
| Pending claim sweeper | mỗi 1m, stale 5m | republish cùng claim ID |
| Reservation sweeper | mỗi 1m | per-promotion index + Lua; missing payload → repair |
| Campaign scheduler | 250ms–1s | DB revision/time + outbox dedup; ZREM sau commit |
| Campaign fallback | 10–30s | DB source; repair missing/stale schedule |
| Schedule bootstrap | startup/failover | rebuild ZSET từ PostgreSQL |
| Outbox CDC relay | WAL | at-least-once; connector retry/DLQ |
| Budget reconciliation | recent 15m + off-peak full scan | DB→Redis, per-promotion lock |
| Snapshot monitor | mỗi 5m | metric only, không auto-warm hàng loạt |
| Wallet expiry normalization | lazy/optional batch | eligibility luôn đọc `expired_at` |
| Outbox trimmer | policy schedule | CDC watermark + replay horizon guard |

Không có Order refund consumer hoặc idempotency cleanup job riêng.
