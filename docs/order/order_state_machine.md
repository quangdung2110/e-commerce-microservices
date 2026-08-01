# Order Service - State Machine & Lifecycle

> **Phase 22 (Simplify Idempotency Schema):** One-shot guard Buyer return giờ là cột `orders.buyer_return_locked_at` (CAS row-level lock) thay cho bảng `order_request_idempotency`. Xem [CHANGELOG Phase 22](../_contracts/CHANGELOG.md#phase-22--simplify-idempotency-schema-order) và [DB Design §1.1](order_db_design.md#11-orders-master-order).

**Liên kết nhanh (Related Documents):**
*   [Database Schema](order_db_design.md)
*   [Events Structure](order_events.md)
*   [Workflows](order_workflow.md)
*   [Workers](order_workers.md)
*   [Temporal Saga Design](temporal_saga_design.md)

---

Tài liệu này mô tả state machine của `orders` và `order_items` ở mức chuẩn hóa để các worker và events dùng chung một ngôn ngữ trạng thái.

> **Phase 20 (Event Payload Simplification):** Reason enum trong event `ORDER_STATUS_CHANGED` và `ORDER_CANCELLED` vẫn giữ nguyên tập giá trị (`PAYMENT_TIMEOUT`, `USER_CANCEL`, `ADMIN_FORCE_CANCEL`, `PRE_PAYMENT_SAGA_FAILED`, `PAYMENT_FAILED` cho cancel; `POST_PAYMENT_SAGA_COMPLETE`, `ADMIN_FORCE_REFUND_REQUESTED`, `RETURN_API_ACCEPTED`, `INVENTORY_RETURN_CONFIRMED`, `LATE_PAYMENT_RECOVERED`, `PAYMENT_SUCCESS_LATE_RECOVERY` cho status change). Phase 20 **không thay đổi** enum FSM hay transition — chỉ thu gọn payload event (bỏ field không cần thiết xem [CHANGELOG Phase 20](../_contracts/CHANGELOG.md#phase-20--event-payload-simplification-order--payment--inventory--promotion--cart)).

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

## 3. Bảng Chi Tiết Transitions

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
| T10.1 | `RETURN_REQUESTED` | `RETURN_REQUESTED` | `REFUND_PROCESSING`, `REFUND_FAILED` (gồm cả `REFUND_REVERSED` đã map về `REFUND_FAILED`) (giữ nguyên FSM lõi, chỉ cập nhật `payment_status`) |

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

## 3.1 Guard cho Buyer API One-shot

Ràng buộc one-shot chỉ áp cho nguồn trigger từ Buyer API (`POST /v1/orders/{orderId}/return`):

| Nguồn trigger | Có áp one-shot guard | Hành vi khi đã dùng guard |
|---|---|---|
| `RETURN_API_ACCEPTED` | Có | Từ chối request mới với `REFUND_CUSTOMER_REQUEST_ALREADY_USED` |
| `ADMIN_FORCE_REFUND_REQUESTED` | Không | Vẫn cho phép vào lane refund theo quyền admin |

Request Buyer chỉ được chấp nhận khi toàn bộ item của order đã `DELIVERED`. Sau khi guard đã được dùng, mọi request Buyer tiếp theo cho cùng order đều bị từ chối.

**Cơ chế enforce (Phase 22):**

- Guard được lưu trên cột `orders.buyer_return_locked_at TIMESTAMPTZ NULL`. Cơ chế first-write-wins qua CAS row-level lock:
  ```sql
  UPDATE orders
  SET buyer_return_locked_at = NOW()
  WHERE id = :order_id
    AND buyer_return_locked_at IS NULL
  RETURNING id;
  ```
- API layer tại request đầu tiên: `RETURNING` trả 1 row → guard được set → tiếp tục start `OrderRefundWorkflow`.
- Request Buyer tiếp theo: `RETURNING` không trả row (vì `buyer_return_locked_at IS NOT NULL`) → trả `409 REFUND_CUSTOMER_REQUEST_ALREADY_USED`.
- Guard không bị reset khi refund kết thúc ở `REFUND_FAILED` hoặc `REFUND_REVERSED` (giá trị `buyer_return_locked_at` đã set giữ nguyên vĩnh viễn cùng vòng đời order).
- Trigger admin (`REQUEST_ADMIN_REFUND`) không đụng vào cột này, không bị khóa bởi guard.

> **Refund invariant:** `sum(refund_transaction)` do Order khởi tạo cho mỗi `order_id` tối đa một lần; refund chỉ được mở từ `API_RETURN` hoặc `ADMIN_FORCE_REFUND_REQUESTED`.

## 4. Buyer Tab Precedence (platformee-style)

Để tránh 1 order xuất hiện đồng thời ở nhiều tab, `v_orders_buyer_tab` (PostgreSQL view, xem [Search/Filter Design §2.3](order_search_design.md#23-view-v_orders_buyer_tab)) phải derive duy nhất `buyer_tab` theo thứ tự ưu tiên sau:

| Ưu tiên | Điều kiện | buyer_tab |
|---|---|---|
| 1 | `orders.status = PENDING_PAYMENT` | `TO_PAY` |
| 2 | `orders.status in (PAID, PROCESSING)` và **chưa có** item ở trạng thái `DELIVERED` | `TO_SHIP` |
| 3 | `orders.status in (PAID, PROCESSING)` và **có ít nhất 1** item ở trạng thái `DELIVERED` | `TO_RECEIVE` |
| 4 | `orders.status = COMPLETED` và không ở return/refund flow | `COMPLETED` |
| 5 | `orders.status = CANCELLED` | `CANCELLED` |
| 6 | `orders.status in (RETURN_REQUESTED, REFUNDED)` hoặc `orders.payment_status in (REFUNDING, REFUNDED, REFUND_FAILED)` (gồm cả `REFUND_REVERSED` đã map về `REFUND_FAILED`) | `RETURN_REFUND` |

Nếu có nhiều điều kiện cùng đúng, luôn chọn tab có ưu tiên cao hơn trong bảng.

## 5. Auto-Complete Logic: PROCESSING -> COMPLETED

Master order tự chuyển `PROCESSING -> COMPLETED` khi tất cả items đã `DELIVERED`.

Ràng buộc:
- Logic chạy idempotent trong transaction với row-level lock + count check:
	```sql
	BEGIN;
	-- Đếm items, chỉ proceed khi tất cả DELIVERED
	SELECT COUNT(*) FILTER (WHERE status <> 'DELIVERED') AS not_delivered
	  FROM order_items WHERE order_id = :order_id FOR UPDATE;
	-- Nếu not_delivered = 0:
	UPDATE orders SET status = 'COMPLETED', updated_at = NOW()
	 WHERE id = :order_id AND status = 'PROCESSING';
	COMMIT;
	```
- Chỉ khi update thành công (affected rows = 1) mới được phát `ORDER_COMPLETED`.
- Không chuyển `COMPLETED` nếu còn item chưa kết thúc.
- Coalescing window 5 giây để tránh rung trạng thái khi nhiều `INVENTORY_SHIP_TO_CUSTOMER` đến sát nhau.

## 6. Mapping FSM sang Search/Filter API Response (Phase 15)

Để phục vụ Search/Filter API trực tiếp từ PostgreSQL (xem [Search/Filter Design](order_search_design.md)), API dựng response theo mapping sau:

| Nguồn FSM | Field response (trong `items[]` của `GET /v1/orders/search`) |
|---|---|
| `orders.status` | `order_status` |
| `orders.payment_status` | `payment_status` |
| `orders.created_at`, `paid_at`, `completed_at`, `cancelled_at`, `refunded_at` | các timestamp tương ứng |
| `orders.total_amount`, `total_discount`, `final_amount` | `total_amount`, `total_discount`, `final_amount` |
| Tập `order_items.status` (qua view `v_orders_buyer_tab`) | `buyer_tab` |
| `buyer_tab` (qua `CASE WHEN` trong search query) | `ui_primary_status`, `ui_status_rank` |
| Hardcoded constant `'PLATFORM'` | `primary_store_name` |
| `MIN(order_item_lines.snapshot_name)` | `primary_item_name` |
| `MIN(order_item_lines.snapshot_image_url)` | `primary_item_image_url` |
| `COUNT(order_items)` | `item_count`, `remaining_item_count` (= `item_count - 1`) |

> Các field `buyer_tab`, `ui_primary_status`, `ui_status_rank` derive y hệt bảng precedence tại §4. SQL đầy đủ xem [Search/Filter Design §3.1](order_search_design.md#31-sql-chính).

Guard keys cho FSM consistency (sau khi bỏ `version` field):

| Stream ownership | Guard key | Mục đích |
|---|---|---|
| Order lifecycle | `last_event_sequence_id` (per `order_id`) | Counter đơn điệu cho mọi event phát ra từ 1 order — thay thế `payment_event_sequence_id` + `refund_event_sequence_id` cũ |
| Item/delivery | (derive từ outbox event sequence per `order_item_id`) | Chặn out-of-order ở cấp item; Notification consumer guard theo `order_item_id` + `last_event_sequence_id` |


> Khi có xung đột giữa timestamp và sequence, FSM consumer luôn ưu tiên sequence để quyết định mới-cũ. Các guard key này nằm trong payload event (xem [Order Events §2.1](order_events.md#21-guard-keys-cho-fsm)).
>
> **Lý do bỏ `version`/`order_version`/`item_version`:** Temporal workflow đã serialize mọi FSM transition; Kafka consumer đã idempotent theo `event_id`; row-level lock đủ chặn concurrent transition. Bỏ field này giảm payload size + giảm logic validate ở downstream (xem chi tiết [DB Design §2](order_db_design.md#2-quản-lý-trạng-thái-phức-tạp-với-state-machine-fsm)).