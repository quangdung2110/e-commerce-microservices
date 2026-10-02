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
  - contracts/events/order/v1/schemas/order-paid.schema.json
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
| `t.order.events` | Outbound | Kafka | Order | Cart (`com.ecommerce.order.paid.v1`), Review (`com.ecommerce.order.completed.v1`) | [Order AsyncAPI v1](../../contracts/events/order/v1/asyncapi.yaml) |
| `t.notification.transactional.v1` | Outbound | Kafka | Notification (topic owner) | Notification | [Notification AsyncAPI v1](../../contracts/events/notification/v1/asyncapi.yaml); Order là producer của `com.ecommerce.notification.requested.v1` cho 5 template `ORDER_*` |
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
| `GET /api/v1/orders/{order_id}/payment-attempts/{attempt_number}` | Đọc attempt; client secret chỉ hiện cho active `PENDING` attempt, lấy từ Payment `client-data` mỗi lần đọc (Order không lưu) | `200`, private/no-store |
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
chứa `user_id`, address snapshot, `items[]` (gồm Product-owned `category_ids`), pricing, promotion snapshot,
`allocation_plan`, `pricing_hash`, `items_hash` và campaign revision. Trường legacy
`user_ack_degraded_pricing` vẫn bắt buộc trên wire ở v1 nhưng bị khóa `false`;
Checkout không submit quotation degraded.
`allocation_plan` dùng tên canonical `allocation_plan_id`; mỗi `line_reference`/SKU/
quantity/warehouse phải ánh xạ một-một với Checkout item. Order tạo `order_line_id`
sau khi accept và persist bridge trước Reserve. Money luôn là
`{amount_minor,currency:"VND"}`; không có `payment_method`. Order persist
`category_ids` cùng line snapshot để dựng lại đúng Promotion `context.items`;
không query Product và không nhận category từ browser.

Khác command dài hạn, endpoint này chờ đến checkpoint `PAYMENT_READY` để Checkout
có thể trả Stripe data cho frontend: lần đầu `201`, replay `200`, gồm `order_id`,
attempt number, transaction ID và `payment` client data (`usable`, `client_secret`,
`publishable_key`, `expires_at`) mà Order lấy từ Payment `client-data` **tại thời điểm
trả** — Order không lưu bản sao; `usable=false` → secret `null`. Checkout relay tiếp,
không cache (Checkout ADR-010).
Promotion reserve fail trả RFC 9457 `409 PROMOTION_RECALCULATION_NEEDED`; timeout
hạ tầng trả `503 retryable=true` và Checkout retry cùng key/body. Recovery read dùng
`GET /internal/v1/checkout-order-submissions/{checkout_token}`.
Create replay và attach chỉ dựa trên unique `checkout_token`, fixed WorkflowID và
durable workflow dispatch/checkpoint; endpoint này không dùng Redis create mirror.

`pricing_hash` là chuỗi `sha256:<64 lowercase hex>` của SHA-256 trên UTF-8 RFC 8785
canonical JSON với object `{items:[{sku_id,unit_price_minor}],
promotion_campaign_revision,total_discount_minor}`; items sort theo byte UTF-8 của
`sku_id` duy nhất. `items_hash` dùng object `{items:[{quantity,sku_id}]}` cùng quy
tắc sort/canonicalization. Order cross-check cả hash lẫn amount invariant trước tạo.
`promotions` có tối đa một PLATFORM_VOUCHER; mảng rỗng bắt buộc đi cùng
`promotion_campaign_revision=0`, còn một promotion phải có revision dương tương ứng.

### 2.3 Finance/Ops

`GET /internal/v1/orders/{order_id}/financial-reconciliation-case` trả revision,
evidence references và permitted resolutions. Existing
`POST /internal/v1/orders/{order_id}/financial-reconciliation-decisions` ghi quyết
định evidence-backed; `Idempotency-Key` phải bằng `decision_id`.

`CLOSE_RETURN_WITHOUT_REFUND` additionally requires
`return_closure {refund_request_id, refund_scope_id, reason}`; reason is
`RETURN_WINDOW_EXPIRED`, `PACKAGE_LOST`, or `CUSTOMER_WITHDREW`, and must equal the
parent `reason_code`. The decision applies only to the existing Buyer manual case
with no accepted/unknown Payment initiation and no
full completion evidence. Order persists the matching Inventory query counters,
freezes close-incomplete or zero-receipt cancel, and returns `202` for durable work.
The reconciliation case closes only after the Inventory receipt; no payout is authorized.
Manual fulfillment cannot bypass this procedure for an unfinalized Buyer scope.
Scope-only `RESUME_FORWARD_REPAIR` can repair unresolved finalization on financially
`SUCCEEDED`/`FAILED` cases without changing master/payment facts. Resume of a proven
rejected no-payout decision requires full completion evidence and retains the rejected
generation/history; accepted/unknown commands cannot be superseded. Return-closure
resolution does not close other unresolved dispute/financial obligations.

