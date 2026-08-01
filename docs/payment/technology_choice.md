# Payment Service - Lựa Chọn Ngôn Ngữ Lập Trình

## Quyết Định: ✅ Java (Spring Boot)

---

## 1. Tóm Tắt Đặc Tính Service

| Đặc tính | Mô tả |
|:---|:---|
| **Kiến trúc** | PostgreSQL-First — Xử lý tiền thật, ACID tối thượng |
| **State Machine** | Dual FSM: Payment Transaction FSM (online-only qua Stripe) + Refund Transaction FSM |
| **Provider Integration** | **Stripe SDK trực tiếp** (`com.stripe:stripe-java`) — bỏ Provider Adapter Pattern vì chỉ 1 provider duy nhất (Phase 18) |
| **Security** | Stripe-Signature verification (HMAC-SHA256 với `whsec_...`), Webhook chống giả mạo |
| **Webhook Processing** | Async: Raw webhook → Kafka → Worker verify Stripe-Signature → FSM update → Outbox |
| **Concurrency** | Optimistic Locking (`version`), Distributed Lock (Redis), Webhook race condition |
| **Reconciliation** | Cronjob định kỳ (hourly) dùng Stripe API đối soát giữa DB nội bộ và Stripe |

---

## 2. Lý Do Chọn Java (Spring Boot)

### 2.1. Single-Provider Simplicity (Phase 18)

Hệ thống chỉ dùng **Stripe** làm payment provider duy nhất. Bỏ hoàn toàn Provider Adapter / Factory Pattern.

**Cách tổ chức code:**
- Toàn bộ Stripe-specific code encapsulate trong package `com.payment.stripe`:
  - `PaymentIntentClient`: wrap `PaymentIntent.create()`, `PaymentIntent.retrieve()`.
  - `RefundClient`: wrap `Refund.create()`, `Refund.retrieve()`.
  - `WebhookVerifier`: wrap `Webhook.constructEvent()` + signature validation.
- Application code (UseCase layer) gọi qua interface `PaymentProviderPort` (chỉ 1 implementation = `StripeAdapter`).
- Stripe SDK singleton: 1 `StripeClient` instance với API key từ Vault/Secret Manager, dùng chung cho toàn service.

**Trade-off chấp nhận:**
- Vendor lock-in với Stripe.
- Đổi provider khác trong tương lai phải refactor code Stripe-specific.
- **Compensation:** Encapsulation trong `com.payment.stripe` package giới hạn blast radius; interface `PaymentProviderPort` cho phép viết adapter mới khi cần.

### 2.2. FSM Phức Tạp — Tiền Bạc Không Được Sai
Payment FSM có nhiều transitions + edge cases phức tạp (Webhook race condition, timeout, retry):
- **Spring State Machine** declarative config cho Payment Transaction FSM + Refund Transaction FSM.
- Guard conditions: Webhook signature verification + amount matching + transaction_ref matching.
- Audit Trail (`payment_status_history`) ghi log mỗi transition — Spring Event Listener pattern.
- Mô hình online-only qua Stripe (không COD) giúp FSM gọn hơn nhưng vẫn cần guard chặt cho replay/out-of-order webhooks (Stripe retry 3-5 lần).

### 2.3. Stripe Webhook Security (Phase 18)
Stripe webhook cần verify **`Stripe-Signature` header** (HMAC-SHA256 với secret key `whsec_...`):
- Java `javax.crypto.Mac` mature, đã qua audit bảm mật.
- **Stripe Java SDK cung cấp sẵn** `Webhook.constructEvent(payload, signatureHeader, secret)` helper, sử dụng luôn để đảm bảo đúng chuẩn Stripe (kèm timestamp window 300s chống replay).
- Spring Security cung cấp utility cho HMAC computation nếu cần custom.

### 2.4. Transactional Integrity — Xử Lý Tiền Thật
Payment là service **nhạy cảm nhất** (money handling):
- `@Transactional` gom INSERT transaction + INSERT outbox + INSERT status_history.
- Optimistic Locking `@Version` ngăn chặn duplicate webhook processing.
- Refund validation (tổng refund ≤ paid_amount) cần query + check + update atomic.

