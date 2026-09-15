---
doc_id: order-interfaces
service: order
scope: target
status: proposed
owners:
  - Order Service team
evidence:
  - contracts/events/order/v1/asyncapi.yaml
  - contracts/events/order/v1/schemas/order-completed.schema.json
  - contracts/http/promotion/v1/openapi.yaml
verified_against_commit: null
---

# Order Service — Giao diện tích hợp

> HTTP allocation contract do Promotion sở hữu đã được chuẩn hóa tại
> [`contracts/http/promotion/v1/openapi.yaml`](../../contracts/http/promotion/v1/openapi.yaml).
> Các interface Order khác vẫn giữ trạng thái đề xuất/partial như ghi riêng bên dưới.

## 1. Danh mục tương tác

| Tương tác | Hướng | Giao thức | Provider | Consumer | Trạng thái |
|---|---|---|---|---|---|
| Order detail/search/cancel/return | Inbound | HTTP | Order | Buyer/Admin qua Gateway | proposed |
| Create from checkout | Inbound | HTTP | Order | Checkout | proposed |
| Inventory activities | Outbound | HTTP/gRPC | Inventory | Order Temporal worker | proposed |
| Promotion activities | Outbound | HTTP | Promotion | Order Temporal worker | contract target exists |
| Payment activities | Outbound | HTTP/gRPC | Payment | Order Temporal worker | proposed |
| `t.order.events` | Outbound | Kafka | Order | Cart, Notification, Review, Analytics và consumer khác; Promotion không consume refund | partial contract |
| `t.payment.events` | Inbound | Kafka | Payment | Order | proposed semantics |
| `t.inventory.events` | Inbound | Kafka | Inventory | Order | proposed semantics |
| Temporal workflows/signals | Internal | Temporal | Order workers | Order adapters/consumers | proposed |

## 2. HTTP API đầu vào

Gateway xác thực JWT và chuyển `user_id`/role đáng tin cậy. Buyer chỉ nhìn/tác
động order của mình; Admin có scope quản trị. Command bất đồng bộ trả `202`, không
giả vờ nghiệp vụ đã hoàn tất.

| API | Mục đích và semantics | Idempotency/lỗi chính |
|---|---|---|
| `GET /v1/orders/{orderId}` | Eager-load master/items/lines/promotions; Buyer owner hoặc Admin; hot PostgreSQL only | `404 NOT_FOUND`; không mô tả cache |
| `GET /v1/orders/search` | Order-level cards; filter `tab,q,from,to,cursor,limit`, trạng thái/payment/amount theo thiết kế query | Read-only; default 90 ngày; keyset cursor |
| `POST /v1/orders/{orderId}/cancel` | Buyer cancel `PENDING_PAYMENT`; Admin cancel pre-payment hoặc mở refund lane khi `PAID/PROCESSING` | Optional `Idempotency-Key`, Redis 7 ngày; `202`; invalid state `409` |
| `POST /v1/orders/{orderId}/return` | Buyer chọn một/nhiều `order_line_id`; mọi item delivered và mỗi selected line phải full quantity; one-shot per order | Header `Idempotency-Key` bắt buộc; `202` + request/workflow ID; lỗi one-shot/scope `409` |
| `POST /v1/orders/create-from-checkout` | Start/attach checkout workflow bằng token; persist server-side snapshot | WorkflowID + Redis 7 ngày; Promotion reserve failure trả structured `409` về Checkout |

Create-from-checkout yêu cầu tối thiểu `checkout_token`, UUID `user_id`,
`delivery_address_snapshot`, `items[]`; mỗi item có `warehouse_id`, line có
`sku_id`, `product_id`, `quantity>0`, `unit_price`. `promotions` có thể rỗng khi
`user_ack_degraded_pricing=true`. Order không tin amount do public client gửi và
không tự tính lại discount. `payment_method` đã bị loại vì Payment chỉ dùng Stripe.
`pricing_hash` phải được cross-check theo canonical SHA-256 input do Checkout định
nghĩa; thuật toán wire-level sẽ được chốt ở lần refactor contract.

Search response tối thiểu: `order_id`, `buyer_tab`, `ui_primary_status`,
`ui_status_rank`, `created_at`, primary item name/image, constant
`primary_store_name="PLATFORM"`, item/remaining counts và `final_amount`. Các field
matched-line của Phase 14 đã bị loại.

Tên field response chuẩn hóa gồm `primary_item_name`, `primary_item_image_url`,
`item_count`, `remaining_item_count`. Các field cũ `matched_lines_count`,
`matched_context[]`, `match_reason`, `matched_lines` không còn được trả.

## 3. Remote activities do Order gọi

