---
doc_id: checkout-adr-010
service: checkout
status: accepted
date: 2026-09-19
decision_owners:
  - Checkout Service team
supersedes: null
evidence:
  - docs/checkout/data.md
  - docs/checkout/workflows.md
  - docs/payment/interfaces.md
  - contracts/http/order/internal/v1/openapi.yaml
---

# ADR-010 — Không cache `client_secret`; relay client data từ Order ở mỗi lần đọc

## Trạng thái

`Accepted` 2026-09-19. Tinh chỉnh nhánh success của
[ADR-007](./ADR-007-payload-and-response-recovery-cache.md) và câu "response cache
lưu đúng ba giá trị" của [ADR-008](./ADR-008-single-payment-provider-stripe.md);
không thay thế hai ADR đó. Chưa có implementation evidence.

## Bối cảnh

Từ 2026-09-19 Payment là **nơi lưu duy nhất** của Stripe `client_secret` (mã hóa,
`docs/payment/data.md §3.1`); Order không còn cột client data trong
`order_payment_attempts` và đọc qua `GET /v1/payments/{transactionId}/client-data`
mỗi lần cần relay (`docs/order/data.md §3.5`). Chỉ Payment biết secret còn dùng được
hay không (`PENDING` và chưa quá `client_expires_at`). ADR-007 lại lưu
`{order_id, client_secret, publishable_key}` trong `checkout:response:*` 20 phút:

- Secret tồn tại ở ba nơi (Payment mã hóa, Order relay, Checkout Redis plaintext)
  với ba chính sách khác nhau; Redis Checkout không mã hóa.
- Sau khi attempt rời `PENDING` (đã thanh toán, hết hạn, bị hủy), Checkout vẫn trả
  secret cũ cho client trong tối đa 20 phút → frontend khởi tạo Elements trên một
  intent đã chết.
- Hai TTL độc lập (Checkout 20 phút, Payment 15 phút) không có quan hệ gì với nhau.

## Quyết định

- `checkout:response:{user_id}:{token}` nhánh success chỉ lưu **định danh**:
  `{order_id, payment_attempt_number, payment_transaction_id}`. Không lưu
  `client_secret`, `publishable_key`, `expires_at`.
- Ở **mọi** lần trả client data cho frontend (lần đầu lẫn replay), Checkout dùng dữ
  liệu Order vừa trả trong cùng request: lần đầu là response của
  `POST /internal/v1/checkout-order-submissions`; replay là
  `GET /internal/v1/checkout-order-submissions/{checkout_token}`. Order lấy từ
  Payment `client-data` tại thời điểm đó và trả kèm `usable`.
- Khi `usable=false` (attempt không còn `PENDING` hoặc quá hạn), Checkout trả
  `client_secret=null`, `publishable_key=null` cùng `order_id`; frontend chuyển sang
  màn hình trạng thái đơn (đọc qua Order public API) thay vì Stripe Elements.
- Nhánh conflict (`409 PROMOTION_RECALCULATION_NEEDED`) và payload cache giữ nguyên
  ADR-007.
- Secret không được ghi vào Redis, log, trace attribute hay response cache của
  Checkout dưới bất kỳ dạng nào.

## Phương án đã cân nhắc

- **Giữ cache secret nhưng rút TTL xuống bằng `expires_at`:** vẫn plaintext trong
  Redis, vẫn không biết attempt đã `SUCCESS`/`CANCELED` sớm hơn hạn. Không chọn.
- **Mã hóa secret trong Redis Checkout:** thêm KMS integration thứ ba cho cùng một
  giá trị Payment đã bảo vệ. Không chọn.
- **Không cache gì, luôn gọi Order:** đúng cho client data; nhưng nhánh conflict
  cần cache để không tính lại Promotion, nên giữ response cache cho conflict và
  định danh cho success. Được chọn.

## Hệ quả

Tích cực: một nơi lưu secret, một policy `usable`; replay không bao giờ trả secret
của intent đã chết; giảm PII/secret trong Redis Checkout. Tiêu cực: replay success
tốn thêm một hop Checkout → Order → Payment (chỉ khi client retry, hiếm); Checkout
phải xử lý `client_secret=null`.

## Ảnh hưởng

`data.md §8.2, §11`, `workflows.md` WF-CHK-003/004, `domain.md`, `interfaces.md`,
`README.md` của Checkout; Order internal contract `PaymentClientData` cho phép
`client_secret`/`publishable_key` null và thêm `usable`.

## Xác minh

Khi có code: retry sau success trả cùng `order_id` và secret **tươi** từ Order; retry
sau khi attempt đã `SUCCESS`/`EXPIRED` trả `usable=false`, `client_secret=null`;
`SCAN`/`GET` Redis Checkout không tìm thấy chuỗi `_secret_`; log không chứa secret.
