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

## 2. Order Item Delivery FSM (Bảng order_items)

Các trạng thái chính của item (đã đơn giản hóa từ 7 xuống 3 trạng thái tại Phase 14):
- `PROCESSING`
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
| I1 | `PROCESSING` | `DELIVERED` | `INVENTORY_SHIP_TO_CUSTOMER` (admin-trigger) |
| I2 | `DELIVERED` | `RETURNED` | `RETURN_API_ACCEPTED` (Buyer API) |
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
> - I1 (`PROCESSING` -> `DELIVERED`) được trigger bởi event `INVENTORY_SHIP_TO_CUSTOMER` do admin gọi API sau khi xuất hàng từ kho.
> - Admin force action với đơn đã thu tiền (`PAID`/`PROCESSING`) phải đi lane refund (`RETURN_REQUESTED`), không đi lane cancel pre-payment.
> - Trigger `RETURN_API_ACCEPTED` chỉ hợp lệ khi Buyer API chưa dùng one-shot guard của order và toàn bộ order item đã `DELIVERED`.
> - Theo policy B, mỗi order chỉ có một refund transaction do Order khởi tạo từ `API_RETURN` hoặc `ADMIN_FORCE_REFUND_REQUESTED`.

## 22.1 Guard cho Buyer API One-shot

Ràng buộc one-shot chỉ áp cho nguồn trigger từ Buyer API (`POST /v1/orders/{orderId}/return`):

| Nguồn trigger | Có áp one-shot guard | Hành vi khi đã dùng guard |
|---|---|---|
| `RETURN_API_ACCEPTED` | Có | Từ chối request mới với `REFUND_CUSTOMER_REQUEST_ALREADY_USED` |
| `ADMIN_FORCE_REFUND_REQUESTED` | Không | Vẫn cho phép vào lane refund theo quyền admin |

Request Buyer chỉ được chấp nhận khi toàn bộ item của order đã `DELIVERED`. Sau khi guard đã được dùng, mọi request Buyer tiếp theo cho cùng order đều bị từ chối.

> **Refund invariant:** `sum(refund_transaction)` do Order khởi tạo cho mỗi `order_id` tối đa một lần; refund chỉ được mở từ `API_RETURN` hoặc `ADMIN_FORCE_REFUND_REQUESTED`.

## 24. Buyer Tab Precedence (platformee-style)

Để tránh 1 order xuất hiện đồng thời ở nhiều tab, projector phải derive duy nhất `buyer_tab` theo thứ tự ưu tiên sau:

| Ưu tiên | Điều kiện | buyer_tab |
|---|---|---|
| 1 | `orders.status = PENDING_PAYMENT` | `TO_PAY` |
| 2 | `orders.status in (PAID, PROCESSING)` và **chưa có** item ở trạng thái `DELIVERED` | `TO_SHIP` |
| 3 | `orders.status in (PAID, PROCESSING)` và **có ít nhất 1** item ở trạng thái `DELIVERED` | `TO_RECEIVE` |
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
- Coalescing window 5 giây để tránh rung trạng thái khi nhiều `INVENTORY_SHIP_TO_CUSTOMER` đến sát nhau.

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
| Item/delivery | `item_version` | Chặn out-of-order ở cấp item |
| Payment | `payment_event_sequence_id` | Tách ownership khỏi order lifecycle |
| Refund | `refund_event_sequence_id` | Tránh stale refund update |

> Khi có xung đột giữa timestamp và sequence/version, projector luôn ưu tiên sequence/version để quyết định mới-cũ.