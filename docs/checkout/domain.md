---
doc_id: checkout-domain
service: checkout
scope: target
status: proposed
owners:
  - Checkout Service team
evidence: []
verified_against_commit: null
---

# Checkout Service — Miền nghiệp vụ

> File này trả lời: “Checkout đang bảo vệ những quy tắc nghiệp vụ nào?” Checkout
> không có aggregate bền vững; domain của nó xoay quanh **phiên báo giá được server
> công nhận** và **token one-time-use** niêm phong phiên đó.

## 1. Ubiquitous language

| Term | Định nghĩa | Không được nhầm với |
|---|---|---|
| Checkout Session | Bản nháp báo giá được server công nhận, lưu Redis Hash `checkout:session:{user_id}`, TTL 20 phút | Order (bền vững, thuộc Order Service) |
| Checkout Token (`checkout_token`) | UUID v4 do server sinh ở Preview, là field `token` trong session; client bắt buộc gửi lại khi Submit; one-time-use | JWT; `idempotency_key` phía Order (dù Order dùng chính token này làm key) |
| Preview | Thao tác gom dữ liệu, tính giá và sinh token mới | Submit |
| Submit | Thao tác chốt đơn: xác thực token, kiểm tra giá stale, đốt token, gửi payload sang Order | Payment (thuộc Payment Service) |
| Quotation / Bảng báo giá | JSON gồm items, subtotal, voucher applied, warehouse assignments, price breakdown | Order snapshot |
| `pricing_summary` | `{total_product_amount, total_voucher_discount, final_amount}` tính ở Preview, dùng nguyên ở Submit | Số tiền client gửi (bị bỏ qua) |
| `pricing_hash` | `sha256:<64 lowercase hex>` trên UTF-8 RFC 8785 canonical JSON `{items:[{sku_id,unit_price_minor}],promotion_campaign_revision,total_discount_minor}`, items sort theo byte UTF-8 của unique `sku_id` | `items_hash` |
| `items_hash` | Cùng format/canonicalization trên `{items:[{quantity,sku_id}]}`; dùng validate downstream cache | `pricing_hash` |
| `promotion_campaign_revision` | Optimistic concurrency/cache fence của cấu hình Promotion; tham gia hard hash gate | Mutable definition version (không tồn tại; definition đã publish không update in-place) |
| `promotion_budget_version` | Phiên bản ngân sách chỉ dùng telemetry/read-time diagnostics | Hard check ở Submit |
| Soft Allocation | Sourcing mô phỏng ở Preview, không khóa kho | Hard Allocation (lock inventory trong Saga) |
| Reserve | Khóa ngân sách/voucher trong Saga (`ReservePromotionActivity`), all-or-nothing | Evaluate (validate-only, không lock budget) |
| PLATFORM_VOUCHER | Slot voucher duy nhất còn lại trong contract Promotion | Voucher shop/free-ship (đã bỏ) |
| Voucher tray | UI chọn voucher gồm tab “Voucher của tôi” (SAVED) và “Khám phá” (wallet + public ACTIVE) | Submit |
| Save & Apply | `PUT /api/v1/me/promotion-vouchers/{promotion_id}` rồi Preview lại; explicit write vào ví | Auto-save (bị cấm ở Submit) |
| Degraded pricing | Báo giá thiếu Promotion (`DEGRADED_PROMOTION`) hoặc Inventory (`DEGRADED_STOCK`) do timeout | Price changed |
| `dropped_promotions[]` | Danh sách mã bị loại (kèm `reason`, `user_message`) sau reserve fail | `surviving_promotions[]` (mã còn sống, nằm trong `proposal_checkout_data`) |
| Client Retry | Nhánh Submit khi token đã `SUBMITTED`: tìm response cache (conflict → trả lại; success → lấy client data tươi từ Order), nếu không có thì dùng payload cache gọi lại Order | Duplicate submit |

## 2. Domain objects

Checkout không có aggregate lưu trong database; các object dưới đây sống trong
Redis với TTL hoặc chỉ tồn tại trong bộ nhớ request.

- **CheckoutSession (aggregate root tạm thời):** identity là `user_id` (một
  session/user). Lifecycle: tạo ở Preview, cập nhật ở Preview kế tiếp (đổi
  voucher/địa chỉ), đốt ở Submit, biến mất khi TTL 20 phút hết. Chứa
  `token`, `status`, `delivery_address_id`, `cart_snapshot`,
  `promotion_campaign_revision`, `promotion_budget_version`, voucher selection,
  `pricing_summary`, `pricing_hash`, `items_hash`, `created_at`, `updated_at`.
  Session còn giữ `delivery_address_snapshot`, Inventory `allocation_plan` và
  `degraded_flags` để Submit không nhận các giá trị này từ client.
