# Kiểm chứng

**Trạng thái:** `proposed`

Bộ tài liệu này chứa nhiều khẳng định ("gửi đúng 1 email dù 5 điểm hỏng", "OTP đến trong 5 giây khi bulk đang xả") và nhiều con số giả định (README §5). File này liệt kê **test nào chứng minh khẳng định nào** và **phép đo nào thay con số nào**. Không có test và số đo, trạng thái vẫn là `proposed`.

## 1. Tầng test

| Tầng | Chạy ở đâu | Phụ thuộc | Mục đích |
|---|---|---|---|
| Unit | `go test ./...`, mọi PR | Không | Decide thuần (bảng 13 bước), template func, state ranking, backoff/jitter, hash email, cursor |
| Golden / snapshot | `go test ./internal/templates/`, mọi PR | Không | Render 15 template × locale so với `.golden`; test injection |
| Contract | mọi PR | Không | Consumer đọc được mọi example trong `contracts/examples/events/`; producer (outcome) sinh event validate được bằng schema; OpenAPI request/response validate |
| Integration | `go test -tags integration`, mọi PR (testcontainers) | Postgres, Redis, Kafka, Mailpit, LocalStack | Các kịch bản §2 |
| Load / soak | Thủ công hoặc nightly, môi trường riêng | Compose đầy đủ | Thay số giả định §3 |
| Chaos (nhỏ) | Nightly | Như integration | Kill worker giữa chừng, tắt Redis, làm chậm Mailpit |

Testcontainers cần ghim Docker API version trên máy dev hiện tại (ghi nhớ của dự án: `api.version=1.44`, `TESTCONTAINERS_HOST_OVERRIDE`).

## 2. Test tích hợp bắt buộc

Mỗi test dưới đây gắn với một quyết định; PR làm test fail phải sửa code hoặc sửa quyết định, không được xoá test.

### 2.1 Idempotency (D-04)

| ID | Kịch bản | Assert | Chứng minh |
|---|---|---|---|
| IT-IDEM-01 | Publish cùng event (cùng `id`) 3 lần lên `t.notification.transactional.v1` | 1 `notification_request`, 2 `notification_delivery` (email + in_app), 1 email trong Mailpit | Lớp A: `ingest_dedup` |
| IT-IDEM-02 | Publish 2 event khác `id`, cùng `dedup_key`, cùng template | 2 request (1 `DECIDED`, 1 `SKIPPED duplicate`), 2 delivery, 1 email | Lớp B: `(dedup_key, channel)` |
| IT-IDEM-03 | Publish 2 request `PASSWORD_RESET` khác `delivery_id` cho cùng user (giả lập Auth) | 2 email | Lớp B cố ý không dedup OTP/reset |
| IT-IDEM-04 | Kill deliver worker ngay sau `provider.send()` trả về, trước `mark_sent`; reaper chạy; worker mới claim | Lane transactional: 2 email, delivery có `possibly_duplicate = true`. Lane critical: 1 email, delivery `FAILED (lease_expired)` | Lớp C3 |
| IT-IDEM-05 | Bơm cùng event SES `Delivery` 2 lần vào SNS | 1 `provider_event` (dedup), delivery `DELIVERED` một lần, 1 event outbox | Lớp D + Track dedup |
| IT-IDEM-06 | Bơm `Delivery` rồi `Send` (ngược thứ tự), rồi `Bounce` | Trạng thái cuối `BOUNCED`; `Send` không hạ trạng thái | State ranking |
| IT-IDEM-07 | Bơm `Delivery` **trước khi** worker commit `provider_message_id` (tạm dừng worker) | Event `UNMATCHED` rồi `PROCESSED` sau khi worker commit; không mất event | Race correlate |

### 2.2 Lane và chính sách preference (D-01, D-10, D-11)

