---
doc_id: checkout-interfaces
service: checkout-service
scope: target
status: proposed
owners:
  - Checkout Service team
evidence:
  - ../../contracts/http/checkout/v1/openapi.yaml
  - ../../contracts/http/cart/v1/openapi.yaml
  - ../../contracts/http/user/v1/openapi.yaml
  - ../../contracts/grpc/ecommerce/product/v1/product_service.proto
  - ../../contracts/http/inventory/workflow/v1/openapi.yaml
  - ../../contracts/http/promotion/v1/openapi.yaml
  - ../../contracts/http/order/internal/v1/openapi.yaml
verified_against_commit: null
---

# Checkout Service — Interfaces

## Public API

Canonical contract: [`contracts/http/checkout/v1/openapi.yaml`](../../contracts/http/checkout/v1/openapi.yaml).

| Endpoint | Client được gửi | Thành công | Deadline |
|---|---|---|---:|
| `POST /api/v1/checkout/preview` | Header `Idempotency-Key` (UUID); body `previous_checkout_token?`, `delivery_address_id`, `promotion_id?` | `200 CheckoutPreview` | 5 s |
| `GET /api/v1/checkout/previews/{checkout_token}/voucher-options` | Token trong path | `200 VoucherOptions` | 1,5 s |
| `POST /api/v1/checkout/submit` | Body `checkout_token` | `201` lần đầu, `200` replay, `202` đang xử lý/chưa rõ | 20 s |
| `GET /api/v1/checkout/submissions/{checkout_token}` | Token trong path | `200` sẵn sàng thanh toán, `202` đang xử lý/chưa rõ | 20 s |

Mọi response, kể cả lỗi, mang `Cache-Control: private, no-store`. Auth context đến từ
Gateway (`X-User-Id`); client không gửi `user_id`, tier, role, channel hay giá trong body.
`X-Request-Id` là gợi ý không bắt buộc: giá trị không khớp `^[A-Za-z0-9._:-]{8,128}$` bị
thay bằng trace ID, không bao giờ làm request lỗi.

### Preview: ngữ nghĩa `previous_checkout_token`

| Trường hợp | Kết quả |
|---|---|
| Token hiện hành của user đang `SUBMITTING`/`RETRYABLE` | `409 SUBMISSION_IN_PROGRESS` kèm `status_url`, không fan-out, không tạo gì |
| Không gửi `previous` (tải trang, tab mới, sau kết quả terminal) | Tạo báo giá mới; token `PREVIEWED` hiện tại (nếu có) thành `SUPERSEDED` |
| Gửi `previous` = token `PREVIEWED` hiện hành (đổi địa chỉ/voucher) | Tạo báo giá mới; token cũ thành `SUPERSEDED` |
| Gửi `previous` khác token `PREVIEWED` hiện hành | `409 ACTIVE_CHECKOUT_CHANGED`; client báo người dùng rồi gọi lại không kèm `previous` |
| Cùng `Idempotency-Key`, cùng body trong 5 phút | Replay đúng response cũ |
| Cùng `Idempotency-Key`, body khác | `409 IDEMPOTENCY_KEY_REUSED` |

Preview lỗi (`422`, `503`) không đổi trạng thái nào; báo giá cũ vẫn dùng được. Quyết định ở
[ADR-016](./decisions/ADR-016-fresh-preview-and-in-flight-submission-guard.md).

### `CheckoutPreview`: nguồn từng field

