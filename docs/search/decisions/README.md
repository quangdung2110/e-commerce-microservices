# Search Service — Architecture Decision Records

Thư mục này lưu các quyết định kiến trúc dài hạn của Search Service. Trạng thái
`Accepted` nghĩa là quyết định đã được chấp thuận ở mức thiết kế; nó **không** có
nghĩa implementation đã được xác minh khớp với quyết định. Khoảng cách giữa hai
điều đó được liệt kê ở [Known gaps](../README.md#9-known-gaps).

## Quy tắc

- Sao chép [ADR-000-template.md](./ADR-000-template.md) thành
  `ADR-<sequence>-<short-name>.md` khi tạo quyết định mới.
- Không sửa lịch sử của ADR đã `Accepted`; tạo ADR mới, đánh dấu ADR cũ là
  `Superseded` và liên kết hai chiều.
- Decision phải cụ thể và kiểm chứng được; mỗi ADR phải nêu cả hệ quả tiêu cực và
  chi phí migration.
- Tài liệu workflow và architecture chỉ liên kết tới ADR, không giấu trade-off
  trong mô tả luồng.
- Lịch sử các đợt thay đổi trước đây (bỏ DLQ, bỏ topic metrics, gộp consumer group)
  sống trong ADR dưới dạng Context, không rải rác thành ghi chú “Phase” trong thân
  tài liệu.

## Decision index

| ADR | Decision | Status | Date | Supersedes |
|---|---|---|---|---|
| [ADR-000](./ADR-000-template.md) | Template — không phải quyết định thực tế | Template | N/A | N/A |
| [ADR-001](./ADR-001-elasticsearch-only-store.md) | Elasticsearch là store duy nhất | Accepted | 2026-09-02 | — |
| [ADR-002](./ADR-002-sku-first-document.md) | Document là SKU, gom nhóm bằng field collapsing | Superseded | 2026-09-02 | ADR-012 |
| [ADR-003](./ADR-003-vietnamese-analysis-and-autocomplete.md) | Phân tích tiếng Việt bằng ICU và autocomplete edge n-gram | Accepted | 2026-09-02 | — |
| [ADR-004](./ADR-004-per-source-ordering-guard.md) | Guard ordering theo từng nguồn dữ liệu | Superseded | 2026-09-02 | ADR-015 |
| [ADR-005](./ADR-005-no-dead-letter-queue.md) | Không dùng dead-letter queue | Accepted | 2026-09-02 | — |
| [ADR-006](./ADR-006-three-phase-reindex.md) | Reindex ba pha với alias swap | Accepted | 2026-09-02 | — |
| [ADR-007](./ADR-007-cursor-pagination.md) | Cursor pagination bằng `search_after` | Accepted | 2026-09-02 | — |
| [ADR-008](./ADR-008-category-product-count-recompute.md) | `product_count` tính lại theo lịch, không cộng trừ | Accepted | 2026-09-02 | Cộng trừ theo event của thiết kế cũ |
| [ADR-009](./ADR-009-stock-projection-ownership.md) | `is_in_stock` đến từ Inventory | Superseded | 2026-09-02 | ADR-014 |
| [ADR-010](./ADR-010-derived-category-tree.md) | Search tự dẫn xuất cây danh mục | Superseded | 2026-09-02 | ADR-013 |
| [ADR-011](./ADR-011-tombstone-and-purge-window.md) | Tombstone kèm cửa sổ purge | Accepted | 2026-09-02 | Draft Cleanup Job của thiết kế cũ |
| [ADR-012](./ADR-012-sku-results-with-search-after.md) | SKU hit trực tiếp, phân trang bằng `search_after` | Accepted | 2026-09-04 | ADR-002 |
| [ADR-013](./ADR-013-product-category-snapshots.md) | Category snapshot Product và control-driven reindex | Accepted | 2026-09-04 | ADR-010 |
| [ADR-014](./ADR-014-inventory-stock-snapshot-contract.md) | Stock projection theo revision và snapshot Inventory | Accepted | 2026-09-04 | ADR-009 |
| [ADR-015](./ADR-015-upstream-revision-guards.md) | Guard theo revision upstream đơn điệu | Accepted | 2026-09-04 | ADR-004 |
| [ADR-016](./ADR-016-review-rating-snapshot-reindex.md) | Snapshot rating Review khi reindex | Accepted | 2026-09-05 | — |
| [ADR-017](./ADR-017-product-catalog-snapshot-offset-contract.md) | Replay boundary catalog do Product export cấp | Accepted | 2026-09-05 | — |
