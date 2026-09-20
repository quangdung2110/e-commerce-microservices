# Data model

**Trạng thái:** `proposed` — DDL dưới đây là bản thiết kế, chưa phải migration; tên cột và kiểu có thể chỉnh khi implement, nhưng **ranh giới giữa các bảng, chiến lược partition và retention** là quyết định (D-12).

## 1. Một ràng buộc Postgres định hình toàn bộ schema

Postgres yêu cầu **mọi unique constraint / primary key trên bảng partition phải chứa partition key**. Ta cần hai thứ mâu thuẫn:

- **Partition theo tháng** cho các bảng lớn (`notification_request`, `notification_delivery`, `provider_event`, `inbox_item`) — để retention là `DROP PARTITION` tức thì thay vì `DELETE` hàng chục triệu dòng gây bloat, và để vacuum/backup chỉ chạm partition đang nóng.
- **Unique không chứa thời gian** cho dedup: `(source, event_id)`, `(dedup_key, channel)`, `(provider, provider_event_id)`.

Cách giải: mỗi chỗ cần dedup có một **sổ dedup** (`*_dedup`) — bảng nhỏ, không partition, chỉ chứa khoá + timestamp, ghi trong **cùng transaction** với bảng nghiệp vụ, purge theo cửa sổ dedup. Nguyên tắc ở [04-idempotency.md](./04-idempotency.md) lớp A giữ nguyên: dedup trong cùng DB, cùng transaction, không phải Redis — chỉ khác là ở bảng bên cạnh.

Cửa sổ dedup phải ≥ khoảng thời gian dài nhất mà một bản sao có thể quay lại. Kafka retention dài nhất trong các topic Notification đọc là 14 ngày → sổ ingest giữ **30 ngày**.

## 2. Tổng quan

| Bảng | Vai trò | Partition | Retention | Ghi bởi | Đọc bởi |
|---|---|---|---|---|---|
| `ingest_dedup` | Sổ dedup lớp A | Không | 30 ngày | Consumer | Consumer |
| `notification_request` | Một dòng mỗi event đã nhận + kết quả Decide | Tháng (`received_at`) | 90 ngày | Consumer | Ops, support |
| `delivery_dedup` | Sổ dedup lớp B | Không | 30 ngày | Consumer (Decide) | Consumer |
| `notification_delivery` | Hàng đợi + trạng thái từng lần gửi | Tháng (`created_at`) | 90 ngày | Consumer, deliver worker, reaper, track worker | Deliver worker, track worker, ops |
| `provider_event_dedup` | Sổ dedup của Track | Không | 14 ngày | SQS poller | SQS poller |
| `provider_event` | Log thô từ SES, hàng đợi cho Track worker | Tháng (`received_at`) | 30 ngày | SQS poller, track worker | Track worker, ops |
| `user_preference` | Bản replicate từ `t.user.events` | Không | Xoá khi `user.deleted` | Preference consumer, track worker (complaint) | Consumer (Decide), deliver worker |
| `suppression` | Địa chỉ không được gửi | Không | Hard/complaint: vĩnh viễn; soft: theo `until` | Track worker, ops | Consumer (Decide), deliver worker |
| `inbox_item` | Read model in-app | Tháng (`created_at`) | 90 ngày | Deliver worker, inbox API | Inbox API |
| `outbox` | Event Notification phát ra (lớp D) | Không | Đã publish > 7 ngày | Deliver worker, track worker | Debezium |

**Không có bảng** cho: frequency cap (Redis `freq:{user_id}:{date}`, TTL 48 h), unread count (Redis `inbox:unread:{user_id}`, recompute từ `inbox_item` khi miss), cache email (Redis TTL 15 phút, không bao giờ xuống DB — D-07), template (phase 1 nằm trong repo, compile vào service — B6).

## 3. Đường ghi Kafka → DB

### 3.1 `ingest_dedup`

