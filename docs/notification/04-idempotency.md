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
| 3 | **Cùng một sự thật, nhiều event khác id** | Order dual-publish `order.paid.v1` và `.v2` khi migrate; Order gửi `paid` và Payment gửi `captured` cho cùng đơn | Khoá nghiệp vụ, ví dụ `order_confirmation:{order_id}` |
| 4 | **Lease hết hạn ở deliver worker** | Worker gọi provider xong, chết trước `mark_sent`; reaper trả về `RETRY` | `delivery.id` làm idempotency key phía provider |
| 5 | **Webhook provider retry** | SNS gửi lại `Delivery` vì endpoint trả 200 chậm | `(provider, provider_event_id)` |
| 6 | **Replay có chủ đích** | Ops reset offset về 6 giờ trước sau sự cố | Tất cả khoá trên + cửa sổ dedup đủ dài |

**#1 và #2 chỉ chặn được vì Auth dùng lại cùng `id` khi retry.** Nếu producer sinh id mới mỗi lần retry, lớp dedup kỹ thuật biến mất và chỉ còn lớp nghiệp vụ (#3). Đây là lý do `contracts/conventions/events.md` bắt buộc retry với cùng CloudEvent id — điều kiện tiên quyết để consumer idempotent được.

## 3. Bốn lớp dọc pipeline

### Lớp A — Ranh giới Kafka → DB: dedup kỹ thuật bằng unique constraint

```sql
CREATE TABLE notification_request (
    id          uuid PRIMARY KEY,
    source      text NOT NULL,       -- CloudEvents source
    event_id    text NOT NULL,       -- CloudEvents id
    event_type  text NOT NULL,
    subject     text,
    payload     jsonb NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (source, event_id)
);
```

```
onMessage(event):
    INSERT INTO notification_request (...) VALUES (...)
        ON CONFLICT (source, event_id) DO NOTHING;
    commit offset
```

Ba điểm bắt buộc:

- **Unique constraint, không "SELECT xem có chưa rồi INSERT".** Hai consumer instance (sau rebalance) có thể cùng SELECT thấy trống rồi cùng INSERT. Để DB làm trọng tài.
- **INSERT trước, commit offset sau.** Ngược lại là at-most-once. Thứ tự INSERT → commit cho at-least-once, unique constraint biến nó thành exactly-once effect.
- **Bản ghi dedup chính là row nghiệp vụ**, không phải bảng `processed_event_ids` riêng hay key Redis. Bảng riêng thì ghi hai nơi (không nguyên tử); Redis mất khi restart hoặc hết TTL sớm hơn Kafka retention → trùng đúng lúc sự cố. Row `notification_request` sống 90 ngày theo partition, tự nó là bằng chứng "đã nhận".

### Lớp B — Stage Decide: dedup nghiệp vụ, và ranh giới với "không phải trùng"

Lớp tinh tế nhất vì **không phải kỹ thuật thuần tuý** — là chính sách theo từng template. Mỗi template khai báo cách tính `dedup_key`:

| Template | `dedup_key` | Lý do |
|---|---|---|
| `order_confirmation` | `order_confirmation:{order_id}` | Một đơn xác nhận một lần, bất kể Order và Payment cùng phát event |
| `review_reply` | `review_reply:{review_id}` | Contract Review: "chỉ sau reply chính thức đầu tiên" |
| `otp` | **không có** | Mỗi lần user bấm "gửi lại" là một OTP **mới**, phải gửi |
| `cart_abandoned` | `cart_abandoned:{cart_id}:{cycle_id}` | Một chu kỳ bỏ giỏ nhắc một lần; chu kỳ mới nhắc lại |

Unique index `uq_delivery_dedup` (partial, `WHERE dedup_key IS NOT NULL`) trên `notification_delivery` chặn ở tầng DB.

OTP là điểm dễ nhầm: hai event OTP cách nhau 30 s có **id khác nhau** (lớp A cho qua — đúng) và **không có dedup_key** (lớp B cho qua — đúng). Cả hai được gửi. Nếu đặt `dedup_key = otp:{user_id}`, nút "gửi lại" của user không bao giờ hoạt động. Muốn chặn user spam "gửi lại" 20 lần thì đó là **rate limiting** (`tối đa 3 OTP / 10 phút / user`) — cơ chế khác, hậu quả khác (từ chối có chủ đích), không phải idempotency.

> Idempotency trả lời "đây có phải cùng một yêu cầu không?". Rate limiting trả lời "yêu cầu này có được phép không?". Trộn hai cái là nguồn của nhiều bug khó hiểu.

### Lớp C — Ranh giới DB → Provider: khe hở không đóng được, chỉ thu hẹp

Khe hở nằm giữa `provider.send()` thành công và `UPDATE status = SENT`. Ba công cụ, dùng kết hợp:

**C1 — Idempotency key phía provider.** Truyền `delivery.id` cho provider trong mọi lần gọi. Provider lưu key 24 h và trả kết quả cũ nếu thấy lại — trùng bị chặn *ở phía họ*. Sạch nhất, nhưng phụ thuộc provider: Stripe, Twilio, Postmark hỗ trợ; SendGrid qua `custom_args` + kiểm tra; **SES không hỗ trợ**. Khi chọn provider cho lane critical, "có idempotency key không" là tiêu chí ngang với giá (quyết định mở #2 trong [README](./README.md)).

**C2 — Compare-and-set khi ghi trạng thái.**

```sql
UPDATE notification_delivery
SET    status = 'SENT', provider_message_id = ?, updated_at = now()
WHERE  id = ? AND status = 'SENDING' AND locked_by = ?;
-- affected rows = 0 → reaper + worker khác đã xử lý row này rồi
```

Nếu 0 row, worker biết mình đã thua cuộc đua và không làm gì thêm: không publish event, không tăng metric. Không ngăn được email trùng đã đi, nhưng ngăn **hậu quả dây chuyền** trong hệ thống: 2 event `notification.sent`, 2 lần cập nhật analytics, 2 dòng timeline.

**C3 — Chính sách theo lane khi lease hết hạn.** Row `RETRY` với `attempt > 1` do lease hết hạn là row *đã từng được thử*:

| Lane | Hành động | Lý do |
|---|---|---|
| Bulk | Gửi lại | Promo trùng là phiền, không phải sự cố |
| Transactional | Gửi lại nếu provider có idempotency key; nếu không, gửi lại nhưng đánh `possibly_duplicate = true` | Xác nhận đơn trùng chấp nhận được, nhưng cần đo được tỷ lệ |
| Critical (OTP) | **Không gửi lại**, đánh `FAILED (lease_expired)` | OTP thứ hai gây nhầm hơn là không có; user tự bấm "gửi lại" và đó là OTP mới, sạch |

Cùng một cơ chế kỹ thuật, ba hậu quả khác nhau — vì "hậu quả nhìn từ user" khác nhau theo lane.

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
6. Worker khác nhặt: lane critical, `attempt > 1`, lease từng hết hạn → **không gửi lại**, đánh `FAILED (lease_expired)`. **Lớp C3.**
7. Webhook `Delivery` cho email đã gửi ở bước 5 đến hai lần (SNS retry). Unique `provider_event_id` chặn bản 2. Track worker thấy row `FAILED (lease_expired)` nhưng có `Delivery` từ provider → chuyển `DELIVERED` (hạng cao hơn), ghi chú `recovered by webhook`. **Lớp D + state ranking.**

Kết quả: user nhận **đúng 1 email**, trạng thái cuối cùng đúng, không event downstream nào bị nhân đôi — dù 5 điểm hỏng xảy ra liên tiếp. Thiếu bất kỳ lớp nào, con số là 2, 3 hoặc 4.

## 5. Cửa sổ dedup phải dài bao nhiêu

Khoá dedup phải sống **ít nhất bằng khoảng thời gian dài nhất mà một bản sao có thể quay lại**. Với Kafka đó là retention của topic (24 h với topic Auth, 7–14 ngày với topic khác), vì ops có thể replay bất kỳ lúc nào trong khoảng đó.

Row `notification_request` là bản ghi dedup và sống 90 ngày theo partition → tự thoả mãn. Với replay cũ hơn 90 ngày (backfill, rất hiếm), cần cờ vận hành `replay_mode` bắt consumer bỏ qua mọi event có `time` cũ hơn X ngày, thay vì gửi lại email của 4 tháng trước.

## 6. Cách làm sai hay gặp

- **Dedup bằng hash payload.** Hai OTP hợp lệ khác nhau có payload gần giống → bị coi là trùng và mất. Ngược lại, retry đóng gói lại với `time` mới → hash khác → không phát hiện. Luôn dedup bằng **định danh**, không bằng nội dung.
- **Một bảng dedup toàn cục.** Trộn khoá kỹ thuật (event id) và khoá nghiệp vụ (order_id) vào một cột `key text` → không ai hiểu row nào chặn cái gì, và không có chính sách khác nhau theo template.
- **Check ở tầng ứng dụng thay vì constraint.** Race condition xuất hiện đúng lúc rebalance, tức đúng lúc tải cao.
- **Coi retry là hiếm nên không test.** Retry, rebalance, lease hết hạn là **đường đi bình thường** dưới tải. Test tích hợp bắt buộc có: publish cùng event 3 lần, kill worker giữa chừng, gửi webhook trùng — assert số email = 1.
- **Idempotent xử lý nhưng không idempotent side-effect.** Row không trùng, nhưng metric `emails_sent` tăng 2 hoặc outbox ghi 2 lần vì nằm ngoài transaction. Mọi side-effect nằm **cùng transaction** với chuyển trạng thái, hoặc được gác bằng compare-and-set.

## 7. Tóm lại

| Lớp | Vị trí | Khoá | Cơ chế |
|---|---|---|---|
| A | Kafka → DB | `(source, event_id)` | Unique constraint, INSERT trước commit sau |
| B | Decide | `dedup_key` theo template | Partial unique index; OTP cố ý không có |
| C | DB → provider | `delivery.id` | Idempotency key provider + compare-and-set + chính sách theo lane |
| D | Notification → Kafka | `outbox.event_id` | Outbox, id sinh lúc INSERT |

Lớp C là lớp duy nhất không đóng kín được về lý thuyết; thay vì cố đóng, ta **chọn hậu quả** — và hậu quả đúng cho OTP khác với hậu quả đúng cho promo. Đó là ý nghĩa của chữ "effect".
