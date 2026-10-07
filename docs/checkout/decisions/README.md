# Checkout Service — Hồ sơ quyết định kiến trúc

ADR cũ được giữ nguyên như lịch sử; chỉ trạng thái và ghi chú được cập nhật. Tài liệu active
phải theo ADR mới nhất; nội dung trong ADR có trạng thái `Superseded` không phải contract hiện
hành và được loại khỏi guard scan cho thuật ngữ cũ.

## Quy tắc

- Không sửa nội dung ADR đã accepted để thay đổi lịch sử; tạo ADR superseding hoặc thêm ghi chú
  có ngày.
- Mọi thay đổi token/state/TTL, ranh giới dữ liệu, deadline/timeout hoặc downstream contract phải
  cập nhật ADR và contract tests.
- Evidence implementation chỉ được thêm khi code/test/config tương ứng tồn tại.
- Contract OpenAPI/protobuf và catalog là nguồn kiểm chứng giao diện; ADR không được tự phát minh
  endpoint.

## Danh mục

| ADR | Quyết định | Trạng thái | Ngày | Bị thay thế bởi |
|---|---|---|---|---|
| [ADR-000](./ADR-000-template.md) | Template | Template | N/A | N/A |
| [ADR-001](./ADR-001-stateless-bff-aggregator.md) | Checkout là BFF tách khỏi Order, không có database bền vững | Accepted (chi tiết thực thi đã được cập nhật bởi ADR-011, 013–015) | Không rõ | — |
| [ADR-002](./ADR-002-server-side-token-and-redis-hash-session.md) | Session Redis Hash/token cũ | Superseded | Không rõ | ADR-011 |
| [ADR-003](./ADR-003-distributed-lock-and-lua-one-time-token.md) | Distributed lock + one-time token cũ | Superseded | Không rõ | ADR-011 |
| [ADR-004](./ADR-004-lightweight-price-staleness-check.md) | Lightweight pricing hash cũ | Superseded | Không rõ | ADR-012 |
| [ADR-005](./ADR-005-soft-allocation-and-reserve-in-saga.md) | Soft/degraded allocation cũ | Superseded | Không rõ | ADR-012 |
| [ADR-006](./ADR-006-explicit-voucher-write-boundary-and-recalculation.md) | Voucher recalculation semantics cũ | Superseded | Không rõ | ADR-012 |
| [ADR-007](./ADR-007-payload-and-response-recovery-cache.md) | Payload/response cache cũ | Superseded | Không rõ | ADR-011 |
| [ADR-008](./ADR-008-single-payment-provider-stripe.md) | Một payment provider, bỏ `payment_method` khỏi Checkout | Accepted | Phase 18 | — |
| [ADR-009](./ADR-009-degraded-pricing-user-acknowledgement.md) | User acknowledgement cho degraded pricing | Superseded | Không rõ | ADR-012 |
| [ADR-010](./ADR-010-relay-payment-client-data-no-secret-cache.md) | Không cache `client_secret` | Accepted | 2026-09-19 | — |
| [ADR-011](./ADR-011-token-keyed-session-and-recovery.md) | Token-keyed session, atomic payload, status recovery | Accepted | 2026-09-20 | — |
| [ADR-012](./ADR-012-contract-aligned-fail-closed-orchestration.md) | Contract-aligned, fail-closed preview/revalidation | Accepted | 2026-09-20 | — |
| [ADR-013](./ADR-013-java-spring-webflux-runtime.md) | Runtime Java 21 + Spring Boot WebFlux | Accepted | 2026-10-07 | — |
| [ADR-014](./ADR-014-resilience-budget-and-mesh-gateway-split.md) | Deadline, timeout, circuit breaker, recovery window; phân vai mesh/Gateway | Accepted | 2026-10-07 | — |
| [ADR-015](./ADR-015-problem-catalog-view-model-and-order-outcomes.md) | `CheckoutProblem` catalog, view model báo giá, ánh xạ kết quả Order, không phí ship | Accepted | 2026-10-07 | — |
| [ADR-016](./ADR-016-fresh-preview-and-in-flight-submission-guard.md) | Fresh preview thay báo giá cũ; chặn Preview khi submission đang chạy | Accepted | 2026-10-07 | — |
| [ADR-017](./ADR-017-voucher-options-read-composition.md) | Khay voucher là read composition của Checkout; save/claim ở Promotion | Accepted | 2026-10-07 | — |
| [ADR-018](./ADR-018-implementation-decisions.md) | Quyết định khi triển khai: DTO theo contract, Redis riêng, parse nghiêm ngặt, owner của attempt | Accepted | 2026-10-07 | — |