## 3. Remote activities do Order gọi

| Activity | Service | Ý nghĩa | Retry/idempotency |
|---|---|---|---|
| Reserve/rollback inventory | Inventory | Giữ/nhả stock theo immutable allocation plan; rollback luôn release toàn root với reason map từ `cancel_reason` (bảng dưới) | Activity timeout/retry; stable request identity |
| Commit payment / recover timed-out payment | Inventory | Commit whole reserved root after payment, accepted whenever still `RESERVED` (no Inventory deadline); fenced recovery only for same timeout-released root | Stable command ID; query `GET /commands/{id}` after unknown outcome; never re-reserve/reroute |
| Register refund scope | Inventory | Đăng ký selected `order_line_id` với full ordered quantity | Key lifetime theo order + refund request |
| `CloseRefundScopeActivity` | Inventory | `POST /internal/inventory/v1/refund-scopes/{refund_scope_id}/close`; only `INSPECTION_COMPLETED` | Frozen command ID/body; success reason `REFUNDED`, terminal failure/reversal without prior intent `REJECTED`; response must prove `CLOSED` |
| `CloseIncompleteRefundScopeActivity` | Inventory | `POST /internal/inventory/v1/refund-scopes/{refund_scope_id}/close-incomplete`; approved no-payout decision, missing expected units and all received final-inspected | Stable command per approved generation; decision reason; response `CLOSED_INCOMPLETE` |
| `CancelRefundScopeActivity` | Inventory | `POST /internal/inventory/v1/refund-scopes/{refund_scope_id}/cancel`; approved no-payout decision, `REGISTERED` with no receipt | No request body; retain reason in Order audit; response `CANCELLED` |
| Release committed order before dispatch | Inventory | Admin automatic path: atomic release tất cả committed lines hoặc no-op conflict | Không có Refund Scope; Inventory là authority dispatch |
| `POST /internal/v1/promotion-allocation/reservations` | Promotion | TRY giữ budget runtime sau submit | Natural key order/promotion; cùng payload replay, khác payload conflict |
| `POST /internal/v1/promotion-allocation/cancellations` | Promotion | CANCEL nhả reservation trước payment confirm | Chỉ nhả `RESERVED`; trả `RELEASED`, `NO_RESERVATION` hoặc `REPAIR_REQUIRED` |
| `POST /internal/v1/promotion-allocation/confirmations` | Promotion | CONFIRM wallet `USED` + durable budget/receipt/outbox | Bắt buộc `Idempotency-Key: REDEEM_FINAL:{order_id}:{promotion_id}` |
| Initiate/cancel payment | Payment | Tạo Stripe PaymentIntent; cancel must resolve capture/non-capture before Order final-cancel | Persist numbered attempt + transaction mapping; stable Payment create/cancel key; `ALREADY_SUCCEEDED` enters paid repair |
| Initiate refund | Payment | Tạo Stripe Refund đúng một lần from the immutable captured transaction after gate | Stable refund key/transaction ID |

`RollbackInventoryActivity` maps Order `cancel_reason` to the Inventory whole-root
release reason ([Inventory ADR-021](../inventory/decisions/ADR-021-order-integration-lifecycle-corrections.md)):

| Order `cancel_reason` | Inventory `ReleaseReservation.reason` |
|---|---|
| `PAYMENT_FAILED` | `PAYMENT_FAILED` |
| `PAYMENT_TIMEOUT` | `RESERVATION_EXPIRED` (the only reason later recoverable by `RecoverTimedOutPayment`) |
| `USER_CANCEL` | `BUYER_CANCELLED` |
| `ADMIN_FORCE_CANCEL` | `ADMIN_CANCELLED` |
| `PRE_PAYMENT_SAGA_FAILED` | `PRE_PAYMENT_SAGA_FAILED` |

Inventory has no single-line committed release and no exception release; paid
pre-dispatch stock is released only through the all-line admin path above.

