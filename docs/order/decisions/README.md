# Order Service — Hồ sơ quyết định kiến trúc

Thư mục này tách các quyết định khó đảo ngược khỏi mô tả workflow. Các ADR được
khôi phục từ thiết kế cũ; `Accepted` nghĩa là thiết kế cũ đã chốt quyết định, không
có nghĩa implementation đã được xác minh.

## Quy tắc

- Không sửa lịch sử một ADR đã accepted để biểu diễn quyết định mới; tạo ADR thay thế.
- Khi có code, thêm migration/test/config/benchmark vào `evidence` và `Verification`.
- Thay đổi Temporal, persistence, search, idempotency, refund gate hoặc voucher
  refund policy phải cập nhật ADR liên quan.
- Contract liên service sẽ được đối soát ở lần refactor riêng; ADR hiện tại bảo
  toàn semantics, không tuyên bố contract đã verified.

## Danh mục quyết định

| ADR | Quyết định | Trạng thái | Ngày | Thay thế |
|---|---|---|---|---|
| [ADR-000](./ADR-000-template.md) | Template, không phải quyết định | Template | N/A | N/A |
| [ADR-001](./ADR-001-temporal-saga-orchestration.md) | Temporal orchestration cho checkout/refund/recovery | Accepted | Không rõ | N/A |
| [ADR-002](./ADR-002-master-sub-order-and-snapshots.md) | Master/sub-order theo kho và immutable snapshots | Accepted | Không rõ | N/A |
| [ADR-003](./ADR-003-transactional-outbox-cdc.md) | Append-only transactional outbox qua CDC | Accepted | Không rõ | N/A |
| [ADR-004](./ADR-004-postgresql-direct-order-search.md) | PostgreSQL là source of truth và search read path | Accepted | Không rõ | Kiến trúc Elasticsearch cũ |
| [ADR-005](./ADR-005-redis-and-fsm-idempotency.md) | Redis SETNX + WorkflowID + FSM CAS, bỏ durable idempotency tables | Accepted | Không rõ | Hai bảng idempotency cũ |
| [ADR-006](./ADR-006-single-refund-with-inventory-gate.md) | Một refund transaction/order, chờ Inventory confirmation | Superseded | Không rõ | ADR-010 |
| [ADR-007](./ADR-007-refund-voucher-policy.md) | PLATFORM allocation deterministic khi refund | Superseded | 2026-06-17, cập nhật Phase 23 | Chính sách FREE_SHIPPING/restore cũ |
| [ADR-008](./ADR-008-late-payment-recovery.md) | Payment success muộn đi recovery; automatic-refund branch historical | Partially superseded | Không rõ | ADR-010 (automatic-refund branch) |
| [ADR-009](./ADR-009-customer-refund-does-not-mutate-promotion.md) | Customer refund không mutate Promotion | Accepted | 2026-09-10 | ADR-007 |
| [ADR-010](./ADR-010-selected-line-refund-policy.md) | Một refund case trọn đời, selected full-quantity lines, admin chỉ automatic trước dispatch | Accepted | 2026-09-15 | ADR-006 (scope/gate) |
| [ADR-011](./ADR-011-financial-saga-recovery-and-durable-dispatch.md) | Financial watermark, durable inbox/dispatch/checkpoint, fenced recovery and refund reversal handling | Accepted | 2026-09-16 | Refines ADR-001, ADR-005, ADR-008, ADR-010 |
| [ADR-012](./ADR-012-redis-request-mirror-scope.md) | Giới hạn Redis ở request replay mirror; bỏ event fast-path, FSM lock và create mirror | Accepted | 2026-09-18 | ADR-005 (một phần) |
