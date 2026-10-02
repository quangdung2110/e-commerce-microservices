---
doc_id: inventory-adr-008
service: inventory
status: superseded
date: 2026-09-05
decision_owners:
  - Inventory Service team
supersedes: null
superseded_by: ADR-012-order-allocation-lifecycle
evidence:
  - java/services/inventory-service/src/main/java/com/platform/inventoryservice/routing
---

# ADR-008 — Thuật toán chọn kho đơn giản sau khi bỏ Shipping Service

## Status

`Superseded` by [ADR-012](./ADR-012-order-allocation-lifecycle.md). This file is historical evidence and is not implementation authority. Code references point to the removed legacy implementation.

## Context

Thiết kế ban đầu có một "Smart Routing Engine" chấm điểm các tổ hợp kho theo chi phí
vận chuyển và số ngày giao dự kiến, rồi chọn tổ hợp tối ưu. Cả hai đại lượng đó đến từ
Shipping Service.

Shipping Service đã bị loại khỏi hệ thống. Không còn nguồn nào cung cấp chi phí vận
chuyển hay thời gian giao, nên hàm chấm điểm không còn đầu vào. Giữ nguyên thuật toán
cũ với các hằng số bịa ra sẽ tạo ra vẻ ngoài của sự tối ưu mà không có nội dung.

Câu hỏi còn lại: chọn kho theo tiêu chí gì khi không biết chi phí giao hàng?

## Decision

**Chọn kho theo ba tiêu chí, xét theo thứ tự: đủ hàng, cùng tỉnh với khách, rồi
`warehouse_id` nhỏ nhất.**

Cụ thể:

- Sinh danh sách ứng viên: mọi `(sku, kho)` có tồn kho khả dụng lớn hơn 0.
- Với mỗi SKU, giữ lại các kho có `available_quantity >= quantity` yêu cầu.
- Ưu tiên kho có `city_code` trùng `customerLocation.cityCode`.
- Nếu vẫn hòa, lấy `warehouse_id` nhỏ nhất — deterministic, để hai lần preview cùng
  input cho cùng kết quả.
- Kết quả được cache dưới dạng route plan với TTL 20 phút; plan hết hạn thì phải
  preview lại, không có gia hạn.
- `districtCode` được nhận nhưng **không** tham gia thuật toán.

Không thuộc phạm vi: tối ưu chi phí vận chuyển, gộp đơn, hay chia một SKU cho nhiều kho.

## Alternatives considered

**Giữ hàm chấm điểm với hằng số ước lượng.** Trông "thông minh" hơn, nhưng các hằng số
không dựa trên dữ liệu thật nào. Kết quả tối ưu theo một mô hình sai còn tệ hơn kết quả
đơn giản mà giải thích được.

**Chọn ngẫu nhiên trong các kho đủ hàng.** Phân tán tải đều. Nhưng hai lần preview cùng
input cho hai kết quả khác nhau, làm việc debug và trải nghiệm người dùng đều tệ hơn.

**Chọn kho có tồn kho lớn nhất.** Có lý về mặt cân bằng kho. Nhưng làm hàng dồn về một
kho lớn bất kể khoảng cách tới khách, đi ngược mục tiêu giao nhanh.

**Cho phép chia một SKU cho nhiều kho.** Tăng tỉ lệ đơn được nhận khi không kho nào đủ
một mình. Nhưng làm route plan, reservation và luồng xuất kho phức tạp hơn hẳn, và tạo
đơn hàng giao thành nhiều lần. Chưa tương xứng với lợi ích ở quy mô hiện tại.

## Consequences

**Tích cực:**

- Thuật toán giải thích được trong một câu, và giải thích được với người vận hành khi
  họ hỏi "tại sao đơn này lại xuất từ kho đó".
- Deterministic: cùng input và cùng trạng thái tồn kho cho cùng kết quả.
- Không phụ thuộc service nào khác.
- Truy vấn ứng viên chạy trên một partial index và rẻ.

**Tiêu cực:**

- Không tối ưu chi phí hay thời gian giao. Kho cùng tỉnh chỉ là xấp xỉ thô của "gần khách".
- Đơn bị từ chối khi không kho nào **một mình** đủ hàng, kể cả khi tổng tồn kho toàn hệ
  thống dư sức đáp ứng. Đây là hệ quả trực tiếp nhất và cũng là hạn chế đáng cân nhắc
  nhất của quyết định này.
- Tie-break theo `warehouse_id` nhỏ nhất làm kho có id thấp bị dùng nhiều hơn một cách
  hệ thống — một dạng thiên lệch tải không chủ ý.
- `districtCode` tồn tại trong contract nhưng vô dụng; nó là mồi bẫy cho người tích hợp.
- Route plan không giữ chỗ, nên plan hợp lệ vẫn có thể dẫn tới `OUT_OF_STOCK` ở bước
  reserve. Đây là chủ đích, nhưng cần được nói rõ với mọi caller.

**Technical debt được chấp nhận:** thiên lệch tải theo `warehouse_id` và việc không hỗ
trợ chia SKU cho nhiều kho.

## Migration impact

- **Code:** hàm chấm điểm cũ bị gỡ bỏ hoàn toàn cùng với phụ thuộc vào Shipping.
- **Contract:** route plan vẫn giữ nguyên hình dạng; `districtCode` được giữ để tương
  thích client, đánh dấu optional.
- **Data:** `warehouses.city_code` trở thành trường tham gia trực tiếp vào quyết định
  nghiệp vụ, nên nó phải được điền đúng cho mọi kho đang hoạt động — kho thiếu
  `city_code` sẽ không bao giờ được ưu tiên.

## Verification

- Logic chọn kho nằm tại
  `routing/` (legacy code, removed);
  `WarehouseSelector` xử lý cả trường hợp `city_code` là `null`.
- Partial index `idx_physical_inventory_sku_available` phục vụ bước sinh ứng viên.
- Cần bổ sung: test chứng minh tie-break ổn định qua nhiều lần gọi, và một phép đo phân
  bố thực tế của kho được chọn để biết mức độ thiên lệch. Chưa được ghi nhận.