- **CheckoutToken (value object):** UUID v4, sinh mới mỗi lần Preview thành công;
  mọi thay đổi (đổi mã, đổi địa chỉ) sinh token mới và ghi đè session.
- **CartSnapshot (value object, tham chiếu Cart):** danh sách `{sku_id, quantity}`
  flat-list lấy từ Cart Service; `quantity` luôn lấy từ Cart, không tin client.
- **SkuPriceSnapshot (tham chiếu Product):** `current_price` (`original_price`,
  `promotional_price`, `best_price`) và `status`/`is_available` từ Product gRPC.
- **DeliveryAddressSnapshot (tham chiếu User):** `recipient_name`,
  `recipient_phone`, `address_line`, `province_code`, `district_code`, `ward_code`
  theo chuẩn User Service, lấy qua internal endpoint sau khi validate ownership.
- **VoucherSelection (value object):** map `selected_vouchers = promotion_id`
  cho slot PLATFORM; voucher không áp thì không có key (không serialize `null`).
- **WarehouseAssignment (tham chiếu Inventory):** `warehouse_id` theo từng item,
  kết quả soft allocation.
- **PricingSummary / PriceBreakdown (value object):** `total_product_amount`,
  `total_voucher_discount`, `final_amount`. Canonical dùng
  `total_voucher_discount`; `total_discount_amount` và
  `total_platform_discount` đã deprecated.
- **SubmitPayload (value object):** toàn bộ dữ liệu gửi sang Order (items,
  amount, discount, address snapshot, `allocation_plan` gồm ID/line assignments,
  `user_ack_degraded_pricing`)
  — tất cả từ Redis, lưu ở `checkout:payload:{user_id}:{token}`.
- **SubmitResponse (value object):** success `{order_id, client_secret,
  publishable_key}` (secret relay từ Order, không lưu; `null` khi `usable=false`) hoặc conflict `{error_code, action, dropped_promotions[],
  proposal_checkout_data}`; lưu ở `checkout:response:{user_id}:{token}`.

Order, tồn kho, ví voucher, ngân sách và địa chỉ thuộc service khác; Checkout chỉ
tham chiếu bằng identifier hoặc snapshot.

## 3. Commands và queries

| Loại | Tên | Actor | Xử lý bởi | Thay đổi state |
|---|---|---|---|---|
| Command | Preview (tạo session mới, `checkout_token = null`) | Buyer đã đăng nhập | Aggregation orchestrator + Calculation engine | Tạo session, token mới |
| Command | Preview (cập nhật session, gửi token cũ) | Buyer | Như trên, theo Optimization Decision Tree | Ghi đè session, token mới |
| Command | Submit | Buyer | Submit orchestrator | `PREVIEW_READY → SUBMITTED`; tạo payload cache và response cache (success: chỉ định danh) |
| Command | Save & Apply voucher | Buyer | Promotion (Checkout chỉ gọi rồi Preview lại) | Ví voucher (thuộc Promotion) |
| Query | Voucher suggestions (“Voucher của tôi”) | Buyer | Promotion `POST /api/v1/me/promotion-voucher-suggestions` | Không |
| Query | Voucher discovery (“Khám phá”) | Buyer | Promotion `POST /api/v1/promotion-discovery` | Không |
| Query | Đọc warehouse assignments của session | Buyer | `HGET checkout:session warehouse_assignments` (ví dụ selective read) | Không |

## 4. Business rules

