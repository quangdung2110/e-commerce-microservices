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
*   [Payment Events](../payment/payment_events.md) — Order consume `PAYMENT_SUCCESS`, `PAYMENT_FAILED`
*   [Shipping Events](../shipping/shipping_events.md) — Order consume `SHIPMENT_STATUS_UPDATED`
*   [Inventory Events](../inventory/inventory_events.md) — Inventory consume Reserve/Deduct/Release commands
*   [Cart Events](../cart/cart_events.md) — Cart consume `ORDER_PAID` để batch delete items

---

Tài liệu này định nghĩa cấu trúc tiêu chuẩn của tất cả các luồng sự kiện **phát đi (Produce)** từ Order Service và **tiêu thụ (Consume)** từ các service khác.

Order Service là **Orchestrator trung tâm** — nó produce nhiều event nhất và consume từ nhiều nguồn nhất trong hệ thống. Tất cả events đi qua [Outbox Pattern](order_db_design.md) để đảm bảo At-Least-Once Delivery.

## 1. Bản Đồ Kafka Topics

### 1.1 Topics mà Order Service **Produce** (Phát đi)

| Topic Name | Partition Key | Consumers chính | Mô tả |
|---|---|---|---|
| `t.order.events` | `order_id` | Promotion, Inventory, Cart, CQRS Projection Worker (internal), Notification, Analytics | Topic chính chứa mọi thay đổi trạng thái đơn hàng |

> **Ghi chú Partition Key:** Sử dụng `order_id` đảm bảo tất cả events của cùng 1 đơn hàng luôn vào đúng 1 Partition → **Strict Ordering** cho từng đơn. Promotion/Inventory cần ordering chính xác để không bị Redeem trước Cancel.

### 1.2 Topics mà Order Service **Consume** (Lắng nghe)

| Topic Name | Consumer Group | Producer | Events | Mô tả |
|---|---|---|---|---|
| `t.payment.events` | `order-payment-consumer` | Payment Service | `PAYMENT_SUCCESS`, `PAYMENT_FAILED`, `PAYMENT_EXPIRED`, `REFUND_SUCCESS` | Nhận kết quả thanh toán / hoàn tiền |
| `t.shipping.events` | `order-shipping-consumer` | Shipping Service | `SHIPMENT_STATUS_UPDATED`, `SHIPMENT_DELIVERED`, `SHIPMENT_RETURNED` | Nhận cập nhật trạng thái vận chuyển |

## 2. Cấu Trúc Message Header Chung (CloudEvents Wrapper)

