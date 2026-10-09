---
doc_id: product-detail-bff-domain
service: product-detail-bff
scope: current
status: implemented
owners:
  - Storefront team
---

# Product Detail BFF — Domain Model

## Ngôn ngữ chung

| Term | Định nghĩa |
|---|---|
| Catalog snapshot | Projection buyer do Product trả: nội dung, category path, thuộc tính, trục biến thể, media giới hạn, SKU và giá |
| Category path | Breadcrumb từ category gốc tới category lá của Product |
| Thuộc tính | Dòng bảng thông số từ mapping không biến thể: tên, giá trị và nhãn option khi `SELECT` |
| Trục biến thể | Một attribute biến thể cùng các option đang được SKU active dùng; nguồn nhãn cho bộ chọn SKU |
| Gallery | Media cấp Product và cấp SKU; gallery của SKU đang chọn là media của SKU đó, fallback media cấp Product |
| Availability query result | Trạng thái advisory theo SKU do Inventory Availability Read API trả |
| `IN_STOCK` | Inventory đã xác nhận SKU có thể hiển thị còn hàng tại `evaluated_at` |
| `OUT_OF_STOCK` | Inventory đã xác nhận SKU không còn available-to-sell tại `evaluated_at` |
| `UNKNOWN` | Inventory không có trạng thái tin cậy, BFF thiếu item, hoặc call Inventory không cho kết quả hợp lệ (lỗi, timeout, circuit open, bulkhead đầy, hết budget) |
| Product display availability | Derived UI state: có ít nhất một SKU catalog-active `IN_STOCK` |
| Review summary | Aggregate rating và tổng số review do Review trả |
| Review preview | Danh sách hữu hạn review `APPROVED` mới nhất do Review trả |
| Review section `AVAILABLE` | Review trả thành công; aggregate 0 là trạng thái hợp lệ |
| Review section `UNAVAILABLE` | Call Review không cho kết quả hợp lệ (lỗi, timeout, circuit open, bulkhead đầy, hết budget); không được diễn giải là chưa có review |

## Quy tắc hiển thị

| ID | Quy tắc |
|---|---|
| `PDP-BR-001` | Availability được quyết định theo SKU đã chọn, không theo Product aggregate. |
| `PDP-BR-002` | Chỉ Inventory được map một SKU thành `IN_STOCK`/`OUT_OF_STOCK`; BFF không tự suy diễn từ quantity, cache hoặc sự vắng mặt của data. |
| `PDP-BR-003` | Không có item, item `UNKNOWN`, hoặc call Inventory không cho kết quả hợp lệ phải trả `UNKNOWN`, không mặc định còn hay hết hàng. |
| `PDP-BR-004` | SKU `INACTIVE` hoặc Product không `ACTIVE` không được trả cho buyer, bất kể Inventory trả gì. |
| `PDP-BR-005` | BFF chỉ gửi một batch query cho các SKU active; không gửi reserve/deduct/release và không dùng availability UI để xác nhận đơn. |
| `PDP-BR-006` | BFF chỉ đọc Review summary/preview; không nhận command hay sở hữu invariant Review. |
| `PDP-BR-007` | Review lỗi không làm mất catalog và không được map thành rating/count bằng 0. |
| `PDP-BR-008` | Chỉ review `APPROVED` do Review trả mới xuất hiện trong preview; BFF không tự kiểm duyệt. |
| `PDP-BR-009` | Nút thêm vào giỏ theo SKU đã chọn: `IN_STOCK` và `UNKNOWN` cho phép thêm; `OUT_OF_STOCK` tắt nút và hiển thị hết hàng. `UNKNOWN` không hiển thị nhãn còn hay hết hàng. |
| `PDP-BR-010` | Category, thuộc tính, trục biến thể, nhãn và thứ tự hiển thị do Product quyết định; BFF giữ nguyên thứ tự Product trả, không sắp lại hay tự tra nhãn. |
| `PDP-BR-011` | Public response không chứa storage key. Media và thumbnail là URL CDN do BFF dựng; SKU không có ảnh nhận URL placeholder cấu hình. |

`PDP-BR-009` là quy tắc của storefront, không phải kiểm tra phía server: Cart không gọi
Inventory và không chặn SKU hết hàng ([Cart interfaces](../cart/interfaces.md#2-http-apis)).
`UNKNOWN` được phép thêm vào giỏ vì nó là lỗi hoặc thiếu dữ liệu, không phải hết hàng;
chặn ở đây sẽ biến sự cố Inventory thành mất doanh thu. Checkout allocation Preview và
Order Reserve vẫn là nơi quyết định có bán được hay không. `OUT_OF_STOCK` có thể đã cũ;
buyer thấy trạng thái mới khi tải lại PDP.

Public response chỉ cần `availability_status`; không expose quantity thật. `evaluated_at`
có thể được trả cho client dưới tên `availability_observed_at` nếu storefront cần giải
thích freshness; nó không phải thời điểm reserve. `availability_revision` chỉ dành cho
nội bộ/trace, không là revision toàn cục của PDP.

Thuộc tính `NUMBER` chưa có đơn vị đo; storefront hiển thị giá trị trần cho tới khi Product
bổ sung đơn vị. `value_label`/`label` chỉ có với `SELECT`; kiểu khác hiển thị `value`.

Public response có thể chứa Review summary và preview, nhưng `review_revision`, report
counter, eligibility và moderation state không được expose. Pagination tiếp theo và
mọi write flow thuộc API của Review Service.
