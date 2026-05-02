# Order Service - State Machine & Lifecycle

**Liên kết nhanh (Related Documents):**
*   [Database Schema](order_db_design.md)
*   [Events Structure](order_events.md)
*   [Workflows](order_workflow.md)
*   [Workers](order_workers.md)
*   [Temporal Saga Design](temporal_saga_design.md)

---

Tài liệu này mô tả state machine của `orders` và `order_items` ở mức chuẩn hóa để các worker và events dùng chung một ngôn ngữ trạng thái.

## 1. Master Order FSM

Các trạng thái chính:
- `CREATED` (transient nội bộ ngay sau khi insert, trước khi chuyển `PENDING_PAYMENT`)
- `PENDING_PAYMENT`
- `PAID`
- `PROCESSING`
- `COMPLETED`
- `CANCELLED`
- `RETURN_REQUESTED`
- `REFUNDED`

## 2. Order Item Shipping FSM (Bảng order_items)

Các trạng thái chính của item:
- `PREPARING`
- `PACKAGED`
- `HANDED_OVER`
- `IN_TRANSIT`
- `DELIVERY_FAILED`
- `DELIVERED`
- `RETURNED`

## 22. Bảng Chi Tiết Transitions

| Transition | From | To | Trigger |
|---|---|---|---|
| T1 | `CREATED` | `PENDING_PAYMENT` | `CREATE_ORDER_ACTIVITY_COMMITTED` |
| T2 | `PENDING_PAYMENT` | `PAID` | `PAYMENT_SUCCESS` |
| T3 | `PENDING_PAYMENT` | `CANCELLED` | `PAYMENT_TIMEOUT` |
| T3.1 | `CANCELLED` | `PAID` | `PAYMENT_SUCCESS_LATE_RECOVERY` (chỉ khi `cancel_reason = PAYMENT_TIMEOUT`) |
| T4 | `PENDING_PAYMENT` | `CANCELLED` | `USER_CANCEL` |
| T4.1 | `PENDING_PAYMENT` | `CANCELLED` | `ADMIN_FORCE_CANCEL` |
| T7 | `PAID` | `RETURN_REQUESTED` | `ADMIN_FORCE_REFUND_REQUESTED` |
| T8 | `PAID` | `PROCESSING` | `POST_PAYMENT_SAGA_COMPLETED` |
| T8.1 | `PROCESSING` | `PAID` | `POST_PAYMENT_COMPENSATION_ROLLBACK` |
| T8.2 | `PROCESSING` | `RETURN_REQUESTED` | `ADMIN_FORCE_REFUND_REQUESTED` |
| T8.3 | `PROCESSING` | `COMPLETED` | `AUTO_COMPLETE_ALL_ITEMS_DELIVERED` |
| P1 | `PREPARING` | `PACKAGED` | `WAREHOUSE_PACKED` |
| P2 | `PACKAGED` | `HANDED_OVER` | `COURIER_PICKED_UP` |
| P3 | `HANDED_OVER` | `IN_TRANSIT` | `SHIPMENT_STATUS_UPDATED(IN_TRANSIT)` |
| P4 | `IN_TRANSIT` | `DELIVERED` | `SHIPMENT_DELIVERED` |
| P5 | `IN_TRANSIT` | `DELIVERY_FAILED` | `SHIPMENT_STATUS_UPDATED(DELIVERY_FAILED)` |
| P6 | `DELIVERY_FAILED` | `IN_TRANSIT` | `SHIPMENT_STATUS_UPDATED(RETRY_IN_TRANSIT)` |
| P7 | `DELIVERY_FAILED` | `RETURNED` | `SHIPMENT_RETURNED` |
| P8 | `DELIVERED` | `RETURNED` | `SHIPMENT_RETURNED` |
| T9 | `COMPLETED` | `RETURN_REQUESTED` | `RETURN_API_ACCEPTED` (pass customer one-shot guard + precondition tất cả order item đã `DELIVERED`) |
| T10 | `RETURN_REQUESTED` | `REFUNDED` | `REFUND_SUCCESS` |
| T10.1 | `RETURN_REQUESTED` | `RETURN_REQUESTED` | `REFUND_PROCESSING`, `REFUND_FAILED` hoặc `REFUND_REVERSED` (giữ nguyên FSM lõi, chỉ cập nhật `payment_status`) |

> [!IMPORTANT]
> **Ownership transition cho mốc thanh toán/vận hành:**
> - T2 (`PENDING_PAYMENT` -> `PAID`) được commit trong Temporal workflow branch `PAYMENT_SUCCESS`.
> - Kafka `order-payment-consumer` chỉ làm nhiệm vụ signal workflow, không ghi trực tiếp `orders.status` cho T2.
> - T3.1 (`CANCELLED` -> `PAID`) chỉ được commit bởi late-payment recovery lane khi có bằng chứng payment success đến muộn sau timeout.
> - T8 (`PAID` -> `PROCESSING`) được commit bởi `UpdateOrderStatusActivity` trong post-payment saga và phát `ORDER_STATUS_CHANGED`.
> - T8.3 (`PROCESSING` -> `COMPLETED`) phát canonical event `ORDER_COMPLETED`.
> - Admin force action với đơn đã thu tiền (`PAID`/`PROCESSING`) phải đi lane refund (`RETURN_REQUESTED`), không đi lane cancel pre-payment.
> - Trigger `RETURN_API_ACCEPTED` chỉ hợp lệ khi Buyer API chưa dùng one-shot guard của order và toàn bộ order item đã `DELIVERED`.
> - Event `SHIPMENT_RETURNED` chỉ cập nhật Order Item FSM (`DELIVERED/DELIVERY_FAILED` -> `RETURNED`), không được dùng để mở `OrderRefundWorkflow`.
> - Theo policy B, mỗi order chỉ có một refund transaction do Order khởi tạo từ `API_RETURN` hoặc `ADMIN_FORCE_REFUND_REQUESTED`.