Tất cả messages phát lên Kafka đều tuân theo chuẩn CloudEvents và được [Outbox Polling Worker](order_workers.md#1-outbox-polling-worker) bổ sung:

*   `event_id`: UUID của sự kiện (từ `outbox_events.id`).
*   `event_type`: Loại sự kiện (VD: `ORDER_CREATED`, `ORDER_PAID`).
*   `aggregate_type`: `Order` hoặc `OrderParcel`.
*   `aggregate_id`: `order_id`.
*   `timestamp`: Thời điểm event được tạo (ISO 8601 UTC).
*   `source`: `order-service`.
*   `correlation_id`: Temporal Workflow ID (để trace xuyên suốt Saga).
*   `business_id`: `order_id` dùng cho nghiệp vụ đối soát.
*   `payload`: JSON Object chi tiết theo từng `event_type`.

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

**Ngữ cảnh:** Payment Service xác nhận thanh toán thành công → Temporal Signal → FSM `PENDING_PAYMENT` → `PAID`. Xem [State Machine T2](order_state_machine.md#12-bảng-chi-tiết-transitions).
**Consumers chính:**
- **Cart Service:** Trigger [Batch Delete](../cart/cart_workflow.md#4-api-batch-delete-xóa-lô-sản-phẩm-sau-checkout-tĩnh) — xóa theo `cart_item_ids` ổn định từ snapshot checkout.
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
    "payment_method": "VNPAY",
    "payment_transaction_id": "TXN-ABC123",
    "cart_item_ids": [
      "cart:user:999:sku-001"
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
    "paid_at": "2026-02-28T10:05:00Z"
  }
}
```

> **Contract ổn định cho Cart delete:** `cart_item_ids` là identity bất biến của item tại thời điểm checkout. Theo [Cart DB schema](../cart/cart_db_schema.md#22-bảng-cart_items), format chuẩn là `{cart_id}:{sku_id}`. Không sử dụng identity động kiểu `added_at/cart_version` để tránh lệch dữ liệu khi replay hoặc khi cart thay đổi đồng thời.

> [!IMPORTANT]
> `ORDER_PAID` là event thông báo trạng thái sau khi Post-Payment Saga đã hoàn tất. Các thao tác giao dịch lõi như `DeductInventoryActivity` và `ConfirmPromotionActivity` được thực thi đồng bộ trước khi event này được phát ra.

### 3.3. ORDER_CANCELLED

**Ngữ cảnh:** Timeout 15 phút / User cancel / Admin force cancel. FSM → `CANCELLED`. Xem [State Machine T3/T4/T7](order_state_machine.md#12-bảng-chi-tiết-transitions).
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
    "tracking_number": "GHN12345",
    "courier_code": "GHN",
    "estimated_delivery_date": "2026-03-02",
    "changed_at": "2026-02-28T14:00:00Z"
  }
}
```

### 3.6. ORDER_REFUNDED

**Ngữ cảnh:** Đơn hàng đã giao thành công nhưng khách đổi trả hàng. FSM `RETURN_REQUESTED` → `REFUNDED`. Xem [State Machine T9](order_state_machine.md#12-bảng-chi-tiết-transitions).
**Consumers chính:**
- **Promotion Service:** Trigger [Refund Budget](../promotion/promotion_workflow.md#5-luồng-đổi-trả-hàng--hoàn-tiền-refund-flow).
- **Inventory Service:** Release deducted stock.

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
    "refund_type": "FULL",
    "refund_amount": 995000,
    "promotions_applied": [
      {"promotion_id": 100, "discount_amount": 50000, "platform_funding": 20000, "seller_funding": 30000}
    ],
    "returned_parcels": [
      {
        "parcel_id": 2001,
        "items": [{"sku_id": "sku-001", "quantity": 2}]
      }
    ],
    "refunded_at": "2026-03-05T10:00:00Z"
  }
}
```
> **Field `refund_type`:** `FULL` (toàn bộ đơn hàng) hoặc `PARTIAL` (1 số parcels).

### 3.7. ORDER_COMPLETED

**Ngữ cảnh:** Tất cả parcels `DELIVERED`. FSM `PROCESSING` → `COMPLETED`. Tự động bởi [Auto-Complete Logic](order_state_machine.md#31-auto-complete-logic-processing--completed).
**Consumers chính:** Analytics, Seller Settlement.

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
    "final_amount": 995000,
    "parcels_count": 2,
    "completed_at": "2026-03-02T16:00:00Z"
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
  "aggregate_type": "Order",
  "aggregate_id": "10001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T10:05:00Z",
  "source": "payment-service",
  "payload": {
    "payment_transaction_id": "TXN-ABC123",
    "order_id": 10001,
    "amount": 995000,
    "provider": "VNPAY",
    "provider_transaction_id": "VNP-XYZ789",
    "paid_at": "2026-02-28T10:05:00Z"
  }
}
```
**Logic xử lý:** Worker nhận event → Signal Temporal Workflow (bằng `WorkflowStub.signal("PaymentResult", SUCCESS)`) → Temporal resume → FSM `PENDING_PAYMENT` → `PAID` → Produce `ORDER_PAID`.

### 4.2. PAYMENT_FAILED (Từ Payment Service)

**Topic:** `t.payment.events`
**Consumer Group:** `order-payment-consumer`

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174008",
  "event_type": "PAYMENT_FAILED",
  "aggregate_type": "Order",
  "aggregate_id": "10001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T10:06:00Z",
  "source": "payment-service",
  "payload": {
    "payment_transaction_id": "TXN-ABC123",
    "order_id": 10001,
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
  "aggregate_type": "Order",
  "aggregate_id": "10001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T10:15:00Z",
  "source": "payment-service",
  "payload": {
    "payment_transaction_id": "TXN-ABC123",
    "order_id": 10001,
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
  "aggregate_type": "Order",
  "aggregate_id": "10001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-03-05T10:00:00Z",
  "source": "payment-service",
  "payload": {
    "payment_transaction_id": "TXN-ABC123",
    "refund_transaction_id": "RFD-DEF456",
    "order_id": 10001,
    "refund_amount": 995000,
    "refunded_at": "2026-03-05T10:00:00Z"
  }
}
```
**Logic xử lý:** Worker nhận event → Update `orders.payment_status = 'REFUNDED'` → FSM `RETURN_REQUESTED` → `REFUNDED` → Produce `ORDER_REFUNDED` → Promotion hoàn budget, Inventory release stock.

### 4.5. REFUND_FAILED (Từ Payment Service)

**Topic:** `t.payment.events`
**Consumer Group:** `order-payment-consumer`

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174010b",
  "event_type": "REFUND_FAILED",
  "aggregate_type": "Order",
  "aggregate_id": "10001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-03-05T10:10:00Z",
  "source": "payment-service",
  "payload": {
    "payment_transaction_id": "TXN-ABC123",
    "refund_transaction_id": "RFD-DEF456",
    "order_id": 10001,
    "error_code": "REFUND_WINDOW_EXPIRED",
    "error_message": "Cổng thanh toán từ chối hoàn",
    "failed_at": "2026-03-05T10:10:00Z"
  }
}
```
**Logic xử lý:** Worker cập nhật `orders.payment_status = 'REFUND_FAILED'`, giữ FSM ở `RETURN_REQUESTED`, phát `ORDER_REFUND_FAILED` cho Notification/Ops, áp retry policy hoặc escalation.

### 4.6. SHIPMENT_STATUS_UPDATED (Từ Shipping Service)

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
    "old_status": "IN_TRANSIT",
    "new_status": "DELIVERED",
    "tracking_number": "GHN12345",
    "courier_code": "GHN",
    "occurred_at": "2026-03-02T16:00:00Z"
  }
}
```
**Logic xử lý:** Worker nhận event → Update `order_parcels.status` theo [mapping table](order_state_machine.md#23-mapping-với-shipping-service-standardized_status) → Produce `PARCEL_STATUS_CHANGED` → Kiểm tra [Auto-Complete](order_state_machine.md#31-auto-complete-logic-processing--completed).

---

## 5. Schema Versioning & Backward Compatibility

*   **Backward Compatibility Rule:** Chỉ được phép **thêm field mới**. Không được xóa hoặc đổi tên field hiện có.
*   **Consumer Resilience:** Tất cả consumers PHẢI ignore unknown fields.
*   **Idempotency:** Mỗi event mang `event_id` (UUID) unique. Downstream consumers sử dụng bảng `idempotency_keys` hoặc `SETNX` Redis để chống xử lý trùng.