| ID | Điều kiện | Rule | Kết quả / lỗi |
|---|---|---|---|
| `BR-CHK-001` | Preview với `checkout_token = null` | Tạo session mới từ giỏ hàng; nếu đã có session hiện hành → reject để chống gian lận nhiều phiên | `409` |
| `BR-CHK-002` | Preview với token cũ | Cho phép cập nhật session đó; sinh token mới ghi đè | Token mới |
| `BR-CHK-003` | Payload Preview cố mutate `quantity` | Checkout chỉ consume snapshot SKU + quantity từ Cart | `400 INVALID_CHECKOUT_MUTATION_SCOPE` |
| `BR-CHK-004` | Bất kỳ SKU trong giỏ có `status != ACTIVE` (BANNED, deleted, unavailable) | Từ chối preview kèm danh sách SKU không khả dụng; user phải xóa khỏi giỏ | `422` |
| `BR-CHK-005` | Tính giá | `current_price` từ Product dùng cho giá thực tế; không dùng `unit_price_at_add` của Cart | — |
| `BR-CHK-006` | Waterfall | `cart_subtotal = Σ(quantity × unit_price)`; `total_product_price = cart_subtotal − voucher_discount`; `final_amount = total_product_price` | `pricing_summary` |
| `BR-CHK-007` | Voucher | Chỉ một slot `PLATFORM_VOUCHER`; Checkout chỉ gửi lựa chọn voucher PLATFORM; validate xung đột/stacking do Promotion xử lý | — |
| `BR-CHK-008` | Chọn voucher Sàn | Chỉ được chọn mã đã có trong ví và chưa quá `expired_at`; expiry đã được Promotion snapshot theo validity mode (`CAMPAIGN_END`, `DURATION_AFTER_SAVE`, `FIXED_DATE`), không có default 30 ngày; Discovery có thể hạ mã thành `BUDGET_EXHAUSTED` | — |
| `BR-CHK-009` | Preview | Không lock budget Promotion, không khóa kho (soft allocation) | — |
| `BR-CHK-010` | Preview vượt quota | Tối đa 10 request/phút/user; Preview lock 5 giây | `429` |
| `BR-CHK-011` | Submit vượt quota | Tối đa 3 request/phút/user | `429` |
| `BR-CHK-012` | Submit không xin được lock | Lock `checkout:lock:submit:{user_id}` TTL 30 giây | `423 Locked` |
| `BR-CHK-013` | Submit khi session có cờ `DEGRADED_PROMOTION`/`DEGRADED_STOCK` | Bắt buộc `user_ack_degraded_pricing = true`; thiếu → nhả lock, từ chối | `409 DEGRADED_PRICING_NOT_CONFIRMED` |
| `BR-CHK-014` | Submit, hash tính lại ≠ `pricing_hash` | Nhả lock, từ chối; không chạy Lua; session vẫn `PREVIEW_READY` | `409 PRICE_CHANGED`, `action=RECALL_PREVIEW` |
| `BR-CHK-015` | Submit, token không khớp | Lua trả `-1` | `409` (INVALID_TOKEN) |
| `BR-CHK-016` | Submit, session hết TTL | Không có session | `404 Not Found` |
| `BR-CHK-017` | Submit, token đã `SUBMITTED` | Lua trả `-2`; trả response cache nếu có; nếu không, dùng payload cache gọi lại Order; payload hết TTL → `410 Gone` | Idempotent response / `503` / `410` |
| `BR-CHK-018` | Order trả lỗi nghiệp vụ từ ReservePromotion | Loại `failed_promotion_id`, gọi lại Promotion evaluate với tập mã còn lại, dựng `dropped_promotions[]` và `proposal_checkout_data`, cache theo token; token vẫn `SUBMITTED` | `409 PROMOTION_RECALCULATION_NEEDED`, `action=RECALL_PREVIEW` |
| `BR-CHK-019` | Order timeout/rớt mạng | Retry nội bộ tối đa 3 lần; không rollback token | `503 Service Unavailable` |
| `BR-CHK-020` | Sau `409 PROMOTION_RECALCULATION_NEEDED` | Client phải gọi lại Preview với `surviving_promotions[]` để lấy token mới trước khi Submit tiếp | — |
| `BR-CHK-021` | Submit | Không auto-save voucher vào ví; không ghi `user_coupon_wallets` | — |
| `BR-CHK-022` | Promotion timeout ở Preview | Trả `DEGRADED_PROMOTION`; UI hiển thị xác nhận “Thanh toán không áp mã giảm giá tạm thời” | Cờ degraded trong session |
| `BR-CHK-023` | Inventory routing timeout ở Preview | Trả `DEGRADED_STOCK`; UI hiển thị xác nhận “Thanh toán với giá tạm thời” | Cờ degraded trong session |
| `BR-CHK-024` | Phase 18 | Submit không nhận `payment_method`; provider cố định Stripe; Order trả `client_secret` + `publishable_key` | — |
| `BR-CHK-025` | Mọi lần trả client data | Checkout không cache `client_secret`/`publishable_key`; lấy từ Order ở mỗi lần trả (submit response hoặc recovery read); `usable=false` → `null` ([ADR-010](./decisions/ADR-010-relay-payment-client-data-no-secret-cache.md)) | Secret trong Redis/log Checkout là lỗi bảo mật |

## 5. Invariants

