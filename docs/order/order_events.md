# Order Service - Định Nghĩa Các Sự Kiện & Kafka Topics (Events Structure)

**Liên kết nhanh (Related Documents):**
*   [Database Schema](order_db_design.md)
*   [Temporal Saga Design](temporal_saga_design.md)
*   [CQRS & Elasticsearch](cqrs_elasticsearch_design.md)
*   [Cold Data Archiving](cold_data_archiving_design.md)
*   [State Machine & Lifecycle](order_state_machine.md)
*   [Workflows (API & Data Flow)](order_workflow.md)
*   [Background Workers & Jobs](order_workers.md)

**Cross-Service References:**
*   [Promotion Events (Consumed)](../promotion/promotion_events.md) — Promotion consume `ORDER_PAID`, `ORDER_CANCELLED`, `ORDER_REFUNDED`
*   [Payment Events](../payment/payment_events.md) — Order consume `PAYMENT_SUCCESS`, `PAYMENT_FAILED`, `PAYMENT_EXPIRED`, `REFUND_PROCESSING`, `REFUND_SUCCESS`, `REFUND_FAILED`, `REFUND_REVERSED`
*   [Shipping Events](../shipping/shipping_events.md) — Order consume `SHIPMENT_STATUS_UPDATED`, `SHIPMENT_DELIVERED`, `SHIPMENT_RETURNED`, `SHIPMENT_DELAYED`
*   [Inventory Events](../inventory/inventory_events.md) — Inventory consume Reserve/Deduct/Release commands
*   [Cart Events](../cart/cart_events.md) — Cart consume `ORDER_PAID` để batch delete SKU lines trong giỏ

---

Tài liệu này định nghĩa cấu trúc tiêu chuẩn của tất cả các luồng sự kiện **phát đi (Produce)** từ Order Service và **tiêu thụ (Consume)** từ các service khác.

Order Service là **Orchestrator trung tâm** — nó produce nhiều event nhất và consume từ nhiều nguồn nhất trong hệ thống. Tất cả produced events đi qua [Outbox Pattern](order_db_design.md) dạng append-only và được publish bằng CDC pipeline để đảm bảo At-Least-Once Delivery.

## 1. Bản Đồ Kafka Topics

### 1.1 Topics mà Order Service **Produce** (Phát đi)

| Topic Name | Partition Key | Consumers chính | Mô tả |
|---|---|---|---|
| `t.order.events` | `order_id` | Promotion, Inventory, Cart, CQRS Projection Worker (internal), Notification, Analytics, Review | Topic chính chứa mọi thay đổi trạng thái đơn hàng |
| `t.search.metrics.events` | `product_id` | Search Service | Topic metrics chuyên dụng cho projection realtime theo product, được phát bởi internal Order Metrics Projector thuộc Order Service |
| `t.order.archive.retry` | `archive_batch_id` | Internal `order-archive-replay-consumer` | Retry topic cho archive batch lỗi; event chỉ mang metadata batch |
| `t.order.cqrs.retry` | `order_id` | Internal `order-cqrs-retry-replayer` | Parking lot cho CQRS projector để replay/self-heal sau backoff |
| `t.order.cqrs.dlq` | `order_id` | Ops / Internal triage tools | Dead-letter cho CQRS projector khi lỗi permanent/contract violation |

> **Ghi chú Partition Key:**
> - `t.order.events` dùng `order_id` để giữ strict ordering theo từng đơn.
> - `t.search.metrics.events` dùng `product_id` để giữ ordering của sold metrics.
> - `t.order.archive.retry` dùng `archive_batch_id` để giữ ordering replay theo batch.

### 1.2 Topics mà Order Service **Consume** (Lắng nghe)

| Topic Name | Consumer Group | Producer | Events | Mô tả |
|---|---|---|---|---|
| `t.payment.events` | `order-payment-consumer` | Payment Service | `PAYMENT_SUCCESS`, `PAYMENT_FAILED`, `PAYMENT_EXPIRED`, `REFUND_PROCESSING`, `REFUND_SUCCESS`, `REFUND_FAILED`, `REFUND_REVERSED` | Nhận kết quả thanh toán / hoàn tiền |
| `t.shipping.events` | `order-shipping-consumer` | Shipping Service | `SHIPMENT_STATUS_UPDATED`, `SHIPMENT_DELIVERED`, `SHIPMENT_RETURNED`, `SHIPMENT_DELAYED` | Nhận cập nhật trạng thái vận chuyển |
| `t.inventory.events` | `order-inventory-return-consumer` | Inventory Service | `INVENTORY_RETURN_RECEIVED_ALL` | Nhận xác nhận Inventory đã nhận đủ hàng trong refund scope |
| `t.order.archive.retry` | `order-archive-replay-consumer` | Order Service (internal) | `ORDER_ARCHIVE_BATCH_FAILED` | Trigger replay cho archive batch thất bại |
| `t.order.cqrs.retry` | `order-cqrs-retry-replayer` | Order Service (internal projector) | Envelope chứa original event + retry metadata | Replay sự kiện CQRS sau backoff/self-heal |

## 2. Cấu Trúc Message Header Chung (CloudEvents Wrapper)

