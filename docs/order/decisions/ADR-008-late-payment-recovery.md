---
doc_id: order-adr-008
service: order
status: accepted
date: null
decision_owners:
  - Architecture Team
supersedes: drop-payment-signal-after-checkout-timeout
evidence:
  - docs/order/workflows.md
  - docs/order/domain.md
---

# ADR-008 — Khôi phục payment success đến sau checkout timeout

## Trạng thái

`Accepted` trong thiết kế; ngày gốc và implementation evidence chưa có.

## Bối cảnh

Temporal timer 15 phút và Payment webhook/event có thể race. Nếu timer thắng,
checkout compensation giải phóng inventory/promotion và cancel order, nhưng provider
có thể xác nhận đã thu tiền ngay sau đó. Drop signal làm user mất tiền nhưng không
có order thực hiện; áp thẳng success lên workflow đóng phá determinism/resource state.

## Quyết định

Khi `PAYMENT_SUCCESS` không signal được vì checkout đã timeout và order là
`CANCELLED(PAYMENT_TIMEOUT)`, start duy nhất
`OrderLatePaymentRecoveryWorkflow` theo order. Workflow verify evidence, chuyển
order về paid lane, phát `ORDER_PAID(payment_recovery=true)`, tái lập Inventory
reservation rồi chạy post-payment. Nếu không phục hồi được các bước bắt buộc, đi
auto-refund để trả tiền.

## Phương án đã cân nhắc

- Drop stale event: đơn giản nhưng sai tài chính.
- Reopen checkout history: không phù hợp workflow đã close/compensated.
- Recovery lane riêng: thêm state/ops nhưng xử lý được payment thật; được chọn.

## Hệ quả

Không bỏ sót tiền đã thu và có audit lane riêng. Đổi lại phải tái reserve sau khi
resource đã release, có nguy cơ hết stock/promotion và cần auto-refund/escalation.
`CANCELLED` do reason khác timeout không được recovery tự động.

## Ảnh hưởng migration

Cần workflow/task queue mới, routing fallback, recovery metadata/event semantics,
reservation evidence và auto-refund bridge. Dashboard phải phân biệt normal paid và
recovered paid.

## Xác minh

Test signal-before/timer-before, exact cancel reason guard, duplicate success,
re-reserve success/failure, post-payment compensation và exactly-one auto-refund.
