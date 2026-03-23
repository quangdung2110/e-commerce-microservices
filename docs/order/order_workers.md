# Order Service - Danh sách Background Workers & Jobs

**Liên kết nhanh (Related Documents):**
*   [Database Schema](order_db_design.md)
*   [Temporal Saga Design](temporal_saga_design.md)
*   [CQRS & Elasticsearch](cqrs_elasticsearch_design.md)
*   [Cold Data Archiving](cold_data_archiving_design.md)
*   [State Machine & Lifecycle](order_state_machine.md)
*   [Workflows (API & Data Flow)](order_workflow.md)
*   [Events Structure & Kafka Topics](order_events.md)

---

Order Service chạy song song 2 hệ thống nền: **Temporal Workers** (điều phối Saga) và **Kafka Workers** (event-driven sync). Ngoài ra có các CronJob dọn dẹp và archive dữ liệu.

## Tổng Hợp Kafka Topics

| Hướng | Topic | Consumer Group | Mô tả |
|---|---|---|---|
| **Produce** | `t.order.events` | — | Topic chính chứa mọi event phát đi |
| **Consume** | `t.payment.events` | `order-payment-consumer` | Nhận kết quả thanh toán |
| **Consume** | `t.shipping.events` | `order-shipping-consumer` | Nhận cập nhật vận chuyển |

Xem payload chi tiết tại [Order Events](order_events.md).

---

## 1. Outbox Polling Worker (Producer)
**Loại hình:** Background Thread chạy định kỳ (mỗi 3 giây).

