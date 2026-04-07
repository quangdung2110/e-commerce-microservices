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
*   [Payment Events](../payment/payment_events.md) — Order consume `PAYMENT_SUCCESS`, `PAYMENT_FAILED`, `PAYMENT_EXPIRED`, `REFUND_SUCCESS`, `REFUND_FAILED`, `REFUND_REVERSED`
*   [Shipping Events](../shipping/shipping_events.md) — Order consume `SHIPMENT_STATUS_UPDATED`, `SHIPMENT_DELIVERED`, `SHIPMENT_RETURNED`, `SHIPMENT_DELAYED`
*   [Inventory Events](../inventory/inventory_events.md) — Inventory consume Reserve/Deduct/Release commands
*   [Cart Events](../cart/cart_events.md) — Cart consume `ORDER_PAID` để batch delete SKU lines trong giỏ

---

Tài liệu này định nghĩa cấu trúc tiêu chuẩn của tất cả các luồng sự kiện **phát đi (Produce)** từ Order Service và **tiêu thụ (Consume)** từ các service khác.

Order Service là **Orchestrator trung tâm** — nó produce nhiều event nhất và consume từ nhiều nguồn nhất trong hệ thống. Tất cả events đi qua [Outbox Pattern](order_db_design.md) để đảm bảo At-Least-Once Delivery.

## 1. Bản Đồ Kafka Topics

### 1.1 Topics mà Order Service **Produce** (Phát đi)

| Topic Name | Partition Key | Consumers chính | Mô tả |
|---|---|---|---|
| `t.order.events` | `order_id` | Promotion, Inventory, Cart, CQRS Projection Worker (internal), Notification, Analytics, Review | Topic chính chứa mọi thay đổi trạng thái đơn hàng |
| `t.search.metrics.events` | `product_id` | Search Service | Topic metrics chuyên dụng cho projection realtime theo product, được phát bởi internal Order Metrics Projector thuộc Order Service |
| `t.order.cqrs.retry` | `order_id` | Internal `order-cqrs-retry-replayer` | Parking lot cho CQRS projector để replay/self-heal sau backoff |
| `t.order.cqrs.dlq` | `order_id` | Ops / Internal triage tools | Dead-letter cho CQRS projector khi lỗi permanent/contract violation |

> **Ghi chú Partition Key:** Sử dụng `order_id` đảm bảo tất cả events của cùng 1 đơn hàng luôn vào đúng 1 Partition → **Strict Ordering** cho từng đơn. Promotion/Inventory cần ordering chính xác để không bị Redeem trước Cancel.

### 1.2 Topics mà Order Service **Consume** (Lắng nghe)

| Topic Name | Consumer Group | Producer | Events | Mô tả |
|---|---|---|---|---|
| `t.payment.events` | `order-payment-consumer` | Payment Service | `PAYMENT_SUCCESS`, `PAYMENT_FAILED`, `PAYMENT_EXPIRED`, `REFUND_SUCCESS`, `REFUND_FAILED`, `REFUND_REVERSED` | Nhận kết quả thanh toán / hoàn tiền |
| `t.shipping.events` | `order-shipping-consumer` | Shipping Service | `SHIPMENT_STATUS_UPDATED`, `SHIPMENT_DELIVERED`, `SHIPMENT_RETURNED`, `SHIPMENT_DELAYED` | Nhận cập nhật trạng thái vận chuyển |
| `t.order.cqrs.retry` | `order-cqrs-retry-replayer` | Order Service (internal projector) | Envelope chứa original event + retry metadata | Replay sự kiện CQRS sau backoff/self-heal |

## 2. Cấu Trúc Message Header Chung (CloudEvents Wrapper)

