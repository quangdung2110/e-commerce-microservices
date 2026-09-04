---
doc_id: search-adr-004
service: search
status: accepted
date: 2026-09-02
decision_owners:
  - Search Service team
supersedes: null
evidence:
  - docs/search/schema/scripts
---

# ADR-004 — Guard ordering theo từng nguồn dữ liệu

## Context

Một SKU document được ghi bởi ba nguồn độc lập: catalog từ Product, tồn kho từ
Inventory, rating từ Review. Ba nguồn có đồng hồ riêng, partition riêng và không
có quan hệ thứ tự với nhau. Product cấp `catalog_revision` tăng đơn điệu theo
Product aggregate; Inventory và Review chỉ có timestamp của event.

Thiết kế trước đây dùng **một** field `version` chung theo `updated_at` cho mọi
nguồn. Cách đó có hai lỗi: một nguồn có thể chặn nhầm cập nhật của nguồn khác chỉ
vì timestamp nhỏ hơn, và việc so sánh timestamp giữa hai service khác nhau không
có ý nghĩa.

## Decision

Mỗi nguồn có guard riêng trên document, và mỗi nguồn chỉ được ghi các field thuộc
về nó:

| Nguồn | Guard | Kiểu |
|---|---|---|
| Catalog Product | `catalog_revision` | `long`, tăng đơn điệu theo Product |
| Category | `category_version` | timestamp event |
| Inventory | `stock_version` | timestamp event |
| Review | `review_version` | timestamp event |
| Recompute cây danh mục | `tree_version` | `long` do job cấp |

Guard được thực thi trong painless script, không ở tầng ứng dụng, để không có
khoảng trống read-modify-write. So sánh là `>` chặt. Guard dạng timestamp bắt buộc
được chuẩn hóa về ISO 8601 UTC với độ chính xác millisecond và hậu tố `Z`, để so
sánh chuỗi trùng với so sánh thời gian.

## Alternatives considered

- **Một `version` chung.** Đơn giản nhưng sai về mặt ngữ nghĩa như mô tả ở trên.
- **Chỉ dựa vào ordering của Kafka partition.** Đúng trong cùng key nhưng không
  bảo vệ được khi replay, khi rebalance làm xử lý lại, hay khi hai nguồn ghi cùng
  document.
- **Dùng `_seq_no`/`if_primary_term` với optimistic concurrency.** Bảo vệ được
  ghi đồng thời nhưng không biết gì về tuổi nghiệp vụ của event, nên vẫn để lọt
  event cũ.
- **Bảng dedupe theo `event_id`.** Chống trùng nhưng không chống được thứ tự đảo,
  và thêm một store mà [ADR-001](./ADR-001-elasticsearch-only-store.md) đã loại bỏ.

## Consequences

Tích cực: mỗi nguồn tự bảo vệ, apply lại event là no-op, và không cần idempotency
storage. Ghi đồng thời từ hai nguồn khác nhau là an toàn vì tập field rời nhau.

Tiêu cực: document mang bốn field metadata; script phức tạp hơn; hai event cùng
timestamp thì event sau bị bỏ qua — chấp nhận được vì cả hai mang cùng giá trị
trong thực tế. Nếu Product từng phát lại toàn bộ catalog với revision đặt lại về
1, mọi event sẽ bị guard chặn: đây là ràng buộc mà Product phải giữ.

## Migration impact

Implementation hiện tại dùng field `version` duy nhất, nên cần: thêm bốn field vào
mapping, cập nhật script, và chạy full reindex để điền guard mới. Trong thời gian
chuyển tiếp, không được để hai thế hệ script cùng ghi lên một index.

## Verification

Test bắt buộc: event cũ hơn bị bỏ qua; event trùng là no-op; event tồn kho không
làm mất cập nhật catalog và ngược lại; event tới sau tombstone không có tác dụng.
