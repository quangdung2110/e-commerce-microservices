---
doc_id: payment-adr-014
service: payment
status: accepted
date: 2026-09-19
decision_owners:
  - Payment Service team
supersedes: null
evidence:
  - docs/payment/domain.md
  - docs/payment/data.md
  - docs/order/data.md
---

# ADR-014 — Decline không phải terminal; mọi trạng thái terminal non-capture đòi hỏi PaymentIntent đã bị hủy

## Trạng thái

`Accepted` 2026-09-19. Tinh chỉnh [ADR-012](./ADR-012-recoverable-initiation-and-cancel.md);
không thay thế ADR nào. Chưa xác minh implementation.

## Bối cảnh

Thiết kế trước map thẳng `payment_intent.payment_failed` → `T3` `FAILED` (terminal) và
phát `PAYMENT_FAILED` để Order cancel/compensate hoặc mở attempt kế tiếp. Nhưng với
Stripe, một PaymentIntent **không** terminal sau một lần decline: intent quay về
`requires_payment_method`, Stripe Elements cho buyer nhập thẻ khác trên cùng intent, và
Stripe bắn `payment_failed` nhiều lần trước khi `succeeded`. Hệ quả:

- UX sai: một lần thẻ bị từ chối làm Order hủy đơn trong khi buyer vẫn đang ở trang
  thanh toán.
- Double capture: Order mở attempt 2 (`pi_yyy`) sau `PAYMENT_FAILED`, nhưng buyer thử
  lại thành công trên `pi_xxx` còn sống → hai lần capture cho một order.
- Expiration Scanner chỉ `PaymentIntent.retrieve` rồi đánh `EXPIRED`; intent vẫn sống
  và có thể capture sau khi Order đã nhả hàng.

## Quyết định

1. **`payment_intent.payment_failed` là sự kiện phi terminal.** Payment ghi
   `last_error_code`/`last_error_message`, audit `T3a` (không tăng
   `financial_state_version`, không phát event tài chính) và giữ `PENDING`. Buyer tiếp
   tục thử trong phiên `expires_at`.
2. **Payment tự chấm dứt attempt** khi decline thuộc tập non-retryable (Stripe
   `decline_code` ∈ {`fraudulent`, `lost_card`, `stolen_card`, `pickup_card`,
   `restricted_card`, `security_violation`, `merchant_blacklist`} hoặc Radar
   `outcome.type = blocked`) hoặc số lần decline trong phiên vượt
   `max_declines_per_attempt` (mặc định 5). Khi đó Payment persist
   `provider_operations CANCEL` (`reason = DECLINED_NON_RETRYABLE`/`DECLINE_LIMIT`),
   gọi `PaymentIntent.cancel`, và **chỉ sau khi intent đã canceled** mới áp `T3`
   `FAILED` + phát `PAYMENT_FAILED` với `error_code` chuẩn hóa.
3. **Bất biến `INV-PAY-010`:** không transition nào đưa payment từ
   `PENDING`/`PROCESSING` sang `FAILED`/`EXPIRED`/`CANCELED` khi PaymentIntent còn có
   thể capture. Mọi đường vào terminal non-capture (`T3`, `T4`, `T5`) đều đi qua một
   `provider_operations CANCEL` đã `PROVIDER_CONFIRMED`, hoặc qua webhook
   `payment_intent.canceled` đã verify. Nếu `PaymentIntent.cancel` trả `succeeded`
   (capture thắng race), áp `T2` `SUCCESS` — không bao giờ terminal non-capture.
4. Expiration Scanner theo đó: quét `expires_at <= now()`, persist `CANCEL`
   (`reason = SESSION_EXPIRED`), cancel intent, rồi `T4` `EXPIRED`. Intent ở trạng
   thái `processing` phải gọi provider và xử lý actual response: canceled → terminal
   phù hợp, succeeded → success, temporarily-not-cancelable/unknown → recovery.
5. `payment_intent.canceled` webhook map về terminal theo `CANCEL` operation đã ghi cho
   payment đó: `DECLINED_*` → `FAILED`; `SESSION_EXPIRED` → `EXPIRED`; reason từ Order
   command hoặc không có operation (Stripe/Dashboard hủy) → `CANCELED`.

## Phương án đã cân nhắc

- **Giữ `payment_failed` = terminal `FAILED` (thiết kế cũ):** UX sai và mở cửa double
  capture. Không chọn.
- **Decline = terminal nhưng cancel intent trước khi phát `PAYMENT_FAILED`:** giải
  quyết double capture nhưng vẫn hủy đơn ngay lần decline đầu; buyer phải tạo attempt
  mới cho một lỗi nhập thẻ thường gặp. Không chọn.
- **Decline phi terminal + tự chấm dứt khi non-retryable/quá ngưỡng + cancel intent
  trước mọi terminal non-capture:** khớp mô hình PaymentIntent của Stripe, giữ nguyên
  contract `PAYMENT_FAILED` cho Order, đóng race capture muộn. Được chọn.

## Hệ quả

Tích cực: `FAILED`/`EXPIRED`/`CANCELED` trở thành fact "không thể capture" thật sự,
đúng với guard Order "attempt mới chỉ sau terminal non-capture fact"; UX retry thẻ tự
nhiên; một cơ chế cancel duy nhất cho ba đường terminal. Tiêu cực: thêm một round-trip
`PaymentIntent.cancel` trước `EXPIRED`/`FAILED`; thêm transition audit `T3a`; cần
policy decline code và ngưỡng cấu hình; `PAYMENT_FAILED` đến Order trễ hơn (sau cancel)
nhưng vẫn trong phiên 15 phút.

## Ảnh hưởng

- `domain.md`: FSM Payment thêm `T3a`, sửa guard `T3`/`T4`/`T5`; thêm `INV-PAY-010`,
  `RULE-PAY-014`; error code mới.
- `data.md`: `provider_operations.request_fingerprint` của `CANCEL` mang `reason`;
  cột `decline_count` trên `payment_transactions`.
- `interfaces.md §5.1`, `architecture.md §3.3`, `workflows.md` WF-002/005: mapping
  event và bước cancel trước expire.
- Order: không đổi contract; `PAYMENT_FAILED` vẫn là terminal non-capture fact.
- `operations.md`: cấu hình `max_declines_per_attempt`, danh sách decline code
  non-retryable.

## Xác minh

Test: decline retryable → vẫn `PENDING`, buyer thanh toán lại thành công trên cùng
intent; decline non-retryable → `CANCEL` operation → `FAILED` + `PAYMENT_FAILED`;
decline thứ 6 → `FAILED`; scanner cancel rồi `EXPIRED`; cancel trả `succeeded` → `T2`;
webhook `payment_intent.canceled` không có operation → `CANCELED`; không tồn tại
đường nào vào `FAILED`/`EXPIRED`/`CANCELED` mà `provider_status_snapshot` ≠ `canceled`.
