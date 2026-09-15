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
class A/B; PostgreSQL pool; Redis dedup/lock errors; Kafka consumer lag/oldest event;
outbox-to-Kafka age; Temporal schedule-to-start/activity/workflow completion;
checkout success/timeout/compensation; refund wait age; late-payment recovery và
auto-complete delay. Error budget và ngưỡng số chưa được chốt.

Liveness chỉ kiểm tra process. Readiness API ghi cần PostgreSQL và khả năng start/
signal Temporal; read API phụ thuộc PostgreSQL. Redis lỗi có thể bỏ fast-path nhưng
không được bỏ FSM safety; Kafka/CDC/consumer/Temporal worker health nên báo riêng
để tránh loại HTTP read instance không cần thiết. Startup probe phải chờ migration/
config, nhưng chi tiết chưa có evidence.

## 2. Cấu hình cần công bố

| Nhóm property | Mục đích | Giá trị thiết kế hiện biết | Secret |
|---|---|---|---|
| PostgreSQL DSN/pool/timeouts | Source of truth/read path | Chưa chốt | Có |
| Redis endpoint/auth | Dedup/locks | TTL request 7 ngày, event 30 ngày, FSM lock 10 giây | Có |
| Kafka brokers/security | Consume/produce via CDC | Topics trong `interfaces.md` | Có |
| Consumer group IDs | Payment/Inventory workers | Ba group đã nêu | Không |
| Temporal endpoint/namespace/TLS | Saga runtime | Chưa chốt | Có |
| Temporal task queues | Route workflow/activity | Ba queue cố định | Không |
| Activity timeout/retry | Remote/local activity | 30 giây, tối đa 3 lần | Không |
| Checkout payment window | Timer | 15 phút | Không |
| Checkout workflow timeout | Full workflow | 30 phút | Không |
| Refund return wait | Inventory gate | 30 ngày | Không |
| Search default window | Performance bound | 90 ngày | Không |
| Outbox retention/schedule | CDC cleanup | 15 ngày; ví dụ 04:00 hàng ngày | Không |

Tên environment variable, default, restart requirement và secret manager chưa
được xác minh; không ghi secret thật vào repository.

## 3. Quan sát hệ thống

Metrics tối thiểu:

- HTTP request/error/latency theo route/status/role scope, không label bằng order ID.
- DB pool usage/timeout, transaction/lock wait/deadlock, search latency/rows và GIN
  index/disk growth.
- Redis command error/dedup-hit/lock-contention theo operation, không theo key.
- Kafka consume result/retry/lag/oldest age/duplicate và CDC connector lag/error.
- Temporal workflow started/completed/failed/timed-out, activity retry, signal route
  miss, compensation failure, workflow age theo type.
- Business counters: order created/paid/cancelled/completed, late recovery outcome,
  return rejected, refund waiting/processing/succeeded/failed, oldest refund wait.
- Outbox row count/oldest age và cleanup deleted/skipped-by-watermark.

Structured log/traces cần correlation/request/event/workflow/order reference và
transition old/new. Hash/redact user reference; không log delivery address, phone,
client secret, raw reason detail hay provider secrets. Trace bao phủ HTTP -> workflow,
activity remote call, Kafka consume -> signal, DB transaction và CDC publish.

## 4. Cảnh báo

| Cảnh báo | Điều kiện đo cần chốt | Mức độ | Owner/runbook |
|---|---|---|---|
| Payment consumer lag | Lag/oldest event vượt payment SLO | Cao | Order on-call / consumer lag |
| Inventory consumer lag | Ship/return signal trễ vượt SLO | Cao | Order + Inventory |
| Temporal task queue backlog | Schedule-to-start hoặc workflow age vượt ngưỡng | Cao | Order on-call |
| Compensation failed | Activity cạn retry | Critical | Saga recovery |
| Late payment recovery failed | Chuyển manual verification, không auto-refund | Critical | Finance/Ops |
| Refund waiting overdue | Gần/quá 30 ngày không confirmation | Cao | Returns Ops |
| Refund failed/reversed | Event critical lane xuất hiện | Critical | Payment/Ops |
| CDC/outbox lag | Oldest outbox chưa publish vượt ngưỡng | Cao | Data platform/Order |
| DB pool/deadlock | Saturation/lock wait vượt ngưỡng | Cao | Database exhaustion |
| Redis unavailable | Error rate liên tục; dedup fast path mất | Trung bình/Cao | Degraded idempotency |
| Search latency/disk | p99 hoặc trigram index disk vượt budget | Trung bình | Query capacity |

## 5. Failure mode và hành vi suy giảm

