# Order Service - State Machine & Lifecycle

**Liên kết nhanh (Related Documents):**
*   [Database Schema](order_db_design.md)
*   [Events Structure](order_events.md)
*   [Workflows](order_workflow.md)
*   [Workers](order_workers.md)
*   [Temporal Saga Design](temporal_saga_design.md)

---

Tài liệu này mô tả state machine của `orders` và `order_parcels` ở mức chuẩn hóa để các worker và events dùng chung một ngôn ngữ trạng thái.

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

## 2. Parcel FSM (Bảng order_parcels)

Các trạng thái chính của parcel:
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
| T4 | `PENDING_PAYMENT` | `CANCELLED` | `USER_CANCEL` |
| T7 | `PAID` | `CANCELLED` | `ADMIN_FORCE_CANCEL` |
| T8 | `PAID` | `PROCESSING` | `POST_PAYMENT_SAGA_COMPLETED` |
| T8.1 | `PROCESSING` | `PAID` | `POST_PAYMENT_COMPENSATION_ROLLBACK` |
| T8.2 | `PROCESSING` | `CANCELLED` | `ADMIN_FORCE_CANCEL` |
| T8.3 | `PROCESSING` | `COMPLETED` | `AUTO_COMPLETE_ALL_PARCELS_DELIVERED` |
| P1 | `PREPARING` | `PACKAGED` | `WAREHOUSE_PACKED` |
| P2 | `PACKAGED` | `HANDED_OVER` | `COURIER_PICKED_UP` |
| P3 | `HANDED_OVER` | `IN_TRANSIT` | `SHIPMENT_STATUS_UPDATED(IN_TRANSIT)` |
| P4 | `IN_TRANSIT` | `DELIVERED` | `SHIPMENT_DELIVERED` |
| P5 | `IN_TRANSIT` | `DELIVERY_FAILED` | `SHIPMENT_STATUS_UPDATED(DELIVERY_FAILED)` |
| P6 | `DELIVERY_FAILED` | `IN_TRANSIT` | `SHIPMENT_STATUS_UPDATED(RETRY_IN_TRANSIT)` |
| P7 | `DELIVERY_FAILED` | `RETURNED` | `SHIPMENT_RETURNED` |
| P8 | `DELIVERED` | `RETURNED` | `SHIPMENT_RETURNED` |
| T9 | `COMPLETED` | `RETURN_REQUESTED` | `RETURN_API_ACCEPTED` hoặc `SHIPMENT_RETURNED` |
| T10 | `RETURN_REQUESTED` | `REFUNDED` | `REFUND_SUCCESS` |

## 23. Mapping với Shipping Service (standardized_status)

| Shipping Event | Parcel Status |
|---|---|
| `SHIPMENT_STATUS_UPDATED` (`PREPARING`/`PACKAGED`/`HANDED_OVER`/`IN_TRANSIT`/`DELIVERY_FAILED`) | Map 1-1 theo standardized_status |
| `SHIPMENT_DELIVERED` | `DELIVERED` |
| `SHIPMENT_RETURNED` | `RETURNED` |
| `SHIPMENT_DELAYED` | Không đổi parcel status, chỉ cập nhật metadata SLA/escalation |

## 31. Auto-Complete Logic: PROCESSING -> COMPLETED

Master order tự chuyển `PROCESSING -> COMPLETED` khi tất cả parcels đã `DELIVERED`.

Ràng buộc:
- Logic chạy idempotent bằng CAS update trên `orders.status`:
	```sql
	UPDATE orders
	SET status = 'COMPLETED', version = version + 1, updated_at = NOW()
	WHERE id = :order_id AND status = 'PROCESSING';
	```
- Chỉ khi update thành công (affected rows = 1) mới được phát `ORDER_COMPLETED`.
- Không chuyển `COMPLETED` nếu còn parcel chưa kết thúc.
- Coalescing window 5 giây để tránh rung trạng thái khi webhook shipping đến sát nhau.
