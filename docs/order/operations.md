---
doc_id: order-operations
service: order
scope: target
status: implemented
owners:
  - Order Service team
evidence:
  - java/services/order-service/src/main/java/com/ecommerce/order
verified_against_commit: null
---

# Order Service — Vận hành

> Worker, timeout, cleanup và config runtime đã được implement tại
> `java/services/order-service` (bảng §2). SLO, ngưỡng cảnh báo và runbook production vẫn là
> acceptance criteria chưa chốt.

## 1. SLO, SLI và health

Cần định nghĩa SLI/SLO cho HTTP availability/error/latency; search p95/p99 theo
class A/B; PostgreSQL pool; Redis request-replay errors; Kafka consumer lag/oldest event;
outbox-to-Kafka age/receipt age; Temporal schedule-to-start/activity/workflow completion;
workflow-message oldest pending age; checkout success/timeout/cancel fact/forward repair;
refund wait age; capture-after-terminal anomaly và độ trễ xác nhận giao hàng (dispatch → Admin confirmation). Error budget và
ngưỡng số chưa được chốt.

Liveness chỉ kiểm tra process. Readiness write API cần PostgreSQL; Temporal outage
không làm mất accepted command vì dispatch/message đã durable, nhưng phải được expose
qua dependency health. Read API phụ thuộc PostgreSQL. Redis lỗi có thể bỏ request replay
fast-path nhưng không được bỏ FSM safety; Kafka/CDC/consumer/Temporal worker health nên báo riêng
để tránh loại HTTP read instance không cần thiết. Startup probe phải chờ migration/
config, nhưng chi tiết chưa có evidence.

## 2. Cấu hình

Nguồn: `java/services/order-service/src/main/resources/application.yml`. Mọi thay đổi cần
restart process.

| Property / env | Mục đích | Default | Secret |
|---|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `DB_POOL_SIZE` | PostgreSQL source of truth/read path, `READ COMMITTED` | `jdbc:postgresql://localhost:15432/order_service`, pool 24 | Có |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `ORDER_REDIS_MIRROR_ENABLED` | Request replay mirrors, timeout 200 ms, fail-open | `localhost:6379`, bật; `order.redis-mirror.ttl=7d` | Có |
| `KAFKA_BROKERS`, `ORDER_CONSUMERS_ENABLED` | Ba consumer group (`order-payment-consumer`, `order-inventory-ship-consumer`, `order-inventory-return-consumer`) + receipt observer `order-outbox-receipt-observer` | `localhost:29092`, bật | Có (khi có security) |
| `TEMPORAL_TARGET`, `TEMPORAL_NAMESPACE`, `TEMPORAL_ENABLED`, `TEMPORAL_WORKER_ENABLED` | Temporal client + worker ba queue `order-checkout-queue`, `order-refund-queue`, `order-financial-reconciliation-queue` | `localhost:7233`, `default`, bật | Có (TLS khi có) |
| `order.temporal.activity-timeout`, `activity-max-attempts` | Activity start-to-close/retry burst | 30 giây, 3 lần; hết burst thì workflow sleep có backoff rồi thử lại | Không |
| `order.checkout.payment-window`, `clock-skew-safety`, `submit-wait` | Business ceiling; safety trừ vào hold expiry; thời gian HTTP chờ `PAYMENT_READY` | 15 phút, 30 giây, 12 giây (mesh timeout 15 giây) | Không |
| `ORDER_SUBMISSION_MAX_WAITERS`, `ORDER_SUBMISSION_RESPONSE_THREADS` | `order.checkout.waiter.max-waiters`/`response-threads`, giới hạn RAM waiter và executor mỗi replica | 512 waiter, 16 thread; queue `max-waiters + 1` | Không |
| `order.checkout.waiter.batch-size`, `recovery-interval`, `reconnect-delay` | Batch đọc bù token terminal; chu kỳ phục hồi và nối lại LISTEN | 256 token, 1 giây, 1 giây | Không |
| `order.refund.inspection-wait` | Inventory inspection gate trước manual verification | 30 ngày | Không |
| `order.search.default-window` | Khoảng mặc định khi thiếu `created_from/to` | 90 ngày | Không |
| `order.fulfillment.delivery-confirmation-overdue`, `delivered-at-future-skew` | Ngưỡng của gauge `order.fulfillment.delivery_confirmation_overdue` (line `DISPATCHED` chưa được Admin xác nhận giao; không bao giờ tự giao); sai lệch tối đa của `delivered_at` ở tương lai | 7 ngày, 5 phút | Không |
| `INVENTORY_BASE_URL`, `PROMOTION_BASE_URL`, `PAYMENT_BASE_URL`, `order.clients.*-timeout` | Remote activities; gửi `X-Service-Identity: order-service` | `:8094`, `:8085`, `:8089`; connect 2 giây, read 10 giây | Không |
| `ORDER_FRONTEND_BASE_URL` | Dựng `order_url`/`review_url` cho notification | `http://localhost:3000` | Không |
| `WORKERS_ENABLED`, `order.workers.*` | Dispatcher (poll 200 ms + nudge sau commit, lease 30 giây), reconciler 30 giây | bật | Không |
| `ORDER_DISPATCH_THREADS` | Giới hạn start RPC đồng thời của dispatcher, worker không giữ JDBC connection | 8 | Không |
| `ORDER_DISPATCH_ACK_BATCH_SIZE`, `ORDER_DISPATCH_ACK_INTERVAL` | Gom kết quả start và ghi một transaction; flush khi đủ row, hết interval hoặc nhóm start đã xong | 50, `PT0.02S` | Không |
| `order.outbox.retention`, `receipt-safety-delay`, `trim-cron` | Trimmer chỉ xoá khi có exact receipt | 15 ngày, 1 giờ, `0 0 4 * * *` | Không |

