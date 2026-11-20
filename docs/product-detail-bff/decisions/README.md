# Product Detail BFF — Architecture Decision Records

| ADR | Decision | Status | Date | Quan hệ |
|---|---|---|---|---|
| [ADR-001](./ADR-001-availability-projection-over-live-query.md) | Inventory-owned batch availability read thay vì BFF projection | Accepted | 2026-09-09 | Amended bởi ADR-003 (caller Reserve) |
| [ADR-002](./ADR-002-review-read-composition-without-domain-ownership.md) | Compose Review read, không đưa domain Review vào BFF | Accepted | 2026-09-07 | — |
| [ADR-003](./ADR-003-checkout-preview-order-reserve-boundary.md) | PDP advisory read; Checkout Preview, Order Reserve | Accepted | 2026-09-20 | Amends ADR-001 |
| [ADR-004](./ADR-004-resilience-budget-and-mesh-split.md) | Resilience budget của PDP, phân chia với mesh/gateway, cache response degrade | Accepted | 2026-09-24 | Amended bởi ADR-007 (timeout Product, budget trước pha 1), ADR-008 (status ngoài contract) và ADR-011 (lời gọi Product dùng chung không bị hủy theo một client) |
| [ADR-005](./ADR-005-pdp-catalog-scope-and-media-url.md) | PDP trả category, thuộc tính, trục biến thể và gallery do Product resolve; BFF dựng URL CDN | Accepted | 2026-09-25 | Cặp với Product ADR-018; amended bởi ADR-008 (`storage_key`, giới hạn chuỗi) |
| [ADR-006](./ADR-006-java-spring-webflux-runtime.md) | Java 21, Spring Boot WebFlux và Resilience4j | Accepted | 2026-09-25 | — |
| [ADR-007](./ADR-007-hot-path-clarifications.md) | Budget Product, request ID chuẩn hóa, `404` no-store, item Inventory thiếu/thừa, cờ tắt dependency, Review token | Accepted | 2026-09-25 | Amends ADR-004 |
| [ADR-008](./ADR-008-post-implementation-audit-corrections.md) | Hiệu chỉnh sau audit: status ngoài contract, `storage_key` và giới hạn chuỗi, readiness mặc định, route lạ, request ID trong log | Accepted | 2026-10-09 | Amends ADR-004, ADR-005 |
| [ADR-009](./ADR-009-service-identity-replaces-review-bearer-token.md) | Bỏ `PDP_REVIEW_SERVICE_TOKEN`; `X-Service-Identity` là identity duy nhất tới Review | Accepted | 2026-10-15 | Sửa mục 6 của ADR-007 |
| [ADR-010](./ADR-010-retry-after-on-every-503.md) | `503` luôn kèm `Retry-After` (giá trị circuit khi mở, mặc định 2 giây khác) | Accepted | 2026-10-16 | — |
| [ADR-011](./ADR-011-in-process-catalog-cache.md) | Cache in-process catalog Product (TTL 10 giây, gộp lời gọi trùng slug); tồn kho/review luôn mới | Accepted | 2026-10-17 | Sửa ADR-004 §2 (hủy lời gọi Product khi cache bật) |
