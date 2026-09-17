---
doc_id: order-interfaces
service: order
scope: target
status: proposed
owners:
  - Order Service team
evidence:
  - contracts/http/order/v1/openapi.yaml
  - contracts/http/order/internal/v1/openapi.yaml
  - contracts/events/order/v1/asyncapi.yaml
  - contracts/events/order/v1/schemas/order-completed.schema.json
  - contracts/http/promotion/v1/openapi.yaml
  - contracts/http/payment/v1/openapi.yaml
  - contracts/events/payment/v2/asyncapi.yaml
  - contracts/events/delivery/v1/asyncapi.yaml
verified_against_commit: null
---

# Order Service — Giao diện tích hợp

> Hợp đồng Payment, Promotion, Inventory workflow, Delivery proof và financial
> reconciliation được liên kết bên dưới là nguồn wire-level. Ví dụ Markdown chỉ mô tả
> behavior và không được ghi đè schema máy đọc.

## 1. Danh mục tương tác

| Tương tác | Hướng | Giao thức | Provider | Consumer | Trạng thái |
|---|---|---|---|---|---|
| Order detail/search/cancel/return/payment retry | Inbound | HTTP | Order | Buyer/Admin qua Gateway | [Order public OpenAPI v1](../../contracts/http/order/v1/openapi.yaml) |
| Create from checkout | Inbound | HTTP | Order | Checkout | [Order internal OpenAPI v1](../../contracts/http/order/internal/v1/openapi.yaml) |
| Inventory activities | Outbound | HTTP | Inventory | Order Temporal worker | [workflow OpenAPI v1](../../contracts/http/inventory/workflow/v1/openapi.yaml) |
| Promotion activities | Outbound | HTTP | Promotion | Order Temporal worker | contract target exists |
| Payment activities | Outbound | HTTP | Payment | Order Temporal worker | [Payment OpenAPI v1](../../contracts/http/payment/v1/openapi.yaml) |
| `t.order.events` | Outbound | Kafka | Order | Cart, Notification, Review, Analytics và consumer khác; Promotion không consume refund | partial contract |
| `t.payment.events.v2` | Inbound | Kafka | Payment | Order | [Payment AsyncAPI v2](../../contracts/events/payment/v2/asyncapi.yaml) |
| `t.inventory.order.events.v1` | Inbound | Kafka | Inventory | Order | [Inventory events v1](../../contracts/events/inventory/v1/asyncapi.yaml) |
| `t.delivery.order.events.v1` | Inbound | Kafka | Delivery | Order | [Delivery AsyncAPI v1](../../contracts/events/delivery/v1/asyncapi.yaml); proof ID + immutable record digest, no raw PII |
| Financial reconciliation decision | Inbound | Internal HTTP | Order | Finance/Ops | [Order internal OpenAPI v1](../../contracts/http/order/internal/v1/openapi.yaml) |
| Temporal workflows/signals | Internal | Temporal | Order workers | Order adapters/consumers | proposed |

## 2. HTTP API đầu vào

Wire-level source of truth là [Order public OpenAPI v1](../../contracts/http/order/v1/openapi.yaml)
và [Order internal OpenAPI v1](../../contracts/http/order/internal/v1/openapi.yaml).
Gateway xác thực JWT và chuyển identity/role đáng tin cậy. Buyer chỉ nhìn/tác động
order của mình; Admin dùng namespace riêng. Mọi JSON `order_id`, `order_item_id` và
`order_line_id` là canonical decimal string không padding để không mất chính xác int64.

### 2.1 Buyer và Admin