| ID | Invariant | Nơi enforcement |
|---|---|---|
| `INV-CHK-001` | Mỗi `checkout_token` chỉ submit đúng một lần; `PREVIEW_READY → SUBMITTED` atomic, không rollback kể cả khi Order conflict/timeout | Lua check-and-mark trên `checkout:session:{user_id}`; Order dedup bằng `idempotency_key = checkout_token` |
| `INV-CHK-002` | Một user chỉ có một session hiện hành | Key `checkout:session:{user_id}`; `BR-CHK-001` |
| `INV-CHK-003` | Mọi số tiền gửi sang Order đến từ `pricing_summary` server-side; amount client bị bỏ qua | Submit orchestrator đọc session Redis, không đọc body |
| `INV-CHK-004` | Lua chỉ chạy sau khi `pricing_hash` tính lại khớp; hash lệch để session nguyên `PREVIEW_READY` | Thứ tự Submit: lock → hash → Lua |
| `INV-CHK-005` | Preview không khóa kho và không lock budget; Reserve trong Saga là quyết định cuối cho exhausted budget | Aggregation orchestrator chỉ gọi calculate/preview endpoints |
| `INV-CHK-006` | Submit không ghi ví voucher; reserve fail không làm phép trừ cục bộ mà gọi lại Promotion evaluate | Submit orchestrator; `BR-CHK-018`, `BR-CHK-021` |
| `INV-CHK-007` | Checkout không mutate `quantity`; quantity luôn từ Cart snapshot | `BR-CHK-003`, `BR-CHK-005` |
| `INV-CHK-008` | Submit ở chế độ degraded phải có `user_ack_degraded_pricing = true` | `BR-CHK-013` |
| `INV-CHK-009` | Tại một thời điểm mỗi user chỉ có một luồng Preview và một luồng Submit đang chạy | Lock `checkout:lock:preview` (5s) và `checkout:lock:submit` (30s) |
| `INV-CHK-010` | Cùng token retry luôn nhận cùng response (success hoặc conflict nghiệp vụ) trong 20 phút | `checkout:response:{user_id}:{token}` |

Chưa có test làm evidence; mỗi invariant phải có test khi implementation được thêm
(xem `development.md`).

## 6. State machine

Session status (`checkout:session:{user_id}.status`):

| State | Ý nghĩa | Initial/Terminal |
|---|---|---|
| `PREVIEW_READY` | Session mới tạo hoặc vừa cập nhật sau Preview | Initial |
| `SUBMITTED` | User đã Submit; token đã dùng một lần | Terminal về mặt nghiệp vụ |
| `EXPIRED` | TTL hết hạn hoặc bị invalidate; thực tế key biến mất | Terminal |

| Transition | Trigger | Guard | Side effect |
|---|---|---|---|
| `∅ → PREVIEW_READY` | Preview thành công (`checkout_token = null`) | Không có session hiện hành | Ghi session, token mới, TTL 20 phút |
| `PREVIEW_READY → PREVIEW_READY` | Preview với token cũ (đổi voucher/địa chỉ/cart snapshot mới) | Token khớp | Token mới ghi đè, hash/pricing cập nhật |
| `PREVIEW_READY → SUBMITTED` | Submit qua Lua check-and-mark | Lock đã giữ; degraded ack hợp lệ; `pricing_hash` khớp; token khớp | Lưu payload; gọi Order |
| `SUBMITTED → SUBMITTED` | Submit lại cùng token | Lua trả `-2` | Trả 409 cache, hoặc lấy client data tươi từ Order (success), hoặc retry Order bằng payload cache |
| `* → EXPIRED` | TTL 20 phút | — | Submit sau đó → `404`; payload hết TTL → `410` |

Transition không hợp lệ:

- `SUBMITTED → PREVIEW_READY`: cấm (không rollback token). Muốn đặt tiếp phải tạo
  token mới qua Preview.
- Submit khi token không khớp: Lua `-1` → `409`.
- Submit khi không có session: `404`.

Trạng thái phụ trong session: cờ degraded (`DEGRADED_PROMOTION`,
`DEGRADED_STOCK`) do Preview đặt khi downstream timeout; chỉ ảnh hưởng guard
`user_ack_degraded_pricing`.

## 7. Authorization rules

- Gateway xác thực JWT; Checkout nhận `user_id` tin cậy và chỉ thao tác trên
  `checkout:session:{user_id}` của chính user đó.
- `address_id` do client gửi phải được xác thực ownership qua User internal API
  (mTLS) trước khi lấy snapshot.
- `promotion_id` là opaque string; tính hợp lệ và số tiền do Promotion trả về.
- Các lời gọi `/internal` sang Promotion, Inventory, User dùng service
  credential/mTLS theo contract của service tương ứng (Promotion allowlist
  `checkout-service`; User `checkoutService` security scheme).
- Không có thao tác admin trong Checkout.

## 8. Domain errors

