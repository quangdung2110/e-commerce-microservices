---
doc_id: order-operations
service: order
scope: target
status: proposed
owners:
  - Order Service team
evidence: []
verified_against_commit: null
---

# Order Service — Vận hành

> Thiết kế cũ mô tả worker, timeout và cleanup nhưng chưa chốt SLO, config runtime
> hay runbook production. Các mục “cần” dưới đây là acceptance criteria, không phải
> cam kết đã triển khai.

## 1. SLO, SLI và health

Cần định nghĩa SLI/SLO cho HTTP availability/error/latency; search p95/p99 theo
class A/B; PostgreSQL pool; Redis request-replay errors; Kafka consumer lag/oldest event;
outbox-to-Kafka age/receipt age; Temporal schedule-to-start/activity/workflow completion;
workflow-message oldest pending age; checkout success/timeout/cancel fact/forward repair;
refund wait age; capture-after-terminal anomaly và auto-complete delay. Error budget và
ngưỡng số chưa được chốt.

Liveness chỉ kiểm tra process. Readiness write API cần PostgreSQL; Temporal outage
không làm mất accepted command vì dispatch/message đã durable, nhưng phải được expose
qua dependency health. Read API phụ thuộc PostgreSQL. Redis lỗi có thể bỏ request replay
fast-path nhưng không được bỏ FSM safety; Kafka/CDC/consumer/Temporal worker health nên báo riêng
để tránh loại HTTP read instance không cần thiết. Startup probe phải chờ migration/
config, nhưng chi tiết chưa có evidence.

## 2. Cấu hình cần công bố

| Nhóm property | Mục đích | Giá trị thiết kế hiện biết | Secret |
|---|---|---|---|
| PostgreSQL DSN/pool/timeouts | Source of truth/read path | Chưa chốt | Có |
| Redis endpoint/auth | Request replay mirrors | TTL request 7 ngày; DB fallback khi Redis lỗi | Có |
| Kafka brokers/security | Consume/produce via CDC | Topics trong `interfaces.md` | Có |
| Consumer group IDs | Payment/Inventory/Delivery workers | Bốn group đã nêu | Không |
| Temporal endpoint/namespace/TLS | Saga runtime | Chưa chốt | Có |
| Temporal task queues | Route workflow/activity | Ba queue cố định | Không |
| Activity timeout/retry | Remote/local activity | 30 giây, tối đa 3 lần | Không |
| Checkout payment window/clock skew | Timer | Business ceiling 15 phút; cap bởi earliest hold expiry minus safety 30 giây (configurable, không được âm) | Không |
| Checkout workflow execution timeout | Full workflow | Không dùng làm correctness boundary; continue-as-new/reconciler | Không |
| Refund return wait | Inventory gate | 30 ngày | Không |
| Search default window | Performance bound | 90 ngày | Không |
| Outbox retention/schedule | CDC cleanup | Minimum 15 ngày + exact destination receipt + safety delay; ví dụ 04:00 hàng ngày | Không |

Tên environment variable, default, restart requirement và secret manager chưa
được xác minh; không ghi secret thật vào repository.

## 3. Quan sát hệ thống

Metrics tối thiểu:

- HTTP request/error/latency theo route/status/role scope, không label bằng order ID.
- Operation age/outcome theo command type và payment-attempt create/replay/conflict,
  không label bằng operation/order ID.
- DB pool usage/timeout, transaction/lock wait/deadlock, search latency/rows và GIN
  index/disk growth.
- Redis request-replay hit/miss/error theo operation, không theo key; không có lock-contention metric.
- Kafka consume result/retry/lag/oldest age/duplicate và CDC connector lag/error.
- Temporal workflow started/completed/failed/timed-out, activity retry, signal route
  miss, compensation failure, workflow age theo type.
- Business counters: order created/paid/cancelled/completed, capture-after-terminal anomaly,
  return rejected, refund waiting/processing/succeeded/failed, oldest refund wait.
- Scope finalization status/oldest pending age, recovery/conflict count and no-payout
  closure outcomes; include financially `SUCCEEDED`/`FAILED` cases in this measurement.
- Workflow-message pending/retry/oldest age, `SignalWithStart` duplicate/no-op và
  pending-prerequisite proof age.
- Outbox row count/oldest age, receipt lag theo destination và cleanup
  deleted/skipped-without-receipt.