Checkout workflow không dùng Workflow Execution Timeout; Continue-As-New khi history vượt
8.000 event. Không ghi secret thật vào repository.

## 3. Quan sát hệ thống

Dispatcher start dùng bounded executor và batched ACK theo [ADR-028](./decisions/ADR-028-parallel-workflow-starts-and-batched-ack.md).
Coordinator renew row chưa ACK mỗi lease/3; ACK/renew kiểm tra owner + `attempts` để từ chối kết quả
stale sau reclaim. Theo dõi `order.workflow.dispatch.active`, distribution summary
`order.workflow.dispatch.ack.batch` và counter `order.workflow.dispatch.ack.stale`, cùng tuổi outbox.
Message `SignalWithStart` vẫn chạy từng row trên coordinator sau nhóm start.

Checkout checkpoint dùng async HTTP waiter ([ADR-027](./decisions/ADR-027-async-submission-checkpoint-waiters.md)).
Mỗi replica mở thêm **một JDBC LISTEN session ngoài Hikari**; tính connection này vào tổng budget DB.
Listener dùng `spring.datasource.url/username/password`; TLS/schema phải có trong URL nếu cần,
và connection phải giữ session (không transaction-pooling). Migration V3 additive; rollback binary
giữ trigger/Flyway history. Listener hỏng vẫn có shared batch recovery mỗi giây; instance chết thì
Checkout GET cùng token qua replica khác hoặc sau restart. Một replica Compose không có HA.

- `order.checkout.waiters`: số HTTP waiter đang chiếm capacity; cleanup ở result/error/timeout/shutdown.
- `order.checkout.response.active`/`queued`: executor đang xử lý/đang chờ, Payment chậm có thể làm đầy.
- `order.checkout.waiter.rejected`: capacity/queue đầy, trả `503 ORDER_UNAVAILABLE`, `Retry-After: 2`.
- `order.checkout.notification.connected`: 0/1; nếu 0 kéo dài, kiểm tra DB session và log SQLState.
  Notification không phải nguồn kết quả; không cần khôi phục RAM registry sau restart.

Metrics tối thiểu:

