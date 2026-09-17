# Checkout Service — Hồ sơ quyết định kiến trúc

Thư mục này tách các quyết định khó đảo ngược khỏi mô tả workflow. Các ADR được
khôi phục từ năm tài liệu thiết kế Checkout cũ và ghi chú kèm theo; `Accepted`
nghĩa là thiết kế cũ đã chốt quyết định, không có nghĩa implementation đã được
xác minh.

## Quy tắc

- Không sửa lịch sử một ADR đã accepted để biểu diễn quyết định mới; tạo ADR thay thế.
- Khi có code, thêm source/test/config/benchmark vào `evidence` và `Xác minh`.
- Thay đổi token semantics, thứ tự lock → hash → Lua, công thức `pricing_hash`,
  TTL/key Redis, ranh giới với Cart/Promotion/Order hoặc degraded contract phải
  cập nhật ADR liên quan.
- Contract liên service (tên RPC Product, path Inventory, giao thức gọi Order) sẽ
  được đối soát ở lần refactor riêng; ADR hiện tại bảo toàn semantics, không tuyên
  bố contract đã verified.

## Danh mục quyết định

| ADR | Quyết định | Trạng thái | Ngày | Thay thế |
|---|---|---|---|---|
| [ADR-000](./ADR-000-template.md) | Template, không phải quyết định | Template | N/A | N/A |
| [ADR-001](./ADR-001-stateless-bff-aggregator.md) | Checkout là BFF/Aggregator stateless, Redis-only, tách khỏi Order; quantity thuộc Cart | Accepted | Không rõ | N/A |
| [ADR-002](./ADR-002-server-side-token-and-redis-hash-session.md) | Token UUID v4 server-side + Redis Hash session, không dùng JWT | Accepted | Không rõ | N/A |
| [ADR-003](./ADR-003-distributed-lock-and-lua-one-time-token.md) | Distributed lock + Lua one-time-use; thứ tự lock → hash → Lua; tách lock khỏi status | Accepted | Không rõ | N/A |
| [ADR-004](./ADR-004-lightweight-price-staleness-check.md) | Lightweight Price Staleness Check bằng `pricing_hash` thay vì re-validate toàn bộ | Accepted | Không rõ | N/A |
| [ADR-005](./ADR-005-soft-allocation-and-reserve-in-saga.md) | Soft allocation và không lock budget ở Preview; Reserve trong Saga là quyết định cuối | Accepted | Không rõ | N/A |
| [ADR-006](./ADR-006-explicit-voucher-write-boundary-and-recalculation.md) | Submit không auto-save voucher; reserve fail → Promotion re-evaluate, không trừ cục bộ | Accepted | Không rõ | N/A |
| [ADR-007](./ADR-007-payload-and-response-recovery-cache.md) | Payload cache + idempotent response cache cho failed submit recovery | Accepted | Không rõ | N/A |
| [ADR-008](./ADR-008-single-payment-provider-stripe.md) | Phase 18: chỉ Stripe, bỏ `payment_method` khỏi Checkout | Accepted | Không rõ (Phase 18) | Multi-provider `payment_method` cũ |
| [ADR-009](./ADR-009-degraded-pricing-user-acknowledgement.md) | Degraded pricing bắt buộc `user_ack_degraded_pricing` | Accepted | Không rõ | N/A |
