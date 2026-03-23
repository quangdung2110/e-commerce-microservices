# Payment Service - Định Nghĩa Các Sự Kiện & Kafka Topics (Events Structure)

**Liên kết nhanh (Related Documents):**
*   [Database Schema](payment_db_design.md)
*   [State Machine](payment_state_machine.md)
*   [Workflows (API & Data Flow)](payment_workflow.md)
*   [Background Workers & Jobs](payment_workers.md)

**Cross-Service References:**
*   [Order Events (Consumer chính)](../order/order_events.md) — Order consume `PAYMENT_SUCCESS`, `PAYMENT_FAILED`, `REFUND_SUCCESS`
*   [Order Temporal Saga](../order/temporal_saga_design.md) — Payment Signal flow

---

Payment Service produce các events liên quan đến kết quả thanh toán và hoàn tiền. Đây là nguồn sự thật duy nhất (Single Source of Truth) cho trạng thái tiền tệ. Tất cả events đi qua Outbox Pattern đảm bảo At-Least-Once Delivery.

---

## 1. Bản Đồ Kafka Topics

### 1.1. Topics Produce

| Topic Name | Partition Key | Consumers chính | Mô tả |
|:---|:---|:---|:---|
| `t.payment.events` | `order_id` | Order Service, Analytics | Events thanh toán & hoàn tiền |

> **Partition Key = `order_id`** thay vì `transaction_id` để đảm bảo tất cả events của cùng 1 đơn hàng (payment + refund) vào cùng partition → Strict Ordering cho Order Service consumer.

### 1.2. Topics Consume

| Topic Name | Consumer Group | Producer | Events | Mô tả |
|:---|:---|:---|:---|:---|
| `t.order.events` | `payment-order-consumer` | Order Service | `ORDER_CANCELLED` | Trigger auto-refund nếu đã thanh toán |

---

## 2. Cấu Trúc Header Chung

Tất cả messages phát lên Kafka đều tuân theo chuẩn CloudEvents:

*   `event_id`: UUID của sự kiện.
*   `event_type`: Loại sự kiện (VD: `PAYMENT_SUCCESS`, `REFUND_INITIATED`).
*   `aggregate_type`: Loại Aggregate Root (Cố định: `Payment`).
*   `aggregate_id`: ID của entity chủ thể (Cố định: `payment_transaction_id`).
*   `correlation_id`: Temporal Workflow ID (vd: `wf-10001`) để truy vết E2E xuyên service.
*   `business_id`: `order_id` dùng cho nghiệp vụ đối soát.
*   `timestamp`: Thời điểm event được tạo (ISO 8601 UTC).
*   `source`: Định danh service gốc phát event (Cố định: `payment-service`).
*   `payload`: JSON Object chứa nội dung chi tiết.

---

## 3. Events Phát Đi (Produced Events)

### 3.1. PAYMENT_INITIATED

**Ngữ cảnh:** Order Service (Temporal Activity) gọi `POST /v1/payments/initiate` → Tạo `payment_transaction` mới. FSM: `[*]` → `PENDING`.
**Consumer chính:** Analytics (tracking funnel conversion).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174000",
  "event_type": "PAYMENT_INITIATED",
  "aggregate_type": "Payment",
  "aggregate_id": "TXN-ABC123",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T10:05:00Z",
  "source": "payment-service",
  "payload": {
    "payment_transaction_id": "TXN-ABC123",
    "order_id": 10001,
    "user_id": 999,
    "amount": 995000,
    "currency": "VND",
    "payment_method": "VNPAY",
    "payment_url": "https://pay.vnpay.vn/checkout?token=xyz",
    "expires_at": "2026-02-28T10:20:00Z",
    "created_at": "2026-02-28T10:05:00Z"
  }
}
```

### 3.2. PAYMENT_SUCCESS

**Ngữ cảnh:** Cổng thanh toán webhook confirm → FSM: `PROCESSING/PENDING` → `SUCCESS`. Xem [State Machine T2](payment_state_machine.md#12-bảng-chi-tiết-transitions).
**Consumer chính:** **Order Service** — Signal Temporal Workflow resume → Post-Payment Saga. Xem [Order Workers - Payment Consumer](../order/order_workers.md#3-payment-status-consumer).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174001",
  "event_type": "PAYMENT_SUCCESS",
  "aggregate_type": "Payment",
  "aggregate_id": "TXN-ABC123",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T10:07:00Z",
  "source": "payment-service",
  "payload": {
    "payment_transaction_id": "TXN-ABC123",
    "order_id": 10001,
    "user_id": 999,
    "amount": 995000,
    "currency": "VND",
    "provider": "VNPAY",
    "provider_transaction_id": "VNP-XYZ789",
    "paid_at": "2026-02-28T10:07:00Z"
  }
}
```

> [!IMPORTANT]
> Đây là **event quan trọng nhất** trong toàn bộ hệ thống thanh toán. Order Service nhận event này → Signal Temporal → FSM Order `PENDING_PAYMENT` → `PAID` → Trigger Post-Payment Saga (Deduct Inventory, Confirm Promotion, Create Shipping Labels).

### 3.3. PAYMENT_FAILED

