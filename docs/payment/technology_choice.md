# Payment Service - Lựa Chọn Ngôn Ngữ Lập Trình

## Quyết Định: ✅ Java (Spring Boot)

---

## 1. Tóm Tắt Đặc Tính Service

| Đặc tính | Mô tả |
|:---|:---|
| **Kiến trúc** | PostgreSQL-First — Xử lý tiền thật, ACID tối thượng |
| **State Machine** | Dual FSM: Payment Transaction FSM (online-only) + Refund Transaction FSM |
| **Provider Integration** | Adapter Pattern cho nhiều cổng thanh toán (VNPay, MoMo, ZaloPay) — Factory Pattern |
| **Security** | HMAC-SHA256/SHA512 signature verification, Webhook chống giả mạo |
| **Webhook Processing** | Async: Raw webhook → Kafka → Worker verify → FSM update → Outbox |
| **Concurrency** | Optimistic Locking (`version`), Distributed Lock (Redis), Webhook race condition |
| **Reconciliation** | Daily CronJob đối soát giữa DB nội bộ và cổng thanh toán |

---

## 2. Lý Do Chọn Java (Spring Boot)

### 2.1. Provider Adapter Pattern — OOP Design Patterns
Payment Service phải tích hợp **nhiều cổng thanh toán** (VNPay, MoMo, ZaloPay) với API format khác nhau:
- **Strategy + Factory Pattern**: `PaymentProviderAdapter` interface → `VNPayAdapter`, `MoMoAdapter`, `ZaloPayAdapter` implements.
- `PaymentProviderFactory.getAdapter(method)` trả về adapter phù hợp.
- Java OOP + Spring DI (`@Service`, `@Autowired`, `@Qualifier`) tạo clean architecture rõ ràng.
- Thêm cổng mới = thêm 1 class implement interface + config — không sửa core logic.

### 2.2. FSM Phức Tạp — Tiền Bạc Không Được Sai
Payment FSM có nhiều transitions + edge cases phức tạp (Webhook race condition, timeout, retry):
- **Spring State Machine** declarative config cho Payment Transaction FSM + Refund Transaction FSM.
- Guard conditions: Webhook signature verification + amount matching + transaction_ref matching.
- Audit Trail (`payment_status_history`) ghi log mỗi transition — Spring Event Listener pattern.
- Mô hình online-only (không COD) giúp FSM gọn hơn nhưng vẫn cần guard chặt cho replay/out-of-order webhooks.

### 2.3. Security — Crypto Libraries Mature
Payment webhook cần verify **chữ ký số** (HMAC-SHA256, HMAC-SHA512, RSA):
- Java `javax.crypto` / `java.security` ecosystem rất mature và đã qua audit bảo mật.
- Spring Security cung cấp utility cho HMAC computation.
- VNPay SDK Java chính thức — không có Go SDK chính thức.

### 2.4. Transactional Integrity — Xử Lý Tiền Thật
Payment là service **nhạy cảm nhất** (money handling):
- `@Transactional` gom INSERT transaction + INSERT outbox + INSERT status_history.
- Optimistic Locking `@Version` ngăn chặn duplicate webhook processing.
- Refund validation (tổng refund ≤ paid_amount) cần query + check + update atomic.

### 2.5. Reconciliation — Scheduled Jobs
Daily CronJob đối soát cần:
- Query toàn bộ transactions trong ngày, gọi API verify từng cổng thanh toán.
- So khớp amount, status giữa DB nội bộ và cổng.
- Spring `@Scheduled` + Spring Retry cho retry logic khi gọi API cổng.

### 2.6. Chiến Lược Lock & Fallback (Redis vs Optimistic DB)
Để xử lý Race Condition và Idempotency mạnh mẽ, hệ thống áp dụng chiến lược kết hợp:
*   **Optimistic Locking (Cột `version` rdbms):** Được sử dụng làm default guard cho **tất cả** state transition để bảo vệ dữ liệu (VD: Webhook ghi đè nhau). Không tốn overhead gọi sang Redis.
*   **Distributed Lock (Redis Redisson):** Được ưu tiên khi cần bảo vệ các tác vụ có side-effect bên ngoài mà không thể rollback qua DB (ví dụ: Gọi API từ webhook gửi tiền/hoàn tiền sang Provider, hoặc Cronjob quét transaction).
    *   *Naming convention:* `lock:payment:{action}:{txn_id}` (VD: `lock:payment:refund:txn_123`).
    *   *TTL:* 15s - 30s. Tránh lock bị treo vĩnh viễn khi worker crash. 
*   **Redis Degradation / Fallback:** Trong trường hợp Redis sập:
    *   Các API query Read/Reconciliation không bị ảnh hưởng.
    *   **Idempotency API (Initiate Payment / Refund):** Sẽ fallback xuống việc cố gắng insert check unique constraint ở CSDL (bảng `payment_idempotency` và `refund_transactions`).
    *   **Worker:** Nếu không lấy được lock, reject operation và đẩy xuống retry cycle / Dead Letter Queue để bảo vệ tính toàn vẹn thay vì ignore lock.

---

## 3. Tại Sao Không Chọn Golang (Gin)

| Hạn chế | Ảnh hưởng |
|:---|:---|
| **Thiếu FSM framework** | Phải implement 2 FSM phức tạp từ đầu + tất cả Guard conditions |
| **Provider SDK** | VNPay, MoMo SDK chính thức chỉ có Java — Go phải tự implement |
| **OOP Patterns** | Adapter + Factory Pattern kém tự nhiên trong Go — interface composition đủ nhưng verbose hơn |
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
| **HTTP Client** | Spring WebClient / OkHttp (gọi payment gateway APIs) |
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