```sql
CREATE TABLE ingest_dedup (
    source       text        NOT NULL,
    event_id     text        NOT NULL,
    received_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (source, event_id)
);
CREATE INDEX idx_ingest_dedup_purge ON ingest_dedup (received_at);
-- purge: DELETE WHERE received_at < now() - interval '30 days', chạy hàng ngày theo batch 10k
```

### 3.2 `notification_request`

```sql
CREATE TABLE notification_request (
    id               uuid        NOT NULL,
    source           text        NOT NULL,                 -- CloudEvents source
    event_id         text        NOT NULL,                 -- CloudEvents id
    event_type       text        NOT NULL,                 -- CloudEvents type
    subject          text,                                 -- aggregate, ví dụ orders/1000234
    input_kind       text        NOT NULL,                 -- request | fact
    template         text,                                 -- NULL nếu fact không ánh xạ được
    lane             text,
    user_id          uuid,
    dedup_key        text,
    expires_at       timestamptz,                          -- effective_expires_at sau Decide
    template_data    jsonb,                                -- NULL với Auth: secret không bao giờ lưu
    correlation_id   text,
    traceparent      text,
    decision         text        NOT NULL,                 -- DECIDED | SKIPPED | FAILED | EXPIRED | IGNORED
    decision_reason  text,                                 -- mã ở 08-decide.md §7
    received_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id, received_at)
) PARTITION BY RANGE (received_at);

CREATE INDEX idx_request_user     ON notification_request (user_id, received_at DESC);
CREATE INDEX idx_request_subject  ON notification_request (subject) WHERE subject IS NOT NULL;
CREATE INDEX idx_request_decision ON notification_request (decision, received_at) WHERE decision <> 'DECIDED';
```

`template_data` là PII mức thấp (tên người nhận, phường/quận). Nó tồn tại để render lại khi điều tra và để `FAILED (invalid_template_data)` có bằng chứng; bị anonymize theo §7.

## 4. Hàng đợi gửi

### 4.1 `delivery_dedup`

```sql
CREATE TABLE delivery_dedup (
    dedup_key   text        NOT NULL,
    channel     text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (dedup_key, channel)
);
-- purge > 30 ngày; max_age lớn nhất là 72 h nên 30 ngày là dư nhiều lần
```

Khoá là **cặp** `(dedup_key, channel)`: một request sinh email + in-app cùng `dedup_key`, unique trên `dedup_key` đơn sẽ chặn nhầm kênh thứ hai.

### 4.2 `notification_delivery`

```sql
CREATE TABLE notification_delivery (
    id                   uuid        NOT NULL,
    request_id           uuid        NOT NULL,
    user_id              uuid,                              -- NULL sau anonymize
    lane                 text        NOT NULL,              -- critical | transactional | bulk
    channel              text        NOT NULL,              -- email | in_app
    template             text        NOT NULL,
    template_version     text,                              -- phiên bản đã render, ghi lúc SENT
    status               text        NOT NULL,              -- PENDING | SENDING | SENT | RETRY | FAILED
                                                            -- | DELIVERED | BOUNCED | EXPIRED | SKIPPED
    attempt              int         NOT NULL DEFAULT 0,
    next_attempt_at      timestamptz NOT NULL,
    lease_until          timestamptz,
    locked_by            text,
    dedup_key            text,
    ordering_key         text,                              -- chỉ template khai báo cần serialize
    expires_at           timestamptz NOT NULL,
    provider             text,                              -- ses | mailpit | inbox
    provider_message_id  text,
    possibly_duplicate   boolean     NOT NULL DEFAULT false,
    last_error_code      text,
    last_error           text,
    skip_reason          text,                              -- channel_reason ở 08-decide.md §7
    sent_at              timestamptz,
    delivered_at         timestamptz,
    finished_at          timestamptz,                       -- thời điểm vào trạng thái cuối
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

-- Index quan trọng nhất: chỉ row đang chờ; tạo tự động trên từng partition
CREATE INDEX idx_delivery_ready
    ON notification_delivery (lane, channel, next_attempt_at)
    WHERE status IN ('PENDING', 'RETRY');

-- Reaper
CREATE INDEX idx_delivery_leased
    ON notification_delivery (lease_until)
    WHERE status = 'SENDING';

-- Track correlate
CREATE INDEX idx_delivery_provider_msg
    ON notification_delivery (provider, provider_message_id)
    WHERE provider_message_id IS NOT NULL;

-- Support / timeline
CREATE INDEX idx_delivery_request ON notification_delivery (request_id);
CREATE INDEX idx_delivery_user    ON notification_delivery (user_id, created_at DESC);
```

