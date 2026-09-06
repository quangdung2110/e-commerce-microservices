---
doc_id: product-detail-bff-domain
service: product-detail-bff
scope: target
status: proposed
owners:
  - Storefront team
---

# Product Detail BFF — Domain Model

## Ngôn ngữ chung

| Term | Định nghĩa |
|---|---|
| Catalog snapshot | Product detail/variant/price do Product trả |
| Availability projection | Snapshot Inventory theo SKU được BFF materialize |
| `IN_STOCK` | Snapshot hợp lệ có `available_quantity > 0` |
| `OUT_OF_STOCK` | Snapshot hợp lệ có `available_quantity = 0` |
| `UNKNOWN` | Không có snapshot, projection quá stale hoặc recovery chưa hoàn tất |
| Product display availability | Derived: có ít nhất một SKU catalog-active `IN_STOCK` |
| Review summary | Aggregate rating và tổng số review do Review trả |
| Review preview | Danh sách hữu hạn review `APPROVED` mới nhất do Review trả |
| Review section `AVAILABLE` | Review trả thành công; aggregate 0 là trạng thái hợp lệ |
| Review section `UNAVAILABLE` | Review timeout/lỗi; không được diễn giải là chưa có review |

## Quy tắc hiển thị

| ID | Quy tắc |
|---|---|
| `PDP-BR-001` | Availability được quyết định theo SKU đã chọn, không theo Product aggregate. |
| `PDP-BR-002` | Không có row hoặc row quá freshness budget phải trả `UNKNOWN`, không mặc định còn hàng. |
| `PDP-BR-003` | SKU `INACTIVE` hoặc Product không `ACTIVE` không được trả cho buyer, bất kể projection tồn kho. |
| `PDP-BR-004` | Chỉ `stock_revision` lớn hơn thay đổi row availability. |
| `PDP-BR-005` | BFF không gửi reserve/deduct/release và không dùng availability UI để xác nhận đơn. |
| `PDP-BR-006` | BFF chỉ đọc Review summary/preview; không nhận command hay sở hữu invariant Review. |
| `PDP-BR-007` | Review lỗi không làm mất catalog và không được map thành rating/count bằng 0. |
| `PDP-BR-008` | Chỉ review `APPROVED` do Review trả mới xuất hiện trong preview; BFF không tự kiểm duyệt. |

Public response chỉ cần `availability_status`; việc expose số lượng thật là product
policy riêng và không phải điều kiện để bật/tắt UI. `observed_at` có thể được trả cho
client nếu cần giải thích freshness, còn `stock_revision` chỉ dành cho nội bộ/trace.

Public response có thể chứa Review summary và preview, nhưng `review_revision`, report
counter, eligibility và moderation state không được expose. Pagination tiếp theo và
mọi write flow thuộc API của Review Service.
