# Order Service - Danh sách Background Workers & Jobs

> **Phase 22 (Simplify Idempotency Schema):** Bỏ `order_event_idempotency`. Idempotency cho cả 3 Kafka consumer (Payment Status, Inventory Ship, Inventory Return) giờ dựa vào **Redis SETNX** (TTL 30 ngày) thay cho durable DB layer. Idempotency ở tầng DB vẫn đảm bảo nhờ FSM CAS (`UPDATE ... WHERE status='OLD_STATUS'` + row-level lock) + CloudEvents `event_id` envelope. Xem [CHANGELOG Phase 22](../_contracts/CHANGELOG.md#phase-22--simplify-idempotency-schema-order).

**Liên kết nhanh (Related Documents):**
*   [Database Schema](order_db_design.md)
*   [Temporal Saga Design](temporal_saga_design.md)
*   [Search/Filter Design (PostgreSQL direct)](order_search_design.md)
*   [State Machine & Lifecycle](order_state_machine.md)
*   [Workflows (API & Data Flow)](order_workflow.md)
*   [Events Structure & Kafka Topics](order_events.md)

---

Order Service chạy song song 3 lớp nền: **Temporal Workers** (điều phối Saga), **Kafka Workers** (event-driven sync), và **CDC Connector Runtime** (publish outbox theo WAL). Ngoài ra có các CronJob dọn dẹp dữ liệu idempotency/outbox.

## Tổng Hợp Kafka Topics

| Hướng | Topic | Consumer Group | Mô tả |
|---|---|---|---|
| **Produce** | `t.order.events` | — | Topic chính chứa mọi event phát đi |
| **Consume** | `t.payment.events` | `order-payment-consumer` | Nhận kết quả thanh toán |
| **Consume** | `t.inventory.events` | `order-inventory-return-consumer` | Nhận xác nhận Inventory đã nhận đủ hàng trong refund scope |
| **Consume** | `t.inventory.events` | `order-inventory-ship-consumer` | Nhận event INVENTORY_SHIP_TO_CUSTOMER để trigger item FSM PROCESSING → DELIVERED |

Xem payload chi tiết tại [Order Events](order_events.md).

---

## 1. Outbox CDC Pipeline (Producer Path)
**Loại hình:** CDC Connector (Debezium/Kafka Connect).

**Sứ mệnh:** Đẩy event từ bảng `outbox_events` append-only ra Kafka một cách tin cậy theo WAL CDC.

**Chi tiết luồng hoạt động:**
1. Trong cùng DB transaction nghiệp vụ, application insert 1 record vào `outbox_events` (kèm `partition_key`, `event_type`, `aggregate_type`, `aggregate_id`, `payload`).
2. Debezium đọc WAL thay đổi mới của `outbox_events`.
3. Outbox Event Router publish sang topic đích:
   - `aggregate_type IN ('Order', 'OrderItem')` -> `t.order.events`
4. Partition key lấy trực tiếp từ cột `partition_key` để giữ ordering theo stream ownership.
5. Retry, DLQ, backoff vận hành tại Kafka Connect runtime, không dùng cột `status/retry_count` trong DB.

> **Invariant:** `partition_key` bắt buộc `NOT NULL`; thiếu key sẽ phá ordering của consumer downstream.

---

## 2. Search/Filter — Query trực tiếp PostgreSQL (Phase 15)

Search/Filter API (`GET /v1/orders/search`) được phục vụ trực tiếp bởi các B-tree/GIN indexes + view `v_orders_buyer_tab` trong PostgreSQL. Từ Phase 21, Order Service **không còn** worker nào liên quan đến Search projection (đã bỏ `order-metrics-projector` và topic `t.search.metrics.events` — Search Service không còn cần `product_sold_count`).

Xem chi tiết schema/index/query tại [Search/Filter Design](order_search_design.md).

---

## 3. Payment Status Consumer (Kafka Consumer)
**Loại hình:** Kafka Consumer chạy liên tục.
**Consumer Group:** `order-payment-consumer`

**Sứ mệnh:** Nhận kết quả thanh toán / hoàn tiền từ Payment Service → signal Temporal workflows để resume, đồng thời kích hoạt recovery lane khi payment success đến muộn sau timeout.

**Chi tiết luồng hoạt động:**
1. Consume topic `t.payment.events`.
2. Phân loại event:
    - **`PAYMENT_SUCCESS`:**
       1. Lookup `checkout_token` theo `order_id`, sau đó tìm Temporal Workflow (WorkflowID = `order-checkout-{checkout_token}`).
       2. Nếu checkout workflow đang active, signal checkout workflow: `workflowStub.signal("PaymentResult", PaymentSignal.SUCCESS, txn_details)`.
       3. Nếu checkout workflow đã close do timeout và order đang `CANCELLED(PAYMENT_TIMEOUT)`, start `OrderLatePaymentRecoveryWorkflow` theo `order_id`.
       4. Worker **không** update trực tiếp `orders.status` và **không** insert `ORDER_PAID`; checkout/recovery workflow mới là owner của commit mốc tài chính.
    - **`PAYMENT_FAILED` / `PAYMENT_EXPIRED`:**
       1. Signal checkout workflow: `PaymentSignal.FAILED`.
       2. Checkout workflow tự chạy compensation pre-payment và chỉ commit `CANCELLED` + outbox `ORDER_CANCELLED` sau khi compensation hoàn tất.
    - **`REFUND_PROCESSING` / `REFUND_SUCCESS` / `REFUND_FAILED` / `REFUND_REVERSED`:**
       1. Lookup refund workflow theo `order_id` (WorkflowID = `order-refund-{order_id}`).
       2. Signal `RefundResult` tương ứng cho `OrderRefundWorkflow`.
       3. Worker **không** update trực tiếp `orders.status/payment_status`; refund workflow là owner duy nhất của commit FSM/outbox trong lane refund.
       4. Worker **không** được reset one-shot guard của Buyer API; guard được enforce qua cột `orders.buyer_return_locked_at` (CAS row-level lock — xem [Workflow §3.1](order_workflow.md#31-api-return-order-post-v1ordersorderidreturn) và [State Machine §3.1](order_state_machine.md#31-guard-cho-buyer-api-one-shot)). Policy vẫn giữ nguyên ngay cả khi refund fail/reversed.

**Idempotency (Phase 22):**
- Fast-path (duy nhất): Redis `SETNX payment:signal:{order_id}:{payment_transaction_id}:{event_type}` TTL 30 ngày.
- Idempotency dựa vào `event_id` unique từ CloudEvents envelope. Phase 19 đã bỏ `payload_hash` (chỉ dùng `event_id` cho dedup at-least-once).
- Nếu đã xử lý → skip, tránh signal Temporal nhiều lần.

---

## 4. Inventory Ship-To-Customer Consumer (Kafka Consumer)
**Loại hình:** Kafka Consumer chạy liên tục.
**Consumer Group:** `order-inventory-ship-consumer`

**Sứ mệnh:** Nhận `INVENTORY_SHIP_TO_CUSTOMER` từ Inventory Service (sau khi admin xác nhận kho đã xuất hàng) → Update Order Item Delivery FSM PROCESSING → DELIVERED → Check Auto-Complete.

**Chi tiết luồng hoạt động:**
1. Consume topic `t.inventory.events`, lọc `event_type = INVENTORY_SHIP_TO_CUSTOMER`.
2. Query `inventory_reservations` theo `(order_id, warehouse_id)` lọc `status='DEDUCTED'` để lấy danh sách `order_item_id` cần chuyển `DELIVERED` (filter cả `warehouse_id` là bắt buộc cho multi-warehouse order để tránh DELIVERED nhầm cho item ở kho khác).
 3. Với mỗi `order_item_id` lấy được:
    - Update FSM với row-level lock + idempotency nhờ CAS (`WHERE status = 'PROCESSING'`). Nếu row đã `DELIVERED` thì `affected_rows = 0`, skip. Không còn `version`/optimistic locking — Temporal serialization + Redis SETNX dedup đã chặn duplicate:
      ```sql
      UPDATE order_items SET status = 'DELIVERED'
      WHERE id = :order_item_id 
        AND status = 'PROCESSING';
      ```
    - Insert `outbox_events` cho `ORDER_ITEM_STATUS_CHANGED`.
 4. **Auto-Complete Check:** Sau mỗi lô update item:
    ```sql
    SELECT COUNT(*) as total, 
           COUNT(CASE WHEN status = 'DELIVERED' THEN 1 END) as delivered
    FROM order_items WHERE order_id = :order_id;
    ```
    Nếu `total = delivered` thì chạy CAS idempotent:
    ```sql
    UPDATE orders
    SET status = 'COMPLETED', updated_at = NOW()
    WHERE id = :order_id AND status = 'PROCESSING';
    ```
   Chỉ khi số bản ghi update = 1 mới produce `ORDER_COMPLETED`.
5. **Coalescing window:** 5 giây để tránh phát `ORDER_COMPLETED` rung khi có nhiều item đến sát nhau.

**Idempotency (Phase 22):**
- Fast-path (duy nhất): Redis `SETNX inventory:ship:applied:{order_id}:{warehouse_id}:{shipped_at}` TTL 30 ngày. `warehouse_id` được include để tránh xung đột idempotency khi multi-warehouse order gửi nhiều event `INVENTORY_SHIP_TO_CUSTOMER` cho cùng `order_id`.
- DB-layer idempotency nhờ FSM CAS ở step 3 (`UPDATE order_items ... WHERE status='PROCESSING'`) — duplicate processing sẽ không có effect.

### 5.1. Inventory Return Confirmation Consumer (Kafka Consumer)
**Loại hình:** Kafka Consumer chạy liên tục.
**Consumer Group:** `order-inventory-return-consumer`

**Sứ mệnh:** Nhận event tổng kết từ Inventory khi đã nhận đủ toàn bộ item trong refund scope, sau đó signal `OrderRefundWorkflow` để mở bước gọi Payment refund.

**Chi tiết luồng hoạt động:**
1. Consume topic `t.inventory.events`, lọc event `INVENTORY_RETURN_RECEIVED_ALL`.
2. Idempotency guard (Phase 22):
   - Duy nhất: Redis `SETNX inventory:return:confirm:{order_id}:{refund_request_id}` TTL 30 ngày.
3. Route signal:
   - Lookup workflow `order-refund-{order_id}`.
   - Signal `InventoryReturnReceivedAll` cho workflow với payload `{refund_request_id}`. **Phase 20:** `INVENTORY_RETURN_RECEIVED_ALL` payload đã bỏ `order_item_ids[]` (Order workflow tự derive từ `refund_request_id` + scope đã lock ở `LockRefundScopeActivity`) và `confirmed_at` (audit only, có trong workflow history).
4. Worker **không** cập nhật trực tiếp `orders.status/payment_status`; quyền commit vẫn thuộc `OrderRefundWorkflow`.

---

## 5. Temporal Worker (Workflow & Activity Registration)
**Loại hình:** Long-running process đăng ký với Temporal Server.

**Sứ mệnh:** Chạy `OrderCheckoutWorkflow` và các Activities liên quan. Xem [Temporal Saga Design](temporal_saga_design.md).

**Chi tiết:**
1. **Workflow Registration:**
   - `OrderCheckoutWorkflow` — Orchestrator chính cho Pre-Payment và Post-Payment Saga.
   - `OrderRefundWorkflow` — Orchestrator cho lane hoàn tiền (`return` + `admin force refund`).
   - `OrderLatePaymentRecoveryWorkflow` — Orchestrator cho lane khôi phục đơn khi payment success đến muộn sau timeout.
2. **Activity Registration:**
   - `CreateOrderActivity` — INSERT Master Order + Items + Item Lines vào DB.
   - `ReserveInventoryActivity` — gRPC/API sang Inventory `/reserve`.
   - `ReservePromotionActivity` — gRPC/API sang Promotion `/reserve`.
       * Nếu fail phải bubble lỗi có cấu trúc (`failed_promotion_id`, `failed_reason`, `reserve_mode=ALL_OR_NOTHING`) về workflow layer để Checkout dựng `409 PROMOTION_RECALCULATION_NEEDED`.
   - `InitiatePaymentActivity` — gRPC/API sang Payment `/initiate` → nhận `client_secret` (Stripe) + `publishable_key`.
   - `DeductInventoryActivity` — gRPC/API sang Inventory `/deduct` (sau Payment).
   - `ConfirmPromotionActivity` — gRPC/API sang Promotion `/confirm` (sau Payment), bắt buộc truyền `idempotency_key` theo từng `promotion_id`.
    - `UpdateOrderStatusActivity` — FSM update an toàn với row-level lock + idempotency theo `event_id` (bỏ `version`/optimistic locking, xem [DB Design §2](order_db_design.md#2-quản-lý-trạng-thái-phức-tạp-với-state-machine-fsm)).
   - `LockRefundScopeActivity` — chốt refund scope từ request đầu vào để tính refund một lần cho mỗi order.
   - `RegisterRefundReturnExpectationActivity` — đăng ký expected return scope với Inventory.
   - `WaitInventoryReturnReceivedAllSignal` — chờ signal Inventory xác nhận đã nhận đủ item refund scope.
   - `RecoverPaidOrderFromTimeoutActivity` — khôi phục order lane khi checkout timeout nhưng payment success đến muộn.
3. **Compensation Activities:**
   - `RollbackPromotionActivity` — Nhả reservation budget voucher ở lane pre-payment (không đổi trạng thái ví DB), bắt buộc truyền `idempotency_key` theo từng `promotion_id`.
   - `RollbackInventoryActivity` — Giải phóng reserved stock.
   - `CancelOrderActivity` — Mark order `CANCELLED` khi pre-payment saga fail.
   - `CancelPaymentActivity` — Hủy payment transaction nếu đã initiate.
   - `RollbackDeductedInventoryActivity` — Hoàn bù stock đã deduct nếu post-payment fail.
   - `RevertOrderStatusToPaidActivity` — Trả status `PROCESSING` về `PAID` khi rollback post-payment.
4. **Cấu hình:**
    - Task Queues: `order-checkout-queue`, `order-refund-queue`, `order-late-payment-recovery-queue`
    - WorkflowID:
       - `order-checkout-{checkout_token}` (đảm bảo 1 checkout submit chỉ có 1 workflow active)
       - `order-refund-{order_id}` (đảm bảo 1 lane refund active theo order)
       - `order-late-payment-recovery-{order_id}` (đảm bảo 1 lane recovery active theo order)
    - Activity Timeout: StartToClose = 30 giây, RetryPolicy = 3 lần
    - Workflow Timeout: 30 phút (bao gồm payment window 15 phút + retry/rollback buffer)

---

## 6. Outbox Data Retention Trimmer (Batch CronJob)
**Loại hình:** Job hàng ngày (VD: 4:00 AM).

```sql
DELETE FROM outbox_events
WHERE created_at < NOW() - INTERVAL '15 days';
```

> Chỉ chạy khi CDC connector lag trong ngưỡng an toàn (watermark đã vượt xa dữ liệu sắp xóa).

---


