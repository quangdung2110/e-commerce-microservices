# Track: nhận kết quả từ provider

**Trạng thái:** `proposed`

## 1. Vì sao Track khác mọi stage còn lại

Mọi stage trước đều là Notification gọi ra ngoài. Track là **bên ngoài gọi vào**: bạn không kiểm soát thời điểm, thứ tự, số lần, và cả tính xác thực của dữ liệu đến. Nếu chỉ coi Track là "cập nhật status cho đẹp dashboard" sẽ bỏ sót phần quan trọng nhất của nó: bounce và complaint quyết định reputation của toàn bộ hệ thống gửi email.

## 2. Provider báo gì, bằng cách nào

Phase 1 có hai kênh (D-08) và một provider email là **Amazon SES** (D-09). Bảng dưới giữ cả các kênh/provider chưa dùng để thấy pattern chung và để thêm sau không phải thiết kế lại:

| Kênh / Provider | Cơ chế | Sự kiện | Phase 1 |
|---|---|---|---|
| **Email – SES** | Không gọi thẳng; SES → SNS topic → **SQS queue** để Notification poll (cũng có thể SNS → HTTP, không dùng) | `Send`, `Delivery`, `Bounce` (Permanent / Transient), `Complaint`, `Reject`, `DeliveryDelay`, `Open`, `Click` | **Dùng** |
| **Email – Mailpit** (dev/CI) | Không có event; test đọc hộp thư qua REST API của Mailpit | — | Dùng cho dev |
| **In-app** | Không có provider; frontend là "provider" | `read` do client gọi API | **Dùng** |
| Email – SendGrid / Postmark | HTTP POST trực tiếp, batch nhiều event một request | `delivered`, `bounce`, `dropped`, `spamreport`, ... | Chưa |
| Push – FCM | **Không có webhook.** Kết quả nằm trong response của `send()` | `UNREGISTERED`, `INVALID_ARGUMENT`, `QUOTA_EXCEEDED` | Chưa |
| SMS – Twilio | HTTP POST `StatusCallback` từng message | `queued → sent → delivered / failed` | Chưa |

Track có hai kiểu đầu vào: **event bất đồng bộ** (email qua SQS) và **feedback đồng bộ** (push, sau này) — loại sau do deliver worker xử lý ngay lúc gửi. Phần dưới tập trung vào đường SES.

## 3. Luồng xử lý: nhận nhanh, xử lý sau

Với SES, chọn **SNS → SQS → worker poll** thay vì SNS → HTTP endpoint:

```
SES ──event──▶ SNS topic ──▶ SQS queue (ses-events)
                                  │
                       SQS poller (long-poll, batch 10)
                                  ├─ 1. verify chữ ký SNS           (~1 ms)
                                  ├─ 2. INSERT provider_event       (~2 ms, append-only, raw JSON)
                                  └─ 3. DeleteMessage khỏi SQS      ← chưa INSERT xong thì không xoá

                       Track worker (poll bảng provider_event, cùng pattern deliver worker)
                                  ├─ 4. correlate: provider_message_id → notification_delivery
                                  ├─ 5. chuyển trạng thái theo thứ hạng
                                  ├─ 6. cập nhật suppression list nếu bounce / complaint
                                  └─ 7. publish event nội bộ qua outbox, ghi metric
```

Vì sao SQS thay vì HTTP endpoint: SQS **hấp thụ burst thay bạn** (1–3 triệu event sau một chiến dịch), giữ message tới 14 ngày nếu Notification đang down, và Notification không cần mở thêm endpoint public nào cho email. Đánh đổi: thêm một queue phải theo dõi (`ApproximateAgeOfOldestMessage`), và phụ thuộc AWS — chấp nhận được vì đã dùng SES.

