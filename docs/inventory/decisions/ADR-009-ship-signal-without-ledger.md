---
doc_id: inventory-adr-009
service: inventory
status: accepted
date: 2026-09-05
decision_owners:
  - Inventory Service team
  - Order Service team
supersedes: null
evidence:
  - java/services/inventory-service/src/main/java/com/platform/inventoryservice/controller/ShipToCustomerController.java
  - java/services/inventory-service/src/main/resources/db/migration/V1__init_schema.sql
---

# ADR-009 — Xác nhận xuất kho là signal, không phải biến động tồn kho

## Status

`Accepted`

## Context

Sau khi Shipping Service bị loại bỏ, không còn ai báo cho Order biết hàng đã rời kho.
Order cần tín hiệu đó để chuyển trạng thái item từ đang xử lý sang đã giao.

Nguồn tín hiệu tự nhiên là Inventory: kho là nơi hàng thực sự rời đi, và admin kho là
người biết điều đó. Nhưng đặt endpoint này ở Inventory đặt ra một câu hỏi: hành động
"xuất hàng cho khách" có phải một biến động tồn kho không?

Câu trả lời là **không**. Tồn kho đã bị trừ ở bước deduct, ngay khi thanh toán thành
công. Từ thời điểm đó, hàng không còn thuộc về kho về mặt sổ sách — nó chỉ còn nằm ở đó
về mặt vật lý. Việc nó rời khỏi kệ không làm đổi con số nào.

## Decision

**Endpoint xác nhận xuất kho chỉ ghi một dòng outbox. Không mutate tồn kho, không ghi
ledger, không đổi trạng thái reservation.**

Cụ thể:

- `POST /v1/inventory/ship-to-customer` kiểm tra có reservation `DEDUCTED` tại đúng
  `(order_id, warehouse_id)`, đếm số item bị ảnh hưởng, rồi mở một transaction chỉ chứa
  một `INSERT inventory_outbox`.
- Event `INVENTORY_SHIP_TO_CUSTOMER` mang `order_id`, `warehouse_id`, `shipped_at` —
  và chỉ vậy.
- `courierNote` được nhận nhưng chỉ vào log nội bộ, **không** vào Kafka payload.
- Reservation giữ nguyên `DEDUCTED`. Trạng thái giao hàng thuộc về Order.
- **Với đơn hàng nhiều kho, admin gọi riêng cho từng kho**, và điều kiện lọc
  `warehouse_id` là bắt buộc ở cả ba chỗ: lúc kiểm tra, lúc đếm, và ở consumer bên Order.

## Alternatives considered

**Ghi một dòng ledger với `reference_type` mới, ví dụ `ORDER_SHIPPED`.** Cho một dấu
vết audit đầy đủ hơn. Nhưng ledger được định nghĩa là sổ ghi biến động `actual_quantity`,
và dòng này sẽ có `quantity_change = 0` mãi mãi — làm loãng ý nghĩa của cả bảng, và làm
mọi truy vấn tổng hợp phải thêm điều kiện loại trừ. Không chọn.

**Đổi trạng thái reservation sang một trạng thái mới, ví dụ `SHIPPED`.** Trông gọn.
Nhưng trạng thái reservation mô tả quan hệ giữa đơn hàng và tồn kho, còn giao hàng là
một chiều khác. Trộn hai thứ vào một enum sẽ khiến câu hỏi "hàng này còn được giữ chỗ
không" và "hàng này đã giao chưa" phải trả lời bằng cùng một trường.

**Đặt endpoint ở Order thay vì Inventory.** Order sở hữu trạng thái item nên không cần
event nào cả. Nhưng người bấm nút là admin kho, và Order không biết reservation nào
thuộc kho nào — nó sẽ phải hỏi ngược Inventory, tức là vẫn cần một lời gọi liên service,
chỉ đảo chiều.

**Bỏ `warehouse_id` khỏi payload cho gọn.** Order có thể tự tra reservation theo
`order_id`. Nhưng khi đó Order không phân biệt được item của kho nào, và một lần xác
nhận xuất kho sẽ đánh dấu đã giao cho cả hàng còn nằm trong kho khác. Đây là lỗi nghiệp
vụ nghiêm trọng nhất mà quyết định này ngăn.

## Consequences

**Tích cực:**

- Ý nghĩa của `inventory_ledger` giữ nguyên: mỗi dòng là một biến động tồn kho thật.
- Endpoint rất nhẹ — một câu insert, không lock, không mutate Redis.
- Ranh giới rõ ràng: Inventory nói "hàng đã rời kho", Order quyết định điều đó nghĩa là gì.
- Payload tối thiểu nghĩa là ít thứ có thể lệch giữa hai service.

**Tiêu cực:**

- **Không có dấu vết audit trong Inventory cho hành động này.** Ai xác nhận, lúc nào,
  chỉ nằm ở log ứng dụng — thứ có retention ngắn hơn database rất nhiều. Đây là khoảng
  trống audit đã biết và được chấp nhận có ý thức.
- Endpoint không idempotent ở phía Inventory: mỗi lần gọi sinh một event. Việc chống
  trùng bị đẩy sang consumer, tức là tính đúng đắn phụ thuộc vào Order làm đúng.
- Với đơn nhiều kho, admin phải nhớ gọi đủ số lần. Không có gì trong hệ thống nhắc họ,
  và không có gì phát hiện được một kho bị bỏ sót.
- "Đơn hàng tồn tại" chỉ có nghĩa là "có reservation mang `order_id` này" — Inventory
  không sở hữu bảng đơn hàng nên không thể kiểm tra chặt hơn.

## Migration impact

- **Code:** không thêm `reference_type` mới vào ledger; không thêm trạng thái mới vào
  reservation.
- **Contract:** Order phải lọc theo cả `order_id` và `warehouse_id` khi xử lý event, và
  phải dedupe theo `event_id`.
- **Vận hành:** cần một cách phát hiện đơn nhiều kho mới được xác nhận một phần; hiện
  chưa có.

## Verification

- `ck_inventory_ledger_reference_type_enum` trong
  [migration V1](../../../java/services/inventory-service/src/main/resources/db/migration/V1__init_schema.sql)
  không chứa giá trị nào cho việc xuất kho, nên ghi ledger cho hành động này là bất khả
  thi ở tầng database — ràng buộc bảo vệ chính quyết định này.
- `ck_inventory_outbox_event_type_enum` cho phép `INVENTORY_SHIP_TO_CUSTOMER`.
- Cần bổ sung: test chứng minh với đơn hai kho, xác nhận xuất kho ở kho A không làm
  đổi item ở kho B; và test chứng minh `courierNote` không xuất hiện trong payload
  Kafka. Chưa được ghi nhận.
