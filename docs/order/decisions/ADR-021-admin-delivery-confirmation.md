---
doc_id: order-adr-021
service: order
status: accepted
date: 2026-10-11
decision_owners:
  - Order Service team
supersedes: null
refines:
  - ADR-010
  - ADR-011
  - ADR-015
  - ADR-020
evidence:
  - contracts/http/order/v1/openapi.yaml
  - contracts/events/order/v1/asyncapi.yaml
  - java/services/order-service/src/main/java/com/ecommerce/order/service/DeliveryConfirmationService.java
  - java/services/order-service/src/main/resources/db/migration/V2__admin_delivery_confirmation.sql
  - java/services/order-service/src/test/java/com/ecommerce/order/integration/CheckoutFlowIT.java
  - frontend/src/components/admin/orders
---

# ADR-021 — Admin xác nhận giao hàng thay cho Delivery service

## Trạng thái

`Accepted` — 2026-10-11. Thay nguồn của fact "đã giao" mà ADR-011/ADR-015/ADR-020 giả định là một
Delivery service. Mọi quy tắc khác của các ADR đó (physical dispatch không phải giao hàng, CAS theo
line, `TryCompleteOrder`, Buyer return chỉ khi mọi line đã giao) giữ nguyên.

## Bối cảnh

Order chỉ chuyển `PROCESSING -> COMPLETED` (T8.3, `INV-ORD-006`) khi mọi line `DELIVERED`, và Buyer
return (`INV-ORD-014`) cần mọi line `DELIVERED`. Thiết kế cũ để một Delivery service phát
`com.ecommerce.delivery.order-line.delivered.v1`. Service đó chưa từng được viết, README của nó
đã bị xoá và không có service nào khác phát "đã giao": Inventory chỉ phát
`reservation.line-dispatched` (hàng đã rời kho) và tài liệu Inventory ghi rõ "Order sở hữu
shipment/delivery state". Hệ quả là không đơn nào lên `COMPLETED`: không có review, không có thông
báo `ORDER_COMPLETED`, Buyer không trả hàng/hoàn tiền được (audit O3).

## Quyết định

1. **Không có Delivery service.** Xoá contract `contracts/events/delivery/v1`, topic
   `t.delivery.order.events.v1`, consumer group `order-delivery-consumer` và mọi chỗ tham chiếu.
2. **Order sở hữu delivery state và nhận lời xác nhận từ Admin.** Thêm
   `POST /api/v1/admin/orders/{order_id}/delivery-confirmations` (ADMIN). Body
   `lines[] {order_line_id, delivered_at, delivery_reference}`, 1–100 line duy nhất.
3. **Inventory không đổi.** Physical dispatch vẫn chỉ đưa line `PROCESSING -> DISPATCHED`
   (`handover_reference`, `handed_over_at`). Chỉ line `DISPATCHED` mới xác nhận được; line còn
   `PROCESSING` trả `409 DELIVERY_REQUIRES_DISPATCH`.
4. **All-or-nothing, đồng bộ, dưới khoá order.** Một transaction: khoá order và các line, kiểm tra
   toàn bộ, CAS `DISPATCHED -> DELIVERED` từng line, tính lại `order_items.status`, rồi chạy
   `TryCompleteOrder` ngay trong transaction đó. Xác nhận của line cuối chuyển master
   `PROCESSING -> COMPLETED` và ghi `order.completed.v1` + `ORDER_COMPLETED` cùng transaction.
   Không dùng Temporal, không có operation resource; trả `200`.
5. **Idempotent theo trạng thái line, không dùng `Idempotency-Key`.** Lặp lại cùng line với cùng
   `delivery_reference` và `delivered_at` là `200` no-op; khác thì `409 DELIVERY_ALREADY_CONFIRMED`.
   Replay sau khi order đã `COMPLETED` không phát thêm fact.
6. **Ràng buộc thời gian.** `delivered_at` không được trước `dispatched_at` của line và không quá
   `order.fulfillment.delivered-at-future-skew` (mặc định 5 phút) sau giờ máy chủ; vi phạm là
   `422 INVALID_DELIVERY_CONFIRMATION`, cũng là mã của line không thuộc order hoặc trùng.
7. **Dấu vết.** Lưu `delivery_reference` (mã vận đơn/biên nhận, nhiều line cùng kiện có thể dùng
   chung nên không unique), `delivered_at` và `delivery_confirmed_by` (UUID Admin từ `X-User-Id`).
   Đây là lời chứng của Admin, không phải bằng chứng xác thực từ bên thứ ba.