Structured log/traces cần correlation/request/event/workflow/order reference và
transition old/new. Hash/redact user reference; không log delivery address, phone,
client secret, raw reason detail hay provider secrets. Trace bao phủ HTTP -> workflow,
activity remote call, Kafka consume -> workflow message -> dispatcher signal, DB transaction
và CDC publish.

## 4. Cảnh báo

| Cảnh báo | Điều kiện đo cần chốt | Mức độ | Owner/runbook |
|---|---|---|---|
| Payment consumer lag | Lag/oldest event vượt payment SLO | Cao | Order on-call / consumer lag |
| Inventory consumer lag | Ship/return workflow message trễ vượt SLO | Cao | Order + Inventory |
| Temporal task queue backlog | Schedule-to-start hoặc workflow age vượt ngưỡng | Cao | Order on-call |
| Compensation failed | Activity cạn retry | Critical | Saga recovery |
| Capture after terminal non-capture | Obligation mới hoặc chưa resolve; order không được hồi sinh | Critical | Payment + Finance/Ops |
| Refund waiting overdue | Gần/quá 30 ngày không confirmation | Cao | Returns Ops |
| Refund failed/reversed | Event critical lane xuất hiện | Critical | Payment/Ops |
| Refund scope finalization unresolved | Three Activity attempts exhausted, durable guard conflict, mismatched receipt, or pending age beyond agreed budget | Cao | Order on-call + Inventory; frozen command query/replay and audited reconciliation |
| CDC/outbox lag | Oldest outbox chưa publish vượt ngưỡng | Cao | Data platform/Order |
| DB pool/deadlock | Saturation/lock wait vượt ngưỡng | Cao | Database exhaustion |
| Redis unavailable | Error rate liên tục; request replay fast path mất | Trung bình | DB fallback và tăng tải đọc/lock |
| Search latency/disk | p99 hoặc trigram index disk vượt budget | Trung bình | Query capacity |

## 5. Failure mode và hành vi suy giảm

| Failure | Hành vi an toàn |
|---|---|
| PostgreSQL unavailable/pool exhausted | Không ready cho API; activity/consumer không ack/không commit; Temporal retry |
| Redis unavailable | Fallback vào durable request record/DB FSM/WorkflowID; safety vẫn giữ, nhưng replay nhanh mất và tải DB tăng. Không được từ chối write nếu fallback đã được chứng minh an toàn |
| Kafka unavailable/consumer lag | API/local transaction có thể tiếp tục ghi outbox; downstream/event workflow message trễ; alert, không xóa outbox |
| Debezium/Kafka Connect lỗi | Outbox backlog tăng; không publish tay bằng cách phá ordering; phục hồi connector từ WAL/offset |
| Temporal unavailable | Read API còn có thể phục vụ; command start/signal trả lỗi/202 chỉ khi đã có durable acceptance; không commit terminal state inline |
| Inventory timeout | Query/replay same durable command; unknown outcome never authorizes compensation. Pre-payment cleanup requires proven outcome/non-capture; post-capture uses forward repair. Buyer refund keeps waiting for validated inspection evidence |
| Promotion timeout/failure | Pre-payment rollback và structured recalculation; final activity retry cùng stable key/receipt; post-payment forward repair or financial reconciliation, never technical cancellation; customer refund không gọi Promotion |
| Payment timeout | Initiate/cancel/refund retry/query theo stable key; Order remains cancelling/reconciling until provider fact, không tạo transaction/refund thứ hai |
| Unknown Inventory/Promotion result | Query/replay same durable command; never compensate by assuming remote mutation failed |
| Inventory scope close/cancel unknown or unavailable | Preserve financial result and frozen finalization intent; query/replay same command. After three attempts mark `RECOVERY_REQUIRED`, retain durable dispatch and alert; never repeat Payment |
| Scope close/cancel durable 409 or receipt mismatch | Set `CONFLICT`, park operation for manual review and reconcile current scope/counters; no endpoint fallback, auto-payout or replacement of an unknown/accepted command |
| Workflow start failure after accepted API | Dispatcher retries/attaches durable workflow dispatch; no accepted command is lost |
| DB commit before Temporal RPC / RPC response lost | Workflow-message dispatcher retries `SignalWithStart` với cùng scoped `message_id`; workflow state carries dedup across Continue-As-New and activity remains DB-idempotent; không ACK/xóa message khi chưa có durable acknowledgement |
| Workflow target đã closed trước dispatch | Reuse policy cấm start lại; reconcile DB proof để mark applied/stale no-op hoặc atomic reroute sang named recovery owner; ambiguous giữ retry/quarantine + alert |
| Delivery proof before dispatch | Persist `PENDING_PREREQUISITE`; dispatch consumer/replayer validate mapping rồi apply proof và chạy convergence |
| Mixed-version deploy | Workflow code phải replay-compatible; consumer validates additive financial fields/version; additive rollout |
| Search extension/index lỗi | Không có ES fallback; fail có quan sát hoặc giới hạn query theo policy |

