---
doc_id: product-adr-008
service: product
status: accepted
date: 2026-08-30
decision_owners:
  - Product Service team
supersedes: null
evidence: []
---

# ADR-008 — Product tính sẵn `best_price`; `display_quantity` là replica

## Status

`Accepted`

## Context

Giá hiển thị của một SKU phụ thuộc vào `original_price` và markdown rule đang active.
Nếu mỗi downstream (Search, Cart, Checkout, trang chi tiết) tự tính giá, chỉ cần một
bên hiểu sai công thức — quên cap `max_discount_amount`, làm tròn khác, hoặc coi
`promotional_price = NULL` là giá 0 — là người mua thấy giá khác nhau ở hai màn hình.

Tương tự, `display_quantity` nằm trong database của Product nhưng phản ánh dữ liệu của
Inventory Service. Nếu có ai dùng nó để quyết định còn hàng hay không lúc đặt đơn, hệ
thống sẽ bán vượt tồn kho vì con số này luôn trễ.

## Decision

Product Service tính sẵn và công bố `best_price` cùng `has_promotion` trong mọi
point-read, mọi event mang snapshot SKU, và trong point-read cache. Công thức:
`best_price = LEAST(original_price, COALESCE(promotional_price, original_price))`, làm
tròn hai chữ số và không âm.

Downstream **chỉ đọc và phản chiếu** `best_price`; không service nào được tự tính lại
giá markdown từ `original_price` và `discount_value`.

Admin chỉ được sửa `original_price`. `promotional_price` do Pricing module của Product
tính, qua đúng hai đường hợp lệ: Markdown Worker khi rule thay đổi, và nhánh đồng bộ
trong API update khi admin đổi `original_price` trong lúc rule đang active.

`display_quantity` và `last_inventory_sync_at` là replica từ Inventory Service. Chúng
chỉ được dùng để hiển thị và để làm guard cho `SHOW`, `AUTO_HIDE_OUT_OF_STOCK`,
`AUTO_REACTIVATE_IN_STOCK`. Chúng không bao giờ được dùng để giữ hàng, trừ kho hoặc xác
nhận khả dụng khi đặt đơn — việc đó thuộc Inventory Service.

## Alternatives considered

- **Downstream tự tính giá** từ `original_price` và thông tin rule: linh hoạt hơn cho
  từng kênh hiển thị, nhưng nhân bản logic nghiệp vụ ra nhiều service và không có cách
  nào bảo đảm chúng đồng nhất.
- **Gọi Promotion Service để lấy giá theo thời gian thực**: luôn đúng tuyệt đối, nhưng
  đặt một cuộc gọi mạng vào đường mua hàng và biến Promotion thành điểm chết chung.
- **Không lưu `display_quantity` ở Product, luôn hỏi Inventory**: dữ liệu tươi hơn,
  nhưng khiến mọi lần hiển thị sản phẩm phụ thuộc Inventory và làm guard của FSM không
  thể chạy trong transaction.

## Consequences

Tích cực: giá hiển thị thống nhất trên mọi kênh; công thức chỉ tồn tại ở một nơi; đường
đọc của Cart/Checkout không phụ thuộc Promotion; guard FSM chạy được hoàn toàn trong
transaction của Product.

Tiêu cực: mọi thay đổi công thức giá đều là thay đổi của Product và phải phát lại event
cho toàn bộ SKU bị ảnh hưởng; `display_quantity` luôn trễ so với Inventory nên có thể
hiển thị số lượng không còn đúng; và có rủi ro thường trực là ai đó dùng nhầm con số này
để quyết định bán hàng.

Ràng buộc mới: contract point-read phải chứa đủ `original_price`, `promotional_price`,
`best_price`, `has_promotion` — hiện `product_service.proto` **chưa** đáp ứng, xem
[Known gaps](../README.md#9-known-gaps). Mọi service đặt đơn phải kiểm tra tồn kho với
Inventory Service, không dựa vào dữ liệu của Product.

## Migration impact

Cần mở rộng proto để mang đủ mô hình giá; đây là thay đổi additive nhưng phải phối hợp
với Cart. Không cần migration dữ liệu vì `best_price` và `has_promotion` là giá trị tính
ra chứ không phải cột.

## Verification

Chứng minh bằng: unit test cho công thức giá ở mọi nhánh (có cap, không cap, giá giảm
lớn hơn giá gốc); kiểm tra mọi payload event mang snapshot SKU đều có bốn field giá; và
rà soát rằng không service nào dùng `display_quantity` trong luồng đặt đơn.