| API | Mục đích và semantics | Kết quả chính |
|---|---|---|
| `GET /api/v1/orders` | Search order của Buyer hiện tại; filter `tab,q,created_from,created_to,order_status,payment_status,min_amount_minor,max_amount_minor,cursor,limit` | `200`; default 90 ngày; keyset cursor opaque |
| `GET /api/v1/orders/{order_id}` | Detail master/items/lines/promotions/payment/refund; owner-only | `200`; non-owner cũng `404`; `Cache-Control: private, no-store` |
| `POST /api/v1/orders/{order_id}/cancellation-requests` | Buyer hủy pre-payment | `202` + operation; bắt buộc `Idempotency-Key` |
| `POST /api/v1/orders/{order_id}/return-requests` | Buyer one-shot, selected full-quantity lines sau khi mọi line delivered | `202` + operation/refund request; scope sai `422`, guard/state conflict `409` |
| `GET /api/v1/orders/{order_id}/refund-case` | Theo dõi lifetime refund case | `200` hoặc `404` |
| `POST /api/v1/orders/{order_id}/payment-attempts` | Tạo numbered attempt kế tiếp sau terminal non-capture fact | `201`; replay `200`; timeout retry cùng key |
| `GET /api/v1/orders/{order_id}/payment-attempts/{attempt_number}` | Đọc attempt; client secret chỉ hiện cho active `PENDING` attempt | `200`, private/no-store |
| `GET /api/v1/order-operations/{operation_id}` | Theo dõi command async của Buyer | `ACCEPTED/RUNNING/SUCCEEDED/REJECTED/FAILED/MANUAL_REVIEW_REQUIRED` |
| `GET /api/v1/admin/orders` | Admin search toàn cục, thêm filter `buyer_id` | `200`; cùng sort/query model |
| `GET /api/v1/admin/orders/{order_id}` | Admin detail | `200` |
| `POST /api/v1/admin/orders/{order_id}/cancellation-requests` | Admin cancel pre-payment; không dùng cho order đã capture | `202` + operation |
| `POST /api/v1/admin/orders/{order_id}/pre-dispatch-refund-requests` | Tạo durable attempt để Inventory atomic-release toàn order trước dispatch | `202`; rejection không tạo lifetime case/khóa Buyer guard |
| `GET /api/v1/admin/order-operations/{operation_id}` | Admin theo dõi attempt/command | `200` |

Cancellation và refund là hai endpoint riêng; endpoint cancel không âm thầm đổi
thành refund theo trạng thái. Mọi POST public bắt buộc `Idempotency-Key`: cùng key +
canonical payload replay kết quả, payload khác trả `409 IDEMPOTENCY_KEY_REUSED`.
Command dài hạn trả `202`; operation resource chỉ biểu diễn tiến độ xử lý, còn FSM
Order/refund case vẫn là nguồn chân lý nghiệp vụ.

Search trả một card trên mỗi order với `order_id`, `buyer_id`, `buyer_tab`,
`ui_primary_status`, `ui_status_rank`, `created_at`, `primary_item_name`,
`primary_item_image_url`, `primary_store_name="PLATFORM"`, `item_count`,
`remaining_item_count` và `final_amount`. Không trả `matched_lines_count`,
`matched_context[]`, `match_reason` hay `matched_lines`.

### 2.2 Checkout service-to-service

`POST /internal/v1/checkout-order-submissions` chỉ cho principal
`checkout-service`; `Idempotency-Key` bắt buộc bằng UUID `checkout_token`. Request
chứa `user_id`, address snapshot, `items[]`, pricing, promotion snapshot,
`allocation_plan`, `pricing_hash`, `items_hash`, campaign revision và degraded ack.
`allocation_plan` dùng tên canonical `allocation_plan_id`; mỗi `line_reference`/SKU/
quantity/warehouse phải ánh xạ một-một với Checkout item. Order tạo `order_line_id`
sau khi accept và persist bridge trước Reserve. Money luôn là
`{amount_minor,currency:"VND"}`; không có `payment_method`.

Khác command dài hạn, endpoint này chờ đến checkpoint `PAYMENT_READY` để Checkout
có thể trả Stripe data cho frontend: lần đầu `201`, replay `200`, gồm `order_id`,
attempt number, transaction ID, `client_secret`, `publishable_key`, `expires_at`.
Promotion reserve fail trả RFC 9457 `409 PROMOTION_RECALCULATION_NEEDED`; timeout
hạ tầng trả `503 retryable=true` và Checkout retry cùng key/body. Recovery read dùng
`GET /internal/v1/checkout-order-submissions/{checkout_token}`.

`pricing_hash` là chuỗi `sha256:<64 lowercase hex>` của SHA-256 trên UTF-8 RFC 8785
canonical JSON với object `{items:[{sku_id,unit_price_minor}],
promotion_campaign_revision,total_discount_minor}`; items sort theo byte UTF-8 của
`sku_id` duy nhất. `items_hash` dùng object `{items:[{quantity,sku_id}]}` cùng quy
tắc sort/canonicalization. Order cross-check cả hash lẫn amount invariant trước tạo.

### 2.3 Finance/Ops