## 22.1 Guard cho Buyer API One-shot

Ràng buộc one-shot chỉ áp cho nguồn trigger từ Buyer API (`POST /v1/orders/{orderId}/return`):

| Nguồn trigger | Có áp one-shot guard | Hành vi khi đã dùng guard |
|---|---|---|
| `RETURN_API_ACCEPTED` | Có | Từ chối request mới với `REFUND_CUSTOMER_REQUEST_ALREADY_USED` |
| `SHIPMENT_RETURNED` | Không | Chỉ cập nhật item status, không mở workflow refund |
| `ADMIN_FORCE_REFUND_REQUESTED` | Không | Vẫn cho phép vào lane refund theo quyền admin |

Request Buyer chỉ được chấp nhận khi toàn bộ item của order đã `DELIVERED`. Sau khi guard đã được dùng, mọi request Buyer tiếp theo cho cùng order đều bị từ chối.

> **Refund invariant:** `sum(refund_transaction)` do Order khởi tạo cho mỗi `order_id` tối đa một lần; event `SHIPMENT_RETURNED` không tạo refund transaction.

## 23. Mapping với Shipping Service (standardized_status)

| Shipping Event | Item Status |
|---|---|
| `SHIPMENT_STATUS_UPDATED` (`PREPARING`/`PACKAGED`/`HANDED_OVER`/`IN_TRANSIT`/`DELIVERY_FAILED`) | Map 1-1 theo standardized_status |
| `SHIPMENT_DELIVERED` | `DELIVERED` |
| `SHIPMENT_RETURNED` | `RETURNED` |
| `SHIPMENT_DELAYED` | Không đổi item status, chỉ cập nhật metadata SLA/escalation |

## 24. Buyer Tab Precedence (platformee-style)

Để tránh 1 order xuất hiện đồng thời ở nhiều tab, projector phải derive duy nhất `buyer_tab` theo thứ tự ưu tiên sau:

| Ưu tiên | Điều kiện | buyer_tab |
|---|---|---|
| 1 | `orders.status = PENDING_PAYMENT` | `TO_PAY` |
| 2 | `orders.status in (PAID, PROCESSING)` và **chưa có** item ở nhóm đang giao (`HANDED_OVER`, `IN_TRANSIT`, `DELIVERY_FAILED`) | `TO_SHIP` |
| 3 | `orders.status in (PAID, PROCESSING)` và **có ít nhất 1** item ở nhóm đang giao (`HANDED_OVER`, `IN_TRANSIT`, `DELIVERY_FAILED`) | `TO_RECEIVE` |
| 4 | `orders.status = COMPLETED` và không ở return/refund flow | `COMPLETED` |
| 5 | `orders.status = CANCELLED` | `CANCELLED` |
| 6 | `orders.status in (RETURN_REQUESTED, REFUNDED)` hoặc `orders.payment_status in (REFUNDING, REFUNDED, REFUND_FAILED, REFUND_REVERSED)` | `RETURN_REFUND` |

Nếu có nhiều điều kiện cùng đúng, luôn chọn tab có ưu tiên cao hơn trong bảng.

## 31. Auto-Complete Logic: PROCESSING -> COMPLETED

Master order tự chuyển `PROCESSING -> COMPLETED` khi tất cả items đã `DELIVERED`.

Ràng buộc:
- Logic chạy idempotent bằng CAS update trên `orders.status`:
	```sql
	UPDATE orders
	SET status = 'COMPLETED', version = version + 1, updated_at = NOW()
	WHERE id = :order_id AND status = 'PROCESSING';
	```
- Chỉ khi update thành công (affected rows = 1) mới được phát `ORDER_COMPLETED`.
- Không chuyển `COMPLETED` nếu còn item chưa kết thúc.
- Coalescing window 5 giây để tránh rung trạng thái khi webhook shipping đến sát nhau.

## 32. Mapping FSM sang CQRS Read Model

Để đồng bộ với [CQRS & Elasticsearch](cqrs_elasticsearch_design.md), projector phải map trạng thái như sau:

| Nguồn FSM | Field trong `orders_read` | Field trong `order_search_lines` |
|---|---|---|
| `orders.status` | `order_status` | `order_status` |
| `orders.payment_status` | `payment_status` | `payment_status` |
| Tập trạng thái từ `order_items.status` | `item_statuses` (keyword array) | `item_status` (theo từng line gắn `order_item_id`) |
| Kết quả bảng precedence mục 24 | `buyer_tab` | `buyer_tab` |
| Trạng thái ưu tiên hiển thị card | `ui_primary_status`, `ui_status_rank` | `ui_status_rank` |

Guard keys áp dụng khi projection:

| Stream ownership | Guard key | Mục đích |
|---|---|---|
| Order lifecycle | `order_version` | Chặn update cũ ghi đè trạng thái mới |
| Item/shipping | `item_version`, `shipment_event_sequence_id` | Chặn out-of-order ở cấp item |
| Payment | `payment_event_sequence_id` | Tách ownership khỏi order lifecycle |
| Refund | `refund_event_sequence_id` | Tránh stale refund update |

> Khi có xung đột giữa timestamp và sequence/version, projector luôn ưu tiên sequence/version để quyết định mới-cũ.