Tất cả messages phát lên Kafka đều tuân theo chuẩn CloudEvents và được [Outbox CDC Pipeline](order_workers.md#1-outbox-cdc-pipeline-producer-path) publish:

*   `event_id`: UUID của sự kiện (từ `outbox_events.event_id`).
*   `event_type`: Loại sự kiện (VD: `ORDER_CREATED`, `ORDER_PAID`).
*   `aggregate_type`: Với **produced events** của Order: `Order`, `OrderItem`, `ProductSalesMetrics`, hoặc `ArchiveBatch`. Với **consumed events**, giữ nguyên giá trị từ producer gốc (ví dụ `Payment`).
*   `aggregate_id`: ID theo `aggregate_type` (ví dụ `order_id`, `order_item_id`, `payment_transaction_id`, `refund_transaction_id`, `product_id`).
*   `timestamp`: Thời điểm event được tạo (ISO 8601 UTC).
*   `source`: `order-service`.
*   `correlation_id`: Temporal Workflow ID (để trace xuyên suốt Saga).
*   `business_id`: `order_id` dùng cho nghiệp vụ đối soát.
*   `payload`: JSON Object chi tiết theo từng `event_type`.

### 2.1. CQRS Ordering Contract (bắt buộc cho projection)

Để CQRS projector chống out-of-order an toàn, các produced events phải mang guard keys theo stream ownership.

| Event nhóm | Guard key bắt buộc | Ownership |
|---|---|---|
| `ORDER_CREATED`, `ORDER_PAID`, `ORDER_CANCELLED`, `ORDER_STATUS_CHANGED`, `ORDER_COMPLETED` | `order_version` | Order lifecycle stream |
| `ORDER_ITEM_STATUS_CHANGED` | `item_version`, `shipment_event_sequence_id` | Item/shipping stream theo `order_item_id` |
| `ORDER_REFUNDED`, `ORDER_REFUND_FAILED` | `refund_event_sequence_id` | Refund stream |
| Payment-derived updates | `payment_event_sequence_id` | Payment stream |

> Guard keys này phục vụ CQRS projection và replay logic. Không suy luận thứ tự event bằng UUID `event_id` theo từ điển.

### 2.2. Search projection contract (order-level card)

Để dựng được list/search kiểu platformee (1 card/1 order), downstream projector cần các trường tối thiểu sau:

| Event | Trường bắt buộc cho CQRS |
|---|---|
| `ORDER_STATUS_CHANGED`, `ORDER_PAID`, `ORDER_CANCELLED`, `ORDER_COMPLETED` | `order_version`, `status/new_status`, `changed_at/paid_at/completed_at/cancelled_at` |
| `ORDER_ITEM_STATUS_CHANGED` | `order_item_id`, `new_status`, `tracking_number`, `item_version`, `shipment_event_sequence_id`, `changed_at` |
| `ORDER_REFUNDED`, `ORDER_REFUND_FAILED` | `refund_event_sequence_id`, `payment_status`, `status` |

`buyer_tab`, `ui_primary_status`, `ui_status_rank` được derive trong projector theo [Order FSM](order_state_machine.md#24-buyer-tab-precedence-platformee-style), không hard-code ở producer.

## 3. Chi Tiết Các Loại Event Phát Đi (Produced Events)

### 3.1. ORDER_CREATED

**Ngữ cảnh:** Checkout Service submit → Temporal Workflow tạo Master Order + Items + Item Lines thành công. Trạng thái: `CREATED` → `PENDING_PAYMENT`. Xem [Workflow Checkout Submit](order_workflow.md#4-workflow-nhận-checkout-submit).
**Consumers chính:** CQRS Projection Worker (index vào ES), Notification (gửi SMS xác nhận đơn).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174000",
  "event_type": "ORDER_CREATED",
  "aggregate_type": "Order",
  "aggregate_id": "10001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T10:00:00Z",
  "source": "order-service",
  "payload": {
    "order_id": 10001,
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "status": "PENDING_PAYMENT",
    "order_version": 1,
    "payment_event_sequence_id": 0,
    "refund_event_sequence_id": 0,
    "payment_method": "VNPAY",
    "total_amount": 1000000,
    "total_shipping_fee": 45000,
    "total_discount": 50000,
    "final_amount": 995000,
    "shipping_address_snapshot": {
      "name": "Nguyen Van A",
      "phone": "0901234567",
      "address": "123 Nguyen Hue, Q1, HCM",
      "province_code": "HCM",
      "district_code": "Q1"
    },
    "items": [
      {
        "order_item_id": 2001,
        "platform_id": 501,
        "store_name_snapshot": "Apple Flagship Store",
        "warehouse_id": 5,
        "shipping_fee": 25000,
        "courier_code": "GHN",
        "status": "PREPARING",
        "lines": [
          {
            "sku_id": "sku-001",
            "product_id": "prod-001",
            "quantity": 2,
            "unit_price": 250000,
            "snapshot_name": "Áo thun nam",
            "snapshot_image_url": "https://cdn.domain.com/img.jpg",
            "snapshot_attributes": {"color": "Đen", "size": "L"}
          }
        ]
      },
      {
        "order_item_id": 2002,
        "platform_id": 502,
        "store_name_snapshot": "admin B",
        "warehouse_id": 8,
        "shipping_fee": 20000,
        "courier_code": "NINJAVAN",
        "status": "PREPARING",
        "lines": [...]
      }
    ],
    "promotions_applied": [
      {
        "promotion_id": 100,
        "promotion_type": "CART_DISCOUNT",
        "discount_amount": 50000,
        "platform_funding": 20000,
        "admin_funding": 30000
      }
    ],
    "created_at": "2026-02-28T10:00:00Z"
  }
}
```

### 3.2. ORDER_PAID

**Ngữ cảnh:** Payment Service xác nhận thanh toán thành công → Temporal Signal → FSM `PENDING_PAYMENT` → `PAID`. Xem [State Machine T2](order_state_machine.md#22-bảng-chi-tiết-transitions).
**Consumers chính:**
- **Cart Service:** Trigger [Batch Delete](../cart/cart_workflow.md#4-api-batch-delete-xóa-lô-sản-phẩm-sau-checkout-tĩnh) — cleanup theo `sku_ids` từ snapshot checkout (xóa thẳng SKU line khỏi giỏ).
- **Notification/CQRS/Analytics:** Đồng bộ trạng thái hiển thị và thông báo.

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174001",
  "event_type": "ORDER_PAID",
  "aggregate_type": "Order",
  "aggregate_id": "10001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T10:05:00Z",
  "source": "order-service",
  "payload": {
    "order_id": 10001,
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "status": "PAID",
    "order_version": 3,
    "payment_event_sequence_id": 20260228100500,
    "payment_recovery": false,
    "payment_method": "VNPAY",
    "payment_transaction_id": "TXN-ABC123",
    "cart_id": "{cart:user:550e8400-e29b-41d4-a716-446655440000}",
    "sku_ids": [
      "sku-001"
    ],
    "final_amount": 995000,
    "promotions_applied": [
      {
        "promotion_id": 100,
        "discount_amount": 50000,
        "platform_funding": 20000,
        "admin_funding": 30000
      }
    ],
    "items": [
      {
        "order_item_id": 2001,
        "platform_id": 501,
        "store_name_snapshot": "Apple Flagship Store",
        "warehouse_id": 5,
        "lines": [
          {"sku_id": "sku-001", "quantity": 2}
        ]
      }
    ],
    "checkout_submitted_at": "2026-02-28T10:00:00Z",
    "paid_at": "2026-02-28T10:05:00Z"
  }
}
```

> **Canonical user_id:** Order Service phát `user_id` dạng UUID theo chuẩn thống nhất toàn hệ thống. `cart_id` được build từ UUID user_id.

> [!IMPORTANT]
> `ORDER_PAID` là event mốc tài chính: được phát ngay khi workflow branch `PAYMENT_SUCCESS` commit thành công transition FSM `PENDING_PAYMENT` -> `PAID`.
> Các thao tác hậu thanh toán thuộc mốc vận hành (`DeductInventoryActivity`, `ConfirmPromotionActivity`, `CreateShippingLabelsActivity`) được theo dõi bằng `ORDER_STATUS_CHANGED` khi transition `PAID` -> `PROCESSING` hoàn tất.
>
> Trong lane khôi phục payment muộn, `ORDER_PAID` có thể được phát với `payment_recovery = true` sau khi order từng bị `ORDER_CANCELLED` vì `PAYMENT_TIMEOUT`.

### 3.3. ORDER_CANCELLED

**Ngữ cảnh:** Lane cancel pre-payment (`status = PENDING_PAYMENT`): timeout 15 phút / user cancel / admin cancel / payment failed. FSM -> `CANCELLED`. Xem [State Machine T3/T4/T4.1](order_state_machine.md#22-bảng-chi-tiết-transitions).
**Consumers chính:**
- **Payment Service:** Reconciliation/cancel pending payment transaction (nếu còn mở).
- **CQRS/Notification/Analytics:** Đồng bộ trạng thái hiển thị và thông báo.

> [!NOTE]
> Với timeout/user-cancel trong checkout saga, rollback Promotion/Inventory được thực thi đồng bộ qua Temporal Compensation Activities trước khi `ORDER_CANCELLED` được phát ra.
> Riêng `reason = PAYMENT_TIMEOUT` là trạng thái có thể được bù bằng lane khôi phục nếu Payment Service xác nhận thu tiền thành công đến muộn.

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174002",
  "event_type": "ORDER_CANCELLED",
  "aggregate_type": "Order",
  "aggregate_id": "10001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T10:20:00Z",
  "source": "order-service",
  "payload": {
    "order_id": 10001,
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "status": "CANCELLED",
    "order_version": 4,
    "previous_status": "PENDING_PAYMENT",
    "reason": "PAYMENT_TIMEOUT",
    "promotions_applied": [
      {"promotion_id": 100, "discount_amount": 50000, "platform_funding": 20000, "admin_funding": 30000}
    ],
    "items": [
      {
        "order_item_id": 2001,
        "warehouse_id": 5,
        "lines": [{"sku_id": "sku-001", "quantity": 2}]
      }
    ],
    "cancelled_at": "2026-02-28T10:20:00Z"
  }
}
```

> **Field `reason`:** `PAYMENT_TIMEOUT`, `USER_CANCEL`, `ADMIN_FORCE_CANCEL`, `PAYMENT_FAILED`.

### 3.4. ORDER_STATUS_CHANGED

**Ngữ cảnh:** Mọi transition FSM của Master Order (trừ CREATED, PAID, CANCELLED, COMPLETED đã có event riêng). VD: `PAID` -> `PROCESSING`, `PAID/PROCESSING/COMPLETED` -> `RETURN_REQUESTED`, `CANCELLED` -> `PAID` (late-payment recovery).
**Consumers chính:** CQRS Projection Worker, Notification Service.

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174003",
  "event_type": "ORDER_STATUS_CHANGED",
  "aggregate_type": "Order",
  "aggregate_id": "10001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T10:05:30Z",
  "source": "order-service",
  "payload": {
    "order_id": 10001,
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "buyer_tab": "TO_SHIP",
    "old_status": "PAID",
    "new_status": "PROCESSING",
    "order_version": 5,
    "changed_by": "system",
    "changed_by_type": "TEMPORAL",
    "reason": "POST_PAYMENT_SAGA_COMPLETE",
    "refund_trigger_source": null,
    "customer_return_request_id": null,
    "customer_return_guard_applied": false,
    "customer_return_policy": "CUSTOMER_RETURN_ONCE_V1",
    "changed_at": "2026-02-28T10:05:30Z"
  }
}
```

> **Canonical user_id:** Order Service phát `user_id` dạng UUID theo chuẩn thống nhất toàn hệ thống.

> **Reason contract mở rộng:** ngoài `POST_PAYMENT_SAGA_COMPLETE`, event này còn dùng cho lane refund/recovery với các reason như `ADMIN_FORCE_REFUND_REQUESTED`, `RETURN_API_ACCEPTED`, `INVENTORY_RETURN_CONFIRMED`, `LATE_PAYMENT_RECOVERED`.
>
> **Refund metadata contract (additive-only):** Khi `new_status = RETURN_REQUESTED`, producer nên phát thêm `refund_trigger_source` (`API_RETURN`/`ADMIN_FORCE_REFUND_REQUESTED`), `refund_waiting_inventory_confirmation` và các field policy one-shot để downstream phân tích hành vi trigger.

### 3.5. ORDER_ITEM_STATUS_CHANGED

**Ngữ cảnh:** Mỗi khi Order Item Shipping FSM transition (nhận từ Shipping Service webhook). Xem [Order Item Shipping FSM](order_state_machine.md#2-item-fsm-bảng-order_items).
**Consumers chính:** CQRS Projection Worker (update ES item status), Notification (gửi push notification tracking).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174004",
  "event_type": "ORDER_ITEM_STATUS_CHANGED",
  "aggregate_type": "OrderItem",
  "aggregate_id": "2001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T14:00:00Z",
  "source": "order-service",
  "payload": {
    "order_id": 10001,
    "order_item_id": 2001,
    "old_status": "HANDED_OVER",
    "new_status": "IN_TRANSIT",
    "item_version": 5,
    "shipment_event_sequence_id": 20260228140000,
    "tracking_number": "GHN12345",
    "courier_code": "GHN",
    "standardized_status": "IN_TRANSIT",
    "estimated_delivery_date": "2026-03-02",
    "changed_at": "2026-02-28T14:00:00Z"
  }
}
```

> **Ordering contract cho consumers:** Topic vẫn partition theo `order_id` để giữ lifecycle theo đơn, nhưng CQRS/Notification consumers phải guard theo `order_item_id + item_version` và ưu tiên `shipment_event_sequence_id` hơn timestamp để chống out-of-order.

### 3.6. ORDER_REFUNDED

**Ngữ cảnh:** Lane refund hoàn tất thành công (return sau giao hàng hoặc admin force refund). FSM `RETURN_REQUESTED` -> `REFUNDED`. Xem [State Machine](order_state_machine.md#22-bảng-chi-tiết-transitions).
**Consumers chính:**
- **Promotion Service:** Trigger [Refund Budget](../promotion/promotion_workflow.md#5-luồng-đổi-trả-hàng--hoàn-tiền-refund-flow).
- **Inventory Service:** Không restock trực tiếp từ event này; Inventory restock vật lý theo `SHIPMENT_RETURNED` khi hàng về kho.

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174005",
  "event_type": "ORDER_REFUNDED",
  "aggregate_type": "Order",
  "aggregate_id": "10001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-03-05T10:00:00Z",
  "source": "order-service",
  "payload": {
    "order_id": 10001,
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "status": "REFUNDED",
    "order_version": 11,
    "refund_event_sequence_id": 20260305100000,
    "refund_transaction_id": "RFD-DEF456",
    "single_refund_transaction": true,
    "refund_trigger_source": "API_RETURN",
    "customer_return_request_id": "fdf4d7d3-4d35-4a61-a55a-8d2b1a315b6a",
    "customer_return_guard_applied": true,
    "customer_return_policy": "CUSTOMER_RETURN_ONCE_V1",
    "refund_type": "FULL",
    "refund_scope_locked_at": "2026-03-05T09:59:40Z",
    "returned_item_ids": [2001],
    "inventory_return_confirmed_at": "2026-03-05T09:58:30Z",
    "refund_amount": 995000,
    "promotions_applied": [
      {"promotion_id": 100, "discount_amount": 50000, "platform_funding": 20000, "admin_funding": 30000}
    ],
    "promotion_refund_allocations": [
      {
        "promotion_id": 100,
        "promotion_scope_type": "platform",
        "scope_admin_id": 501,
        "refunded_discount_amount": 50000,
        "platform_funding_refund": 20000,
        "admin_funding_refund": 30000,
        "partial_restore_rule": "RESTORE_ONLY_IF_FULL_platform_COVERED",
        "voucher_restore_mode": "BEST_EFFORT_NON_BLOCKING"
      }
    ],
    "returned_items": [
      {
        "order_item_id": 2001,
        "lines": [
          {
            "product_id": "prod-001",
            "sku_id": "sku-001",
            "variant_name": "Mau Den, Size L",
            "quantity": 2,
            "line_subtotal": 1045000,
            "line_discount_allocated": 50000
          }
        ]
      }
    ],
    "refunded_at": "2026-03-05T10:00:00Z"
  }
}
```

> **Canonical user_id:** Order Service phát `user_id` dạng UUID theo chuẩn thống nhất toàn hệ thống.
> **Field `refund_type`:** `FULL` (toàn bộ đơn hàng) hoặc `PARTIAL` (1 số items).
>
> **Contract bắt buộc cho idempotency liên service:** `refund_transaction_id` phải luôn có trong payload để downstream dedup đúng một giao dịch hoàn tiền.
>
> **Contract deterministic cho Promotion:** Với `refund_type = PARTIAL`, Order phải phát `promotion_refund_allocations[]` để chỉ rõ số tiền hoàn theo từng promotion. Promotion **không** tự suy luận từ tỷ lệ item khi thiếu allocation.
>
> **Partial voucher policy:** `PARTIAL` chỉ hoàn voucher `platform` khi refund scope bao phủ toàn bộ item của platform đó trong order. `PLATFORM` và `SHIPPING` allocation trong partial phải bằng `0`.
>
> **Wallet validity contract:** Luồng restore voucher phía Promotion phải tôn trọng `user_coupon_wallets.expired_at` (mặc định 30 ngày từ lúc sở hữu). Chỉ restore `USED -> SAVED` khi còn hạn; nếu quá hạn thì chuyển `EXPIRED` hoặc giữ `USED` theo trạng thái thực tế.
>
> **One-shot scope:** Chính sách one-shot chỉ áp khi `refund_trigger_source = API_RETURN`. Trigger từ admin có thể không dùng guard này (`customer_return_guard_applied = false`).
>
> **Policy B invariant:** Mỗi `order_id` chỉ có một `refund_transaction_id` do Order khởi tạo. `SHIPMENT_RETURNED` không tạo giao dịch refund.
>
> **Non-blocking invariant:** Dù restore voucher thành `SAVED`/`EXPIRED`/`USED` hay gặp `VOUCHER_UNAVAILABLE`, transaction hoàn tiền ở Payment không bị rollback.

### 3.7. ORDER_COMPLETED

**Ngữ cảnh:** Tất cả items `DELIVERED`. FSM `PROCESSING` → `COMPLETED`. Tự động bởi [Auto-Complete Logic](order_state_machine.md#31-auto-complete-logic-processing--completed).
**Consumers chính:** Analytics, admin Settlement, Review Service.

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174006",
  "event_type": "ORDER_COMPLETED",
  "aggregate_type": "Order",
  "aggregate_id": "10001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-03-02T16:00:00Z",
  "source": "order-service",
  "payload": {
    "order_id": 10001,
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "status": "COMPLETED",
    "order_version": 9,
    "final_amount": 995000,
    "item_count": 2,
    "items": [
      {
        "product_id": "prod-001",
        "sku_id": "sku-001",
        "variant_name": "Mau Den, Size L",
        "quantity": 2
      }
    ],
    "completed_at": "2026-03-02T16:00:00Z"
  }
}
```

> **Canonical user_id:** Order Service phát `user_id` dạng UUID theo chuẩn thống nhất toàn hệ thống.

> **Contract note cho Review Service:** `items[]` là bắt buộc trong `ORDER_COMPLETED` để Review cấp quyền đánh giá theo từng item đã mua.

> **Field-level contract cho Review consumer:**
> - `user_id` là UUID theo chuẩn thống nhất.
> - Trong `items[]`, `product_id` và `sku_id` là bắt buộc.
> - `variant_name` có thể null ở một số case legacy; Review phải log warning nhưng không drop toàn bộ event.

### 3.8. ORDER_PRODUCT_METRICS_UPDATED

**Ngữ cảnh:** Internal Order Metrics Projector aggregate từ luồng `ORDER_COMPLETED` và phát delta/hợp nhất theo `product_id` để Search sync `product_sold_count` theo đúng partition key.
**Consumers chính:** Search Service (`search-sales-metrics-sync`).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174016",
  "event_type": "ORDER_PRODUCT_METRICS_UPDATED",
  "aggregate_type": "ProductSalesMetrics",
  "aggregate_id": "prod-001",
  "correlation_id": "metrics-20260302-160000",
  "timestamp": "2026-03-02T16:00:01Z",
  "source": "order-service",
  "payload": {
    "product_id": "prod-001",
    "total_sold": 15200,
    "delta_sold": 2,
    "event_sequence_id": 1740931201000,
    "updated_at": "2026-03-02T16:00:01Z"
  }
}
```

> **Ordering contract:** `event_sequence_id` phải đơn điệu theo từng `product_id` để downstream guard out-of-order an toàn.

### 3.9. ORDER_REFUND_FAILED

**Ngữ cảnh:** Payment trả `REFUND_FAILED` hoặc `REFUND_REVERSED` trong lane refund (bao gồm return flow và admin force refund). Order giữ FSM ở `RETURN_REQUESTED` và phát event escalation để Notification/Ops xử lý.
**Consumers chính:** Notification Service (critical lane), Ops Dashboard.

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174006b",
  "event_type": "ORDER_REFUND_FAILED",
  "aggregate_type": "Order",
  "aggregate_id": "10001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-03-05T10:10:00Z",
  "source": "order-service",
  "payload": {
    "order_id": 10001,
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "status": "RETURN_REQUESTED",
    "payment_status": "REFUND_FAILED",
    "order_version": 10,
    "refund_event_sequence_id": 20260305101000,
    "payment_transaction_id": "TXN-ABC123",
    "refund_transaction_id": "RFD-DEF456",
    "refund_trigger_source": "API_RETURN",
    "customer_return_request_id": "fdf4d7d3-4d35-4a61-a55a-8d2b1a315b6a",
    "customer_return_guard_applied": true,
    "customer_return_policy": "CUSTOMER_RETURN_ONCE_V1",
    "reason": "REFUND_WINDOW_EXPIRED",
    "error_message": "Cong thanh toan tu choi hoan",
    "failed_at": "2026-03-05T10:10:00Z"
  }
}
```

> **Canonical user_id:** Order Service phát `user_id` dạng UUID theo chuẩn thống nhất toàn hệ thống.

> Khi event là `ORDER_REFUND_FAILED` từ lane `API_RETURN`, policy one-shot vẫn giữ nguyên; Buyer API không được mở lại quyền submit mới cho cùng order.

### 3.10. ORDER_ARCHIVE_BATCH_FAILED

**Ngữ cảnh:** Cold Data Archiver thất bại ở một stage của batch archive và cần trigger replay.
**Topic:** `t.order.archive.retry`
**Consumers chính:** Internal `order-archive-replay-consumer`.

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174015",
  "event_type": "ORDER_ARCHIVE_BATCH_FAILED",
  "aggregate_type": "ArchiveBatch",
  "aggregate_id": "ARCHIVE-20260331-030000",
  "correlation_id": "archive-20260331-030000-attempt-1",
  "timestamp": "2026-03-31T03:02:10Z",
  "source": "order-service",
  "payload": {
    "archive_batch_id": "ARCHIVE-20260331-030000",
    "attempt": 1,
    "failure_stage": "VERIFY",
    "failed_order_count": 37,
    "error_code": "S3_CHECKSUM_MISMATCH",
    "error_message": "Checksum verification failed after upload",
    "failed_at": "2026-03-31T03:02:10Z"
  }
}
```

> **Design rule:** Event chỉ mang metadata batch (`archive_batch_id`, `attempt`, `failure_stage`), không nhét danh sách `order_id`; replay consumer sẽ đọc membership từ `archive_batch_items`.

---

## 4. Chi Tiết Các Loại Event Tiêu Thụ (Consumed Events)

### 4.1. PAYMENT_SUCCESS (Từ Payment Service)

**Topic:** `t.payment.events`
**Consumer Group:** `order-payment-consumer`
**Xử lý bởi:** [Payment Status Consumer Worker](order_workers.md#4-payment-status-consumer-kafka-consumer)

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174007",
  "event_type": "PAYMENT_SUCCESS",
  "aggregate_type": "Payment",
  "aggregate_id": "TXN-ABC123",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T10:05:00Z",
  "source": "payment-service",
  "payload": {
    "payment_transaction_id": "TXN-ABC123",
    "order_id": 10001,
    "payment_event_sequence_id": 20260228100500,
    "amount": 995000,
    "provider": "VNPAY",
    "provider_transaction_id": "VNP-XYZ789",
    "paid_at": "2026-02-28T10:05:00Z"
  }
}
```
**Logic xử lý:** Worker nhận event → Signal Temporal Workflow (bằng `WorkflowStub.signal("PaymentResult", SUCCESS)`) → Temporal resume → commit FSM `PENDING_PAYMENT` -> `PAID` + produce `ORDER_PAID` (mốc tài chính) → chạy Post-Payment Saga → `UpdateOrderStatusActivity` chuyển `PAID` -> `PROCESSING` và produce `ORDER_STATUS_CHANGED` (mốc vận hành).

Nếu checkout workflow đã đóng timeout và order đang `CANCELLED(PAYMENT_TIMEOUT)`, worker phải start `OrderLatePaymentRecoveryWorkflow` thay vì drop signal thành stale.

> **Routing rule bắt buộc:**
> - `PAYMENT_SUCCESS|PAYMENT_FAILED|PAYMENT_EXPIRED`: lookup `checkout_token` theo `payload.order_id` rồi signal `order-checkout-{checkout_token}`.
> - Nếu `PAYMENT_SUCCESS` không signal được do checkout workflow đã close timeout, route sang `order-late-payment-recovery-{order_id}`.
> - `REFUND_PROCESSING|REFUND_SUCCESS|REFUND_FAILED|REFUND_REVERSED`: signal trực tiếp `order-refund-{order_id}` theo `payload.order_id`.
> - Không route theo `aggregate_id` vì aggregate của Payment là transaction-level.

> **Ordering contract payment stream:** `payment_event_sequence_id`/`refund_event_sequence_id` phải đơn điệu theo từng `order_id` để Order/CQRS xử lý an toàn khi nhận event đồng thời hoặc replay.

### 4.2. PAYMENT_FAILED (Từ Payment Service)

**Topic:** `t.payment.events`
**Consumer Group:** `order-payment-consumer`

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174008",
  "event_type": "PAYMENT_FAILED",
  "aggregate_type": "Payment",
  "aggregate_id": "TXN-ABC123",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T10:06:00Z",
  "source": "payment-service",
  "payload": {
    "payment_transaction_id": "TXN-ABC123",
    "order_id": 10001,
    "payment_event_sequence_id": 20260228100600,
    "error_code": "INSUFFICIENT_BALANCE",
    "error_message": "Số dư không đủ",
    "failed_at": "2026-02-28T10:06:00Z"
  }
}
```
**Logic xử lý:** Worker signal checkout workflow (`PaymentResult.FAILED`) -> Temporal chạy compensation stack pre-payment (`CancelPayment` -> `RollbackPromotion` -> `RollbackInventory` -> `CancelOrder`) -> sau khi compensation hoàn tất mới commit FSM `PENDING_PAYMENT` -> `CANCELLED` và produce `ORDER_CANCELLED`.

### 4.3. PAYMENT_EXPIRED (Từ Payment Service)

**Topic:** `t.payment.events`
**Consumer Group:** `order-payment-consumer`

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174009",
  "event_type": "PAYMENT_EXPIRED",
  "aggregate_type": "Payment",
  "aggregate_id": "TXN-ABC123",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T10:15:00Z",
  "source": "payment-service",
  "payload": {
    "payment_transaction_id": "TXN-ABC123",
    "order_id": 10001,
    "payment_event_sequence_id": 20260228101500,
    "expired_at": "2026-02-28T10:15:00Z"
  }
}
```
**Logic xử lý:** Tương tự `PAYMENT_FAILED`. Temporal Timer cũng sẽ tự timeout song song — event nào đến trước xử lý trước, event đến sau bị idempotency check bỏ qua.

### 4.4. REFUND_PROCESSING (Từ Payment Service)

**Topic:** `t.payment.events`
**Consumer Group:** `order-payment-consumer`

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174009b",
  "event_type": "REFUND_PROCESSING",
  "aggregate_type": "Payment",
  "aggregate_id": "RFD-DEF456",
  "correlation_id": "wf-10001",
  "timestamp": "2026-03-05T09:00:05Z",
  "source": "payment-service",
  "payload": {
    "payment_transaction_id": "TXN-ABC123",
    "refund_transaction_id": "RFD-DEF456",
    "order_id": 10001,
    "refund_event_sequence_id": 20260305090005,
    "refund_amount": 995000,
    "processing_at": "2026-03-05T09:00:05Z"
  }
}
```