Inventory Reserve/Commit/Release results have no Kafka acknowledgment path.
Use the persisted command checkpoint to query/replay the exact ID/body; a missing query
result alone cannot prove an in-flight command failed. Retain durable repair work and
alert after retry exhaustion. Reconcile current reservation state separately from the
original receipt. The Inventory inbox/DLQ handles only physical dispatch and selected-scope
inspection completion ([Inventory ADR-020](../inventory/decisions/ADR-020-command-receipts-and-minimal-order-events.md)).

## 6. Scaling và capacity

- HTTP/read scale theo RPS/CPU/DB pool; tổng connection không vượt PostgreSQL budget.
- Search class B và GIN disk là bottleneck chính; benchmark 90-day buyer window và
  admin full-scope riêng. Subquery card là N+1-style trong một SQL và cần EXPLAIN.
- Kafka consumer concurrency không vượt partition hữu ích; vẫn giữ order key ordering.
- Temporal worker scale theo task queue/activity latency; remote downstream rate
  limit và DB pool giới hạn concurrency.
- Refund workflow ngủ 30 ngày không giữ thread nhưng tăng Temporal history/storage;
  cần capacity assumption và continue-as-new policy nếu history lớn.
- Outbox retention chỉ 15 ngày là minimum age; row không có exact destination receipt
  không bao giờ bị trimmer xóa, nên observer/connector outage đổi thành storage-growth
  incident thay vì data loss.

Chưa có load test, partition count, pool size, RPS hay storage growth evidence.

## 7. Triển khai và rollback

Migration phải additive/backward-compatible trước khi code đọc/ghi field mới.
Backfill `buyer_return_locked_at` trước khi drop hai bảng idempotency cũ. Tạo
extension/index lớn cần online/concurrent plan. Deploy workflow code phải giữ
Temporal determinism/replay compatibility; thay đổi không tương thích cần versioning
workflow/activity chứ không sửa history đang chạy.

Thứ tự mục tiêu: migration/extension -> backward-compatible app/worker -> connector/
consumer config -> quan sát -> dọn field/table cũ sau verification. Rollback không
được yêu cầu schema cũ sau destructive migration. Chưa có canary/feature flag plan.

## 8. Workers và tác vụ định kỳ

| Worker/job | Trigger/input | Concurrency/idempotency | Timeout/retry/output |
|---|---|---|---|
| Outbox CDC pipeline | WAL row `outbox_events` | Destination allowlist + partition key; connector offset | Kafka Connect retry/DLQ/backoff; Order facts hoặc Notification command topic |
| Outbox receipt observer | Messages on each allowed destination | PK event/topic, idempotent insert | Supplies per-row deletion proof; outage retains rows |
| Payment consumer | `t.payment.events.v2` | Exact accepted types; stream-scoped inbox + mapping/amount/currency/per-transaction version guard | Atomically persist evidence/workflow message; dispute chỉ update obligation evidence |
| Inventory dispatch consumer | `com.ecommerce.inventory.reservation.line-dispatched.v1` | Durable inbox + `reservation_revision` + line/warehouse guard | `DISPATCHED`, replay prerequisite proof, convergence |
| Delivery proof consumer | `t.delivery.order.events.v1` | Durable inbox + proof ID/digest + aggregate revision + line/warehouse guard | Park before dispatch; otherwise CAS `DELIVERED` + convergence |
| Inventory return consumer | `com.ecommerce.inventory.refund.all-items-inspection-completed.v1` | Durable inbox + scope revision | Persist full evidence + workflow message in one transaction |
| Workflow start dispatcher | `order_workflow_dispatches` | Fixed workflow ID/input digest | Start/attach retry after API crash |
| Workflow message dispatcher | `order_workflow_messages` | Stable message ID + `SignalWithStart`; DB leases with `SKIP LOCKED` | Retry until Temporal durable acknowledgement; duplicate history is no-op |
| Prerequisite/convergence reconciler | Pending proofs, open required obligations, deliverable orders | Order lock + deterministic guards | Replay proof; invoke `TryCompleteOrder`; never infer evidence |
| Refund scope finalization reconciler | Unresolved finalization metadata/checkpoints, including financially terminal Buyer cases | Same scope/generation/command; conflict requires audited decision | Restore durable finalization dispatch on `order-refund-queue`; never call Payment |
| Temporal worker | Three task queues | WorkflowID per checkout/order; idempotent activity | Activity 30s/3 retry burst; durable history/continue-as-new |
| Outbox trimmer | Hàng ngày, ví dụ 04:00 | Một execution logic; exact receipt guard | Xóa row >15 ngày + safety delay chỉ khi receipt khớp destination |

