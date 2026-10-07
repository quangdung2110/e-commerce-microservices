---
doc_id: order-adr-005
service: order
status: accepted
date: null
decision_owners:
  - Architecture Team
supersedes: order-request-and-event-idempotency-tables
evidence:
  - docs/order/data.md
  - docs/order/architecture.md
---

# ADR-005 — Idempotency bằng Redis, WorkflowID và FSM CAS

## Trạng thái

`Accepted` từ Phase 22; thay hai bảng `order_request_idempotency` và
`order_event_idempotency`. Chưa có implementation evidence.

## Bối cảnh

Durable idempotency tables tăng schema/lifecycle/cleanup. Temporal WorkflowID và
PostgreSQL conditional transition đã bảo vệ nhiều side effect; Redis có thể chặn
duplicate nhanh trong cửa sổ thực tế.

## Quyết định

Request dùng Redis SETNX TTL 7 ngày; consumed event dùng TTL 30 ngày; FSM lock API
TTL 10 giây. Create dedup chính bằng WorkflowID theo checkout token. Refund theo
WorkflowID/refund case uniqueness. Correctness cuối dựa row lock/CAS, state/outbox
transaction và stable remote idempotency key. Buyer one-shot là cột DB vĩnh viễn,
không phải Redis. Bỏ optimistic `version` và hai durable idempotency tables.

## Phương án đã cân nhắc

- Giữ durable tables: replay dài hạn tốt hơn nhưng tăng storage/cleanup/write.
- Chỉ Redis: không đủ vì key có thể mất; không chọn như safety duy nhất.
- Redis fast path + durable domain guards: ít schema hơn và vẫn giữ invariant;
  được chọn.

## Hệ quả

Hot path nhẹ và schema nhỏ. Duplicate sau TTL/mất Redis có thể vào sâu hơn nên
mọi transition/activity/outbox phải idempotent; exact response replay không luôn
được bảo đảm nếu payload không lưu trong Redis. Không có durable inbox audit.

## Ảnh hưởng migration

Backfill `buyer_return_locked_at` từ guard cũ trước khi drop tables; triển khai key
namespace/TTL/metrics; test fallback khi Redis lỗi. Không reset Buyer guard.

## Xác minh

Concurrency test với Redis hit/miss/outage/expiry, WorkflowID duplicate, CAS affected
row, no duplicate outbox/refund và migration backfill/drop order.