### 4.5. REFUND_SUCCESS (Từ Payment Service)

**Topic:** `t.payment.events`
**Consumer Group:** `order-payment-consumer`

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174010",
  "event_type": "REFUND_SUCCESS",
  "aggregate_type": "Payment",
  "aggregate_id": "RFD-DEF456",
  "correlation_id": "wf-10001",
  "timestamp": "2026-03-05T10:00:00Z",
  "source": "payment-service",
  "payload": {
    "payment_transaction_id": "TXN-ABC123",
    "refund_transaction_id": "RFD-DEF456",
    "order_id": 10001,
    "refund_event_sequence_id": 20260305100000,
    "refund_amount": 995000,
    "refunded_at": "2026-03-05T10:00:00Z"
  }
}
```
**Logic xử lý:** Worker route theo `order_id` và signal `OrderRefundWorkflow` (`RefundResult.SUCCESS`) -> refund workflow commit `orders.payment_status = 'REFUNDED'`, FSM `RETURN_REQUESTED` -> `REFUNDED`, rồi produce `ORDER_REFUNDED` (bắt buộc kèm `refund_transaction_id` + `promotion_refund_allocations[]`). Flow này chỉ xảy ra sau khi workflow đã nhận signal `INVENTORY_RETURN_RECEIVED_ALL`. Worker không reset one-shot guard của Buyer API.

### 4.6. REFUND_FAILED (Từ Payment Service)

**Topic:** `t.payment.events`
**Consumer Group:** `order-payment-consumer`

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174010b",
  "event_type": "REFUND_FAILED",
  "aggregate_type": "Payment",
  "aggregate_id": "RFD-DEF456",
  "correlation_id": "wf-10001",
  "timestamp": "2026-03-05T10:10:00Z",
  "source": "payment-service",
  "payload": {
    "payment_transaction_id": "TXN-ABC123",
    "refund_transaction_id": "RFD-DEF456",
    "order_id": 10001,
    "refund_event_sequence_id": 20260305101000,
    "error_code": "REFUND_WINDOW_EXPIRED",
    "error_message": "Cổng thanh toán từ chối hoàn",
    "failed_at": "2026-03-05T10:10:00Z"
  }
}
```
**Logic xử lý:** Worker signal `OrderRefundWorkflow` (`RefundResult.FAILED`) -> refund workflow commit `orders.payment_status = 'REFUND_FAILED'`, giữ FSM ở `RETURN_REQUESTED`, phát `ORDER_REFUND_FAILED` cho Notification/Ops và escalation lane. Nếu trigger ban đầu là `API_RETURN`, policy one-shot vẫn giữ nguyên.

