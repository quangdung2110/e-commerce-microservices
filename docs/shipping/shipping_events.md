# Shipping Service - Định Nghĩa Các Sự Kiện & Kafka Topics

**Liên kết nhanh (Related Documents):**
*   [Database Schema](shipping_db_design.md)
*   [State Machine & Provider Mapping](shipping_state_machine.md)
*   [Workflows (API & Data Flow)](shipping_workflow.md)
*   [Background Workers & Jobs](shipping_workers.md)

**Cross-Service References:**
*   [Order Events (Consumer)](../order/order_events.md) — Order consume `SHIPMENT_STATUS_UPDATED`, `SHIPMENT_DELIVERED`, `SHIPMENT_RETURNED`, `SHIPMENT_DELAYED`
*   [Order Workers - Shipping Consumer](../order/order_workers.md#5-shipping-status-consumer-kafka-consumer) — Cách Order xử lý shipping events

---

Shipping Service produce các events liên quan đến vòng đời vận đơn. Events đều đã được chuẩn hóa qua [Provider Status Mapping](shipping_state_machine.md#2-provider-status-mapping-table) — downstream consumers không cần biết 3PL gốc.

---

## 1. Bản Đồ Kafka Topics

### 1.1. Topics Produce

| Topic Name | Partition Key | Consumers chính | Mô tả |
|:---|:---|:---|:---|
| `t.shipping.events` | `order_id` | Order Service, Notification Service, Analytics | Events vận đơn đã chuẩn hóa |

### 1.2. Topics Nội Bộ (Internal)

| Topic Name | Partition Key | Consumer | Mô tả |
|:---|:---|:---|:---|
| `t.shipping.webhooks.raw` | `provider:tracking_number` | Webhook Ingest Worker | Raw webhook từ 3PL |

### 1.3. Topics Consume

| Topic Name | Consumer Group | Producer | Events |
|:---|:---|:---|:---|
| *(Không consume topic ngoài)* | — | — | Shipping nhận requests qua gRPC/REST API trực tiếp từ Order (Temporal Activities) |

> **Lưu ý:** Shipping Service **không consume Kafka events từ bên ngoài**. Mọi tương tác từ Order Service đều qua Temporal Activity calls (gRPC). Shipping chỉ produce events ra ngoài sau khi xử lý webhooks.

---

## 2. Cấu Trúc Header Chung

Tương tự CloudEvents: `event_id`, `event_type`, `aggregate_type = Shipment`, `aggregate_id = shipment_id`, `source: shipping-service`, `timestamp`, `correlation_id` (workflow/order trace), `business_id = order_id`, `payload`.

**Contract bắt buộc cho downstream ordering:** payload của toàn bộ status events phải có `shipment_event_sequence_id` tăng đơn điệu theo `order_item_id`.

---

## 3. Events Phát Đi (Produced Events)

### 3.1. SHIPMENT_CREATED

**Ngữ cảnh:** Temporal Activity `CreateShippingLabelActivity` gọi API → Shipment tạo thành công. FSM: `[*]` → `PENDING`.
**Consumer:** Order CQRS Projection Worker, Notification (SMS "Đơn hàng đang được chuẩn bị").

```json
{
  "event_type": "SHIPMENT_CREATED",
  "payload": {
    "shipment_id": 3001,
    "order_id": 10001,
    "order_item_id": 2001,
    "tracking_number": "GHN12345",
    "courier_code": "GHN",
    "courier_name": "Giao Hàng Nhanh",
    "sender_address": {"warehouse_id": 5, "city": "HCM"},
    "receiver_address": {"name": "Nguyen Van A", "phone": "0901234567", "city": "HCM", "district": "Q1"},
    "estimated_delivery_date": "2026-03-02",
    "created_at": "2026-02-28T10:10:00Z"
  }
}
```

### 3.2. SHIPMENT_STATUS_UPDATED

**Ngữ cảnh:** 3PL webhook → [Provider Mapping](shipping_state_machine.md#2-provider-status-mapping-table) → FSM transition. Đây là **event được produce nhiều nhất** — mỗi lần 3PL update tracking là 1 event.
**Consumer chính:** **Order Service** — [Shipping Status Consumer](../order/order_workers.md#5-shipping-status-consumer-kafka-consumer) update Order Item Shipping FSM.

```json
{
  "event_type": "SHIPMENT_STATUS_UPDATED",
  "payload": {
    "shipment_id": 3001,
    "order_id": 10001,
    "order_item_id": 2001,
    "shipment_event_sequence_id": 20260301080000,
    "tracking_number": "GHN12345",
    "courier_code": "GHN",
    "old_status": "READY_TO_SHIP",
    "new_status": "IN_TRANSIT",
    "standardized_status": "IN_TRANSIT",
    "provider_original_status": "picked",
    "provider_event_fingerprint": "sha256:7f4d...",
    "location": "Sorting Hub Tân Bình, HCM",
    "occurred_at": "2026-03-01T08:00:00Z"
  }
}
```
> **Field `provider_original_status`:** Giữ nguyên mã gốc của 3PL để debug/audit. Downstream chỉ dùng `new_status` (đã chuẩn hóa).
>
> **Field `provider_event_fingerprint`:** Khoá dedup nghiệp vụ cho webhook (`provider_webhook_id` hoặc hash fallback). Shipping dùng fingerprint này làm `action_scope_key` ở `shipping_event_idempotency`.

### 3.3. SHIPMENT_DELIVERED

**Ngữ cảnh:** 3PL xác nhận giao hàng thành công. FSM: `IN_TRANSIT` → `DELIVERED`.
**Consumer:** Order Service (trigger Auto-Complete check), Analytics, admin Settlement.

```json
{
  "event_type": "SHIPMENT_DELIVERED",
  "payload": {
    "shipment_id": 3001,
    "order_id": 10001,
    "order_item_id": 2001,
    "shipment_event_sequence_id": 20260302143000,
    "tracking_number": "GHN12345",
    "standardized_status": "DELIVERED",
    "delivered_at": "2026-03-02T14:30:00Z",
    "receiver_name": "Nguyen Van A",
    "proof_of_delivery_url": "https://cdn.ghn.vn/pod/GHN12345.jpg"
  }
}
```

### 3.4. SHIPMENT_RETURNED

**Ngữ cảnh:** Kiện hàng trả về kho (giao thất bại hoặc khách đổi trả). FSM: `FAILED/DELIVERED` → `RETURNED`.
**Consumer:** Order Service (cập nhật item timeline/status), Inventory (restock vật lý khi hàng về kho).

```json
{
  "event_type": "SHIPMENT_RETURNED",
  "payload": {
    "shipment_id": 3001,
    "order_id": 10001,
    "order_item_id": 2001,
    "shipment_event_sequence_id": 20260304100000,
    "tracking_number": "GHN12345",
    "standardized_status": "RETURNED",
    "return_reason": "DELIVERY_FAILED_3_TIMES",
    "returned_to_warehouse_id": 5,
    "items": [
      {
        "sku_id": "sku-001",
        "product_id": "prod-uuid-001",
        "quantity": 2
      }
    ],
    "returned_at": "2026-03-04T10:00:00Z"
  }
}
```
> **Field `return_reason`:** `DELIVERY_FAILED_3_TIMES`, `CUSTOMER_RETURN`, `DAMAGED_IN_TRANSIT`.
>
> `order_item_id`, `returned_to_warehouse_id`, `items[].sku_id`, `items[].product_id`, `items[].quantity` là bắt buộc để Inventory restock chính xác và dedup theo `shipment_id + order_item_id`.
>
> **Contract với Order refund lane:** `SHIPMENT_RETURNED` không mở `OrderRefundWorkflow`; refund chỉ đi từ `API_RETURN`/admin lane và được gate bởi xác nhận tổng kết từ Inventory.

### 3.5. SHIPMENT_CANCELLED

**Ngữ cảnh:** Temporal Compensation `CancelShippingActivity`. FSM: `PENDING` → `CANCELLED`.
**Consumer:** Order Service (update internal tracking).

```json
{
  "event_type": "SHIPMENT_CANCELLED",
  "payload": {
    "shipment_id": 3001,
    "order_id": 10001,
    "order_item_id": 2001,
    "tracking_number": "GHN12345",
    "reason": "ORDER_CANCELLED",
    "cancelled_at": "2026-02-28T10:25:00Z"
  }
}
```

### 3.6. SHIPMENT_DELAYED

**Ngữ cảnh:** [Stale Shipment Detector](shipping_workers.md#4-stale-shipment-detector-cronjob) phát hiện vận đơn quá 48h không nhận tracking update từ 3PL — nghi ngờ mất hàng hoặc trễ nghiêm trọng.
**Consumer:**
- **Order Service** → Update internal tracking metadata, CQRS ES document.
- **Notification Service** → Push notification cho buyer: "Vận đơn {tracking} đang bị chậm, chúng tôi đang liên hệ đơn vị vận chuyển."

```json
{
  "event_type": "SHIPMENT_DELAYED",
  "payload": {
    "shipment_id": 3001,
    "order_id": 10001,
    "order_item_id": 2001,
    "shipment_event_sequence_id": 20260228160000,
    "tracking_number": "GHN12345",
    "courier_code": "GHN",
    "current_status": "IN_TRANSIT",
    "standardized_status": "IN_TRANSIT",
    "last_update_at": "2026-02-26T08:00:00Z",
    "hours_since_update": 56,
    "escalation_level": 1,
    "detected_at": "2026-02-28T16:00:00Z"
  }
}
```

> **Tần suất:** Mỗi shipment phát `SHIPMENT_DELAYED` ở `escalation_level = 1` đúng 1 lần bằng key `shipping:delayed:sent:{shipment_id}:L1` (TTL 7 ngày). Nếu shipment tiếp tục không update thêm 24h nữa (tổng 72h), phát `escalation_level = 2` đúng 1 lần bằng key riêng `shipping:delayed:sent:{shipment_id}:L2`.

---

## 4. Schema Versioning

*   Chỉ thêm fields mới, không xóa/rename.
*   Consumers MUST ignore unknown fields.
*   `event_id` unique — dùng cho idempotency downstream.
