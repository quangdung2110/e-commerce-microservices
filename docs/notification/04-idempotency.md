# Idempotency và "exactly-once effect"

**Trạng thái:** `proposed`

## 1. Vì sao exactly-once là bất khả thi

Bất kỳ lúc nào làm hai việc trên hai hệ thống khác nhau — *gửi email qua provider* và *ghi "đã gửi" vào DB của mình* — luôn tồn tại khe hở giữa hai việc đó, và process có thể chết đúng trong khe hở:

```
gọi provider ──▶ provider nhận, gửi đi ──▶ [process chết] ──▶ ghi DB "SENT"  ← không bao giờ xảy ra
```

Khởi động lại, không có cách nào biết provider đã nhận hay chưa. Chỉ có hai lựa chọn: **gửi lại** (rủi ro trùng — *at-least-once*) hoặc **không gửi** (rủi ro mất — *at-most-once*). Không có transaction phân tán nào bao được cả provider lẫn Postgres của bạn.

Mục tiêu đúng không phải "xử lý đúng một lần" mà là **hậu quả nhìn từ user như thể đã xử lý đúng một lần** — *exactly-once effect*:

> **at-least-once delivery + idempotent processing = exactly-once effect**

Chấp nhận message đến nhiều lần; thiết kế sao cho lần thứ 2, 3, n **không tạo thêm hậu quả**. Phần còn lại trả lời: idempotent ở đâu, bằng khoá nào.

## 2. Trùng đến từ đâu: sáu nguồn, mỗi nguồn một khoá

Đặt một bảng dedup ở đầu vào rồi coi là xong là sai lầm phổ biến: trùng phát sinh ở nhiều điểm dọc pipeline, và mỗi điểm cần khoá khác nhau.

| # | Nguồn trùng | Ví dụ trong dự án | Nhận diện bằng |
|---|---|---|---|
| 1 | **Producer retry** | Auth publish 3 lần cùng CloudEvent `id` khi Kafka timeout | `(source, event_id)` |
| 2 | **Kafka redelivery** | Consumer INSERT xong, chết trước khi commit offset | `(source, event_id)` — cùng khoá #1 |
| 3 | **Cùng một sự thật, nhiều event khác id** | Producer dual-publish `.v1` và `.v2` khi migrate; Order phát lại request `ORDER_CONFIRMED` với id mới sau khi sửa bug outbox; Order phát request `ORDER_REFUNDED` hai lần với id khác cho cùng refund | Khoá nghiệp vụ, ví dụ `order_confirmed:{order_id}` |
| 4 | **Lease hết hạn ở deliver worker** | Worker gọi provider xong, chết trước `mark_sent`; reaper trả về `RETRY` | `delivery.id` + compare-and-set + chính sách theo lane (SES không có idempotency key) |
| 5 | **Webhook provider retry** | SNS gửi lại `Delivery` vì endpoint trả 200 chậm | `(provider, provider_event_id)` |
| 6 | **Replay có chủ đích** | Ops reset offset về 6 giờ trước sau sự cố | Tất cả khoá trên + cửa sổ dedup đủ dài |

**#1 và #2 chỉ chặn được vì Auth dùng lại cùng `id` khi retry.** Nếu producer sinh id mới mỗi lần retry, lớp dedup kỹ thuật biến mất và chỉ còn lớp nghiệp vụ (#3). Đây là lý do `contracts/conventions/events.md` bắt buộc retry với cùng CloudEvent id — điều kiện tiên quyết để consumer idempotent được.

## 3. Bốn lớp dọc pipeline

### Lớp A — Ranh giới Kafka → DB: dedup kỹ thuật bằng unique constraint

```sql
CREATE TABLE ingest_dedup (
    source       text NOT NULL,      -- CloudEvents source
    event_id     text NOT NULL,      -- CloudEvents id
    received_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (source, event_id)
);
```

```
onMessage(event):
    BEGIN
      INSERT INTO ingest_dedup (source, event_id) ON CONFLICT DO NOTHING
          → 0 row ⇒ đã xử lý: COMMIT, commit offset, return
      INSERT INTO notification_request (...)
      ... decide, INSERT delivery ...
    COMMIT
    commit offset
```

Ba điểm bắt buộc:

- **Unique constraint, không "SELECT xem có chưa rồi INSERT".** Hai consumer instance (sau rebalance) có thể cùng SELECT thấy trống rồi cùng INSERT. Để DB làm trọng tài.
- **INSERT trước, commit offset sau.** Ngược lại là at-most-once. Thứ tự INSERT → commit cho at-least-once, unique constraint biến nó thành exactly-once effect.
- **Bản ghi dedup nằm trong cùng DB, cùng transaction với row nghiệp vụ** — không phải Redis (mất khi restart hoặc hết TTL sớm hơn Kafka retention → trùng đúng lúc sự cố), không phải một service khác. Nó là bảng `ingest_dedup` riêng thay vì unique trên chính `notification_request` chỉ vì Postgres không cho unique constraint thiếu partition key trên bảng partition ([09-data-model.md](./09-data-model.md) §1); về mặt nguyên tử thì hai bảng trong một transaction tương đương một bảng. Cửa sổ giữ 30 ngày ≥ 2× Kafka retention dài nhất.

### Lớp B — Stage Decide: dedup nghiệp vụ, và ranh giới với "không phải trùng"

Lớp tinh tế nhất vì **không phải kỹ thuật thuần tuý** — là chính sách theo từng template. Ai sinh `dedup_key` phụ thuộc loại đầu vào (D-07):

- **Request**: producer gửi kèm trong payload, vì producer biết ngữ nghĩa nghiệp vụ (Order biết `order_confirmed:{order_id}` là "một đơn xác nhận một lần"). Template có thể khai báo `dedup_key` là bắt buộc hay tuỳ chọn.
- **Fact**: bảng ánh xạ fact → template ([02-pipeline.md](./02-pipeline.md) §2.2) sinh ra từ các field của fact.

| Template | Nguồn | `dedup_key` | Lý do |
|---|---|---|---|
| `ORDER_CONFIRMED` | Request (Order) | `order_confirmed:{order_id}` | Một đơn xác nhận một lần, kể cả khi Order publish lại request |
| `ORDER_PAYMENT_FAILED` | Request (Order) | `order_payment_failed:{order_id}:{attempt}` | Mỗi lần thanh toán thất bại là một thông báo; cùng attempt thì không |
| `REVIEW_REPLIED` | Request (Review) | `review_replied:{review_id}` | Contract Review: "chỉ sau reply chính thức đầu tiên" |
| OTP / password reset | Request (Auth) | **không có** | Mỗi lần user bấm "gửi lại" là một OTP **mới**, phải gửi |
| `CART_ABANDONED` | Fact (Cart) | `cart_abandoned:{cart_id}:{abandonment_cycle}` | Một chu kỳ bỏ giỏ nhắc một lần; chu kỳ mới nhắc lại |

Sổ `delivery_dedup` với khoá **`(dedup_key, channel)`** — ghi cùng transaction với `notification_delivery` lúc Decide — chặn ở tầng DB. Khoá là cặp vì một request sinh email + in-app cùng `dedup_key` ([08-decide.md](./08-decide.md) §3 bước 12).

OTP là điểm dễ nhầm: hai event OTP cách nhau 30 s có **id khác nhau** (lớp A cho qua — đúng) và **không có dedup_key** (lớp B cho qua — đúng). Cả hai được gửi. Nếu đặt `dedup_key = otp:{user_id}`, nút "gửi lại" của user không bao giờ hoạt động. Muốn chặn user spam "gửi lại" 20 lần thì đó là **rate limiting** (`tối đa 3 OTP / 10 phút / user`) — cơ chế khác, hậu quả khác (từ chối có chủ đích), không phải idempotency.

> Idempotency trả lời "đây có phải cùng một yêu cầu không?". Rate limiting trả lời "yêu cầu này có được phép không?". Trộn hai cái là nguồn của nhiều bug khó hiểu.

### Lớp C — Ranh giới DB → Provider: khe hở không đóng được, chỉ thu hẹp

Khe hở nằm giữa `provider.send()` thành công và `UPDATE status = SENT`. Ba công cụ, dùng kết hợp:

**C1 — Idempotency key phía provider.** Truyền `delivery.id` cho provider trong mọi lần gọi; provider lưu key và trả kết quả cũ nếu thấy lại — trùng bị chặn *ở phía họ*. Sạch nhất, nhưng **hầu hết email provider phổ biến không hỗ trợ**: SES, SendGrid, Postmark, Mailgun đều không có idempotency key ở API gửi (Resend có; Stripe có nhưng không phải email). Twilio SMS cũng không.

Với **SES đã chọn (D-09), C1 không khả dụng**. Hệ quả: lớp C với email dựa hoàn toàn vào C2 + C3 dưới đây. Vẫn nên gắn `delivery.id` vào email qua header `X-Notification-Delivery-Id` / SES message tag để Track correlate và để điều tra trùng sau này, nhưng đó là truy vết, không phải chặn.

**C2 — Compare-and-set khi ghi trạng thái.**

```sql
UPDATE notification_delivery
SET    status = 'SENT', provider_message_id = ?, updated_at = now()
WHERE  id = ? AND status = 'SENDING' AND locked_by = ?;
-- affected rows = 0 → reaper + worker khác đã xử lý row này rồi
```

Nếu 0 row, worker biết mình đã thua cuộc đua và không làm gì thêm: không publish event, không tăng metric. Không ngăn được email trùng đã đi, nhưng ngăn **hậu quả dây chuyền** trong hệ thống: 2 event `notification.sent`, 2 lần cập nhật analytics, 2 dòng timeline.

**C3 — Chính sách theo lane khi lease hết hạn.** Reaper ghi `last_error_code = 'lease_expired'` khi trả row về `RETRY`; worker claim thấy mã đó là biết row *đã từng được thử* (không suy từ `attempt`, vì `attempt` còn bị `release` trừ lại khi breaker/rate-limit từ chối):

| Lane | Hành động | Lý do |
|---|---|---|
| Bulk | Gửi lại | Promo trùng là phiền, không phải sự cố |
| Transactional | Gửi lại, đánh `possibly_duplicate = true` (SES không có idempotency key nên không chặn được ở phía provider) | Xác nhận đơn trùng chấp nhận được, nhưng cần đo được tỷ lệ; metric `possibly_duplicate_rate` là chỉ số sức khoẻ của lease/heartbeat |
| Critical (OTP) | **Không gửi lại**, đánh `FAILED (lease_expired)` | OTP thứ hai gây nhầm hơn là không có; user tự bấm "gửi lại" và đó là OTP mới, sạch |
| In-app (mọi lane) | Gửi lại an toàn | Ghi inbox là thao tác trên DB của chính Notification → nằm trong cùng transaction với `mark_sent`, không có khe hở |

Cùng một cơ chế kỹ thuật, các hậu quả khác nhau — vì "hậu quả nhìn từ user" khác nhau theo lane và kênh.

### Lớp D — Event Notification tự phát ra: outbox và id sinh sớm

Notification publish `notification.delivered.v1`, `notification.bounced.v1` cho Analytics/User. Để consumer phía họ dedup được, phải làm đúng điều đòi hỏi ở Auth: **cùng sự kiện → cùng id, kể cả khi publish lại**. Cách duy nhất là outbox:

```sql
BEGIN;
  UPDATE notification_delivery SET status = 'DELIVERED' WHERE id = ? AND status = 'SENT';
  INSERT INTO outbox (event_id, type, aggregate_id, payload)
  VALUES (gen_random_uuid(), 'notification.delivered.v1', ?, ?);
COMMIT;
-- publisher riêng đọc outbox, publish, đánh dấu published.
-- Chết giữa chừng → publish lại CÙNG event_id.
```

`event_id` sinh **lúc INSERT vào outbox**, không phải lúc publish. Sinh lúc publish thì mỗi retry là một id mới và toàn bộ dedup downstream sụp đổ. Review Service đã làm đúng cách này (ADR-003 outbox CDC); Notification theo cùng pattern.

## 4. Ví dụ: OTP đi qua cả bốn lớp khi mọi thứ cùng xui