**Sứ mệnh:** Chuyển Event từ bảng `outbox_events` sang Kafka Topic `t.order.events`. Cơ chế tương tự [Product Outbox Worker](../product/product_workers.md#1-outbox-polling-worker-producer).

**Chi tiết luồng hoạt động:**
1. Quét theo lô:
   ```sql
   SELECT * FROM outbox_events 
   WHERE (status = 'PENDING') 
      OR (status = 'FAILED' AND retry_count < max_retries AND next_retry_at <= NOW()) 
   ORDER BY created_at ASC LIMIT 100 
   FOR UPDATE SKIP LOCKED;
   ```
2. Forward lên Kafka: Key = `aggregate_id` (order_id). Bổ sung CloudEvents headers.
3. Sau khi Kafka ACK: `UPDATE outbox_events SET status = 'PUBLISHED'`.
4. Xử lý lỗi: `status = 'FAILED'`, `retry_count + 1`, Exponential Backoff. Quá `max_retries` → `DEAD`.

> **Multi-instance safe:** `FOR UPDATE SKIP LOCKED` cho phép chạy nhiều instance song song mà không duplicate.

---

## 2. CQRS Projection Worker (Kafka Consumer)
**Loại hình:** Kafka Consumer chạy liên tục.
**Consumer Group:** `order-cqrs-projection`

**Sứ mệnh:** Phi chuẩn hóa dữ liệu Order thành Document JSON → Upsert vào Elasticsearch. Phục vụ tìm kiếm/lọc đa chiều cho Buyer và Seller. Xem [CQRS Design](cqrs_elasticsearch_design.md).

**Chi tiết luồng hoạt động:**
1. Consume topic `t.order.events`.
2. Phân loại event:
   - `ORDER_CREATED`: Tạo document mới trong ES — denormalize toàn bộ (buyer info, items, parcels, financials).
   - `ORDER_PAID` / `ORDER_CANCELLED` / `ORDER_STATUS_CHANGED`: Partial update `status` field trong ES document.
   - `PARCEL_STATUS_CHANGED`: Script update parcel status trong nested array.
   - `ORDER_COMPLETED` / `ORDER_REFUNDED`: Update status + financial fields.
3. **Batch Processing:** Gom events theo `order_id` — chỉ xử lý event cuối cùng cho mỗi order (dedup).
4. Bulk API: `POST _bulk` cho Elasticsearch.

**Idempotency:** Sử dụng `event_id` check — nếu ES document đã có `last_event_id >= current_event_id`, skip.

---

## 3. Payment Status Consumer (Kafka Consumer)
**Loại hình:** Kafka Consumer chạy liên tục.
**Consumer Group:** `order-payment-consumer`

**Sứ mệnh:** Nhận kết quả thanh toán / hoàn tiền từ Payment Service → Signal Temporal Workflow để resume.

**Chi tiết luồng hoạt động:**
1. Consume topic `t.payment.events`.
2. Phân loại event:
   - **`PAYMENT_SUCCESS`:** 
     - Tìm Temporal Workflow bằng `order_id` (WorkflowID = `order-checkout-{order_id}`).
     - `workflowStub.signal("PaymentResult", PaymentSignal.SUCCESS, txn_details)`.
     - Temporal resume → Post-Payment Saga bắt đầu.
   - **`PAYMENT_FAILED` / `PAYMENT_EXPIRED`:**
     - Signal Temporal: `PaymentSignal.FAILED`.
     - Temporal resume → Cancel Order → Compensation Stack.
   - **`REFUND_SUCCESS`:**
     - Update `orders.payment_status = 'REFUNDED'`.
     - Trigger FSM: `RETURN_REQUESTED` → `REFUNDED` (nếu đang ở trạng thái phù hợp).
     - Produce `ORDER_REFUNDED` event.
    - **`REFUND_FAILED`:**
       - Update `orders.payment_status = 'REFUND_FAILED'`.
       - Giữ Order ở `RETURN_REQUESTED` và gắn `refund_error_code`, `refund_error_message` để tránh đóng đơn sai trạng thái.
       - Produce `ORDER_REFUND_FAILED` event cho Notification/Ops.
       - Retry policy: exponential backoff + dead-letter escalation sau ngưỡng retry.

**Idempotency:**
- Check bảng `idempotency_keys` với key = `payment:{payment_transaction_id}:{event_type}`.
- Nếu đã xử lý → skip, tránh signal Temporal nhiều lần.

---

## 4. Shipping Status Consumer (Kafka Consumer)
**Loại hình:** Kafka Consumer chạy liên tục.
**Consumer Group:** `order-shipping-consumer`

**Sứ mệnh:** Nhận cập nhật tracking từ Shipping Service → Update Parcel FSM → Check Auto-Complete.

**Chi tiết luồng hoạt động:**
1. Consume topic `t.shipping.events`.
2. Áp dụng event `SHIPMENT_STATUS_UPDATED`:
   - Map `standardized_status` → Parcel status theo [mapping table](order_state_machine.md#23-mapping-với-shipping-service-standardized_status).
   - Update FSM với Optimistic Locking:
     ```sql
     UPDATE order_parcels SET status = :new_status, 
       tracking_number = :tracking, version = version + 1
     WHERE id = :parcel_id AND version = :expected_version;
     ```
   - Insert `order_status_history` cho parcel transition.
   - Insert `outbox_events` cho `PARCEL_STATUS_CHANGED`.
3. **Auto-Complete Check:** Sau mỗi parcel update:
   ```sql
   SELECT COUNT(*) as total, 
          COUNT(CASE WHEN status = 'DELIVERED' THEN 1 END) as delivered
   FROM order_parcels WHERE order_id = :order_id;
   ```
   Nếu `total = delivered` → Master Order FSM `PROCESSING` → `COMPLETED`.
4. **Partial Return Check:** Nếu parcel chuyển sang `RETURNED` sau `DELIVERED`:
   - Master Order FSM `COMPLETED` → `RETURN_REQUESTED`.
   - Trigger Refund luồng: gọi Payment refund API hoặc start Refund Temporal Workflow.

**Idempotency:** Key = `shipping:{shipment_id}:{new_status}:{occurred_at}`.

---

## 5. Temporal Worker (Workflow & Activity Registration)
**Loại hình:** Long-running process đăng ký với Temporal Server.

**Sứ mệnh:** Chạy `OrderCheckoutWorkflow` và các Activities liên quan. Xem [Temporal Saga Design](temporal_saga_design.md).

**Chi tiết:**
1. **Workflow Registration:**
   - `OrderCheckoutWorkflow` — Orchestrator chính cho Pre-Payment và Post-Payment Saga.
2. **Activity Registration:**
   - `ApplyPromotionActivity` — gRPC/API sang Promotion `/reserve`.
   - `ReserveInventoryActivity` — gRPC/API sang Inventory `/reserve`.
   - `CreateOrderActivity` — INSERT Master Order + Parcels + Items vào DB.
   - `InitiatePaymentActivity` — gRPC/API sang Payment `/initiate` → nhận `payment_url`.
   - `DeductInventoryActivity` — gRPC/API sang Inventory `/deduct` (sau Payment).
   - `ConfirmPromotionActivity` — gRPC/API sang Promotion `/confirm` (sau Payment).
   - `CreateShippingLabelsActivity` — gRPC/API sang Shipping `/create-shipment` cho từng parcel.
   - `UpdateOrderStatusActivity` — FSM update an toàn với Optimistic Locking.
3. **Compensation Activities:**
   - `RollbackPromotionActivity` — Hoàn voucher.
   - `RollbackInventoryActivity` — Giải phóng reserved stock.
   - `CancelPaymentActivity` — Hủy payment transaction nếu đã initiate.
   - `CancelShippingActivity` — Hủy vận đơn đã tạo.
4. **Cấu hình:**
   - Task Queue: `order-checkout-queue`
   - WorkflowID: `order-checkout-{order_id}` (đảm bảo 1 order chỉ có 1 workflow active)
   - Activity Timeout: StartToClose = 30 giây, RetryPolicy = 3 lần
   - Workflow Timeout: 24 giờ (bao gồm thời gian chờ thanh toán)

---

## 6. Cold Data Archiver (Batch CronJob)
**Loại hình:** Lịch biểu chạy hàng tuần (VD: Chủ Nhật 3:00 AM).

**Sứ mệnh:** Di dời đơn hàng cũ sang Cold Storage. Xem [Cold Data Archiving Design](cold_data_archiving_design.md).

**Chi tiết luồng hoạt động:**
1. Quét đơn hàng đủ điều kiện:
   ```sql
   SELECT id FROM orders 
   WHERE status IN ('COMPLETED', 'CANCELLED') 
     AND updated_at < NOW() - INTERVAL '6 months' 
   LIMIT 1000;
   ```
2. Denormalize → JSON/Parquet → Upload S3.
3. Verify S3 integrity → Hard Delete từ PostgreSQL (CASCADE `order_parcels`, `order_items`, `order_promotions`).

---

## 7. Old Outbox Trimmer (Batch CronJob)
**Loại hình:** Job dọn dẹp chạy hàng ngày (VD: 4:00 AM).

```sql
DELETE FROM outbox_events 
WHERE status = 'PUBLISHED' AND created_at < NOW() - INTERVAL '15 days';
```

Không xóa `DEAD` events — giữ lại cho SysAdmin Dashboard.

---

## 8. Dead Letter Handler (Alerting CronJob)
**Loại hình:** Cron Job mỗi 5 phút.

**Chi tiết:**
1. Quét `outbox_events WHERE status = 'DEAD'`.
2. Ghi metric `order_outbox_dead_events_total` → Prometheus.
3. Alert Slack/PagerDuty nếu `dead_count > 0`.
4. SysAdmin Dashboard cho phép manual retry: `UPDATE outbox_events SET status = 'PENDING', retry_count = 0 WHERE event_id = :id`.

---

## 9. Idempotency Key Cleanup (Batch CronJob)
**Loại hình:** Job hàng ngày (VD: 5:00 AM).

```sql
DELETE FROM idempotency_keys WHERE expires_at < NOW();
```