All scope mutations require Inventory `Idempotency-Key`, `X-Correlation-ID` and
`X-Causation-ID`. Persist intent/checkpoint/dispatch before HTTP; require matching
order/request/scope/command IDs, target outcome and scope revision on the receipt.
Timeout/503 queries `GET /internal/inventory/v1/commands/{command_id}` or replays
the frozen command. Retry exhaustion leaves durable `RECOVERY_REQUIRED`; a durable
409/mismatched receipt is `CONFLICT`, not an endpoint fallback. Reversal preserves
the original close payload, and a terminal scope cannot be reopened/replaced.
Admin pre-dispatch finalization is `NOT_REQUIRED`. API `scope_finalization` is an
optional sanitized progress projection separate from case financial status.

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

Order dùng CloudEvents 1.0 structured JSON cho mọi message trên `t.order.events`.
Kafka key, `data.order_id` và `subject=orders/{order_id}` dùng cùng canonical decimal
string không padding. `id` là UUID ổn định của outbox row và không đổi khi relay retry;
`sequence` tăng đơn điệu trên **integration event đã phát** của một order, không phải
FSM version và được phép có gap.

Delivery là at-least-once. Cart xóa SKU bằng `HDEL` idempotent; Review dedupe bằng
receipt `CloudEvents.id` và unique business key. Consumer group có retry/DLQ riêng.
Event Payment/Inventory/Delivery mà Order nhận phải qua durable inbox `event_id`,
semantic identity và provider revision/version guard trước khi signal Temporal.

## 5. Event phát ra

Order chỉ phát event có consumer runtime và interaction đã đăng ký:

| CloudEvents type | Trigger | Payload | Consumer/hành vi |
|---|---|---|---|
| `com.ecommerce.order.paid.v1` | Commit mốc tài chính `PENDING_PAYMENT -> PAID`, kể cả fenced late recovery | canonical `order_id`, UUID `user_id`, unique purchased `sku_ids[]` | Cart xóa đúng SKU đã mua, không đổi activity |
| `com.ecommerce.order.completed.v1` | Mọi line có verified Delivery proof và master CAS sang `COMPLETED` | `order_id`, `user_id`, `completed_at`, immutable product/SKU/variant/quantity items | Review cấp tối đa một entitlement cho mỗi `(order_id,product_id,sku_id)` |

`com.ecommerce.order.paid.v1` không mang amount, payment transaction/provider hay recovery flag vì
Cart không dùng. `com.ecommerce.order.completed.v1` giữ item snapshot vì Review không gọi đồng bộ
Order/Product trên write path. `variant_name` nullable cho historic order; producer
mới gửi khi checkout snapshot có giá trị.

Không phát created, cancelled, generic status-changed, line-fulfillment, refunded hoặc
refund-failed **fact** khi chưa có implemented consumer. Analytics mới chỉ có tài liệu;
Payment cancellation được quyết định qua explicit HTTP command/recovery query, không phụ
thuộc Kafka. Trạng thái còn lại được đọc qua Order HTTP API/operation resource. Một event
mới chỉ được thêm sau khi consumer, business effect và catalog interaction được đăng ký.

### 5.1 Notification request (command, không phải fact)

Thông báo cho buyer về vòng đời đơn hàng **không** đi bằng fact của Order, Payment hay
Delivery mà bằng request Order gửi thẳng lên lane transactional do Notification sở hữu.
Lý do: Payment và Delivery fact không mang `user_id` theo thiết kế, còn Order sở hữu đủ
snapshot (tên sản phẩm, số tiền, địa chỉ) để Notification render mà không phải gọi ngược
Order lúc gửi. Interaction `order-requests-notifications`; quy tắc phân loại ở
[Notification D-07](../notification/decisions.md).

| Template | Mốc | `template_data` chính | `dedup_key` |
|---|---|---|---|
| `ORDER_CONFIRMED` | `PENDING_PAYMENT -> PAID` | items (name, variant, quantity, unit price), subtotal/discount/shipping/total, payment method label, địa chỉ rút gọn (recipient_name, ward, district, city), `order_url` | `order_confirmed:{order_id}` |
| `ORDER_PAYMENT_FAILED` | Payment attempt `failed`/`expired` (Order consume `t.payment.events.v2`) | amount, attempt, `can_retry`, `retry_deadline?` | `order_payment_failed:{order_id}:{attempt}` |
| `ORDER_CANCELLED` | `-> CANCELLED` | `reason` ∈ buyer / payment_timeout / admin / out_of_stock, `refund_expected` | `order_cancelled:{order_id}` |
| `ORDER_COMPLETED` | `-> COMPLETED` | items (name, variant, quantity), `review_url` | `order_completed:{order_id}` |
| `ORDER_REFUNDED` | `refund.succeeded` (Order consume) | `refund_id`, amount | `order_refunded:{order_id}:{refund_id}` |