Câu claim ở [02-pipeline.md](./02-pipeline.md) §5.2 không đổi. Với bảng partition, planner dùng `idx_delivery_ready` của từng partition và gộp — row `PENDING`/`RETRY` gần như chỉ nằm ở partition tháng hiện tại nên chi phí không đổi.

Autovacuum cho bảng này chỉnh mạnh hơn mặc định:

```sql
ALTER TABLE notification_delivery SET (
    autovacuum_vacuum_scale_factor = 0.01,
    autovacuum_analyze_scale_factor = 0.005
);
```

## 5. Track

### 5.1 `provider_event_dedup`

```sql
CREATE TABLE provider_event_dedup (
    provider           text        NOT NULL,
    provider_event_id  text        NOT NULL,
    received_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, provider_event_id)
);
-- purge > 14 ngày (SQS giữ message tối đa 14 ngày)
```

SES không cấp event id riêng → `provider_event_id = sha256(mail.messageId ‖ eventType ‖ mail.timestamp ‖ bounce/complaint timestamp nếu có)`.

### 5.2 `provider_event`

```sql
CREATE TABLE provider_event (
    id                   bigint      GENERATED ALWAYS AS IDENTITY,
    provider             text        NOT NULL,
    provider_event_id    text        NOT NULL,
    provider_message_id  text,
    event_type           text        NOT NULL,              -- send | delivery | bounce | complaint | reject | delivery_delay
    occurred_at          timestamptz,
    raw                  jsonb       NOT NULL,               -- đã thay mọi địa chỉ email bằng sha256 trước khi ghi
    status               text        NOT NULL DEFAULT 'PENDING',   -- PENDING | PROCESSED | UNMATCHED | IGNORED
    attempt              int         NOT NULL DEFAULT 0,
    next_attempt_at      timestamptz NOT NULL DEFAULT now(),
    received_at          timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id, received_at)
) PARTITION BY RANGE (received_at);

CREATE INDEX idx_provider_event_ready
    ON provider_event (next_attempt_at)
    WHERE status IN ('PENDING', 'UNMATCHED');
CREATE INDEX idx_provider_event_msg ON provider_event (provider_message_id);
```

`raw` của SES chứa `mail.destination[]` và `bouncedRecipients[].emailAddress`. Poller **hash các field đó trước khi INSERT**; bản gốc không bao giờ chạm DB. Correlate không cần địa chỉ (dùng `provider_message_id`), suppression dùng hash.

## 6. Preference và suppression

```sql
CREATE TABLE user_preference (
    user_id            uuid        PRIMARY KEY,
    email_enabled      boolean     NOT NULL,
    in_app_enabled     boolean     NOT NULL,
    marketing_enabled  boolean     NOT NULL,
    timezone           text,                                -- NULL → Asia/Ho_Chi_Minh lúc đọc
    locale             text,                                -- NULL → vi
    version            bigint      NOT NULL,                -- epoch ms của updated_at trong event; compare-and-set
    synced_at          timestamptz NOT NULL DEFAULT now()
);
-- Áp dụng event: UPDATE ... WHERE user_id = ? AND version < ?  (05-ordering.md §6)
```

