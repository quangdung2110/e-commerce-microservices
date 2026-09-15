---
doc_id: payment-adr-002
service: payment
status: accepted
date: null
decision_owners:
  - Architecture Team
supersedes: null
evidence:
  - docs/payment/README.md
  - docs/payment/architecture.md
  - docs/payment/workflows.md
---

# ADR-002 — Giữ Refund trong Payment Service (Phase 1), tách khi đạt ngưỡng Phase 2

## Trạng thái

`Accepted` trong thiết kế (quyết định gốc ghi "03/2026"; ngày chính xác không được
ghi lại). Chưa xác minh implementation.

## Bối cảnh

Payment Service quản lý cả Payment Transaction và Refund Transaction trong cùng
service boundary; hệ thống dùng PostgreSQL-first + Outbox + webhook async. Cần
chốt có tách Refund Service ngay hay không, ghi rõ điều kiện chuyển phase và đảm
bảo các design doc tham chiếu cùng một quyết định.

Inputs được xác nhận: refund ratio hiện tại `< 2%`; chưa cần workflow duyệt nhiều
cấp cho Refund; chưa tách ownership team Refund khỏi Payment.

**Phase 18 update:** chuyển sang single-provider (Stripe) **không thay đổi** quyết
định này vì các trigger Phase 2 (refund ratio, workflow complexity, ownership)
vẫn valid; single-provider chỉ giảm nhẹ phần "provider variance" trong benchmark.

## Quyết định

**Không tách Refund Service ở thời điểm hiện tại.** Refund là logical module
trong Payment Service (Phase 1) nhưng phải giữ code boundary rõ (`src/refund/`,
interface riêng, contract độc lập) để tách thành service độc lập khi đạt ngưỡng.

### Trigger chuyển sang Phase 2 (Refund Service riêng)

Chuyển phase khi đạt ít nhất một điều kiện chính trong 2 chu kỳ liên tiếp:

| Trigger | Ngưỡng gợi ý | Tác động |
|---|---|---|
| Refund volume | `refund_ratio > 5%` | Cần scale riêng cho refund workflows |
| Workflow complexity | `manual/dispute refund > 1%` | Cần approval workflow và dispute domain riêng |
| Ownership split | Team Refund độc lập | Cần tách deploy + runbook + SLA |
| Ops pressure | Backlog/manual intervention tăng có xu hướng | Cần tách để giảm MTTR |

### Bất biến khi tách service

1. Partition key của refund events vẫn là `order_id`.
2. Contract idempotency giữ ở API + consumer.
3. Webhook signature verification luôn xảy ra trước state transition.
4. Outbox publish only-after-commit.
5. Reconciliation job vẫn bắt buộc.

### Phase 2 Readiness Checklist

1. Tách interface nội bộ: `RefundApplicationService`, `RefundProviderPort`.
2. Chuẩn hóa schema event `REFUND_INITIATED`, `REFUND_SUCCESS`, `REFUND_FAILED`
   (và `REFUND_REVERSED` nếu provider hỗ trợ).
3. Có runbook replay cho queue/DLQ.
4. Có dashboard theo dõi refund SLA + stuck processing age.
5. Chạy shadow-read/shadow-reconcile trước khi cutover.

Khuyến nghị đi kèm: endpoint `GET /v1/refunds/{refundTransactionId}` để tách đọc
Refund khỏi read model Payment Transaction, tạo seam cho Phase 2 mà không đổi
contract phía Order/CSKH.

## Phương án đã cân nhắc

- **Tách Refund Service ngay:** chưa có áp lực scale đủ lớn để justify chi phí
  phân tán; tăng rủi ro drift event contract, lock coordination và reconciliation
  chéo service; nhiều hop hơn khi xử lý incident. Không chọn.
- **Giữ trong Payment (modular boundary):** giữ cùng transaction boundary giúp
  kiểm soát `sum(refunds) <= paid_amount`; rút ngắn đường xử lý incident. Được chọn.

## Benchmark từ hệ thống lớn

Stripe, Adyen, PayPal, Square, Amazon Pay cho thấy pattern nhất quán: refund là
asynchronous lifecycle (response ban đầu chưa phải kết quả cuối); idempotency key
bắt buộc; webhook có thể duplicate/delay/out-of-order và consumer phải dedup theo
eventual consistency; refund có thể failed/reversed sau khi accepted;
reconciliation là lớp bắt buộc để khóa sổ tài chính. Kiến trúc hiện tại đã đúng
hướng nếu tiếp tục tăng cường contract và vận hành theo các nguyên tắc trên.

## Hệ quả

Tích cực: đơn giản vận hành, invariant tài chính trong một transaction, ít điểm
fail. Tiêu cực: Payment Service gánh cả refund domain; phải kỷ luật giữ boundary
code để không phải rewrite khi tách; cần theo dõi các trigger định kỳ.

## Ảnh hưởng migration

Không có migration hiện tại. Khi tách: cần tách interface, chuẩn hóa event
schema, shadow reconcile, và giữ 5 bất biến ở trên.

## Xác minh

Cần cấu trúc package `src/refund/` tách biệt, interface `RefundApplicationService`/
`RefundProviderPort`, metric refund ratio và manual/dispute ratio để đánh giá
trigger theo chu kỳ.