### 4.7. REFUND_REVERSED (Từ Payment Service - Optional)

**Topic:** `t.payment.events`
**Consumer Group:** `order-payment-consumer`

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174010c",
  "event_type": "REFUND_REVERSED",
  "aggregate_type": "Payment",
  "aggregate_id": "RFD-DEF456",
  "correlation_id": "wf-10001",
  "timestamp": "2026-03-06T08:00:00Z",
  "source": "payment-service",
  "payload": {
    "refund_transaction_id": "RFD-DEF456",
    "payment_transaction_id": "TXN-ABC123",
    "order_id": 10001,
    "refund_event_sequence_id": 20260306080000,
    "reason_code": "REFUND_REVERSED_BY_PROVIDER",
    "reason_message": "Destination account invalid",
    "reversed_at": "2026-03-06T08:00:00Z"
  }
}
```
**Logic xử lý:** Worker signal `OrderRefundWorkflow` (`RefundResult.REVERSED`) -> refund workflow commit `orders.payment_status = 'REFUND_REVERSED'`, giữ FSM ở `RETURN_REQUESTED`, phát `ORDER_REFUND_FAILED` với reason escalation cho Notification/Ops.

### 4.7. SHIPMENT_STATUS_UPDATED (Từ Shipping Service)

**Topic:** `t.shipping.events`
**Consumer Group:** `order-shipping-consumer`
**Xử lý bởi:** [Shipping Status Consumer Worker](order_workers.md#5-shipping-status-consumer-kafka-consumer)

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174011",
  "event_type": "SHIPMENT_STATUS_UPDATED",
  "aggregate_type": "Shipment",
  "aggregate_id": "3001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-03-02T16:00:00Z",
  "source": "shipping-service",
  "payload": {
    "shipment_id": 3001,
    "order_item_id": 2001,
    "order_id": 10001,
    "shipment_event_sequence_id": 20260302160000,
    "old_status": "IN_TRANSIT",
    "new_status": "DELIVERED",
    "standardized_status": "DELIVERED",
    "tracking_number": "GHN12345",
    "courier_code": "GHN",
    "occurred_at": "2026-03-02T16:00:00Z"
  }
}
```
**Logic xử lý:** Worker nhận event → Update `order_items.status` theo [mapping table](order_state_machine.md#23-mapping-với-shipping-service-standardized_status) → Produce `ORDER_ITEM_STATUS_CHANGED` → Kiểm tra [Auto-Complete](order_state_machine.md#31-auto-complete-logic-processing--completed).

> **Ordering contract shipping stream:** `shipment_event_sequence_id` phải tăng dần theo `order_item_id`; khi sequence mới hơn mâu thuẫn với timestamp cũ hơn thì vẫn ưu tiên sequence.

### 4.8. SHIPMENT_DELIVERED (Từ Shipping Service)

**Topic:** `t.shipping.events`
**Consumer Group:** `order-shipping-consumer`

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174012",
  "event_type": "SHIPMENT_DELIVERED",
  "aggregate_type": "Shipment",
  "aggregate_id": "3001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-03-02T16:00:00Z",
  "source": "shipping-service",
  "payload": {
    "shipment_id": 3001,
    "order_item_id": 2001,
    "order_id": 10001,
    "shipment_event_sequence_id": 20260302160000,
    "tracking_number": "GHN12345",
    "delivered_at": "2026-03-02T16:00:00Z"
  }
}
```
**Logic xử lý:** Worker map trực tiếp item sang `DELIVERED`, ghi lịch sử trạng thái, phát `ORDER_ITEM_STATUS_CHANGED`, sau đó chạy auto-complete CAS cho master order.

### 4.9. SHIPMENT_RETURNED (Từ Shipping Service)

**Topic:** `t.shipping.events`
**Consumer Group:** `order-shipping-consumer`

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174013",
  "event_type": "SHIPMENT_RETURNED",
  "aggregate_type": "Shipment",
  "aggregate_id": "3001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-03-04T10:00:00Z",
  "source": "shipping-service",
  "payload": {
    "shipment_id": 3001,
    "order_item_id": 2001,
    "order_id": 10001,
    "shipment_event_sequence_id": 20260304100000,
    "tracking_number": "GHN12345",
    "return_reason": "DELIVERY_FAILED_3_TIMES",
    "returned_at": "2026-03-04T10:00:00Z"
  }
}
```
**Logic xử lý:** Worker map item sang `RETURNED` và ghi lịch sử shipping/item như bình thường. Event này **không** được dùng để mở `OrderRefundWorkflow`.

