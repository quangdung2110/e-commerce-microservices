# PII và compliance

**Trạng thái:** `implemented` (phase 1)

Các quy tắc ở đây đã được rải trong 02, 03, 07, 08, 09 khi từng quyết định được đưa ra. File này gom chúng về một chỗ để trả lời ba câu hỏi mà một cuộc audit sẽ hỏi: **dữ liệu cá nhân nào nằm ở đâu, sống bao lâu, ai xoá** — và để làm checklist khi thêm template hay nguồn mới.

## 1. Nguyên tắc

1. **Notification không phải nơi lưu danh tính.** Email là của Auth, preference là của User. Notification chỉ giữ `user_id` và những gì cần để render một thông báo cụ thể trong một khoảng thời gian ngắn.
2. **PII không đi qua Kafka.** Kafka replicate, lưu theo retention, không xoá được từng bản ghi, và ai có quyền đọc topic thì đọc được tất cả. Email, số điện thoại, địa chỉ chi tiết, secret không bao giờ nằm trong event — kể cả event Notification tự phát.
3. **Không lưu bản đã render.** Email HTML đã gửi không được ghi ở đâu; cần xem lại thì render lại từ `template` + `template_version` + `template_data`.
4. **Hash thay vì địa chỉ** ở mọi nơi cần nhận diện địa chỉ email lâu dài (suppression, log provider).
5. **Xoá tài khoản là anonymize + xoá, trong 24 giờ, không thủng metric.**

## 2. Bản đồ dữ liệu cá nhân

| Dữ liệu | Phân loại | Ở đâu | Sống bao lâu | Ai xoá | Ghi chú |
|---|---|---|---|---|---|
| `user_id` | INTERNAL | Hầu hết bảng, Redis key, event | Theo retention từng bảng | `user.deleted` → NULL ở request/delivery/suppression; DELETE ở preference/inbox | Định danh nội bộ, không phải PII trực tiếp nhưng nối được về người |
| **Email người nhận** | PII | **Chỉ** trong memory của worker giữa resolve và gọi SES; Redis cache `contact:{user_id}` TTL 15 phút | ≤ 15 phút | TTL; `user.deleted` → `DEL` | Không bao giờ vào Postgres, log, metric label, event |
| Email trong `raw` của SES event | PII | **Không** — poller thay bằng `sha256` trước khi INSERT `provider_event` | — | — | Bản gốc từ SQS bị xoá sau khi INSERT |
| `email_hash` | Pseudonymous | `suppression`, `provider_event.raw` | Suppression hard/complaint: vĩnh viễn; `provider_event`: 30 ngày | Không xoá khi `user.deleted` (địa chỉ chết vẫn chết; hash không truy ngược) | `sha256(lower(trim(email)))`, không salt để so khớp được giữa các lần |
| `template_data` (tên người nhận, phường/quận/thành phố, tên sản phẩm, số tiền, mã đơn) | PII mức thấp | `notification_request.template_data` | 90 ngày (partition) | `user.deleted` → NULL trong 24 h | Ba template OTP/reset của Auth: **không lưu** (`NULL`), vì đến qua API `content` chứa secret. Bốn template cảnh báo của Auth chỉ có timestamp (`changed_at`, `locked_at`…) — INTERNAL, lưu bình thường |
| Secret (OTP, reset URL) | SECRET | **Không ở đâu** — chỉ trong memory giữa gọi Auth `content` API và gọi SES | ms | — | Không cache, không log, không `template_data` |
| Nội dung in-app (`title`, `body`, `deep_link`) | PII mức thấp | `inbox_item` | 90 ngày | `user.deleted` → DELETE | Do template sinh từ `template_data`; không chứa gì ngoài những gì `template_data` có |
| Preference (3 cờ, timezone, locale) | INTERNAL | `user_preference` | Tới khi user xoá | `user.deleted` → DELETE | Bản replicate; nguồn sự thật ở User |
| `subject` (`orders/1000234`) | INTERNAL | `notification_request`, `notification_delivery`, event | 90 ngày | `user.deleted` → NULL ở request và delivery | Nối được về đơn hàng → về người; anonymize cùng lúc |
| `dedup_key`, `source_ref` | INTERNAL | `notification_request`, `notification_delivery` (`delivery_dedup` chỉ giữ `dedup_key` 30 ngày, không gắn user) | 90 ngày | `user.deleted` → NULL ở request và delivery | `dedup_key` nhúng order/review/cart id (`order_confirmed:1000234`), `source_ref` là `delivery_id` của Auth; cùng mức nhạy cảm với `subject` nên anonymize cùng lúc |
| Subject/user HMAC key | Pseudonymous | `notification_subject_sequence`, `notification_ordering_lease`, `deleted_user_tombstone` | Không TTL phase 1 | Purge chỉ theo replay-safe policy | Versioned HMAC secret (`NOTIFICATION_PRIVACY_HMAC_KEY`, ≥ 32 byte ngoài development) trong secret manager; không raw subject/user ID; rotation: đọc mọi version, ghi subject ledger dưới version active, ghi tombstone dưới mọi version ([09-data-model.md](./09-data-model.md) §3, §10) |
| Frequency cap, unread count | INTERNAL | Redis, TTL 48 h / không TTL | | `user.deleted` → `DEL` | |
| Audit ops (principal, tham số) | INTERNAL | Log audit | Theo chính sách log của dự án | — | Tham số có `user_id`, không có email |