8. **Cảnh báo, không tự hoàn tất.** Gauge `order.fulfillment.delivery_confirmation_overdue` đếm line
   `DISPATCHED` đã quá `order.fulfillment.delivery-confirmation-overdue` (mặc định 7 ngày). Hệ thống
   không bao giờ tự đặt `DELIVERED` theo thời gian.
9. **Trạng thái đơn được phép xác nhận:** `PAID`, `PROCESSING`, `FINANCIAL_RECONCILIATION_REQUIRED`
   (line `DISPATCHED` có thể tồn tại trong lúc financial repair chưa xong; convergence hoàn tất đơn khi
   về `PROCESSING`). Trạng thái khác trả `409 INVALID_ORDER_TRANSITION`, trừ replay thuần.

## Phương án đã cân nhắc

- **Physical dispatch của Inventory = đã giao.** Ít việc nhất, nhưng Buyer trả hàng/đánh giá được
  ngay khi kho xuất hàng và xoá ranh giới Inventory ↔ Order đã chốt (Inventory ADR-012). Không chọn.
- **Tự động `DELIVERED` sau N ngày.** Không cần ai thao tác nhưng là suy đoán theo thời gian, có thể
  cho review/trả hàng trước khi khách nhận hàng. Không chọn; có thể thêm sau như phương án dự phòng
  bằng một ADR mới.
- **Khôi phục Delivery service.** Cần thêm một service chỉ để phát một fact; ngoài phạm vi.
- **Inventory thêm lệnh xác nhận giao.** Đảo quyết định "Inventory không sở hữu delivery state".

## Hệ quả

- Tích cực: có đường tới `COMPLETED` mà không thêm service; ranh giới Inventory/Order giữ nguyên; bỏ
  logic "proof tới trước dispatch" (không thể xảy ra vì chỉ xác nhận được line đã `DISPATCHED`) nên bỏ
  trạng thái inbox `PENDING_PREREQUISITE`.
- Tiêu cực: thêm một thao tác thủ công cho mỗi lô hàng; đơn kẹt ở `PROCESSING` nếu Admin quên bấm
  (có metric/alert); tính xác thực chỉ ở mức Admin.
- `INV-ORD-006`, T8.3 và I2 đổi nguồn từ "verified Delivery fact" sang "Admin delivery confirmation".
  `INV-ORD-033` (target đã closed) và `INV-ORD-030` (hội tụ) giữ nguyên.

## Ảnh hưởng migration

- `V2__admin_delivery_confirmation.sql`: `order_item_lines.delivery_proof_id` đổi tên
  `delivery_reference` (bỏ UNIQUE), bỏ `delivery_proof_sha256` và `delivery_event_id`, thêm
  `delivery_confirmed_by`, CHECK `DELIVERED` ⇔ có `delivered_at` + `delivery_reference`, index
  một phần cho line `DISPATCHED`; inbox bỏ trạng thái `PENDING_PREREQUISITE` (hàng cũ chuyển
  `REJECTED/DELIVERY_SOURCE_REMOVED`). Chưa có đơn nào từng `DELIVERED` nên không cần backfill.
- Contract: Order HTTP `1.1.0` (additive), Order Events `1.1.0` (bỏ channel/operation Delivery); xoá
  `contracts/events/delivery/v1`. Không có consumer hay provider thật nên không áp dụng chính sách
  breaking-change.
- Cấu hình: bỏ `order.kafka.delivery-topic/group`; thêm `order.fulfillment.*`.
- Frontend: nút "Xác nhận đã giao" ở chi tiết đơn của Admin.

## Xác minh

- `CheckoutFlowIT.adminDeliveryConfirmationNeedsDispatchAndCompletesOrderOnLastLine`: từ chối khi chưa
  dispatch, dispatch không tự giao, 403/400/422/404, all-or-nothing, replay/conflict, chỉ line cuối
  mới `COMPLETED` và phát đúng một `order.completed.v1` + `ORDER_COMPLETED`, response khớp schema.
- `CheckoutFlowIT.dispatchedButUnconfirmedLinesAreCountedAsOverdue`: gauge cảnh báo.
- `RefundFlowIT`: Buyer return chỉ sau khi mọi line đã được xác nhận.