```sql
CREATE TABLE suppression (
    id                 uuid        PRIMARY KEY,
    user_id            uuid,                                -- NULL sau khi user xoá; hash vẫn giữ
    email_hash         text        NOT NULL,                -- sha256(lower(trim(email)))
    reason             text        NOT NULL,                -- HARD_BOUNCE | COMPLAINT | SOFT_BOUNCE | MANUAL
    source             text        NOT NULL,                -- ses | ops
    until              timestamptz,                         -- NULL = vĩnh viễn
    evidence_event_id  bigint,                              -- provider_event.id
    superseded_at      timestamptz,                         -- user đã đổi email, xem 08-decide.md §4
    created_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_suppression_user ON suppression (user_id) WHERE superseded_at IS NULL;
CREATE INDEX idx_suppression_hash ON suppression (email_hash) WHERE superseded_at IS NULL;
```

Không có cột email rõ ở bất kỳ đâu trong schema. Địa chỉ chỉ tồn tại trong memory của worker giữa lúc resolve và lúc gọi SES, và trong cache Redis 15 phút.

## 7. In-app

```sql
CREATE TABLE inbox_item (
    id           uuid        NOT NULL,                      -- = notification_delivery.id
    user_id      uuid        NOT NULL,
    template     text        NOT NULL,
    title        text        NOT NULL,                      -- ≤ 80 ký tự
    body         text        NOT NULL,                      -- ≤ 200 ký tự
    deep_link    text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    read_at      timestamptz,
    archived_at  timestamptz,
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE INDEX idx_inbox_user_list   ON inbox_item (user_id, created_at DESC) WHERE archived_at IS NULL;
CREATE INDEX idx_inbox_user_unread ON inbox_item (user_id) WHERE read_at IS NULL AND archived_at IS NULL;
```

Tách khỏi `notification_delivery` vì: read path (list theo user, cursor theo `created_at`, đánh dấu đã đọc) khác hẳn write path của hàng đợi; `UPDATE read_at` không nên chạm bảng hot; retention và index khác nhau. Delivery row vẫn tồn tại như bản ghi trạng thái (`DELIVERED` ngay khi `inbox_item` được INSERT).

Unread count: Redis `inbox:unread:{user_id}`; `INCR` sau khi commit `inbox_item`, `DECR` khi mark read; miss hoặc nghi lệch → `SELECT count(*)` bằng `idx_inbox_user_unread` rồi `SET`.

## 8. Outbox

```sql
CREATE TABLE outbox (
    id             uuid        PRIMARY KEY,                 -- = CloudEvents id, sinh lúc INSERT
    aggregatetype  text        NOT NULL,                    -- notification
    aggregateid    text        NOT NULL,                    -- delivery id
    type           text        NOT NULL,                    -- com.ecommerce.notification.delivered.v1 | bounced.v1 | failed.v1 | complaint-received.v1
    payload        jsonb       NOT NULL,                    -- CloudEvents đầy đủ
    created_at     timestamptz NOT NULL DEFAULT now(),
    published_at   timestamptz                              -- chỉ dùng nếu chuyển sang poller
);
```

Tên cột theo **Debezium Outbox Event Router** để dùng CDC qua `kafka-connect` đã có trong compose, nhất quán với Review (ADR-003). Nếu lúc triển khai thấy Debezium quá nặng, đổi sang poller (`SELECT ... FOR UPDATE SKIP LOCKED`, đánh `published_at`) không cần đổi schema. Purge dòng đã publish > 7 ngày (với CDC: Debezium đọc WAL, có thể `DELETE` ngay sau INSERT trong cùng transaction — pattern chuẩn của outbox router).

## 9. Partition và retention

