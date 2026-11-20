# ADR-028 — Chính sách bộ lọc và phạm vi discovery storefront

Ngày: 17/10/2026. Trạng thái: Accepted.

## Bối cảnh

Search lập token từ thuộc tính Product/SKU; đây là khả năng truy vấn, không đồng nghĩa mọi thuộc tính đều hữu ích trên giao diện. Catalog local dùng chung `cau-hinh` cho dung lượng, RAM/SSD, công suất và tính năng khác; fixture QA xuất hiện trong facet và tổng số. Frontend mở sẵn toàn bộ lựa chọn gây khay lọc dài hàng nghìn pixel.

## Quyết định

- Chính sách discovery thuộc Search, cấu hình bằng `search.storefront`. Product tiếp tục sở hữu schema/giá trị thuộc tính. Không thêm cờ `filterable` vào Product API, không thay event hoặc index mapping trong lượt này.
- `common-facets` là danh sách mã nhóm có thứ tự cho mọi phạm vi. Search không có danh mục chỉ quảng bá các nhóm này. `category-facets.<slug>` bổ sung nhóm theo nhánh; cấu hình gần category hiện tại nhất ghi đè cấu hình tổ tiên. Nhóm không có token trong kết quả không được tạo giả. Thứ tự/nhãn/opaque token/số đếm hiện hữu được giữ, riêng đơn vị phổ biến được hiển thị đúng chữ hoa.
- Policy áp dụng cho cả response Search (đường grouping và `product_cards`) lẫn category facet bootstrap. Không đổi logic AND giữa mã thuộc tính, OR giữa giá trị cùng mã, hay quy tắc một SKU phải thỏa mọi điều kiện.
- `excluded-product-ids` và `excluded-category-ids` là danh sách ID tường minh, mặc định trống trong service. Compose local khai báo 5 Product QA và 1 category QA đã biết. Không suy đoán fixture từ tên chứa `QA`, không xóa hoặc đổi status của fixture.
- Query SKU/nested SKU thêm loại trừ **trước** grouping, đếm, chọn SKU đại diện và phân trang. Category exclusions áp dụng qua `category_ancestor_ids` trên sản phẩm; cây category bỏ node và hậu duệ thuộc nhánh đã loại. Job category count và nguồn Product suggestion dùng cùng visibility filters. Suggestion đọc loại ID nguồn đã cấu hình trước limit, nên suggestion Product cũ cũng không lọt vào response. Category suggestion loại theo ID nguồn tường minh; nếu loại nhánh có category con, khai báo cả ID các category con để loại cả suggestion đã build trước đó.
- Đây là phạm vi **khám phá công khai**, không phải phân quyền hoặc cấm truy cập Product. API admin, export, gRPC, lịch sử đơn hàng, giỏ hàng đang có và URL chi tiết QA trực tiếp tiếp tục dùng dữ liệu canonical. QA muốn thử public discovery có thể tạm bỏ exclusions trong môi trường kiểm thử riêng.
- Catalog seed chuyển sang thuộc tính có nghĩa rõ ràng bằng API Product/ETag. SKU ID/code, base/selling price, trạng thái và Inventory không bị sửa. `cau-hinh` cũ vẫn là mapping tùy chọn để rollback; SKU trình diễn không còn giá trị ở mapping này. Lịch sử Order snapshot không bị viết lại.

## Hệ quả và vận hành

Không cần migration DB, mapping ES hoặc full reindex cho policy. Thay cấu hình cần rollout Search; thay giá trị thuộc tính hội tụ qua CDC. Cấu hình phải có cùng giá trị trên mọi replica. Khi đổi policy, client nên bắt đầu lượt tìm kiếm mới thay vì giữ PIT cursor cũ. Category count/suggestion rebuild vẫn có lịch cập nhật riêng; metadata nhãn có cache tối đa 60 giây.

Nếu lookup category phục vụ chọn policy lỗi, response chỉ quảng bá nhóm chung; không mở rộng thành mọi thuộc tính. Không dùng policy để ngăn người dùng gửi token hợp lệ từ liên kết cũ: token vẫn có thể lọc, frontend phải giữ hoặc cho bỏ rõ ràng.

Khi thêm thuộc tính/danh mục mới, cập nhật policy theo taxonomy của catalog. Cấu hình hiện tại là mặc định cho catalog tiếng Việt local, không phải danh sách chung cho mọi cửa hàng. Với nhu cầu admin tự biên tập policy có thể bổ sung resource quản trị sau; hiện policy được review và deploy cùng Search.

Kiểm chứng và rollback dữ liệu: [báo cáo triển khai](../../frontend/catalog-filter-fix-2026-10-17.md).