Không còn order metrics projector, search projection/reindex worker, Kafka CQRS
retry/DLQ hoặc cold archive batcher trong thiết kế hiện tại.

## 9. Runbook bắt buộc

Mỗi runbook cần symptom, kiểm tra read-only, mitigation, cách xác nhận phục hồi và
escalation owner. Tối thiểu phải có:

1. Payment/Inventory consumer lag: xác định partition/offset/event age, dependency,
   tăng worker an toàn, replay với dedup, xác minh workflow/FSM không double-apply.
2. Temporal workflow mắc kẹt/cancel or forward-repair fail: inspect history, dispatch
   and remote checkpoint; repair dependency, query/replay same activity key, không update
   DB thủ công thiếu inbox/outbox.
3. Refund quá 30 ngày/manual verification: đối chiếu selected locked scope và toàn bộ
   expected/received/passed/failed counters qua Inventory query, persist query checkpoint,
   rồi ghi Finance/Ops decision/evidence digest qua internal API. Full completion mới
   cho phép resume Payment gate; inspection `FAIL` không bỏ Payment. Nếu quyết định
   kết thúc return thiếu hàng không payout, dùng `CLOSE_RETURN_WITHOUT_REFUND` với
   bound request/scope/reason, chỉ khi không có refund transaction hoặc accepted/unknown
   Payment initiation. `IN_PROGRESS` cần mọi received unit final-inspected; `REGISTERED`
   zero receipt dùng cancel. Timer không tự đóng; unresolved decision chặn Payment.
   Case chỉ `CLOSED_INCOMPLETE` và reconciliation chỉ đóng sau Inventory receipt.
4. CDC/outbox backlog: dừng trimmer, kiểm tra connector/WAL/offset/topic, resume,
   xác minh sequence/duplicate downstream.
5. Redis corruption/outage: đánh giá request replay hit/miss và DB fallback,
   không flush broad keyspace, khôi phục rồi theo dõi replay/error rate.
6. DB pool/deadlock: xác định query/lock holder, giảm concurrency, không kill
   transaction mù, xác minh state/outbox atomicity.
7. Capture-after-terminal anomaly: xác nhận mapped attempt/transaction/version, terminal
   non-capture evidence và provider ledger; giữ order terminal, attach immutable evidence
   vào `CAPTURE_AFTER_TERMINAL_NON_CAPTURE`, chỉ xử lý qua authenticated decision API.
   Không sửa DB/hồi sinh order hoặc phát `order.paid` thủ công. Sau khi Payment/Accounting
   thực sự settle charge, attach settlement proof + accounting reference rồi dùng
   `CLOSE_WITH_EXTERNAL_FINANCIAL_REMEDIATION`; không close chỉ dựa ticket/comment.
8. Outbox retention cleanup failure: kiểm tra exact `(event_id,destination_topic)`
   receipt, connector/observer lag và oldest row; không dùng watermark thay receipt.
9. Scope finalization stuck after financial success/failure: inspect frozen generation,
   operation/reason/command/body digest, dispatch and Inventory command result. Replay an
   accepted command after response loss; unknown/503 uses the same key. Repair dispatcher/
   worker and let the finalization reconciler resume the same intent, including terminal
   financial cases. Persist only matching IDs/outcome/revision/receipt. Durable rejection
   requires an audited corrected decision before a new generation; retain earlier attempts.
   Never change `REFUNDED`, repeat Payment, reopen a closed scope or discard failed
   quarantine. After reversal preserve the original close reason and separate financial
   review. Verify operation progress and scope receipt, not Payment success alone.