| ID | Kịch bản | Assert |
|---|---|---|
| IT-LANE-01 | 5.000 request bulk vào hàng đợi; sau đó 1 request critical (giả lập Auth) | Critical `SENT` trong < 5 s dù bulk còn > 4.000 row pending; consumer group và pool không chia sẻ |
| IT-LANE-02 | Pause lane bulk qua ops API; publish bulk và transactional | Bulk vẫn được INSERT (`PENDING`) nhưng không gửi; transactional gửi bình thường; resume → bulk xả |
| IT-PREF-01 | `email_enabled = false`, gửi `ORDER_CONFIRMED` | Không email; in-app có |
| IT-PREF-02 | `email_enabled = false`, gửi `PASSWORD_RESET` (critical) | **Email vẫn gửi** |
| IT-PREF-03 | `marketing_enabled = false`, `CART_ABANDONED` | `SKIPPED (all_channels_off)`; không delivery nào |
| IT-PREF-04 | Không có row `user_preference`, gửi `ORDER_CONFIRMED` và `CART_ABANDONED` | Order: email + in-app; Cart: `SKIPPED` (mặc định marketing off) |
| IT-PREF-05 | Timezone `Asia/Ho_Chi_Minh`, đồng hồ giả 23:00, `CART_ABANDONED` | Delivery `PENDING` với `next_attempt_at = 08:00 hôm sau`; không gửi trước đó |
| IT-PREF-06 | 3 bulk trong một ngày cho cùng user | 2 gửi, 1 `SKIPPED (frequency_cap)` |
| IT-PREF-07 | Tắt Redis, gửi bulk | Vẫn gửi (fail-open), metric `frequency_cap_degraded` = 1 |
| IT-SUPP-01 | Bơm hard bounce cho delivery của user U; gửi `ORDER_CONFIRMED` cho U | Email `SKIPPED (suppressed)`, in-app vẫn gửi; `PASSWORD_RESET` cho U vẫn gửi email |
| IT-SUPP-02 | Sau IT-SUPP-01, đổi email của U ở Auth giả lập; gửi `ORDER_CONFIRMED` | Email gửi; dòng suppression có `superseded_at` |
| IT-SUPP-03 | Bơm complaint | Suppression `COMPLAINT`; `user_preference.marketing_enabled = false`; event `complaint-received` trên outcome topic |

### 2.3 Contract của producer (D-07, D-13)

| ID | Kịch bản | Assert |
|---|---|---|
| IT-CTR-01 | Request với `template = FOO` | `FAILED (unknown_template)`, row trong DLQ, alert metric tăng, **không** retry |
| IT-CTR-02 | Request `ORDER_CONFIRMED` publish lên topic bulk | `FAILED (lane_mismatch)` |
| IT-CTR-03 | `template_data` có thêm field `email` | `FAILED (invalid_template_data)` — schema `additionalProperties: false` |
| IT-CTR-04 | Thiếu `dedup_key` với template `required` | `FAILED (missing_dedup_key)` |
| IT-CTR-05 | Mọi file trong `contracts/examples/events/notification-*.json` | Consumer chấp nhận; event outcome sinh ra validate được bằng schema tương ứng |
| IT-CTR-06 | Sáu negative case của schema request (đã chạy tay khi viết contract) | Đưa vào CI như test cố định |

### 2.4 Đường gửi và Track (D-09)

| ID | Kịch bản | Assert |
|---|---|---|
| IT-SEND-01 | `ORDER_CONFIRMED` end-to-end | Mailpit có email đúng subject; HTML không chứa `<script>` khi `name` chứa script; header `X-Notification-Delivery-Id`; bulk có `List-Unsubscribe` |
| IT-SEND-02 | Mailpit chậm 8 s (toxiproxy) với 100 row transactional | Consumer lag = 0 suốt; không rebalance; không email trùng; `pending_age` tăng rồi xả |
| IT-SEND-03 | Mailpit trả lỗi 100 % trong 2 phút | Breaker mở; row critical `RETRY` với `attempt` không tăng; alert metric; hồi phục → xả ở đúng quota |
| IT-TRACK-01 | Bơm `Delivery`/`Bounce`/`Complaint` mẫu (JSON thật của SES, đã ẩn danh) | Correlate đúng; `raw` không chứa địa chỉ email rõ (assert regex trên toàn bảng) |
| IT-TRACK-02 | Message SNS chữ ký sai | Không INSERT; log `invalid_signature`; xoá khỏi queue |

### 2.5 In-app và API (D-14)

| ID | Kịch bản | Assert |
|---|---|---|
| IT-INBOX-01 | 60 item cho user; list `limit=20` ba trang | Không trùng, không thiếu, `next_cursor = null` cuối |
| IT-INBOX-02 | Mark read 2 lần; archive 2 lần | 200/204 cả hai lần; unread count đúng; item archive không xuất hiện |
| IT-INBOX-03 | User A gọi item của user B | `404` |
| IT-INBOX-04 | Xoá key Redis unread rồi gọi unread-count | Recompute đúng từ `inbox_item` |
| IT-OPS-01 | `POST .../redrive` cùng `Idempotency-Key` 2 lần; lần 3 khác body | 200, 200 (cùng kết quả), 409 |
| IT-OPS-02 | Gỡ suppression `HARD_BOUNCE` | `409` |