`GET /internal/v1/orders/{order_id}/financial-reconciliation-case` trả revision,
evidence references và permitted resolutions. Existing
`POST /internal/v1/orders/{order_id}/financial-reconciliation-decisions` ghi quyết
định evidence-backed; `Idempotency-Key` phải bằng `decision_id`.

## 3. Remote activities do Order gọi

| Activity | Service | Ý nghĩa | Retry/idempotency |
|---|---|---|---|
| Reserve/rollback inventory | Inventory | Giữ/nhả stock theo immutable allocation plan | Activity timeout/retry; stable request identity |
| Commit payment / recover timed-out payment | Inventory | Commit whole reserved root after payment; fenced recovery only for same timeout-released root | Stable command ID; query `GET /commands/{id}` after unknown outcome; never re-reserve/reroute |
| Register refund scope | Inventory | Đăng ký selected `order_line_id` với full ordered quantity | Key lifetime theo order + refund request |
| Release committed order before dispatch | Inventory | Admin automatic path: atomic release tất cả committed lines hoặc no-op conflict | Không có Refund Scope; Inventory là authority dispatch |
| `POST /internal/v1/promotion-allocation/reservations` | Promotion | TRY giữ budget runtime sau submit | Natural key order/promotion; cùng payload replay, khác payload conflict |
| `POST /internal/v1/promotion-allocation/cancellations` | Promotion | CANCEL nhả reservation trước payment confirm | Chỉ nhả `RESERVED`; trả `RELEASED`, `NO_RESERVATION` hoặc `REPAIR_REQUIRED` |
| `POST /internal/v1/promotion-allocation/confirmations` | Promotion | CONFIRM wallet `USED` + durable budget/receipt/outbox | Bắt buộc `Idempotency-Key: REDEEM_FINAL:{order_id}:{promotion_id}` |
| Initiate/cancel payment | Payment | Tạo Stripe PaymentIntent; cancel must resolve capture/non-capture before Order final-cancel | Persist numbered attempt + transaction mapping; stable Payment create/cancel key; `ALREADY_SUCCEEDED` enters paid repair |
| Initiate refund | Payment | Tạo Stripe Refund đúng một lần from the immutable captured transaction after gate | Stable refund key/transaction ID |

Promotion keys do Order sinh và giữ xuyên retry:

- `REDEEM_FINAL:{order_id}:{promotion_id}`

`REDEEM_FINAL` is held through Temporal retry. `CANCEL_FINAL` is a migration-only
capability and Order Service has no route, identity or code path to generate it. Promotion canonicalize request để phát
hiện cùng key khác payload; Order only considers confirmation complete after the durable
receipt/wallet/budget commit. Redis finalizer may converge later and does not alter the
stored business response.

Mọi ID trong payload Promotion là opaque string. Money dùng
`{amount_minor,currency}` và hiện chỉ chấp nhận `currency=VND`; Order adapter không truyền
BIGINT/`*_vnd` storage shape qua wire.

Reserve batch là all-or-nothing. Nếu fail, lỗi phải giữ
`failed_promotion_id`, `failed_reason`, `reserve_mode=ALL_OR_NOTHING`; Checkout mới
evaluate lại và tạo token mới. Trước confirm promotion ở race payment/timer, activity
gia hạn reservation thêm 60 giây grace window.

## 4. Event envelope, delivery và ordering

Event Order tự phát đi qua append-only outbox và Debezium, delivery at-least-once,
topic `t.order.events`, partition key `order_id`. Envelope thiết kế cũ dùng các
field `event_id`, `event_type`, `aggregate_type`, `aggregate_id`, `timestamp`,
`source=order-service`, `correlation_id` (Temporal WorkflowID), `business_id`
(`order_id` khi dùng) và payload/version.

Event thông thường đặt business identifier tại `payload.order_id`; contract
`ORDER_COMPLETED` đặt tại `data.order_id` và `data.user_id`. Trường chuyển trạng
thái dùng tên `old_status`, `new_status`; `ORDER_PAID` mang `sku_ids` để Cart dọn
line. Không suy diễn `orders.status/payment_status` hoặc generic
`status/payment_status` thành một FSM duy nhất: đây là hai trục riêng.

Phần `ORDER_COMPLETED` v1 hiện dùng CloudEvents chuẩn với `id`, `type`, `source`,
`subject`, `time`, `datacontenttype`, `dataschema`, extension `correlationid`,
`sequence` và `data`. Đây là khác biệt envelope có chủ đích cần xử lý ở lần thống
nhất contract sau.

