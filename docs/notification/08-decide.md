# Stage Decide

**Trạng thái:** `proposed`

## 1. Decide làm gì và không làm gì

Decide nhận một `NotificationRequest` đã chuẩn hoá (từ request hoặc fact — [02-pipeline.md](./02-pipeline.md) §2.1) và trả lời ba câu: *có gửi không*, *qua kênh nào*, *khi nào sớm nhất*. Đầu ra là **0..N dòng `notification_delivery`** (một dòng mỗi kênh) hoặc một kết luận không gửi kèm mã lý do.

Decide **chỉ dùng dữ liệu cục bộ**: catalog template (trong service), `user_preference`, `suppression`, `delivery_dedup` trong Postgres, và một lệnh Redis cho frequency cap. Nó **không** gọi Auth, không gọi provider, không render. Resolve email và render là việc của worker ở ranh giới 2.

## 2. Decide chạy trong transaction của Kafka consumer

Có hai chỗ có thể đặt Decide: trong transaction của consumer (ranh giới 1), hoặc một worker riêng poll `notification_request`. Chọn **trong transaction của consumer**:

| | Trong consumer | Worker riêng |
|---|---|---|
| Đầu vào | Toàn bộ cục bộ, không RPC → không có lý do tách | Như nhau |
| Chi phí | +3–5 ms (vài SELECT theo PK, một `INCR` Redis): consumer từ ~10 ms lên ~15 ms, vẫn rất xa `max.poll.interval.ms` | 0 cho consumer, nhưng thêm một worker và một trạng thái `PENDING_DECISION` phải vận hành |
| Nguyên tử | `INSERT request` + `INSERT delivery` cùng transaction → không có request "mồ côi" | Cần xử lý request đã INSERT nhưng chưa decide khi crash |
| Độ trễ critical | Delivery có ngay khi commit; `LISTEN/NOTIFY` đánh thức worker | Thêm một vòng poll |

Sơ đồ ranh giới 1 vì thế là:

```
onMessage(event):
  BEGIN
    INSERT ingest_dedup (source, event_id)          ON CONFLICT DO NOTHING → 0 row ⇒ trùng, COMMIT, commit offset, return
    INSERT notification_request (..., decision = NULL)
    decision = decide(request)                      -- §3, chỉ đọc DB/Redis
    UPDATE notification_request SET decision, decision_reason
    for each channel kept:
      INSERT delivery_dedup (dedup_key, channel)    ON CONFLICT DO NOTHING → 0 row ⇒ kênh này SKIPPED(duplicate)
      INSERT notification_delivery (status = PENDING, next_attempt_at, expires_at, ...)
  COMMIT
  NOTIFY delivery_ready (lane)                       -- chỉ lane critical cần; các lane khác poll
  commit offset
```

Kỷ luật duy nhất phải giữ: **không có I/O ra ngoài trong khối này.** Nếu ngày nào đó Decide cần gọi một service khác, đó là dấu hiệu quy tắc D-07 đang bị vi phạm (dữ liệu render phải đến từ producer).

### Điều kiện có thể thay đổi trong lúc row nằm chờ

Row bulk có thể nằm 4 giờ; user có thể tắt marketing trong lúc đó. Worker **re-check những gì rẻ** lúc claim: `expires_at`, `marketing_enabled` (bulk), suppression theo `email_hash` sau khi resolve email (§4 bước 9). **Không** re-check frequency cap và quiet hours — chúng đã được quyết một lần và `next_attempt_at` đã phản ánh.

## 3. Thứ tự áp quy tắc

Thứ tự có chủ ý: **lỗi của producer trước** (để họ thấy ngay trong DLQ và alert), rồi **hết hạn**, rồi **chính sách** theo user. Mỗi bước hoặc cho qua, hoặc kết thúc với một mã lý do ghi vào `notification_request.decision_reason`.

