---
doc_id: product-adr-006
service: product
status: superseded
date: 2026-08-30
decision_owners:
  - Product Service team
supersedes: null
superseded_by: ADR-010
evidence: []
---

# ADR-006 — Hợp nhất event cấp SKU thành `SKU_UPDATED`

## Status

`Superseded` bởi [ADR-010](./ADR-010-catalog-event-revision-and-category-reindex.md).

## Context

Trước đây thay đổi cấp SKU được phát bằng hai event riêng: `SKU_PRICE_UPDATED` cho giá
và `SKU_STATUS_CHANGED` cho trạng thái. Thực tế nhiều thay đổi chạm cả hai nhóm cùng
lúc (Phase 2 đổi `sku_status` và đôi khi đổi luôn `product_status`; admin sửa giá kèm
media), khiến consumer phải ghép hai event lại và tự đoán thứ tự.

Đồng thời, downstream cần biết **cái gì đã đổi** để quyết định hành động, chứ không nên
so sánh payload với dữ liệu hiện có của mình.

## Decision

Chỉ tồn tại một event cho mọi thay đổi cấp SKU: `SKU_UPDATED`. Hai tên cũ bị loại bỏ
hoàn toàn khỏi code, tài liệu và cấu hình.

Payload là snapshot SKU đầy đủ (không phải delta) kèm:

- `changed_fields[]`: liệt kê đúng các field thực sự đổi giá trị. Consumer dùng field
  này để quyết định hành động và phải bỏ qua an toàn giá trị lạ.
- `change_source`: bắt buộc khi `sku_status` nằm trong `changed_fields`, cho biết
  nguồn gây ra thay đổi (ví dụ admin, markdown, auto-hide, auto-reactivate, fallback
  của cronjob).
- `product_status_changed` và `new_product_status`: có mặt khi FSM đổi luôn trạng thái
  product trong cùng thao tác, để consumer xử lý cả hai cấp trong một event mà không cần
  `PRODUCT_STATUS_CHANGED` riêng.
- `updated_at`: mốc ordering guard duy nhất cho downstream. Không dùng version number.

Toàn bộ event cấp SKU (`SKU_CREATED`, `SKU_UPDATED`, `SKU_DELETED`) dùng
`partition_key = sku_id`; event cấp Product dùng `product_id`.

## Alternatives considered

- **Giữ hai event riêng**: mỗi event nhỏ và rõ nghĩa hơn, nhưng consumer phải tự tương
  quan hai event khi một thao tác đổi cả hai nhóm field, và số loại event tăng theo mỗi
  nhóm field mới.
- **Một event chỉ chứa delta**: nhẹ hơn, nhưng consumer nào mất một event sẽ không thể
  tự phục hồi trạng thái mà phải gọi ngược lại API.
- **Để consumer tự so sánh payload với dữ liệu của mình** thay vì `changed_fields`:
  bỏ được một field, nhưng đẩy logic suy luận sang từng consumer và mỗi bên sẽ tự đoán
  một kiểu.

## Consequences

Tích cực: một thao tác nghiệp vụ tương ứng một event; consumer chỉ cần một đường xử lý;
snapshot đầy đủ giúp bên nhận tự phục hồi mà không cần gọi ngược; ordering guard thống
nhất bằng `updated_at`.

Tiêu cực: payload lớn hơn; consumer phải đọc `changed_fields` đúng cách, nếu bỏ qua sẽ
xử lý thừa; `partition_key = sku_id` nghĩa là thứ tự chỉ được bảo đảm trong phạm vi một
SKU chứ không phải toàn bộ product — hai SKU của cùng một product có thể tới lệch nhau.

Ràng buộc mới: producer phải tính `changed_fields` chính xác (không thiếu, không thừa);
consumer không được throw khi gặp giá trị chưa biết trong `changed_fields` hoặc
`change_source`.

## Migration impact

Đây là breaking change đối với consumer còn nghe hai event cũ; việc chuyển đổi đã được
thực hiện ở mức thiết kế và hai tên cũ không còn tồn tại. Cần contract test cho
`changed_fields` và `change_source`, và cần AsyncAPI để ràng buộc payload. Ghi chú này
thuộc trạng thái lịch sử của ADR-006; contract hiện hành nằm tại ADR-010.

## Verification

Chứng minh bằng: contract test khẳng định `changed_fields` khớp thay đổi thực tế và
`change_source` có mặt khi cần; test consumer với giá trị lạ trong `changed_fields`; và
kiểm tra không còn tham chiếu tới `SKU_PRICE_UPDATED`/`SKU_STATUS_CHANGED` trong toàn
bộ repository.
