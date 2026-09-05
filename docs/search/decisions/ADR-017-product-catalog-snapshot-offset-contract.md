---
doc_id: search-adr-017
service: search
status: accepted
date: 2026-09-05
decision_owners:
  - Search Service team
  - Product Service team
supersedes: null
evidence:
  - contracts/http/product/v1/openapi.yaml
  - docs/product/decisions/ADR-014-catalog-snapshot-replay-offsets.md
  - docs/search/decisions/ADR-006-three-phase-reindex.md
---

# ADR-017 — Replay boundary catalog do Product export cấp

## Status

`Accepted`.

## Context

ADR-006 định nghĩa pha Catch-up đọc từ watermark trước Build, nhưng chưa quy định
service nào cấp boundary cho catalog snapshot. Inventory và Review đã trả
`replay_from_offsets` cùng snapshot export; Product là ngoại lệ khiến Search phải
tự đọc Kafka và làm contract reindex không đồng nhất.

## Decision

Search coi `replay_from_offsets` từ Product là boundary có thẩm quyền cho catalog:

1. Import toàn bộ Category export và Product export vào index versioned mới.
2. Chỉ sau khi Build của catalog, stock và rating hoàn tất, consumer tạm seek
   inclusive tới offsets Product/Inventory/Review trả về và ghi trực tiếp vào index
   mới.
3. Search không tự chụp Kafka end offset cho `t.category.events` hoặc
   `t.product.events`.

Offsets được lưu theo source/topic/partition cùng `run_id` và export session để
resume. `category_revision`, `catalog_revision`, `stock_revision` và
`review_revision` vẫn là guard duy nhất quyết định event có được apply hay không.

`t.catalog.control.events` chỉ điều phối reindex. Control event mới trong Build hoặc
Catch-up làm run hiện tại `SUPERSEDED`; giữ alias cũ và coalesce một run mới, thay vì
cutover index có category path cũ.

## Alternatives considered

- **Giữ Search tự chụp watermark catalog.** Đơn giản cục bộ nhưng lệch contract
  với upstream khác và buộc consumer biết chi tiết broker của Product.
- **Catch-up đồng thời với Build.** Phức tạp checkpoint/rollback hơn và trái với
  quy trình Build → Catch-up → Cutover đã chọn.

## Consequences

Reindex phụ thuộc Product export có Kafka khỏe mạnh và offsets hợp lệ. Đổi lại,
replay boundary được kiểm chứng tại source owner, workflow nhất quán cho mọi nguồn
dữ liệu và dễ contract-test hơn. ADR này mở rộng ADR-006, không thay ba pha hay alias
swap của ADR đó.

## Migration impact

Search triển khai khả năng đọc offsets Product trước, sau đó Product bật response
field mới và Search gỡ code tự chụp watermark. Run không có đủ offsets phải fail
trước Cutover.

## Verification

Test reindex phải bao phủ Category/Product mutation trong lúc export, replay từ
offset inclusive, resume dùng cùng offsets, control event supersede run, và không có
lời gọi Kafka Admin từ Search để chụp catalog watermark.
