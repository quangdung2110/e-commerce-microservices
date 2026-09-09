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
container runtime, PostgreSQL, Redis, Kafka/Kafka Connect/Debezium, Temporal server/
CLI/UI và công cụ migration. Runtime phải có client/SDK hỗ trợ durable Temporal
workflow, PostgreSQL transaction/locking và Kafka consumer.

## 2. Chạy local và dependency

Khi implementation được thêm, tài liệu phải cung cấp command chính thức để cài
dependency, start PostgreSQL/Redis/Kafka/Connect/Temporal, chạy migration, start
HTTP server, ba consumer và Temporal worker, kiểm tra health, rồi cleanup an toàn.

Local stack cần topic `t.order.events`, `t.payment.events`, `t.inventory.events`,
ba consumer group và ba Temporal task queue. Inventory/Promotion/Payment có thể
mock bằng contract fixture có idempotency/retry behavior; không mock Temporal bằng
thread sleep cho test workflow.

## 3. Cấu hình local

Cần `.env.example` hoặc profile local không chứa secret thật. Bảng cấu hình phải
bao phủ DSN/pool, Redis, Kafka security/group, Temporal namespace/task queue/TLS,
timeouts/retry, timer 15 phút, refund wait 30 ngày, TTL keys, search window và
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
- Pricing/refund allocation FULL/PARTIAL, PLATFORM-only, non-blocking voucher rule.
- Workflow compensation stack order và deterministic replay logic.
- Cursor encode/decode, visibility/filter mapping và accent normalization.

### Integration test

- PostgreSQL transaction: state + sequence + outbox atomic; row-lock/CAS races.
- Buyer return first-write-wins; unique checkout/refund transaction constraints.
- Search class A/B với extensions/index/view và equal timestamp cursor.
- Redis duplicate hit/miss/outage/TTL expiry; DB/Workflow fallback safety.
- Debezium outbox at-least-once, partition key và connector restart.
- Temporal crash/resume, timer/signal race, activity retry, workflow identity.

### Contract/consumer/provider test

Không chuẩn hóa payload trong lần refactor này, nhưng implementation phải test mọi
HTTP/event/activity semantics liệt kê ở `interfaces.md`: unknown field, duplicate,
unsupported version, route theo order/workflow ID, canonical string order ID của
`ORDER_COMPLETED`, nullable variant snapshot và promotion allocation.

### End-to-end/failure injection

Chạy checkout success, từng pre/post-payment failure, timeout cancel, late payment
recovery/auto-refund, multi-warehouse delivery/auto-complete, Buyer one-shot return,
Inventory 30-day timeout/override, refund success/fail/reverse và Redis/Kafka/DB/
Temporal outage. Timer dài cần test clock/time skipping, không chờ thật.

## 6. Contract code generation

Hiện chỉ có một phần Order event v1; chưa có quy trình generate. Khi có OpenAPI/
AsyncAPI/Proto chuẩn, cần pin tool version, output path, generated-code policy và
CI drift check. Markdown example không phải source để generate model.

## 7. Database migration

Migration đầu tiên phải tạo schema/tables/FK/check/index/view/extensions trong
`data.md`, đồng thời giải quyết các gap timestamp và manual verification state.
Migration Phase 22 phải backfill `buyer_return_locked_at` trước khi drop bảng
idempotency cũ. Index trigram lớn nên tạo theo kế hoạch không khóa production.

Cần command tạo/up/down/verify migration, seed order đa kho và rollback policy.
Destructive down migration không được xóa order production. Schema test phải đảm
bảo query design không tham chiếu cột thiếu.

## 8. Test data và fixture

Fixture tối thiểu gồm order một/nhiều kho; trạng thái master/item/payment; FULL/
PARTIAL promotion; buyer/admin; cùng timestamp; tên tiếng Việt có/không dấu;
payment success/fail/expired; refund processing/success/fail/reverse; inventory
ship/return duplicate. Dùng UUID/số điện thoại/address giả, không dùng PII production.

## 9. Gỡ lỗi

Ưu tiên trace bằng `correlation_id`, WorkflowID, event ID và order ID. Inspect
Temporal history trước khi retry/manual signal; kiểm tra row lock/state/outbox trong
read-only transaction; kiểm tra Kafka offset/lag và Redis TTL mà không dump PII.
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
