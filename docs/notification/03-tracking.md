# Track: nhận kết quả từ provider

**Trạng thái:** `proposed`

## 1. Vì sao Track khác mọi stage còn lại

Mọi stage trước đều là Notification gọi ra ngoài. Track là **bên ngoài gọi vào**: bạn không kiểm soát thời điểm, thứ tự, số lần, và cả tính xác thực của dữ liệu đến. Nếu chỉ coi Track là "cập nhật status cho đẹp dashboard" sẽ bỏ sót phần quan trọng nhất của nó: bounce và complaint quyết định reputation của toàn bộ hệ thống gửi email.

## 2. Provider báo gì, bằng cách nào

Không phải kênh nào cũng có webhook:

| Kênh / Provider | Cơ chế | Sự kiện |
|---|---|---|
| **Email – SES** | Không gọi thẳng; SES đẩy qua SNS topic → HTTP POST vào endpoint của bạn, hoặc SNS → SQS để bạn poll | `Send`, `Delivery`, `Bounce` (Permanent / Transient), `Complaint`, `Reject`, `Open`, `Click` |
| **Email – SendGrid / Postmark** | HTTP POST trực tiếp, batch nhiều event một request | `delivered`, `bounce`, `dropped`, `spamreport`, `open`, `click`, `unsubscribe` |
| **Push – FCM** | **Không có webhook.** Kết quả nằm trong response của `send()` | `UNREGISTERED` (token chết), `INVALID_ARGUMENT`, `QUOTA_EXCEEDED` |
| **SMS – Twilio** | HTTP POST `StatusCallback` từng message | `queued → sent → delivered / undelivered / failed` |
| **In-app** | Không có provider; frontend là "provider" | `read` do client gọi API |

Vậy Track có hai kiểu đầu vào: **webhook bất đồng bộ** (email, SMS) và **feedback đồng bộ** (push) — loại sau do chính deliver worker xử lý ngay lúc gửi, không cần endpoint riêng. Phần dưới tập trung vào webhook.

## 3. Luồng xử lý: nhận nhanh, xử lý sau

```
Provider ──POST──▶ Gateway ──▶ POST /internal/webhooks/{provider}
                                    │
                                    ├─ 1. verify chữ ký            (~1 ms)
                                    ├─ 2. INSERT provider_event    (~2 ms, append-only, raw JSON)
                                    └─ 3. trả 200 OK               ← tổng < 10 ms

                              Track worker (poll bảng provider_event, cùng pattern deliver worker)
                                    ├─ 4. correlate: provider_message_id → notification_delivery
                                    ├─ 5. chuyển trạng thái theo thứ hạng
                                    ├─ 6. cập nhật suppression list nếu bounce / complaint
                                    └─ 7. publish event nội bộ qua outbox, ghi metric
```

Vì sao tách 1–3 khỏi 4–7: provider có **timeout ngắn và retry hung hãn**. SNS chờ 15 s rồi retry tới 100 lần trong 1 giờ; SendGrid retry tới 24 giờ. Nếu endpoint làm cả correlate + update + publish rồi mới trả 200, một lần DB chậm là provider gửi lại → nhận gấp đôi → DB chậm hơn → vòng xoáy. Endpoint chỉ làm một việc: **ghi nhận đã nhận, rồi trả lời**. Cùng triết lý với ranh giới 1 ở Kafka consumer.

```sql
CREATE TABLE provider_event (
    id                 bigserial PRIMARY KEY,
    provider           text NOT NULL,
    provider_event_id  text NOT NULL,       -- id do provider cấp
    provider_message_id text,               -- để correlate
    event_type         text NOT NULL,       -- delivery | bounce | complaint | open | ...
    occurred_at        timestamptz,
    raw                jsonb NOT NULL,      -- giữ nguyên, là hộp đen để điều tra
    status             text NOT NULL DEFAULT 'PENDING',   -- PENDING | PROCESSED | UNMATCHED | IGNORED
    next_attempt_at    timestamptz NOT NULL DEFAULT now(),
    received_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (provider, provider_event_id)    -- lớp dedup đầu tiên
);
```

## 4. Ví dụ: email xác nhận đơn

| Thời điểm | Sự kiện | Kết quả trên `notification_delivery` |
|---|---|---|
| 14:47:30.000 | Deliver worker gọi SES, nhận `MessageId = 0100018a-...`; `UPDATE status = SENT, provider_message_id = ...` | `SENT` |
| 14:47:31.200 | SES → SNS → endpoint: `Send`. Verify, ghi `provider_event`, trả 200 | — |
| 14:47:34.800 | Gmail nhận thư. SES gửi `Delivery`. Ghi, trả 200 | — |
| 14:47:35 | Track worker nhặt `Send`: đã `SENT`, không đổi. Nhặt `Delivery`: `SENT → DELIVERED`, ghi `delivered_at`; outbox `notification.delivered.v1` | `DELIVERED` |
| 15:12:08 | User mở email. `Open` → ghi `first_opened_at`, **không đổi status** | `DELIVERED` |
| 15:12:08 | Mở lần nữa trên máy khác. `Open` với `provider_event_id` khác → `open_count += 1`, không publish gì | `DELIVERED` |

Toàn bộ chuỗi nằm trên một row delivery và bốn row `provider_event`. Ops tra được bằng một câu SQL.

## 5. Ba điều làm Track khó hơn vẻ ngoài

### 5.1 Sự kiện đến sai thứ tự, và đến trước cả khi bạn kịp ghi

Webhook không có thứ tự đảm bảo: `Delivery` có thể đến trước `Send`; `Bounce` có thể đến sau `Delivered` (mail server nhận rồi mới từ chối — *delayed bounce*). Giải pháp là **state machine có thứ hạng**, không phải "ghi đè bằng cái mới nhất":