Vì sao vẫn tách 1–3 khỏi 4–7 dù đã có SQS: poller chỉ nên làm việc nhanh để giữ nhịp xả SQS ổn định; correlate/suppression/outbox có thể chậm hoặc cần retry riêng. Cùng triết lý với ranh giới 1 ở Kafka consumer. Nếu sau này thêm provider dùng HTTP webhook (SendGrid, Twilio), endpoint đó cũng chỉ làm 1–3 rồi trả 200, vì provider có **timeout ngắn và retry hung hãn** (SNS-HTTP chờ 15 s rồi retry tới 100 lần; SendGrid retry tới 24 giờ).

```sql
CREATE TABLE provider_event (                -- schema đầy đủ: 09-data-model.md §5
    id                 bigint GENERATED ALWAYS AS IDENTITY,
    provider           text NOT NULL,
    provider_event_id  text NOT NULL,        -- SES không cấp id → sha256(messageId ‖ eventType ‖ timestamp)
    provider_message_id text,                -- để correlate
    event_type         text NOT NULL,        -- send | delivery | bounce | complaint | reject | delivery_delay
    occurred_at        timestamptz,
    raw                jsonb NOT NULL,       -- hộp đen để điều tra, NHƯNG mọi địa chỉ email đã thay bằng sha256 trước khi ghi
    status             text NOT NULL DEFAULT 'PENDING',   -- PENDING | PROCESSED | UNMATCHED | IGNORED
    attempt            int  NOT NULL DEFAULT 0,
    next_attempt_at    timestamptz NOT NULL DEFAULT now(),
    received_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id, received_at)            -- partition theo tháng
) PARTITION BY RANGE (received_at);
-- Dedup: sổ provider_event_dedup (provider, provider_event_id), ghi cùng transaction, giữ 14 ngày
```

`raw` của SES chứa `mail.destination[]` và `bouncedRecipients[].emailAddress`. Poller **hash các field đó trước khi INSERT**; bản gốc không bao giờ chạm DB. Correlate không cần địa chỉ (dùng `provider_message_id`), suppression dùng hash — [08-decide.md](./08-decide.md) §4.

## 4. Ví dụ: email xác nhận đơn

| Thời điểm | Sự kiện | Kết quả trên `notification_delivery` |
|---|---|---|
| 14:47:30.000 | Deliver worker gọi SES, nhận `MessageId = 0100018a-...`; `UPDATE status = SENT, provider_message_id = ...` | `SENT` |
| 14:47:31.200 | SES → SNS → SQS: `Send`. Poller verify, ghi `provider_event`, xoá khỏi queue | — |
| 14:47:34.800 | Gmail nhận thư. SES gửi `Delivery`. Poller ghi, xoá khỏi queue | — |
| 14:47:35 | Track worker nhặt `Send`: đã `SENT`, không đổi. Nhặt `Delivery`: `SENT → DELIVERED`, ghi `delivered_at`; outbox `notification.delivered.v1` | `DELIVERED` |
| 15:12:08 | User mở email. **Không có event**: phase 1 không bật `Open`/`Click` ở configuration set nào (§5.3) | `DELIVERED` |

Toàn bộ chuỗi nằm trên một row delivery và hai row `provider_event`. Ops tra được bằng một câu SQL.

## 5. Ba điều làm Track khó hơn vẻ ngoài

### 5.1 Sự kiện đến sai thứ tự, và đến trước cả khi bạn kịp ghi

Webhook không có thứ tự đảm bảo: `Delivery` có thể đến trước `Send`; `Bounce` có thể đến sau `Delivered` (mail server nhận rồi mới từ chối — *delayed bounce*). Giải pháp là **state machine có thứ hạng**, không phải "ghi đè bằng cái mới nhất":

```
SENT (1) → DELIVERED (2) → BOUNCED (3) / COMPLAINED (3)
```

Quy tắc: chỉ chuyển sang trạng thái có hạng **cao hơn hoặc bằng**. Event hạng thấp đến muộn chỉ ghi timestamp, không đổi status. `Delivery` đến trước `Send` → nhảy thẳng `DELIVERED`; `Send` đến sau bị bỏ qua. `Bounce` đến sau `Delivered` → vẫn nhận, vì bounce là thông tin quan trọng hơn.

