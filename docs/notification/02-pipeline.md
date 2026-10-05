# Pipeline: sáu stage logic, hai ranh giới vật lý

**Trạng thái:** `proposed`

## 1. Vấn đề với "một handler làm hết"

Cách trực quan nhất:

```
onKafkaMessage(event):
    check duplicate            (DB)
    load user preferences      (DB)
    resolve recipient email    (RPC → Auth internal API)
    render template
    send via provider          (HTTP ra ngoài, 200 ms – 8 s)
    save status                (DB)
    commit offset
```

Chạy tốt ở dev. Dưới tải, nó hỏng ở bốn điểm — và mỗi điểm là một lý do phải tách.

### Lý do 1: consumer Kafka bị trói vào tốc độ của thứ chậm nhất

Thời gian xử lý một message = tổng mọi bước. Khi provider chậm từ 200 ms lên 8 s, throughput consumer rơi 40 lần dù dedup, decide, render vẫn nhanh như cũ. Tệ hơn: với `max.poll.records = 500` và `max.poll.interval.ms = 300 s`, một batch 500 × 8 s = 4.000 s vượt xa 300 s → Kafka coi consumer đã chết, **rebalance**, giao partition cho consumer khác — consumer này bắt đầu lại từ offset đã commit → những email đã gửi trong batch dở được gửi lại. Vòng lặp rebalance ↔ gửi trùng kéo dài suốt thời gian provider chậm, và log chỉ toàn `Rebalance in progress`.

### Lý do 2: retry đúng cách là bất khả thi

Provider lỗi thì chỉ có hai lựa chọn, đều tệ: retry tại chỗ (sleep 1 s, 2 s, 4 s) → mọi message phía sau trên partition đứng im; hoặc bỏ qua và commit → mất notification.

### Lý do 3: mỗi stage có đặc tính lỗi và scale khác nhau

| Stage | Bị giới hạn bởi | Lỗi thường gặp | Cách retry đúng |
|---|---|---|---|
| Ingest / Dedup | DB write | DB timeout | Retry ngay, an toàn |
| Decide | DB read / cache | Hiếm | — |
| Render | CPU | Template lỗi, thiếu field | **Không retry**, đi DLQ |
| Deliver | Quota provider, network | Timeout, 429, 5xx | Backoff dài, có jitter |
| Track | Webhook inbound | Provider gửi trùng | Idempotent |

Gộp chung buộc phải dùng một chính sách retry cho tất cả, và không chính sách nào đúng cho cả "template thiếu field" lẫn "provider 429".

### Lý do 4: không quan sát được, không can thiệp được

Khi một notification bị kẹt, handler đơn chỉ trả lời "đang xử lý" hoặc "xong". Không tạm dừng được riêng khâu gửi khi provider sự cố.

## 2. Pipeline logic

```
Ingest → Dedup → Decide → Render → Deliver → Track
```

| Stage | Làm gì | Không làm gì |
|---|---|---|
| **Ingest** | Consume Kafka, validate CloudEvents envelope, chuẩn hoá thành `NotificationRequest` nội bộ (xem §2.1) | Không gọi ra ngoài |
| **Dedup** | Khoá idempotency `(source, event_id)` — xem [04-idempotency.md](./04-idempotency.md) | |
| **Decide** | Với fact: tra bảng ánh xạ fact → template + lane (§2.2). Áp preferences (bản replicate từ `t.user.events`), quiet hours, rate limit per-user, suppression list, chọn kênh, gán `dedup_key` | Không I/O ra provider |
| **Render** | Resolve email người nhận qua Auth (§2.3); template versioned, precompile & cache, i18n | Render fail → DLQ, không retry |
| **Deliver** | Worker per `lane × channel`, rate limiter, circuit breaker, retry | |
| **Track** | Nhận webhook/feedback provider, cập nhật trạng thái, nuôi suppression list — xem [03-tracking.md](./03-tracking.md) | |

Sáu stage này là **mô hình logic**. Khi triển khai, chúng thu về **hai ranh giới vật lý** — đủ để giải quyết cả bốn lý do ở trên. Tách thêm chỉ khi số liệu chứng minh cần.

