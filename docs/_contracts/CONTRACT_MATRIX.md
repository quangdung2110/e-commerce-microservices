# Service Contract Matrix (SSOT)

**Single Source of Truth** cho mọi hợp đồng (event/payload) xuyên suốt 5 service: **Checkout, Order, Payment, Inventory, Promotion**.

> Mọi service doc khác (workflow, events, db_design, ...) khi nhắc đến event/payload phải tham chiếu về file này. File này là chuẩn cuối cùng, không có file nào override.

> **Lưu ý (Phase 13):** Shipping Service đã được loại bỏ hoàn toàn khỏi hệ thống để giảm độ phức tạp. Mọi contract liên quan tới vận chuyển (3PL, courier, tracking, shipping fee, FREE_SHIPPING voucher) đã bị xóa.

> **Lưu ý (Phase 18):** Hệ thống **chỉ dùng 1 payment provider duy nhất (Stripe)**. Bỏ hoàn toàn VNPay, MoMo, ZaloPay; bỏ Provider Adapter Pattern. Webhook URL cố định: `POST /v1/payments/webhook/stripe`. Cột `orders.payment_method` đã DROP. Xem [CHANGELOG.md Phase 18](CHANGELOG.md#phase-18--single-payment-provider-stripe).

---

## 0. Quy ước chuẩn (Canonical Conventions)

### 0.1. ID Convention

| Field | Type | Owner | Ghi chú |
|---|---|---|---|
| `user_id` | **UUID** (chuẩn hóa toàn hệ thống) | User Service (canonical) | Tất cả 5 service dùng UUID (Payment & Promotion đã được sync tại Phase 1). |
| `order_id` | BIGINT | Order Service | Internal ID. |
| `order_item_id` | BIGINT | Order Service | Per-item delivery unit. |
| `sku_id` | VARCHAR(64) | Product Service | SKU identifier. |
| `product_id` | VARCHAR(64) | Product Service | Product identifier. |
| `warehouse_id` | BIGINT | Inventory Service | Warehouse identifier. |
| `promotion_id` | BIGINT | Promotion Service | Promotion identifier. |
| `refund_request_id` | VARCHAR(64) | Order Service (sinh tại OrderRefundWorkflow) | Cross-service refund scope key. |
| `refund_transaction_id` | VARCHAR(120) | Payment Service (sinh tại /v1/payments/{txn_id}/refund) | Cross-service refund transaction key. |
| `payment_transaction_id` | VARCHAR(50) | Payment Service (đóng vai trò `txn_ref` gửi lên Stripe PaymentIntent) | Payment unique ID. |
| `checkout_token` | UUID v4 | Checkout Service (sinh tại Preview) | One-time-use per checkout session. |
| `route_plan_id` | UUID | Inventory Service (sinh tại /v1/inventory/routing-options) | Warehouse routing plan key. |
| `address_id` | UUID | User Service | Canonical address id. |

### 0.2. Kafka Envelope (CloudEvents Wrapper)

Mọi message trên Kafka đều mang header chuẩn sau:

| Field | Type | Required | Ghi chú |
|---|---|:---:|---|
| `event_id` | UUID | ✅ | Khóa delivery-level unique (dedup primary). |
| `event_type` | STRING | ✅ | VD: `ORDER_PAID`, `PAYMENT_SUCCESS`. |
| `aggregate_type` | STRING | ✅ | VD: `Order`, `Payment`, `Inventory`, `Promotion`, `MarkdownRule`. |
| `aggregate_id` | STRING | ✅ | Theo `aggregate_type` (vd: `order_id`, `payment_transaction_id`, `sku_id`). |
| `correlation_id` | STRING | ✅ | Temporal WorkflowID (vd: `order-checkout-{checkout_token}`, `order-refund-{order_id}`). |
| `business_id` | STRING | ✅ | ID nghiệp vụ theo event (vd: `order_id`, `claim_id`). |
| `timestamp` | ISO 8601 UTC | ✅ | Thời điểm event tạo. |
| `source` | STRING | ✅ | Tên service: `checkout-service`, `order-service`, `payment-service`, `inventory-service`, `promotion-service`. |
| `schema_version` | INT | ✅ | Hiện tại = `1`. Bắt buộc cho mọi event. |
| `payload` | JSON Object | ✅ | Nội dung chi tiết theo từng `event_type`. |

### 0.3. Voucher Convention

Hệ thống chỉ có **2 role**: `ADMIN` (do Platform) và `CUSTOMER` (người mua). Không có `SHOP`/`SELLER`.

Voucher types (theo `discount_type`):

| `discount_type` | `deleted_owner_type` | Vai trò |
|---|---|---|
| `PERCENTAGE` | `PLATFORM` | Voucher giảm % sản phẩm (toàn đơn) |
| `FIXED_AMOUNT` | `PLATFORM` | Voucher giảm số tiền cố định (toàn đơn) |

> [!IMPORTANT]
> Canonical enum cho `deleted_owner_type` chỉ còn **1 giá trị duy nhất**: `PLATFORM` (uppercase). Mọi chỗ ghi `admin` (lowercase) là typo, sẽ được sửa trong **Phase 11**.

> **Phase 21:** Bỏ column `order_promotions.promotion_type` (Phase 21 chỉ còn giá trị `CART_DISCOUNT` duy nhất — không cần lưu). Schema `order_promotions` giờ chỉ chứa `id`, `order_id`, `promotion_id`, `discount_amount`. Xem [Order DB §1.4](../order/order_db_design.md#14-order_promotions-chi-tiết-áp-dụng-khuyến-mãi) + [Promotion DB §2.2](../promotion/promotion_db_design.md#22-mapping-với-order-service-order_promotions).

> Voucher refund allocation (xem chi tiết [ADR Refund Voucher Policy](../order/refund_voucher_policy.md)):
> - `FULL`: hoàn toàn bộ PLATFORM allocation
> - `PARTIAL`: chỉ hoàn PLATFORM allocation khi scope bao phủ toàn bộ item của platform đó

### 0.4. Idempotency Convention

Mỗi service có 2 lớp idempotency (xem chi tiết [IDEMPOTENCY_GUIDELINES.md](IDEMPOTENCY_GUIDELINES.md) - Phase 7):

- **Layer 1 (fast path)**: Redis `SETNX` với key format theo convention dưới đây
- **Layer 2 (durable)**: PostgreSQL unique constraint bảng idempotency

Key format chuẩn:

| Loại | Format | TTL | Owner | Layer 2 Table |
|---|---|---|---|---|
| Checkout Preview Rate Limit | `checkout:ratelimit:preview:{user_id}` | 60s | Checkout | (in-memory only) |
| Checkout Submit Rate Limit | `checkout:ratelimit:submit:{user_id}` | 60s | Checkout | (in-memory only) |
| Checkout Preview Lock | `checkout:lock:preview:{user_id}` | 5s | Checkout | (transient) |
| Checkout Submit Lock | `checkout:lock:submit:{user_id}` | 30s | Checkout | (transient) |
| Checkout Response Cache | `checkout:response:{user_id}:{token}` | 20 phút | Checkout | (transient) |
| Checkout Payload Cache | `checkout:payload:{user_id}:{token}` | 20 phút | Checkout | (transient) |
| Payment Idempotency | `payment:initiate:{order_id}` | 15 phút + 60s đệm | Payment | `payment_idempotency` |
| Payment Refund Idempotency | `payment:refund:{order_id}:{refund_transaction_id}` | 15 phút | Payment | `refund_transactions.idempotency_key` |
| Webhook Dedup | `payment:webhook:STRIPE:{stripe_event_id}:{event_type}` | 7 ngày | Payment | `webhook_logs` UNIQUE (`provider`, `provider_transaction_id`, `event_type`) |
| Inventory Reserve | `inventory:idemp:reserve:{sku_id:warehouse_id}:order:{order_id}` | 30 ngày | Inventory | `inventory_ledger.event_id` UNIQUE |
| Inventory Release | `inventory:idemp:release:{sku_id:warehouse_id}:order:{order_id}` | 30 ngày | Inventory | `inventory_ledger.event_id` UNIQUE |
| Inventory Deduct | `inventory:idemp:deduct:{sku_id:warehouse_id}:order:{order_id}` | 30 ngày | Inventory | `inventory_ledger.event_id` UNIQUE |
| Inventory Return Restock | `inventory:return_restock:{order_id}:{order_item_id}` | 7 ngày | Inventory | `inventory_ledger.event_id` UNIQUE |
| Order Saga Idempotency (Sync API) | `Idempotency-Key` header → Redis SETNX `order:req:{action}:{idempotency_key}` (Phase 22 — bỏ `order_request_idempotency`) | 7 ngày (TTL Redis) | Order | (không có — Temporal WorkflowID + Redis) |
| Order One-shot Guard (Buyer return) | `orders.buyer_return_locked_at` column (CAS row-level lock, Phase 22) | Vòng đời order | Order | (column inline) |
| Order Event Idempotency (Async/Signal) | Redis SETNX `payment:signal:{order_id}:{txn_id}:{event_type}` / `inventory:ship:applied:{order_id}:{warehouse_id}:{shipped_at}` / `inventory:return:confirm:{order_id}:{refund_request_id}` (Phase 22 — bỏ `order_event_idempotency`) | 30 ngày (TTL Redis) | Order | (không có — FSM row-level lock + CloudEvents `event_id`) |
| Promotion Reserve (sync) | `promo:lock:redeem:{promotion_id}:{order_id}` | 10s lock TTL | Promotion | `promotion_event_idempotency` |
| Promotion Cancel (sync) | `promo:lock:cancel:{promotion_id}:{order_id}` | 10s lock TTL | Promotion | `promotion_event_idempotency` |
| Promotion Refund (sync) | `promo:lock:refund:{promotion_id}:{order_id}` | 10s lock TTL | Promotion | `promotion_event_idempotency` |
| Order → Promotion idempotency_key | `REDEEM_FINAL:{order_id}:{promotion_id}` / `CANCEL_FINAL:{order_id}:{promotion_id}` / `REFUND_FULL:{order_id}:{promotion_id}:{refund_request_id}` / `REFUND_PARTIAL:{order_id}:{promotion_id}:{refund_request_id}` | theo Retention | Order (sinh) → Promotion (lưu) | `promotion_event_idempotency.event_id` (PK) |

### 0.5. Workflow Identity Convention

| Workflow | WorkflowID Format | Owner |
|---|---|---|
| `OrderCheckoutWorkflow` | `order-checkout-{checkout_token}` | Order |
| `OrderRefundWorkflow` | `order-refund-{order_id}` | Order |
| `OrderLatePaymentRecoveryWorkflow` | `order-late-payment-recovery-{order_id}` | Order |

### 0.6. Sequence/Version Field (chống out-of-order)

> **Phase 19 đã chốt:** Bỏ `version`/`order_version`/`item_version` (bỏ optimistic locking). Counter `last_event_sequence_id` (per `order_id`) thay thế 2 counter cũ trong Order's own produced events. Counter `payment_event_sequence_id`/`refund_event_sequence_id` vẫn còn trong event Payment Service phát ra nhưng **Order KHÔNG dùng làm guard** — chỉ telemetry.

Mỗi stream sở hữu một guard key tăng đơn điệu:

| Stream | Field | Scope | Producer |
|---|---|---|---|
| Order lifecycle (Order's own events) | `last_event_sequence_id` | Per `order_id` | Order Service |
| Payment stream (do Payment tự quản) | `payment_event_sequence_id` | Per `order_id` | Payment Service |
| Refund stream (do Payment tự quản) | `refund_event_sequence_id` | Per `order_id` | Payment Service |

> **Phase 21:** Bỏ stream "Order Metrics (CQRS)" — table `order_product_metrics` + event `ORDER_PRODUCT_METRICS_UPDATED` + topic `t.search.metrics.events` đã bị loại bỏ hoàn toàn (Search Service không còn cần `product_sold_count`).

> **Phase 22:** Consumer Order Service guard theo `event_id` (CloudEvents envelope) + **Redis SETNX** (TTL 30 ngày) + **FSM row-level lock** (`UPDATE ... WHERE status='OLD_STATUS'`). Bảng `order_event_idempotency` đã bỏ (xem [Order DB §2.0](../order/order_db_design.md#20-sequence-id-consolidation-thay-thế-event_sequence_id-riêng-biệt) và [CHANGELOG Phase 22](CHANGELOG.md#phase-22--simplify-idempotency-schema-order)).

---

## 1. Kafka Topic Map (SSOT)

| Topic Name | Owner | Partition Key | Retention | Consumers chính |
|---|---|---|---|---|
| `t.order.events` | Order | `order_id` | theo policy | Promotion, Inventory (refund), Cart, Notification, Analytics, Review |
| `t.payment.events` | Payment | `order_id` | theo policy | Order Service |
| `t.payment.webhooks.raw` | Payment (internal) | `STRIPE:stripe_event_id` | theo policy | Payment internal Webhook Processor |
| `t.inventory.events` | Inventory (CDC) | `sku_id` (stock) / `order_id` (return) | theo policy | Product (display), Order (return), Analytics |
| `t.promotion.markdown.events` | Promotion (CDC) | `rule_id` | theo policy | Product Service |
| `t.promotion.coupon.events` | Promotion (CDC) | `order_id` (lifecycle) / `claim_id` (claim) | theo policy | Notification, Analytics |
| `t.promotion.budget.events` | Promotion (CDC) | `promotion_id` | theo policy | Notification, Frontend (degraded) |

> **Phase 21:** Bỏ topic `t.search.metrics.events` (Order Service không còn publish `ORDER_PRODUCT_METRICS_UPDATED`).

> Tất cả event đều đi qua Outbox Pattern + Debezium CDC trừ khi ghi chú khác (xem [OUTBOX_PATTERNS.md](OUTBOX_PATTERNS.md) - Phase 9).

---

## 2. Event Catalog (SSOT)

### 2.1. Events do **Order Service** Produce

> **Phase 20 (Event Payload Simplification):** Mỗi event chỉ chứa field tối thiểu cần thiết. Field chỉ dùng bởi 1 consumer hoặc có thể derive từ DB/API bị loại bỏ. Schema chi tiết từng event xem [Order Events](../order/order_events.md#3-chi-tiết-các-loại-event-phát-đi-produced-events).

#### `ORDER_CREATED`
- **Aggregate**: `Order` / `aggregate_id = order_id`
- **Trigger**: Sau `CreateOrderActivity` (T1 `CREATED` → `PENDING_PAYMENT`)
- **Consumers**: Notification, Analytics
- **Guard key**: `last_event_sequence_id` (per `order_id`)
- **Required payload fields** (Phase 20): `order_id`, `user_id`, `status`, `last_event_sequence_id`, `final_amount`
- **Removed (Phase 20)**: `delivery_address_snapshot` (Notification fetch qua API), `items[]` (snapshot đã persist trong DB), `promotions_applied[]` (Promotion tự reserve qua Saga), `total_amount`/`total_discount` (chỉ `final_amount` cần cho display), `created_at` (trùng envelope `timestamp`)
- **Schema owner**: `docs/order/order_events.md#31-order_created`

#### `ORDER_PAID`
- **Aggregate**: `Order` / `aggregate_id = order_id`
- **Trigger**: T2 `PENDING_PAYMENT` → `PAID` (mốc tài chính)
- **Consumers**: Cart (cleanup SKU lines), Notification, Analytics
- **Guard key**: `last_event_sequence_id` (per `order_id`)
- **Required payload fields** (Phase 20): `order_id`, `user_id`, `status`, `last_event_sequence_id`, `payment_recovery`, `payment_transaction_id`, `sku_ids[]`, `final_amount`, `paid_at`
- **Removed (Phase 20)**: `cart_id` (Cart derive từ `user_id`), `promotions_applied[]`, `items[]` (chỉ cần `sku_ids[]`), `checkout_submitted_at`
- **Schema owner**: `docs/order/order_events.md#32-order_paid`

#### `ORDER_CANCELLED`
- **Aggregate**: `Order` / `aggregate_id = order_id`
- **Trigger**: T3/T4/T4.1 (pre-payment cancel)
- **Consumers**: Payment (reconcile pending), Notification
- **Guard key**: `last_event_sequence_id` (per `order_id`)
- **Required payload fields** (Phase 20): `order_id`, `user_id`, `status`, `previous_status`, `reason`, `last_event_sequence_id`, `cancelled_at`
- **Removed (Phase 20)**: `items[]`, `promotions_applied[]`
- **Reason enum**: `PAYMENT_TIMEOUT`, `USER_CANCEL`, `ADMIN_FORCE_CANCEL`, `PRE_PAYMENT_SAGA_FAILED`, `PAYMENT_FAILED`
- **Schema owner**: `docs/order/order_events.md#33-order_cancelled`

#### `ORDER_STATUS_CHANGED`
- **Aggregate**: `Order` / `aggregate_id = order_id`
- **Trigger**: Mọi transition FSM (trừ các event dedicated)
- **Consumers**: Notification
- **Guard key**: `last_event_sequence_id` (per `order_id`)
- **Required payload fields** (Phase 20): `order_id`, `user_id`, `old_status`, `new_status`, `reason`, `last_event_sequence_id`, `changed_at`
- **Removed (Phase 20)**: `buyer_tab` (Notification derive từ FSM), `changed_by`/`changed_by_type` (luôn = `system` vì Order là producer duy nhất), `refund_trigger_source`/`customer_return_request_id`/`customer_return_guard_applied`/`customer_return_policy` (sẽ xem xét tách thành event `ORDER_RETURN_REQUESTED` riêng trong phase structural refactor)
- **Reason enum (mở rộng)**: `POST_PAYMENT_SAGA_COMPLETE`, `ADMIN_FORCE_REFUND_REQUESTED`, `RETURN_API_ACCEPTED`, `INVENTORY_RETURN_CONFIRMED`, `LATE_PAYMENT_RECOVERED`, `PAYMENT_SUCCESS_LATE_RECOVERY`
- **Schema owner**: `docs/order/order_events.md#34-order_status_changed`

#### `ORDER_ITEM_STATUS_CHANGED`
- **Aggregate**: `OrderItem` / `aggregate_id = order_item_id`
- **Trigger**: Inventory phát `INVENTORY_SHIP_TO_CUSTOMER` (admin trigger) → Order Service cập nhật item FSM PROCESSING → DELIVERED.
- **Consumers**: Notification
- **Guard key**: `last_event_sequence_id` (per `order_id`) + `order_item_id`
- **Required payload fields** (Phase 20): `order_id`, `order_item_id`, `old_status`, `new_status`, `last_event_sequence_id`, `changed_at`
- **Schema owner**: `docs/order/order_events.md#35-order_item_status_changed`

#### `ORDER_REFUNDED`
- **Aggregate**: `Order` / `aggregate_id = order_id`
- **Trigger**: T10 `RETURN_REQUESTED` → `REFUNDED` (sau khi nhận `REFUND_SUCCESS`)
- **Consumers**: **Promotion (refund budget)**, Notification, Analytics
- **Guard key**: `last_event_sequence_id` (per `order_id`)
- **Required payload fields** (Phase 20):
  - `order_id`, `user_id`, `status`, `last_event_sequence_id`, `refunded_at`
  - `refund_transaction_id` (VARCHAR(120), unique)
  - `refund_type`: `FULL` | `PARTIAL`
  - `refund_amount`
  - `refund_trigger_source`: `API_RETURN` | `ADMIN_FORCE_REFUND_REQUESTED`
  - `customer_return_request_id` (UUID, optional, chỉ khi `API_RETURN`)
  - `returned_item_ids` (BIGINT[])
  - `promotion_refund_allocations[]`: MỖI PHẦN TỬ chứa `promotion_id`, `refunded_discount_amount` (Phase 21 — bỏ `admin_funding_refund`)
- **Removed (Phase 20)**:
  - `single_refund_transaction` (đã invariant — 1 refund per order)
  - `customer_return_guard_applied`, `customer_return_policy` (consumer không dùng)
  - `refund_scope_locked_at`, `inventory_return_confirmed_at` (audit only, có trong workflow history)
  - `returned_items[]` chi tiết (audit only, **KHÔNG dùng để tính toán**)
  - `promotions_applied[]` (Promotion chỉ cần `promotion_refund_allocations[]`)
  - Trong `promotion_refund_allocations[]`: `scope_admin_id` (luôn PLATFORM, Phase 14), `partial_restore_rule` (Promotion tự xử lý qua `idempotency_key`), `voucher_restore_mode` (Promotion tự quyết — Phase 23: luôn giữ `USED`), `platform_funding_refund` (luôn = `admin_funding_refund`), `admin_funding_refund` (luôn = `refunded_discount_amount` ở PLATFORM-only system, Phase 21)
- **Schema owner**: `docs/order/order_events.md#36-order_refunded`

#### `ORDER_COMPLETED`
- **Aggregate**: `Order` / `aggregate_id = order_id`
- **Trigger**: T8.3 `PROCESSING` → `COMPLETED` (tất cả items `DELIVERED`, auto-complete)
- **Consumers**: Analytics, admin Settlement, **Review Service**
- **Guard key**: `last_event_sequence_id` (per `order_id`)
- **Required payload fields** (Phase 20): `order_id`, `user_id`, `status`, `final_amount`, `item_count`, `items[]` (bắt buộc cho Review, với `product_id` + `sku_id` + `quantity`), `last_event_sequence_id`, `completed_at`
- **Removed (Phase 20)**: `variant_name` (Review không cần)
- **Auto-complete coalescing window**: 5 giây
- **Schema owner**: `docs/order/order_events.md#37-order_completed`

#### `ORDER_REFUND_FAILED`
- **Aggregate**: `Order` / `aggregate_id = order_id`
- **Trigger**: `REFUND_FAILED` / `REFUND_REVERSED` trong lane refund
- **Consumers**: Notification (critical), Ops Dashboard
- **Guard key**: `last_event_sequence_id` (per `order_id`)
- **Required payload fields** (Phase 20): `order_id`, `user_id`, `status`, `payment_status`, `last_event_sequence_id`, `refund_transaction_id`, `refund_trigger_source`, `customer_return_request_id`, `error_code`, `error_message`, `failed_at`
- **Removed (Phase 20)**: `payment_transaction_id` (Order không cần route workflow), `customer_return_guard_applied`, `customer_return_policy`, `reason` (trùng `error_code`)
- **Schema owner**: `docs/order/order_events.md#39-order_refund_failed`

### 2.2. Events do **Payment Service** Produce (Topic: `t.payment.events`)

> Partition key: `order_id` (cho tất cả event) để giữ ordering theo đơn.
>
> **Phase 18 (Single Payment Provider - Stripe):** Tất cả `provider` trong payload = `"STRIPE"`. `provider_transaction_id` = `pi_xxx` (PaymentIntent). `provider_refund_id` = `re_xxx` (Refund). Webhook Stripe URL cố định `/v1/payments/webhook/stripe`. Payment Service gọi trực tiếp Stripe SDK, không còn Provider Adapter Pattern.
>
> **Phase 20 (Event Payload Simplification cho Order consumer):** Field không cần cho Order routing/workflow đã được loại bỏ khỏi payload tối thiểu. Payment Service vẫn giữ field đầy đủ ở internal log/audit, nhưng Kafka payload chỉ mang field Order cần để commit FSM. Cụ thể:
> - Bỏ `user_id` (Order có sẵn), `currency` (luôn VND), `provider` (luôn STRIPE), `payment_method_type` (Order không phân loại)
> - Bỏ `provider_transaction_id`/`provider_charge_id` (Payment Service giữ; Order không cần cross-ref Stripe)
> - Bỏ `payment_event_sequence_id`/`refund_event_sequence_id` (Order không guard theo; chỉ telemetry)
> - Bỏ `error_message`/`reason_message` (Notification tự map từ `error_code` qua i18n key)
> - Bỏ `payment_transaction_id` ở `REFUND_*` (Order không cần cross-ref payment txn khi đã có `order_id` route workflow)
>
> **Phase 22:** Order Service idempotency giờ dựa vào **Redis SETNX** (TTL 30 ngày) + **FSM row-level lock** thay cho `order_event_idempotency` (bảng đã bỏ). Notification downstream consumer tự dedup theo `(aggregate_id, last_event_sequence_id)` cho mọi Order event (xem [Order Events §2.1](../order/order_events.md#21-guard-keys-cho-fsm)).

| Event | Aggregate | Aggregate ID | Trigger | Guard key | Schema owner |
|---|---|---|---|---|---|
| `PAYMENT_INITIATED` | `Payment` | `payment_transaction_id` | Tạo payment_transaction mới | - | `docs/payment/payment_events.md#31-payment_initiated` |
| `PAYMENT_SUCCESS` | `Payment` | `payment_transaction_id` | T2 webhook xác nhận thành công | `payment_event_sequence_id` | `docs/payment/payment_events.md#32-payment_success` |
| `PAYMENT_FAILED` | `Payment` | `payment_transaction_id` | T3 webhook xác nhận thất bại | `payment_event_sequence_id` | `docs/payment/payment_events.md#33-payment_failed` |
| `PAYMENT_EXPIRED` | `Payment` | `payment_transaction_id` | CronJob quét `expires_at < NOW()` | `payment_event_sequence_id` | `docs/payment/payment_events.md#34-payment_expired` |
| `REFUND_INITIATED` | `Payment` | `refund_transaction_id` | Tạo refund_transaction | - | `docs/payment/payment_events.md#35-refund_initiated` |
| `REFUND_PROCESSING` | `Payment` | `refund_transaction_id` | PENDING → PROCESSING (gọi Stripe `Refund.create` xong) | `refund_event_sequence_id` | `docs/payment/payment_events.md#36-refund_processing` |
| `REFUND_SUCCESS` | `Payment` | `refund_transaction_id` | R2 PROCESSING → SUCCESS | `refund_event_sequence_id` | `docs/payment/payment_events.md#37-refund_success` |
| `REFUND_FAILED` | `Payment` | `refund_transaction_id` | R3 PROCESSING → FAILED | `refund_event_sequence_id` | `docs/payment/payment_events.md#38-refund_failed` |
| `REFUND_REVERSED` (optional) | `Payment` | `refund_transaction_id` | Provider đảo chiều refund | `refund_event_sequence_id` | `docs/payment/payment_events.md#39-refund_reversed` |

> **Routing contract cho Order consumer**: Dù `aggregate_id` của Payment events là transaction-level, Order Service route workflow theo `payload.order_id` (KHÔNG route theo `aggregate_id`).
> - `PAYMENT_SUCCESS|FAILED|EXPIRED`: lookup `checkout_token` theo `order_id` rồi signal `order-checkout-{checkout_token}`. Nếu checkout workflow closed do timeout, route sang `order-late-payment-recovery-{order_id}`.
> - `REFUND_PROCESSING|SUCCESS|FAILED|REVERSED`: signal `order-refund-{order_id}` theo `payload.order_id`.

### 2.3. Events do **Inventory Service** Produce (Topic: `t.inventory.events`)

> **Phase 20 (Event Payload Simplification cho Order consumer):**
> - `INVENTORY_RETURN_RECEIVED_ALL`: bỏ `order_item_ids[]` (Order Service derive từ `refund_request_id` + `order_id` trong `OrderRefundWorkflow` đã lock scope) và bỏ `confirmed_at` (trùng envelope `timestamp`). Order chỉ cần `order_id` + `refund_request_id` để route signal.
> - `INVENTORY_SHIP_TO_CUSTOMER`: bỏ `courier_note` (audit only, không ảnh hưởng FSM). Order vẫn cần `warehouse_id` để query `inventory_reservations` chính xác cho multi-warehouse order.

| Event | Aggregate | Aggregate ID | Partition Key | Trigger | Guard key | Schema owner |
|---|---|---|---|---|---|---|
| `INVENTORY_STOCK_MUTATION` | `Inventory` | `sku_id` | `sku_id` | Lua Script 1/2/3 return `{1,''}` | `order_item_id` (bắt buộc cho order flow) | `docs/inventory/inventory_events.md#41-inventory_stock_mutation` |
| `INVENTORY_STOCK_CHANGED` | `Inventory` | `sku_id` | `sku_id` | Ledger Worker / Restock / Return Restock | - | `docs/inventory/inventory_events.md#42-inventory_stock_changed` |
| `INVENTORY_RETURN_RECEIVED_ALL` | `Inventory` | `order_id` | `order_id` | Restock API (`reference_type=ORDER_RETURNED_RESTOCK`) khi refund completion check = 0 | `refund_request_id` | `docs/inventory/inventory_events.md#43-inventory_return_received_all` |
| `INVENTORY_SHIP_TO_CUSTOMER` | `Inventory` | `order_id` | `order_id` | Ship-to-Customer API khi admin xác nhận kho đã xuất hàng | `order_id` | `docs/inventory/inventory_events.md#44-inventory_ship_to_customer` |

> **Cơ chế produce**:
> - `INVENTORY_STOCK_MUTATION`: Redis Stream Outbox + Relay Worker
> - `INVENTORY_STOCK_CHANGED`, `INVENTORY_RETURN_RECEIVED_ALL`, `INVENTORY_SHIP_TO_CUSTOMER`: PostgreSQL Outbox + Debezium CDC

> **Consumer chính**:
> - `INVENTORY_STOCK_CHANGED`: Product Service (cập nhật display cache)
> - `INVENTORY_RETURN_RECEIVED_ALL`: Order Service (signal `OrderRefundWorkflow` theo `order_id`)
> - `INVENTORY_SHIP_TO_CUSTOMER`: Order Service (chuyển `order_items.status` từ `PROCESSING` → `DELIVERED` theo `order_id`)

### 2.4. Events do **Promotion Service** Produce

| Topic | Event | Partition Key | Trigger | Guard key | Schema owner |
|---|---|---|---|---|---|
| `t.promotion.markdown.events` | `MARKDOWN_CREATED`, `MARKDOWN_UPDATED`, `MARKDOWN_ENDED` | `rule_id` | Admin CRUD / Expiry scheduler | - | `docs/promotion/promotion_events.md#21-30` |
| `t.promotion.coupon.events` | `COUPON_CLAIMED` | `claim_id` | Lua SPOP claim thành công (direct-produce, không qua outbox) | `event_id` (delivery) + `claim_id` (business) | `docs/promotion/promotion_events.md#24-coupon_claimed` |
| `t.promotion.coupon.events` | `COUPON_REDEEMED` | `order_id` | Sync confirm qua `/v1/promotions/confirm` | `order_id` | `docs/promotion/promotion_events.md#25-coupon_redeemed` |
| `t.promotion.coupon.events` | `COUPON_CANCELLED` | `order_id` | Sync cancel qua `/v1/promotions/cancel` | `order_id` | `docs/promotion/promotion_events.md#26-coupon_cancelled` |
| `t.promotion.coupon.events` | `COUPON_REFUNDED` | `order_id` | Async từ `ORDER_REFUNDED` | `order_id`, `refund_transaction_id` | `docs/promotion/promotion_events.md#27-coupon_refunded` |
| `t.promotion.budget.events` | `BUDGET_EXHAUSTED` | `promotion_id` | Monitor phát hiện budget cạn | - | `docs/promotion/promotion_events.md#28-budget_exhausted` |

> **Reserve state không có event liên service:** Trạng thái `RESERVED` (runtime trên Redis do Lua Script Budget Reserve sinh ra khi `ReservePromotionActivity` chạy) chỉ tồn tại tạm thời và chưa commit ledger DB, nên không phát event Kafka liên service. Consumer cần theo dõi trạng thái budget thì dùng `COUPON_REDEEMED` (commit) hoặc `COUPON_CANCELLED` (release) làm mốc chuẩn.

> **Quan trọng**: Với luồng **sync** (`/confirm`, `/cancel`, `/cancel-confirmed`, `/refund`), Promotion KHÔNG tự sinh `event_id` — dùng đúng `idempotency_key` từ Order. Với luồng **async** (Kafka `ORDER_REFUNDED`), dùng đúng `event_id` envelope.
> Topic: `t.promotion.coupon.events` được publish trực tiếp sau DB commit (best-effort). `COUPON_CLAIMED` là direct-produce từ API (rescue bằng Pending Claims Sweeper).

### 2.5. Events do **Checkout Service** Produce

> **Checkout Service KHÔNG produce Kafka events**. Checkout chỉ dùng Redis (session) và gọi đồng bộ sang các service khác. Phản hồi cuối cùng trả về Client qua REST.

### 2.6. Cross-Service Event Consumption Map

| Service | Consume từ topic | Consumer Group | Action |
|---|---|---|---|
| Order | `t.payment.events` | `order-payment-consumer` | Signal `OrderCheckoutWorkflow` / `OrderRefundWorkflow`; fallback `OrderLatePaymentRecoveryWorkflow` |
| Order | `t.inventory.events` (RETURN_RECEIVED_ALL) | `order-inventory-return-consumer` | Signal `OrderRefundWorkflow` |
| Order | `t.inventory.events` (SHIP_TO_CUSTOMER) | `order-inventory-ship-consumer` | Update Order Item FSM PROCESSING → DELIVERED |
| Promotion | `t.order.events` (`ORDER_REFUNDED`) | `promo-order-consumer` | Trigger Refund Flow (Workflow 5) |
| Promotion | `t.product.events` (PRODUCT_STATE_CHANGED) | `promo-product-state-consumer` | Apply markdown cho product ACTIVE |
| Promotion | `t.auth.events` (USER_REGISTERED) | `promo-auth-consumer` (optional) | Welcome voucher |
| Promotion | `t.promotion.coupon.events` (COUPON_CLAIMED) | `promo-claim-consumer` | Persist claim + upsert wallet |
| Payment | `t.order.events` (ORDER_CANCELLED) | `payment-order-consumer` | Đóng payment pending (pre-payment lane only) |
| Cart | `t.order.events` (ORDER_PAID) | (Cart consumer) | Batch delete SKU lines |

---

## 3. Sync API Catalog (SSOT)

### 3.1. Order ↔ Checkout

| Endpoint | Direction | Owner | Auth | Schema owner |
|---|---|---|---|---|
| `POST /v1/checkout/preview` | Client → Checkout | Checkout | JWT (User) | `docs/checkout/checkout_workflow.md#1` |
| `POST /v1/checkout/submit` | Client → Checkout | Checkout | JWT (User) | `docs/checkout/checkout_workflow.md#2` |
| `POST /v1/orders/create-from-checkout` | Checkout → Order | Order | Service token (OIDC m2m) | `docs/order/order_workflow.md#4` |
| `GET /v1/orders/{orderId}` | Client → Order | Order | JWT (Buyer/Admin) | `docs/order/order_workflow.md#1` |
| `GET /v1/orders/search` | Client → Order | Order | JWT (Buyer/Admin) | `docs/order/order_workflow.md#2` |
| `POST /v1/orders/{orderId}/cancel` | Client → Order | Order | JWT (Buyer/Admin) | `docs/order/order_workflow.md#3` |
| `POST /v1/orders/{orderId}/return` | Client → Order | Order | JWT (Buyer) | `docs/order/order_workflow.md#31` |

### 3.2. Order ↔ Payment (Temporal Saga + Sync API)

| Endpoint | Direction | Owner | Auth | Schema owner |
|---|---|---|---|---|
| `POST /v1/payments/initiate` | Order → Payment | Payment | Service token (Temporal Activity) | `docs/payment/payment_workflow.md#1` |
| `GET /v1/payments/{transactionId}` | Order/User → Payment | Payment | JWT/Service | `docs/payment/payment_workflow.md#3` |
| `POST /v1/payments/{transactionId}/refund` | Order → Payment | Payment | Service token + `Idempotency-Key` header | `docs/payment/payment_workflow.md#4` |
| `GET /v1/refunds/{refundTransactionId}` | Order/CSKH → Payment | Payment | JWT | `docs/payment/payment_workflow.md#42` |
| `POST /v1/payments/webhook/stripe` | Stripe → Payment | Payment | HMAC-SHA256 (Stripe-Signature header) + IP whitelist | `docs/payment/payment_workflow.md#2` |

### 3.3. Order ↔ Inventory (Temporal Activities + Sync API)

| Activity/Endpoint | Direction | Owner | Auth | Schema owner |
|---|---|---|---|---|
| `POST /v1/inventory/routing-options` | Checkout → Inventory | Inventory | JWT (User) | `docs/inventory/inventory_endpoints.md#1` |
| `ReserveInventoryActivity` (Temporal) | Order → Inventory | Inventory | Service token | `docs/inventory/inventory_workflow.md#1` |
| `DeductInventoryActivity` (Temporal) | Order → Inventory | Inventory | Service token | `docs/inventory/inventory_workflow.md#2` |
| `RollbackInventoryActivity` (Temporal) | Order → Inventory | Inventory | Service token | `docs/inventory/inventory_workflow.md#3` |
| `POST /v1/inventory/returns/refund-expectations` | Order → Inventory | Inventory | Service token (OIDC m2m) | `docs/inventory/inventory_endpoints.md#3` |
| `POST /v1/inventory/restock` | Admin → Inventory | Inventory | JWT (Admin) | `docs/inventory/inventory_endpoints.md#2` |
| `POST /v1/inventory/admin/unlock-sku` | Admin → Inventory | Inventory | JWT (Admin) | `docs/inventory/inventory_endpoints.md#4` |

### 3.4. Order ↔ Inventory (continued)

| Activity/Endpoint | Direction | Owner | Auth | Schema owner |
|---|---|---|---|---|
| `POST /v1/inventory/routing-options` | Checkout → Inventory | Inventory | JWT (User) | `docs/inventory/inventory_endpoints.md#1` |
| `ReserveInventoryActivity` (Temporal) | Order → Inventory | Inventory | Service token | `docs/inventory/inventory_workflow.md#1` |
| `DeductInventoryActivity` (Temporal) | Order → Inventory | Inventory | Service token | `docs/inventory/inventory_workflow.md#2` |
| `RollbackInventoryActivity` (Temporal) | Order → Inventory | Inventory | Service token | `docs/inventory/inventory_workflow.md#3` |
| `POST /v1/inventory/ship-to-customer` | Admin → Inventory | Inventory | JWT (Admin) | `docs/inventory/inventory_endpoints.md#5` |
| `POST /v1/inventory/returns/refund-expectations` | Order → Inventory | Inventory | Service token (OIDC m2m) | `docs/inventory/inventory_endpoints.md#3` |
| `POST /v1/inventory/restock` | Admin → Inventory | Inventory | JWT (Admin) | `docs/inventory/inventory_endpoints.md#2` |
| `POST /v1/inventory/admin/unlock-sku` | Admin → Inventory | Inventory | JWT (Admin) | `docs/inventory/inventory_endpoints.md#4` |

### 3.4. Checkout ↔ Promotion (Sync API)

| Endpoint | Direction | Owner | Auth | Schema owner |
|---|---|---|---|---|
| `GET /v1/promotions/voucher-suggestions` | Checkout → Promotion | Promotion | JWT (User) | `docs/promotion/voucher_discovery_api.md#2` |
| `GET /v1/promotions/platform-voucher-discovery` | Checkout → Promotion | Promotion | JWT (User) | `docs/promotion/voucher_discovery_api.md#9` |
| `POST /v1/promotions/save-and-apply` | Checkout → Promotion | Promotion | JWT (User) | `docs/promotion/voucher_discovery_api.md#96` |
| `POST /v1/promotions/calculate-discount` | Checkout → Promotion | Promotion | Service token | `docs/promotion/promotion_workflow.md#3` |

### 3.5. Order ↔ Promotion (Sync Temporal API)

| Endpoint | Direction | Owner | Action | Idempotency Key |
|---|---|---|---|---|
| `POST /v1/promotions/reserve` | Order (Temporal `ReservePromotionActivity`) → Promotion | Promotion | Reserve budget all-or-nothing (Lua Script 2B) | n/a (Promotion tự sinh reservation_key) |
| `POST /v1/promotions/confirm` | Order (Temporal `ConfirmPromotionActivity`) → Promotion | Promotion | Commit REDEEMED | `REDEEM_FINAL:{order_id}:{promotion_id}` |
| `POST /v1/promotions/cancel` | Order (Temporal `RollbackPromotionActivity`) → Promotion | Promotion | Release pre-payment reservation | `CANCEL_FINAL:{order_id}:{promotion_id}` |
| `POST /v1/promotions/cancel-confirmed` | Order (Temporal `CancelConfirmedPromotionActivity`) → Promotion | Promotion | Hoàn REDEEMED post-payment (lane compensation) | `CANCEL_FINAL:{order_id}:{promotion_id}` |
| `POST /v1/promotions/refund` | Order (Temporal `InitiateRefundActivity`, optional sync) → Promotion | Promotion | Hoàn budget theo allocation | `REFUND_FULL\|REFUND_PARTIAL:{order_id}:{promotion_id}:{refund_request_id}` |

---

## 4. State Machine Map (SSOT)

### 4.1. Order Master FSM

> Canonical tại `docs/order/order_state_machine.md`

```
CREATED → PENDING_PAYMENT → PAID → PROCESSING → COMPLETED
                ↓                ↓          ↓
            CANCELLED     RETURN_REQUESTED → REFUNDED
```

| Transition | From | To | Trigger |
|---|---|---|---|
| T1 | `CREATED` | `PENDING_PAYMENT` | `CREATE_ORDER_ACTIVITY_COMMITTED` |
| T2 | `PENDING_PAYMENT` | `PAID` | `PAYMENT_SUCCESS` (commit trong Temporal) |
| T3 | `PENDING_PAYMENT` | `CANCELLED` | `PAYMENT_TIMEOUT` |
| T3.1 | `CANCELLED` | `PAID` | `PAYMENT_SUCCESS_LATE_RECOVERY` (chỉ khi `cancel_reason = PAYMENT_TIMEOUT`) |
| T4 | `PENDING_PAYMENT` | `CANCELLED` | `USER_CANCEL` |
| T4.1 | `PENDING_PAYMENT` | `CANCELLED` | `ADMIN_FORCE_CANCEL` |
| T7 | `PAID` | `RETURN_REQUESTED` | `ADMIN_FORCE_REFUND_REQUESTED` |
| T8 | `PAID` | `PROCESSING` | `POST_PAYMENT_SAGA_COMPLETED` |
| T8.1 | `PROCESSING` | `PAID` | `POST_PAYMENT_COMPENSATION_ROLLBACK` |
| T8.2 | `PROCESSING` | `RETURN_REQUESTED` | `ADMIN_FORCE_REFUND_REQUESTED` |
| T8.3 | `PROCESSING` | `COMPLETED` | `AUTO_COMPLETE_ALL_ITEMS_DELIVERED` |
| T9 | `COMPLETED` | `RETURN_REQUESTED` | `RETURN_API_ACCEPTED` |
| T10 | `RETURN_REQUESTED` | `REFUNDED` | `REFUND_SUCCESS` |
| T10.1 | `RETURN_REQUESTED` | `RETURN_REQUESTED` | `REFUND_PROCESSING/FAILED/REVERSED` |

> `cancel_reason` enum: `PAYMENT_TIMEOUT`, `USER_CANCEL`, `ADMIN_FORCE_CANCEL`, `PRE_PAYMENT_SAGA_FAILED`, `PAYMENT_FAILED` (bổ sung `PAYMENT_FAILED` theo Phase 12).

### 4.2. Order Item Delivery FSM

```
PROCESSING → DELIVERED → RETURNED
```

| Transition | From | To | Trigger |
|---|---|---|---|
| I1 | `PROCESSING` | `DELIVERED` | Inventory `INVENTORY_SHIP_TO_CUSTOMER` (admin xác nhận kho đã xuất hàng) |
| I2 | `DELIVERED` | `RETURNED` | `RETURN_API_ACCEPTED` từ Order (khách yêu cầu trả hàng hoặc admin force) |

> Order Item FSM đã được đơn giản hóa từ 7 trạng thái xuống còn 3 trạng thái. Trigger duy nhất cho PROCESSING → DELIVERED là event `INVENTORY_SHIP_TO_CUSTOMER` do admin gọi API sau khi xuất hàng từ kho.

### 4.3. Payment FSM

```
PENDING → PROCESSING → SUCCESS → REFUNDING → REFUNDED
              ↓            ↓           ↓
          FAILED      FAILED      FAILED (compensation T8: REFUNDING→SUCCESS + flag manual review)
              ↓
          EXPIRED
          CANCELED
```

| ID | From | Event/Trigger | Guard | To |
|---|---|---|---|---|
| T1 | `PENDING` | `PAYMENT_WEBHOOK_RECEIVED` | Signature + amount/ref match | `PROCESSING` |
| T2 | `PROCESSING` | `PAYMENT_SUCCESS_CONFIRMED` | Provider xác nhận | `SUCCESS` |
| T3 | `PROCESSING` | `PAYMENT_FAILED_CONFIRMED` | Provider xác nhận | `FAILED` |
| T4 | `PENDING/PROCESSING` | `PAYMENT_SESSION_EXPIRED` | `NOW() >= expires_at` | `EXPIRED` |
| T5 | `PENDING/PROCESSING` | `PAYMENT_CANCELED` | Hủy hợp lệ | `CANCELED` |
| T6 | `SUCCESS` | `REFUND_REQUEST_ACCEPTED` | Tổng refund hợp lệ | `REFUNDING` |
| T7 | `REFUNDING` | `REFUND_SUCCESS_CONFIRMED` | Provider/poller xác nhận | `REFUNDED` |
| T8 | `REFUNDING` | `REFUND_FAILED_CONFIRMED` | Provider xác nhận refund fail | `FAILED` (set `requires_manual_review = true`) |
| T9 | `REFUNDED` | `REFUND_REVERSED_CONFIRMED` | Provider đảo chiều | `SUCCESS` (set `requires_manual_review = true`) |

> **Lưu ý T8**: T8 sai ở bản cũ (`REFUNDING → SUCCESS` với label "REFUND_FAILED_CONFIRMED"). Đã sửa thành `REFUNDING → FAILED` (Phase 3).

### 4.4. Refund Transaction FSM

| ID | From | Event/Trigger | To |
|---|---|---|---|
| R1 | `PENDING` | `REFUND_REQUEST_SENT` | `PROCESSING` |
| R2 | `PROCESSING` | `REFUND_SUCCESS_CONFIRMED` | `SUCCESS` |
| R3 | `PROCESSING` | `REFUND_FAILED_CONFIRMED` | `FAILED` |
| R4 | `PENDING` | `REFUND_REQUEST_REJECTED` | `FAILED` |

---

## 5. Compensation Stack (Temporal Saga)

### 5.1. Pre-Payment Compensation (LIFO)

| Order | Activity | Service |
|---|---|---|
| 1 (registered first) | `CreateOrderActivity` → `CancelOrderActivity` (khi fail) | Order |
| 2 | `ReserveInventoryActivity` → `RollbackInventoryActivity` | Inventory |
| 3 | `ReservePromotionActivity` → `RollbackPromotionActivity` (pre-payment only, không đổi ví DB) | Promotion |
| 4 (last) | `InitiatePaymentActivity` → `CancelPaymentActivity` | Payment |

Khi rollback, Temporal chạy LIFO: `CancelPayment` → `RollbackPromotion` → `RollbackInventory` → `CancelOrder`.

### 5.2. Post-Payment Compensation (LIFO)

| Order | Activity | Service |
|---|---|---|
| 1 (first) | `DeductInventoryActivity` → `RollbackDeductedInventoryActivity` | Inventory |
| 2 | `ConfirmPromotionActivity` → `CancelConfirmedPromotionActivity` (post-payment, có thể đảo REDEEMED, idempotency theo `CANCEL_FINAL:{order_id}:{promotion_id}`) | Promotion |
| 3 (last) | `UpdateOrderStatusActivity(PAID→PROCESSING)` → `RevertOrderStatusToPaidActivity` | Order |

Khi rollback, Temporal chạy LIFO: `RevertOrderStatusToPaid` → `CancelConfirmedPromotion` → `RollbackDeductedInventory`.

---

## 6. TTL & Window Convention (SSOT)

| Item | Value | Source / Owner |
|---|---|---|
| Checkout session TTL | 20 phút | Checkout Service (Redis `checkout:session:{user_id}`) |
| Payment session TTL | 15 phút + 60s đệm (network buffer) | Payment Service (DB `payment_transactions.expires_at`) |
| Order Saga payment timer | 15 phút | Order Service (Temporal Timer) |
| Route Plan TTL | 20 phút (mặc định, configurable) | Inventory Service (`route_plan:{id}` Redis) |
| Promotion Reservation TTL | 15 phút business + cleanup grace window | Promotion Service (`promo:{id}:reservation:{order_id}` + ZSET index) |
| Auto-Complete coalescing window | 5 giây | Order Service (Worker) |
| Order sync API idempotency retention | 7 ngày (Redis SETNX TTL, Phase 22 — bỏ DB table) | Order Service (Redis) |
| Order Kafka consumer idempotency retention | 30 ngày (Redis SETNX TTL, Phase 22 — bỏ DB table) | Order Service (Redis) |
| Order one-shot guard retention | Vòng đời order (`orders.buyer_return_locked_at`) | Order Service (column inline) |
| Promotion idempotency retention | 45 ngày (≥ `refund_window + 15 days`) | Promotion Service |
| Outbox CDC trim retention | 7 ngày (Promotion), 15 ngày (Order/Payment) | Service-specific (xem [OUTBOX_PATTERNS.md](OUTBOX_PATTERNS.md)) |
| Markdown expiry scheduler | 250ms-1s (Redis ZSET), 10-30s (Cron fallback) | Promotion Service |
| Reconciliation interval | 15 phút (DB → Redis budget) | Promotion Service |

---

## 7. Cross-Service Validation (Bắt buộc)

Mọi event cross-service phải:

1. **Idempotency**: Consumer dùng `event_id` + `aggregate_id` để dedup, kết hợp với idempotency layer tương ứng: Promotion dùng `promotion_event_idempotency` UNIQUE constraint; **Order (Phase 22)** dùng Redis SETNX + FSM row-level lock (xem [Idempotency Guidelines §1.1](IDEMPOTENCY_GUIDELINES.md#11-tại-sao-phải-có-idempotency-2-lớp)).
2. **Out-of-order**: Projector/Consumer ưu tiên `*_sequence_id` / `*_version` hơn `timestamp` để quyết định stale.
3. **Backward compatibility**: Chỉ thêm field mới, không xóa/rename. Consumer ignore unknown fields. (Phase 20 note: đây là rule cho từng field — Phase 20 thực hiện xóa field đã được 2 phía (producer + consumer) thống nhất đồng thời nâng cấp; vẫn tương thích additive-only ở góc nhìn consumer cũ.)
4. **Schema version**: Mọi payload có `schema_version` ở envelope top-level. Hiện tại = `1`.
5. **Header chuẩn**: Tuân thủ Section 0.2 envelope (CloudEvents wrapper).
6. **Canonical ID**: `user_id` luôn là UUID. Internal IDs (`order_id`, `sku_id`, ...) theo convention Section 0.1.
7. **Minimum viable payload (Phase 20)**: Producer chỉ gửi field consumer cần dùng để (a) xác định aggregate, (b) quyết định hành động, (c) audit khi bắt buộc. Field có thể derive từ DB/API khác (snapshot details, full items array, addresses, audit timestamps) hoặc chỉ dùng bởi 1 consumer duy nhất thì KHÔNG đưa vào payload. Consumer cần thêm info thì gọi API riêng (VD `GET /v1/orders/{id}`).

---

## 8. Cập nhật matrix

- Matrix này được khởi tạo tại Phase 0B. Mọi sửa đổi từ Phase 1-12 phải cập nhật lại file này. Xem [CHANGELOG.md](CHANGELOG.md) để biết lịch sử thay đổi.
- Khi một service doc khác (workflow, events, db_design) mô tả contract khác với matrix này → matrix này là chuẩn, file kia phải sửa để khớp.
