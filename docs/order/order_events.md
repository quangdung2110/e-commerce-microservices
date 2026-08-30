# Order Service - Định Nghĩa Các Sự Kiện & Kafka Topics (Events Structure)

> **Phase 22 (Simplify Idempotency Schema):** Bỏ `order_event_idempotency`. Consumer guard giờ dựa vào **Redis SETNX** (TTL 30 ngày) + **FSM row-level lock** (`UPDATE ... WHERE status='OLD_STATUS'`) + **CloudEvents `event_id`** envelope. Outbox duplicate event (nếu Redis mất key) được xử lý bởi Notification consumer thông qua guard key `(order_item_id, last_event_sequence_id)` cho `ORDER_ITEM_STATUS_CHANGED` (xem §3.5) và `(order_id, last_event_sequence_id)` cho `ORDER_COMPLETED` (xem §3.7). Xem [CHANGELOG Phase 22](../_contracts/CHANGELOG.md#phase-22--simplify-idempotency-schema-order).

**Liên kết nhanh (Related Documents):**
*   [Database Schema](order_db_design.md)
*   [Temporal Saga Design](temporal_saga_design.md)
*   [Search/Filter Design (PostgreSQL direct)](order_search_design.md)
*   [State Machine & Lifecycle](order_state_machine.md)
*   [Workflows (API & Data Flow)](order_workflow.md)
*   [Background Workers & Jobs](order_workers.md)

**Cross-Service References:**
*   [Promotion Events (Consumed)](../promotion/promotion_events.md) — Promotion consume `ORDER_REFUNDED`
*   [Payment Events](../payment/payment_events.md) — Order consume `PAYMENT_SUCCESS`, `PAYMENT_FAILED`, `PAYMENT_EXPIRED`, `REFUND_PROCESSING`, `REFUND_SUCCESS`, `REFUND_FAILED`, `REFUND_REVERSED`
*   [Inventory Events](../inventory/inventory_events.md) — Order consume `INVENTORY_RETURN_RECEIVED_ALL` (refund gate) và `INVENTORY_SHIP_TO_CUSTOMER` (item delivery FSM trigger)
*   [Cart Interfaces](../cart/interfaces.md) — Cart consume `ORDER_PAID` để batch delete SKU lines trong giỏ

---

Tài liệu này định nghĩa cấu trúc tiêu chuẩn của tất cả các luồng sự kiện **phát đi (Produce)** từ Order Service và **tiêu thụ (Consume)** từ các service khác.

Order Service là **Orchestrator trung tâm** — nó produce nhiều event nhất và consume từ nhiều nguồn nhất trong hệ thống. Tất cả produced events đi qua [Outbox Pattern](order_db_design.md) dạng append-only và được publish bằng CDC pipeline để đảm bảo At-Least-Once Delivery.

## 1. Bản Đồ Kafka Topics

### 1.1 Topics mà Order Service **Produce** (Phát đi)

| Topic Name | Partition Key | Consumers chính | Mô tả |
|---|---|---|---|
| `t.order.events` | `order_id` | Promotion, Inventory (refund), Cart, Notification, Analytics, Review | Topic chính chứa mọi thay đổi trạng thái đơn hàng |

> **Ghi chú Partition Key:**
> - `t.order.events` dùng `order_id` để giữ strict ordering theo từng đơn.


### 1.2 Topics mà Order Service **Consume** (Lắng nghe)

| Topic Name | Consumer Group | Producer | Events | Mô tả |
|---|---|---|---|---|
| `t.payment.events` | `order-payment-consumer` | Payment Service | `PAYMENT_SUCCESS`, `PAYMENT_FAILED`, `PAYMENT_EXPIRED`, `REFUND_PROCESSING`, `REFUND_SUCCESS`, `REFUND_FAILED`, `REFUND_REVERSED` | Nhận kết quả thanh toán / hoàn tiền |
| `t.inventory.events` | `order-inventory-return-consumer` | Inventory Service | `INVENTORY_RETURN_RECEIVED_ALL` | Nhận xác nhận Inventory đã nhận đủ hàng trong refund scope |
| `t.inventory.events` | `order-inventory-ship-consumer` | Inventory Service | `INVENTORY_SHIP_TO_CUSTOMER` | Trigger Order Item FSM PROCESSING → DELIVERED khi admin xác nhận kho đã xuất hàng |

## 2. Cấu Trúc Message Header Chung (CloudEvents Wrapper)

Tất cả messages phát lên Kafka đều tuân theo chuẩn CloudEvents và được [Outbox CDC Pipeline](order_workers.md#1-outbox-cdc-pipeline-producer-path) publish:

*   `event_id`: UUID của sự kiện (từ `outbox_events.event_id`).
*   `event_type`: Loại sự kiện (VD: `ORDER_CREATED`, `ORDER_PAID`).
*   `aggregate_type`: Với **produced events** của Order: `Order` hoặc `OrderItem`. Với **consumed events**, giữ nguyên giá trị từ producer gốc (ví dụ `Payment`).
*   `aggregate_id`: ID theo `aggregate_type` (ví dụ `order_id`, `order_item_id`, `payment_transaction_id`, `refund_transaction_id`, `product_id`).
*   `timestamp`: Thời điểm event được tạo (ISO 8601 UTC).
*   `source`: `order-service`.
*   `correlation_id`: Temporal Workflow ID (để trace xuyên suốt Saga).
*   `business_id`: `order_id` dùng cho nghiệp vụ đối soát.
*   `payload`: JSON Object chi tiết theo từng `event_type`.

### 2.1. Guard Keys cho FSM

| Event nhóm | Guard key bắt buộc | Stream ownership |
|---|---|---|
| `ORDER_CREATED`, `ORDER_PAID`, `ORDER_CANCELLED`, `ORDER_STATUS_CHANGED`, `ORDER_COMPLETED`, `ORDER_REFUNDED`, `ORDER_REFUND_FAILED` | `last_event_sequence_id` | Order lifecycle (per `order_id`) |
| `ORDER_ITEM_STATUS_CHANGED` | `last_event_sequence_id` + `order_item_id` | Item/delivery (per `order_item_id`) |

> **Lưu ý:** `payment_event_sequence_id`/`refund_event_sequence_id` trong payload event Order **consume** từ Payment Service (`PAYMENT_*`/`REFUND_*`) là do Payment Service tự phát, không thuộc guard key của Order — Order guard cho consumed events dựa vào **Redis SETNX** TTL 30 ngày + **FSM row-level lock** (xem §4 routing note).

> Các guard key này phục vụ FSM consistency tại write-side và ordering hint cho consumer. Không suy luận thứ tự event bằng UUID `event_id` theo từ điển.

## 3. Chi Tiết Các Loại Event Phát Đi (Produced Events)

### 3.1. ORDER_CREATED

**Ngữ cảnh:** Checkout Service submit → Temporal Workflow tạo Master Order + Items + Item Lines thành công. Trạng thái: `CREATED` → `PENDING_PAYMENT`. Xem [Workflow Checkout Submit](order_workflow.md#4-workflow-nhận-checkout-submit).
**Consumers chính:** Notification (gửi SMS xác nhận đơn).

> **Phase 20 (Payload Simplification):** Bỏ `delivery_address_snapshot` (Notification fetch qua `GET /v1/orders/{id}`), `items[]` (snapshot đã persist trong DB), `promotions_applied[]` (Promotion tự reserve qua Saga), `total_amount`/`total_discount` (chỉ `final_amount` cần cho display), `created_at` (trùng envelope `timestamp`).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174000",
  "event_type": "ORDER_CREATED",
  "aggregate_type": "Order",
  "aggregate_id": "10001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T10:00:00Z",
  "source": "order-service",
  "schema_version": 1,
  "payload": {
    "order_id": 10001,
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "status": "PENDING_PAYMENT",
    "last_event_sequence_id": 1,
    "final_amount": 950000
  }
}
```

### 3.2. ORDER_PAID

**Ngữ cảnh:** Payment Service xác nhận thanh toán thành công → Temporal Signal → FSM `PENDING_PAYMENT` → `PAID`. Xem [State Machine T2](order_state_machine.md#3-bảng-chi-tiết-transitions).
**Consumers chính:**
- **Cart Service:** Trigger [Batch Delete](../cart/workflows.md#wf-cart-004--xóa-item-theo-lô) — cleanup theo `user_id` (Cart tự build `cart_id` từ `user_id`) + `sku_ids` từ payload.
- **Notification:** Đồng bộ trạng thái hiển thị và thông báo.

> **Phase 20 (Payload Simplification):** Bỏ `cart_id` (Cart derive từ `user_id`), `promotions_applied[]` (Promotion đã có ledger riêng), `items[]` (Cart chỉ cần `sku_ids[]`), `checkout_submitted_at` (Notification có thể fetch từ DB).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174001",
  "event_type": "ORDER_PAID",
  "aggregate_type": "Order",
  "aggregate_id": "10001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T10:05:00Z",
  "source": "order-service",
  "schema_version": 1,
  "payload": {
    "order_id": 10001,
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "status": "PAID",
    "last_event_sequence_id": 3,
    "payment_recovery": false,
    "payment_transaction_id": "TXN-ABC123",
    "sku_ids": [
      "sku-001"
    ],
    "final_amount": 995000,
    "paid_at": "2026-02-28T10:05:00Z"
  }
}
```

> [!IMPORTANT]
> `ORDER_PAID` là event mốc tài chính: được phát ngay khi workflow branch `PAYMENT_SUCCESS` commit thành công transition FSM `PENDING_PAYMENT` -> `PAID`.
> Các thao tác hậu thanh toán thuộc mốc vận hành (`DeductInventoryActivity`, `ConfirmPromotionActivity`) được theo dõi bằng `ORDER_STATUS_CHANGED` khi transition `PAID` -> `PROCESSING` hoàn tất.
>
> Trong lane khôi phục payment muộn, `ORDER_PAID` có thể được phát với `payment_recovery = true` sau khi order từng bị `ORDER_CANCELLED` vì `PAYMENT_TIMEOUT`.

### 3.3. ORDER_CANCELLED

**Ngữ cảnh:** Lane cancel pre-payment (`status = PENDING_PAYMENT`): timeout 15 phút / user cancel / admin cancel / payment failed. FSM -> `CANCELLED`. Xem [State Machine T3/T4/T4.1](order_state_machine.md#3-bảng-chi-tiết-transitions).
**Consumers chính:**
- **Payment Service:** Reconciliation/cancel pending payment transaction (nếu còn mở).
- **Notification:** Đồng bộ trạng thái hiển thị và thông báo.

> **Phase 20 (Payload Simplification):** Bỏ `items[]` (Payment chỉ cần `order_id` để cancel pending txn; Promotion đã rollback qua activity), `promotions_applied[]`.

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
  "schema_version": 1,
  "payload": {
    "order_id": 10001,
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "status": "CANCELLED",
    "previous_status": "PENDING_PAYMENT",
    "reason": "PAYMENT_TIMEOUT",
    "last_event_sequence_id": 4,
    "cancelled_at": "2026-02-28T10:20:00Z"
  }
}
```

> **Field `reason`:** `PAYMENT_TIMEOUT`, `USER_CANCEL`, `ADMIN_FORCE_CANCEL`, `PAYMENT_FAILED`.

### 3.4. ORDER_STATUS_CHANGED

**Ngữ cảnh:** Mọi transition FSM của Master Order (trừ CREATED, PAID, CANCELLED, COMPLETED đã có event riêng). VD: `PAID` -> `PROCESSING`, `PAID/PROCESSING/COMPLETED` -> `RETURN_REQUESTED`, `CANCELLED` -> `PAID` (late-payment recovery).
**Consumers chính:** Notification Service.

> **Phase 20 (Payload Simplification):** Bỏ `buyer_tab` (Notification derive từ FSM qua `v_orders_buyer_tab`), `changed_by`/`changed_by_type` (luôn = `system` vì Order Service là producer duy nhất, Temporal workflow owner duy nhất). Các field refund-metadata (`refund_trigger_source`, `customer_return_request_id`, `customer_return_guard_applied`, `customer_return_policy`) sẽ được xem xét tách thành event `ORDER_RETURN_REQUESTED` riêng trong phase structural refactor sau; hiện tại loại khỏi payload vì chỉ relevant cho 1 transition trong nhiều.

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174003",
  "event_type": "ORDER_STATUS_CHANGED",
  "aggregate_type": "Order",
  "aggregate_id": "10001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T10:05:30Z",
  "source": "order-service",
  "schema_version": 1,
  "payload": {
    "order_id": 10001,
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "old_status": "PAID",
    "new_status": "PROCESSING",
    "reason": "POST_PAYMENT_SAGA_COMPLETE",
    "last_event_sequence_id": 5,
    "changed_at": "2026-02-28T10:05:30Z"
  }
}
```

> **Canonical user_id:** Order Service phát `user_id` dạng UUID theo chuẩn thống nhất toàn hệ thống.

> **Reason contract mở rộng:** ngoài `POST_PAYMENT_SAGA_COMPLETE`, event này còn dùng cho lane refund/recovery với các reason như `ADMIN_FORCE_REFUND_REQUESTED`, `RETURN_API_ACCEPTED`, `INVENTORY_RETURN_CONFIRMED`, `LATE_PAYMENT_RECOVERED`.

### 3.5. ORDER_ITEM_STATUS_CHANGED

**Ngữ cảnh:** Mỗi khi Order Item Delivery FSM transition (trigger bởi `INVENTORY_SHIP_TO_CUSTOMER` hoặc buyer API trả hàng). Xem [Order Item Delivery FSM](order_state_machine.md#2-order-item-delivery-fsm).
**Consumers chính:** Notification (gửi push notification cho khách).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174004",
  "event_type": "ORDER_ITEM_STATUS_CHANGED",
  "aggregate_type": "OrderItem",
  "aggregate_id": "2001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T14:00:00Z",
  "source": "order-service",
  "schema_version": 1,
  "payload": {
    "order_id": 10001,
    "order_item_id": 2001,
    "old_status": "PROCESSING",
    "new_status": "DELIVERED",
    "last_event_sequence_id": 5,
    "changed_at": "2026-02-28T14:00:00Z"
  }
}
```

> **Ordering contract cho consumers:** Topic vẫn partition theo `order_id` để giữ lifecycle theo đơn, nhưng Notification consumer (và mọi consumer khác) phải guard theo `order_item_id + last_event_sequence_id` để chống out-of-order.

### 3.6. ORDER_REFUNDED

**Ngữ cảnh:** Lane refund hoàn tất thành công (return sau giao hàng hoặc admin force refund). FSM `RETURN_REQUESTED` -> `REFUNDED`. Xem [State Machine](order_state_machine.md#3-bảng-chi-tiết-transitions).
**Consumers chính:**
- **Promotion Service:** Trigger [Refund Budget](../promotion/promotion_workflow.md#5-luồng-đổi-trả-hàng--hoàn-tiền-refund-flow) theo `promotion_refund_allocations[]`.
- **Notification:** Đồng bộ trạng thái hoàn tiền thành công.

> **Phase 20 (Payload Simplification):** Bỏ `single_refund_transaction` (đã invariant — 1 refund per order), `customer_return_guard_applied`/`customer_return_policy` (consumer không dùng), `refund_scope_locked_at`/`inventory_return_confirmed_at` (audit only, có trong workflow history), `returned_items[]` chi tiết (audit only, **KHÔNG dùng để tính toán** theo contract), `promotions_applied[]` (Promotion chỉ cần `promotion_refund_allocations[]`). Trong `promotion_refund_allocations[]` thu gọn: bỏ `scope_admin_id` (luôn PLATFORM, Phase 14), `partial_restore_rule` (Promotion tự xử lý qua `idempotency_key`), `voucher_restore_mode` (Promotion tự quyết — Phase 23: luôn giữ `USED`, không restore), `platform_funding_refund` (luôn = `admin_funding_refund`).

> **Phase 21:** Bỏ `admin_funding_refund` khỏi `promotion_refund_allocations[]` (luôn = `refunded_discount_amount` ở PLATFORM-only system). Schema `order_promotions` đã DROP column `admin_funding` (xem [DB Design §1.4](order_db_design.md#14-order_promotions-chi-tiết-áp-dụng-khuyến-mãi)). Allocation giờ chỉ còn 2 field: `promotion_id` + `refunded_discount_amount`.

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174005",
  "event_type": "ORDER_REFUNDED",
  "aggregate_type": "Order",
  "aggregate_id": "10001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-03-05T10:00:00Z",
  "source": "order-service",
  "schema_version": 1,
  "payload": {
    "order_id": 10001,
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "status": "REFUNDED",
    "last_event_sequence_id": 11,
    "refund_transaction_id": "RFD-DEF456",
    "refund_type": "FULL",
    "refund_amount": 995000,
    "refund_trigger_source": "API_RETURN",
    "customer_return_request_id": "fdf4d7d3-4d35-4a61-a55a-8d2b1a315b6a",
    "returned_item_ids": [2001],
    "promotion_refund_allocations": [
      {
        "promotion_id": 100,
        "refunded_discount_amount": 50000
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
> **Contract deterministic cho Promotion:** Với `refund_type = PARTIAL`, Order phải phát `promotion_refund_allocations[]` để chỉ rõ số tiền hoàn theo từng promotion. Promotion **không** tự suy luận từ tỷ lệ item khi thiếu allocation. Mỗi entry trong allocation chỉ chứa: `promotion_id` + `refunded_discount_amount`.
>
> **Partial voucher policy:** `PARTIAL` chỉ hoàn voucher `PLATFORM` khi refund scope bao phủ toàn bộ item của platform đó trong order. PLATFORM allocation trong partial phải bằng `0` nếu scope không bao phủ hết platform. (Phase 14: chỉ còn voucher type `PLATFORM`; `SHIPPING`/`FREE_SHIPPING` đã bị loại bỏ.)
>
> **Wallet validity contract (Phase 23):** Promotion **không** restore `USED -> SAVED` khi xử lý `ORDER_REFUNDED`. Voucher luôn giữ trạng thái `USED`. Xem [Refund Voucher Policy ADR §1.2](../order/refund_voucher_policy.md#12-wallet-validity-voucher-restore-policy).
>
> **One-shot scope:** Chính sách one-shot chỉ áp khi `refund_trigger_source = API_RETURN`. Trigger từ admin không dùng guard này (Buyer API không được mở lại quyền submit mới cho cùng order, bất kể kết quả refund). Guard được enforce qua cột `orders.buyer_return_locked_at` (CAS row-level lock — xem [DB Design §1.1](order_db_design.md#11-orders-master-order) và [Workflow §3.1](order_workflow.md#31-api-return-order-post-v1ordersorderidreturn)) — internal DB invariant, không truyền qua event payload.
>
> **Policy B invariant:** Mỗi `order_id` chỉ có một `refund_transaction_id` do Order khởi tạo. Refund workflow chỉ mở từ `API_RETURN` hoặc `ADMIN_FORCE_REFUND_REQUESTED`.
>
> **Non-blocking invariant:** Dù xử lý budget/voucher ra sao (`RETAINED_USED`/`VOUCHER_UNAVAILABLE`), transaction hoàn tiền ở Payment không bị rollback.

### 3.7. ORDER_COMPLETED

**Ngữ cảnh:** Tất cả items `DELIVERED`. FSM `PROCESSING` → `COMPLETED`. Tự động bởi [Auto-Complete Logic](order_state_machine.md#5-auto-complete-logic-processing--completed).
**Consumers chính:** Analytics, admin Settlement, Review Service.

> **Phase 20 (Payload Simplification):** Bỏ `variant_name` trong `items[]` (Review Service không cần — đã lưu ở `order_item_lines.snapshot_name` và có thể fetch qua `GET /v1/orders/{id}`).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174006",
  "event_type": "ORDER_COMPLETED",
  "aggregate_type": "Order",
  "aggregate_id": "10001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-03-02T16:00:00Z",
  "source": "order-service",
  "schema_version": 1,
  "payload": {
    "order_id": 10001,
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "status": "COMPLETED",
    "last_event_sequence_id": 9,
    "final_amount": 995000,
    "item_count": 2,
    "items": [
      {
        "product_id": "prod-001",
        "sku_id": "sku-001",
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
> - `variant_name` không còn ở đây (Phase 20). Review Service cần thì gọi `GET /v1/orders/{id}` để lấy detail.

### 3.8. ORDER_REFUND_FAILED

**Ngữ cảnh:** Payment trả `REFUND_FAILED` hoặc `REFUND_REVERSED` trong lane refund (bao gồm return flow và admin force refund). Order giữ FSM ở `RETURN_REQUESTED` và phát event escalation để Notification/Ops xử lý.
**Consumers chính:** Notification Service (critical lane), Ops Dashboard.

> **Phase 20 (Payload Simplification):** Bỏ `payment_transaction_id` (Order không cần route workflow — đã trong `OrderRefundWorkflow`), `customer_return_guard_applied`/`customer_return_policy` (consumer không dùng), `reason` (trùng `error_code`).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174006b",
  "event_type": "ORDER_REFUND_FAILED",
  "aggregate_type": "Order",
  "aggregate_id": "10001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-03-05T10:10:00Z",
  "source": "order-service",
  "schema_version": 1,
  "payload": {
    "order_id": 10001,
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "status": "RETURN_REQUESTED",
    "payment_status": "REFUND_FAILED",
    "last_event_sequence_id": 10,
    "refund_transaction_id": "RFD-DEF456",
    "refund_trigger_source": "API_RETURN",
    "customer_return_request_id": "fdf4d7d3-4d35-4a61-a55a-8d2b1a315b6a",
    "error_code": "REFUND_WINDOW_EXPIRED",
    "error_message": "Cong thanh toan tu choi hoan",
    "failed_at": "2026-03-05T10:10:00Z"
  }
}
```

> **Canonical user_id:** Order Service phát `user_id` dạng UUID theo chuẩn thống nhất toàn hệ thống.

> Khi event là `ORDER_REFUND_FAILED` từ lane `API_RETURN`, policy one-shot vẫn giữ nguyên; Buyer API không được mở lại quyền submit mới cho cùng order.

## 4. Chi Tiết Các Loại Event Tiêu Thụ (Consumed Events)

### 4.1. PAYMENT_SUCCESS (Từ Payment Service)

**Topic:** `t.payment.events`
**Consumer Group:** `order-payment-consumer`
**Xử lý bởi:** [Payment Status Consumer Worker](order_workers.md#4-payment-status-consumer-kafka-consumer)

> **Phase 20 (Payload Simplification):** Bỏ `user_id` (Order có sẵn từ DB), `currency` (luôn VND), `provider` (luôn STRIPE), `provider_transaction_id`/`provider_charge_id` (Payment Service giữ; Order không cần cross-ref Stripe), `payment_method_type` (Order không phân loại), `payment_event_sequence_id` (Order không guard theo; chỉ telemetry).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174007",
  "event_type": "PAYMENT_SUCCESS",
  "aggregate_type": "Payment",
  "aggregate_id": "TXN-ABC123",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T10:05:00Z",
  "source": "payment-service",
  "schema_version": 1,
  "payload": {
    "payment_transaction_id": "TXN-ABC123",
    "order_id": 10001,
    "amount": 995000,
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

> **Ordering contract payment stream:** `payment_event_sequence_id` (cho `PAYMENT_*`) và `refund_event_sequence_id` (cho `REFUND_*`) là counter do **Payment Service** phát ra, đơn điệu theo từng `order_id`. Order Service **không** dùng 2 field này làm guard — chỉ dùng để telemetry/debug khi cần trace.
>
> **Guard key chính của Order cho consumed events** (Phase 22): dùng `event_id` (CloudEvents envelope) + **Redis SETNX** TTL 30 ngày (`payment:signal:{order_id}:{txn_id}:{event_type}` cho Payment, `inventory:ship:applied:{order_id}:{warehouse_id}:{shipped_at}` cho Inventory Ship, `inventory:return:confirm:{order_id}:{refund_request_id}` cho Inventory Return) + FSM row-level lock (`UPDATE ... WHERE status='OLD_STATUS'`).
>
> **Counter `orders.last_event_sequence_id` (per `order_id`)** chỉ dùng cho **Order's own produced events** (`ORDER_*`) — tăng mỗi lần Order insert `outbox_events`, không liên quan đến event Order consume từ service khác.

### 4.2. PAYMENT_FAILED (Từ Payment Service)

**Topic:** `t.payment.events`
**Consumer Group:** `order-payment-consumer`

> **Phase 20 (Payload Simplification):** Bỏ `user_id`, `provider`, `payment_event_sequence_id`, `error_message` (Notification tự map từ `error_code` qua i18n key).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174008",
  "event_type": "PAYMENT_FAILED",
  "aggregate_type": "Payment",
  "aggregate_id": "TXN-ABC123",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T10:06:00Z",
  "source": "payment-service",
  "schema_version": 1,
  "payload": {
    "payment_transaction_id": "TXN-ABC123",
    "order_id": 10001,
    "error_code": "INSUFFICIENT_BALANCE",
    "failed_at": "2026-02-28T10:06:00Z"
  }
}
```
**Logic xử lý:** Worker signal checkout workflow (`PaymentResult.FAILED`) -> Temporal chạy compensation stack pre-payment (`CancelPayment` -> `RollbackPromotion` -> `RollbackInventory` -> `CancelOrder`) -> sau khi compensation hoàn tất mới commit FSM `PENDING_PAYMENT` -> `CANCELLED` và produce `ORDER_CANCELLED`.

### 4.3. PAYMENT_EXPIRED (Từ Payment Service)

**Topic:** `t.payment.events`
**Consumer Group:** `order-payment-consumer`

> **Phase 20 (Payload Simplification):** Bỏ `user_id`, `payment_event_sequence_id`, `last_event_sequence_id` (legacy field lẫn lộn Order scope).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174009",
  "event_type": "PAYMENT_EXPIRED",
  "aggregate_type": "Payment",
  "aggregate_id": "TXN-ABC123",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T10:15:00Z",
  "source": "payment-service",
  "schema_version": 1,
  "payload": {
    "payment_transaction_id": "TXN-ABC123",
    "order_id": 10001,
    "expired_at": "2026-02-28T10:15:00Z"
  }
}
```
**Logic xử lý:** Tương tự `PAYMENT_FAILED`. Temporal Timer cũng sẽ tự timeout song song — event nào đến trước xử lý trước, event đến sau bị idempotency check bỏ qua.

### 4.4. REFUND_PROCESSING (Từ Payment Service)

**Topic:** `t.payment.events`
**Consumer Group:** `order-payment-consumer`

> **Phase 20 (Payload Simplification):** Bỏ `payment_transaction_id` (Order không cần cross-ref payment txn khi đã route theo `order_id`), `user_id`, `refund_amount` (Order đã lock từ workflow), `provider_refund_id`, `refund_event_sequence_id`.

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174009b",
  "event_type": "REFUND_PROCESSING",
  "aggregate_type": "Payment",
  "aggregate_id": "RFD-DEF456",
  "correlation_id": "wf-10001",
  "timestamp": "2026-03-05T09:00:05Z",
  "source": "payment-service",
  "schema_version": 1,
  "payload": {
    "refund_transaction_id": "RFD-DEF456",
    "order_id": 10001,
    "processing_at": "2026-03-05T09:00:05Z"
  }
}
```

### 4.5. REFUND_SUCCESS (Từ Payment Service)

**Topic:** `t.payment.events`
**Consumer Group:** `order-payment-consumer`

> **Phase 20 (Payload Simplification):** Bỏ `payment_transaction_id`, `user_id`, `provider_refund_id`, `refund_event_sequence_id`.

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174010",
  "event_type": "REFUND_SUCCESS",
  "aggregate_type": "Payment",
  "aggregate_id": "RFD-DEF456",
  "correlation_id": "wf-10001",
  "timestamp": "2026-03-05T10:00:00Z",
  "source": "payment-service",
  "schema_version": 1,
  "payload": {
    "refund_transaction_id": "RFD-DEF456",
    "order_id": 10001,
    "refund_amount": 995000,
    "refunded_at": "2026-03-05T10:00:00Z"
  }
}
```
**Logic xử lý:** Worker route theo `order_id` và signal `OrderRefundWorkflow` (`RefundResult.SUCCESS`) -> refund workflow commit `orders.payment_status = 'REFUNDED'`, FSM `RETURN_REQUESTED` -> `REFUNDED`, rồi produce `ORDER_REFUNDED` (bắt buộc kèm `refund_transaction_id` + `promotion_refund_allocations[]`). Flow này chỉ xảy ra sau khi workflow đã nhận signal `INVENTORY_RETURN_RECEIVED_ALL`. Worker không reset one-shot guard của Buyer API.

### 4.6. REFUND_FAILED (Từ Payment Service)

**Topic:** `t.payment.events`
**Consumer Group:** `order-payment-consumer`

> **Phase 20 (Payload Simplification):** Bỏ `payment_transaction_id`, `user_id`, `refund_event_sequence_id`, `error_message` (Notification tự map từ `error_code`).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174010b",
  "event_type": "REFUND_FAILED",
  "aggregate_type": "Payment",
  "aggregate_id": "RFD-DEF456",
  "correlation_id": "wf-10001",
  "timestamp": "2026-03-05T10:10:00Z",
  "source": "payment-service",
  "schema_version": 1,
  "payload": {
    "refund_transaction_id": "RFD-DEF456",
    "order_id": 10001,
    "error_code": "REFUND_WINDOW_EXPIRED",
    "failed_at": "2026-03-05T10:10:00Z"
  }
}
```
**Logic xử lý:** Worker signal `OrderRefundWorkflow` (`RefundResult.FAILED`) -> refund workflow commit `orders.payment_status = 'REFUND_FAILED'`, giữ FSM ở `RETURN_REQUESTED`, phát `ORDER_REFUND_FAILED` cho Notification/Ops và escalation lane. Nếu trigger ban đầu là `API_RETURN`, policy one-shot vẫn giữ nguyên.

### 4.7. REFUND_REVERSED (Từ Payment Service - Optional)

**Topic:** `t.payment.events`
**Consumer Group:** `order-payment-consumer`

> **Phase 20 (Payload Simplification):** Bỏ `payment_transaction_id`, `user_id`, `provider_refund_id`, `refund_event_sequence_id`, `reason_message` (Notification tự map từ `reason_code`).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174010c",
  "event_type": "REFUND_REVERSED",
  "aggregate_type": "Payment",
  "aggregate_id": "RFD-DEF456",
  "correlation_id": "wf-10001",
  "timestamp": "2026-03-06T08:00:00Z",
  "source": "payment-service",
  "schema_version": 1,
  "payload": {
    "refund_transaction_id": "RFD-DEF456",
    "order_id": 10001,
    "reason_code": "REFUND_REVERSED_BY_PROVIDER",
    "reversed_at": "2026-03-06T08:00:00Z"
  }
}
```
**Logic xử lý:** Worker signal `OrderRefundWorkflow` (`RefundResult.REVERSED`) -> refund workflow commit `orders.payment_status = 'REFUND_FAILED'` (gộp về cùng status, lý do gốc lưu trong `ORDER_REFUND_FAILED` event payload `error_code`/`reason_code` để Ops xử lý), giữ FSM ở `RETURN_REQUESTED`, phát `ORDER_REFUND_FAILED` với reason escalation cho Notification/Ops.

### 4.8. INVENTORY_SHIP_TO_CUSTOMER (Từ Inventory Service)

**Topic:** `t.inventory.events`
**Consumer Group:** `order-inventory-ship-consumer`
**Xử lý bởi:** [Inventory Ship Consumer Worker](order_workers.md#5-inventory-ship-consumer-kafka-consumer)

> **Phase 20 (Payload Simplification):** Bỏ `courier_note` (audit only, không ảnh hưởng FSM).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174011",
  "event_type": "INVENTORY_SHIP_TO_CUSTOMER",
  "aggregate_type": "Inventory",
  "aggregate_id": "10001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-03-02T16:00:00Z",
  "source": "inventory-service",
  "schema_version": 1,
  "payload": {
    "order_id": 10001,
    "warehouse_id": 5,
    "shipped_at": "2026-03-02T16:00:00Z"
  }
}
```
**Logic xử lý:** Worker nhận event → Query `inventory_reservations` theo `(order_id, warehouse_id)` lọc `status='DEDUCTED'` để lấy `order_item_ids` cần chuyển sang `DELIVERED` (filter `warehouse_id` là bắt buộc cho multi-warehouse order, tránh DELIVERED nhầm cho item ở kho khác) → Với mỗi `order_item_id`, Update `order_items.status` từ `PROCESSING` sang `DELIVERED` (idempotent theo `event_id`) → Produce `ORDER_ITEM_STATUS_CHANGED` → Chạy Auto-Complete CAS cho master order (transition `PROCESSING` → `COMPLETED` khi tất cả items đã `DELIVERED`).

### 4.9. INVENTORY_RETURN_RECEIVED_ALL (Từ Inventory Service)

**Topic:** `t.inventory.events`
**Consumer Group:** `order-inventory-return-consumer`

> **Phase 20 (Payload Simplification):** Bỏ `order_item_ids[]` (Order Service derive từ `refund_request_id` + `order_id` trong `OrderRefundWorkflow` đã lock scope), `confirmed_at` (trùng envelope `timestamp`).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174013b",
  "event_type": "INVENTORY_RETURN_RECEIVED_ALL",
  "aggregate_type": "Inventory",
  "aggregate_id": "10001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-03-05T09:58:30Z",
  "source": "inventory-service",
  "schema_version": 1,
  "payload": {
    "order_id": 10001,
    "refund_request_id": "fdf4d7d3-4d35-4a61-a55a-8d2b1a315b6a"
  }
}
```

**Logic xử lý:** Worker kiểm tra idempotency rồi signal `InventoryReturnReceivedAll` cho `OrderRefundWorkflow` theo `order_id`. Chỉ sau signal này workflow mới được gọi Payment refund API.

---

## 5. Schema Versioning & Backward Compatibility

*   **Backward Compatibility Rule:** Chỉ được phép **thêm field mới**. Không được xóa hoặc đổi tên field hiện có.
*   **Consumer Resilience:** Tất cả consumers PHẢI ignore unknown fields.
*   **Idempotency:** Mỗi event mang `event_id` unique. Phase 22: downstream consumers chỉ dùng **Redis SETNX** (`SETNX payment:signal:{order_id}:{txn_id}:{event_type}` cho Order Payment consumer, `SETNX inventory:ship:applied:{order_id}:{warehouse_id}:{shipped_at}` cho Order Inventory Ship consumer, `SETNX inventory:return:confirm:{order_id}:{refund_request_id}` cho Order Inventory Return consumer) với TTL 30 ngày để chống xử lý trùng. Đảm bảo ở tầng DB nhờ FSM row-level lock + CloudEvents `event_id` envelope.