### 2.1 Hai loại đầu vào, một `NotificationRequest`

Notification nhận **request** (producer chỉ định template) và **fact** (sự kiện nghiệp vụ) — quy tắc phân loại ở [decisions.md](./decisions.md) D-07. Ingest có hai loại consumer, nhưng cả hai chuẩn hoá về cùng một cấu trúc nội bộ trước khi INSERT, để **từ Decide trở đi pipeline không phân biệt nguồn**:

```
request topics t.notification.{lane}.v1  (Order)     ─┐
               t.review.events           (Review)    ─┼─▶ consumer request ──┐
               t.auth.notification.events (Auth, A4) ─┘                      ├──▶ NotificationRequest {
                                                                             │      source, event_id,        -- dedup lớp A
fact topic     t.cart.events             (Cart)      ───▶ consumer fact ─────┘      template,                -- request: từ payload; fact: từ bảng ánh xạ
                                                                                    lane,                    -- request: theo topic/catalog; fact: từ bảng ánh xạ
                                                                                    user_id, subject, source_sequence?,
                                                                                    template_data,           -- request: từ payload; fact: trích từ fact
                                                                                    dedup_key?, expires_at?  -- request: producer gửi kèm; fact: bảng ánh xạ sinh
                                                                                  }
```

Ba consumer request khác nhau ở cách đọc payload nhưng cùng đích:

- **Order** gửi đúng schema `notification.requested.v1` với `sequence` bắt buộc,
  monotonic riêng theo order subject → ánh xạ trực tiếp và dùng cho supersession/serialization.
- **Review** gửi message riêng của họ (`reviewReplyNotificationRequested`, `reviewRemovalNotificationRequested`) → consumer ánh xạ 1-1 sang `REVIEW_REPLIED` / `REVIEW_REMOVED`, `recipient_user_id` → `user_id`.
- **Auth** là request dạng đặc biệt: payload chỉ có `delivery_id` + metadata. Consumer **gán `template` ngay ở Ingest** từ CloudEvents `type` (+ `data.purpose` với OTP) theo bảng ánh xạ ở [10-interfaces.md](./10-interfaces.md) §2 — Decide cần template để biết kênh, `dedup_key`, `max_age`. Với 4 template cảnh báo (`EMAIL_CHANGED`, `PASSWORD_CHANGED`, `ACCOUNT_LOCKED`, `ACCOUNT_UNLOCKED`) `template_data` (`changed_at`, `locked_at`, …) nằm sẵn trong event và được lưu như mọi request khác; với 3 template OTP/reset `template_data` **không lưu** (secret). Email (và secret) được resolve ở Render qua `POST /internal/v1/auth/notification-deliveries/{id}/content` cho kênh email; `source_ref` của request/delivery giữ `delivery_id` của Auth để worker gọi API này.

**Không có consumer cho Payment và Delivery**: fact của họ không mang `user_id` (Payment cấm tường minh trong schema), nên Notification không biết gửi cho ai. Order đã consume các fact đó để chạy state machine và phát request `ORDER_PAYMENT_FAILED`, `ORDER_REFUNDED`, `ORDER_COMPLETED` thay ([07-templates.md](./07-templates.md) §6).

### 2.2 Bảng ánh xạ fact → template

Với fact, "gửi gì" là quyết định của Notification và nằm trong một **bảng cấu hình có review**, không rải trong code. Phase 1 chỉ có một nguồn fact:

| Fact (`type`) | Template | Lane | `dedup_key` | `template_data` trích từ |
|---|---|---|---|---|
| `com.ecommerce.cart.abandoned.v1` | `CART_ABANDONED` | bulk | `cart_abandoned:{cart_id}:{abandonment_cycle}` | `cart_id`; `cart_url` do Notification lắp từ cấu hình base URL của frontend (ngoại lệ duy nhất của quy tắc "producer gửi URL", vì fact không có chỗ cho URL) |

