---
doc_id: inventory-adr-010
service: inventory
status: accepted
date: 2026-09-05
decision_owners:
  - Inventory Service team
  - Order Service team
supersedes: null
evidence:
  - java/services/inventory-service/src/main/resources/db/migration/V1__init_schema.sql
  - java/services/inventory-service/src/main/resources/db/migration/V3__partial_unique_outbox_return_received.sql
---

# ADR-010 — Refund expectation registry đặt tại Inventory

## Status

`Accepted`

## Context

Khi khách trả hàng, tiền chỉ nên được hoàn sau khi hàng đã thực sự về kho. Với một yêu
cầu hoàn nhiều món, điều kiện là **tất cả** món đã về, không phải món đầu tiên.

Ai nên theo dõi điều kiện đó?

Order biết yêu cầu hoàn gồm những món nào, nhưng không biết món nào đã về kho — thông
tin đó nằm ở Inventory. Inventory biết món nào vừa được nhận về, nhưng không biết yêu
cầu hoàn gồm những món nào — thông tin đó nằm ở Order.

Trước khi Shipping Service bị loại bỏ, tín hiệu "hàng đã hoàn" đến từ event của
Shipping. Không còn service đó, tín hiệu phải đến từ nơi khác.

## Decision

**Order đăng ký trước phạm vi hoàn hàng với Inventory. Inventory theo dõi tiến độ và
phát đúng một signal khi đã nhận đủ.**

Cụ thể:

- Order gọi `POST /v1/inventory/returns/refund-expectations` với `orderId`,
  `refundRequestId` và danh sách `orderItemIds`.
- Inventory đánh dấu `refund_expected = true` và `refund_request_id` trên các
  reservation `DEDUCTED` tương ứng.
- Khi admin nhận hàng về qua restock với `ORDER_RETURNED_RESTOCK`, Inventory điền
  `refund_returned_at` cho reservation đó và đếm số món còn thiếu **trong cùng transaction**.
- Đếm được 0 → ghi outbox `INVENTORY_RETURN_RECEIVED_ALL`, cũng trong transaction đó.
- Restock hàng hoàn cho món **chưa** được đăng ký bị từ chối.
- Đăng ký lại cùng khóa với tập món khác bị từ chối thay vì ghi đè.

## Alternatives considered

**Order tự hỏi Inventory định kỳ.** Order giữ toàn bộ logic, Inventory không cần biết
gì về hoàn tiền. Nhưng tạo một vòng polling và một cửa sổ trễ; và Order vẫn phải hỏi
"món này về chưa" cho từng món.

**Inventory phát event cho từng món về kho, Order tự đếm.** Ranh giới sạch hơn: Inventory
chỉ báo sự kiện, Order quyết định khi nào đủ. Nhưng đẩy một điều kiện đếm phân tán sang
Order, nơi phải xử lý event trùng, event thiếu và event đến sai thứ tự để giữ đúng phép
đếm. Đặt phép đếm cạnh dữ liệu — trong cùng một transaction — cho một câu trả lời chắc
chắn hơn nhiều.

**Một bảng riêng cho refund scope.** Tách bạch hơn về mô hình. Nhưng phép đếm khi đó
trải trên hai bảng, và các cột hiện tại trên reservation đã đủ diễn đạt. Chưa tương
xứng với chi phí thêm một bảng.

**Cho phép đăng ký lại và ghi đè tập món.** Linh hoạt hơn khi Order đổi ý. Nhưng nếu
phạm vi thay đổi sau khi hàng đã bắt đầu về, điều kiện "đã đủ" dịch chuyển và có thể
làm signal phát sớm hoặc không bao giờ phát. Từ chối là lựa chọn an toàn hơn.

## Consequences

**Tích cực:**

- Điều kiện "đã nhận đủ" được đánh giá trong cùng transaction với dữ liệu quyết định
  nó — không có cửa sổ trễ, không có phép đếm phân tán.
- Signal phát đúng một lần, được bảo đảm bởi partial unique index chứ không phải bởi
  câu lệnh điều kiện.
- Restock hàng hoàn không đăng ký trước bị chặn, nên không có hàng "về kho" ngoài mọi
  phạm vi hoàn nào.
- Order chỉ cần xử lý một signal thay vì đếm nhiều event.

**Tiêu cực:**

- Inventory phải biết về khái niệm hoàn tiền — một khái niệm thuộc miền của Order. Đây
  là sự rò rỉ boundary có ý thức, đánh đổi lấy tính đúng đắn của phép đếm.
- Không có API hủy đăng ký. Order đăng ký nhầm phạm vi thì phải sửa dữ liệu bằng tay
  trước khi hàng bắt đầu về.
- Hai admin cùng nhận hai món cuối có thể cùng thấy số món còn thiếu bằng 0. Câu lệnh
  điều kiện **không** ngăn được điều đó; unique index mới ngăn, và transaction thua sẽ
  rollback rồi cần retry ở tầng ứng dụng.
- Restock hàng hoàn không idempotent về mặt tồn kho: gọi lại làm tồn kho cộng hai lần.
  `refund_returned_at` chỉ được đặt khi còn trống nên phép đếm không sai, nhưng con số
  tồn kho thì sai.
- Signal đã phát không thu hồi được; Order phải tự bảo vệ bằng dedupe.

## Migration impact

- **Data:** ba cột `refund_expected`, `refund_request_id`, `refund_returned_at` trên
  `inventory_reservations`, cộng một partial index cho phép đếm và một partial unique
  index trên outbox.
- **Contract:** Order phải gọi đăng ký **trước** khi admin nhận bất kỳ món nào; thứ tự
  này là bắt buộc, không phải khuyến nghị.
- **Vận hành:** cần theo dõi các phạm vi hoàn đã đăng ký nhưng treo quá lâu — không có
  gì tự đóng chúng.

## Verification

- Ba cột refund và `idx_inventory_reservations_refund_completion` nằm trong
  [migration V1](../../../java/services/inventory-service/src/main/resources/db/migration/V1__init_schema.sql).
- `uq_inventory_outbox_return_received` trong
  [migration V3](../../../java/services/inventory-service/src/main/resources/db/migration/V3__partial_unique_outbox_return_received.sql)
  có comment ghi rõ nó tồn tại để chặn race khi hai transaction cùng thấy phép đếm bằng 0.
- Cần bổ sung: test hai transaction đồng thời nhận hai món cuối, chứng minh chỉ một
  signal được ghi; và test đăng ký lại với tập món khác bị từ chối. Chưa được ghi nhận.
