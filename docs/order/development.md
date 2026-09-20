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
HTTP server, bốn Kafka consumer và Temporal worker, kiểm tra health, rồi cleanup an toàn.

Local stack cần topic `t.order.events`, `t.payment.events.v2`, `t.inventory.order.events.v1`,
`t.delivery.order.events.v1`, bốn consumer group, workflow dispatcher và bốn Temporal task queue. Inventory/Promotion/Payment có thể
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
- Pricing snapshot PLATFORM-only; customer refund không tạo promotion allocation/call.
- Pre-payment compensation versus post-capture forward-repair/reconciliation, and deterministic replay logic.
- Cursor encode/decode, visibility/filter mapping và accent normalization.

### Integration test

- PostgreSQL transaction: state + sequence + outbox + durable inbox/workflow-dispatch/checkpoint atomicity; row-lock/CAS races.
- Buyer return first-write-wins; unique checkout/refund transaction constraints.
- Search class A/B với extensions/index/view và equal timestamp cursor.
- Request replay mirror hit/miss/outage/TTL expiry; duplicate events must be safe without
  Redis; durable inbox/dispatch/checkpoint remains the authority.
- Debezium outbox at-least-once, partition key và connector restart.
- Temporal crash/resume, timer/signal race, activity retry, workflow identity and dispatcher recovery after local commit/start failure.
- Promotion final activity: same-key stored response, different-payload conflict, crash
  sau durable receipt trước Redis convergence; assert no active post-capture `CANCEL_FINAL`.

### Contract/consumer/provider test

Implementation phải test mọi HTTP/event/activity semantics liệt kê ở
`interfaces.md`: unknown field, duplicate, unsupported version, route theo
order/workflow ID, canonical string order ID của cả hai outbound event, unique paid
SKU list, nullable completed-item variant
snapshot, voucher toàn đơn snapshot, selected full-quantity scope, InspectionCompleted
PASS/FAIL full evidence persistence, atomic admin pre-dispatch release attempt/case boundary,
Payment attempt↔transaction/amount/currency/per-transaction financial-version validation and refund reverse
from `REFUNDED`. Payment fixtures phải dùng full structured CloudEvents, exact
namespaced `type`, canonical `source`, matching `dataschema` và Kafka key
`data.order_id`; unknown/legacy type phải được ACK/ignore theo policy, không dispatch
bằng alias uppercase. Refund amount/multiplicity is fixed by ADR-010, not deferred.

### End-to-end/failure injection

Chạy checkout success, từng pre-payment failure, post-capture forward-repair failure,
cancel-versus-capture, unknown remote outcome/query replay, timeout cancel, fenced late
payment recovery/manual verification, multi-warehouse physical dispatch and separate Delivery
proof (never auto-delivery),
Buyer one-shot return, rejected/accepted admin attempt, Inventory 30-day timeout/manual
verification, refund success/fail/reverse after `REFUNDED` và Redis/Kafka/DB/Temporal outage.
Timer dài cần test clock/time skipping, không chờ thật.

## 6. Contract code generation

Order đã có public/internal OpenAPI v1 ở `contracts/http/order`; AsyncAPI v1 mô tả
hai outbound event có consumer và ba inbound provider channels. Cần pin generator/linter version, output path, generated-code
policy và CI drift check. Markdown example không phải source để generate model.

## 7. Database migration

Migration đầu tiên phải tạo schema/tables/FK/check/index/view/extensions trong
`data.md`, gồm `paid_at`, `completed_at`, `cancelled_at`, `refunded_at` và manual
verification state.
Migration phải add/backfill durable inbox, workflow dispatch, saga checkpoint, numbered payment
attempt identity/version, refund evidence, reconciliation case/decision and admin attempt tables before retiring historical
idempotency storage. Index trigram lớn nên tạo theo kế hoạch không khóa production.

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

- Extension `pg_trgm`/`unaccent` chưa bật hoặc expression index không dùng được.
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