**Ngữ cảnh:** Cổng thanh toán webhook báo thất bại. FSM: `PROCESSING` → `FAILED`.
**Consumer chính:** Order Service — Signal Temporal → Cancel Order → Compensation.

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174002",
  "event_type": "PAYMENT_FAILED",
  "aggregate_type": "Payment",
  "aggregate_id": "TXN-ABC123",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T10:08:00Z",
  "source": "payment-service",
  "payload": {
    "payment_transaction_id": "TXN-ABC123",
    "order_id": 10001,
    "user_id": 999,
    "error_code": "INSUFFICIENT_BALANCE",
    "error_message": "Số dư tài khoản không đủ",
    "provider": "VNPAY",
    "failed_at": "2026-02-28T10:08:00Z"
  }
}
```

### 3.4. PAYMENT_EXPIRED

**Ngữ cảnh:** CronJob quét `expires_at < NOW()`. FSM: `PENDING/PROCESSING` → `EXPIRED`.
**Consumer chính:** Order Service — Signal Temporal → Cancel Order.

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174003",
  "event_type": "PAYMENT_EXPIRED",
  "aggregate_type": "Payment",
  "aggregate_id": "TXN-ABC123",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T10:20:00Z",
  "source": "payment-service",
  "payload": {
    "payment_transaction_id": "TXN-ABC123",
    "order_id": 10001,
    "user_id": 999,
    "expired_at": "2026-02-28T10:20:00Z"
  }
}
```

### 3.5. REFUND_INITIATED

**Ngữ cảnh:** Order Service request refund → Tạo `refund_transaction`. Refund FSM: `[*]` → `PENDING`.
**Consumer:** Analytics.

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174004",
  "event_type": "REFUND_INITIATED",
  "aggregate_type": "Payment",
  "aggregate_id": "RFD-DEF456",
  "correlation_id": "wf-10001",
  "timestamp": "2026-03-05T09:00:00Z",
  "source": "payment-service",
  "payload": {
    "refund_transaction_id": "RFD-DEF456",
    "payment_transaction_id": "TXN-ABC123",
    "order_id": 10001,
    "user_id": 999,
    "refund_amount": 995000,
    "reason": "CUSTOMER_RETURN",
    "initiated_at": "2026-03-05T09:00:00Z"
  }
}
```

### 3.6. REFUND_SUCCESS

**Ngữ cảnh:** Cổng thanh toán xác nhận hoàn tiền xong. Refund FSM: `PROCESSING` → `SUCCESS`. Payment FSM: `REFUNDING` → `REFUNDED`.
**Consumer chính:** **Order Service** — FSM Order `RETURN_REQUESTED` → `REFUNDED`. Produce `ORDER_REFUNDED`.

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174005",
  "event_type": "REFUND_SUCCESS",
  "aggregate_type": "Payment",
  "aggregate_id": "RFD-DEF456",
  "correlation_id": "wf-10001",
  "timestamp": "2026-03-05T10:00:00Z",
  "source": "payment-service",
  "payload": {
    "refund_transaction_id": "RFD-DEF456",
    "payment_transaction_id": "TXN-ABC123",
    "order_id": 10001,
    "user_id": 999,
    "refund_amount": 995000,
    "provider_refund_id": "VNP-RFD-001",
    "refunded_at": "2026-03-05T10:00:00Z"
  }
}
```

### 3.7. REFUND_FAILED

**Ngữ cảnh:** Cổng thanh toán từ chối hoàn tiền. Refund FSM: `PROCESSING` → `FAILED`.
**Consumer:** Order Service (alert), SysAdmin Dashboard.

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174006",
  "event_type": "REFUND_FAILED",
  "aggregate_type": "Payment",
  "aggregate_id": "RFD-DEF456",
  "correlation_id": "wf-10001",
  "timestamp": "2026-03-05T10:00:00Z",
  "source": "payment-service",
  "payload": {
    "refund_transaction_id": "RFD-DEF456",
    "payment_transaction_id": "TXN-ABC123",
    "order_id": 10001,
    "error_code": "REFUND_WINDOW_EXPIRED",
    "error_message": "Quá hạn hoàn tiền 30 ngày",
    "failed_at": "2026-03-05T10:00:00Z"
  }
}
```

---

## 4. Events Tiêu Thụ (Consumed Events)

### 4.1. ORDER_CANCELLED (Từ Order Service)

**Topic:** `t.order.events`
**Consumer Group:** `payment-order-consumer`

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174007",
  "event_type": "ORDER_CANCELLED",
  "aggregate_type": "Order",
  "aggregate_id": "10001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T10:20:00Z",
  "source": "order-service",
  "payload": {
    "order_id": 10001,
    "previous_status": "PROCESSING",
    "reason": "ADMIN_FORCE_CANCEL"
  }
}
```
**Logic xử lý:** Kiểm tra payment transaction status:
- Nếu `PENDING` / `PROCESSING` → Cancel payment (FSM T6).
- Nếu `SUCCESS` → Auto-initiate refund (FSM T7 → Tạo `refund_transaction`).
- Nếu đã `EXPIRED` / `CANCELLED` / `FAILED` → Ignore.

---

## 5. Schema Versioning & Backward Compatibility

*   Chỉ thêm fields mới, không xóa/rename fields hiện có.
*   Consumers phải ignore unknown fields.
*   `event_id` unique — downstream dùng để idempotency check.