`SHIPMENT_RETURNED` phục vụ item timeline + restock vật lý tại Inventory. Quyết định mở bước gọi Payment refund được điều khiển bởi signal tổng kết từ Inventory (`INVENTORY_RETURN_RECEIVED_ALL`).

### 4.10. INVENTORY_RETURN_RECEIVED_ALL (Từ Inventory Service)

**Topic:** `t.inventory.events`
**Consumer Group:** `order-inventory-return-consumer`

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174013b",
  "event_type": "INVENTORY_RETURN_RECEIVED_ALL",
  "aggregate_type": "Inventory",
  "aggregate_id": "10001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-03-05T09:58:30Z",
  "source": "inventory-service",
  "payload": {
    "order_id": 10001,
    "refund_request_id": "fdf4d7d3-4d35-4a61-a55a-8d2b1a315b6a",
    "required_order_item_ids": [2001],
    "received_order_item_ids": [2001],
    "all_items_received": true,
    "confirmed_at": "2026-03-05T09:58:30Z"
  }
}
```

**Logic xử lý:** Worker kiểm tra idempotency rồi signal `InventoryReturnReceivedAll` cho `OrderRefundWorkflow` theo `order_id`. Chỉ sau signal này workflow mới được gọi Payment refund API.

### 4.11. SHIPMENT_DELAYED (Từ Shipping Service)

**Topic:** `t.shipping.events`
**Consumer Group:** `order-shipping-consumer`

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174014",
  "event_type": "SHIPMENT_DELAYED",
  "aggregate_type": "Shipment",
  "aggregate_id": "3001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-03-03T16:00:00Z",
  "source": "shipping-service",
  "payload": {
    "shipment_id": 3001,
    "order_item_id": 2001,
    "order_id": 10001,
    "shipment_event_sequence_id": 20260303160000,
    "tracking_number": "GHN12345",
    "hours_since_update": 56,
    "escalation_level": 1,
    "detected_at": "2026-03-03T16:00:00Z"
  }
}
```
**Logic xử lý:** Worker không đổi item terminal status, chỉ ghi metadata SLA; nếu `escalation_level >= 2` thì phát `ORDER_STATUS_CHANGED` reason `SHIPMENT_DELAYED_ESCALATED` cho CQRS/Ops.