| Field | Nguồn | Quy tắc tại Checkout |
|---|---|---|
| `checkout_token` | Checkout | UUIDv4 CSPRNG |
| `expires_at` | Checkout | Tạo + 20 phút |
| `address` | User checkout snapshot | Chép nguyên, gồm `address_id` |
| `items[].line_reference` | Checkout | Bằng `sku_id`; items sort theo byte UTF-8 của `sku_id` |
| `items[].product_id`, `sku_id`, `quantity` | Cart | `product_id` phải trùng Product và là UUID |
| `items[].product_name`, `variant_attributes` | Product `GetBatchSkus` | Chép nguyên; `variant_attributes` là mã thuộc tính → giá trị (Product chưa có nhãn) |
| `items[].image_url` | Product `image_url` (storage key) | `CHECKOUT_MEDIA_CDN_BASE_URL` + `/` + key percent-encode từng segment; key rỗng thì `CHECKOUT_MEDIA_PLACEHOLDER_URL` |
| `items[].list_unit_price` | Product `base_price` | Chỉ để hiển thị giá gạch |
| `items[].unit_price` | Product `price` | Giá dùng cho mọi phép tính |
| `items[].line_total` | Checkout | `unit_price × quantity` |
| `promotion` | Promotion calculation | `promotion_id=null` thì `campaign_revision=0`, discount 0 |
| `pricing` | Checkout | `total_amount = Σ line_total`; `total_discount` = Promotion `discount`; `final_amount = total_amount − total_discount ≥ 1`; không phí ship |

Response không chứa `warehouse_id`, `allocation_plan_id`, `category_ids`, `catalog_revision`,
`items_hash` hay `pricing_hash`; đó là dữ liệu nội bộ lưu trong session.

### `VoucherOptions`: nguồn từng field

| Field | Nguồn | Quy tắc |
|---|---|---|
| `applied_promotion_id`, `items[].is_applied` | Session | Voucher đang áp trong báo giá |
| `items[].promotion_id`, `eligible` | Promotion `evaluation` | Chép nguyên |
| `items[].source` | Promotion `evaluation.source` | `WALLET`/`PUBLIC`; `UNKNOWN` bị bỏ và đếm metric |
| `items[].required_action` | `source` + `distribution_mode` | `WALLET` → `NONE`; `PUBLIC`+`PUBLIC_SAVE` → `SAVE`; `PUBLIC`+`LIMITED_CLAIM` → `CLAIM`; tổ hợp khác bị bỏ |
| `items[].expected_campaign_revision` | `evaluation.campaign_revision` | Dùng làm `expected_campaign_revision` khi claim |
| `items[].reason` | `evaluation.reason` | Giá trị chưa biết thành `UNKNOWN` |
| `items[].estimated_discount`, `estimated_final_amount` | `evaluation.discount`, `final_amount` | Advisory |
| `items[].code`, `name`, `description`, `min_order_amount`, `valid_until` | Promotion `display` | Chép nguyên |
| `items[].discount_rule` | `display.discount` | `PERCENTAGE` giữ `value_basis_points`, `max_discount`; `FIXED_VND` giữ `value` |

Thứ tự item giữ nguyên ranking của Promotion. Không có cache; mỗi lần mở khay là một lời gọi.

### Submit và status

`CheckoutSubmissionReady` chứa `order_id`, `order_status=PENDING_PAYMENT` và `payment` lấy
từ Order trong **cùng request** (lần đầu từ response POST, replay/status từ Order GET).
Checkout không lưu `client_secret`/`publishable_key`; `usable=false` thì hai field này là
`null` và frontend chuyển sang trang đơn hàng
([ADR-010](./decisions/ADR-010-relay-payment-client-data-no-secret-cache.md)).

`CheckoutSubmissionPending.state`: `PREPARING` khi Order đang xử lý; `RESULT_UNKNOWN` khi
chưa biết Order đã nhận hay chưa. Cả hai kèm `Retry-After` và `status_url`; client chỉ
được poll, không được tạo báo giá mới (Preview cũng sẽ trả `409 SUBMISSION_IN_PROGRESS`).

## Error catalog