| Nhóm event | Ordering/guard |
|---|---|
| Order lifecycle thường | `last_event_sequence_id` đơn điệu per `order_id` |
| `ORDER_COMPLETED` v1 | CloudEvents `sequence` per order |
| Order line fulfillment lifecycle | `order_line_id + last_event_sequence_id`; topic vẫn partition theo order |
| Event Payment/Inventory/Delivery tiêu thụ | Durable inbox `event_id` + source revision/financial version + semantic identity validation; Redis SETNX only fast-path |

## 5. Event phát ra

| Event | Khi phát và ý nghĩa | Dữ liệu nghiệp vụ cốt lõi | Consumer/hành vi |
|---|---|---|---|
| `ORDER_CREATED` | Create transaction chuyển transient `CREATED -> PENDING_PAYMENT` | order/user/status/sequence/final amount VND object | Notification; không nhúng item/address/promotion snapshot |
| `ORDER_PAID` | Mốc tài chính commit `PENDING_PAYMENT -> PAID`, hoặc recovery payment muộn | order/user/status/sequence, recovery flag, captured payment txn, SKU IDs, final amount VND object, paid time | Cart cleanup, Notification, Analytics |
| `ORDER_CANCELLED` | Matching Payment non-capture plus pre-payment cleanup complete and master bị cancel | order/user/status, previous state, reason, **payment_transaction_id**, cancellation command ID, sequence, cancel time | Payment reconciliation, Notification |
| `ORDER_STATUS_CHANGED` | Master transition không có event chuyên biệt | order/user, old/new, reason, sequence, changed time | Notification |
| `ORDER_LINE_FULFILLMENT_CHANGED` | Per-line dispatch/delivery transition | order/item/line/warehouse, old/new, fact/proof ID, sequence, changed time | Notification; guard theo line |
| `ORDER_REFUNDED` | Payment refund success, master `RETURN_REQUESTED -> REFUNDED` | order/user/status/sequence, refund txn/mode/amount/source/request, selected line IDs or admin-pre-dispatch evidence, time | Notification/Analytics; Promotion không consume |
| `ORDER_COMPLETED` | Tất cả line delivered bằng verified Delivery proof và master CAS thành công | canonical string order ID, UUID user, immutable product/SKU/variant/quantity items, completed time | Review entitlement; Analytics/Settlement chưa đăng ký |
| `ORDER_REFUND_FAILED` | Payment refund failed/reversed; master giữ `RETURN_REQUESTED` | order/user/payment state/sequence, refund IDs/source, error, time | Notification/Ops critical lane |

Chi tiết quan trọng:

- `ORDER_CREATED` không nhúng address/items/promotions; snapshot đã nằm trong DB.
- `ORDER_PAID` là mốc tài chính, không có nghĩa Inventory commit/Promotion confirm đã xong.
- Cancel reason hợp lệ gồm `PAYMENT_TIMEOUT`, `USER_CANCEL`,
  `ADMIN_FORCE_CANCEL`, `PAYMENT_FAILED`; thiết kế FSM còn nhắc
  `PRE_PAYMENT_SAGA_FAILED` ở tập reason nội bộ.
- Status-change reason gồm `POST_PAYMENT_SAGA_COMPLETE`,
  `ADMIN_FORCE_REFUND_PRE_DISPATCH`, `RETURN_API_ACCEPTED`,
  `INVENTORY_SCOPE_INSPECTION_COMPLETED`, `LATE_PAYMENT_RECOVERED` và
  `PAYMENT_SUCCESS_LATE_RECOVERY` theo ngữ cảnh cũ.
- `ORDER_REFUNDED` không chứa `promotion_refund_allocations[]`. Voucher là lợi ích toàn
  đơn, không được phân bổ xuống SKU; customer refund không hoàn budget/voucher tại
  Promotion.
- Refund fields are `refund_trigger_source`, `refund_request_id`,
  `selected_order_line_ids`, `refund_mode`, `refund_transaction_id` and
  `refund_amount`; `refund_type` and generic `returned_item_ids` are retired.
- Buyer amount is gross selected-line snapshot price capped at `final_amount_minor`, with
  no discount re-proration. There is one lifetime refund case/transaction per order.
