---
doc_id: payment-adr-010
service: payment
status: accepted
date: null
decision_owners:
  - Architecture Team
supersedes: null
evidence:
  - docs/payment/interfaces.md
  - docs/payment/workflows.md
---

# ADR-010 — Refund chỉ được khởi tạo bởi explicit command từ Order, không suy luận từ event

## Trạng thái

`Accepted` trong thiết kế (tài liệu Events cũ ghi là "Contract mới"); thay thế
hành vi auto-refund từ `ORDER_CANCELLED`. Ngày không được ghi lại. Chưa xác minh
implementation.

## Bối cảnh

Payment consume `ORDER_CANCELLED` từ `t.order.events`. Nếu payment đã `SUCCESS`
khi order bị hủy, một thiết kế có thể tự động refund từ event này. Tuy nhiên Order
Service có refund lane riêng (`OrderRefundWorkflow`) chờ Inventory xác nhận đủ
hàng hoàn trước khi hoàn tiền; auto-refund từ event sẽ bỏ qua gate đó và tạo hai
đường refund.

## Quyết định

- Refund chỉ được khởi tạo bởi **explicit refund request** từ `OrderRefundWorkflow`
  (`POST /v1/payments/{transactionId}/refund` kèm `Idempotency-Key`), gọi **sau
  khi** Order đã nhận xác nhận từ Inventory rằng toàn bộ item trong refund scope
  đã về kho. Không suy luận refund từ `ORDER_CANCELLED` hay bất kỳ shipping event
  nào.
- Consumer `payment-order-consumer` xử lý `ORDER_CANCELLED` chỉ cho pre-payment
  lane (`previous_status = PENDING_PAYMENT`): payment `PENDING`/`PROCESSING` →
  cancel; `SUCCESS` → **không** auto-refund, ghi reconciliation marker để Order
  kiểm tra lane refund riêng; `EXPIRED`/`CANCELLED`/`FAILED` → ignore; event với
  `previous_status` khác → ignore + cảnh báo reconciliation.
- Request refund mang `inventory_return_confirmed` làm evidence của gate.

## Phương án đã cân nhắc

- **Auto-refund từ `ORDER_CANCELLED` khi payment `SUCCESS`:** đơn giản nhưng bỏ
  qua Inventory gate, có thể hoàn tiền khi hàng chưa về kho và tạo hai đường
  refund. Không chọn.
- **Explicit command duy nhất từ Order:** một owner cho quyết định refund, Payment
  chỉ thực thi và giữ invariant tài chính. Được chọn.

## Hệ quả

Tích cực: boundary rõ (Order quyết định, Payment thực thi); một refund transaction
trên mỗi order theo thiết kế Order; không hoàn tiền trước khi nhận hàng. Tiêu
cực: order đã `SUCCESS` bị hủy cần Order chủ động đi refund lane; reconciliation
marker phải được theo dõi để không bỏ sót.

## Ảnh hưởng migration

Consumer `ORDER_CANCELLED` không gọi refund; cần cơ chế lưu/cảnh báo reconciliation
marker (chưa được thiết kế cũ mô tả chi tiết).

## Xác minh

Cần consumer test cho từng trạng thái payment với `ORDER_CANCELLED`, test không
có refund nào được tạo từ event, test refund API yêu cầu `Idempotency-Key` và
`inventory_return_confirmed`.
