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

Order Service chạy song song 3 lớp nền: **Temporal Workers** (điều phối Saga), **Kafka Workers** (event-driven sync), và **CDC Connector Runtime** (publish outbox theo WAL). Ngoài ra có các CronJob dọn dẹp và archive dữ liệu.

## Tổng Hợp Kafka Topics

| Hướng | Topic | Consumer Group | Mô tả |
|---|---|---|---|
| **Produce** | `t.order.events` | — | Topic chính chứa mọi event phát đi |
| **Produce** | `t.search.metrics.events` | — | Topic metrics theo `product_id` cho Search projection |
| **Produce** | `t.order.archive.retry` | — | Topic replay cho archive batch lỗi (payload chỉ chứa `archive_batch_id`) |
| **Produce** | `t.order.cqrs.retry` | — | Retry lane cho CQRS projector khi self-heal/replay |
| **Produce** | `t.order.cqrs.dlq` | — | Dead-letter lane cho CQRS projector (contract/permanent errors) |
| **Consume** | `t.order.archive.retry` | `order-archive-replay-consumer` | Consumer chạy lại archive batch theo `archive_batch_id` |
| **Consume** | `t.payment.events` | `order-payment-consumer` | Nhận kết quả thanh toán |
| **Consume** | `t.shipping.events` | `order-shipping-consumer` | Nhận cập nhật vận chuyển |
| **Consume** | `t.order.cqrs.retry` | `order-cqrs-retry-replayer` | Replay sự kiện CQRS sau backoff |

Xem payload chi tiết tại [Order Events](order_events.md).

---

## 1. Outbox CDC Pipeline (Producer Path)
**Loại hình:** CDC Connector (Debezium/Kafka Connect), không polling SQL trong application.

**Sứ mệnh:** Đẩy event từ bảng `outbox_events` append-only ra Kafka một cách tin cậy theo WAL CDC.

**Chi tiết luồng hoạt động:**
1. Trong cùng DB transaction nghiệp vụ, application insert 1 record vào `outbox_events` (kèm `partition_key`, `event_type`, `aggregate_type`, `aggregate_id`, `payload`).
2. Debezium đọc WAL thay đổi mới của `outbox_events`.
3. Outbox Event Router publish sang topic đích:
   - `aggregate_type IN ('Order', 'OrderParcel')` -> `t.order.events`
   - `aggregate_type = 'ProductSalesMetrics'` -> `t.search.metrics.events`
   - `aggregate_type = 'ArchiveBatch'` -> `t.order.archive.retry`
4. Partition key lấy trực tiếp từ cột `partition_key` để giữ ordering theo stream ownership.
5. Retry, DLQ, backoff vận hành tại Kafka Connect runtime, không dùng cột `status/retry_count` trong DB.

> **Invariant:** `partition_key` bắt buộc `NOT NULL`; thiếu key sẽ phá ordering của CQRS/consumer downstream.

---

## 2. CQRS Projection Workers (Kafka Consumers)
**Loại hình:** Kafka Consumer chạy liên tục.

**Consumer groups chính:**
- `order-cqrs-summary-projector`: ghi `orders_write` (1 document/order).
- `order-cqrs-line-projector`: ghi `order_search_lines_write` (1 document/item).
- `order-cqrs-retry-replayer`: consume `t.order.cqrs.retry` và replay theo backoff.

**Sứ mệnh:** Đồng bộ read-model Elasticsearch theo kiến trúc hybrid 2-index để phục vụ đồng thời:
1. Lọc đa chiều chính xác theo buyer/seller/status/time.
2. Text search theo tên sản phẩm, tracking number, store name.
3. Trả về order-level card nhất quán với buyer tabs kiểu Shopee.

Xem chi tiết schema/query/guard/reindex tại [CQRS Design](cqrs_elasticsearch_design.md).

### 2.1. Luồng xử lý chuẩn

1. Consume từ `t.order.events`.
2. Parse và phân lane theo event type:
   - Lane `orders_read`: cập nhật order-level summary (`ORDER_*`, payment/refund derived status, parcel summary, buyer_tab, card fields).
   - Lane `order_search_lines`: cập nhật line-level text documents cho product/tracking/store search, đồng bộ `buyer_tab` và `ui_status_rank` để sort ổn định.
