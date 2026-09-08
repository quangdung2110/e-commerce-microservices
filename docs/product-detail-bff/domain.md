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
| Availability query result | Trạng thái advisory theo SKU do Inventory Availability Read API trả |
| `IN_STOCK` | Inventory đã xác nhận SKU có thể hiển thị còn hàng tại `evaluated_at` |
| `OUT_OF_STOCK` | Inventory đã xác nhận SKU không còn available-to-sell tại `evaluated_at` |
| `UNKNOWN` | Inventory không có trạng thái tin cậy, BFF thiếu item, hoặc Inventory dependency lỗi |
| Product display availability | Derived UI state: có ít nhất một SKU catalog-active `IN_STOCK` |
| Review summary | Aggregate rating và tổng số review do Review trả |
| Review preview | Danh sách hữu hạn review `APPROVED` mới nhất do Review trả |
| Review section `AVAILABLE` | Review trả thành công; aggregate 0 là trạng thái hợp lệ |
| Review section `UNAVAILABLE` | Review timeout/lỗi; không được diễn giải là chưa có review |

## Quy tắc hiển thị

| ID | Quy tắc |
|---|---|
| `PDP-BR-001` | Availability được quyết định theo SKU đã chọn, không theo Product aggregate. |
| `PDP-BR-002` | Chỉ Inventory được map một SKU thành `IN_STOCK`/`OUT_OF_STOCK`; BFF không tự suy diễn từ quantity, cache hoặc sự vắng mặt của data. |
| `PDP-BR-003` | Không có item, item `UNKNOWN`, timeout hoặc `503` từ Inventory phải trả `UNKNOWN`, không mặc định còn hay hết hàng. |
| `PDP-BR-004` | SKU `INACTIVE` hoặc Product không `ACTIVE` không được trả cho buyer, bất kể Inventory trả gì. |
| `PDP-BR-005` | BFF chỉ gửi một batch query cho các SKU active; không gửi reserve/deduct/release và không dùng availability UI để xác nhận đơn. |
| `PDP-BR-006` | BFF chỉ đọc Review summary/preview; không nhận command hay sở hữu invariant Review. |
| `PDP-BR-007` | Review lỗi không làm mất catalog và không được map thành rating/count bằng 0. |
| `PDP-BR-008` | Chỉ review `APPROVED` do Review trả mới xuất hiện trong preview; BFF không tự kiểm duyệt. |

Public response chỉ cần `availability_status`; không expose quantity thật. `evaluated_at`
có thể được trả cho client dưới tên `availability_observed_at` nếu storefront cần giải
thích freshness; nó không phải thời điểm reserve. `availability_revision` chỉ dành cho
nội bộ/trace, không là revision toàn cục của PDP.

Public response có thể chứa Review summary và preview, nhưng `review_revision`, report
counter, eligibility và moderation state không được expose. Pagination tiếp theo và
mọi write flow thuộc API của Review Service.