Fact không có trong bảng → ghi `IGNORED`, không phải lỗi. Thêm một fact mới = thêm một dòng cấu hình + template, không sửa consumer — với điều kiện fact đó có `user_id` và đủ dữ liệu render; nếu không, đàm phán với producer để họ gửi request thay (đúng bài học từ Payment/Delivery).

### 2.3 Email người nhận: resolve lúc gửi, không đi qua Kafka

Không event nào mang email (PII không đi qua Kafka — xem [README §4](./README.md)). Render resolve email qua Auth internal API ngay trước khi gửi:

| Nguồn | API | Trả về |
|---|---|---|
| Auth (kênh email) | `POST /internal/v1/auth/notification-deliveries/{delivery_id}/content` (đã có) | email + template + `template_data` (secret) + `expires_at`; `template` trả về phải khớp template đã gán ở Ingest, lệch → `FAILED (template_mismatch)`; `410` → `FAILED (content_expired)` |
| Mọi nguồn khác | `GET /internal/v1/auth/users/{user_id}/notification-contact` (đã thêm vào contract và code Auth) | email, `email_verified`, `locale?` |

Cả hai endpoint nằm trên listener **internal 8081** của Auth. Ràng buộc: timeout 2 s, breaker và bulkhead riêng theo lane ([06-backpressure.md](./06-backpressure.md) §3), cache theo `user_id` 15 phút **trong memory/Redis có TTL — không ghi xuống DB của Notification** để không tạo thêm một bản PII lâu dài. `404` → delivery `FAILED (no_recipient)`, không retry.

Delivery **in-app không cần resolve gì** (chỉ cần `user_id`) — kể cả in-app của 4 template cảnh báo Auth, vì `template_data` của chúng đã lưu từ event — nên khi Auth API sự cố, in-app vẫn đến; hai delivery email và in-app của cùng một request độc lập nhau.

## 3. Hai ranh giới vật lý

```
Ranh giới 1: Kafka → DB                 Ranh giới 2: DB → provider
┌────────────────────────────┐          ┌──────────────────────────────┐
│ Kafka consumer             │  INSERT  │ Deliver worker (pool riêng)  │
│ validate → dedup → DECIDE  │─────────▶│ claim → resolve email →      │──▶ provider
│ → INSERT delivery → commit │          │ render → send → update       │
│ ~15 ms / message, chỉ DB   │          │ nhịp riêng, retry riêng      │
└────────────────────────────┘          └──────────────────────────────┘
                                                 ▲
                                                 │ poll (FOR UPDATE SKIP LOCKED)
                                         bảng notification_delivery
                                         = queue + trạng thái + lịch retry
```

Decide nằm ở **ranh giới 1**, trong cùng transaction với INSERT request: mọi đầu vào của nó đều cục bộ (preference, suppression, sổ dedup, một lệnh Redis), không có RPC nào, nên không có lý do tách nó ra. Chi tiết thứ tự quy tắc và mã lý do ở [08-decide.md](./08-decide.md). Worker ở ranh giới 2 chỉ còn resolve email, render, gửi.

Bảng trạng thái là **source of truth duy nhất**. Không rải trạng thái qua nhiều topic nội bộ: một notification = một dòng, timeline nằm sẵn trong dòng đó.

### Vì sao chọn DB làm queue thay vì Kafka topic nội bộ

| | Kafka topic nội bộ giữa mỗi stage | Bảng trạng thái làm queue |
|---|---|---|
| Scale | Rất tốt | Đủ tới vài nghìn notification/s trên một Postgres (xem §5.6) |
| Truy vết một notification | Rải trên nhiều topic, khó | Một dòng, một câu SQL |
| Retry / scheduling | Cần retry topics phân tầng riêng | Cùng cơ chế: `next_attempt_at` |
| Source of truth | Vẫn cần DB → ghi hai nơi | Một nơi |
| Can thiệp thủ công | Khó | `UPDATE ... SET status` |

Với tải giả định của dự án, DB làm queue thắng ở mọi tiêu chí trừ trần scale, và trần đó cao hơn nhu cầu nhiều lần. Nghẽn thực sự nằm ở quota provider (150/s), không ở DB.