```
SENT (1) → DELIVERED (2) → BOUNCED (3) / COMPLAINED (3)
```

Quy tắc: chỉ chuyển sang trạng thái có hạng **cao hơn hoặc bằng**. Event hạng thấp đến muộn chỉ ghi timestamp, không đổi status. `Delivery` đến trước `Send` → nhảy thẳng `DELIVERED`; `Send` đến sau bị bỏ qua. `Bounce` đến sau `Delivered` → vẫn nhận, vì bounce là thông tin quan trọng hơn.

Race tinh tế hơn: provider có thể gửi `Send` **trước khi** deliver worker kịp commit `UPDATE ... provider_message_id = ...` (cửa sổ vài chục ms). Track worker correlate không thấy row nào. Không được bỏ event — đánh `UNMATCHED`, `next_attempt_at = now + 30 s`, thử lại; sau 3 lần vẫn không khớp thì alert (email gửi ngoài hệ thống, hoặc bug). Đây là lý do `provider_event` là bảng có worker poll, không xử lý trong endpoint.

### 5.2 Bounce và complaint không phải trạng thái — chúng là hành động

Hai sự kiện này thay đổi hành vi **của những lần gửi sau**:

| Sự kiện | Hành động | Phạm vi |
|---|---|---|
| **Hard bounce** (địa chỉ không tồn tại) | Thêm vào `suppression (address, reason, source, until)` với `until = NULL` | Mọi lane trừ critical — user chủ động xin OTP thì vẫn thử |
| **Soft bounce** (hộp thư đầy, server tạm lỗi) | Suppression tạm 24–72 h; có thể lên lịch retry cho chính delivery đó | Bulk, transactional |
| **Complaint** (user bấm "báo spam") | Suppression vĩnh viễn; đồng thời **tắt marketing preference** của user (publish event cho User Service, hoặc ghi vào bản replicate cục bộ) | Mọi lane trừ critical |

Không làm điều này là vi phạm điều khoản của provider: complaint rate > 0,1 % là account bị xem xét, > 0,5 % là bị chặn gửi — chặn cả OTP. Track worker do đó cần quyền ghi suppression list và cần publish event; nó không phải "chỉ cập nhật status".

Stage Decide ([02-pipeline.md](./02-pipeline.md) §2) đọc suppression list trước khi tạo delivery.

### 5.3 Volume của Track có thể lớn hơn Deliver

Một email sinh 2–3 event (`Send`, `Delivery`, có thể `Open`); chiến dịch marketing sinh 5–10 event/email vì `Open`/`Click` lặp lại (mỗi lần preview trong Gmail là một `Open`). 300k email → 1–3 triệu webhook trong vài giờ. Hai quyết định:

- **Không bật `Open`/`Click`** cho lane bulk, hoặc chỉ bật theo tỷ lệ mẫu. Giá trị nghiệp vụ nằm ở `Delivery`/`Bounce`/`Complaint`; open tracking ngày càng vô nghĩa vì Apple Mail Privacy Protection tự "mở" mọi email.
- Với SES, chọn **SNS → SQS → worker poll** thay vì SNS → HTTP. SQS hấp thụ burst; endpoint HTTP không còn là điểm nghẽn. Đánh đổi: thêm một hàng đợi phải theo dõi.

## 6. Bảo mật: cửa vào từ Internet

Endpoint webhook là endpoint public duy nhất của Notification (ngoài in-app API). Ba việc bắt buộc:

1. **Xác thực chữ ký theo từng provider.** SNS ký bằng certificate — phải tải certificate từ đúng domain `sns.*.amazonaws.com`, không tin URL trong payload. SendGrid: ECDSA với public key từ dashboard. Twilio: HMAC-SHA1 trên URL + body. Verify fail → 401 và **không ghi gì**.
2. **Chống replay.** Từ chối event có timestamp lệch quá 5–10 phút, cộng unique constraint trên `provider_event_id`.
3. **Payload là dữ liệu, không phải lệnh.** Không dùng địa chỉ email, URL, hay message id trong webhook để gọi tiếp hệ thống khác khi chưa đối chiếu với row nội bộ. Chỉ suppress địa chỉ **nếu** nó khớp `recipient` của delivery mà `provider_message_id` trỏ tới — nếu không, một webhook có chữ ký hợp lệ nhưng nội dung bị thao túng có thể chặn email của người khác.

Về routing: Gateway hiện chỉ mở một route webhook cho Payment (Stripe). Notification cần route tương tự — `/webhooks/notification/{provider}` — với cùng chính sách: không qua auth user, rate limit riêng, chỉ Notification nhận. Cần ghi vào `docs/gateway/` khi triển khai.

## 7. Kênh không có webhook

- **Push (FCM)**: feedback nằm trong response. `UNREGISTERED` nghĩa là token đã chết (user gỡ app) → deliver worker vô hiệu token ngay, không chờ Track. Hệ quả: bảng device token nên thuộc Notification, hoặc Notification có quyền ghi vào nó.
- **In-app**: "delivered" là lúc row được ghi vào inbox; "read" là khi frontend gọi `POST /notifications/{id}/read`. Vẫn cần idempotent (user bấm hai lần) và không làm sai `unread_count` — cập nhật count trong Redis bằng thao tác nguyên tử cùng lúc với UPDATE row.

## 8. Tóm lại

Track = một endpoint cực mỏng (verify → ghi thô → 200) cộng một worker poll bảng `provider_event` — **cùng pattern với deliver worker**, ngược chiều dữ liệu. Ba việc worker phải làm đúng: correlate chịu được race, chuyển trạng thái theo thứ hạng thay vì theo thời gian đến, và biến bounce/complaint thành suppression để bảo vệ reputation của cả hệ thống.
