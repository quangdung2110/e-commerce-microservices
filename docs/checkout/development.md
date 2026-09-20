---
title: Checkout Development Guide
status: proposed
last_updated: 2026-09-20
owners:
  - domain: checkout
    team: commerce-platform
    contact: commerce-platform@example.com
review_cycle_days: 30
source_of_truth: false
evidence:
  - ../../contracts/http/checkout/v1/openapi.yaml
  - ../../contracts/catalog/interactions.yaml
tags: [checkout, development, testing]
---

# Checkout Development Guide

## Thứ tự triển khai

1. Generate server/client types từ Checkout OpenAPI và các downstream contracts.
2. Triển khai token generation/HMAC lookup, Redis schemas và Lua/CAS scripts.
3. Triển khai typed clients cho Cart, Product, User, Inventory, Promotion, Order.
4. Triển khai preview orchestration và server-side totals.
5. Triển khai submit state machine, immutable payload và Order idempotency.
6. Triển khai submission status/recovery.
7. Thêm telemetry, rate limiting và Gateway route sau smoke tests.

Không hand-code DTO khác contract nếu generator có thể dùng được. Money là integer VND; không dùng floating point.

## Unit tests bắt buộc

- Tổng tiền, rounding và discount bounds.
- Token HMAC lookup và encryption-key rotation; không để raw token trong key/log, chỉ giữ bản mã hóa trong submit payload recovery.
- Mọi state transition hợp lệ/không hợp lệ.
- Preview mới không xóa token đang submit.
- Claim submit materialize payload atomically.
- Hai submit đồng thời chỉ có một owner side effect.
- Tier absent và `checkout_channel=CHECKOUT` mapping.
- Promotion scalar, không giả định stacking.

## Contract tests

- Validate Checkout OpenAPI examples/schema.
- Checkout consumer tests với Cart checkout snapshot.
- Product `GetBatchSkus` trả `category_ids`.
- Inventory request là whole-order `lines` và xử lý đúng `ALLOCATABLE`/`REJECT_ALL`.
- Promotion chấp nhận tier omit/null, channel cố định.
- Order nhận `user_ack_degraded_pricing=false` và replay cùng idempotency key.
- RFC 9457 mapping không leak downstream body.

## Integration/concurrency tests

- Redis TTL/eviction và Lua scripts trên Redis thật hoặc compatible test container.
- POST Order success, conflict, timeout-before-write, timeout-after-write.
- Recovery tìm thấy Order sau khi Checkout mất response.
- Retry cùng token/payload/key không tạo Order thứ hai.
- Preview A, preview B, submit A/B xen kẽ.
- Dependency partial outage; Inventory/Product/User fail-closed.

## End-to-end acceptance

- Preview -> submit -> status success.
- Repeated preview `Idempotency-Key` replay cùng response.
- Cùng preview `Idempotency-Key` nhưng fingerprint khác trả `409`; replay value vẫn encrypted at rest.
- Cart/price/promotion/allocation đổi trả conflict và yêu cầu preview lại.
- Token của user khác trả `404`.
- Raw token không xuất hiện trong logs, traces hoặc Redis key names.
- Response có `Cache-Control: private, no-store`.

## CI gates

- OpenAPI lint/validation và protobuf breaking check.
- Catalog validation cho services/interactions.
- Link/frontmatter validation cho docs.
- `rg` guard chống endpoint/key/field cũ trong tài liệu active.
- Race/concurrency tests và dependency contract tests.

## Review checklist

- Không đọc trực tiếp storage của service khác.
- Không thêm downstream cache khi chưa có ADR/invalidation contract.
- Không thay Order idempotency key (= checkout token) giữa retry.
- Không thêm degraded availability/pricing path.
- Mọi thay đổi endpoint phải cập nhật Checkout, owner service, Gateway, Mesh và catalog trong cùng PR.
