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
- Contract liên service sẽ được đối soát ở lần refactor riêng; ADR hiện tại bảo
  toàn semantics, không tuyên bố contract đã verified.

## Danh mục quyết định

| ADR | Quyết định | Trạng thái | Ngày | Thay thế |
|---|---|---|---|---|
| [ADR-000](./ADR-000-template.md) | Template, không phải quyết định | Template | N/A | N/A |
| [ADR-001](./ADR-001-java-spring-boot-runtime.md) | Java (Spring Boot) thay vì Golang (Gin) | Accepted | Không rõ | N/A |
| [ADR-002](./ADR-002-refund-inside-payment-service.md) | Giữ Refund trong Payment Service (Phase 1), tách khi đạt ngưỡng Phase 2 | Accepted | 2026-03 | N/A |
| [ADR-003](./ADR-003-stripe-single-provider-direct-sdk.md) | Stripe là provider duy nhất, gọi SDK trực tiếp, bỏ Provider Adapter Pattern, bỏ COD | Accepted | Phase 18 | Multi-provider (VNPay/MoMo/ZaloPay) + COD |
| [ADR-004](./ADR-004-async-webhook-processing-via-kafka.md) | Webhook trả 200 ngay, xử lý async qua `t.payment.webhooks.raw` | Accepted | Không rõ | N/A |
| [ADR-005](./ADR-005-transactional-outbox-cdc.md) | Transactional outbox append-only qua Debezium CDC | Accepted | Không rõ | Outbox Polling Worker |
| [ADR-006](./ADR-006-optimistic-lock-and-redis-lock-strategy.md) | Optimistic lock mặc định + Redis lock cho side effect, có fallback | Accepted | Không rõ | N/A |
| [ADR-007](./ADR-007-order-id-partition-key.md) | Partition key `order_id`, route theo `payload.order_id` | Accepted | Không rõ | N/A |
| [ADR-008](./ADR-008-minimal-event-payload-phase-20.md) | Payload event tối thiểu cho Order consumer | Accepted | Phase 20 | Payload đầy đủ trước Phase 20 |
| [ADR-009](./ADR-009-monthly-partitioning-and-tiered-archive.md) | Partition theo tháng + archive S3 + tiered read | Superseded (ADR-013) | Không rõ (ghi nhận sau Phase 16) | N/A |
| [ADR-010](./ADR-010-refund-only-by-explicit-order-command.md) | Refund chỉ từ explicit command của Order; Payment không consume Order event | Accepted | Không rõ | Auto-refund từ Order lifecycle event |
| [ADR-011](./ADR-011-one-lifetime-order-refund.md) | Một Payment refund transaction trọn đời/order và hai dạng Inventory evidence | Accepted | 2026-09-15 | ADR-010 (refund gate details) |
| [ADR-012](./ADR-012-recoverable-initiation-and-cancel.md) | Durable provider operation for create/cancel and fact-based Order cancellation | Accepted | 2026-09-16 | Refines ADR-006, ADR-007 and ADR-008 |
| [ADR-013](./ADR-013-single-oltp-retention-no-partition.md) | Một OLTP schema, không partition/cold archive; retention bằng row-level trimmer | Accepted | 2026-09-19 | ADR-009 |
| [ADR-014](./ADR-014-decline-non-terminal-and-canceled-intent-before-non-capture-terminal.md) | Decline phi terminal; mọi terminal non-capture đòi hỏi PaymentIntent đã canceled | Accepted | 2026-09-19 | Refines ADR-012 |
