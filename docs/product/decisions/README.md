# Product Service — Architecture Decision Records

Thư mục này lưu các quyết định kiến trúc dài hạn của Product Service. Trạng thái
`Accepted` nghĩa là quyết định đã được chấp thuận ở mức thiết kế; nó không có nghĩa
implementation đã được verified.

## Quy tắc

- Sao chép [ADR-000-template.md](./ADR-000-template.md) thành
  `ADR-<sequence>-<short-name>.md` khi tạo quyết định mới.
- Không sửa lịch sử của ADR `Accepted`; tạo ADR mới để thay thế và liên kết hai chiều
  qua field `supersedes`.
- Decision phải cụ thể và kiểm chứng được; nêu rõ alternative, hệ quả tiêu cực và cách
  verification.
- `architecture.md` và `workflows.md` chỉ liên kết tới ADR; không giấu trade-off trong
  mô tả luồng.

## Khi nào phải tạo ADR

Khi thay đổi service boundary hoặc data ownership; khi chọn hoặc thay thế database,
broker, framework, protocol; khi đổi mô hình consistency, concurrency, caching hay
messaging; khi tạo trade-off khó đảo ngược; và khi thay thế một quyết định đã được
chấp nhận.

## Decision index

| ADR | Decision | Status | Date | Supersedes |
|---|---|---|---|---|
| [ADR-000](./ADR-000-template.md) | Template — không phải quyết định thực tế | Template | N/A | N/A |
| [ADR-001](./ADR-001-java-spring-boot-runtime.md) | Java và Spring Boot cho Product Service | Accepted | 2026-08-30 | — |
| [ADR-002](./ADR-002-outbox-debezium-cdc.md) | Transactional outbox phát qua Debezium CDC | Superseded | 2026-08-30 | ADR-012 |
| [ADR-003](./ADR-003-two-phase-lock-free-inventory-sync.md) | Inventory sync hai pha, lock-free ở pha một | Superseded | 2026-08-30 | ADR-009 |
| [ADR-004](./ADR-004-pessimistic-locking-order.md) | Pessimistic locking và lock order Product → SKU | Superseded | 2026-08-30 | ADR-011 |
| [ADR-005](./ADR-005-single-writer-cache-ownership.md) | Một writer bất đồng bộ duy nhất cho point-read cache | Superseded | 2026-08-30 | ADR-009 |
| [ADR-006](./ADR-006-consolidated-sku-updated-event.md) | Hợp nhất event cấp SKU thành `SKU_UPDATED` | Superseded | 2026-08-30 | ADR-010 |
| [ADR-007](./ADR-007-admin-only-product-lifecycle.md) | Vòng đời admin-only ba trạng thái | Accepted | 2026-08-30 | — |
| [ADR-008](./ADR-008-price-and-inventory-snapshot-ownership.md) | Product tính sẵn `best_price`; `display_quantity` là replica | Superseded | 2026-08-30 | ADR-009 |
| [ADR-009](./ADR-009-catalog-boundary-and-published-price.md) | Product là catalog SoT; Inventory không phản chiếu, caller Reserve cũ | Superseded | 2026-09-01 | ADR-003, ADR-005, ADR-008; superseded by ADR-017 |
| [ADR-010](./ADR-010-catalog-event-revision-and-category-reindex.md) | Catalog event revision và full reindex khi đổi cây Category | Accepted | 2026-09-02 | ADR-006 |
| [ADR-011](./ADR-011-markdown-consumer-only-background-processing.md) | Markdown Consumer là background workload duy nhất | Superseded | 2026-09-02 | ADR-004 |
| [ADR-012](./ADR-012-outbox-partition-key-by-product-aggregate.md) | Outbox partition key theo Product aggregate | Accepted | 2026-09-02 | ADR-002 |
| [ADR-013](./ADR-013-effective-sku-thumbnail.md) | Product resolve effective thumbnail theo SKU | Accepted | 2026-09-04 | — |
| [ADR-014](./ADR-014-catalog-snapshot-replay-offsets.md) | Catalog export trả replay offsets cho Search | Accepted | 2026-09-05 | — |
| [ADR-015](./ADR-015-buyer-product-detail-read.md) | Buyer Product Detail read nội bộ cho BFF | Proposed | 2026-09-06 | —; amended by ADR-018 |
| [ADR-016](./ADR-016-state-discriminated-markdown-snapshot.md) | Markdown snapshot phân biệt ACTIVE và INACTIVE | Accepted | 2026-09-11 | ADR-011 |
| [ADR-017](./ADR-017-catalog-price-and-inventory-orchestration-boundary.md) | Product catalog/price SoT; Checkout Preview, Order Reserve | Accepted | 2026-09-20 | ADR-009 |
| [ADR-018](./ADR-018-resolved-buyer-product-detail-projection.md) | Buyer-read trả projection đã resolve: category path, nhãn thuộc tính, trục biến thể, media 20/10 | Proposed | 2026-09-25 | — (amends ADR-015) |