Tất cả messages phát lên Kafka đều tuân theo chuẩn CloudEvents và được [Outbox Polling Worker](order_workers.md#1-outbox-polling-worker) bổ sung:

*   `event_id`: UUID của sự kiện (từ `outbox_events.id`).
*   `event_type`: Loại sự kiện (VD: `ORDER_CREATED`, `ORDER_PAID`).
*   `aggregate_type`: Với **produced events** của Order: `Order`, `OrderParcel`, hoặc `ProductSalesMetrics`. Với **consumed events**, giữ nguyên giá trị từ producer gốc (ví dụ `Payment`).
*   `aggregate_id`: ID theo `aggregate_type` (ví dụ `order_id`, `parcel_id`, `payment_transaction_id`, `refund_transaction_id`, `product_id`).
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
| `PARCEL_STATUS_CHANGED` | `parcel_version`, `shipment_event_sequence_id` | Parcel/shipping stream theo `parcel_id` |
| `ORDER_REFUNDED`, `ORDER_REFUND_FAILED` | `refund_event_sequence_id` | Refund stream |
| Payment-derived updates | `payment_event_sequence_id` | Payment stream |

> Guard keys này phục vụ CQRS projection và replay logic. Không suy luận thứ tự event bằng UUID `event_id` theo từ điển.

## 3. Chi Tiết Các Loại Event Phát Đi (Produced Events)

### 3.1. ORDER_CREATED

**Ngữ cảnh:** Checkout Service submit → Temporal Workflow tạo Master Order + Parcels + Items thành công. Trạng thái: `CREATED` → `PENDING_PAYMENT`. Xem [Workflow Checkout Submit](order_workflow.md#4-workflow-nhận-checkout-submit).
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
    "user_id": 999,
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
    "parcels": [
      {
        "parcel_id": 2001,
        "warehouse_id": 5,
        "shipping_fee": 25000,
        "courier_code": "GHN",
        "status": "PREPARING",
        "items": [
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
        "parcel_id": 2002,
        "warehouse_id": 8,
        "shipping_fee": 20000,
        "courier_code": "NINJAVAN",
        "status": "PREPARING",
        "items": [...]
      }
    ],
    "promotions_applied": [
      {
        "promotion_id": 100,
        "promotion_type": "CART_DISCOUNT",
        "discount_amount": 50000,
        "platform_funding": 20000,
        "seller_funding": 30000
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
    "user_id": 999,
    "status": "PAID",
    "order_version": 3,
    "payment_event_sequence_id": 20260228100500,
    "payment_method": "VNPAY",
    "payment_transaction_id": "TXN-ABC123",
    "cart_id": "{cart:user:999}",
    "items": [
      {"sku_id": "sku-001", "quantity": 2}
    ],
    "sku_ids": [
      "sku-001"
    ],
    "final_amount": 995000,
    "promotions_applied": [
      {
        "promotion_id": 100,
        "discount_amount": 50000,
        "platform_funding": 20000,
        "seller_funding": 30000
      }
    ],
    "parcels": [
      {
        "parcel_id": 2001,
        "warehouse_id": 5,
        "items": [
          {"sku_id": "sku-001", "quantity": 2}
        ]
      }
    ],
    "checkout_submitted_at": "2026-02-28T10:00:00Z",
    "paid_at": "2026-02-28T10:05:00Z"
  }
}
```

> **Contract ổn định cho Cart delete:** Truyền `cart_id` theo đúng format Redis key (`{cart:user:{user_id}}`) và mảng `sku_ids` để Cart xóa thẳng các SKU đã mua khỏi giỏ.

> **Kiểu dữ liệu `user_id`:** Producer phát `user_id` dạng số (`number`) theo domain Order. Consumer phía Cart/Redis phải normalize sang chuỗi trước khi build key Redis.

> **Mốc thời gian:** `checkout_submitted_at` và `paid_at` được giữ cho timeline/audit/analytics. Cart cleanup không phụ thuộc timestamp, chỉ dùng `cart_id` + `sku_ids` snapshot.

> **Field Subset cho Cart Consumer:** Cart Service chỉ dùng `cart_id` + `sku_ids`. Các field `items[]`, `parcels[]` và `quantity` phục vụ consumer khác (inventory/analytics/notification), **không** tham gia logic cart cleanup.

> [!IMPORTANT]
> `ORDER_PAID` là event thông báo trạng thái sau khi Post-Payment Saga đã hoàn tất. Các thao tác giao dịch lõi như `DeductInventoryActivity` và `ConfirmPromotionActivity` được thực thi đồng bộ trước khi event này được phát ra.

### 3.3. ORDER_CANCELLED

**Ngữ cảnh:** Timeout 15 phút / User cancel / Admin force cancel. FSM → `CANCELLED`. Xem [State Machine T3/T4/T7](order_state_machine.md#22-bảng-chi-tiết-transitions).
**Consumers chính:**
- **Payment Service:** Trigger Auto-Refund nếu đã thanh toán (T7 case).
- **CQRS/Notification/Analytics:** Đồng bộ trạng thái hiển thị và thông báo.

> [!NOTE]
> Với timeout/user-cancel trong checkout saga, rollback Promotion/Inventory được thực thi đồng bộ qua Temporal Compensation Activities trước khi `ORDER_CANCELLED` được phát ra.

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
    "user_id": 999,
    "status": "CANCELLED",
    "order_version": 4,
    "previous_status": "PENDING_PAYMENT",
    "reason": "PAYMENT_TIMEOUT",
    "promotions_applied": [
      {"promotion_id": 100, "discount_amount": 50000, "platform_funding": 20000, "seller_funding": 30000}
    ],
    "parcels": [
      {
        "parcel_id": 2001,
        "warehouse_id": 5,
        "items": [{"sku_id": "sku-001", "quantity": 2}]
      }
    ],
    "cancelled_at": "2026-02-28T10:20:00Z"
  }
}
```

> **Field `reason`:** `PAYMENT_TIMEOUT`, `USER_CANCEL`, `ADMIN_FORCE_CANCEL`, `PAYMENT_FAILED`.

### 3.4. ORDER_STATUS_CHANGED

**Ngữ cảnh:** Mọi transition FSM của Master Order (trừ CREATED, PAID, CANCELLED đã có event riêng). VD: `PAID` → `PROCESSING`, `PROCESSING` → `COMPLETED`.
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
    "user_id": 999,
    "old_status": "PAID",
    "new_status": "PROCESSING",
    "order_version": 5,
    "changed_by": "system",
    "changed_by_type": "TEMPORAL",
    "reason": "POST_PAYMENT_SAGA_COMPLETE",
    "changed_at": "2026-02-28T10:05:30Z"
  }
}
```

### 3.5. PARCEL_STATUS_CHANGED

**Ngữ cảnh:** Mỗi khi Parcel FSM transition (nhận từ Shipping Service webhook). Xem [Parcel FSM](order_state_machine.md#2-parcel-fsm-bảng-order_parcels).
**Consumers chính:** CQRS Projection Worker (update ES parcel status), Notification (gửi push notification tracking).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174004",
  "event_type": "PARCEL_STATUS_CHANGED",
  "aggregate_type": "OrderParcel",
  "aggregate_id": "2001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T14:00:00Z",
  "source": "order-service",
  "payload": {
    "order_id": 10001,
    "parcel_id": 2001,
    "old_status": "HANDED_OVER",
    "new_status": "IN_TRANSIT",
    "parcel_version": 5,
    "shipment_event_sequence_id": 20260228140000,
    "tracking_number": "GHN12345",
    "courier_code": "GHN",
    "estimated_delivery_date": "2026-03-02",
    "changed_at": "2026-02-28T14:00:00Z"
  }
}
```

> **Ordering contract cho consumers:** Topic vẫn partition theo `order_id` để giữ lifecycle theo đơn, nhưng CQRS/Notification consumers phải guard theo `parcel_id + parcel_version` và ưu tiên `shipment_event_sequence_id` hơn timestamp để chống out-of-order.

### 3.6. ORDER_REFUNDED

**Ngữ cảnh:** Đơn hàng đã giao thành công nhưng khách đổi trả hàng. FSM `RETURN_REQUESTED` → `REFUNDED`. Xem [State Machine T9](order_state_machine.md#22-bảng-chi-tiết-transitions).
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
    "user_id": 999,
    "status": "REFUNDED",
    "order_version": 11,
    "refund_event_sequence_id": 20260305100000,
    "refund_transaction_id": "RFD-DEF456",
    "refund_type": "FULL",
    "refund_amount": 995000,
    "promotions_applied": [
      {"promotion_id": 100, "discount_amount": 50000, "platform_funding": 20000, "seller_funding": 30000}
    ],
    "promotion_refund_allocations": [
      {
        "promotion_id": 100,
        "refunded_discount_amount": 50000,
        "platform_funding_refund": 20000,
        "seller_funding_refund": 30000
      }
    ],
    "returned_parcels": [
      {
        "parcel_id": 2001,
        "items": [
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
> **Field `refund_type`:** `FULL` (toàn bộ đơn hàng) hoặc `PARTIAL` (1 số parcels).
>
> **Contract bắt buộc cho idempotency liên service:** `refund_transaction_id` phải luôn có trong payload để downstream dedup đúng một giao dịch hoàn tiền.
>
> **Contract deterministic cho Promotion:** Với `refund_type = PARTIAL`, Order phải phát `promotion_refund_allocations[]` để chỉ rõ số tiền hoàn theo từng promotion. Promotion **không** tự suy luận từ tỷ lệ item khi thiếu allocation.

### 3.7. ORDER_COMPLETED

**Ngữ cảnh:** Tất cả parcels `DELIVERED`. FSM `PROCESSING` → `COMPLETED`. Tự động bởi [Auto-Complete Logic](order_state_machine.md#31-auto-complete-logic-processing--completed).
**Consumers chính:** Analytics, Seller Settlement, Review Service.

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
    "user_id": 999,
    "status": "COMPLETED",
    "order_version": 9,
    "final_amount": 995000,
    "parcels_count": 2,
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

> **Contract note cho Review Service:** `items[]` là bắt buộc trong `ORDER_COMPLETED` để Review cấp quyền đánh giá theo từng item đã mua.
>
> **Field-level contract cho Review consumer:**
> - `user_id` hiện được phát ở dạng số; Review consumer phải normalize về canonical `u-{id}` trước khi ghi DB/event.
> - Trong `items[]`, `product_id` và `sku_id` là bắt buộc.
> - `variant_name` có thể null ở một số case legacy; Review phải log warning nhưng không drop toàn bộ event.

### 3.8. ORDER_PRODUCT_METRICS_UPDATED

**Ngữ cảnh:** Internal Order Metrics Projector aggregate từ luồng `ORDER_COMPLETED` và phát delta/hợp nhất theo `product_id` để Search sync `product_sold_count` theo đúng partition key.
**Consumers chính:** Search Service (`search-sales-metrics-sync`).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174006",
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

**Ngữ cảnh:** Payment trả `REFUND_FAILED` hoặc `REFUND_REVERSED`. Order giữ FSM ở `RETURN_REQUESTED` và phát event escalation để Notification/Ops xử lý.
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
    "user_id": 999,
    "status": "RETURN_REQUESTED",
    "payment_status": "REFUND_FAILED",
    "order_version": 10,
    "refund_event_sequence_id": 20260305101000,
    "payment_transaction_id": "TXN-ABC123",
    "refund_transaction_id": "RFD-DEF456",
    "reason": "REFUND_WINDOW_EXPIRED",
    "error_message": "Cong thanh toan tu choi hoan",
    "failed_at": "2026-03-05T10:10:00Z"
  }
}
```

---

## 4. Chi Tiết Các Loại Event Tiêu Thụ (Consumed Events)

### 4.1. PAYMENT_SUCCESS (Từ Payment Service)

**Topic:** `t.payment.events`
**Consumer Group:** `order-payment-consumer`
**Xử lý bởi:** [Payment Status Consumer Worker](order_workers.md#3-payment-status-consumer)

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
**Logic xử lý:** Worker nhận event → Signal Temporal Workflow (bằng `WorkflowStub.signal("PaymentResult", SUCCESS)`) → Temporal resume → FSM `PENDING_PAYMENT` → `PAID` → Produce `ORDER_PAID`.

> **Routing rule bắt buộc:** Với toàn bộ payment consumed events, Order lookup workflow theo `payload.order_id` (không route theo `aggregate_id` vì aggregate của Payment là transaction-level).

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
**Logic xử lý:** Signal Temporal → FSM `PENDING_PAYMENT` → `CANCELLED` (lý do: `PAYMENT_FAILED`) → Produce `ORDER_CANCELLED` → Trigger compensation stack (Release Inventory + Cancel Promotion).

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

### 4.4. REFUND_SUCCESS (Từ Payment Service)

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
**Logic xử lý:** Worker nhận event → Update `orders.payment_status = 'REFUNDED'` → FSM `RETURN_REQUESTED` → `REFUNDED` → Produce `ORDER_REFUNDED` (bắt buộc kèm `refund_transaction_id` + `promotion_refund_allocations[]`) → Promotion hoàn budget. Inventory restock đi theo luồng `SHIPMENT_RETURNED` từ Shipping.

### 4.5. REFUND_FAILED (Từ Payment Service)

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
**Logic xử lý:** Worker cập nhật `orders.payment_status = 'REFUND_FAILED'`, giữ FSM ở `RETURN_REQUESTED`, phát `ORDER_REFUND_FAILED` cho Notification/Ops, áp retry policy hoặc escalation.

### 4.6. REFUND_REVERSED (Từ Payment Service - Optional)

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
**Logic xử lý:** Worker cập nhật `orders.payment_status = 'REFUND_REVERSED'`, giữ FSM ở `RETURN_REQUESTED`, phát `ORDER_REFUND_FAILED` với reason escalation cho Notification/Ops.

### 4.7. SHIPMENT_STATUS_UPDATED (Từ Shipping Service)

**Topic:** `t.shipping.events`
**Consumer Group:** `order-shipping-consumer`
**Xử lý bởi:** [Shipping Status Consumer Worker](order_workers.md#4-shipping-status-consumer)

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174011",
  "event_type": "SHIPMENT_STATUS_UPDATED",
  "aggregate_type": "OrderParcel",
  "aggregate_id": "2001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-03-02T16:00:00Z",
  "source": "shipping-service",
  "payload": {
    "shipment_id": 3001,
    "parcel_id": 2001,
    "order_id": 10001,
    "shipment_event_sequence_id": 20260302160000,
    "old_status": "IN_TRANSIT",
    "new_status": "DELIVERED",
    "tracking_number": "GHN12345",
    "courier_code": "GHN",
    "occurred_at": "2026-03-02T16:00:00Z"
  }
}
```
**Logic xử lý:** Worker nhận event → Update `order_parcels.status` theo [mapping table](order_state_machine.md#23-mapping-với-shipping-service-standardized_status) → Produce `PARCEL_STATUS_CHANGED` → Kiểm tra [Auto-Complete](order_state_machine.md#31-auto-complete-logic-processing--completed).

> **Ordering contract shipping stream:** `shipment_event_sequence_id` phải tăng dần theo `parcel_id`; khi sequence mới hơn mâu thuẫn với timestamp cũ hơn thì vẫn ưu tiên sequence.

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
    "parcel_id": 2001,
    "order_id": 10001,
    "shipment_event_sequence_id": 20260302160000,
    "tracking_number": "GHN12345",
    "delivered_at": "2026-03-02T16:00:00Z"
  }
}
```
**Logic xử lý:** Worker map trực tiếp parcel sang `DELIVERED`, ghi lịch sử trạng thái, phát `PARCEL_STATUS_CHANGED`, sau đó chạy auto-complete CAS cho master order.

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
    "parcel_id": 2001,
    "order_id": 10001,
    "shipment_event_sequence_id": 20260304100000,
    "tracking_number": "GHN12345",
    "return_reason": "DELIVERY_FAILED_3_TIMES",
    "returned_at": "2026-03-04T10:00:00Z"
  }
}
```
**Logic xử lý:** Worker map parcel sang `RETURNED`, nếu order đã `COMPLETED` thì chuyển master sang `RETURN_REQUESTED` và kích hoạt refund flow.

### 4.10. SHIPMENT_DELAYED (Từ Shipping Service)

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
    "parcel_id": 2001,
    "order_id": 10001,
    "shipment_event_sequence_id": 20260303160000,
    "tracking_number": "GHN12345",
    "hours_since_update": 56,
    "escalation_level": 1,
    "detected_at": "2026-03-03T16:00:00Z"
  }
}
```
**Logic xử lý:** Worker không đổi parcel terminal status, chỉ ghi metadata SLA; nếu `escalation_level >= 2` thì phát `ORDER_STATUS_CHANGED` reason `SHIPMENT_DELAYED_ESCALATED` cho CQRS/Ops.

---

## 5. Schema Versioning & Backward Compatibility

*   **Backward Compatibility Rule:** Chỉ được phép **thêm field mới**. Không được xóa hoặc đổi tên field hiện có.
*   **Consumer Resilience:** Tất cả consumers PHẢI ignore unknown fields.
*   **Idempotency:** Mỗi event mang `event_id` (UUID) unique. Downstream consumers sử dụng bảng `idempotency_keys` hoặc `SETNX` Redis để chống xử lý trùng.
