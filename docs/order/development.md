---
doc_id: order-development
service: order
scope: target
status: proposed
owners:
  - Order Service team
evidence: []
verified_against_commit: null
---

# Order Service — Hướng dẫn phát triển

> Repository chưa có implementation Order Service để suy ra runtime, command,
> port, package hoặc migration. File này là checklist triển khai/kiểm thử trung
> thực với thiết kế, không bịa lệnh copy-paste chưa tồn tại.

## 1. Điều kiện tiên quyết

Cần chốt và ghi phiên bản ngôn ngữ/framework, build tool, formatter/linter,
container runtime, PostgreSQL, Redis request-replay cache, Kafka/Kafka Connect/Debezium, Temporal server/
CLI/UI và công cụ migration. Runtime phải có client/SDK hỗ trợ durable Temporal
workflow, PostgreSQL transaction/locking và Kafka consumer.

## 2. Chạy local và dependency

Khi implementation được thêm, tài liệu phải cung cấp command chính thức để cài
dependency, start PostgreSQL/Redis/Kafka/Connect/Temporal, chạy migration, start
HTTP server, bốn Kafka consumer, dispatch/reconciliation workers và Temporal worker,
kiểm tra health, rồi cleanup an toàn.

Local stack cần topic `t.order.events`, `t.payment.events.v2`, `t.inventory.order.events.v1`,
`t.delivery.order.events.v1`, `t.notification.transactional.v1`, bốn consumer group,
workflow start/message dispatcher, receipt observer và ba Temporal task queue. Inventory/Promotion/Payment có thể
mock bằng contract fixture có idempotency/retry behavior; không mock Temporal bằng
thread sleep cho test workflow.

## 3. Cấu hình local

Cần `.env.example` hoặc profile local không chứa secret thật. Bảng cấu hình phải
bao phủ DSN/pool, Redis request-replay cache, Kafka security/group, Temporal namespace/task queue/TLS,
timeouts/retry, timer 15 phút, refund wait 30 ngày, request-mirror TTL, search window và
outbox retention. Stripe secret/client secret thuộc Payment, không được commit/log
ở Order.

## 4. Build, lint và format

Command chuẩn phải trùng CI và có tiêu chí pass rõ ràng. Ngoài compile/lint/format,
CI cần validate migration, deterministic Temporal workflow, event serialization,
SQL query plan smoke test và Markdown links/frontmatter. Chưa có command cụ thể để
ghi tại đây.

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
dispatch and separate Delivery proof including proof-before-dispatch replay,
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

Order đã có public/internal OpenAPI v1 ở `contracts/http/order`; AsyncAPI v1 mô tả
hai outbound event có consumer và ba inbound provider channels. Cần pin generator/linter version, output path, generated-code
policy và CI drift check. Markdown example không phải source để generate model.

## 7. Database migration

Migration đầu tiên phải tạo schema/tables/FK/check/index/view/extensions trong
`data.md`, gồm `paid_at`, `completed_at`, `cancelled_at`, `refunded_at` và manual
verification state.
Migration phải add/backfill durable inbox, workflow dispatch/message, operation, saga
checkpoint, numbered payment attempt identity/version, refund evidence, reconciliation
case/obligation/evidence/decision and admin attempt tables before retiring historical
idempotency storage. Index trigram lớn nên tạo theo kế hoạch không khóa production.
Add finalization status/generation/receipt metadata, generation-keyed checkpoints and
the unresolved-finalization index. Backfill Admin to `NOT_REQUIRED`; query/recover
Inventory evidence for historical Buyer terminal cases without reissuing Payment or
inventing command receipts. Version the refund workflow change for replay and register
`OrderRefundScopeFinalizationWorkflow` on the existing refund queue before dispatch.

Cần command tạo/up/down/verify migration, seed order đa kho và rollback policy.
Destructive down migration không được xóa order production. Schema test phải đảm
bảo query design không tham chiếu cột thiếu.

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

## 10. Lỗi local thường gặp cần ghi khi có code

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
được cập nhật trước khi nâng trạng thái khỏi `proposed`.