3. Áp guard theo stream ownership:
   - `order_version` cho order lifecycle events.
   - `parcel_version` + `shipment_event_sequence_id` cho shipping/parcel events.
   - `payment_event_sequence_id` hoặc `refund_event_sequence_id` cho payment/refund updates.
4. Bulk write:
   - `POST orders_write/_bulk`
   - `POST order_search_lines_write/_bulk`
5. Cập nhật metadata (`last_event_id`, `last_event_timestamp`, `indexed_at`, `projection_version`).

### 2.2. Projection fields bắt buộc cho buyer list/search

- Summary projector phải derive `buyer_tab` theo precedence trong [Order FSM](order_state_machine.md#24-buyer-tab-precedence-shopee-style).
- Summary projector phải duy trì đầy đủ card fields trong `orders_read`:
   - `ui_primary_status`, `ui_status_rank`
   - `primary_item_name`, `primary_item_image_url`, `remaining_item_count`
   - `primary_store_name`, `primary_tracking_number`
   - `parcel_count`, `item_count`, `has_multi_parcel`
- Line projector phải đồng bộ `buyer_tab` và `ui_status_rank` để query class B có thể filter/sort đồng nhất với query class A.
- Nếu thiếu dữ liệu để dựng card fields (ví dụ event incremental không đủ), event phải park vào `t.order.cqrs.retry` để snapshot rebuild, không được ghi document nửa vời.

### 2.3. Idempotency và out-of-order

- Idempotency không dựa trên so sánh thứ tự UUID `event_id` theo từ điển.
- Quy tắc skip event cũ: chỉ skip khi guard key của stream đó không tăng (`incoming <= current`).
- Event thiếu guard key bắt buộc bị xem là contract violation và chuyển DLQ.

### 2.4. Retry, self-heal và DLQ

1. Lỗi ES transient (`429`, timeout, network reset): inline retry tối đa 3 lần.
2. Không tìm thấy document hoặc thiếu data để apply event incrementally:
   - Park vào `t.order.cqrs.retry`.
   - Retry replayer thực hiện snapshot rebuild theo `order_id` rồi replay event.
3. Lỗi schema/permanent hoặc vượt retry budget:
   - Chuyển `t.order.cqrs.dlq`.
   - Gắn reason code để Ops triage.

### 2.5. Commit semantics

Offset chỉ được commit khi:
- bulk apply thành công, hoặc
- event đã park thành công vào retry/DLQ.

Nếu cả apply lẫn park đều fail, không commit offset để tránh mất event.

---

## 3. Order Metrics Projector (Kafka Consumer + Producer)
**Loại hình:** Kafka Consumer chạy liên tục, đồng thời produce metrics event.
**Consumer Group:** `order-metrics-projector`

**Sứ mệnh:** Aggregate sold metrics theo `product_id` từ luồng `ORDER_COMPLETED` và phát `ORDER_PRODUCT_METRICS_UPDATED` lên `t.search.metrics.events`.

**Chi tiết luồng hoạt động:**
1. Consume `t.order.events`, lọc `ORDER_COMPLETED`.
2. Tách `items[]` theo `product_id`, cộng dồn `quantity` theo từng product trong event.
3. Transaction:
   - Upsert bảng aggregate nội bộ `order_product_metrics`.
   - Tăng `event_sequence_id` đơn điệu theo từng `product_id`.
   - Insert outbox event `ORDER_PRODUCT_METRICS_UPDATED` cho từng `product_id` bị ảnh hưởng.
4. CDC connector publish sang `t.search.metrics.events` với partition key `product_id`.

**Idempotency & Ordering:**
- Dedup theo `event_id` của `ORDER_COMPLETED`.
- Event metrics bắt buộc mang `event_sequence_id` đơn điệu theo `product_id`.
- Nếu xử lý lỗi tạm thời, retry tại consumer trước khi ghi DB/outbox; nếu connector lỗi, xử lý qua Kafka Connect DLQ và replay pipeline.

---

## 4. Payment Status Consumer (Kafka Consumer)
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
    - **`REFUND_SUCCESS` / `REFUND_FAILED` / `REFUND_REVERSED`:**
       1. Lookup refund workflow theo `order_id` (WorkflowID = `order-refund-{order_id}`).
       2. Signal `RefundResult` tương ứng cho `OrderRefundWorkflow`.
       3. Worker **không** update trực tiếp `orders.status/payment_status`; refund workflow là owner duy nhất của commit FSM/outbox trong lane refund.
       4. Worker **không** được reset `order_customer_return_guard`; policy one-shot của Buyer API vẫn giữ nguyên ngay cả khi refund fail/reversed.

**Idempotency:**
- Fast-path: Redis `SETNX payment:signal:{order_id}:{payment_transaction_id}:{event_type}` TTL 30 phút.
- Durable guard: ghi/check bảng `order_event_idempotency` với `action_type` theo loại payment/refund để chống lặp sau restart.
- Khi gặp cùng `event_id` nhưng khác `payload_hash`, phải đưa vào contract-violation lane (không skip mù).
- Nếu đã xử lý → skip, tránh signal Temporal nhiều lần.

---

## 5. Shipping Status Consumer (Kafka Consumer)
**Loại hình:** Kafka Consumer chạy liên tục.
**Consumer Group:** `order-shipping-consumer`

**Sứ mệnh:** Nhận cập nhật tracking từ Shipping Service → Update Parcel FSM → Check Auto-Complete.

**Chi tiết luồng hoạt động:**
1. Consume topic `t.shipping.events`.
2. Áp dụng event theo loại:
   - `SHIPMENT_STATUS_UPDATED`: Map `standardized_status` → Parcel status theo [mapping table](order_state_machine.md#23-mapping-với-shipping-service-standardized_status).
   - `SHIPMENT_DELIVERED`: Force map sang `DELIVERED` (nếu transition hợp lệ).
   - `SHIPMENT_RETURNED`: Force map sang `RETURNED` (nếu transition hợp lệ).
   - `SHIPMENT_DELAYED`: Không đổi parcel terminal status; update metadata SLA/escalation.
3. Với các event đổi trạng thái parcel (`SHIPMENT_STATUS_UPDATED`, `SHIPMENT_DELIVERED`, `SHIPMENT_RETURNED`):
   - Update FSM với Optimistic Locking:
     ```sql
     UPDATE order_parcels SET status = :new_status, 
       tracking_number = :tracking, version = version + 1
     WHERE id = :parcel_id AND version = :expected_version;
     ```
   - Insert `order_status_history` cho parcel transition.
   - Insert `outbox_events` cho `PARCEL_STATUS_CHANGED`.
4. **Auto-Complete Check:** Sau mỗi parcel update:
   ```sql
   SELECT COUNT(*) as total, 
          COUNT(CASE WHEN status = 'DELIVERED' THEN 1 END) as delivered
   FROM order_parcels WHERE order_id = :order_id;
   ```
   Nếu `total = delivered` thì chạy CAS idempotent:
   ```sql
   UPDATE orders
   SET status = 'COMPLETED', version = version + 1, updated_at = NOW()
   WHERE id = :order_id AND status = 'PROCESSING';
   ```
   Chỉ khi số bản ghi update = 1 mới produce `ORDER_COMPLETED`.
5. **Return Trigger Check (policy B):** Nếu parcel chuyển sang `RETURNED` sau `DELIVERED`:
   - Trigger Refund luồng: chỉ `SHIPMENT_RETURNED` **event đầu tiên** được quyền start `OrderRefundWorkflow` (`trigger_source = SHIPMENT_RETURNED`).
   - Trigger này là lane vận hành hệ thống, không bị ràng buộc bởi one-shot guard của Buyer API.
   - Nếu order đã có refund workflow active: worker signal append-returned-parcel vào workflow hiện tại (không start workflow mới).
   - Nếu order đã có `refund_transaction_id` terminal: worker không mở refund transaction thứ hai; ghi history + chuyển manual adjustment lane.
   - `OrderRefundWorkflow` là nơi commit transition master `COMPLETED` -> `RETURN_REQUESTED`.
6. **Delayed Escalation:** Nếu event `SHIPMENT_DELAYED` có `escalation_level >= 2`:
   - Insert `order_status_history` với reason `SHIPMENT_DELAYED_ESCALATED`.
   - Produce `ORDER_STATUS_CHANGED` (metadata-only) cho CQRS/Ops dashboard.

**Idempotency:** Key = `shipping:{shipment_id}:{new_status}:{occurred_at}`; durable guard ghi vào `order_event_idempotency` với `action_scope_key = {shipment_id}:{shipment_event_sequence_id}`.

---

## 6. Temporal Worker (Workflow & Activity Registration)
**Loại hình:** Long-running process đăng ký với Temporal Server.

**Sứ mệnh:** Chạy `OrderCheckoutWorkflow` và các Activities liên quan. Xem [Temporal Saga Design](temporal_saga_design.md).

**Chi tiết:**
1. **Workflow Registration:**
   - `OrderCheckoutWorkflow` — Orchestrator chính cho Pre-Payment và Post-Payment Saga.
   - `OrderRefundWorkflow` — Orchestrator cho lane hoàn tiền (`return` + `admin force refund`).
   - `OrderLatePaymentRecoveryWorkflow` — Orchestrator cho lane khôi phục đơn khi payment success đến muộn sau timeout.
2. **Activity Registration:**
   - `CreateOrderActivity` — INSERT Master Order + Parcels + Items vào DB.
   - `ReserveInventoryActivity` — gRPC/API sang Inventory `/reserve`.
   - `ReservePromotionActivity` — gRPC/API sang Promotion `/reserve`.
       * Nếu fail phải bubble lỗi có cấu trúc (`failed_promotion_id`, `failed_reason`, `reserve_mode=ALL_OR_NOTHING`) về workflow layer để Checkout dựng `409 PROMOTION_RECALCULATION_NEEDED`.
   - `InitiatePaymentActivity` — gRPC/API sang Payment `/initiate` → nhận `payment_url`.
   - `DeductInventoryActivity` — gRPC/API sang Inventory `/deduct` (sau Payment).
   - `ConfirmPromotionActivity` — gRPC/API sang Promotion `/confirm` (sau Payment), bắt buộc truyền `idempotency_key` theo từng `promotion_id`.
   - `CreateShippingLabelsActivity` — gRPC/API sang Shipping `/create-shipment` cho từng parcel.
   - `UpdateOrderStatusActivity` — FSM update an toàn với Optimistic Locking.
   - `LockRefundScopeActivity` — chốt snapshot parcels returned để tính refund một lần cho mỗi order.
   - `AppendReturnedParcelActivity` — append shipping return follow-up vào refund case đang active.
   - `RecoverPaidOrderFromTimeoutActivity` — khôi phục order lane khi checkout timeout nhưng payment success đến muộn.
3. **Compensation Activities:**
   - `RollbackPromotionActivity` — Hoàn voucher, bắt buộc truyền `idempotency_key` theo từng `promotion_id`.
   - `RollbackInventoryActivity` — Giải phóng reserved stock.
   - `CancelOrderActivity` — Mark order `CANCELLED` khi pre-payment saga fail.
   - `CancelPaymentActivity` — Hủy payment transaction nếu đã initiate.
   - `RollbackDeductedInventoryActivity` — Hoàn bù stock đã deduct nếu post-payment fail.
   - `RevertOrderStatusToPaidActivity` — Trả status `PROCESSING` về `PAID` khi rollback post-payment.
   - `CancelShippingActivity` — Hủy vận đơn đã tạo.
4. **Cấu hình:**
    - Task Queues: `order-checkout-queue`, `order-refund-queue`, `order-late-payment-recovery-queue`
    - WorkflowID:
       - `order-checkout-{checkout_token}` (đảm bảo 1 checkout submit chỉ có 1 workflow active)
       - `order-refund-{order_id}` (đảm bảo 1 lane refund active theo order)
       - `order-late-payment-recovery-{order_id}` (đảm bảo 1 lane recovery active theo order)
    - Activity Timeout: StartToClose = 30 giây, RetryPolicy = 3 lần
    - Workflow Timeout: 30 phút (bao gồm payment window 15 phút + retry/rollback buffer)

---

## 7. Cold Data Archiver (Batch CronJob)
**Loại hình:** Lịch biểu chạy hàng tuần (VD: Chủ Nhật 3:00 AM).

**Sứ mệnh:** Di dời đơn hàng cũ sang Cold Storage. Xem [Cold Data Archiving Design](cold_data_archiving_design.md).

**Chi tiết luồng hoạt động:**
1. Khởi tạo `archive_batch_id` và insert control record vào `archive_batches` (`status = RUNNING`, `attempt_count + 1`).
2. Quét đơn hàng đủ điều kiện:
   ```sql
   SELECT id FROM orders 
   WHERE status IN ('COMPLETED', 'CANCELLED', 'REFUNDED') 
     AND updated_at < NOW() - INTERVAL '6 months' 
   LIMIT 10000;
   ```
3. Insert membership vào `archive_batch_items` cho toàn bộ `order_id` đã chọn.
4. Denormalize -> JSON/Parquet -> Upload S3.
5. Verify S3 integrity, sau đó upsert `archived_orders_lookup` (`order_id`, `archive_batch_id`, `s3_file_key`, `order_created_at`, `archived_at`).
6. Hard Delete từ PostgreSQL (CASCADE `order_parcels`, `order_items`, `order_promotions`) chỉ sau khi lookup đã ghi thành công.
7. Cập nhật `archive_batch_items.archive_status` theo từng order và chốt `archive_batches.status = COMPLETED`.
8. Nếu fail ở bất kỳ stage nào (`SNAPSHOT`, `UPLOAD`, `VERIFY`, `DELETE`):
   - Cập nhật `archive_batches.status = FAILED`, `failure_stage`, `failure_reason`.
   - Insert outbox event `ORDER_ARCHIVE_BATCH_FAILED` (`aggregate_type = ArchiveBatch`, `partition_key = archive_batch_id`) để CDC publish lên `t.order.archive.retry`.

---

## 8. Archive Replay Consumer (Kafka Consumer)
**Loại hình:** Kafka Consumer chạy liên tục.
**Consumer Group:** `order-archive-replay-consumer`

**Sứ mệnh:** Lắng nghe `ORDER_ARCHIVE_BATCH_FAILED` từ `t.order.archive.retry`, sau đó replay lô lỗi theo `archive_batch_id`.

**Chi tiết luồng hoạt động:**
1. Consume event chứa: `archive_batch_id`, `attempt`, `failure_stage`, `failed_at`.
2. Idempotency guard:
   - Fast path: Redis `SETNX archive:replay:{archive_batch_id}:{attempt}`.
   - Durable path: `order_event_idempotency` với `action_type = ARCHIVE_BATCH_REPLAY`, `action_scope_key = {archive_batch_id}:{attempt}`.
3. Load `archive_batches` + danh sách `order_id` từ `archive_batch_items`.
4. Chỉ replay các item `archive_status IN ('PENDING', 'FAILED')`.
5. Re-run theo stage thất bại:
   - `SNAPSHOT`/`UPLOAD`/`VERIFY`: re-upload + verify rồi mới cho phép delete.
   - `DELETE`: chỉ chạy delete cho orders đã có `archived_orders_lookup` + checksum hợp lệ.
6. Khi thành công, update `archive_batches.status = COMPLETED`; nếu vẫn fail:
   - Tăng `attempt_count`, cập nhật `archive_batches.status = FAILED` + `failure_stage` mới.
   - Insert outbox event `ORDER_ARCHIVE_BATCH_FAILED` mới (`aggregate_type = ArchiveBatch`, `partition_key = archive_batch_id`) để CDC publish lại retry event theo backoff policy.

---

## 9. Outbox Data Retention Trimmer (Batch CronJob)
**Loại hình:** Job hàng ngày (VD: 4:00 AM).

```sql
DELETE FROM outbox_events
WHERE created_at < NOW() - INTERVAL '15 days';
```

> Chỉ chạy khi CDC connector lag trong ngưỡng an toàn (watermark đã vượt xa dữ liệu sắp xóa).

---

## 10. CDC DLQ & Connector Health Monitor (Alerting CronJob)
**Loại hình:** Cron Job mỗi 5 phút.

**Chi tiết:**
1. Kiểm tra trạng thái Debezium/Kafka Connect connector của outbox.
2. Theo dõi DLQ topics của CDC pipeline (ví dụ: `t.order.outbox.cdc.dlq`).
3. Ghi metrics:
   - `order_outbox_cdc_connector_down_total`
   - `order_outbox_cdc_dlq_events_total`
4. Alert Slack/PagerDuty nếu connector `FAILED` hoặc DLQ tăng vượt ngưỡng.

---

## 11. Idempotency Cleanup (Batch CronJob)
**Loại hình:** Job hàng ngày (VD: 5:00 AM).

```sql
DELETE FROM order_request_idempotency
WHERE expires_at < NOW();

DELETE FROM order_event_idempotency
WHERE processed_at < NOW() - INTERVAL '45 days';
```
