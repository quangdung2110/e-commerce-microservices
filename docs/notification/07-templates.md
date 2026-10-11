# Catalog template

**Trạng thái:** `implemented` (phase 1)

## 1. Vì sao catalog là contract, không phải chi tiết nội bộ

Producer gửi request bằng cách gọi tên template (`ORDER_CONFIRMED`) và đóng gói `template_data` theo schema của template đó. Nghĩa là tên template và schema `template_data` là **giao diện công khai** giữa Notification và mọi producer — đổi tên hay xoá field là breaking change y như đổi schema event. Catalog vì thế sống trong `contracts/`, có versioning, có vòng đời, và có owner.

Với fact (Cart), producer không biết tới template; bảng ánh xạ trong Notification ([02-pipeline.md](./02-pipeline.md) §2.2) chọn template và trích `template_data` từ fact. Template vẫn nằm trong cùng catalog để có một nơi duy nhất trả lời "hệ thống gửi được những gì".

## 2. Quy ước

| Quy ước | Nội dung |
|---|---|
| **Định danh** | `UPPER_SNAKE_CASE`, ổn định vĩnh viễn. Khớp cách Auth đã đặt (`PASSWORD_RESET`, `ACCOUNT_LOCKED`). |
| **Vị trí** | `contracts/events/notification/v1/templates/<TEMPLATE_ID>.schema.json` — mỗi template một JSON Schema cho `template_data`, kèm `x-lane`, `x-channels`, `x-governed-by`, `x-max-age`, `x-dedup-key`. |
| **Versioning** | Thêm field optional vào `template_data` = compatible. Đổi/xoá field required, đổi type = breaking → template mới `<ID>_V2`; template cũ chuyển `deprecated` cho tới khi producer migrate xong. Cùng quy tắc với event trong `contracts/conventions/events.md`. |
| **Vòng đời** | `proposed` → `active` → `deprecated` → `retired`. Producer chỉ được gửi template `active`. Request với template `retired` hoặc không tồn tại → `FAILED (unknown_template)`, không retry, có metric. |
| **Owner** | Notification. Producer đề xuất template mới bằng PR vào thư mục trên; Notification review lane, `max_age`, PII trong `template_data`. |
| **Hai bản render, một `template_data`** | Mỗi template có bản **email** (subject, HTML, text fallback) và bản **in-app** (`title` ≤ 80 ký tự, `body` ≤ 200, `deep_link`). Producer không cần biết kênh nào sẽ được dùng. |
| **i18n** | `vi` bắt buộc, `en` tuỳ chọn. Locale lấy từ Auth `notification-contact` (`locale?`), mặc định `vi`. Thiếu bản dịch → fallback `vi`, không lỗi. |
| **PII trong `template_data`** | Chỉ những gì cần hiển thị. **Không** email, số điện thoại, địa chỉ đầy đủ (địa chỉ giao hàng chỉ `recipient_name`, `ward`, `district`, `city`). Secret (OTP, reset URL) chỉ đi qua Auth `content` API, không bao giờ nằm trong Kafka. |
| **Tiền tệ** | `*_minor` (integer, đơn vị nhỏ nhất) + `currency` (`VND`), khớp `vnd_money` của Payment. Template render định dạng. |
| **URL** | Producer request gửi URL tuyệt đối tới frontend (`order_url`). Notification không tự lắp URL để không phải biết routing của frontend. Ngoại lệ: template từ nguồn mà contract **không có chỗ cho URL** — `CART_ABANDONED` (fact) và `REVIEW_REPLIED` (message của Review không mang URL) — Notification lắp `cart_url` / `review_url` từ `frontend.base_url` cấu hình (`/cart`, `/reviews/{review_id}` — trang đánh giá của chính tác giả, vì người nhận REVIEW_REPLIED luôn là tác giả; trang sản phẩm đánh địa chỉ theo slug mà Review không biết). Khi Review bổ sung `review_url` vào contract thì bỏ ngoại lệ này. |

## 3. Thuộc tính của một template