Race tinh tế hơn: provider có thể gửi `Send` **trước khi** deliver worker kịp commit `UPDATE ... provider_message_id = ...` (cửa sổ vài chục ms). Track worker correlate không thấy row nào. Không được bỏ event — đánh `UNMATCHED`, `next_attempt_at = now + 30 s × min(attempt, 10)`, thử lại; sau 3 lần vẫn không khớp thì alert (`provider_event_unmatched_age_seconds`; email gửi ngoài hệ thống, hoặc bug) nhưng **vẫn tiếp tục thử** với khoảng giãn tới 5 phút; quá 24 h kể từ khi nhận thì `IGNORED` (R-09). Đây là lý do `provider_event` là bảng có worker poll, không xử lý trong endpoint.

Ánh xạ event → hành động của Track worker:

| Event | Trạng thái delivery | Suppression | Outbox |
|---|---|---|---|
| `Send` | `→ SENT` (theo hạng; thường không đổi) | — | — |
| `Delivery` | `→ DELIVERED` | — | `notification.delivered.v1` khi status đổi |
| `Bounce` Permanent | `→ BOUNCED` | `HARD_BOUNCE` vĩnh viễn (lane ≠ critical) | `notification.bounced.v1` |
| `Bounce` Transient | không đổi | `SOFT_BOUNCE` tới `now + 48 h` (lane ≠ critical) | `notification.bounced.v1` (`transient`) |
| `Complaint` | **không đổi** (thường đến sau `DELIVERED`) | `COMPLAINT` vĩnh viễn (lane ≠ critical) + `marketing_enabled = false` cục bộ | `notification.complaint-received.v1` |
| `Reject` (SES từ chối sau khi accept, ví dụ virus) | `→ BOUNCED` (ghi chú `reject`) | không | `notification.bounced.v1` (`permanent`, `suppressed = false`) |
| `DeliveryDelay`, khác | không đổi | — | — (`PROCESSED`, không hành động) |

### 5.2 Bounce và complaint không phải trạng thái — chúng là hành động

Hai sự kiện này thay đổi hành vi **của những lần gửi sau**:

| Sự kiện | Hành động | Phạm vi |
|---|---|---|
| **Hard bounce** (địa chỉ không tồn tại) | Thêm vào `suppression (user_id, email_hash, reason, source, until)` với `until = NULL`; `user_id` lấy từ delivery đã correlate, `email_hash = sha256(lower(email))` — không lưu email rõ | Mọi lane trừ critical — user chủ động xin OTP thì vẫn thử |
| **Soft bounce** (hộp thư đầy, server tạm lỗi) | Suppression tạm 24–72 h; có thể lên lịch retry cho chính delivery đó | Bulk, transactional |
| **Complaint** (user bấm "báo spam") | Suppression vĩnh viễn; đồng thời **tắt marketing preference** của user (publish event cho User Service, hoặc ghi vào bản replicate cục bộ) | Mọi lane trừ critical |

Không làm điều này là vi phạm điều khoản của provider: complaint rate > 0,1 % là account bị xem xét, > 0,5 % là bị chặn gửi — chặn cả OTP. Track worker do đó cần quyền ghi suppression list và cần publish event; nó không phải "chỉ cập nhật status".

Stage Decide ([02-pipeline.md](./02-pipeline.md) §2) đọc suppression list trước khi tạo delivery.

### 5.3 Volume của Track có thể lớn hơn Deliver

Một email sinh 2–3 event (`Send`, `Delivery`, có thể `Open`); chiến dịch marketing sinh 5–10 event/email vì `Open`/`Click` lặp lại (mỗi lần preview trong Gmail là một `Open`). 300k email → 1–3 triệu webhook trong vài giờ. Hai quyết định:

- **Không bật `Open`/`Click`** ở phase 1 cho mọi lane; configuration set chỉ bật `Send`, `Delivery`, `Bounce`, `Complaint`, `Reject`, `DeliveryDelay`. Muốn đo open cho transactional sau này thì thêm cột `first_opened_at`/`open_count` vào `notification_delivery` và bật riêng configuration set đó. Giá trị nghiệp vụ nằm ở `Delivery`/`Bounce`/`Complaint`; open tracking ngày càng vô nghĩa vì Apple Mail Privacy Protection tự "mở" mọi email.
- Dùng **hai SES configuration set** (`notification-critical-transactional`, `notification-bulk`) trỏ về cùng SNS topic nhưng bật tập event khác nhau; configuration set name được gắn vào mỗi email lúc gửi nên Track cũng biết event thuộc lane nào mà không cần correlate trước.

## 6. Bảo mật

Ở phase 1, đường SES → SNS → SQS **không mở endpoint public nào**: Notification là bên chủ động poll SQS bằng IAM credential. Ba việc vẫn bắt buộc:

1. **Xác thực chữ ký SNS** trên từng message lấy từ SQS — tải certificate từ đúng domain `sns.*.amazonaws.com`, không tin URL trong payload. Verify fail → ghi log, xoá khỏi queue, **không INSERT**.
2. **Chống replay.** Từ chối event có timestamp lệch quá 5–10 phút, cộng unique constraint trên `provider_event_id` (SES `mail.messageId` + `eventType` + timestamp, vì SES không cấp event id riêng).
3. **Payload là dữ liệu, không phải lệnh.** Không dùng địa chỉ email hay message id trong event để gọi tiếp hệ thống khác khi chưa đối chiếu với row nội bộ. Chỉ suppress địa chỉ **nếu** nó khớp delivery mà `provider_message_id` trỏ tới — Notification không lưu địa chỉ người nhận, nên phép đối chiếu là: tag `delivery_id` mà worker gắn vào email lúc gửi (`mail.tags.delivery_id`) phải bằng `id` của delivery đã correlate (khi SES không trả tag, chấp nhận correlate theo `messageId`). Nếu không, một event có chữ ký hợp lệ nhưng nội dung bị thao túng có thể chặn email của người khác. Chứng cứ giữ lại là `email_hash`, không phải địa chỉ.

Khi thêm provider dùng HTTP webhook (phase sau), Gateway cần route `/webhooks/notification/{provider}` với chính sách như Stripe webhook của Payment: không qua auth user, rate limit riêng, chỉ Notification nhận, và endpoint verify chữ ký theo provider (SendGrid: ECDSA; Twilio: HMAC-SHA1).

## 7. Kênh không có webhook

- **In-app** (phase 1): "delivered" là lúc row được ghi vào inbox; "read" là khi frontend gọi `POST /notifications/{id}/read`. Vẫn cần idempotent (user bấm hai lần) và không làm sai `unread_count` — cập nhật count trong Redis bằng thao tác nguyên tử cùng lúc với UPDATE row. Không có RPC nào trên đường gửi in-app, nên kênh này vẫn hoạt động khi SES hoặc Auth API sự cố.
- **Push (FCM)** (phase sau): feedback nằm trong response. `UNREGISTERED` nghĩa là token đã chết (user gỡ app) → deliver worker vô hiệu token ngay, không chờ Track. Hệ quả: bảng device token nên thuộc Notification, hoặc Notification có quyền ghi vào nó.

## 8. Tóm lại

Track = một poller cực mỏng (verify → ghi thô → xoá khỏi SQS) cộng một worker poll bảng `provider_event` — **cùng pattern với deliver worker**, ngược chiều dữ liệu. Ba việc worker phải làm đúng: correlate chịu được race, chuyển trạng thái theo thứ hạng thay vì theo thời gian đến, và biến bounce/complaint thành suppression để bảo vệ reputation của cả hệ thống.