## 4. Ranh giới 1: Kafka consumer

```
onMessage(event):
    validate CloudEvents envelope                       → lỗi envelope → DLQ ngay
    BEGIN
      INSERT ingest_dedup (source, event_id) ON CONFLICT DO NOTHING
          → 0 row ⇒ trùng: COMMIT, commit offset, return
      INSERT notification_request (...)
      if HMAC(event.data.user_id) exists in deleted_user_tombstone:
          anonymize request, decision = SKIPPED(user_deleted), COMMIT, commit offset, return
      if source == '/services/order':
          require integer sequence >= 1
          compute versioned HMAC subject key; lock/upsert notification_subject_sequence
          same sequence + same event + same payload => SKIPPED(duplicate), no delivery
          same sequence + different event or payload => FAILED(sequence_collision)
          lower sequence => request timeline kept, deliveries SKIPPED(superseded)
      decision = decide(request)                        -- 08-decide.md, chỉ đọc DB/Redis
      UPDATE notification_request SET decision, decision_reason
      for each channel of template:
        if kept:
          INSERT delivery_dedup (dedup_key, channel) ON CONFLICT DO NOTHING
              → 0 row ⇒ kênh này bị bỏ với skip_reason = duplicate
          INSERT notification_delivery (status = PENDING, ...)
        else:
          INSERT notification_delivery (status = SKIPPED, skip_reason, finished_at = now)   -- timeline thấy vì sao kênh bị bỏ
    COMMIT
    NOTIFY delivery_ready(lane)                         -- lane critical
    commit offset
```

Envelope không parse được (thiếu `id`/`type`/`source`/`data`) → publish nguyên message sang topic `<topic>.dlq` của nguồn (kèm header `x-dlq-reason`) rồi commit offset; lỗi DB tạm thời → retry tại chỗ tối đa `consume_max_attempts` rồi mới DLQ. Lỗi contract của producer (`unknown_template`, `invalid_template_data`, …) **không** đi Kafka DLQ: row `notification_request.decision = FAILED` chính là "DLQ" dạng bảng mà ops API `dlq/redrive` và alert đọc.

Ba quy tắc:

1. **Chỉ làm việc nhanh, chỉ chạm DB cục bộ và Redis.** Không gọi provider, không gọi Auth API. Mục tiêu ~15 ms/message để không bao giờ chạm `max.poll.interval.ms`, không bao giờ rebalance vì chậm.
2. **INSERT trước, commit offset sau.** Ngược lại là at-most-once: chết giữa chừng là mất OTP.
3. **Dedup bằng unique constraint**, không bằng `SELECT` rồi `INSERT`. Sổ dedup `ingest_dedup` là bảng riêng không partition, ghi cùng transaction — lý do ở [09-data-model.md](./09-data-model.md) §1. Chi tiết lớp dedup ở [04-idempotency.md](./04-idempotency.md) §3.

Consumer Kafka **không biết provider đang sự cố**. Khi provider chậm, lag Kafka vẫn bằng 0; áp lực dồn vào bảng DB (xem [06-backpressure.md](./06-backpressure.md)).

## 5. Ranh giới 2: deliver worker poll DB

Pattern "database as queue" (job table). Đi từ bảng → câu SQL nhặt việc → vòng lặp → xử lý crash → bẫy hiệu năng.

### 5.1 Bảng làm hàng đợi

Các cột liên quan tới cơ chế hàng đợi (schema đầy đủ, partition và retention ở [09-data-model.md](./09-data-model.md) §4):