1. Auth publish OTP, Kafka timeout, retry → **3 bản sao cùng `id = evt-771`** trên topic critical.
2. Consumer nhặt bản 1, INSERT thành công, **chết trước commit offset**.
3. Consumer mới nhận lại cả 3: bản 1 `ON CONFLICT DO NOTHING`, bản 2 và 3 cũng vậy. Commit. **Lớp A: 3 message → 1 row.**
4. Decide: template `otp` không có dedup_key → 1 delivery. **Lớp B: cho qua, đúng.**
5. Deliver worker claim, gọi provider thành công, **chết trước `mark_sent`**. 60 s sau reaper trả về `RETRY`, `attempt = 2`.
6. Worker khác nhặt: lane critical, `last_error_code = lease_expired` → **không gửi lại**, đánh `FAILED (lease_expired)`. **Lớp C3.**
7. Webhook `Delivery` cho email đã gửi ở bước 5 đến hai lần (SNS retry). Unique `provider_event_id` chặn bản 2. Track worker thấy row `FAILED (lease_expired)` nhưng có `Delivery` từ provider → chuyển `DELIVERED` (hạng cao hơn), ghi chú `recovered by webhook`. **Lớp D + state ranking.**

Kết quả: user nhận **đúng 1 email**, trạng thái cuối cùng đúng, không event downstream nào bị nhân đôi — dù 5 điểm hỏng xảy ra liên tiếp. Thiếu bất kỳ lớp nào, con số là 2, 3 hoặc 4.

## 5. Cửa sổ dedup phải dài bao nhiêu

Khoá dedup phải sống **ít nhất bằng khoảng thời gian dài nhất mà một bản sao có thể quay lại**. Với Kafka đó là retention của topic (24 h với topic Auth, 7–14 ngày với topic khác), vì ops có thể replay bất kỳ lúc nào trong khoảng đó.

Sổ `ingest_dedup` giữ 30 ngày (≥ 2× retention 14 ngày) → thoả mãn; `notification_request` giữ 90 ngày cho điều tra. Với replay cũ hơn 90 ngày (backfill, rất hiếm), cần cờ vận hành `replay_mode` bắt consumer bỏ qua mọi event có `time` cũ hơn X ngày, thay vì gửi lại email của 4 tháng trước.

## 6. Cách làm sai hay gặp

- **Dedup bằng hash payload.** Hai OTP hợp lệ khác nhau có payload gần giống → bị coi là trùng và mất. Ngược lại, retry đóng gói lại với `time` mới → hash khác → không phát hiện. Luôn dedup bằng **định danh**, không bằng nội dung.
- **Một bảng dedup toàn cục.** Trộn khoá kỹ thuật (event id) và khoá nghiệp vụ (order_id) vào một cột `key text` → không ai hiểu row nào chặn cái gì, và không có chính sách khác nhau theo template.
- **Check ở tầng ứng dụng thay vì constraint.** Race condition xuất hiện đúng lúc rebalance, tức đúng lúc tải cao.
- **Coi retry là hiếm nên không test.** Retry, rebalance, lease hết hạn là **đường đi bình thường** dưới tải. Test tích hợp bắt buộc có: publish cùng event 3 lần, kill worker giữa chừng, gửi webhook trùng — assert kỹ thuật dedup đúng và khe hở provider cho kết quả đúng theo lane; transactional không được giả vờ luôn chỉ có một email.
- **Idempotent xử lý nhưng không idempotent side-effect.** Row không trùng, nhưng metric `emails_sent` tăng 2 hoặc outbox ghi 2 lần vì nằm ngoài transaction. Mọi side-effect nằm **cùng transaction** với chuyển trạng thái, hoặc được gác bằng compare-and-set.

## 7. Tóm lại

| Lớp | Vị trí | Khoá | Cơ chế |
|---|---|---|---|
| A | Kafka → DB | `(source, event_id)` | PK trong `ingest_dedup`, cùng transaction; INSERT trước commit sau |
| B | Decide | `(dedup_key, channel)` theo template | PK trong `delivery_dedup`; OTP cố ý không có key |
| C | DB → provider | `delivery.id` | Compare-and-set + chính sách theo lane; idempotency key provider chỉ khi provider hỗ trợ (SES: không) |
| D | Notification → Kafka | `outbox.event_id` | Outbox, id sinh lúc INSERT |

Lớp C là lớp duy nhất không đóng kín được về lý thuyết; thay vì cố đóng, ta **chọn hậu quả** — và hậu quả đúng cho OTP khác với hậu quả đúng cho promo. Đó là ý nghĩa của chữ "effect".