Quy tắc phía Order:

- Ghi request vào **cùng outbox và cùng transaction** với chuyển trạng thái; CloudEvents `id`
  sinh lúc ghi outbox để retry giữ nguyên `id`. `subject = orders/{order_id}` là Kafka key.
- `template_data` chỉ lấy từ row đơn hàng và snapshot của chính Order; **không gọi Product,
  User hay Auth** để gom thêm. Không có email, số điện thoại, địa chỉ chi tiết trong payload;
  Notification resolve email từ `user_id` lúc gửi.
- Snapshot item của Order phải mang **tên sản phẩm** (`name`). `order.completed.v1` hiện
  chỉ có `product_id`/`sku_id`/`variant_name`; nếu snapshot nội bộ cũng thiếu, Checkout ghi
  thêm field này lúc tạo snapshot — khoảng trống ghi ở §8.
- Schema `template_data`: `contracts/events/notification/v1/templates/ORDER_*.schema.json`;
  request bị Notification từ chối (`FAILED`, DLQ, alert) nếu sai schema, sai lane hoặc thiếu
  `dedup_key`.

## 6. Event tiêu thụ

### 6.1. Payment events — `order-payment-consumer`

| Business alias | Exact CloudEvents `type` | Payload semantics | Xử lý |
|---|---|---|---|
| `PAYMENT_SUCCESS` | `com.ecommerce.payment.transaction.succeeded.v2` | transaction + attempt, order, VND amount/currency, time, financial version | Validate mapping/amount/version; freeze captured identity then signal checkout hoặc fenced recovery |
| `PAYMENT_FAILED` | `com.ecommerce.payment.transaction.failed.v2` | terminal non-capture + `error_code` | Update đúng attempt rồi signal; intent đã canceled |
| `PAYMENT_EXPIRED` | `com.ecommerce.payment.transaction.expired.v2` | terminal non-capture | Như failed; chỉ fact này mới cho phép hoàn tất timeout cancellation |
| `PAYMENT_CANCELED` | `com.ecommerce.payment.transaction.canceled.v2` | terminal non-capture + `reason_code` | Match active transaction rồi hoàn tất pre-capture cancellation |
| `REFUND_PROCESSING` | `com.ecommerce.payment.refund.processing.v2` | refund txn, captured payment txn, amount, financial version | Match existing case, set `REFUNDING`; chưa final |
| `REFUND_SUCCESS` | `com.ecommerce.payment.refund.succeeded.v2` | refund txn, captured payment txn, VND amount/currency, financial version | Workflow atomically commits `REFUNDED` + Buyer `CLOSE/REFUNDED` intent/dispatch; closure receipt follows separately |
| `REFUND_FAILED` | `com.ecommerce.payment.refund.failed.v2` | refund txn, error code, financial version | Giữ `RETURN_REQUESTED`, set failure; Buyer `CLOSE/REJECTED` intent/dispatch |
| `REFUND_REVERSED` | `com.ecommerce.payment.refund.reversed.v2` | refund txn, reason code, financial version | Same case manual verification; preserve existing close intent/terminal scope; otherwise close completed Buyer scope with `REJECTED`; no new refund |

Order không subscribe `com.ecommerce.payment.transaction.initiated.v2`
(`PAYMENT_INITIATED`) hoặc `com.ecommerce.payment.refund.initiated.v2`
(`REFUND_INITIATED`): chính Order đã gọi
HTTP command và lưu durable response/checkpoint. Nhận lại initiation fact không tạo
business action mới.

Dispatch bắt buộc theo exact namespaced `type`; routing payment result lookup
`checkout_token` theo `data.order_id`
và signal `order-checkout-{checkout_token}`; refund result signal
`order-refund-{order_id}`. Không route theo Payment `aggregate_id` vì đó là ID
transaction. Consumer không update master FSM/outbox trực tiếp.

Dedup authority is `order_consumed_events(event_id)`. Financial version is a monotonic
semantic guard **within one payment transaction**; a mismatched/stale event is persisted as
rejected/reconciliation evidence and must not signal a business transition. There is no
Redis event fast-path: a duplicate, stale or out-of-order event always passes through the
durable inbox and semantic guards.