```sql
CREATE TABLE notification_delivery (
    id                  uuid NOT NULL,
    request_id          uuid NOT NULL,         -- 1 request → N delivery theo kênh
    source              text NOT NULL,
    source_sequence     bigint,
    lane                text NOT NULL,         -- critical | transactional | bulk
    channel             text NOT NULL,         -- email | in_app (phase 1)
    template            text NOT NULL,
    status              text NOT NULL,         -- PENDING | SENDING | SENT | RETRY | FAILED
                                               -- | DELIVERED | BOUNCED | EXPIRED | SKIPPED
    attempt             int  NOT NULL DEFAULT 0,
    next_attempt_at     timestamptz NOT NULL,  -- khi nào được phép nhặt (Decide đặt; quiet hours đẩy về tương lai)
    lease_until         timestamptz,           -- worker giữ tới lúc nào (chống crash)
    locked_by           text,                  -- worker id, để debug
    dedup_key           text,                  -- khoá nghiệp vụ; unique theo (dedup_key, channel) ở bảng delivery_dedup
    ordering_key        text,                  -- Order: versioned HMAC(source:subject), không lưu raw subject
    expires_at          timestamptz NOT NULL,  -- effective_expires_at do Decide tính, xem 05-ordering.md
    provider            text,
    provider_message_id text,
    possibly_duplicate  boolean NOT NULL DEFAULT false,
    last_error_code     text,
    last_error          text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)               -- bảng partition theo tháng trên created_at
) PARTITION BY RANGE (created_at);

-- Index quan trọng nhất: chỉ index row đang chờ, không index toàn bảng
CREATE INDEX idx_delivery_ready
    ON notification_delivery (lane, channel, next_attempt_at)
    WHERE status IN ('PENDING', 'RETRY');
```

Dedup nghiệp vụ **không** là unique index trên bảng này (bảng partition không cho unique thiếu partition key) mà là sổ `delivery_dedup (dedup_key, channel)` ghi cùng transaction lúc Decide — [09-data-model.md](./09-data-model.md) §1 và §4.1. Khoá là **cặp** `(dedup_key, channel)`: một request sinh email + in-app cùng `dedup_key`, unique trên `dedup_key` đơn sẽ chặn nhầm kênh thứ hai.

`idx_delivery_ready` là **partial index**: bảng có 50 triệu row lịch sử nhưng chỉ vài nghìn row đang `PENDING`/`RETRY`, nên index bé và luôn nằm trong RAM. Không có nó, mỗi lần poll là một lần quét bảng lớn — đây là lỗi số một khi người ta kết luận "DB làm queue chậm".

### 5.2 Câu SQL nhặt việc

```sql
WITH picked AS (
    SELECT id
    FROM   notification_delivery
    WHERE  lane = 'critical'
      AND  channel = 'email'
      AND  status IN ('PENDING', 'RETRY')
      AND  next_attempt_at <= now()
    ORDER  BY next_attempt_at
    LIMIT  20
    FOR UPDATE SKIP LOCKED
)
UPDATE notification_delivery d
SET    status      = 'SENDING',
       lease_until = now() + interval '60 seconds',
       locked_by   = 'critical-email-worker-3',
       attempt     = attempt + 1,
       updated_at  = now()
FROM   picked
WHERE  d.id = picked.id
RETURNING d.*;
```

Ba mệnh đề làm nên sự đúng đắn:

- **`FOR UPDATE`**: khoá row được chọn tới khi transaction kết thúc.
- **`SKIP LOCKED`**: row đã bị worker khác khoá thì bỏ qua, lấy row kế tiếp. Không có nó, 10 worker xếp hàng chờ nhau trên cùng 20 row đầu — song song bằng không.
- **`UPDATE ... RETURNING` trong cùng câu lệnh**: chọn và đánh dấu `SENDING` là một thao tác nguyên tử. Không có khe hở giữa "tôi thấy row này" và "tôi giữ row này".

Ba worker chạy đồng thời trên 50 row sẵn sàng:

```
t=0.000  worker-1 → khoá row 1..20, đánh SENDING, trả về
t=0.001  worker-2 → row 1..20 đang khoá → SKIP → lấy row 21..40
t=0.002  worker-3 → row 1..40 khoá/đã SENDING → lấy row 41..50
```

Mỗi row đúng một worker. Không cần Redis lock, không cần bảng claims riêng.

### 5.3 Vòng lặp worker

