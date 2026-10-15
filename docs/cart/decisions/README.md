# Cart Service — Architecture Decision Records

Thư mục này lưu các quyết định kiến trúc dài hạn của Cart Service. Trạng thái
`Accepted` nghĩa là quyết định đã được chấp thuận và còn hiệu lực. Implementation của
Cart (`go/services/cart-service`) đã được đối chiếu với các ADR còn hiệu lực; ADR
`Superseded` chỉ còn giá trị lịch sử.

Các câu hỏi kiểu “vì sao Redis là nguồn chân lý”, “vì sao outbox nằm trên Redis
Streams”, “vì sao không đồng bộ giá bằng event” phải được trả lời ở đây, không
trộn vào mô tả workflow.

## Khi nào phải tạo ADR

Tạo ADR khi quyết định:

- Thay đổi service boundary hoặc data ownership.
- Chọn hoặc thay thế database, broker, framework hay protocol quan trọng.
- Chọn mô hình consistency, concurrency, caching hoặc messaging.
- Tạo một trade-off khó đảo ngược hoặc ảnh hưởng nhiều service.
- Chấp nhận một constraint hoặc risk dài hạn.
- Thay thế một quyết định đã được chấp nhận trước đó.

## Quy tắc

- Sao chép [ADR-000-template.md](./ADR-000-template.md) thành
  `ADR-<sequence>-<short-name>.md`.
- Không sửa lịch sử của ADR đã `Accepted`; tạo ADR mới và đánh dấu ADR cũ là
  `Superseded`, liên kết hai chiều.
- Decision phải cụ thể và kiểm chứng được.
- Ghi cả hệ quả tiêu cực và chi phí migration, không chỉ lợi ích.

## Decision index

| ADR | Decision | Status | Date | Supersedes | Superseded by |
|---|---|---|---|---|---|
| [ADR-000](./ADR-000-template.md) | Template — không phải quyết định thực tế | Template | N/A | N/A | N/A |
| [ADR-001](./ADR-001-redis-first-source-of-truth.md) | Redis là nguồn chân lý, PostgreSQL là cold store | Superseded | 2026-08-28 | — | ADR-010 |
| [ADR-002](./ADR-002-redis-streams-outbox.md) | Outbox bằng Redis Streams thay vì publish Kafka trực tiếp | Superseded | 2026-08-28 | — | ADR-010 |
| [ADR-003](./ADR-003-global-sharded-stream.md) | Global sharded stream thay vì stream theo từng giỏ | Superseded | 2026-08-28 | — | ADR-008 |
| [ADR-004](./ADR-004-stream-id-ordering.md) | Thứ tự và idempotency dựa trên Stream ID | Superseded | 2026-08-28 | — | ADR-009 |
| [ADR-005](./ADR-005-pull-model-product-data.md) | Pull Model cho dữ liệu Product thay vì đồng bộ đẩy | Accepted (chi tiết điều chỉnh bởi ADR-011) | 2026-08-28 | — | — |
| [ADR-006](./ADR-006-golang-runtime.md) | Chọn Go làm runtime của Cart Service | Accepted | 2026-08-28 | — | — |
| [ADR-007](./ADR-007-redis-streams-temporary-outbox-records.md) | Redis Streams cho record outbox tạm thời | Accepted (phạm vi điều chỉnh bởi ADR-011) | 2026-08-28 | — | — |
| [ADR-008](./ADR-008-bucketed-redis-stream-outbox.md) | 64 bucketed Redis Stream outbox cùng slot với cart | Accepted (chi tiết điều chỉnh bởi ADR-011) | 2026-08-30 | ADR-003 | — |
| [ADR-009](./ADR-009-bucket-scoped-event-ordering.md) | Ordering theo stream bucket và event identity kép | Superseded | 2026-08-30 | ADR-004 | ADR-011 |
| [ADR-010](./ADR-010-redis-only-cart-and-abandoned-outbox.md) | Redis-only Cart và outbox chỉ cho CART_ABANDONED | Accepted | 2026-08-30 | ADR-001, ADR-002 | — |
| [ADR-011](./ADR-011-current-pull-read-and-redis-outbox-scope.md) | Pull read thuần và phạm vi Redis outbox hiện hành | Accepted | 2026-08-31 | ADR-009; điều chỉnh ADR-005, ADR-007, ADR-008 | — |
| [ADR-012](./ADR-012-catalog-only-cart-validation.md) | Cart chỉ xác thực catalog, mô tả caller Inventory cũ | Superseded | 2026-09-01 | — | ADR-013 |
| [ADR-013](./ADR-013-checkout-preview-order-reserve-boundary.md) | Cart catalog-only; Checkout Preview, Order Reserve | Accepted | 2026-09-20 | ADR-012 | — |
| [ADR-014](./ADR-014-purge-cart-on-user-deleted.md) | Xóa giỏ hàng khi account bị xóa (`USER_DELETED`) | Accepted | 2026-10-09 | — | — |
| [ADR-015](./ADR-015-proto-stub-drift-detection.md) | Phát hiện stub Product lệch proto chuẩn bằng hash; regenerate stub cũ | Accepted | 2026-10-15 | — | — |
| [ADR-016](./ADR-016-retry-after-on-retryable-503.md) | `Retry-After: 2` trên `503` retryable (`AbortWithProblem`); contract khai báo header | Accepted | 2026-10-15 | — | — |
| [ADR-017](./ADR-017-classify-product-errors-by-error-info.md) | Phân loại lỗi Product theo `ErrorInfo`; lỗi do caller không retry và không mở breaker | Accepted | 2026-10-15 | — | — |
| [ADR-018](./ADR-018-grpc-channel-round-robin-and-keepalive.md) | Kênh Product dùng `dns:///` + `round_robin` + keepalive 30s/10s | Accepted | 2026-10-15 | — | — |