| Bảng | Partition | Tạo trước | Retention | Cơ chế |
|---|---|---|---|---|
| `notification_request` | Tháng | 2 tháng | 90 ngày | `DROP PARTITION` |
| `notification_delivery` | Tháng | 2 tháng | 90 ngày | `DROP PARTITION` — nhưng **kiểm tra** partition không còn row `PENDING`/`RETRY`/`SENDING` trước khi drop (bulk có thể nằm lâu; `max_age` tối đa 72 h nên thực tế không xảy ra, kiểm tra là lưới an toàn) |
| `provider_event` | Tháng | 2 tháng | 30 ngày | `DROP PARTITION` |
| `inbox_item` | Tháng | 2 tháng | 90 ngày | `DROP PARTITION` |
| `ingest_dedup` | — | — | 30 ngày | `DELETE` batch hàng ngày |
| `delivery_dedup` | — | — | 30 ngày | `DELETE` batch |
| `provider_event_dedup` | — | — | 14 ngày | `DELETE` batch |
| `outbox` | — | — | 7 ngày sau publish | `DELETE` batch (hoặc ngay trong tx với CDC) |
| `user_preference`, `suppression` | — | — | Theo vòng đời user | §10 |

Job tạo partition chạy hàng ngày, luôn giữ sẵn partition cho tháng hiện tại + 2 tháng tới; thiếu partition là INSERT lỗi toàn bộ — đây là sự cố kinh điển, cần alert khi partition tương lai < 1 tháng.

### Ước lượng kích thước

Với giả định README §5 (40 request/s): ~3,5M request, ~5M delivery, ~10M provider_event mỗi ngày. Ở ~500 byte/dòng, riêng `notification_delivery` ≈ **75 GB/tháng**, 90 ngày ≈ 225 GB đang sống. Đây là lý do retention là `DROP PARTITION` chứ không phải `DELETE`, và là lời nhắc rằng giả định 40/s có thể cao gấp 10–40 lần thực tế của đồ án — khi có số đo, partition (tháng → tuần hay ngược lại) và retention là thứ đầu tiên hiệu chỉnh.

## 10. `user.deleted`

Auth đã có event xoá account. Trong vòng 24 h sau khi nhận:

| Nơi | Hành động | Lý do |
|---|---|---|
| `user_preference` | `DELETE` | |
| `inbox_item` | `DELETE` (theo `idx_inbox_user_list`) | |
| `notification_request` | `UPDATE SET user_id = NULL, template_data = NULL, subject = NULL` | Giữ `decision`, timestamp để metric và audit không thủng; xoá thứ nhận diện được |
| `notification_delivery` | `UPDATE SET user_id = NULL` | Không có PII khác trong bảng này |
| `suppression` | `UPDATE SET user_id = NULL` | Giữ `email_hash`: địa chỉ chết vẫn là địa chỉ chết; hash không truy ngược được |
| `provider_event.raw` | Không cần | Email đã hash từ lúc ghi |
| Redis `freq:*`, `inbox:unread:*`, cache email | `DEL` | |
| Delivery đang `PENDING`/`RETRY` của user | `UPDATE SET status = 'SKIPPED', skip_reason = 'user_deleted'` | Không gửi cho tài khoản đã xoá; Auth `notification-contact` cũng sẽ trả 404 nên đây là lớp thứ hai |

Quy tắc chung của toàn bộ schema: **không bao giờ lưu bản email đã render**. Cần xem lại thì render lại từ `template` + `template_version` + `template_data` (cho tới khi bị anonymize). Log ứng dụng không chứa email, chỉ chứa `user_id` và `delivery_id`.

## 11. Sơ đồ quan hệ

```
ingest_dedup ──(cùng tx)── notification_request 1 ──── N notification_delivery ──── 0..1 inbox_item (channel = in_app)
                                    │                          │    ▲
                                    │                          │    └── provider_event (correlate qua provider_message_id)
                                    └── user_preference        │
                                        (đọc lúc Decide)       ├── delivery_dedup (cùng tx lúc INSERT)
                                                               ├── suppression (đọc lúc Decide; ghi bởi Track)
                                                               └── outbox (cùng tx lúc đổi trạng thái cuối)
```
