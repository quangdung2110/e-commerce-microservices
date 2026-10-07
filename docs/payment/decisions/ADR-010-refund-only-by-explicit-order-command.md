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
hành vi auto-refund suy luận từ Order event. Ngày không được ghi lại. Chưa xác
minh implementation.

## Bối cảnh

Nếu payment đã `SUCCESS` khi order bị hủy, một thiết kế có thể tự động refund từ
một lifecycle event của Order. Tuy nhiên Order Service có refund lane riêng
(`OrderRefundWorkflow`) chờ Inventory xác nhận đủ hàng hoàn trước khi hoàn tiền;
auto-refund từ event sẽ bỏ qua gate đó và tạo hai đường refund. Order Events v1
không đăng ký Payment làm consumer, nên dependency này cũng không có contract.

## Quyết định

- Refund chỉ được khởi tạo bởi **explicit refund request** từ `OrderRefundWorkflow`
  (`POST /v1/payments/{transactionId}/refund` kèm `Idempotency-Key`), gọi **sau
  khi** Order đã nhận xác nhận từ Inventory rằng toàn bộ item trong refund scope
  đã về kho. Không suy luận refund từ Order/shipping event.
- Payment không đăng ký consumer trên `t.order.events`. Pre-capture cancellation
  dùng explicit synchronous cancel command; outcome tài chính được xác nhận bằng
  Payment provider fact.
- Request refund mang `inventory_return_confirmed` làm evidence của gate.

## Phương án đã cân nhắc

- **Auto-refund từ Order event khi payment `SUCCESS`:** đơn giản nhưng bỏ
  qua Inventory gate, có thể hoàn tiền khi hàng chưa về kho và tạo hai đường
  refund. Không chọn.
- **Explicit command duy nhất từ Order:** một owner cho quyết định refund, Payment
  chỉ thực thi và giữ invariant tài chính. Được chọn.

## Hệ quả

Tích cực: boundary rõ (Order quyết định, Payment thực thi); một refund transaction
trên mỗi order theo thiết kế Order; không hoàn tiền trước khi nhận hàng. Tiêu
cực: order đã `SUCCESS` bị hủy cần Order chủ động đi refund lane.

## Ảnh hưởng migration

Loại bỏ consumer Order event, durable inbox và reconciliation marker tương ứng
khỏi thiết kế Payment. Luồng cancel/refund hiện hữu phải dùng đúng HTTP contract.

## Xác minh

Cần test rằng không có refund nào được tạo ngoài explicit command; refund API yêu
cầu `Idempotency-Key` và Inventory evidence. Contract test phải xác nhận Payment
không khai báo Order event consumer.