### 4.12. ORDER_ARCHIVE_BATCH_FAILED (Internal Retry Trigger)

**Topic:** `t.order.archive.retry`
**Consumer Group:** `order-archive-replay-consumer`

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174015",
  "event_type": "ORDER_ARCHIVE_BATCH_FAILED",
  "aggregate_type": "ArchiveBatch",
  "aggregate_id": "ARCHIVE-20260331-030000",
  "timestamp": "2026-03-31T03:02:10Z",
  "source": "order-service",
  "payload": {
    "archive_batch_id": "ARCHIVE-20260331-030000",
    "attempt": 1,
    "failure_stage": "VERIFY",
    "failed_order_count": 37,
    "error_code": "S3_CHECKSUM_MISMATCH"
  }
}
```

**Logic xử lý:** Replay consumer đọc membership từ `archive_batch_items` theo `archive_batch_id`, không lấy danh sách `order_id` trực tiếp từ event payload. Nếu replay vẫn fail, consumer ghi một record outbox `ORDER_ARCHIVE_BATCH_FAILED` mới để CDC publish lại theo backoff.

---

## 5. Schema Versioning & Backward Compatibility

*   **Backward Compatibility Rule:** Chỉ được phép **thêm field mới**. Không được xóa hoặc đổi tên field hiện có.
*   **Consumer Resilience:** Tất cả consumers PHẢI ignore unknown fields.
*   **Idempotency:** Mỗi event mang `event_id` unique. Downstream consumers dùng `SETNX` (fast-path) + bảng `order_event_idempotency` (durable guard) để chống xử lý trùng.