- `ORDER_COMPLETED.data.order_id` là opaque canonical decimal string không padding;
  DB `10001` phát `"10001"`, subject `orders/10001`. Không phát JSON number hoặc
  prefix. `variant_name` nullable cho order cũ; producer mới gửi khi có.
- `ORDER_COMPLETED.items[]` là ngoại lệ payload tối giản vì Review không gọi sync
  Order/Product trong write path.
- Refund failure/reverse đều map `payment_status=REFUND_FAILED`; error/reason gốc
  phải còn đủ cho Ops. Buyer one-shot guard không được reset.

### 5.1. Các field/event lịch sử đã chủ đích loại bỏ

Để bảo toàn dấu vết thiết kế mà không đưa chúng trở lại contract hiện tại:

- Payload tối giản đã bỏ `cart_id`, `checkout_submitted_at`, `changed_by`,
  `changed_by_type`, `customer_return_guard_applied`, `customer_return_policy`,
  `refund_scope_locked_at`, `single_refund_transaction`, `scope_admin_id`,
  `partial_restore_rule`, `voucher_restore_mode`, `platform_funding_refund` và
  `admin_funding_refund`.
- `changed_by` trước đây luôn có giá trị `system`; reverse payload từng có
  `reason_message`, nay consumer map từ code/i18n.
- Payment/provider detail không cần ở Order event gồm `provider_transaction_id`,
  `provider_charge_id`, `provider_refund_id`, `payment_method_type`; payment result
  hiện trả Stripe `client_secret + publishable_key`, không còn `payment_url` kiểu
  provider redirect cũ.
- Order-produced generic payload version remains separate from upstream facts. Consumed
  Payment financial version and Inventory aggregate/scope revision are retained as durable
  ordering guards alongside event ID; Redis/Temporal alone are insufficient.
- Outbox không duy trì mutable `status/retry_count`; retry state thuộc Kafka Connect.
- `payment_event_sequence_id` và `refund_event_sequence_id` có thể tồn tại ở stream
  Payment để telemetry, nhưng không còn là guard của Order và không được nhầm với
  `orders.last_event_sequence_id`.
- `ORDER_PRODUCT_METRICS_UPDATED`, `t.search.metrics.events` và search projector đã
  bị loại. `SHIPMENT_RETURNED` không còn vì Shipping Service đã bị loại Phase 14.
- `ORDER_RETURN_REQUESTED` mới chỉ từng được nêu như khả năng tách event trong một
  refactor tương lai; không phải event hiện hành.

## 6. Event tiêu thụ

### 6.1. Payment events — `order-payment-consumer`

| Event | Payload semantics tối thiểu theo thiết kế cũ | Xử lý |
|---|---|---|
| `PAYMENT_SUCCESS` | transaction + `payment_attempt_number`, order, VND amount, **currency**, paid time, financial version | Validate mapped active/cancelled attempt/order/amount/currency/version; freeze captured identity then signal active checkout or fenced late recovery |
| `PAYMENT_FAILED`/`PAYMENT_EXPIRED`/`PAYMENT_CANCELED` | transaction + attempt identity, order, financial version, terminal fact | Update only that attempt mapping then signal; only non-capture fact can complete cancellation |
| `REFUND_PROCESSING` | refund txn, captured payment txn, order, financial version | Match existing case and immutable captured transaction then signal `REFUNDING` |
| `REFUND_SUCCESS` | refund txn, captured payment txn, order, VND amount, currency, financial version | Match existing case then commit `REFUNDED` + outbox |
| `REFUND_FAILED` | refund txn, captured payment txn, order, error code, financial version | Signal; giữ `RETURN_REQUESTED`, set failure, alert |
| `REFUND_REVERSED` | refund txn, captured payment txn, order, reason code, financial version | Reopen same `REFUNDED` case/master to manual verification; no new refund |

Routing bắt buộc: payment result lookup `checkout_token` theo payload `order_id`
và signal `order-checkout-{checkout_token}`; refund result signal
`order-refund-{order_id}`. Không route theo Payment `aggregate_id` vì đó là ID
transaction. Consumer không update master FSM/outbox trực tiếp.

Dedup authority is `order_consumed_events(event_id)`. Financial version is a monotonic
semantic guard **within one payment transaction**; a mismatched/stale event is persisted as rejected/reconciliation evidence
and must not signal a business transition. Redis key is an optional 30-day accelerator.

Key fast-path canonical is `payment:signal:{order_id}:{payment_transaction_id}:{event_type}`;
the legacy alias `txn_id` is retired and must not appear in a new contract or implementation.

