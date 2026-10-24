# Payment Service — Hồ sơ quyết định kiến trúc

Thư mục này tách các quyết định khó đảo ngược khỏi mô tả workflow. Các ADR được
khôi phục từ thiết kế cũ (technology choice, refund boundary decision, ghi chú
Phase 16/18/20 và các "tại sao" rải trong workflow/DB design/workers/events);
`Accepted` nghĩa là thiết kế cũ đã chốt quyết định, không có nghĩa implementation
đã được xác minh.

## Quy tắc

- Không sửa lịch sử một ADR đã accepted để biểu diễn quyết định mới; tạo ADR thay thế.
- Khi có code, thêm migration/test/config/benchmark vào `evidence` và `Xác minh`.
- Thay đổi provider, refund boundary, webhook processing, outbox pattern, lock
  strategy, event partition/payload hoặc retention phải cập nhật ADR liên quan.
- Contract canonical đã lint/bundle và đối chiếu runtime offline. Sandbox/provider-consumer/CDC production phải có bằng chứng riêng; Accepted không có nghĩa mọi gate môi trường đã verified.

## Danh mục quyết định

| ADR | Quyết định | Trạng thái | Ngày | Thay thế |
|---|---|---|---|---|
| [ADR-000](./ADR-000-template.md) | Template, không phải quyết định | Template | N/A | N/A |
| [ADR-001](./ADR-001-java-spring-boot-runtime.md) | Java (Spring Boot) thay vì Golang (Gin) | Accepted | Không rõ | N/A |
| [ADR-002](./ADR-002-refund-inside-payment-service.md) | Giữ Refund trong Payment Service (Phase 1), tách khi đạt ngưỡng Phase 2 | Accepted | 2026-03 | N/A |
| [ADR-003](./ADR-003-stripe-single-provider-direct-sdk.md) | Stripe là provider duy nhất, gọi SDK trực tiếp, bỏ Provider Adapter Pattern, bỏ COD | Accepted, refined by ADR-021/022 | Phase 18 | Multi-provider (VNPay/MoMo/ZaloPay) + COD |
| [ADR-004](./ADR-004-async-webhook-processing-via-kafka.md) | Webhook trả 200 ngay, xử lý async qua `t.payment.webhooks.raw` | Accepted, refined by ADR-015 | Không rõ | N/A |
| [ADR-005](./ADR-005-transactional-outbox-cdc.md) | Transactional outbox append-only qua Debezium CDC | Accepted, refined by ADR-015 | Không rõ | Outbox Polling Worker |
| [ADR-006](./ADR-006-optimistic-lock-and-redis-lock-strategy.md) | PostgreSQL optimistic lock + durable lease/fencing; không có Redis runtime | Accepted (amended Gate 3) | Không rõ | N/A |
| [ADR-007](./ADR-007-order-id-partition-key.md) | Partition key `order_id`; wire v2 dùng `data.order_id` theo ADR-015 | Accepted, refined by ADR-015 | Không rõ | N/A |
| [ADR-008](./ADR-008-minimal-event-payload-phase-20.md) | Payload event tối thiểu lịch sử; v2 dùng per-event schema theo ADR-015 | Superseded (ADR-015) | Phase 20 | Payload đầy đủ trước Phase 20 |
| [ADR-009](./ADR-009-monthly-partitioning-and-tiered-archive.md) | Partition theo tháng + archive S3 + tiered read | Superseded (ADR-013) | Không rõ (ghi nhận sau Phase 16) | N/A |
| [ADR-010](./ADR-010-refund-only-by-explicit-order-command.md) | Refund chỉ từ explicit command của Order; Payment không consume Order event | Accepted | Không rõ | Auto-refund từ Order lifecycle event |
| [ADR-011](./ADR-011-one-lifetime-order-refund.md) | Một Payment refund transaction trọn đời/order và hai dạng Inventory evidence | Accepted | 2026-09-15 | ADR-010 (refund gate details) |
| [ADR-012](./ADR-012-recoverable-initiation-and-cancel.md) | Durable provider operation for create/cancel and fact-based Order cancellation | Accepted | 2026-09-16 | Refines ADR-006, ADR-007 and ADR-008 |
| [ADR-013](./ADR-013-single-oltp-retention-no-partition.md) | Một OLTP schema, không partition/cold archive; retention bằng row-level trimmer | Accepted | 2026-09-19 | ADR-009 |
| [ADR-014](./ADR-014-decline-non-terminal-and-canceled-intent-before-non-capture-terminal.md) | Decline phi terminal; mọi terminal non-capture đòi hỏi PaymentIntent đã canceled | Accepted | 2026-09-19 | Refines ADR-012 |
| [ADR-015](./ADR-015-canonical-asyncapi-and-structured-cloudevents.md) | Schema riêng từng event, namespaced type, full structured CloudEvent; internal representation được ADR-016 refine thành verified delivery | Accepted | 2026-09-20 | Refines ADR-004/005/007/008 |
| [ADR-016](./ADR-016-authenticated-webhook-inbox.md) | Verify webhook tại ingress; durable inbox lease/fencing | Accepted | 2026-09-22 | Supersedes phần verify async của ADR-004; refines ADR-015 |
| [ADR-017](./ADR-017-provider-idempotency-horizon-and-command-leases.md) | Command lease/fencing và provider idempotency safety horizon | Accepted | 2026-09-22 | Refines ADR-006/012 |
| [ADR-018](./ADR-018-provider-event-ordering-and-stripe-contract.md) | Provider checkpoints, conflict resolution, refund/dispute event set | Accepted, refined by ADR-022 | 2026-09-22 | Refines ADR-014/015 |
| [ADR-019](./ADR-019-payment-owned-subledger-and-disputes.md) | Payment-owned subledger, dispute và settlement reconciliation | Accepted | 2026-09-22 | N/A |
| [ADR-020](./ADR-020-financial-retention-privacy-and-disaster-recovery.md) | Retention 7 năm, webhook crypto-retention, privacy và DR fail-closed | Accepted | 2026-09-22 | Refines ADR-013 |
| [ADR-021](./ADR-021-pin-stripe-java-and-api-version.md) | Pin `stripe-java 33.4.2` và Stripe API `2026-08-26.dahlia` | Accepted | 2026-09-22 | Refines ADR-003/018 |
| [ADR-022](./ADR-022-refund-lifecycle-hardening.md) | Refund theo `Refund.status` của Stripe, reconcile refund `SUCCESS` 35 ngày, phạm vi thẻ, ledger dùng đúng balance transaction | Accepted | 2026-09-30 | Refines ADR-003/011/018/019 |
| [ADR-023](./ADR-023-reconciliation-ownership-and-audit-corrections.md) | Session ownership/fencing, pagination, freeze và manual correction sau audit | Accepted | 2026-10-11 | Refines ADR-017/019/020 |
| [ADR-024](./ADR-024-certified-create-non-acceptance.md) | Certified first-dispatch CREATE non-acceptance và Order compensation | Accepted | 2026-10-15 | Refines ADR-012/014/017 |
| [ADR-025](./ADR-025-request-correlation-and-caller-deadline.md) | Request ID, trace ID and caller deadline in Payment logs; `X-Correlation-Id` unchanged | Accepted | 2026-10-15 | — |
| [ADR-026](./ADR-026-service-identity-allowlist-and-401-403.md) | Configurable caller allowlist on the Order-facing routes; 401/403 instead of 400 | Accepted | 2026-10-15 | — |
| [ADR-027](./ADR-027-retry-after-on-retryable-503.md) | `Retry-After` trên `503` retryable và webhook; tách response `Unavailable` khỏi `Problem` trong contract | Accepted | 2026-10-15 | — |
| [ADR-028](./ADR-028-finance-ops-caller-allowlist.md) | Allowlist `X-Service-Identity` riêng cho Finance Ops `/internal/v1/payment/**` (401/403), path internal khác luôn bị từ chối | Accepted | 2026-10-22 | Refines ADR-026 |