| Error code | Điều kiện | Retryable | Mapping |
|---|---|---|---|
| `INVALID_CHECKOUT_MUTATION_SCOPE` | Payload Preview cố mutate quantity | Không | HTTP `400` |
| (SKU không khả dụng) | SKU có `status != ACTIVE` | Không (user phải sửa giỏ) | HTTP `422` kèm danh sách SKU |
| (Session đã tồn tại) | Preview `checkout_token = null` khi đã có session | Không | HTTP `409` |
| (Rate limited / preview locked) | Vượt 10 req/phút hoặc lock preview đang giữ | Có, sau khi chờ | HTTP `429` |
| `LOCKED` | Không xin được submit lock | Có (client chờ) | HTTP `423` |
| `DEGRADED_PRICING_NOT_CONFIRMED` | Session degraded, thiếu ack | Không (client phải gửi ack) | HTTP `409` |
| `PRICE_CHANGED` | `pricing_hash` lệch | Không (gọi lại Preview) | HTTP `409`, `action=RECALL_PREVIEW` |
| `INVALID_TOKEN` (Lua `-1`) | Token không khớp session | Không | HTTP `409` |
| `ALREADY_SUBMITTED` (Lua `-2`) | Token đã dùng | Không (đi nhánh idempotent) | Response cache / recovery |
| (Session không tồn tại) | TTL hết | Không | HTTP `404` |
| `PROMOTION_RECALCULATION_NEEDED` | Reserve voucher fail all-or-nothing | Không (gọi lại Preview với `surviving_promotions[]`) | HTTP `409`, `action=RECALL_PREVIEW` |
| (Payload hết hạn) | Retry sau khi `checkout:payload` hết TTL | Không (Preview lại) | HTTP `410 Gone` |
| (Order unavailable) | Order timeout/down sau 3 lần retry | Có (client retry, đi nhánh recovery) | HTTP `503` |

Lỗi hạ tầng (Redis unavailable, gRPC timeout của Product) không phải domain error;
xem `operations.md`.

## 9. Business examples

**Ví dụ 1 — Preview hợp lệ.** Giỏ có `sku-101` × 2, `unit_price = 22 000 000`,
không voucher. Wave 1 trả địa chỉ, cart snapshot, giá hiện tại, kho 5 đủ hàng.
Kết quả `subtotal = 44 000 000`, `voucher_applied = {promotion_id: null,
discount_amount: 0}`, `price_breakdown = {44 000 000, 0, 44 000 000}`,
`warehouse_assignments = [{warehouse_id: 5}]`, token `uuid-v4-xxx`, session
`PREVIEW_READY`.

**Ví dụ 2 — Đổi voucher.** Client gửi lại token cũ + `selected_vouchers`. Chỉ
Promotion được gọi lại (< 20ms); cache address/cart/routing vẫn dùng; token mới
ghi đè session; `pricing_hash` đổi vì `total_voucher_discount` đổi.

**Ví dụ 3 — Giá đổi giữa Preview và Submit.** Admin sửa giá `sku-101` trong 20
phút. Submit xin lock OK, batch fetch giá mới → hash ≠ `pricing_hash` → nhả lock,
`409 PRICE_CHANGED`. Session vẫn `PREVIEW_READY`; client làm mới trang (Preview).

**Ví dụ 4 — Reserve fail.** Submit thành công qua Lua, Order chạy Saga,
`ReservePromotion` fail vì `promo-30` hết budget. Checkout loại `promo-30`, gọi
lại Promotion với mã còn lại; `promo-10` không còn đủ điều kiện do tổng tiền đổi;
`promo-55` sống. Trả `409 PROMOTION_RECALCULATION_NEEDED` với
`dropped_promotions = [promo-30 (BUDGET_EXHAUSTED), promo-10
(CONDITIONS_NOT_MET)]`, `proposal_checkout_data = {new_total_amount: 550000,
surviving_promotions: ["promo-55"]}`; token vẫn `SUBMITTED`; cache 409 theo token.

**Ví dụ 5 — Rớt mạng sau khi đốt token.** Lua OK, payload đã lưu, Order không trả
lời sau 3 lần retry → `503`. App retry Submit: Lua `-2`, không có response cache,
có payload cache → gọi lại Order; Order dedup theo `checkout_token` và trả
`order_id` cũ → cache response, xóa payload, trả client.

**Ví dụ 6 — Vi phạm scope.** Payload Preview chứa `items[].quantity` khác Cart →
`400 INVALID_CHECKOUT_MUTATION_SCOPE`; client phải gọi Cart API trước.

**Ví dụ 7 — Hai tab.** Cả hai tab cùng token (một session/user). Tab A submit
trước thắng; tab B nhận `409` hoặc response đã cache của tab A.