## 3. Log, metric, trace

| Kênh | Được | Không được |
|---|---|---|
| **Log ứng dụng** | `delivery_id`, `request_id`, `user_id`, `template`, `lane`, `channel`, `status`, `reason_code`, `provider_message_id`, thời gian | Email (kể cả một phần), `template_data`, nội dung đã render, body request/response của Auth API, `raw` SES chưa hash, OTP/URL reset |
| **Metric (Prometheus)** | Label: `lane`, `channel`, `template`, `provider`, `status`, `reason_code`, `stage` | Label có cardinality theo người: `user_id`, `email_hash`, `subject`, `delivery_id` |
| **Trace** | Span attribute: như log; `traceparent` xuyên từ event tới gọi SES | Như log |
| **Alert / dashboard** | Tổng hợp theo label trên | Danh sách người nhận |

Hai quy tắc kỹ thuật:

- HTTP client tới Auth và SES **tắt log body** ở mọi mức; chỉ log status code, latency, `X-Request-Id`.
- Panic/stack trace không được in struct chứa `recipient`; kiểu dữ liệu chứa email trong code phải có `String()` che giá trị (Go: implement `fmt.Stringer` trả `"<redacted>"`).

## 4. Xoá tài khoản (`USER_DELETED`)

Consumer riêng đọc `t.auth.events`, idempotent theo `user_id`. Trong 24 h:

| Bước | Nơi | Hành động |
|---|---|---|
| 1 | `deleted_user_tombstone` | Dưới advisory lock theo user, insert HMAC(user_id) dưới mọi key version trước mọi xoá (cùng transaction với bước 2–6); ingest check tombstone để replay cũ không tái tạo delivery/inbox, consumer `preferences-updated` check dưới cùng lock để không tạo lại preference |
| 2 | Delivery đang `PENDING`/`RETRY` | `SKIPPED (user_deleted)` — không gửi cho tài khoản đã xoá; Auth `notification-contact` cũng trả `404` nên đây là lớp thứ hai |
| 3 | `user_preference`, `inbox_item` | `DELETE` |
| 4 | `notification_request` | `user_id`, `template_data`, `subject`, `dedup_key`, `source_ref` → `NULL`; giữ `decision`, `decision_reason`, timestamp |
| 5 | `notification_delivery` | `user_id`, `subject`, `dedup_key`, `source_ref` → `NULL`; giữ status/timestamp; ordering key đã là HMAC |
| 6 | `suppression` | `user_id` → `NULL`; giữ `email_hash` |
| 7 | Redis | `DEL contact:{user_id}`, `freq:{user_id}:*`, `inbox:unread:{user_id}` |
| 8 | Outbox | Không phát event mới; outcome đã publish trước đó chỉ có `user_id` — Analytics tự xử lý theo event `USER_DELETED` của Auth |

