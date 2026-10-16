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

Inventory's cross-service [ADR-020](../../inventory/decisions/ADR-020-command-receipts-and-minimal-order-events.md)
narrows its Order-facing event contract to dispatch and inspection completion. Reservation
commands retain ADR-011's durable checkpoint/query/replay recovery; no acknowledgment
event is required and historical Order ADR bodies are unchanged.

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
| [ADR-008](./ADR-008-late-payment-recovery.md) | Historical late-payment recovery | Superseded | Không rõ | ADR-014 |
| [ADR-009](./ADR-009-customer-refund-does-not-mutate-promotion.md) | Customer refund không mutate Promotion | Accepted | 2026-09-10 | ADR-007 |
| [ADR-010](./ADR-010-selected-line-refund-policy.md) | Một refund case trọn đời, selected full-quantity lines, admin chỉ automatic trước dispatch | Accepted | 2026-09-15 | ADR-006 (scope/gate) |
| [ADR-011](./ADR-011-financial-saga-recovery-and-durable-dispatch.md) | Financial watermark, durable inbox/dispatch/checkpoint and refund reversal handling | Accepted, refined | 2026-09-16 | Refined by ADR-014, ADR-015, ADR-018 |
| [ADR-012](./ADR-012-redis-request-mirror-scope.md) | Giới hạn Redis ở request replay mirror; bỏ event fast-path, FSM lock và create mirror | Accepted | 2026-09-18 | ADR-005 (một phần) |
| [ADR-013](./ADR-013-durable-refund-scope-finalization.md) | Đóng Inventory scope bằng durable Activity/recovery; quyết định Ops kết thúc return thiếu hàng không payout | Accepted | 2026-10-02 | Refines ADR-010, ADR-011 |
| [ADR-014](./ADR-014-payment-attempt-deadline-and-terminal-semantics.md) | Immutable deadline, FAILED-only retry, không hồi sinh terminal non-capture | Accepted | 2026-10-05 | ADR-008; refines ADR-011 |
| [ADR-015](./ADR-015-durable-workflow-messages-and-convergence.md) | DB-to-Temporal outbox, stream-scoped inbox, cross-Continue-As-New dedup, proof replay/convergence | Accepted | 2026-10-05 | Refines ADR-001, ADR-005, ADR-011 |
| [ADR-016](./ADR-016-multi-destination-outbox-and-receipts.md) | Facts/notification commands chung outbox, durable notification ledger, cleanup bằng exact receipt | Accepted | 2026-10-05 | Refines ADR-003 |
| [ADR-017](./ADR-017-deterministic-line-net-refund-and-search.md) | Line-net refund allocation, deterministic search/idempotency/secret policy | Accepted | 2026-10-05 | Refines ADR-004, ADR-010, ADR-012 |
| [ADR-018](./ADR-018-reconciliation-obligations.md) | Một envelope, nhiều obligation/evidence/decision độc lập | Accepted | 2026-10-05 | Refines ADR-011, ADR-013 |
| [ADR-019](./ADR-019-external-remediation-refund-convergence.md) | Hội tụ refund lane sau full external customer payout, không giả Payment fact | Accepted | 2026-10-05 | Refines ADR-018 |
| [ADR-020](./ADR-020-release-1-implementation-clarifications.md) | Làm rõ trước implementation: runtime, line limit 100, cancel reason, `variant_name`, submission state, obligation return thủ công, Admin workflow ID | Accepted | 2026-10-05 | Refines ADR-012…018 |
| [ADR-021](./ADR-021-admin-delivery-confirmation.md) | Không có Delivery service: Admin xác nhận giao hàng qua Order API là nguồn duy nhất đưa line `DISPATCHED` sang `DELIVERED` | Accepted | 2026-10-11 | Refines ADR-010, ADR-011, ADR-015, ADR-020 |
| [ADR-023](./ADR-023-per-dependency-timeouts-and-inbound-deadline.md) | Timeout đọc riêng cho Inventory/Promotion/Payment nhỏ hơn timeout mesh; request thread không vượt deadline còn lại của caller; `submit-wait` bị kẹp | Accepted | 2026-10-15 | Làm rõ ADR-020 |
| [ADR-024](./ADR-024-remote-call-metrics-and-bulkhead.md) | Timer `order.remote.http` theo dependency; bulkhead theo dependency (`BULKHEAD_FULL` là UNKNOWN); ghi `Retry-After` | Accepted | 2026-10-15 | Bổ sung ADR-023 |
| [ADR-025](./ADR-025-unreadable-success-body-is-unknown.md) | 2xx có body không đọc được là `UNKNOWN` `BODY_UNREADABLE`, không phải `ACCEPTED` với body null | Accepted | 2026-10-16 | — |
| [ADR-026](./ADR-026-retry-after-default-on-503.md) | `Retry-After` mặc định `2` giây cho `503` retryable, `1` cho `429` | Accepted | 2026-10-16 | — |
| [ADR-027](./ADR-027-async-submission-checkpoint-waiters.md) | `DeferredResult`, PostgreSQL commit notification và bounded batch recovery cho HTTP checkout checkpoint; giữ wire contract/deadline/idempotency | Accepted | 2026-10-16 | Refines ADR-020, ADR-023 |