### 2.5. Reconciliation — Scheduled Jobs
Cronjob định kỳ (hourly) đối soát cần:
- Query toàn bộ transactions trong kỳ, gọi Stripe API `PaymentIntent.list` / `BalanceTransaction.list` để verify từng transaction.
- So khớp amount, status giữa DB nội bộ và Stripe.
- Spring `@Scheduled` + Spring Retry cho retry logic khi gọi Stripe API.

### 2.6. Chiến Lược Lock & Fallback (Redis vs Optimistic DB)
Để xử lý Race Condition và Idempotency mạnh mẽ, hệ thống áp dụng chiến lược kết hợp:
*   **Optimistic Locking (Cột `version` rdbms):** Được sử dụng làm default guard cho **tất cả** state transition để bảo vệ dữ liệu (VD: Webhook Stripe ghi đè nhau). Không tốn overhead gọi sang Redis.
*   **Distributed Lock (Redis Redisson):** Được ưu tiên khi cần bảo vệ các tác vụ có side-effect bên ngoài mà không thể rollback qua DB (ví dụ: Gọi Stripe API để tạo PaymentIntent/Refund, hoặc Cronjob quét transaction).
    *   *Naming convention:* `lock:payment:{action}:{txn_id}` (VD: `lock:payment:refund:txn_123`, `lock:payment:initiate:order_10001`).
    *   *TTL:* 15s - 30s. Tránh lock bị treo vĩnh viễn khi worker crash. 
*   **Redis Degradation / Fallback:** Trong trường hợp Redis sập:
    *   Các API query Read/Reconciliation không bị ảnh hưởng.
    *   **Idempotency API (Initiate Payment / Refund):** Sẽ fallback xuống việc cố gắng insert check unique constraint ở CSDL (bảng `payment_idempotency` và `refund_transactions`).
    *   **Worker:** Nếu không lấy được lock, reject operation và đẩy xuống retry cycle / Dead Letter Queue để bảo vệ tính toàn vẹn thay vì ignore lock.

---

## 3. Tại Sao Không Chọn Golang (Gin)

| Hạn chế | Ảnh hưởng |
|---|---|
| **Thiếu FSM framework** | Phải implement 2 FSM phức tạp từ đầu + tất cả Guard conditions |
| **Provider SDK** | Stripe có cả Java lẫn Go SDK, nhưng Java SDK mature hơn cho finance domain (long-term support, community, examples) |
| **Transaction boilerplate** | Manual tx management cho money-handling = rủi ro cao |

---

## 4. Tech Stack Chi Tiết

| Component | Công nghệ |
|:---|:---|
| **Framework** | Spring Boot 3.x + Spring Web |
| **FSM** | Spring State Machine (Payment FSM + Refund FSM) |
| **Database** | PostgreSQL + Spring Data JPA |
| **Kafka** | Spring Kafka (Webhook raw dump, Outbox relay, Event production) |
| **Redis** | Lettuce (Idempotency, Distributed Lock) |
| **Security** | Java Crypto (`javax.crypto`) — HMAC-SHA256/512 |
| **HTTP Client** | Spring WebClient / OkHttp (gọi Stripe API) |
| **Scheduling** | Spring `@Scheduled` (Reconciliation, Expiration Scanner, Refund Poller) |
| **Build** | Maven + JDK 21 |

---

## 5. Quyết Định Boundary: Refund trong Payment hay tách Service?

### Quyết định hiện tại

Giữ Refund trong Payment Service (modular boundary), **chưa tách thành microservice độc lập**.

### Lý do

1. Refund ratio hiện thấp, chưa đạt ngưỡng cần scale độc lập.
2. Chưa có workflow dispute/approval nhiều cấp buộc phải tách domain vận hành.
3. Tránh overhead phân tán sớm: lock coordination, idempotency chéo service, reconciliation chéo service.

### Chuẩn tham chiếu quốc tế

Các hệ thống lớn (Stripe, Adyen, PayPal, Square, Amazon Pay) đều tuân thủ:

*   Refund lifecycle bất đồng bộ.
*   Idempotency bắt buộc.
*   Webhook retry/duplicate/out-of-order là bình thường.
*   Reconciliation là lớp bắt buộc.

Thiết kế hiện tại của Payment Service bám sát các nguyên tắc trên.

### Tài liệu chuẩn hóa quyết định

Xem chi tiết tại: [Refund Boundary Decision (ADR)](refund_boundary_decision.md)
