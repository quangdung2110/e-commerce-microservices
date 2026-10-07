---
doc_id: cart-adr-011
service: cart
status: accepted
date: 2026-08-31
decision_owners:
  - Cart Service team
supersedes:
  - ADR-005
  - ADR-007
  - ADR-008
  - ADR-009
evidence:
  - docs/cart/data.md
  - docs/cart/workflows.md
  - docs/cart/scripts
  - contracts/grpc/ecommerce/product/v1/product_service.proto
---

# ADR-011 — Pull read thuần và phạm vi Redis outbox hiện hành

## Status

`Accepted`. Thay thế các chi tiết không còn đúng của ADR-005, ADR-007, ADR-008 và
ADR-009; ADR-010 vẫn là quyết định Redis-only Cart hiện hành.

## Context

Cart cần hiển thị catalog mới nhất nhưng không sở hữu price, availability hay
inventory. Các quyết định cũ đã chọn pull model và Redis Streams, nhưng có ba điểm
cần được chốt lại: một ngoại lệ ghi Redis trong GET, mô tả mọi cart write đều tạo
outbox record, và các chi tiết cold-store/v1 không còn tồn tại.

Một GET có side effect làm khó retry, quan sát và suy luận TTL. Đồng thời, dùng
readiness để gate theo Product/Kafka sẽ khiến promised degraded response không tới
được người dùng. Cart cần ranh giới nhất quán hơn mà không biến Redis thành bản sao
catalog.

## Decision

- `GET /api/v1/carts/mine` là query thuần. Nó đọc state Redis rồi gọi
  `GetBatchSkus`; không cập nhật item snapshot, TTL, ZSET hay lifecycle.
- Snapshot Redis chỉ phục vụ hiển thị fallback và tái sử dụng validation cho positive
  delta tối đa `CART_PRODUCT_VALIDATION_TTL` (30–60 giây). Sau khi hết hạn, Lua yêu
  cầu `GetSku` refresh trước khi tăng quantity. Checkout luôn tự xác thực catalog,
  giá và inventory; không dùng Cart response để chốt giao dịch.
- Redis Stream outbox chỉ có một producer nghiệp vụ: `mark_abandoned.lua`. Chỉ
  transition `ACTIVE -> ABANDONED` mới `XADD CART_ABANDONED` nguyên tử.
- State và outbox dùng namespace `v2` với 64 bucket `{b00}` đến `{b63}`. Event
  identity là `outbox_key + stream_id`; không còn cold-store projection hoặc global
  stream ordering claim.
- Idempotency record được scope theo user và HTTP operation, không chỉ bucket. HTTP
  readiness chỉ phụ thuộc Redis; Product và Kafka được quan sát/cảnh báo riêng.

## Consequences

GET trở nên retry-safe và không tạo write load khi người dùng chỉ xem giỏ. Đổi lại,
availability dùng để cho phép positive delta có thể cũ trong validation window; đây
không phải reservation và Checkout là ranh giới chốt cuối cùng.

Kafka outage không ngắt Cart read/write ngay lập tức, nhưng làm outbox tăng trong
Redis. `noeviction`, capacity reserve và alert backlog là bắt buộc để hệ thống
fail-closed thay vì mất state im lặng.

## Verification

- Test GET không thay đổi item hash, TTL hay ZSET.
- Test Product timeout vẫn trả `DEGRADED` trong khi pod còn ready nếu Redis healthy.
- Test SKU snapshot unavailable sau khi validation expiry có thể refresh thành
  available trước positive delta.
- Test chỉ `mark_abandoned.lua` tạo stream entry.
- Test idempotency key cùng raw value không xung đột giữa user hoặc operation.