| Thuộc tính | Ý nghĩa | Ai quyết |
|---|---|---|
| `lane` | `critical` / `transactional` / `bulk` | Catalog, cố định. Producer request phải publish lên đúng topic của lane; publish sai topic → `FAILED (lane_mismatch)`. |
| `channels` | Tập kênh được phép: `email`, `in_app` (phase 1, D-08) | Catalog |
| `governed_by` | Preference nào được chặn template này: `email_enabled`, `in_app_enabled`, `marketing_enabled`, hoặc `none` | Catalog |
| `respects_suppression` | Hard bounce / complaint có chặn email không | Theo lane (§4) |
| `quiet_hours` | Có hoãn trong giờ yên tĩnh không | Theo lane (§4) |
| `max_age` | Khi claim, `now - created_at > max_age` → `EXPIRED` ([05-ordering.md](./05-ordering.md) §4) | Catalog. Producer có thể **rút ngắn** bằng `expires_at` trong request, không được kéo dài. |
| `dedup_key` | Công thức và mức bắt buộc: `required` / `optional` / `none` ([04-idempotency.md](./04-idempotency.md) lớp B) | Catalog định nghĩa công thức; producer điền giá trị (request) hoặc bảng ánh xạ sinh (fact). Thiếu `dedup_key` khi `required` → `FAILED (missing_dedup_key)`. |
| `template_data` | JSON Schema | Catalog |

## 4. Chính sách theo lane

Áp cho mọi template trong lane; template không được ghi đè.

| | Critical | Transactional | Bulk |
|---|---|---|---|
| `email_enabled = false` | **Vẫn gửi email.** `email_enabled` nghĩa là "không muốn nhận email thông báo", không phải "từ chối reset password của chính tôi". Tắt được critical thì user tắt email rồi quên mật khẩu là mất tài khoản. | Không gửi email; in-app vẫn gửi nếu `in_app_enabled` | Không gửi email |
| `in_app_enabled = false` | Không tạo delivery in-app (email vẫn đi) | Không tạo delivery in-app | Không tạo delivery in-app |
| `marketing_enabled = false` | Không liên quan | Không liên quan | **Không gửi kênh nào** |
| Suppression (hard bounce / complaint) | **Vẫn thử gửi** email; bounce lại thì `FAILED`. Không có kênh thay thế cho OTP nên không có lựa chọn khác. | Không gửi email; in-app vẫn gửi | Không gửi email; in-app vẫn gửi (suppression chỉ áp cho địa chỉ email) |
| Quiet hours (22:00–08:00 theo timezone của user; User chưa publish timezone → mặc định `Asia/Ho_Chi_Minh`) | Không áp | Không áp | Hoãn tới 08:00 nếu chưa quá `max_age`; quá thì `EXPIRED` |
| Frequency cap per-user | Không (Auth tự giới hạn OTP) | Không | Tối đa **2 bulk / ngày / user**, tính theo ngày lịch của timezone user; vượt → `SKIPPED (frequency_cap)` |

Ghi chú về complaint: user bấm "báo spam" trên **bất kỳ** email nào → suppression áp cho transactional và bulk, đồng thời tắt `marketing_enabled` (publish event cho User Service). Critical vẫn đi vì đó là yêu cầu chủ động của user.

## 5. Catalog phase 1 — 15 template

### 5.1 Critical — 7 template từ Auth

Tên giữ nguyên theo enum trong `contracts/http/auth/internal/v1/openapi.yaml`; template được gán ngay ở Ingest từ CloudEvents `type` (+ `purpose`) — bảng ánh xạ ở [10-interfaces.md](./10-interfaces.md) §2. Với ba template OTP/reset, `template_data` là secret, không nằm trong Kafka và không lưu; Notification lấy qua `POST /internal/v1/auth/notification-deliveries/{delivery_id}/content` cùng với email và `expires_at` lúc gửi. Với bốn template cảnh báo, `template_data` (`changed_at`, `locked_at`, `locked_until`, `unlocked_at`) nằm sẵn trong event của Auth và được lưu như mọi request khác — kênh in-app render từ đó, không gọi Auth; kênh email vẫn gọi `content` để lấy địa chỉ.

| Template | Kênh | `governed_by` | `max_age` | `dedup_key` | `template_data` (từ Auth API) |
|---|---|---|---|---|---|
| `REGISTRATION_VERIFICATION` | email | none | theo `expires_at` của Auth | none | `otp` |
| `EMAIL_CHANGE_VERIFICATION` | email | none | theo `expires_at` | none | `otp` |
| `PASSWORD_RESET` | email | none | theo `expires_at` | none | `reset_url` |
| `EMAIL_CHANGED` | email + in_app | none | 24 h | `email_changed:{delivery_id}` | `event`, `changed_at` |
| `PASSWORD_CHANGED` | email + in_app | none | 24 h | `password_changed:{delivery_id}` | `event`, `changed_at` |
| `ACCOUNT_LOCKED` | email + in_app | none | 24 h | `account_locked:{delivery_id}` | `event`, `locked_at`, `locked_until` |
| `ACCOUNT_UNLOCKED` | email + in_app | none | 24 h | `account_unlocked:{delivery_id}` | `event`, `unlocked_at` |