Signal enum theo mô tả cũ gồm `PaymentSignal.FAILED`/`PaymentResult.FAILED` và
`RefundResult.SUCCESS`, `RefundResult.FAILED`, `RefundResult.REVERSED`. Lý do reverse
điển hình `REFUND_REVERSED_BY_PROVIDER` được giữ thành `reason_code`/error phục vụ
Ops, không thành state master mới.

### 6.2. Inventory events

| Event/group | Payload semantics tối thiểu | Xử lý |
|---|---|---|
| `com.ecommerce.inventory.reservation.line-dispatched.v1` / `order-inventory-ship-consumer` | order, order line, warehouse, event ID, aggregate revision, dispatched time | Durable inbox/revision validate immutable allocation, CAS `PROCESSING -> DISPATCHED`; no customer-delivery or auto-complete inference |
| `com.ecommerce.inventory.refund.all-items-inspection-completed.v1` / `order-inventory-return-consumer` | scope ID, order, request, revision, selected lines with expected/received/passed/failed | Validate immutable case scope, then signal `InventoryReturnScopeInspectionCompleted` |

Consumers persist event identity/digest and aggregate/scope revision in durable inbox.
Inspection consumer must compare all per-line counters to immutable Order case then persist
the full evidence before signal; `courier_note` và package detail không nằm trong event.

### 6.3. Delivery events — `order-delivery-consumer`

| Event | Payload semantics tối thiểu | Xử lý |
|---|---|---|
| `com.ecommerce.delivery.order-line.delivered.v1` | order, order line, warehouse, unique `delivery_proof_id` + immutable proof-record SHA-256, delivered time, aggregate revision | Durable inbox/proof/mapping validate; CAS only that line `DISPATCHED -> DELIVERED`, derive item status, then complete master only if every line is delivered |

The exact CloudEvents schema/topic is the [Delivery AsyncAPI v1](../../contracts/events/delivery/v1/asyncapi.yaml). A proof received before physical dispatch, a duplicate proof
bound to another line, or a mismatched warehouse is parked and never mapped to delivery.

## 7. Temporal interface

| Workflow | WorkflowID | Task queue | Signals/chức năng |
|---|---|---|---|
| `OrderCheckoutWorkflow` | `order-checkout-{checkout_token}` | `order-checkout-queue` | PaymentResult, CancelOrder; pre/post-payment saga |
| `OrderRefundWorkflow` | `order-refund-{order_id}` | `order-refund-queue` | InventoryReturnScopeInspectionCompleted, InventoryPreDispatchReleaseResult, RefundResult, manual verification |
| `OrderLatePaymentRecoveryWorkflow` | `order-late-payment-recovery-{order_id}` | `order-late-payment-recovery-queue` | Khôi phục payment muộn hoặc đưa manual verification; không system auto-refund ở Release 1 |
| `OrderFinancialReconciliationWorkflow` | `order-financial-reconciliation-{order_id}` | `order-financial-reconciliation-queue` | Execute an evidence-backed `RESUME_FORWARD_REPAIR` decision only; never blind rollback or direct financial mutation |

All starts/attaches originate from durable `order_workflow_dispatches`; consumer/API crash
after local acceptance is retried by dispatcher. Activity timeout mục tiêu 30 giây, retry tối đa 3 lần. Checkout workflow timeout
30 phút gồm payment window 15 phút và buffer retry/rollback. Refund wait Inventory
là 30 ngày trước manual-verification lane.

## 8. Tương thích và khoảng trống contract

- Quy tắc cũ: chỉ thêm field, consumer ignore unknown field; breaking change cần
  version mới. Chưa có CI compatibility check hay deprecation window.
- Mỗi event có unique ID; consumer xử lý at-least-once và duplicate.
- Public và internal Order HTTP surface đã có machine-readable OpenAPI v1 ở trạng
  thái `proposed`; mọi lifecycle event ngoài `ORDER_COMPLETED` vẫn thiếu schema đầy
  đủ. Không đánh dấu `verified` nếu chưa có provider/consumer contract test.
- Các lệch `ORDER_STATUS_CHANGED` refund metadata và `ORDER_REFUND_FAILED`
  `error_message` được giữ trong [Known gaps](./README.md#9-khoảng-trống-đã-biết),
  không giải quyết trong lần refactor cấu trúc này.
