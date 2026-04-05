# Payment Service - Refund Boundary Decision (ADR)

## 1. Context

Payment Service hiện quản lý cả Payment Transaction và Refund Transaction trong cùng service boundary. Hệ thống đang dùng PostgreSQL-first + Outbox + Webhook async processing.

Mục tiêu của ADR này:

1. Chốt quyết định có tách Refund Service ngay hay không.
2. Ghi rõ điều kiện chuyển phase trong tương lai.
3. Đảm bảo các design docs khác tham chiếu cùng một quyết định.

---

## 2. Decision (03/2026)

**Không tách Refund Service ở thời điểm hiện tại.**

Refund tiếp tục là logical module trong Payment Service (Phase 1), nhưng phải giữ code boundary rõ (`src/refund/`, interface riêng, contract độc lập) để có thể tách thành service độc lập khi đạt ngưỡng.

### 2.1 Inputs được xác nhận

- Refund ratio hiện tại: `< 2%`
- Chưa cần workflow duyệt nhiều cấp cho Refund
- Chưa tách ownership team Refund khỏi Payment

### 2.2 Tại sao chưa tách ngay

1. Chưa có áp lực scale đủ lớn để justify chi phí phân tán.
2. Giữ cùng transaction boundary giúp kiểm soát tốt điều kiện `sum(refunds) <= paid_amount`.
3. Giảm rủi ro drift event contract, lock coordination và reconciliation chéo service.
4. Rút ngắn đường xử lý incident (ít hop, ít điểm fail hơn).

---

## 3. Benchmark từ hệ thống lớn

Tham chiếu từ Stripe, Adyen, PayPal, Square, Amazon Pay cho thấy các pattern nhất quán:

1. Refund là asynchronous lifecycle (API response ban đầu chưa phải kết quả cuối cùng).
2. Idempotency key là bắt buộc để tránh refund trùng.
3. Webhook có thể duplicate, delay, out-of-order; consumer phải dedup và chịu lỗi theo hướng eventual consistency.
4. Refund có thể failed/reversed sau khi đã accepted.
5. Reconciliation là lớp bắt buộc để khóa sổ tài chính chính xác.

=> Kiến trúc hiện tại của Payment Service đã đúng hướng nếu tiếp tục tăng cường contract và vận hành theo các nguyên tắc trên.

---

## 4. Trigger chuyển sang Phase 2 (Refund Service riêng)

Chuyển phase khi đạt ít nhất một điều kiện chính trong 2 chu kỳ liên tiếp:

| Trigger | Ngưỡng gợi ý | Tác động |
|:---|:---|:---|
| Refund volume | `refund_ratio > 5%` | Cần scale riêng cho refund workflows |
| Workflow complexity | `manual/dispute refund > 1%` | Cần approval workflow và dispute domain riêng |
| Ownership split | Team Refund độc lập | Cần tách deploy + runbook + SLA |
| Ops pressure | Backlog/manual intervention tăng có xu hướng | Cần tách để giảm MTTR |

---

## 5. Bất biến khi tách service

Khi tách Refund Service, phải giữ các bất biến sau:

1. Partition key của refund events vẫn là `order_id`.
2. Contract idempotency giữ ở API + consumer.
3. Webhook signature verification luôn xảy ra trước state transition.
4. Outbox publish only-after-commit.
5. Reconciliation job vẫn bắt buộc.

---

## 6. Phase 2 Readiness Checklist

1. Tách interface nội bộ: `RefundApplicationService`, `RefundProviderPort`.
2. Chuẩn hóa schema event cho `REFUND_INITIATED`, `REFUND_SUCCESS`, `REFUND_FAILED` (và `REFUND_REVERSED` nếu provider hỗ trợ).
3. Có runbook replay cho queue/DLQ.
4. Có dashboard theo dõi refund SLA + stuck processing age.
5. Chạy shadow-read/shadow-reconcile trước khi cutover.