### 2.6 Dữ liệu và privacy (D-12, D-16)

| ID | Kịch bản | Assert |
|---|---|---|
| IT-DEL-01 | Tạo đủ dữ liệu cho user U (request, delivery, inbox, preference, suppression, Redis); publish `USER_DELETED` | Sau xử lý: `count(*) WHERE user_id = U` = 0 trên mọi bảng; `template_data`/`subject` NULL ở request của U; `email_hash` của U vẫn còn trong suppression với `user_id NULL`; Redis key biến mất; delivery đang chờ → `SKIPPED (user_deleted)` |
| IT-PII-01 | Chạy toàn bộ bộ test trên với log ở mức `debug` | Grep toàn bộ log và mọi bảng: không có chuỗi khớp regex email (`[^@\s]+@[^@\s]+\.[a-z]{2,}`) ngoài Mailpit |
| IT-PART-01 | Đồng hồ giả tới tháng sau khi chưa tạo partition | Job tạo partition chạy trước; INSERT không lỗi; metric `partition_future_months` đúng |
| IT-PART-02 | DROP partition tháng cũ có row `PENDING` | Job từ chối drop, alert |

## 3. Đo để thay giả định

README §5 giả định 40 request/s ổn định, burst 300k/2 phút, SES 50/s. Mỗi con số cần một phép đo, trên môi trường compose đầy đủ với Mailpit (không giới hạn) rồi với rate limiter bật ở quota giả lập:

| Giả định | Phép đo | Kịch bản | Số cần ghi |
|---|---|---|---|
| Consumer ~15 ms/message | `ingest_duration_seconds` p99 | 10.000 request liên tục, 1 consumer | p50/p99, message/s trần của một consumer |
| Claim 1–3 ms | `delivery_claim_duration_seconds` với 1M row lịch sử + 10k pending | Nạp dữ liệu giả 1M `SENT`, đo claim | p99 trước/sau `VACUUM`; xác nhận partial index được dùng (`EXPLAIN`) |
| Postgres chịu 300 UPDATE/s | `pg_stat` + latency | 60 worker gửi vào Mailpit không giới hạn | UPDATE/s đạt được, dead tuple ratio sau 30 phút, autovacuum có theo kịp |
| Xả 300k bulk ở 15/s ≈ 5,5 giờ | `delivery_pending_count` theo thời gian | 300k request bulk, quota 15/s | Thời gian xả thực; critical p99 trong lúc đó (phải < 5 s) |
| ~75 GB/tháng ở 40/s | `pg_total_relation_size` | Sau kịch bản trên | Byte/row thực của `notification_delivery`, `notification_request`, `provider_event` |
| Lease 60 s, batch 20 an toàn | `delivery_lease_expired_total` | Mailpit chậm 10 s, batch 20, không heartbeat | Có lease expired không → quyết định heartbeat |
| Auth contact cache 15 phút đủ | `contact_cache_hit_ratio` | Chiến dịch bulk 100k user, mỗi user 1–2 thông báo | Hit ratio; req/s tới Auth giả lập |

Kết quả ghi vào README §5 với ngày đo và commit; giả định nào lệch > 2× thì mở lại quyết định liên quan (partition theo tuần thay tháng, batch size, pool size).

## 4. Chaos nhỏ, chạy nightly

| Kịch bản | Kỳ vọng |
|---|---|
| `docker kill` deliver worker mỗi 30 s trong 10 phút dưới tải | Không mất delivery; `possibly_duplicate` thấp (< 0,1 %) nhờ lease; critical không gửi lại |
| `docker pause redis` 2 phút | Bulk vẫn gửi (fail-open cap); limiter fail-closed về mức an toàn; unread recompute khi Redis trở lại |
| Toxiproxy Mailpit: latency 8 s + 30 % lỗi trong 30 phút | Diễn biến như [06-backpressure.md](./06-backpressure.md) §2: Kafka lag 0, breaker theo ngưỡng, retry budget chặn bão retry |
| `docker pause postgres` 30 s | Consumer dừng commit offset (không mất event); worker lỗi claim và retry; hồi phục không cần can thiệp |
| Kafka rebalance ép (thêm/bớt consumer) | Không gửi trùng (dedup lớp A), không mất |

## 5. Tiêu chí hoàn thành kiểm chứng

- Mọi test §2 pass trong CI trên PR.
- §3 có số đo cho từng dòng, ghi vào README §5.
- §4 chạy được bằng một lệnh và có báo cáo.
- Contract test cho SES thật (sandbox, credential riêng) pass ít nhất một lần trước khi nâng `contract_adoption` lên `complete`.