| Failure | Hành vi an toàn |
|---|---|
| PostgreSQL unavailable/pool exhausted | Không ready cho API; activity/consumer không ack/không commit; Temporal retry |
| Redis unavailable | Không dựa vào fast-path; DB FSM/WorkflowID vẫn bảo vệ safety, nhưng khả năng nhận duplicate/tải tăng; có thể từ chối write nếu implementation không chứng minh fallback an toàn |
| Kafka unavailable/consumer lag | API/local transaction có thể tiếp tục ghi outbox; downstream/event signal trễ; alert, không xóa outbox |
| Debezium/Kafka Connect lỗi | Outbox backlog tăng; không publish tay bằng cách phá ordering; phục hồi connector từ WAL/offset |
| Temporal unavailable | Read API còn có thể phục vụ; command start/signal trả lỗi/202 chỉ khi đã có durable acceptance; không commit terminal state inline |
| Inventory timeout | Activity retry/compensate; refund tiếp tục chờ, không gọi Payment refund |
| Promotion timeout/failure | Pre-payment rollback và structured recalculation; final activity retry cùng stable key, replay durable receipt; post-payment technical compensation PostgreSQL-first; customer refund không gọi Promotion |
| Payment timeout | Initiate/cancel/refund retry theo stable key; không tạo transaction/refund thứ hai |
| Mixed-version deploy | Workflow code phải replay-compatible; event consumer ignore unknown fields; additive rollout |
| Search extension/index lỗi | Không có ES fallback; fail có quan sát hoặc giới hạn query theo policy |

## 6. Scaling và capacity

- HTTP/read scale theo RPS/CPU/DB pool; tổng connection không vượt PostgreSQL budget.
- Search class B và GIN disk là bottleneck chính; benchmark 90-day buyer window và
  admin full-scope riêng. Subquery card là N+1-style trong một SQL và cần EXPLAIN.
- Kafka consumer concurrency không vượt partition hữu ích; vẫn giữ order key ordering.
- Temporal worker scale theo task queue/activity latency; remote downstream rate
  limit và DB pool giới hạn concurrency.
- Refund workflow ngủ 30 ngày không giữ thread nhưng tăng Temporal history/storage;
  cần capacity assumption và continue-as-new policy nếu history lớn.
- Outbox retention chỉ 15 ngày đòi CDC outage recovery nhỏ hơn retention hoặc trimmer
  phải dừng theo watermark.

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
| Outbox CDC pipeline | WAL row `outbox_events` | Partition theo order; connector offset | Kafka Connect retry/DLQ/backoff; `t.order.events` |
| Payment consumer | `t.payment.events` | Redis 30 ngày; workflow/FSM fallback | Signal checkout/refund/recovery; không ghi master trực tiếp |
| Inventory ship consumer | `INVENTORY_SHIP_TO_CUSTOMER` | Key order+warehouse+time; item/master CAS | Item event và optional completion |
| Inventory return consumer | `com.ecommerce.inventory.refund.all-items-inspection-completed.v1` | Key order+refund request + scope revision | Validate scope/counters, signal refund workflow |
| Temporal worker | Ba task queue | WorkflowID per checkout/order; idempotent activity | Activity 30s/3 retry; durable history |
| Outbox trimmer | Hàng ngày, ví dụ 04:00 | Một execution logic; watermark guard | Xóa row >15 ngày khi an toàn |

Không còn order metrics projector, search projection/reindex worker, Kafka CQRS
retry/DLQ hoặc cold archive batcher trong thiết kế hiện tại.

## 9. Runbook bắt buộc

Mỗi runbook cần symptom, kiểm tra read-only, mitigation, cách xác nhận phục hồi và
escalation owner. Tối thiểu phải có:

1. Payment/Inventory consumer lag: xác định partition/offset/event age, dependency,
   tăng worker an toàn, replay với dedup, xác minh workflow/FSM không double-apply.
2. Temporal workflow mắc kẹt/compensation fail: xem history/activity attempt, sửa
   dependency, retry activity có stable key, không update DB thủ công thiếu outbox.
3. Refund quá 30 ngày/manual verification: đối chiếu selected locked scope với
   Inventory, ghi audit quyền admin, signal đúng workflow và xác minh lifetime case
   chưa tạo transaction thứ hai. Không coi inspection `FAIL` là lý do bỏ Payment.
4. CDC/outbox backlog: dừng trimmer, kiểm tra connector/WAL/offset/topic, resume,
   xác minh sequence/duplicate downstream.
5. Redis corruption/outage: đánh giá duplicate window, ưu tiên DB/Temporal guard,
   không flush broad keyspace, khôi phục và theo dõi duplicate rate.
6. DB pool/deadlock: xác định query/lock holder, giảm concurrency, không kill
   transaction mù, xác minh state/outbox atomicity.
7. Late-payment recovery/manual verification: xác nhận payment evidence, cancel reason,
   Inventory reservation/physical-dispatch evidence và refund case trước mọi manual
   action. Automatic admin refund chỉ được tiếp tục sau all-line atomic pre-dispatch
   release; mixed dispatch là manual exception.
8. Outbox retention cleanup failure: kiểm tra watermark và oldest row; chỉ chạy lại
   delete khi connector đã vượt dữ liệu mục tiêu.