Signal enum theo mô tả cũ gồm `PaymentSignal.FAILED`/`PaymentResult.FAILED` và
`RefundResult.SUCCESS`, `RefundResult.FAILED`, `RefundResult.REVERSED`. Lý do reverse
điển hình `REFUND_REVERSED_BY_PROVIDER` được giữ thành `reason_code`/error phục vụ
Ops, không thành state master mới.

### 6.2. Inventory events

The Inventory topic contains only the two types below. Reserve/Commit/Release and
fenced late-payment recovery complete through durable HTTP receipts, not Kafka
acknowledgments. Order persists command identity/request before the remote Activity,
then checkpoints the matching result; an unknown result uses command query/same-ID
replay. Reconciliation distinguishes that original receipt from current reservation
state. Retry exhaustion retains durable recovery work and alerting. See
[Inventory ADR-020](../inventory/decisions/ADR-020-command-receipts-and-minimal-order-events.md).

| Event/group | Payload semantics tối thiểu | Xử lý |
|---|---|---|
| `com.ecommerce.inventory.reservation.line-dispatched.v1` / `order-inventory-ship-consumer` | order, order line, warehouse, event ID, aggregate revision, dispatched time | Durable inbox/revision validate immutable allocation, CAS `PROCESSING -> DISPATCHED`; no customer-delivery or auto-complete inference |
| `com.ecommerce.inventory.refund.all-items-inspection-completed.v1` / `order-inventory-return-consumer` | scope ID, order, request, revision, selected lines with expected/received/passed/failed | Validate immutable case scope, then signal `InventoryReturnScopeInspectionCompleted` |

Consumers persist event identity/digest and aggregate/scope revision in durable inbox.
Inspection consumer must compare all per-line counters to immutable Order case then persist
the full evidence before signal; `courier_note` và package detail không nằm trong event.
When a no-payout decision is unresolved, completion is retained as evidence but cannot
initiate Payment. Inventory closure has no new integration event; the accepted HTTP
command receipt proves it. Late completion for a terminal no-payout case is reconciled,
never used to reopen the scope or mint a refund.

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
| `OrderRefundScopeFinalizationWorkflow` | `order-refund-scope-finalization-{order_id}-{generation}` | `order-refund-queue` | Recover the frozen Inventory close/cancel checkpoint only; never call Payment |
| `OrderLatePaymentRecoveryWorkflow` | `order-late-payment-recovery-{order_id}` | `order-late-payment-recovery-queue` | Khôi phục payment muộn hoặc đưa manual verification; không system auto-refund ở Release 1 |
| `OrderFinancialReconciliationWorkflow` | `order-financial-reconciliation-{order_id}` | `order-financial-reconciliation-queue` | Execute proof-backed resume or dispatch an approved no-payout scope finalization; never blind rollback or direct financial mutation |

All starts/attaches originate from durable `order_workflow_dispatches`; consumer/API crash
after local acceptance is retried by dispatcher. Activity timeout mục tiêu 30 giây, retry tối đa 3 lần. Checkout workflow timeout
30 phút gồm payment window 15 phút và buffer retry/rollback. Refund wait Inventory
là 30 ngày trước manual-verification lane.
Scope retry exhaustion is not end-of-work: durable finalization dispatch/reconciler
retains the same command generation even on a financially terminal case. The 30-day
timer never triggers closure by itself. See
[ADR-013](./decisions/ADR-013-durable-refund-scope-finalization.md).

## 8. Tương thích và khoảng trống contract

- Chỉ thêm optional field là compatible; đổi CloudEvents type, Kafka key, business
  meaning hoặc field bắt buộc cần event version mới.
- Mỗi event có unique ID; consumer xử lý at-least-once và duplicate.
- Public và internal Order HTTP surface đã có machine-readable OpenAPI v1 ở trạng
  thái `proposed`; hai outbound event, năm notification request và receive surface có
  machine-readable AsyncAPI v1. Không đánh dấu `verified` trước khi Order producer/provider
  test tồn tại.
- **Khoảng trống với Notification**: `ORDER_CONFIRMED` và `ORDER_COMPLETED` cần
  `items[].name`; phải xác nhận item snapshot của Order (và Checkout snapshot nguồn) có
  field này trước khi implement `publishNotificationRequests`.
- Order proposed HTTP v1 adds `CLOSED_INCOMPLETE`, optional `scope_finalization`, and
  conditional no-payout decision input. Existing generated clients must support these
  enums before rollout; no implementation/adoption status is promoted by this edit.
  Inventory close/cancel wire shapes, guards and reason enums are unchanged.
