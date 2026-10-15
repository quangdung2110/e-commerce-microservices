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

Cột **Quan hệ** luôn đọc theo chiều của chính dòng đó: “Thay X” nghĩa là ADR này thay
X, “Bị thay bởi Y” nghĩa là Y thay ADR này.

| ADR | Decision | Status | Date | Quan hệ |
|---|---|---|---|---|
| [ADR-000](./ADR-000-template.md) | Template — không phải quyết định thực tế | Template | N/A | — |
| [ADR-001](./ADR-001-elasticsearch-only-store.md) | Elasticsearch là store duy nhất | Accepted | 2026-09-02 | — |
| [ADR-002](./ADR-002-sku-first-document.md) | Document là SKU, gom nhóm bằng field collapsing | Superseded | 2026-09-02 | Bị thay bởi ADR-012 |
| [ADR-003](./ADR-003-vietnamese-analysis-and-autocomplete.md) | Phân tích tiếng Việt bằng ICU và autocomplete edge n-gram | Accepted | 2026-09-02 | — |
| [ADR-004](./ADR-004-per-source-ordering-guard.md) | Guard ordering theo từng nguồn dữ liệu | Superseded | 2026-09-02 | Bị thay bởi ADR-015 |
| [ADR-005](./ADR-005-no-dead-letter-queue.md) | Không dùng dead-letter queue | Accepted; điều khoản Inventory missing-doc đã bị thay | 2026-09-02 | Điều khoản Inventory bị ADR-019 thay; ADR-022 làm rõ cơ chế retry (`nack`) |
| [ADR-006](./ADR-006-three-phase-reindex.md) | Reindex ba pha với alias swap | Accepted | 2026-09-02 | Được ADR-016, ADR-017 và ADR-022 (vòng đời reindex) mở rộng |
| [ADR-007](./ADR-007-cursor-pagination.md) | Cursor pagination bằng `search_after` | Superseded cho storefront | 2026-09-02 | Storefront bị ADR-020 thay |
| [ADR-008](./ADR-008-category-product-count-recompute.md) | `product_count` tính lại theo lịch, không cộng trừ | Accepted; đã đính chính field gom bucket | 2026-09-02 | Thay cách cộng trừ theo event của thiết kế cũ |
| [ADR-009](./ADR-009-stock-projection-ownership.md) | `is_in_stock` đến từ Inventory | Superseded | 2026-09-02 | Bị thay bởi ADR-014 |
| [ADR-010](./ADR-010-derived-category-tree.md) | Search tự dẫn xuất cây danh mục | Superseded | 2026-09-02 | Bị thay bởi ADR-013 |
| [ADR-011](./ADR-011-tombstone-and-purge-window.md) | Tombstone kèm cửa sổ purge | Accepted; đã được mở rộng | 2026-09-02 | Thay Draft Cleanup Job cũ; ADR-019 mở rộng gate purge; ADR-021 chốt cửa sổ 35 ngày, ADR-022 nâng lên 45 ngày |
| [ADR-012](./ADR-012-sku-results-with-search-after.md) | SKU hit trực tiếp, phân trang bằng `search_after` | Superseded | 2026-09-04 | Thay ADR-002; bị thay bởi ADR-020 |
| [ADR-013](./ADR-013-product-category-snapshots.md) | Category snapshot Product và control-driven reindex | Accepted | 2026-09-04 | Thay ADR-010 |
| [ADR-014](./ADR-014-inventory-stock-snapshot-contract.md) | Stock projection theo revision và snapshot Inventory | Superseded | 2026-09-04 | Thay ADR-009; bị thay bởi ADR-018 |
| [ADR-015](./ADR-015-upstream-revision-guards.md) | Guard theo revision upstream đơn điệu | Accepted; điều khoản Inventory đã bị thay | 2026-09-04 | Thay ADR-004; điều khoản Inventory bị ADR-019 thay |
| [ADR-016](./ADR-016-review-rating-snapshot-reindex.md) | Snapshot rating Review khi reindex | Accepted | 2026-09-05 | Mở rộng nguồn dữ liệu pha Build của ADR-006 |
| [ADR-017](./ADR-017-product-catalog-snapshot-offset-contract.md) | Replay boundary catalog do Product export cấp | Accepted; điều khoản Inventory đã bị thay | 2026-09-05 | Mở rộng ADR-006; điều khoản Inventory bị ADR-019 thay |
| [ADR-018](./ADR-018-inventory-position-projection-contract.md) | Inventory position projection and export watermark | Superseded | 2026-09-07 | Thay ADR-014; bị thay bởi ADR-019 |
| [ADR-019](./ADR-019-inventory-owned-sku-availability.md) | Inventory owns per-SKU availability; readiness guarantees Product document exists; no Search index added | Accepted | 2026-09-07 | Thay ADR-018 |
| [ADR-020](./ADR-020-product-cards-with-cheapest-eligible-sku.md) | Một Product một card, SKU giá thấp nhất thỏa bộ lọc; signed PIT cursor | Accepted | 2026-09-30 | Thay ADR-012 và phần storefront của ADR-007 |
| [ADR-021](./ADR-021-tombstone-protection-window-35d.md) | Cửa sổ bảo vệ tombstone 35 ngày, dài hơn retention dài nhất của topic Search tiêu thụ | Superseded | 2026-10-09 | Mở rộng ADR-011; bị thay bởi ADR-022 |
| [ADR-022](./ADR-022-post-implementation-audit-corrections.md) | Sửa sau kiểm chứng audit: redelivery bằng `nack`, lỗi HTTP, vòng đời reindex (lease, resume, hủy, rollback, kiểm tra cutover, tail replay, dọn index, tự chạy theo control event), cửa sổ tombstone 45 ngày | Accepted | 2026-10-10 | Thay con số của ADR-021; làm rõ ADR-005/ADR-019; mở rộng ADR-006/013/017 |
| [ADR-023](./ADR-023-request-correlation-and-caller-deadline.md) | Request ID, trace ID, deadline của caller và timeout export 25 giây (nhỏ hơn mesh 30 giây) | Accepted | 2026-10-15 | Làm rõ ADR-022 |