| Activity | Service | Ý nghĩa | Retry/idempotency |
|---|---|---|---|
| Reserve/rollback inventory | Inventory | Giữ/nhả stock theo routing plan | Activity timeout/retry; stable request identity |
| Deduct/rollback deducted inventory | Inventory | Trừ stock sau payment hoặc bù khi post-payment fail | Bắt buộc idempotent |
| Register refund scope | Inventory | Đăng ký selected `order_line_id` với full ordered quantity | Key lifetime theo order + refund request |
| Release committed order before dispatch | Inventory | Admin automatic path: atomic release tất cả committed lines hoặc no-op conflict | Không có Refund Scope; Inventory là authority dispatch |
| `POST /internal/v1/promotion-allocation/reservations` | Promotion | TRY giữ budget runtime sau submit | Natural key order/promotion; cùng payload replay, khác payload conflict |
| `POST /internal/v1/promotion-allocation/cancellations` | Promotion | CANCEL nhả reservation trước payment confirm | Chỉ nhả `RESERVED`; trả `RELEASED`, `NO_RESERVATION` hoặc `REPAIR_REQUIRED` |
| `POST /internal/v1/promotion-allocation/confirmations` | Promotion | CONFIRM wallet `USED` + durable budget/receipt/outbox | Bắt buộc `Idempotency-Key: REDEEM_FINAL:{order_id}:{promotion_id}` |
| `POST /internal/v1/promotion-allocation/technical-cancellations` | Promotion | Bù kỹ thuật sau confirm; không phải customer refund | Bắt buộc `Idempotency-Key: CANCEL_FINAL:{order_id}:{promotion_id}`; wallet về `SAVED/EXPIRED` |
| Initiate/cancel payment | Payment | Tạo/hủy Stripe PaymentIntent | Payment sở hữu provider details |
| Initiate refund | Payment | Tạo Stripe Refund đúng một lần sau gate | Stable refund key/transaction ID |

Promotion keys do Order sinh và giữ xuyên retry:

- `REDEEM_FINAL:{order_id}:{promotion_id}`
- `CANCEL_FINAL:{order_id}:{promotion_id}`

Hai final key được giữ qua mọi Temporal retry. Promotion canonicalize request để phát
hiện cùng key khác payload; Order chỉ coi activity hoàn tất khi durable command receipt,
wallet, budget và outbox đã commit. Redis confirm finalizer hoặc technical-cancel repair
có thể hội tụ sau và không thay business response đã lưu.

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
| Order item lifecycle | `order_item_id + last_event_sequence_id`; topic vẫn partition theo order |
| Event Payment/Inventory tiêu thụ | Redis SETNX key + event ID + FSM CAS; upstream sequence chỉ telemetry |

## 5. Event phát ra

| Event | Khi phát và ý nghĩa | Dữ liệu nghiệp vụ cốt lõi | Consumer/hành vi |
|---|---|---|---|
| `ORDER_CREATED` | Create transaction chuyển transient `CREATED -> PENDING_PAYMENT` | order/user/status/sequence/final amount | Notification; không nhúng item/address/promotion snapshot |
| `ORDER_PAID` | Mốc tài chính commit `PENDING_PAYMENT -> PAID`, hoặc recovery payment muộn | order/user/status/sequence, recovery flag, payment txn, SKU IDs, final amount, paid time | Cart cleanup, Notification, Analytics |
| `ORDER_CANCELLED` | Compensation pre-payment xong và master bị cancel | order/user/status, previous state, reason, sequence, cancel time | Payment reconciliation, Notification |
| `ORDER_STATUS_CHANGED` | Master transition không có event chuyên biệt | order/user, old/new, reason, sequence, changed time | Notification |
| `ORDER_ITEM_STATUS_CHANGED` | Delivery item transition | order/item, old/new, sequence, changed time | Notification; guard theo item |
| `ORDER_REFUNDED` | Payment refund success, master `RETURN_REQUESTED -> REFUNDED` | order/user/status/sequence, refund txn/mode/amount/source/request, selected line IDs or admin-pre-dispatch evidence, time | Notification/Analytics; Promotion không consume |
| `ORDER_COMPLETED` | Tất cả item delivered và master CAS thành công | canonical string order ID, UUID user, immutable product/SKU/variant/quantity items, completed time | Review entitlement; Analytics/Settlement chưa đăng ký |
| `ORDER_REFUND_FAILED` | Payment refund failed/reversed; master giữ `RETURN_REQUESTED` | order/user/payment state/sequence, refund IDs/source, error, time | Notification/Ops critical lane |

Chi tiết quan trọng:

- `ORDER_CREATED` không nhúng address/items/promotions; snapshot đã nằm trong DB.
- `ORDER_PAID` là mốc tài chính, không có nghĩa deduct/confirm promotion đã xong.
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
- Buyer amount is gross selected-line snapshot price capped at `final_amount`, with
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
- `payload_hash`, `item_version`, `order_version` và generic `version` đã bị loại;
  idempotency/order dùng event ID, Redis, Temporal và FSM CAS.
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
| `PAYMENT_SUCCESS` | payment txn, order, amount, paid time | Lookup checkout token; signal active checkout, hoặc start late recovery nếu timeout-cancelled |
| `PAYMENT_FAILED` | payment txn, order, error code, failed time | Signal failed; workflow compensation rồi cancel |
| `PAYMENT_EXPIRED` | payment txn, order, expired time | Như failed; race với Temporal timer |
| `REFUND_PROCESSING` | refund txn, order, time | Signal refund workflow; set/read `REFUNDING` qua workflow |
| `REFUND_SUCCESS` | refund txn, order, amount, time | Signal; workflow commit `REFUNDED` + outbox |
| `REFUND_FAILED` | refund txn, order, error code, time | Signal; giữ `RETURN_REQUESTED`, set failure, alert |
| `REFUND_REVERSED` | refund txn, order, reason code, time | Signal reversed; map về failure/escalation |

Routing bắt buộc: payment result lookup `checkout_token` theo payload `order_id`
và signal `order-checkout-{checkout_token}`; refund result signal
`order-refund-{order_id}`. Không route theo Payment `aggregate_id` vì đó là ID
transaction. Consumer không update master FSM/outbox trực tiếp.

Dedup key: `payment:signal:{order_id}:{payment_transaction_id}:{event_type}` TTL
30 ngày. Tài liệu cũ nói upstream payment/refund sequence chỉ dùng telemetry, không
làm Order guard.

Một biến thể ký hiệu cũ dùng `payment:signal:{order_id}:{txn_id}:{event_type}`;
`txn_id` và `payment_transaction_id` phải được thống nhất khi refactor contract,
nhưng cả hai đều biểu diễn Payment transaction ID trong dedup key.

Signal enum theo mô tả cũ gồm `PaymentSignal.FAILED`/`PaymentResult.FAILED` và
`RefundResult.SUCCESS`, `RefundResult.FAILED`, `RefundResult.REVERSED`. Lý do reverse
điển hình `REFUND_REVERSED_BY_PROVIDER` được giữ thành `reason_code`/error phục vụ
Ops, không thành state master mới.

### 6.2. Inventory events

| Event/group | Payload semantics tối thiểu | Xử lý |
|---|---|---|
| `INVENTORY_SHIP_TO_CUSTOMER` / `order-inventory-ship-consumer` | order, warehouse, shipped time | Resolve đúng delivery unit theo order+warehouse, CAS `PROCESSING -> DELIVERED`, event item, auto-complete |
| `com.ecommerce.inventory.refund.all-items-inspection-completed.v1` / `order-inventory-return-consumer` | scope ID, order, request, revision, selected lines with expected/received/passed/failed | Validate immutable case scope, then signal `InventoryReturnScopeInspectionCompleted` |

Ship dedup key chứa `order_id`, `warehouse_id`, `shipped_at`; inspection-completion
key chứa `order_id`, `refund_request_id`, `refund_scope_id`, `scope_revision`, TTL
đều 30 ngày. Consumer must compare all per-line counters to the immutable Order case;
`courier_note` và package detail không nằm trong event.

## 7. Temporal interface

| Workflow | WorkflowID | Task queue | Signals/chức năng |
|---|---|---|---|
| `OrderCheckoutWorkflow` | `order-checkout-{checkout_token}` | `order-checkout-queue` | PaymentResult, CancelOrder; pre/post-payment saga |
| `OrderRefundWorkflow` | `order-refund-{order_id}` | `order-refund-queue` | InventoryReturnScopeInspectionCompleted, InventoryPreDispatchReleaseResult, RefundResult, manual verification |
| `OrderLatePaymentRecoveryWorkflow` | `order-late-payment-recovery-{order_id}` | `order-late-payment-recovery-queue` | Khôi phục payment muộn hoặc đưa manual verification; không system auto-refund ở Release 1 |

Activity timeout mục tiêu 30 giây, retry tối đa 3 lần. Checkout workflow timeout
30 phút gồm payment window 15 phút và buffer retry/rollback. Refund wait Inventory
là 30 ngày trước manual-verification lane.

## 8. Tương thích và khoảng trống contract

- Quy tắc cũ: chỉ thêm field, consumer ignore unknown field; breaking change cần
  version mới. Chưa có CI compatibility check hay deprecation window.
- Mỗi event có unique ID; consumer xử lý at-least-once và duplicate.
- Không có machine-readable HTTP Order contract đầy đủ hoặc AsyncAPI cho toàn bộ
  event catalog. Không đánh dấu `verified` dựa trên ví dụ JSON Markdown.
- Các lệch `ORDER_STATUS_CHANGED` refund metadata và `ORDER_REFUND_FAILED`
  `error_message` được giữ trong [Known gaps](./README.md#9-khoảng-trống-đã-biết),
  không giải quyết trong lần refactor cấu trúc này.