- HTTP request/error/latency theo route/status/role scope, không label bằng order ID.
- Lời gọi remote: timer `order.remote.http{dependency,method,outcome,status}` (outcome là phân loại
  `ACCEPTED`/`REJECTED`/`UNKNOWN`; `status` là `2xx`/`4xx`/`5xx`/`none`), không label bằng path hay ID
  ([ADR-024](./decisions/ADR-024-remote-call-metrics-and-bulkhead.md)). `outcome=UNKNOWN,status=none` tăng đều là
  timeout, lỗi kết nối, `DEADLINE_EXHAUSTED` hoặc `BULKHEAD_FULL`; giới hạn đồng thời là
  `order.clients.max-concurrent` (mặc định 64 mỗi provider).
- Operation age/outcome theo command type và payment-attempt create/replay/conflict,
  không label bằng operation/order ID.
- DB pool usage/timeout, transaction/lock wait/deadlock, search latency/rows và GIN
  index/disk growth.
- Redis request-replay hit/miss/error theo operation, không theo key; không có lock-contention metric.
- Kafka consume result/retry/lag/oldest age/duplicate và CDC connector lag/error.
- Temporal workflow started/completed/failed/timed-out, activity retry, signal route
  miss, compensation failure, workflow age theo type.
- Fulfilment: counter `order.fulfillment.dispatched`/`delivered`, gauge `order.fulfillment.delivery_confirmation_overdue`.
- Business counters: order created/paid/cancelled/completed, capture-after-terminal anomaly,
  return rejected, refund waiting/processing/succeeded/failed, oldest refund wait.
- Scope finalization status/oldest pending age, recovery/conflict count and no-payout
  closure outcomes; include financially `SUCCEEDED`/`FAILED` cases in this measurement.
- Workflow-message pending/retry/oldest age, `SignalWithStart` duplicate/no-op và
  pending-prerequisite proof age.
- Outbox row count/oldest age, receipt lag theo destination và cleanup
  deleted/skipped-without-receipt.

Structured log/traces cần correlation/request/event/workflow/order reference và
transition old/new. Dòng log trong phạm vi một request có `request_id` và `trace_id` (pattern
`logging.pattern.level`, MDC do `RequestCorrelationFilter` đặt; [contracts ADR-002](../../contracts/decisions/ADR-002-correlation-and-trace-propagation.md));
dòng log của Temporal activity không có hai trường này và được tra bằng Workflow ID. Hash/redact user reference; không log delivery address, phone,
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
| Delivery confirmation overdue | `order.fulfillment.delivery_confirmation_overdue` > 0 (line `DISPATCHED` quá 7 ngày chưa xác nhận giao) | Trung bình | Warehouse/Ops Admin: xác nhận giao qua `delivery-confirmations` hoặc điều tra thất lạc |
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
| Admin quên xác nhận giao | Đơn ở `PROCESSING` không tự hoàn tất, Review/Buyer return chưa mở; gauge quá hạn cảnh báo; hệ thống không suy ra `DELIVERED` từ thời gian hay từ dispatch |
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
| Inventory dispatch consumer | `com.ecommerce.inventory.reservation.line-dispatched.v1` | Durable inbox + `reservation_revision` + line/warehouse guard | `DISPATCHED` (không bao giờ `DELIVERED`/`COMPLETED`) |
| Inventory return consumer | `com.ecommerce.inventory.refund.all-items-inspection-completed.v1` | Durable inbox + scope revision | Persist full evidence + workflow message in one transaction |
| Workflow start dispatcher | `order_workflow_dispatches` | Fixed workflow ID/input digest | Start/attach retry after API crash |
| Workflow message dispatcher | `order_workflow_messages` | Stable message ID + `SignalWithStart`; DB leases with `SKIP LOCKED` | Retry until Temporal durable acknowledgement; duplicate history is no-op |
| Prerequisite/convergence reconciler | Payment fact chờ identity bind, open required obligations, orders `PROCESSING` mà mọi line đã `DELIVERED` | Order lock + deterministic guards | Replay identity bind; invoke `TryCompleteOrder`; never infer evidence |
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