- Ba template OTP/reset chỉ có email vì in-app vô nghĩa khi user chưa đăng nhập được. Bốn template cảnh báo có in-app để user thấy trong app ở lần đăng nhập tới.
- `dedup_key` của ba template OTP/reset là `none` **có chủ ý**: mỗi lần user bấm "gửi lại" là một `delivery_id` mới và phải gửi. Chặn spam là việc của Auth (rate limit OTP), không phải idempotency.
- `EMAIL_CHANGED` là cảnh báo gửi tới **email cũ** (một `delivery_id`, Auth chốt trong [AsyncAPI Auth](../../contracts/events/auth/v1/asyncapi.yaml) `userEmailChanged`). Email **mới** nhận mã xác minh `EMAIL_CHANGE_VERIFICATION` ở bước trước đó nên không cần cảnh báo thứ hai. Notification chỉ gửi tới địa chỉ mà `content` API của Auth trả về cho `delivery_id` đó.

### 5.2 Transactional — 5 template từ Order (request, cần contract mới)

Order đóng gói `template_data` từ row đơn hàng và snapshot của chính nó, trong cùng transaction chuyển trạng thái, qua outbox. **Order không gọi service nào khác** để gom dữ liệu (D-07). Publish lên `t.notification.transactional.v1`, key = `subject` = `orders/{order_id}`.

| Template | Mốc trong Order | Kênh | `max_age` | `dedup_key` (required) | `template_data` |
|---|---|---|---|---|---|
| `ORDER_CONFIRMED` | Chuyển `PAID` | email + in_app | 6 h | `order_confirmed:{order_id}` | `order_id`, `order_code`, `placed_at`, `items[]{name, variant_name?, quantity, unit_price_minor}`, `subtotal_minor`, `discount_minor`, `shipping_fee_minor`, `total_minor`, `currency`, `payment_method_label`, `shipping_address{recipient_name, ward, district, city}`, `order_url` |
| `ORDER_PAYMENT_FAILED` | Payment attempt thất bại (Order consume `payment.failed`/`expired`) | email + in_app | 2 h | `order_payment_failed:{order_id}:{attempt}` | `order_id`, `order_code`, `amount_minor`, `currency`, `attempt`, `can_retry`, `retry_deadline?`, `order_url` |
| `ORDER_CANCELLED` | Chuyển `CANCELLED` pre-capture (buyer, payment timeout, admin, hết hàng, checkout failure) | email + in_app | 24 h | `order_cancelled:{order_id}` | `order_id`, `order_code`, `cancelled_at`, `reason` ∈ {`buyer`, `payment_timeout`, `admin`, `out_of_stock`, `checkout_failed`}, `refund_expected=false`, `order_url` |
| `ORDER_COMPLETED` | Chuyển `COMPLETED` (mọi line có delivery proof) | email + in_app | 48 h | `order_completed:{order_id}` | `order_id`, `order_code`, `completed_at`, `items[]{name, variant_name?, quantity}`, `review_url` |
| `ORDER_REFUNDED` | Refund thành công (Order consume `refund.succeeded`) | email + in_app | 48 h | `order_refunded:{order_id}:{refund_id}` | `order_id`, `order_code`, `refund_id`, `amount_minor`, `currency`, `refunded_at`, `order_url` |

`governed_by` cho cả 5: `email_enabled`, `in_app_enabled`.

- **Nguồn đã chốt với Order**: Checkout bắt buộc `items[].snapshot_name`; Order lưu
  immutable `order_item_lines.snapshot_name` và map sang notification `items[].name`.
  `order.completed.v1` không cần mang name vì Review chỉ dùng product/SKU/variant;
  Notification không RPC Product/User để enrich item.
- **Không có trong phase 1, thêm sau không phá gì**: `ORDER_CREATED` (user đang nhìn màn hình đặt hàng), `ORDER_LINE_DELIVERED` (quá nhiều email; `ORDER_COMPLETED` đủ), `REFUND_FAILED` (vấn đề vận hành, user không làm gì được), return-request lifecycle (chưa rõ scope).
- `ORDER_COMPLETED` có email dù là email thứ 2–3 cho một đơn, vì nó dẫn user tới `review_url` — có giá trị nghiệp vụ cho Review.

### 5.3 Transactional — 2 template từ Review (request, contract đã có)

