---
doc_id: order-adr-012
service: order
status: accepted
date: 2026-09-18
decision_owners:
  - Order Service team
  - Architecture Team
supersedes: ADR-005 (event fast-path, FSM lock and create-mirror portions only)
evidence:
  - docs/order/data.md
  - docs/order/architecture.md
  - docs/order/interfaces.md
  - docs/order/workflows.md
  - docs/order/decisions/ADR-011-financial-saga-recovery-and-durable-dispatch.md
---

# ADR-012 — Giới hạn Redis trong Order ở request replay mirror

## Trạng thái

`Accepted` — 2026-09-18. ADR này chỉ refine một phần ADR-005; lịch sử ADR-005
không bị sửa. Redis không là source of truth cho Order.

> Current authority: ADR-017 scopes every mirror key by principal/action and a hash of
> the caller key; durable request identity/digest remains authoritative.

## Bối cảnh

ADR-011 đã đưa durable inbox, workflow dispatch và remote-command checkpoint vào
PostgreSQL, đồng thời yêu cầu FSM/CAS và recovery không phụ thuộc Redis-only dedup.
Vì vậy event fast-path và distributed FSM lock không còn cần để bảo vệ correctness.
Create request cũng đã có unique `checkout_token`, fixed WorkflowID và durable
dispatch. Chỉ các public request cần replay nhanh vẫn có lợi ích từ Redis mirror.

## Quyết định

Order chỉ giữ bốn request mirror với TTL 7 ngày:

- `order:req:cancel:{idempotency_key}`
- `order:req:admin-refund:{idempotency_key}`
- `order:req:return:{idempotency_key}`
- `order:req:payment-attempt:{idempotency_key}`

Các mirror này chỉ lưu canonical request digest và reference tới durable result đã
được sanitize. Chúng không được quyết định FSM, bắt đầu workflow, gọi remote command
hoặc đánh dấu event đã consume. Payload chứa PII/client secret không được lưu dạng
raw trong Redis.

Loại bỏ các nhóm sau khỏi Order:

- `order:req:create:{checkout_token}`
- `order:lock:fsm:{order_id}`
- mọi Redis event fast-path cho Payment, Inventory ship, Delivery proof và Inventory
  return inspection.

Create dedup dựa trên unique database constraint, fixed WorkflowID và durable
workflow dispatch. FSM serialization dựa trên PostgreSQL row lock/CAS. Event
dedup và evidence dựa trên `order_consumed_events` cùng semantic revision/proof
guards. Redis outage hoặc TTL expiry chỉ làm mất replay fast-path; request fallback
vào durable record và event vẫn đi qua durable inbox.

## Hệ quả

Redis giảm còn một vai trò hẹp hơn và không còn là dependency của event correctness,
workflow start hoặc FSM concurrency. Duplicate event có thể tạo thêm DB lookup/unique
check nhưng không được tạo thêm side effect. Bốn request mirror vẫn cần durable
request identity/hash/result reference để giữ semantics replay và
`IDEMPOTENCY_KEY_REUSED` khi Redis mất hoặc key hết hạn.

## Ảnh hưởng migration

Ngừng đọc/ghi các key đã loại bỏ; không flush broad keyspace, để key cũ hết TTL tự
nhiên. Cập nhật metrics, alert, runbook, config và test để không còn event-key/
lock-contention assumptions. Bổ sung durable idempotency fields trước khi loại bỏ
bất kỳ request mirror nào còn lại.

## Xác minh

Test concurrent cancel/return/admin action bằng PostgreSQL lock/CAS; duplicate,
stale, out-of-order và retry event khi Redis hoàn toàn không tồn tại; retry request
khi Redis hit/miss/outage/expiry; same-key replay, different-payload conflict;
workflow start crash sau local commit; không duplicate PaymentIntent, refund,
Inventory command, workflow dispatch hoặc outbox event.