```
loop forever:
    rows = claim(lane, channel, batch = 20)      -- §5.2, transaction ngắn, commit ngay

    if rows.empty:
        sleep(adaptive_backoff)                   -- 50 ms → 100 ms → ... → 1 s khi rảnh
        continue
    reset backoff to 50 ms

    for row in rows (song song trong pool):
        -- re-check rẻ những gì có thể đã đổi trong lúc chờ (08-decide.md §2)
        if row.expires_at < now():                -- TTL theo template
            mark(row, EXPIRED); continue
        if lane_paused(row.lane, row.channel):    -- công tắc ops (ops_control), poll mỗi 5 s
            release(row, next = now + 5 s); continue          -- attempt không tính
        if row.lane == 'bulk' and not marketing_enabled(row.user_id):
            mark(row, SKIPPED, preference_off); continue
        if row.last_error_code == 'lease_expired':            -- reaper đã trả row về; 04-idempotency.md lớp C3
            critical → mark(row, FAILED, lease_expired); continue
            transactional → possibly_duplicate = true
        if superseded(row):                       -- fact projection, xem 05-ordering.md
            mark(row, SKIPPED, superseded); continue

        ordering_lease = false
        try:
            if row.source == '/services/order':
                ordering_lease = acquire_ordering_lease(row.ordering_key)  -- HMAC key, không raw subject
                    → busy: release row về PENDING/RETRY, không tính attempt; continue
                start heartbeat; mất lease trước provider call → release/retry, không gửi
                if row.source_sequence < highest_subject_sequence(row.source, row.subject):
                    mark(row, SKIPPED, superseded); continue
            if row.channel == 'in_app':
                body = render_inapp(row)          -- template đã cache
                INSERT inbox_item + mark DELIVERED + INSERT outbox(delivered)  -- cùng transaction; INCR unread sau commit
                continue
            if not breaker.allow():               -- provider chết: 06-backpressure.md §5
                release(row, next = now + breaker_open_retry(lane)); continue   -- attempt không tính
            if row.template is Auth:
                content   = auth.content(row.source_ref)   -- email + secret; 404/410 → FAILED, không retry
            else:
                recipient = auth.contact(row.user_id)      -- cache 15 phút; 404 → FAILED(no_recipient)
                if row.lane == 'bulk' and not recipient.email_verified:
                    mark(row, SKIPPED, email_unverified); continue
                if row.lane != 'critical' and suppressed(row.user_id):
                    hash == suppression.email_hash → mark(row, SKIPPED, suppressed); continue   -- 08-decide.md §4
                    hash != → suppression.superseded_at = now (user đã đổi email), gửi tiếp
            body = render_email(row, data)
            rate_limiter.acquire(lane, email, wait 5 s)   -- token bucket theo lane; hết chờ → release(row, next = now), không tính attempt
            result = provider.send(body)          -- SES không có idempotency key; xem 04-idempotency.md lớp C
            mark_sent(row, result.message_id, template_version, possibly_duplicate)   -- compare-and-set
        catch RetryableError:                     -- 5xx, timeout, 429, breaker
            attempt >= max_attempts(lane) → mark(row, FAILED, provider_error)
            else schedule_retry(row)              -- RETRY, next_attempt_at = now + backoff(attempt) + jitter (hoặc retry_budget_defer)
        catch PermanentError:                     -- render_failed, no_recipient, invalid_recipient, provider_rejected
            mark(row, FAILED, code)               -- FAILED + outbox(failed); parking table = chính bảng delivery
        finally:
            stop ordering heartbeat
            if ordering_lease: release_ordering_lease(row.ordering_key, row.id)
```

Chi tiết đáng chú ý:

- **Transaction claim rất ngắn**: chỉ bao câu `UPDATE ... RETURNING`, commit ngay, rồi mới gọi provider. Không giữ transaction mở trong lúc gọi HTTP 8 s — làm vậy giữ lock, giữ connection pool, và chặn vacuum. `lease_until` là thứ thay cho việc giữ lock lâu.
- **Backoff có jitter**: `next_attempt_at = now + min(2^attempt × 30 s, 1 h) + random(0, 30 s)`. Không jitter, 10.000 row fail cùng lúc sẽ cùng hết hạn retry cùng lúc và dội vào provider vừa hồi phục.
- **Rate limiter đứng trước provider, không đứng trước claim**. Đặt trước claim thì row nằm `PENDING` không ai nhặt, không phân biệt được "đang throttle" với "worker chết".
- **Claim tăng `attempt`; row không thực sự được thử thì trả lại bằng `release`** (`RETRY`, `attempt - 1`, `next_attempt_at`, `last_error_code` = `breaker_open` / `rate_limited` / `lane_paused`) — nên `attempt` luôn đếm số lần gọi provider thật ([06-backpressure.md](./06-backpressure.md) §5).
- **Adaptive sleep khi rảnh**: poll 50 ms liên tục trên bảng trống là lãng phí. Lane critical có thể thêm Postgres `LISTEN/NOTIFY` để consumer Kafka đánh thức worker ngay sau INSERT, giảm độ trễ từ ~500 ms xuống ~5 ms.
- **Gia hạn lease theo chu kỳ** (heartbeat `UPDATE lease_until = now + 60 s` mỗi 20 s cho row đang giữ) để batch lớn hoặc timeout dài không làm row hết lease trước khi được gửi — xem [06-backpressure.md](./06-backpressure.md) §6.
- **Ordering lease của Order cũng có heartbeat riêng.** Worker chỉ gọi provider khi vẫn
  sở hữu lease và timeout provider nhỏ hơn lease trừ safety margin. Nếu lease mất trong
  provider call, kết quả là không chắc chắn: CAS của owner cũ không được ghi đè owner mới,
  delivery được quan sát như `possibly_duplicate`/`ordering_lease_lost`. Không thiết kế
  nào ở đây có thể bắt provider email/SMS giao tới inbox theo thứ tự tuyệt đối sau khi đã
  accept/timeout; ordering này là local provider-call ordering, không phải saga proof.

### 5.4 Worker crash giữa chừng: lease và reaper

Worker claim 20 row, đánh `SENDING`, gửi được 7 rồi bị kill. 13 row còn lại kẹt ở `SENDING` mãi nếu không ai xử lý. Reaper (chạy mỗi 30 s, có thể là chính worker):

```sql
UPDATE notification_delivery
SET    status = 'RETRY',
       next_attempt_at = now(),
       last_error_code = 'lease_expired',
       last_error = 'lease expired (worker ' || locked_by || ')',
       lease_until = NULL, locked_by = NULL
WHERE  status = 'SENDING'
  AND  lease_until < now();
```

13 row quay lại hàng đợi sau tối đa 60 s. Riêng row thứ 8 — đã gọi provider thành công, chưa kịp `mark_sent` — sẽ bị gửi lại. Đây là khe hở không đóng kín được về lý thuyết; cách xử lý theo lane ở [04-idempotency.md](./04-idempotency.md) §3 lớp C — worker nhận biết qua `last_error_code = 'lease_expired'` của row vừa claim, không suy từ `attempt`.

### 5.5 Trạng thái và ai được chuyển

```
PENDING ──claim──▶ SENDING ──ok──▶ SENT ──webhook──▶ DELIVERED
   ▲                 │  │                    └──webhook──▶ BOUNCED
   │                 │  └──retryable──▶ RETRY ──next_attempt_at──▶ (claim lại)
   │                 └──permanent──▶ FAILED
   │
   ├── EXPIRED   (quá expires_at khi được claim)
   └── SKIPPED   (aggregate đã đổi trạng thái, không còn đúng để gửi)
```

| Chuyển | Ai | Điều kiện |
|---|---|---|
| `PENDING/RETRY → SENDING` | claim | `next_attempt_at <= now()` |
| `SENDING → SENT/RETRY/FAILED` | deliver worker | `WHERE status = 'SENDING' AND locked_by = me` (compare-and-set) |
| `SENDING → RETRY` | reaper | `lease_until < now()` |
| `SENT → DELIVERED/BOUNCED` | track worker | theo thứ hạng, xem [03-tracking.md](./03-tracking.md) |
| `* → EXPIRED/SKIPPED` | deliver worker lúc claim | xem [05-ordering.md](./05-ordering.md) |
| `(không tạo) → SKIPPED` | consumer lúc Decide | kênh bị bỏ (`preference_off`, `suppressed`, `duplicate`, `channel_not_allowed`) vẫn có row `SKIPPED` để timeline đầy đủ |
| `PENDING/RETRY → SKIPPED` | consumer `user.deleted` | `skip_reason = user_deleted` |
| `FAILED/EXPIRED → PENDING` | ops redrive | `attempt = 0`, giữ lịch sử lỗi |