Mọi lỗi là `application/problem+json` theo
[HTTP convention](../../contracts/conventions/http.md#error) với schema `CheckoutProblem`:
`type`, `title`, `status`, `detail`, `instance`, `code`, `retryable`, `action`, `trace_id`
và extension theo code. Không bao giờ trả body thô của downstream. Quyết định ở
[ADR-015](./decisions/ADR-015-problem-catalog-view-model-and-order-outcomes.md).

`action` cho frontend biết bước tiếp theo:

| `action` | Frontend làm gì |
|---|---|
| `RETRY` | Gửi lại đúng request sau `Retry-After` |
| `START_NEW_PREVIEW` | Thông báo, rồi gọi Preview **không** kèm `previous_checkout_token` |
| `EDIT_CART` | Đưa người dùng về giỏ hàng (kèm `sku_ids`/`rejected_lines` nếu có) |
| `CHANGE_ADDRESS` | Mở chọn địa chỉ, rồi Preview kèm `previous` |
| `CHANGE_VOUCHER` | Mở khay voucher hoặc bỏ voucher, rồi Preview |
| `VIEW_SUBMISSION` | Chuyển sang màn trạng thái, poll `status_url` |
| `NONE` | Lỗi của client; sửa request, không tự retry |

| Code | HTTP | `retryable` | `action` | Extension | Khi nào |
|---|---:|---|---|---|---|
| `INVALID_REQUEST` | 400 | false | `NONE` | `errors` | Body/header sai contract, thiếu `Idempotency-Key`, field lạ |
| `UNAUTHENTICATED` | 401 | false | `NONE` | — | Thiếu trusted `X-User-Id` |
| `IDEMPOTENCY_KEY_REUSED` | 409 | false | `NONE` | — | Cùng key Preview, body khác |
| `ACTIVE_CHECKOUT_CHANGED` | 409 | false | `START_NEW_PREVIEW` | — | `previous_checkout_token` không còn là token `PREVIEWED` hiện hành |
| `SUBMISSION_IN_PROGRESS` | 409 | false | `VIEW_SUBMISSION` | `status_url` | Preview khi user có token `SUBMITTING`/`RETRYABLE` |
| `CHECKOUT_ALREADY_SUBMITTED` | 409 | false | `VIEW_SUBMISSION` | `status_url` | Voucher options cho token đã submit |
| `CART_EMPTY` | 422 | false | `EDIT_CART` | — | Cart không có dòng nào |
| `SKU_UNAVAILABLE` | 422 Preview / 409 Submit | false | `EDIT_CART` | `sku_ids` (+`changed_fields` khi Submit) | SKU không có, không `ACTIVE`, hoặc `product_id` lệch Cart |
| `ADDRESS_NOT_FOUND` | 422 | false | `CHANGE_ADDRESS` | — | User trả `404` |
| `INSUFFICIENT_STOCK` | 422 | false | `EDIT_CART` | `rejected_lines` | Inventory `REJECT_ALL` |
| `PROMOTION_INVALID` | 422 | false | `CHANGE_VOUCHER` | `promotion_reason` | Promotion `eligible=false` lúc Preview |
| `FINAL_AMOUNT_NOT_PAYABLE` | 422 | false | `CHANGE_VOUCHER` | — | `final_amount < 1 VND` |
| `CHECKOUT_NOT_FOUND` | 404 | false | `START_NEW_PREVIEW` | — | Token không thuộc user hoặc record đã bị xoá vật lý |
| `SUBMISSION_NOT_FOUND` | 404 | false | `NONE` | — | Status cho token chưa từng submit |
| `CHECKOUT_EXPIRED` | 410 | false | `START_NEW_PREVIEW` | — | Token `PREVIEWED` quá `expires_at` |
| `CHECKOUT_SUPERSEDED` | 410 | false | `START_NEW_PREVIEW` | — | Token đã bị fresh preview thay thế |
| `CART_CHANGED` | 409 | false | `START_NEW_PREVIEW` | `changed_fields` | `items_hash` lúc Submit khác báo giá |
| `PRICE_CHANGED` | 409 | false | `START_NEW_PREVIEW` | `changed_fields` | Giá bán hoặc `category_ids` đổi lúc Submit |
| `PROMOTION_CHANGED` | 409 | false | `CHANGE_VOUCHER` | `changed_fields`, `promotion_reason` | Promotion lúc Submit: không còn eligible, discount hoặc `campaign_revision` khác |
| `PROMOTION_RECALCULATION_NEEDED` | 409 | false | `CHANGE_VOUCHER` | `dropped_promotion` | Order: Promotion Reserve từ chối voucher |
| `INVENTORY_RESERVATION_REJECTED` | 409 | false | `EDIT_CART` | — | Order: Inventory Reserve `REJECT_ALL` |
| `PAYMENT_INITIATION_REJECTED` | 409 | false | `START_NEW_PREVIEW` | — | Order: Payment từ chối khởi tạo bền vững |
| `CHECKOUT_PAYMENT_WINDOW_EXHAUSTED` | 409 | false | `START_NEW_PREVIEW` | — | Order: thời gian giữ hàng không đủ để thanh toán |
| `ORDER_CANCELLED_DURING_PREPARATION` | 409 | false | `START_NEW_PREVIEW` | — | Order: đơn bị huỷ trước `PAYMENT_READY` |
| `SUBMISSION_NOT_ACCEPTED` | 409 | false | `START_NEW_PREVIEW` | — | Order xác nhận chưa nhận submission sau cửa sổ retry |
| `SUBMISSION_REJECTED` | 409 | false | `START_NEW_PREVIEW` | — | Order trả `409`/`422` ngoài các code trên (lỗi contract, có alert) |
| `CHECKOUT_UNAVAILABLE` | 503 | true | `RETRY` | `Retry-After` | Dependency bắt buộc lỗi, circuit mở, bulkhead đầy hoặc hết deadline |
| `VOUCHER_OPTIONS_UNAVAILABLE` | 503 | true | `RETRY` | `Retry-After` | Promotion không trả được voucher options |

Khi Submit phát hiện nhiều thay đổi, `changed_fields` liệt kê tất cả (`CART`,
`AVAILABILITY`, `PRICE`, `PROMOTION`) và `code` lấy theo thứ tự ưu tiên `CART_CHANGED` >
`SKU_UNAVAILABLE` > `PRICE_CHANGED` > `PROMOTION_CHANGED`. Cùng một code có thể trả `422`
lúc Preview (không dựng được báo giá) và `409` lúc Submit (báo giá đã cũ).

`429` do Gateway trả theo profile `checkout-preview`, `checkout-submit`,
`checkout-voucher-options`; Checkout không có rate limiter riêng.

## Internal dependencies

| Interaction | Provider | Contract | Ghi chú |
|---|---|---|---|
| Cart snapshot | Cart | `GET /internal/v1/carts/{user_id}/checkout-snapshot` | Tối đa 50 `items[{product_id, sku_id, quantity}]`; Redis miss trả mảng rỗng; không đọc `cart:user:*` |
| Address snapshot | User (cổng internal) | `GET /internal/v1/users/{user_id}/addresses/{address_id}/checkout-snapshot` | `404` = không thuộc user; `429` tính là lỗi dependency |
| SKU batch | Product | gRPC `ProductService/GetBatchSkus` | Tối đa 100 SKU unique mỗi lời gọi; giỏ 50 dòng nên luôn một lời gọi |
| Allocation preview | Inventory | `POST /internal/inventory/v1/allocation-previews` | Stateless, không giữ hàng; request chỉ có `lines`, không có địa chỉ |
| Discount calculation | Promotion | `POST /internal/v1/promotion-discount-calculations` | Pure query; chỉ gọi khi có `promotion_id` |
| Voucher options | Promotion | `POST /internal/v1/promotion-voucher-options` | `proposed`; chỉ `checkout-service` được gọi |
| Order submission | Order | `POST /internal/v1/checkout-order-submissions` | `Idempotency-Key = checkout_token`; chờ tới `PAYMENT_READY` tối đa 12 giây |
| Order recovery | Order | `GET /internal/v1/checkout-order-submissions/{checkout_token}` | Nguồn duy nhất của Payment client data khi replay |

### Header gửi cho dependency

| Header / metadata | Cart | User | Product | Inventory | Promotion | Order |
|---|---|---|---|---|---|---|
| `X-Service-Identity: checkout-service` | Có | Có | Có (metadata `x-service-identity`) | Có (bắt buộc) | Có | Có |
| `traceparent`/`tracestate` | Có | Có | Có | Có | Có | Có |
| `X-Request-Id` (đã chuẩn hoá) | Có | Có | Có (metadata `x-request-id`) | — | Có | Có |
| `X-Correlation-ID` (= request ID đã chuẩn hoá) | — | — | — | Có (bắt buộc) | — | — |
| `X-Causation-ID` (= `Idempotency-Key` của Preview) | — | — | — | Có (bắt buộc) | — | — |
| `Idempotency-Key` (= `checkout_token`) | — | — | — | — | — | Chỉ POST |

Request ID đã chuẩn hoá: dùng `X-Request-Id` từ Gateway nếu khớp
`^[A-Za-z0-9._:-]{8,128}$`, nếu không thì dùng trace ID (32 ký tự hex). Nhờ vậy client không
thể làm Inventory hay Order trả `400` bằng header sai độ dài. Request ID nằm trong mọi dòng
log để tra chéo với provider. Không gửi `Authorization`, cookie hay header client nào khác.

### Mapping Cart + Product → báo giá

1. Cart rỗng → `422 CART_EMPTY`. Lấy danh sách `sku_id` unique.
2. `GetBatchSkus`: SKU nằm trong `not_found_sku_ids`/`unavailable_sku_ids`, `status` khác
   `SKU_STATUS_ACTIVE`, hoặc `product_id` khác Cart → `422 SKU_UNAVAILABLE` kèm `sku_ids`.
3. `product_id` không phải UUID, `price > base_price`, thiếu `price`, `currency` khác `VND`
   hay `category_ids` quá 20 → response sai contract → `503 CHECKOUT_UNAVAILABLE`.
4. `items_hash` = `sha256:` + hex SHA-256 của RFC 8785 JSON `{"items":[{"quantity","sku_id"}]}`
   sort theo byte UTF-8 `sku_id` — đúng recipe trong Order OpenAPI.

### Mapping sang Inventory

Request `lines[{line_reference=sku_id, sku_id, quantity}]` theo cùng thứ tự. Chỉ chấp nhận
`ALLOCATABLE` khi mỗi `line_reference` xuất hiện đúng một lần với cùng `sku_id`/`quantity`
và có `warehouse_id`; lệch là sai contract (`503`). `REJECT_ALL` → `422 INSUFFICIENT_STOCK`
với `rejected_lines[{sku_id, quantity}]`. `503 INVENTORY_UNAVAILABLE` hay timeout →
`503 CHECKOUT_UNAVAILABLE`, không có degraded stock.

### Mapping sang Promotion calculation

| Field | Giá trị |
|---|---|
| `user_id` | Trusted `X-User-Id` |
| `user_tier` | Omit (v1 chưa có tier authority) |
| `promotion_id` | Từ Preview request |
| `context.subtotal` | `total_amount` |
| `context.payment_method` | `STRIPE` |
| `context.checkout_channel` | `CHECKOUT` |
| `context.items[]` | `product_id`, `sku_id`, `quantity`, `unit_price` (Product `price`), `category_ids` (Product) |

Xử lý kết quả:

- `eligible=true` → áp `discount`; `campaign_revision` phải > 0.
- `eligible=false` → `422 PROMOTION_INVALID` với `promotion_reason = reason`. Trường hợp
  `VOUCHER_NOT_OWNED` nghĩa là voucher công khai chưa lưu: frontend lưu rồi Preview lại.
- `subtotal` khác `total_amount`, `discount > subtotal`, hay `final_amount` của Promotion
  khác phép trừ của Checkout → sai contract → `503`.
- `budget_guard` chỉ dùng cho metric; `DEGRADED`/`EXHAUSTED` không đổi quyết định vì
  Promotion đã phản ánh vào `eligible`/`reason`, và Order Reserve là quyết định cuối.
- Lỗi hạ tầng → `503 CHECKOUT_UNAVAILABLE`, không bao giờ "không giảm giá".

### Mapping sang Promotion voucher options

Request `{user_id, context, include_public: true, include_ineligible: true, limit: 50}`
với `context` dựng từ session đúng như bảng calculation ở trên (không gọi lại Cart/Product).
Bỏ `user_tier`. Map response theo bảng [VoucherOptions](#voucheroptions-nguồn-từng-field).

### Mapping sang Order submission

| Field Order | Nguồn |
|---|---|
| `checkout_token` | Token |
| `user_id` | Trusted `X-User-Id` |
| `items_hash`, `pricing_hash` | Recipe RFC 8785 + SHA-256 trong Order OpenAPI; không định nghĩa công thức riêng |
| `promotion_campaign_revision` | `0` khi không có voucher, ngược lại revision từ Promotion |
| `user_ack_degraded_pricing` | Luôn `false` |
| `delivery_address_snapshot` | User snapshot bỏ `address_id`; `province_name`/`district_name`/`ward_name` không gửi |
| `items[].line_reference`, `product_id`, `sku_id`, `quantity`, `unit_price`, `category_ids` | Session |
| `items[].snapshot_name` | Product `product_name` |
| `items[].snapshot_image_url` | URL CDN như báo giá; `null` khi SKU không có ảnh (không gửi placeholder) |
| `items[].snapshot_attributes` | Product `variant_attributes` |
| `items[].variant_name` | Không gửi (Product chưa có nhãn biến thể) |
| `pricing` | `total_amount`, `total_discount`, `final_amount` |
| `promotions` | `[]` hoặc `[{promotion_id, discount_amount}]` |
| `allocation_plan` | `allocation_plan_id` + `lines[{line_reference, sku_id, warehouse_id, quantity}]` từ Inventory |

### Mapping kết quả Order

| Order trả | State Checkout | Public |
|---|---|---|
| POST `201`/`200` | `SUCCEEDED` | `201` (lần đầu) hoặc `200` |
| POST `409 PROMOTION_RECALCULATION_NEEDED` | `CONFLICT` | `409 PROMOTION_RECALCULATION_NEEDED`, `dropped_promotion` = phần tử đầu của `dropped_promotions` |
| POST `409 INVENTORY_RESERVATION_REJECTED` | `FAILED` | `409 INVENTORY_RESERVATION_REJECTED` |
| POST `409 PAYMENT_INITIATION_REJECTED` | `FAILED` | `409 PAYMENT_INITIATION_REJECTED` |
| POST `409` code khác, hoặc `422` (ví dụ `INVALID_CHECKOUT_SNAPSHOT`) | `FAILED` | `409 SUBMISSION_REJECTED`; alert `contract_violation` |
| POST `400`/`401`/`403` | `RETRYABLE` (Order chưa nhận) | `503 CHECKOUT_UNAVAILABLE`; alert cấu hình |
| POST `503`, timeout, lỗi kết nối | `RETRYABLE` | `202 RESULT_UNKNOWN` |
| GET `state=PAYMENT_READY` | `SUCCEEDED` | `200` với `payment` mới |
| GET `state=PREPARING` | giữ nguyên | `202 PREPARING` |
| GET `state=RECALCULATION_REQUIRED` | `CONFLICT` | `409 PROMOTION_RECALCULATION_NEEDED` |
| GET `state=FAILED`, `failure.code` ∈ {`INVENTORY_RESERVATION_REJECTED`, `PAYMENT_INITIATION_REJECTED`, `CHECKOUT_PAYMENT_WINDOW_EXHAUSTED`, `ORDER_CANCELLED_DURING_PREPARATION`} | `FAILED` | `409` cùng code |
| GET `state=FAILED`, code khác | `FAILED` | `409 SUBMISSION_REJECTED` |
| GET `404` | Theo [WF-04](./workflows.md#wf-04-recovery-sau-kết-quả-không-rõ) | Re-POST, `202`, hoặc `409 SUBMISSION_NOT_ACCEPTED` |
| GET lỗi hạ tầng | giữ nguyên | `202 RESULT_UNKNOWN` |

Không retry business `4xx`. Không retry trong cùng request; mọi lần gửi lại Order đi qua
luồng recovery với cùng payload và key.