Kiểm chứng: sau bước 8, `SELECT count(*) FROM notification_request WHERE user_id = ?` và tương tự trên mọi bảng phải bằng 0; replay event cũ chỉ tạo sanitized `SKIPPED (user_deleted)`. Đây là test tích hợp bắt buộc (C2).

## 5. Quyền truy cập

| Ai | Được gì | Không được gì |
|---|---|---|
| Notification worker | Đọc/ghi mọi bảng của Notification; gọi Auth `content` và `notification-contact`; gọi SES | Đọc DB của service khác |
| Support tool (Order admin, finance-ops) | Status API: timeline, status, `reason_code` | `template_data`, email, nội dung |
| `notification-ops` | Ops API: pause, redrive, suppression thủ công (theo `user_id`, không nhập email), replay | Xem email; gỡ `HARD_BOUNCE`/`COMPLAINT` |
| Analytics, User | Outcome event | `template_data`, email |
| Frontend (qua Gateway) | Inbox của **chính user** | Inbox người khác (`404`, không phải `403`, để không lộ tồn tại) |

## 6. Provider (SES)

- SES lưu địa chỉ người nhận trong log của AWS theo chính sách của họ; đây là bên xử lý dữ liệu (data processor) — ghi vào danh sách xử lý dữ liệu của dự án nếu có.
- Configuration set không bật `Open`/`Click` ở phase 1 ([03-tracking.md](./03-tracking.md) §5.3): không theo dõi hành vi đọc.
- Complaint được xử lý trong vòng một chu kỳ Track worker (giây): suppression + tắt marketing. Đây vừa là yêu cầu của SES vừa là quyền của user.
- Suppression list là dữ liệu vận hành, không phải marketing list; không dùng nó để suy ra điều gì về user ngoài "không gửi được".

## 7. Checklist khi thêm template hoặc nguồn mới

Dùng khi review PR vào `contracts/events/notification/v1/templates/`:

- [ ] `template_data` chỉ chứa thứ **hiển thị** trong thông báo; không email, số điện thoại, địa chỉ chi tiết, số thẻ, token.
- [ ] Mọi field PII mức thấp (tên người, địa chỉ rút gọn) có `x-pii: true` trong schema.
- [ ] `additionalProperties: false` — không có chỗ cho field "tiện tay".
- [ ] Nếu là fact mới: có `user_id`? đủ dữ liệu render? Không → producer gửi request (D-07).
- [ ] `max_age` hợp lý với nội dung (thông báo có thời hạn ngắn không được sống lâu trong hàng đợi).
- [ ] `governed_by` đúng lane; critical chỉ dành cho hành động user đang chờ hoặc cảnh báo bảo mật.
- [ ] Không có URL do Notification tự lắp (trừ ngoại lệ `CART_ABANDONED`, `REVIEW_REPLIED` đã ghi ở 07-templates.md §2).
- [ ] Bản render in-app không lộ gì hơn bản email.
- [ ] Không thêm cột PII mới vào bảng nào của Notification; nếu bắt buộc, cập nhật §2 và quy trình §4 trong cùng PR.

## 8. Còn mở

- User Service chưa publish `timezone`/`locale`; Notification dùng mặc định `Asia/Ho_Chi_Minh` / `vi`. Khi User bổ sung, hai field này vào `user_preference` và tuân theo cùng vòng đời.
- Chính sách retention log audit của ops API theo chính sách chung của dự án (chưa có).
- Nếu sau này thêm SMS: số điện thoại phải đi cùng đường với email (resolve lúc gửi, không lưu, hash trong suppression).