### 5.6 Vì sao đủ nhanh

Con số tham khảo trên một node Postgres thông thường (chưa đo trên dự án):

- Câu claim với partial index: 1–3 ms cho 20 row.
- Mỗi worker claim 20 row rồi gửi ~1 s theo rate limit → ~1 claim/s/worker. 60 worker = 60 claim/s.
- Ghi trạng thái: 2 UPDATE/row (`SENDING`, rồi `SENT`). Ở 150 email/s là 300 UPDATE/s.

Pattern này đủ tới vài nghìn notification/s trên một Postgres; vượt mức đó mới cân nhắc Kafka topic nội bộ cho ranh giới 2.

### 5.7 Bẫy hay gặp

- **Dead tuple và bloat.** Mỗi UPDATE Postgres tạo bản ghi mới, bản cũ chờ vacuum. Bảng hot 300 UPDATE/s cần `autovacuum_vacuum_scale_factor` thấp (≈ 0.01) cho riêng bảng này; nếu không `idx_delivery_ready` phình dần và claim chậm dần sau vài ngày — hỏng kiểu "từ từ".
- **Không tách hàng đợi khỏi lịch sử.** Row `SENT` ba tháng trước nằm chung với row `PENDING` hôm nay. Partial index cứu phần đọc, nhưng vacuum/backup vẫn cày cả bảng. Partition theo tháng và archive row hoàn tất sang cold store sau vài ngày.
- **`ORDER BY next_attempt_at` không có trong index** → sort mỗi lần claim. Index ở §5.1 đã bao cột này theo đúng thứ tự.
- **Batch quá lớn so với lease.** Quy tắc: `batch × thời_gian_gửi_1_row < lease / 2`, hoặc dùng heartbeat gia hạn lease.
- **Render nặng trong lúc giữ lease.** Nếu render tốn CPU (PDF hoá đơn), tách thành trạng thái `RENDERED` riêng. Email HTML thông thường không cần.
- **Deliver worker chiếm hết DB connection pool** → consumer Kafka không INSERT được → critical hỏng vì bulk. Quota connection theo lane, xem [06-backpressure.md](./06-backpressure.md) §3.

## 6. Ví dụ đối chiếu: provider chậm 8 s trong 40 phút

| | Một handler làm hết | Hai ranh giới |
|---|---|---|
| Kafka consumer | Rebalance liên tục sau 5 phút; lag tăng 60k; log toàn `Rebalance in progress` | Không biết provider sự cố; lag = 0; không rebalance |
| Email trùng | Mỗi lần rebalance gửi lại phần batch dở → user nhận 2–3 email | Không, nhờ `SKIP LOCKED` + compare-and-set |
| Nguyên nhân gốc | Mất ~40 phút để nhận ra là provider | Alert `pending_age_p99` + `provider_error_rate` bắn sau 1 phút |
| Lựa chọn của ops | Đứng nhìn | Pause bulk để nhường quota; để transactional chờ; theo dõi critical (phase 1 không có provider dự phòng, in-app vẫn đến) |
| Khi provider hồi phục | 12 consumer đồng loạt xả 60k message hết cỡ, thêm đợt trùng | Worker xả ở đúng quota, không dội ngược provider |
| "Email đơn #A1092 gửi lúc nào?" | Có lẽ đã gửi, 2–3 lần, không chắc lúc nào | Một câu SQL: `attempts 3 · 14:02:12 503 · 14:03:05 503 · 14:47:30 SENT` |

Chỉ thêm một bảng và một worker pool — không thêm topic, không thêm hạ tầng.
