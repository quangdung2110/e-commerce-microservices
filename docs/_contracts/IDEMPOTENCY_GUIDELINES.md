# Idempotency Guidelines (SSOT)

**Chuẩn chung về idempotency cho mọi service trong hệ thống.** Mọi service doc khác khi nhắc đến idempotency phải tham chiếu file này.

> Chi tiết key-by-key (format, TTL, owner, layer 2 table) xem tại [Service Contract Matrix §0.4](CONTRACT_MATRIX.md#04-idempotency-convention).

---

## 1. Triết lý chung

### 1.1. Tại sao phải có idempotency 2 lớp?

Hệ thống E-Commerce có 3 nguồn lặp event cần chống:

1. **Client double-click / retry**: User bấm nút "Thanh toán" 2 lần liên tiếp hoặc App retry khi mất mạng → backend nhận 2 request giống hệt.
2. **Kafka At-Least-Once Delivery**: Broker phát lại message khi restart/rebalance → consumer nhận 2 lần cùng 1 event.
3. **Provider Webhook retry**: Cổng thanh toán retry nếu backend trả > 5s hoặc response không rõ ràng.

Vì 2 nguồn trên dùng **2 cơ chế khác nhau** (HTTP retry vs Kafka offset retry), hệ thống cần **2 lớp idempotency độc lập**:

- **Layer 1 (fast path)**: Redis `SETNX` — chặn trong microseconds, ngăn 99% duplicate.
- **Layer 2 (durable)**: PostgreSQL Unique Constraint / `*_idempotency` table — chốt quyết định cuối cùng, bảo vệ khi Redis miss/down/restart.

### 1.2. Nguyên tắc bất biến

1. **Không được tự sinh `event_id` cho nhánh sync.** Sync API qua Temporal Activity phải dùng đúng `idempotency_key` do Order Service truyền vào làm `*_idempotency.event_id`. Chỉ nhánh async Kafka mới tự sinh `event_id`.
2. **Layer 2 quyết định cuối cùng.** Nếu Redis SETNX miss do restart/eviction, PostgreSQL Unique Constraint vẫn chặn được duplicate. Nếu 2 layer conflict (Redis nói "chưa xử lý" nhưng DB đã có record), phải coi như đã xử lý, **không rollback DB**.
3. **TTL Redis > thời gian xử lý tối đa.** Không được set TTL ngắn hơn retry window của client hoặc Kafka consumer lag window.
4. **Mọi event Kafka đều có `event_id` unique** — dùng làm khóa delivery-level (Layer 1 ngoài Redis), `aggregate_id + action_scope_key` dùng làm khóa business-level (Layer 2 trong DB).

---

## 2. Pattern theo use case

### 2.1. HTTP API nhận từ Client (User-facing)

| Pattern | Owner | Example |
|---|---|---|
| Header `Idempotency-Key` bắt buộc, lưu `(key, action)` trong DB | Order `POST /v1/orders/{id}/cancel` | `order_request_idempotency` |
| Response cache theo `(user_id, token)` — TTL 20 phút | Checkout `POST /v1/checkout/submit` | `checkout:response:{user_id}:{token}` |
| Payload cache để retry khi mất mạng — TTL 20 phút | Checkout retry recovery | `checkout:payload:{user_id}:{token}` |

### 2.2. Sync API qua Temporal Activity (Service-to-Service)

| Pattern | Owner | Example |
|---|---|---|
| Order sinh `idempotency_key` chuẩn hoá, gọi sync sang service khác | Order → Promotion | `REDEEM_FINAL:{order_id}:{promotion_id}` |
| Service nhận dùng đúng key làm `*_idempotency.event_id` PK | Promotion `promotion_event_idempotency` | `event_id = "REDEEM_FINAL:10001:55"` |
| Service KHÔNG tự sinh `event_id` mới cho nhánh sync | (nguyên tắc) | — |

### 2.3. Kafka consumer (Async)

| Pattern | Owner | Example |
|---|---|---|
| Redis SETNX với key format `{service}:{action}:{scope}` TTL 30-60 phút | Order payment consumer | `payment:signal:{order_id}:{txn_id}:{event_type}` |
| PostgreSQL Unique Constraint `(aggregate_id, action_type, action_scope_key)` | Order `order_event_idempotency` | `(order_id, action_type='PAYMENT_SUCCESS_SIGNAL', action_scope_key='{txn_id}')` |
| Idempotency guard ở DB là quyết định cuối cùng; SETNX chỉ là fast path | (nguyên tắc) | — |

### 2.4. Webhook Gateway (External → Internal)

| Pattern | Owner | Example |
|---|---|---|
| Trả 2xx < 500ms ngay lập tức, dump raw webhook vào Kafka | Payment | `t.payment.webhooks.raw` |
| Webhook Worker consume, verify signature, idempotency check theo `provider_event_fingerprint` (ưu tiên `provider_webhook_id`) | Payment | `webhook_logs` UNIQUE `(provider, provider_transaction_id, event_type)` |
| Cross-check với `webhook_logs` DB constraint | Payment | `UNIQUE(provider, provider_transaction_id, event_type)` |

### 2.5. Reserve / Deduct / Mutate nóng (Redis Lua)

| Pattern | Owner | Example |
|---|---|---|
| Atomic mutation + idempotency key trong cùng Lua script | Inventory Reserve, Promotion Reserve | `inventory:idemp:reserve:{sku:wh}:order:{order_id}` |
| Trả code `2` (idempotent hit) — KHÔNG phát event mới | Inventory Redis Design §3 | — |
| Trả code `1` (applied) — mới phát event | Inventory Redis Design §3 | — |

---

## 3. Quy ước tên key (canonical)

### 3.1. Format chuẩn

```
{service}:{action}:{scope_key1}:{scope_key2}:...
```

- `service`: tên service ngắn (`checkout`, `order`, `payment`, `inventory`, `promotion`)
- `action`: hành động (`lock`, `submit`, `signal`, `webhook`, `idempotency`, `delayed`)
- `scope_key`: identifier nghiệp vụ (order_id, sku_id:wh, refund_transaction_id, ...)

### 3.2. Bảng key chuẩn

| Loại | Format | Owner | TTL |
|---|---|---|---|
| Checkout rate limit | `checkout:ratelimit:{preview\|submit}:{user_id}` | Checkout | 60s |
| Checkout lock | `checkout:lock:{preview\|submit}:{user_id}` | Checkout | 5-30s |
| Checkout response cache | `checkout:response:{user_id}:{token}` | Checkout | 20 phút |
| Checkout payload cache | `checkout:payload:{user_id}:{token}` | Checkout | 20 phút |
| Payment initiate | `payment:initiate:{order_id}` | Payment | 15 phút + 60s |
| Payment webhook dedup | `payment:webhook:{provider}:{provider_txn_id}:{event_type}` | Payment | 7 ngày |
| Inventory reserve | `inventory:idemp:reserve:{sku_id:warehouse_id}:order:{order_id}` | Inventory | 30 ngày |
| Inventory release | `inventory:idemp:release:{sku_id:warehouse_id}:order:{order_id}` | Inventory | 30 ngày |
| Inventory deduct | `inventory:idemp:deduct:{sku_id:warehouse_id}:order:{order_id}` | Inventory | 30 ngày |
| Order sync API | `order_request_idempotency.idempotency_key` | Order | theo `expires_at` |
| Order async/signal | `order_event_idempotency` `(order_id, action_type, action_scope_key)` | Order | 45 ngày |
| Promotion sync | `promo:lock:{redeem\|cancel\|refund}:{promotion_id}:{order_id}` | Promotion | 10s |
| Promotion durable | `promotion_event_idempotency.event_id` (PK) | Promotion | 45 ngày |
| Order → Promotion idempotency_key | `REDEEM_FINAL:{order_id}:{promotion_id}` / `CANCEL_FINAL:{order_id}:{promotion_id}` / `REFUND_FULL:{order_id}:{promotion_id}:{refund_request_id}` / `REFUND_PARTIAL:{order_id}:{promotion_id}:{refund_request_id}` | Order (sinh) → Promotion (lưu) | theo Retention |

---

## 4. Cảnh báo & Anti-pattern

### 4.1. ❌ Anti-pattern: Tự sinh `event_id` ở nhánh sync

```go
// ❌ SAI — service tự sinh event_id cho nhánh sync
func (s *Promotion) ConfirmPromotion(...) error {
    eventID := uuid.New().String()  // ❌ Order đã có idempotency_key rồi
    s.idempotencyTable.Insert(eventID, ...)
}

// ✅ ĐÚNG — dùng idempotency_key từ Order
func (s *Promotion) ConfirmPromotion(idempotencyKey string, ...) error {
    s.idempotencyTable.Insert(idempotencyKey, ...)  // idempotency_key = "REDEEM_FINAL:10001:55"
}
```

### 4.2. ❌ Anti-pattern: TTL quá ngắn

```go
// ❌ SAI — TTL 10s, nhưng Kafka consumer lag có thể 5 phút
redis.SETNX(key, "1", 10*time.Second)

// ✅ ĐÚNG — TTL đủ bao quanh retry window
redis.SETNX(key, "1", 30*time.Minute)
```

### 4.3. ❌ Anti-pattern: Chỉ dùng 1 lớp

```sql
-- ❌ SAI — chỉ có DB constraint, không có Redis fast path
INSERT INTO promotion_event_idempotency (event_id, ...) VALUES (...);
-- Mỗi duplicate request phải round-trip DB
```

```sql
-- ✅ ĐÚNG — 2 lớp
-- Layer 1: Redis SETNX (μs)
-- Layer 2: DB unique constraint (chốt cuối cùng)
```

### 4.4. ❌ Anti-pattern: Idempotency key quá rộng

```go
// ❌ SAI — dùng chung 1 key cho nhiều promotion khác nhau
idempotencyKey := fmt.Sprintf("CONFIRM:%s", orderID)
```

```go
// ✅ ĐÚNG — key theo từng promotion_id
idempotencyKey := fmt.Sprintf("REDEEM_FINAL:%s:%s", orderID, promotionID)
```

### 4.5. ❌ Anti-pattern: Bỏ qua confirm từ DB

```go
// ❌ SAI — Redis SETNX success, nhưng DB chưa commit → crash → request retry → DB INSERT trùng
redis.SETNX(key, "1", ttl)
db.Insert(...)  // crash ở đây
```

```go
// ✅ ĐÚNG — DB commit trước, Redis SETNX chỉ là fast path
db.Insert(...)  // commit trước, nếu fail thì return error, không SETNX
redis.SETNX(key, "1", ttl)  // success marker
```

---

## 5. Retention & Cleanup

| Service | Table | Retention | Job |
|---|---|---|---|
| Order | `order_request_idempotency` | theo `expires_at` field | Cleanup CronJob hàng ngày |
| Order | `order_event_idempotency` | 45 ngày | Cleanup CronJob hàng ngày |
| Promotion | `promotion_event_idempotency` | ≥ `refund_window + 15 days` (khuyến nghị 45 ngày) | Cleanup CronJob hàng ngày |
| Payment | `webhook_logs` | ≥ refund window | Cleanup CronJob hàng ngày |

> Lý do: phải giữ idempotency record ≥ refund window + buffer để không mở cửa duplicate khi refund webhook đến trễ.

---

## 6. Khi nào dùng `event_id` vs `idempotency_key`?

| Use case | Khóa | Ghi chú |
|---|---|---|
| Kafka async inbound (`ORDER_PAID` → Promotion) | `event_id` từ envelope | Promotion lưu `event_id` nguyên bản vào `promotion_event_idempotency` |
| Sync Temporal Activity (`/confirm`, `/cancel`) | `idempotency_key` từ Order | Promotion lưu `idempotency_key` vào DB; KHÔNG tự sinh `event_id` mới |
| Replay async (worker route theo business_id) | `event_id` (delivery) + `business_id` (logic) | Consumer phải dedup 2 tầng: event_id + business discriminator |

---

## 7. Cross-service consistency

Khi 1 service emit event mà nhiều service consume:

1. **Producer ghi `event_id` unique** ở envelope (KHÔNG tự tái sử dụng).
2. **Tất cả consumers dùng cùng `event_id` làm dedup key** ở Layer 2 (DB unique constraint).
3. **Nếu 1 consumer fail ghi idempotency**, retry phải dùng đúng `event_id` cũ, không tự sinh mới.
4. **Nếu 2 consumers cùng xử lý event**, họ sẽ race-condition ở Redis SETNX (chỉ 1 thắng) nhưng vẫn cần DB constraint để chống 2 đều INSERT trùng.

---

## 8. Kết nối với Temporal Saga

Trong Saga, mỗi Activity có retry built-in. Idempotency contract với Saga:

- **Activity idempotency**: Mỗi Activity phải ghi idempotency ngay lần đầu commit DB, **trước khi** gọi Activity tiếp theo.
- **Saga compensation**: Compensation cũng phải idempotent — ghi cùng `idempotency_key` với success path để retry compensation không double-charge.
- **Saga signal idempotency**: Temporal signal có thể đến nhiều lần do retry; `*_idempotency` table chặn duplicate signal processing.

---

## 9. Tóm tắt quy tắc vàng

1. **2 lớp**: Redis SETNX + DB Unique Constraint. Không chỉ 1.
2. **TTL đủ rộng**: ≥ retry window tối đa.
3. **Idempotency key đủ hẹp**: theo từng entity nghiệp vụ (không theo order_id chung nếu có nhiều sub-entity).
4. **Sync dùng `idempotency_key` do caller sinh**: Service nhận KHÔNG tự sinh event_id mới.
5. **Async dùng `event_id` từ envelope**: Consumer lưu nguyên bản.
6. **DB quyết định cuối cùng**: Nếu Redis layer 1 miss, layer 2 vẫn chặn được.
7. **Retention ≥ refund window + 15 ngày**: Không được xóa idempotency quá sớm.

Mọi thay đổi rule mới phải cập nhật file này + update [CONTRACT_MATRIX.md §0.4](CONTRACT_MATRIX.md#04-idempotency-convention).