Đến từ `t.review.events`, message `reviewReplyNotificationRequested` / `reviewRemovalNotificationRequested`. Consumer request của Review ánh xạ 1-1 sang template; `recipient_user_id` trong payload là `user_id`.

| Template | Kênh | `governed_by` | `max_age` | `dedup_key` | `template_data` |
|---|---|---|---|---|---|
| `REVIEW_REPLIED` | email + in_app | `email_enabled`, `in_app_enabled` | 72 h | `review_replied:{review_id}` | `review_id`, `product_id`, `review_url` (Notification lắp từ `frontend.base_url` — ngoại lệ §2) |
| `REVIEW_REMOVED` | email + in_app | `email_enabled`, `in_app_enabled` | 72 h | `review_removed:{review_id}` | `review_id`, `product_id` |

Theo contract Review, payload **không** mang nội dung reply, lý do gỡ, danh tính admin, hay URL — email chỉ là "đánh giá của bạn có phản hồi mới" + link Notification lắp. Không có tên sản phẩm; chấp nhận cho phase 1.

### 5.4 Bulk — 1 template từ Cart (fact, contract đã có)

| Template | Kênh | `governed_by` | `max_age` | `dedup_key` (bảng ánh xạ sinh) | `template_data` |
|---|---|---|---|---|---|
| `CART_ABANDONED` | email + in_app | `marketing_enabled`, `email_enabled`, `in_app_enabled` | 24 h | `cart_abandoned:{cart_id}:{abandonment_cycle}` | `cart_id`, `abandonment_cycle?` (schema chỉ bắt buộc `cart_id`, `cart_url`; consumer luôn điền), `cart_url` do Notification lắp từ base URL frontend (fact không có chỗ cho URL — ngoại lệ §2) |

Fact `cart.abandoned.v1` chỉ có `cart_id`, `user_id`, `abandonment_cycle`, `occurred_at` — email là lời nhắc + link, không có danh sách sản phẩm. Muốn có sản phẩm thì Cart bổ sung snapshot vào event (thay đổi ở nguồn), không phải Notification gọi Cart.

Bulk còn chỗ cho `PROMOTION_*` khi Promotion có nhu cầu; chưa có contract nên không liệt kê.

## 6. Tổng hợp theo nguồn

| Nguồn | Mô hình | Template | Topic |
|---|---|---|---|
| Auth | Request (resolve content) | 7 critical | `t.auth.notification.events` (A4) |
| Order | Request | 5 transactional | `t.notification.transactional.v1` (**cần mở**) |
| Review | Request | 2 transactional | `t.review.events` |
| Cart | Fact | 1 bulk | `t.cart.events` |
| Payment, Delivery | **Không consume** | — | Fact của họ không có `user_id` (Payment cấm tường minh); Order đã consume chúng và phát request thay |

## 7. Ví dụ một request từ Order

```json
{
  "specversion": "1.0",
  "id": "5b1c0f2e-2f0a-4d0e-9c1a-1a2b3c4d5e6f",
  "type": "com.ecommerce.notification.requested.v1",
  "source": "/services/order",
  "subject": "orders/1000234",
  "time": "2026-09-20T10:15:30Z",
  "correlationid": "chk_01J8...",
  "datacontenttype": "application/json",
  "dataschema": "/contracts/events/notification/v1/schemas/notification-requested.schema.json",
  "data": {
    "template": "ORDER_CONFIRMED",
    "user_id": "0f6d3a8e-6a4e-4d0d-9c3b-2b1e6f7a8c9d",
    "dedup_key": "order_confirmed:1000234",
    "expires_at": "2026-09-20T16:15:30Z",
    "template_data": {
      "order_id": "1000234",
      "order_code": "ORD-1000234",
      "placed_at": "2026-09-20T10:15:02Z",
      "items": [
        { "name": "Áo thun cotton", "variant_name": "Đen / L", "quantity": 2, "unit_price_minor": 199000 }
      ],
      "subtotal_minor": 398000,
      "discount_minor": 0,
      "shipping_fee_minor": 0,
      "total_minor": 398000,
      "currency": "VND",
      "payment_method_label": "Thanh toán trực tuyến",
      "shipping_address": { "recipient_name": "Nguyễn Văn A", "ward": "Phường 7", "district": "Quận 3", "city": "TP. Hồ Chí Minh" },
      "order_url": "https://shop.example.com/orders/1000234"
    }
  }
}
```

Không có email, không có số điện thoại, không có địa chỉ chi tiết. Notification resolve email từ `user_id` lúc gửi ([02-pipeline.md](./02-pipeline.md) §2.3).
