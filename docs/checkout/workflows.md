---
doc_id: checkout-workflows
service: checkout
scope: target
status: proposed
owners:
  - Checkout Service team
evidence: []
verified_against_commit: null
---

# Checkout Service — Luồng nghiệp vụ

> Các luồng dưới đây bảo toàn happy path, timeout, retry, race và recovery của
> thiết kế cũ cho hai API quan trọng nhất (Preview, Submit) và các nhánh phụ. Chưa
> có implementation evidence.

**Cross-service references:**

- [Order WF-ORDER-004 — Checkout submit saga](../order/workflows.md#wf-order-004--checkout-submit-saga)
- [Promotion Workflows](../promotion/workflows.md) — discovery/calculate, TRY/CONFIRM/CANCEL
- [Inventory Workflows — Whole-order checkout and fulfillment](../inventory/workflows.md#1-whole-order-checkout-and-fulfillment)

## Danh mục luồng nghiệp vụ

| ID | Workflow | Trigger | Tác nhân | Trạng thái |
|---|---|---|---|---|
| `WF-CHK-001` | Checkout Preview (tạo/cập nhật session) | HTTP `POST /v1/checkout/preview` | Buyer | proposed |
| `WF-CHK-002` | Voucher tray và Save & Apply | HTTP (Promotion) + Preview lại | Buyer | proposed |
| `WF-CHK-003` | Checkout Submit | HTTP `POST /v1/checkout/submit` | Buyer | proposed |
| `WF-CHK-004` | Client Retry / Failed Submit Recovery | HTTP Submit với token đã `SUBMITTED` | Buyer (App tự retry) | proposed |
| `WF-CHK-005` | Promotion reserve fail recovery | Lỗi nghiệp vụ từ Order Saga | Checkout + Buyer | proposed |
| `WF-CHK-006` | Degraded pricing fallback | Downstream timeout ở Preview | Checkout + Buyer | proposed |

---

## WF-CHK-001 — Checkout Preview

### Mục tiêu

Tạo bảng báo giá được server công nhận (Quotation), niêm phong bằng
`checkout_token` mới và lưu session Redis 20 phút, mà không khóa kho hay ngân sách
khuyến mãi.

### Actor và participants

Buyer (qua Gateway); Checkout; User (địa chỉ); Cart (snapshot); Product (giá,
trạng thái SKU); Inventory (routing per warehouse, soft allocation); Promotion
(tính discount, validate only).

### Trigger

`POST /v1/checkout/preview` khi user vào màn hình Checkout, đổi địa chỉ, chọn/bỏ
voucher, hoặc sau khi đã đổi giỏ hàng qua Cart API.

### Preconditions

JWT hợp lệ; qua rate limit 10 request/phút; xin được
`SETNX checkout:lock:preview:{user_id}` TTL 5 giây. Bị chặn → `429 Too Many
Requests`. Lock này chặn hacker mở đồng thời 10 luồng Preview với 10 mã voucher
khác nhau gây race condition đè vỡ `pricing_hash` trên session.

### Input

`checkout_token` (`null` = tạo mới; token cũ = cập nhật), `delivery_address_id`,
`items` (từ Cart), `selected_vouchers` (map slot → `promotion_id`, chỉ
`PLATFORM_VOUCHER`). Xem `interfaces.md` §2.1.

### Happy path

1. Guard: rate limit + preview lock.
2. Nếu `checkout_token = null` và đã có session hiện hành → `409` (chống gian
   lận nhiều phiên). Nếu token cũ → cho phép cập nhật.
3. **Wave 1 (song song):** [A] User: GET address list; [B] Cart: `HGETALL
   cart:user:{id}`; [E] Product gRPC batch SKU (validate `is_available` +
   `status`, lấy `current_price`); [D×N] Inventory routing cho từng nhóm kho
   (Inventory là owner routing cho mỗi warehouse). [E] dùng `sku_ids` từ request
   nhưng `quantity` luôn từ [B].
4. Join Wave 1 → dựng `cart_context`. Bất kỳ SKU `status != ACTIVE` → `422` kèm
   danh sách SKU không khả dụng.
5. **Wave 2:** [C] Promotion `POST /internal/v1/promotion-discount-calculations`
   với subtotal/items mới nhất, `payment_method=STRIPE`, Money
   `{amount_minor, currency}`; validate only, không lock budget.
6. **Wave 3 — Calculation Engine (waterfall):**
   ```
   1. cart_subtotal = Σ (quantity × unit_price)        -- unit_price = current_price từ Product
   2. total_product_price = cart_subtotal − voucher_discount
   3. final_amount = total_product_price
   ```
7. Sinh `checkout_token` UUID v4; tính `pricing_hash`, `items_hash`; `HSET`
   session với status `PREVIEW_READY`, TTL 20 phút; populate routing cache.
8. Nhả lock; trả response (xem `interfaces.md` §2.1).

### Optimization Decision Tree (Preview lần sau)

```
Preview Request (đợt sau)
      │
    ├── [Đổi Voucher PLATFORM]
    │     → Chỉ gọi [C] Promotion POST /internal/v1/promotion-discount-calculations
    │     → Cache A (address), B (cart), D×N (routing) vẫn dùng
    │     → Latency target: < 20ms
    │
    ├── [Đổi địa chỉ giao hàng]
    │     → Gọi [A] User Service (validate), invalidate cache D×N
    │     → Gọi lại Inventory D×N cho tất cả warehouses
    │     → Latency target: < 120ms
    │
    └── [Đổi items (qty/add/remove)]
          → Không xử lý mutate trong Checkout Preview
          → Frontend phải gọi Cart API trước, rồi gọi lại Preview với cart snapshot mới
          → Checkout đọc lại [B] Cart và gọi [C]/[D×N] theo dữ liệu Cart mới
```

### State/data changes

Chỉ Redis của Checkout: `checkout:session:{user_id}` (ghi/ghi đè, token mới),
`checkout:routing:{items_hash}:{address_hash}` (populate), rate-limit counter,
preview lock. Không có state thay đổi ở downstream.

### Events emitted

Không có.

### Output

Quotation kèm `checkout_token`; hoặc lỗi `400/409/422/429`; hoặc response
degraded (`DEGRADED_PROMOTION`/`DEGRADED_STOCK`, xem WF-CHK-006).

### Failure paths

| Điểm lỗi | Kết quả | State còn lại |
|---|---|---|
| Rate limit / lock | `429` | Không đổi |
| Session đã tồn tại với `checkout_token = null` | `409` | Session cũ giữ nguyên |
| Payload mutate quantity | `400 INVALID_CHECKOUT_MUTATION_SCOPE` | Không đổi |
| SKU không ACTIVE | `422` + danh sách | Không đổi; user sửa giỏ |
| Cart timeout (50ms) | Lỗi Preview (bắt buộc nhanh vì in-memory) | Không đổi |
| User timeout (500ms) | Dùng địa chỉ cũ từ session nếu có | Session có thể được cập nhật với địa chỉ cũ |
| Inventory timeout (3s) | `DEGRADED_STOCK`; tài liệu cũ ghi “Flat-rate config fallback” | Session mang cờ degraded |
| Promotion timeout (2s) | `DEGRADED_PROMOTION`, không áp voucher, báo user | Session mang cờ degraded |
| Tổng Preview > 5s | Fallback từng phần, không block toàn bộ | Theo từng phần |

### Timeout và retry

| Scope | Timeout | Fallback |
|---|---|---|
| User Service (address) | 500ms | Địa chỉ cũ từ session (nếu có) |
| Cart Service (Redis) | 50ms | Lỗi Preview |
| Inventory routing | 3s | Flat-rate config fallback (theo tài liệu cũ) / `DEGRADED_STOCK` |
| Promotion discount calculation | 2s | Không áp voucher, báo user / `DEGRADED_PROMOTION` |
| Tổng Preview API | 5s | Fallback từng phần |

Checkout không retry Preview thay client; client gọi lại khi cần.

### Idempotency

Preview không idempotent về token (mỗi lần thành công sinh token mới). Việc ghi đè
session là an toàn vì lock 5 giây bảo đảm tuần tự.

### Concurrency/race conditions

Nhiều Preview đồng thời cùng user → lock chặn, chỉ một luồng vào downstream.
Hai tab cùng user chia sẻ một session; tab preview sau ghi đè token của tab
trước.

### Compensation hoặc recovery

Không có side effect downstream nên không cần compensation. Session hỏng → user
Preview lại.

### Metrics và traces

Latency theo wave và theo downstream; tỷ lệ `429/409/422`; tỷ lệ degraded; cache
hit routing. Identifier: `user_id`, `checkout_token`, `items_hash`.

### Implementation evidence

Chưa có.

### Test scenarios

Tạo mới; cập nhật đổi voucher (chỉ gọi Promotion); đổi địa chỉ (gọi lại
Inventory); cart snapshot mới; SKU không ACTIVE; mutate quantity; session đã tồn
tại; rate limit/lock; từng downstream timeout và cờ degraded; hash và token đổi
sau mỗi Preview.

---

## WF-CHK-002 — Voucher tray và Save & Apply

### Mục tiêu

Cho user chọn voucher Sàn theo policy cố định, với ghi ví chỉ qua explicit action.

### Happy path

1. Tab “Voucher của tôi”: gọi `POST /api/v1/me/promotion-voucher-suggestions`;
   chỉ hiển thị mã `SAVED` trong ví.
2. Tab “Khám phá”: gọi `POST /api/v1/promotion-discovery`; hiển thị cả mã trong
   ví và mã public `ACTIVE` chưa lưu. Promotion có thể downgrade mã sang
   `BUDGET_EXHAUSTED` bằng budget guard read-time (Option A) trước khi trả gợi ý.
3. User bấm “Lưu & Chọn”: Frontend gọi
   `PUT /api/v1/me/promotion-vouchers/{promotion_id}`; Promotion ghi ví.
4. Sau khi Save & Apply thành công, Frontend gọi lại `POST /v1/checkout/preview`
   với `selected_vouchers` để nhận token mới và tổng tiền mới (WF-CHK-001, nhánh
   đổi voucher).

### Rules

- Chỉ được chọn mã đã có trong ví và chưa quá `expired_at`; expiry đã được
  Promotion snapshot theo validity mode (`CAMPAIGN_END`, `DURATION_AFTER_SAVE`,
  `FIXED_DATE`), không có default 30 ngày.
- Campaign `[start_time, end_time)` là claim/save window; use window khi submit
  theo `user_coupon_wallets.expired_at`, nên voucher đã lưu có thể còn dùng sau
  natural campaign end.
- Promotion không dùng wallet cache; tray query DB trực tiếp nên PUT xong lần
  Discovery/Suggestion kế tiếp tự thấy mã mới.
- Nếu budget guard timeout/lỗi Redis ngắn hạn, Discovery có thể trả degraded
  (fail-open); Submit Reserve vẫn là nguồn quyết định cuối cùng.
- Rate limit Discovery thuộc Gateway (`promotion-discovery`, 20 req/phút/user).

### Failure paths

Promotion lỗi → tray không hiển thị; không ảnh hưởng session. PUT thất bại →
không Preview lại.

### Test scenarios

Suggestions chỉ SAVED; Discovery gồm public ACTIVE; Save & Apply rồi Preview cho
token mới; mã `BUDGET_EXHAUSTED`; mã quá `expired_at`; Discovery degraded.

---

## WF-CHK-003 — Checkout Submit

### Mục tiêu

Chuyển bảng báo giá đã Preview thành đúng một lệnh tạo đơn tại Order Service,
bảo đảm giá không stale, token one-time-use và không tạo đơn trùng.

### Actor và participants

Buyer; Checkout; Redis; Product (giá hiện tại); Promotion (revision; re-evaluate
khi reserve fail); Order (Temporal Saga: `CreateOrder → ReserveInventory →
ReservePromotion → InitiatePayment → WaitPaymentSignal`).

### Trigger

`POST /v1/checkout/submit` với `{checkout_token, user_ack_degraded_pricing}`.

### Preconditions

Session tồn tại, `PREVIEW_READY`, token khớp; qua rate limit 3 req/phút.

### Happy path

```mermaid
sequenceDiagram
    participant Client
    participant CS as Checkout Service
    participant Redis
    participant PS as Product (gRPC)
    participant OS as Order Service

    Client->>CS: POST /v1/checkout/submit {checkout_token, user_ack_degraded_pricing}
    CS->>Redis: SETNX checkout:lock:submit:{user_id} (TTL 30s)
    alt Lock fail
        CS-->>Client: 423 Locked
    end
    CS->>Redis: HGETALL checkout:session:{user_id}
    alt status == SUBMITTED
        Note over CS,Redis: Rẽ nhánh WF-CHK-004 (Client Retry)
    end
    CS->>CS: Kiểm tra cờ degraded và user_ack_degraded_pricing
    CS->>PS: Batch fetch giá hiện tại (Lightweight Price Staleness Check)
    CS->>CS: hash = SHA-256(UTF-8 RFC 8785 canonical pricing JSON)
    alt hash != pricing_hash
        CS->>Redis: DEL lock
        CS-->>Client: 409 PRICE_CHANGED (session vẫn PREVIEW_READY)
    end
    CS->>Redis: EVAL Lua check-and-mark (PREVIEW_READY -> SUBMITTED)
    CS->>Redis: SET checkout:payload:{user_id}:{token} (TTL 20 phút)
    CS->>OS: POST /internal/v1/checkout-order-submissions + Idempotency-Key: token
    OS-->>CS: {order_id, payment:{attempt, transaction_id, client_secret, publishable_key, expires_at}}
    CS->>Redis: SET checkout:response:{user_id}:{token} = {order_id, attempt, transaction_id} (TTL 20 phút); DEL payload; DEL lock
    CS-->>Client: {order_id, client_secret, publishable_key} (relay, không cache secret)
```

Các bước chi tiết:

1. **Tiếp nhận & khóa:** xin `checkout:lock:submit:{user_id}` TTL 30 giây; fail
   → `423 Locked`.
2. **Kiểm tra sơ bộ & cờ degraded:** đọc session. Nếu `status == SUBMITTED` →
   rẽ WF-CHK-004. Nếu session cảnh báo `DEGRADED_PROMOTION`/`DEGRADED_STOCK` mà
   `user_ack_degraded_pricing != true` → nhả lock, `409
   DEGRADED_PRICING_NOT_CONFIRMED`.
3. **Lightweight Price Staleness Check** (sau lock, **trước** Lua; session vẫn
   `PREVIEW_READY`):
   - Batch fetch giá hiện tại của các SKU trong session qua Product gRPC
     (`original_price`, `promotional_price`, `best_price`); Product tự xử lý cache
     miss nội bộ.
   - Lấy `promotion_campaign_revision` hiện hành (hard check);
     `promotion_budget_version` chỉ telemetry/diagnostic. Revision là
     concurrency/cache fence, không phải mutable definition version; published
     definition thay đổi bằng identity mới nên selection cũ phải evaluate lại.
   - `hash = SHA-256(UTF-8 RFC 8785 canonical pricing JSON)` theo internal
     Order OpenAPI v1;
     so với `pricing_hash` trong session.
   - Khớp → tiếp. Không khớp → nhả lock, `409 PRICE_CHANGED` với
     `action=RECALL_PREVIEW`; **không rollback** vì token vẫn `PREVIEW_READY`,
     đơn giản là không chạy Lua nữa.
   - Chi phí: lookup cache hit ~2–4ms, hash ~0.2ms, tổng < 8ms. Submit bắt buộc
     check lại phiên bản Promotion, không chỉ giá sản phẩm.
4. **Khóa session vĩnh viễn:** Lua check-and-mark `PREVIEW_READY → SUBMITTED`
   (script trong `data.md` §8). Từ đây token “chết”, không rollback.
5. **Lưu payload cứu hộ:** `SET checkout:payload:{user_id}:{token}` TTL 20 phút.
6. **Gọi Order Service:** `POST /internal/v1/checkout-order-submissions`, truyền
   toàn bộ payload đã khóa (bao gồm canonical `allocation_plan_id` và assignments
   lưu trong session từ Preview — client không gửi) với header
   `Idempotency-Key = checkout_token`. Submit path giữ nguyên nguyên tắc read/validate/reserve;
   không auto-save voucher. Nếu pre-payment timeout/cancel,
   `RollbackPromotionActivity` bên Order chỉ nhả reservation runtime
   (`reserved_*`), không đổi trạng thái ví voucher.
7. **Xử lý kết quả:**
   - **Kịch bản A — Success:** Order trả `{order_id, payment:{payment_attempt_number,
     payment_transaction_id, client_secret, publishable_key, expires_at}}`. Cache
     **chỉ** `{order_id, payment_attempt_number, payment_transaction_id}`, `DEL`
     payload, `DEL` lock; relay client data cho client trong cùng response, không
     ghi secret vào Redis ([ADR-010](./decisions/ADR-010-relay-payment-client-data-no-secret-cache.md)).
     Frontend dùng Stripe Elements.
   - **Kịch bản B — Business conflict (ReservePromotion all-or-nothing fail):**
     xem WF-CHK-005. Cache 409 theo token, `DEL` lock, token vẫn `SUBMITTED`.
   - **Kịch bản C — Rớt mạng/timeout:** retry nội bộ tối đa 3 lần; cả 3 fail →
     `503 Service Unavailable`, **không** rollback token, giữ payload; App tự
     retry → WF-CHK-004.

### State/data changes

Redis: session `status = SUBMITTED`; `checkout:payload` (tạo; xóa khi success);
`checkout:response` (tạo khi success — chỉ định danh — hoặc conflict); submit lock
(tạo/xóa). Không có secret Stripe trong Redis. Order
Service tạo order/workflow (thuộc Order).

### Events emitted

Không có từ Checkout. Order/Payment phát event của họ.

### Output

`{order_id, client_secret, publishable_key}` (secret relay từ Order, `null` nếu
`usable=false`); hoặc `409 PRICE_CHANGED`; hoặc `409 PROMOTION_RECALCULATION_NEEDED`;
hoặc `503`.

### Failure paths

| Điểm lỗi | Kết quả | State còn lại |
|---|---|---|
| Không xin được lock | `423` | Không đổi |
| Session hết TTL | `404` | Không có session |
| Thiếu ack degraded | `409 DEGRADED_PRICING_NOT_CONFIRMED`, nhả lock | `PREVIEW_READY` |
| Product gRPC lỗi khi hash check | Lỗi hạ tầng, nhả lock (không chạy Lua) | `PREVIEW_READY` |
| Hash lệch | `409 PRICE_CHANGED`, nhả lock | `PREVIEW_READY` |
| Lua `-1` | `409` token không hợp lệ | Không đổi |
| Lua `-2` | WF-CHK-004 | `SUBMITTED` |
| Order conflict nghiệp vụ | WF-CHK-005 | `SUBMITTED`, response cache có 409 |
| Order timeout ×3 | `503` | `SUBMITTED`, payload còn, response cache trống |
| Redis Lua fail | Lock là fallback layer chặn duplicate | Phụ thuộc thời điểm |

### Timeout và retry

Lock 30 giây; gọi Order retry nội bộ 3 lần (backoff chưa chốt); Product gRPC
deadline chưa chốt. Client được phép retry sau `503`/`423`; không retry sau
`409`/`404`/`410`.

### Idempotency

Key là `checkout_token`. Kết quả lưu ở `checkout:response:{user_id}:{token}` 20
phút; replay trả đúng response cũ (success hoặc conflict). Order Service dedup
cùng token bằng Temporal WorkflowID, Redis fast path và FSM guard, nên Checkout
retry bao nhiêu lần Order cũng chỉ tạo đúng một order logic.

### Concurrency/race conditions

Double-click / 2 tab / slow-network retry: lock + Lua (belt and suspenders, xem
`architecture.md` §6). Lock bao bọc cả hash check để không có request chèn vào
khi đang check giá. Exhausted budget vẫn có thể xảy ra trong race ngắn giữa
Preview và Submit dù Discovery có budget guard, vì vậy Reserve là quyết định cuối.

### Compensation hoặc recovery

Checkout không compensate; recovery bằng payload cache (WF-CHK-004). Compensation
tồn kho/budget thuộc Saga của Order.

### Metrics và traces

Tỷ lệ `PRICE_CHANGED`, `PROMOTION_RECALCULATION_NEEDED`, `503`, `423`; latency
hash check (< 8ms mục tiêu); Order call latency/retry count; identifier
`checkout_token`, `order_id`, `user_id`.

### Implementation evidence

Chưa có.

### Test scenarios

Success; hash lệch trước Lua (session còn `PREVIEW_READY`); thiếu ack degraded;
Lua `-1`/`-2`; double submit đồng thời chỉ tạo một order; Order timeout ×3 →
`503` rồi recovery; Order conflict → 409 cache; lock hết TTL giữa chừng; Redis Lua
fail nhưng lock vẫn chặn; `payment_method` trong body bị bỏ qua; amount trong
body bị bỏ qua.

---

## WF-CHK-004 — Client Retry / Failed Submit Recovery

### Mục tiêu

Xử lý trường hợp Lua đã mark `SUBMITTED` nhưng lần gọi Order đầu tiên fail
(timeout/down) — user bị stuck: session đã dùng, order chưa tạo, response cache
trống. Đồng thời trả idempotent response khi client retry sau khi Order đã xong.

### Trigger

Client (App tự động sau `503`, hoặc user bấm lại) gọi `POST /v1/checkout/submit`
với token đã `SUBMITTED`.

### Happy path

```
Client retry POST /checkout/submit {token}
  │
  ├─ Lua Script trả -2 (ALREADY_SUBMITTED)  [hoặc bước 2 thấy status == SUBMITTED]
  │
  ├─ Check checkout:response:{user_id}:{token} ?
  │   ├─ CÓ, conflict → Trả lại 409 cũ (idempotent)
  │   ├─ CÓ, success  → GET /internal/v1/checkout-order-submissions/{token} (Order)
  │   │                 → payment.usable=true  → trả {order_id, client_secret, publishable_key}
  │   │                 → payment.usable=false → trả {order_id, client_secret: null} (đơn đã thanh toán/hết hạn)
  │   └─ KHÔNG → Lần đầu fail, chưa có response cache
  │
  └─ Check checkout:payload:{user_id}:{token} ?
      ├─ CÓ → Retry gọi Order Service (dùng payload từ Redis)
      │   ├─ Thành công → Cache {order_id, attempt, transaction_id} + DEL payload → Trả client (secret relay)
      │   └─ Thất bại → Trả 503 (client có thể retry tiếp)
      └─ KHÔNG (TTL hết) → Trả 410 Gone
          → Client hiển thị: "Phiên checkout đã hết hạn, vui lòng thử lại"
          → User quay lại Preview → Tạo session mới
```

Lý do có hai key: `checkout:response` giải quyết cả nhánh success (response đầy
đủ, tránh user submit lại tạo đơn mới) lẫn nhánh conflict (kết quả re-evaluate
đã cache, tránh tính lại); `checkout:payload` giải quyết việc session đã
`SUBMITTED` nhưng Order chưa nhận được — payload sẵn sàng để gửi lại mà không bị
ảnh hưởng bởi trạng thái đã chuyển trên session.

### Lifecycle của `checkout:payload`

| Sự kiện | Hành động |
|---|---|
| Submit bắt đầu (sau Lua OK) | `SET checkout:payload:{user_id}:{token}` TTL 20 phút |
| Submit thành công | `DEL checkout:payload` |
| Submit fail (Order lỗi) | Giữ nguyên, đợi client retry hoặc TTL expire |
| Client retry (token ALREADY_SUBMITTED) | Check payload còn? → retry Order |
| Payload TTL hết | `410 Gone` — buộc user Preview lại |

### Chống duplicate order

Order nhận request với `idempotency_key = checkout_token`. Dù Checkout retry bao
nhiêu lần, Order chỉ tạo đúng một order logic cho mỗi token — bảo đảm bởi Temporal
WorkflowID, Redis fast path và FSM guard phía Order (xem
[Order Data — Redis và idempotency](../order/data.md#7-redis-outbox-và-idempotency-storage)).

### Failure paths

Order tiếp tục fail → `503` lặp lại cho tới khi payload hết TTL (20 phút) →
`410 Gone`, user quay lại Preview tạo session mới. Nhánh này chạy dưới submit lock
nên không có hai luồng recovery đồng thời cho cùng user.

### Idempotency

Toàn bộ nhánh này là idempotent theo token; vẫn chạy dưới submit lock 30 giây.

### Test scenarios

Retry sau success trả cùng `order_id`; retry sau conflict trả cùng 409; retry khi
Order down → gọi lại từ payload; payload hết TTL → `410`; Order trả order cũ khi
nhận lại cùng token.

---

## WF-CHK-005 — Promotion reserve fail recovery

### Mục tiêu

Khi `ReservePromotionActivity` trong Saga fail all-or-nothing, trả cho user một
đề xuất minh bạch (mã nào bị loại, tổng tiền mới) mà không làm phép trừ cục bộ và
không tái sử dụng token.

### Trigger

Order trả lỗi nghiệp vụ có cấu trúc từ ReservePromotion trong WF-CHK-003 bước 7.

### Happy path (4 bước bắt buộc)

- (a) Nhận `failed_promotion_id` + `failed_reason` + `reserve_mode` từ lỗi có
  cấu trúc.
- (b) Loại `failed_promotion_id` khỏi tập mã đã chọn và gọi lại Promotion
  `POST /internal/v1/promotion-discount-calculations` với cart context hiện tại
  + tập mã còn lại.
- (c) Dựng `dropped_promotions[]` (chỉ gồm mã bị loại, kèm `reason`,
  `user_message`) và `proposal_checkout_data` (`new_total_amount`,
  `surviving_promotions[]`) từ kết quả re-evaluation. Mã bị loại có thể kéo theo
  mã khác không còn đủ điều kiện (`CONDITIONS_NOT_MET`) — đây là hệ quả dây
  chuyền phải hiển thị đủ theo thứ tự.
- (d) Trả `409 PROMOTION_RECALCULATION_NEEDED` + `action=RECALL_PREVIEW`; cache
  phản hồi theo token để retry cùng token trả kết quả nhất quán. Session giữ
  nguyên `SUBMITTED` (token đã dùng), không rollback.

### Phía client

1. Frontend hiển thị popup minh bạch từ `dropped_promotions[]`, render đủ toàn bộ
   lý do theo đúng thứ tự trả về.
2. Khi user bấm “Đồng ý đặt hàng”, Frontend **bắt buộc** gọi lại
   `POST /v1/checkout/preview` với `surviving_promotions[]` để lấy
   `checkout_token` mới.
3. Chỉ sau khi có token mới, Frontend mới được gọi `POST /v1/checkout/submit`
   lần kế tiếp.

### State/data changes

`checkout:response:{user_id}:{token}` chứa 409; session `SUBMITTED`. Order Saga
đã compensate phần đã reserve (thuộc Order/Promotion).

### Failure paths

Promotion re-evaluate timeout → Checkout không được tự trừ; trả lỗi hạ tầng và để
client retry (token `SUBMITTED`, chưa có response cache, payload vẫn còn → retry
sẽ gọi lại Order, Order có thể lại trả reserve fail → lặp lại bước (a)–(d)).

### Test scenarios

Reserve fail một mã; hệ quả dây chuyền hai mã; retry cùng token trả cùng 409;
Preview với `surviving_promotions[]` cho token mới; Submit lại token cũ bị từ chối.

---

## WF-CHK-006 — Degraded pricing fallback

### Mục tiêu

Khi mạng nội bộ gặp sự cố (network partition), Preview vẫn trả được báo giá tạm
thời nhưng Submit phải có xác nhận rõ ràng của user.

### Trigger

Circuit breaker / timeout tại Preview: Promotion (2s) hoặc Inventory routing (3s).

### Happy path

1. Promotion timeout → Preview trả trạng thái `DEGRADED_PROMOTION`; UI bắt buộc
   hiển thị hộp xác nhận “Thanh toán không áp mã giảm giá tạm thời” trước khi cho
   Submit.
2. Inventory routing timeout → Preview trả `DEGRADED_STOCK`; UI bắt buộc hiển thị
   hộp xác nhận “Thanh toán với giá tạm thời” trước khi cho Submit.
3. Session mang cờ degraded.
4. Submit ở chế độ degraded phải mang `user_ack_degraded_pricing = true`; thiếu
   cờ → `409 DEGRADED_PRICING_NOT_CONFIRMED` (nhả lock, session không đổi).
5. Payload gửi sang Order chứa `user_ack_degraded_pricing`; Order chấp nhận
   `promotions` rỗng khi cờ này `true`.

### Failure paths

User không ack → không thể Submit; user Preview lại khi downstream hồi phục để bỏ
cờ degraded.

### Test scenarios

Promotion timeout → cờ + ack bắt buộc; Inventory timeout → cờ + ack bắt buộc;
Submit không ack → 409; Submit có ack → payload mang cờ; Preview lại sau hồi phục
xóa cờ.