| # | Kiểm tra | Nếu không qua | Áp cho | Ai chịu trách nhiệm |
|---|---|---|---|---|
| 1 | Template tồn tại trong catalog và ở trạng thái `active` | `FAILED (unknown_template)` → DLQ, **alert** (lỗi contract) | Tất cả | Producer |
| 2 | Lane của template khớp topic nhận được | `FAILED (lane_mismatch)` → DLQ, alert | Request | Producer |
| 3 | `template_data` hợp lệ theo JSON Schema của template | `FAILED (invalid_template_data)` → DLQ, alert | Request | Producer |
| 4 | `dedup_key` có mặt khi template khai báo `required` | `FAILED (missing_dedup_key)` → DLQ, alert | Request | Producer |
| 5 | `user_id` có mặt và là UUID | `FAILED (no_recipient)` → DLQ | Tất cả | Producer |
| 6 | `effective_expires_at = min(request.expires_at, now + template.max_age)` chưa qua | `EXPIRED (already_expired)` — chỉ xảy ra khi consumer lag lớn hơn `max_age` | Tất cả | — |
| 7 | Nạp `user_preference`; **chưa có row** → mặc định (§5) | — | Tất cả | — |
| 8 | Với từng kênh trong `template.channels`: kênh có bị `governed_by` chặn theo chính sách lane ([07-templates.md](./07-templates.md) §4) không | Kênh bị bỏ với `channel_reason = preference_off`; **mọi** kênh bị bỏ → `SKIPPED (all_channels_off)` | Tất cả | — |
| 9 | Suppression theo `user_id` (chỉ kênh email; critical bỏ qua) | Kênh email bị bỏ, `suppressed`; còn in-app thì vẫn đi | Transactional, bulk | — |
| 10 | Frequency cap: `INCR freq:{user_id}:{local_date}` (Redis, `EXPIRE 48h`); kết quả > 2 | `SKIPPED (frequency_cap)`. Redis lỗi → **fail-open + alert** | Bulk | — |
| 11 | Quiet hours: giờ địa phương của user ∈ [22:00, 08:00) → `next_attempt_at = 08:00 kế tiếp`; nếu > `effective_expires_at` | `EXPIRED (quiet_hours_past_expiry)` | Bulk | — |
| 12 | INSERT `delivery_dedup (dedup_key, channel)`; conflict | Kênh đó `SKIPPED (duplicate)` — không phải lỗi, không alert | Tất cả có `dedup_key` | — |
| 13 | INSERT `notification_delivery` cho mỗi kênh còn lại: `status = PENDING`, `next_attempt_at` (= now, hoặc giờ quiet-hours), `expires_at = effective_expires_at` | — | Tất cả | — |

`decision` cuối cùng của request: `DECIDED` nếu có ít nhất một delivery được tạo; ngược lại là `SKIPPED` / `FAILED` / `EXPIRED` / `IGNORED` (fact không có trong bảng ánh xạ).

### Vì sao bước 12 unique theo `(dedup_key, channel)`

Một request `ORDER_CONFIRMED` sinh hai delivery (email + in-app) **cùng** `dedup_key`. Unique trên `dedup_key` đơn sẽ chặn nhầm kênh thứ hai. Khoá đúng là cặp `(dedup_key, channel)`. (Bản đầu của [02-pipeline.md](./02-pipeline.md) §5.1 viết unique trên `dedup_key` — đã sửa.)

## 4. Suppression: khoá theo `user_id`, xác nhận theo `email_hash`

Decide chạy trước khi biết email, nên suppression không thể tra theo địa chỉ. Cách làm:

1. Track nhận hard bounce / complaint → correlate `provider_message_id → delivery → user_id` → ghi `suppression (user_id, email_hash = sha256(lower(email)), reason, ...)`. Không lưu email rõ.
2. Decide (bước 9) tra `suppression WHERE user_id = ? AND (until IS NULL OR until > now())` → bỏ kênh email.
3. Worker, sau khi resolve email từ Auth, so `sha256(lower(email))` với `email_hash` của dòng suppression. **Khác nhau nghĩa là user đã đổi email** → suppression không còn áp dụng, gửi bình thường và đánh dấu dòng suppression đó `superseded_at`.

Hành vi này đúng về nghiệp vụ: địa chỉ cũ chết không có nghĩa địa chỉ mới cũng chết. Nó cũng là lý do `suppression` giữ `email_hash` ngay cả khi `user_id` bị xoá ([09-data-model.md](./09-data-model.md) §6).

Complaint có thêm side effect: publish `notification.complaint-received.v1` qua outbox để User Service tắt `marketing_enabled` ở nguồn; Notification cũng cập nhật bản replicate cục bộ ngay để không phải chờ vòng event.

## 5. Mặc định khi chưa có preference

User mới hoặc event `preferences-updated` chưa tới. Mặc định:

| Cờ | Mặc định | Lý do |
|---|---|---|
| `email_enabled` | `true` | Chặn email xác nhận đơn của user mới là sai rõ ràng hơn gửi nhầm |
| `in_app_enabled` | `true` | Như trên; và in-app không có chi phí reputation |
| `marketing_enabled` | `false` | Gửi marketing cho người chưa từng đồng ý là rủi ro compliance; user sẽ bật khi muốn |
| `timezone` | `Asia/Ho_Chi_Minh` | User chưa publish timezone; đa số user ở VN |
| `locale` | `vi` | |

Daily reconciliation của User Service (đã có trong contract) sẽ lấp chỗ trống trong vòng 24 h.

## 6. Frequency cap và quiet hours — chi tiết

**Frequency cap** (bulk): đếm ở Decide, không đếm ở Send. Hệ quả: một bulk được đếm rồi sau đó `EXPIRED` vẫn chiếm một slot trong ngày — chấp nhận, vì đếm ở Send sẽ để hai bulk cùng qua Decide rồi cùng gửi. Key `freq:{user_id}:{YYYY-MM-DD theo timezone user}`, TTL 48 h. Redis lỗi → fail-open (cho qua) + alert `frequency_cap_degraded`: bulk là kênh ít rủi ro nhất, chặn toàn bộ bulk vì Redis chết là phản ứng quá tay.

**Quiet hours** (bulk): 22:00–08:00 theo `timezone` của user. Decide đặt `next_attempt_at` một lần; worker không tính lại. Row có `next_attempt_at` trong tương lai vẫn là `PENDING` và không bị claim cho tới lúc đó (điều kiện `next_attempt_at <= now()` trong câu claim). Nếu `08:00 kế tiếp > effective_expires_at` thì `EXPIRED` ngay tại Decide, không tạo delivery — không có lý do tạo row mà chắc chắn sẽ hết hạn.

## 7. Mã lý do

Mã lý do là **contract vận hành**: dashboard, alert và support đều đọc chúng. Không thêm mã mới tuỳ tiện; mỗi mã có nghĩa cố định.

| `decision` | `decision_reason` | Ai cần biết | Alert |
|---|---|---|---|
| `FAILED` | `unknown_template`, `lane_mismatch`, `invalid_template_data`, `missing_dedup_key`, `no_recipient` | Producer (lỗi contract) | **Có**, ngay lần đầu |
| `EXPIRED` | `already_expired`, `quiet_hours_past_expiry` | Ops (lag hoặc `max_age` quá ngắn) | Khi tỷ lệ > 1 % |
| `SKIPPED` | `all_channels_off`, `frequency_cap`, `duplicate` | Không ai — hành vi bình thường | Không (metric để theo dõi xu hướng) |
| `IGNORED` | `unmapped_fact` | Không ai | Không |
| `DECIDED` | — | | |

Ở mức delivery, `channel_reason` cho kênh bị bỏ: `preference_off`, `suppressed`, `duplicate`, `channel_not_allowed`.

## 8. In-app đi cùng đường với email

In-app không có provider, nên về lý thuyết Decide có thể render và ghi thẳng `inbox_item` trong transaction consumer. **Không làm vậy**: render trong consumer đưa lỗi template vào đường Kafka (đúng thứ ranh giới 1 phải tránh), và tạo hai đường code cho hai kênh. In-app đi qua cùng worker: `PENDING → claim → render → INSERT inbox_item + INCR unread → DELIVERED`, tất cả trong một transaction Postgres (Redis `INCR` sau commit; lệch thì recompute từ `inbox_item`). Với `LISTEN/NOTIFY`, độ trễ thêm là vài ms.

## 9. Ví dụ

Request `ORDER_CONFIRMED` cho user chưa có preference, chưa từng bounce:

```
1  ORDER_CONFIRMED active                         ✓
2  lane transactional == topic transactional      ✓
3  template_data hợp lệ                            ✓
4  dedup_key "order_confirmed:1000234" có          ✓
5  user_id có                                      ✓
6  effective_expires_at = min(request 16:15, now+6h = 16:16) = 16:15   ✓
7  preference: không có row → email ✓ in_app ✓ marketing ✗ (không liên quan)
8  email: governed_by email_enabled = true → giữ;  in_app: true → giữ
9  suppression theo user_id: không có               ✓
10-11 (bulk only) bỏ qua
12 delivery_dedup (order_confirmed:1000234, email) ✓ ; (…, in_app) ✓
13 INSERT 2 delivery PENDING, expires_at 16:15
→ decision = DECIDED
```

Cùng request đến lần hai (Order replay outbox với id mới): bước 1–11 qua, bước 12 conflict cả hai kênh → `decision = SKIPPED (duplicate)`, không có delivery mới, không alert.
