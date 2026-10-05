# Quyết định kiến trúc

**Trạng thái:** `proposed` — các quyết định dưới đây được chốt trong thảo luận thiết kế, chưa được kiểm chứng bằng runtime. Mỗi mục ghi bối cảnh, quyết định, phương án bị loại và hệ quả, để người đọc sau hiểu *tại sao* chứ không chỉ *là gì*. Khi có ADR chính thức theo template của dự án, các mục này là nguồn để viết.

## D-01 · Ba lane ưu tiên, tách ở topic + consumer group + quota

**Bối cảnh.** Notification gánh ba lớp workload có SLO chênh nhau hàng nghìn lần (OTP giây, khuyến mãi giờ). Kafka partition là FIFO; đi chung một topic thì message quan trọng bị kẹt sau message không quan trọng (head-of-line blocking), lỗi không lộ qua metric và xảy ra đúng lúc business cần nhất.

**Quyết định.** Ba topic `t.notification.{critical,transactional,bulk}.v1`, mỗi topic một consumer group, mỗi lane một deliver pool và một phần quota provider dành riêng. Producer chọn lane theo tiêu chí ghi trong contract; Notification là owner phê duyệt.

**Bị loại.**
- *Một topic, Notification tự route*: tầng router chính là điểm nghẽn chung đang cố tránh.
- *Partition range thay cho topic*: phá auto-rebalance, producer phải tự tính partition, ordering theo key không còn đảm bảo; tiết kiệm được hai topic.
- *Tách thêm theo kênh / tenant*: email và push cùng lane không chặn nhau (pool riêng ở ranh giới 2); tách topic chỉ khi hai luồng có SLO khác nhau **và** có thể chặn nhau.

**Hệ quả.** Quyết định lộ ra ngoài contract, khó đảo ngược → phải chốt trước khi producer đầu tiên tích hợp. Cần đàm phán với Auth về `t.auth.notification.events` (đã chốt ở D-19: giữ topic Auth, consume bằng group riêng).

Chi tiết: [01-lanes.md](./01-lanes.md).

## D-02 · Hai ranh giới vật lý; bảng trạng thái làm queue cho ranh giới 2

**Bối cảnh.** Handler đơn "consume → gửi" trói consumer Kafka vào tốc độ provider: provider chậm 8 s → vượt `max.poll.interval.ms` → rebalance → gửi trùng; retry tại chỗ gây head-of-line blocking trong lane; không tạm dừng được riêng khâu gửi.

**Quyết định.** Ranh giới 1: Kafka consumer chỉ validate + INSERT + commit (~10 ms). Ranh giới 2: deliver worker poll bảng `notification_delivery` bằng `UPDATE ... FROM (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING`, với `lease_until` + reaper chống crash, `next_attempt_at` + jitter cho retry, partial index cho row đang chờ. Bảng là source of truth duy nhất.

**Bị loại.**
- *Kafka topic nội bộ giữa mỗi stage*: trạng thái rải nhiều nơi, vẫn cần DB làm source of truth → ghi hai nơi; retry cần retry-topics riêng; khó truy vết một notification.
- *Sáu stage vật lý*: tách thêm chỉ khi số liệu chứng minh cần; hai ranh giới đã giải quyết cả bốn vấn đề của handler đơn.

**Hệ quả.** Postgres gánh thêm ~2 UPDATE/delivery; cần autovacuum riêng cho bảng hot, partition theo tháng, archive row hoàn tất. Trần scale ước tính vài nghìn notification/s trên một node — cao hơn nhu cầu nhiều lần; nghẽn thật nằm ở quota provider. Thứ tự Kafka **mất** ở ranh giới 2 theo chủ ý (xem D-05).

Chi tiết: [02-pipeline.md](./02-pipeline.md).

## D-03 · Track: poller/endpoint mỏng + worker poll; bounce/complaint là hành động

**Bối cảnh.** Webhook provider đến sai thứ tự, đến trùng, đến trước cả khi Notification kịp ghi `provider_message_id`; provider retry hung hãn khi endpoint trả lời chậm; volume webhook có thể gấp 5–10 lần số email gửi.

**Quyết định.** Poller (SQS) hoặc endpoint (HTTP, phase sau) chỉ verify chữ ký → INSERT `provider_event` (raw, append-only, unique `provider_event_id`) → 200. Track worker poll bảng đó, correlate, chuyển trạng thái theo **thứ hạng** (`SENT < DELIVERED < BOUNCED/COMPLAINED`), event không khớp thì `UNMATCHED` + retry chứ không bỏ. Hard bounce / complaint ghi suppression list và tắt marketing preference. Không bật open/click tracking cho bulk. Với SES (D-09): SNS → SQS → poller, không mở endpoint public.

**Bị loại.** *Xử lý đầy đủ trong endpoint*: một lần DB chậm là provider gửi lại → vòng xoáy. *Ghi đè status bằng event mới nhất*: sai với delayed bounce và event đến ngược thứ tự.

**Hệ quả.** Phase 1 không mở endpoint public nào (poll SQS bằng IAM). Khi thêm provider dùng HTTP webhook, Gateway cần route `/webhooks/notification/{provider}` với chính sách như Stripe webhook của Payment. Thêm dependency SNS + SQS. Track worker có quyền ghi suppression và publish event — nó không phải "chỉ cập nhật status".

Chi tiết: [03-tracking.md](./03-tracking.md).

## D-04 · Idempotency bốn lớp; chọn hậu quả theo lane ở lớp provider

**Bối cảnh.** Trùng phát sinh ở sáu điểm dọc pipeline với sáu khoá khác nhau. Khe hở giữa `provider.send()` và `mark_sent` không đóng kín được về lý thuyết.

**Quyết định.**
- Lớp A (Kafka → DB): unique `(source, event_id)` trong sổ `ingest_dedup`, cùng transaction với request; INSERT trước, commit offset sau.
- Lớp B (Decide): `dedup_key` nghiệp vụ theo template trong sổ `delivery_dedup(dedup_key,channel)`, cùng transaction với delivery; OTP **cố ý không có** dedup_key; chặn spam là rate limiting, không phải idempotency.
- Lớp C (DB → provider): compare-and-set khi ghi trạng thái + chính sách theo lane khi lease hết hạn (bulk gửi lại; transactional gửi lại có đánh dấu `possibly_duplicate`; critical **không** gửi lại; in-app gửi lại an toàn vì cùng transaction). Idempotency key phía provider chỉ khi provider hỗ trợ — SES (D-09) không hỗ trợ.
- Lớp D (Notification → Kafka): outbox, `event_id` sinh lúc INSERT.

**Bị loại.** *Bảng `processed_event_ids` riêng hoặc key Redis*: ghi hai nơi không nguyên tử; Redis mất khi restart / hết TTL sớm hơn Kafka retention. *Dedup bằng hash payload*: vừa chặn nhầm OTP hợp lệ vừa bỏ lọt retry đóng gói lại. *Check `if exists` ở tầng ứng dụng*: race đúng lúc rebalance.

**Hệ quả.** Yêu cầu producer retry với cùng CloudEvent id (convention đã có). Vì SES không có idempotency key, tỷ lệ suy ra từ `delivery_possibly_duplicate_total` của lane transactional là chỉ số sức khoẻ của cơ chế lease/heartbeat. Test tích hợp bắt buộc: publish cùng event 3 lần chỉ tạo một effect; kill worker trong khe hở provider/DB phải cho kết quả theo lane (transactional có thể 2 email và cờ `possibly_duplicate`, critical không resend); webhook trùng chỉ apply một lần.

Chi tiết: [04-idempotency.md](./04-idempotency.md).

## D-05 · Ordering: chỉ trong aggregate; Order có sequence và per-subject serialization

**Bối cảnh.** Thứ tự toàn cục vô nghĩa với notification; thứ tự Kafka mất ở ranh giới 2 theo chủ ý; retry phá thứ tự và ta chọn retry. Rủi ro thật là gửi thông tin đã lỗi thời ("đơn đã xác nhận" sau khi đơn đã huỷ).

**Quyết định.** Partition key theo aggregate nhỏ nhất mà thứ tự có nghĩa (`order_id`, `user_id`, `review_id`). Mỗi template có `max_age` → `EXPIRED` nếu quá hạn (OTP 5 phút). Mọi request `/services/order` bắt buộc mang notification `sequence` tăng đơn điệu riêng theo subject. Consumer atomically lưu highest sequence: thấp hơn bị `SKIPPED (superseded)`; cùng sequence + cùng event/payload là durable duplicate; cùng sequence lệch event hoặc payload là contract collision. Deliver worker serialize provider call theo logical `source:subject`; persisted ordering key là versioned HMAC nên không giữ raw subject vô hạn, rồi recheck highest sequence sau khi acquire lease. Preferences đi consumer riêng, áp dụng bằng compare-and-set trên version.

**Bị loại.** *Serialize mặc định theo user*: giảm song song, tái tạo head-of-line blocking. *Dùng Order integration fact sequence*: notification command sẽ tạo gap/ghép sai hai stream có vòng đời khác nhau. *Chỉ dựa Kafka partition*: ordering mất ở deliver worker song song. *Giữ row cũ chặn vô hạn row mới*: một provider failure sẽ làm head-of-line blocking; sequence mới được quyền supersede retry cũ chưa bắt đầu provider call.

**Hệ quả.** Order phải cấp sequence và outbox command trong cùng transaction. Notification cần `notification_subject_sequence`, `notification_ordering_lease`, metric/alert/test collision và lease. Đây chỉ đảm bảo thứ tự bắt đầu provider call trong điều kiện lease hợp lệ; provider có thể timeout sau accept hoặc tự reorder delivery, nên không được dùng notification ordering làm correctness proof cho Order saga. TTL vừa là ordering vừa là load shedding.

Chi tiết: [05-ordering.md](./05-ordering.md).

## D-06 · Backpressure: DB là bể chứa; bulkhead theo lane cho mọi tài nguyên; breaker theo provider, hành động theo lane

**Bối cảnh.** Khi provider chậm, áp lực dồn vào bảng delivery, không dồn lên Kafka hay producer. Tài nguyên dùng chung (thread, connection, quota, cả Auth API) bị lane bulk chiếm hết đúng lúc xả backlog. Retry và breaker sai cách làm provider hồi phục chậm hơn và đẩy row vào DLQ oan.

**Quyết định.**
- Alert theo số lượng và tuổi row pending theo lane; công tắc `pause/resume lane`; không đẩy ngược áp lực lên producer.
- Bulkhead theo `lane × channel` cho thread pool, HTTP client, **DB connection**, và limit phía client cho Auth API.
- Quota provider chia theo lane, có phần dự trữ bulk không được mượn; token bucket trên Redis, fail-closed về mức an toàn khi Redis chết.
- Breaker theo provider; khi mở: critical `RETRY` ngắn + alert ngay (phase 1 không có provider dự phòng, D-09; in-app vẫn gửi), transactional chờ, bulk chờ lâu hơn/tự pause; row bị breaker từ chối **không tăng `attempt`**; half-open thăm dò bằng row lane thấp.
- Retry budget ≤ 20 % tổng lượng gửi/phút. Timeout mọi hop ngắn hơn lease; heartbeat gia hạn lease.

**Bị loại.** *Quota chung, bulk mượn phần dư của critical*: critical mất đường đi đúng lúc cần. *Breaker theo lane*: provider chết là chết cho cả ba. *Fail-open limiter khi Redis chết*: vượt quota → provider phạt cả account.

**Hệ quả.** Tốn quota "lãng phí" ở lane critical lúc bình thường — giá của việc critical luôn có đường đi. Cần Redis cho limiter phân tán (đã có trong hạ tầng).

Chi tiết: [06-backpressure.md](./06-backpressure.md).

## D-07 · Hai loại đầu vào: request và fact, phân loại theo "dữ liệu render đang ở đâu"

**Bối cảnh.** Các producer hiện dùng hai mô hình: Auth/Review gửi *yêu cầu* (chỉ định template), Order/Payment/Delivery/Cart phát *sự thật*. Fact-style thuần cho Order không chạy được với contract hiện tại: `order.paid.v1` chỉ có `order_id, user_id, sku_ids` (không đủ render), Order không phát event huỷ/hoàn tiền khi chưa có consumer đăng ký, và không event nào mang email. Request-style thuần thì bắt Cart đóng gói lại thứ họ đã phát.

**Quyết định.** Hybrid, với quy tắc: **request khi producer phải đóng gói dữ liệu nó đang sở hữu hoặc phải quyết định điều kiện gửi; fact khi payload sẵn có `user_id` và đủ để render.** Producer **không bao giờ gọi service khác** để gom dữ liệu cho request — nếu một template đòi hỏi điều đó, thu gọn template hoặc bổ sung snapshot ở nguồn.

| Nguồn | Mô hình | Lý do |
|---|---|---|
| Auth | Request (resolve content theo `delivery_id`) | Secret không đi qua Kafka |
| Review | Request | Điều kiện gửi là nghiệp vụ của Review |
| **Order** | **Request** (`notification.requested.v1`, 5 template, `template_data` lấy từ row đơn hàng) | Order sở hữu item snapshot, tổng tiền, địa chỉ; tránh Notification gọi ngược Order lúc render; khớp chính sách "business effect đăng ký trước" của Order |
| Cart | Fact | Payload có `user_id` và đủ cho lời nhắc |
| **Payment, Delivery** | **Không consume** | Fact của họ **không có `user_id`** (`payment-event-common.schema.json` đặt `"user_id": false`; Delivery chỉ có `order_id`/`order_line_id`). Notification không biết gửi cho ai nếu không replicate Order. Order đã consume các fact này và phát request `ORDER_PAYMENT_FAILED`, `ORDER_REFUNDED`, `ORDER_COMPLETED` thay. |

*Ghi chú lịch sử:* bản đầu của D-07 xếp Payment/Delivery vào fact vì giả định payload có `user_id`. Soi schema thật cho thấy không; quy tắc không đổi, chỉ áp dụng lại cho đúng.

Cả hai loại chuẩn hoá về một `NotificationRequest` ở Ingest; từ Decide trở đi pipeline không phân biệt. Với fact, "gửi gì / lane nào / `dedup_key` gì" nằm trong **bảng ánh xạ có review** trong Notification. **Email luôn do Notification resolve lúc gửi** qua Auth internal API (không đi qua Kafka: retention, replication, không xoá được, có thể lỗi thời); cache 15 phút trong Redis/memory, không ghi DB.

**Bị loại.** *Fact thuần cho Order + Notification gọi ngược Order lúc render*: thêm dependency trên đường gửi, Notification tấn công Order khi xả backlog, và vẫn cần Order mở internal read API. *Fact từ Payment/Delivery + bảng `order_id → user_id` trong Notification*: chính là replicate trạng thái Order dưới tên khác. *Đưa email vào event*: PII rải khắp Kafka và backup, không xoá được theo `user.deleted`.

**Hệ quả.** Ba việc cần mở với service khác (README §7): topic `t.notification.*.v1` + schema `notification.requested.v1` + catalog template; `GET /internal/v1/auth/users/{user_id}/notification-contact`; snapshot đơn hàng của Order có `name` sản phẩm. Order gánh 5 mốc gửi request. Hai loại consumer phải maintain. Read-your-latest (D-05) thu hẹp: với request, producer chịu trách nhiệm không phát request lỗi thời; Notification không replicate trạng thái đơn hàng.

Chi tiết: [README §4](./README.md), [02-pipeline.md](./02-pipeline.md) §2.1–2.3, [07-templates.md](./07-templates.md) §6.

## D-08 · Kênh phase 1: email + in-app

**Bối cảnh.** `user-preferences-updated.v1` chỉ có `email_enabled`, `in_app_enabled`, `marketing_enabled`. Không có contract nào về device token hay số điện thoại.

**Quyết định.** Phase 1 chỉ email và in-app. Push và SMS ở phase sau.

**Bị loại (tạm).** *Push*: cần hệ con quản lý device token, xử lý `UNREGISTERED`, User Service thêm preference, frontend đăng ký token — thay đổi contract của nhiều bên. *SMS*: chi phí và ràng buộc pháp lý (opt-in, brand name với nhà mạng) không đáng khi chưa có traffic thật.

**Hệ quả.** Pool ở ranh giới 2: `{critical,transactional,bulk} × {email,inapp}`. In-app không có dependency ngoài (ghi vào DB của Notification) nên là kênh duy nhất còn hoạt động khi SES hoặc Auth API sự cố; delivery in-app và email của cùng request độc lập nhau. `channel` là enum, adapter interface chung — thêm push sau là thêm adapter + pool + preference flag, không thiết kế lại. In-app buộc thiết kế inbox API và `unread_count` trên Redis ngay từ phase 1.

## D-09 · Provider: Amazon SES cho production, Mailpit cho dev/CI, không có provider dự phòng

**Bối cảnh.** Mục tiêu dự án là học tập; traffic thật thấp. Cần provider rẻ, và cần cách test email cục bộ (compose hiện chưa có mail server giả lập).

**Quyết định.**
- **SES** cho production: rẻ nhất (~$0,10/1.000; free tier 3.000/tháng năm đầu). Hai configuration set khớp lane (`critical-transactional`, `bulk`), bulk không bật open/click. Tracking qua **SNS → SQS → poller**, không mở HTTP endpoint.
- **Mailpit** trong `docker-compose.yml` cho dev/CI; adapter `smtp` dùng Mailpit, test tự động đọc hộp thư qua REST API của Mailpit.
- **Không có provider dự phòng** ở phase 1. Khi breaker SES mở: critical `RETRY` 30 s không tăng `attempt`, alert ngay, in-app vẫn gửi, OTP quá `expires_at` tự hết hạn. Adapter interface phải cho phép cắm provider thứ hai sau này mà không sửa worker.

**Bị loại.** *Postmark*: deliverability và onboarding tốt hơn, Message Streams khớp lane tự nhiên, nhưng đắt gấp ~15 lần — không đáng cho mục tiêu học tập. *Provider dự phòng*: domain verification, DKIM, warm-up, giám sát cho provider thứ hai không xứng khi chưa có traffic.

**Hệ quả.** SES **không có idempotency key** → D-04 lớp C dựa hoàn toàn vào compare-and-set + chính sách theo lane. SES sandbox (1/s, 200/ngày, chỉ gửi tới địa chỉ verify) → phải xin production access trước khi có user thật; dev không dùng SES. Bounce > 5 % / complaint > 0,1 % → account bị review → suppression list là bắt buộc từ ngày đầu. Quota là của AWS account: Notification nên là nơi duy nhất gọi SES. Phụ thuộc thêm SNS + SQS (theo dõi `ApproximateAgeOfOldestMessage`).

Chi tiết: [03-tracking.md](./03-tracking.md) §2–3, [06-backpressure.md](./06-backpressure.md) §4–5.

## D-10 · Catalog template là contract; chính sách preference theo lane

**Bối cảnh.** Producer gọi template bằng tên và đóng gói `template_data` theo schema → tên và schema là giao diện công khai. Preference của User chỉ có ba cờ; cần quy tắc rõ khi cờ xung đột với bản chất của thông báo (user tắt email rồi quên mật khẩu).

**Quyết định.**
- Catalog sống ở `contracts/events/notification/v1/templates/*.schema.json`, owner Notification, định danh `UPPER_SNAKE_CASE` ổn định, versioning như event (breaking → `<ID>_V2` + deprecated), vòng đời `proposed → active → deprecated → retired`. Mỗi template khai báo `lane`, `channels`, `governed_by`, `max_age`, công thức `dedup_key`; producer chỉ được rút ngắn `max_age`, không được đổi lane.
- Phase 1: 15 template — 7 critical (Auth, giữ tên enum của Auth), 5 transactional từ Order (`ORDER_CONFIRMED`, `ORDER_PAYMENT_FAILED`, `ORDER_CANCELLED`, `ORDER_COMPLETED`, `ORDER_REFUNDED`), 2 transactional từ Review (`REVIEW_REPLIED`, `REVIEW_REMOVED`), 1 bulk từ Cart (`CART_ABANDONED`).
- Chính sách theo lane, template không ghi đè: **critical bỏ qua `email_enabled`, suppression và quiet hours** (cả 7 template, kể cả 4 cảnh báo sau sự kiện); transactional tôn trọng `email_enabled`/`in_app_enabled`, không quiet hours; bulk tôn trọng cả ba cờ, quiet hours 22:00–08:00 theo timezone user (mặc định `Asia/Ho_Chi_Minh`), frequency cap 2/ngày/user.
- Mỗi template có hai bản render (email, in-app) trên cùng `template_data`; `vi` bắt buộc; không PII ngoài thứ cần hiển thị; producer gửi URL tuyệt đối (ngoại lệ: `cart_url` do Notification lắp vì fact không có chỗ).

**Bị loại.** *Cho 4 template cảnh báo bảo mật tuân theo `email_enabled`*: cảnh báo "mật khẩu của bạn vừa bị đổi" không nên tắt được — đó là lớp phòng thủ khi tài khoản bị chiếm. *`ORDER_LINE_DELIVERED`*: quá nhiều email cho một đơn; `ORDER_COMPLETED` đủ. *`ORDER_CREATED`*: user đang nhìn màn hình đặt hàng. *`REFUND_FAILED`*: vấn đề vận hành, user không làm gì được. *Notification tự lắp URL*: phải biết routing frontend.

**Hệ quả.** Order target contract đã khóa nguồn tên sản phẩm: Checkout
`items[].snapshot_name` -> `order_item_lines.snapshot_name` -> notification
`items[].name`, không runtime RPC (README §7 mục 3). Review email không có tên sản phẩm và **không có URL** (contract Review không mang) — Notification lắp `review_url` như `cart_url` (ngoại lệ thứ hai của quy ước URL, ghi ở 07 §2 và D-20). `CART_ABANDONED` là lời nhắc + link, không có danh sách sản phẩm cho tới khi Cart bổ sung snapshot. Cần bảng `user_preference` replicate có timezone khi User publish; trước đó dùng mặc định.

Chi tiết: [07-templates.md](./07-templates.md).

## D-11 · Decide chạy trong transaction của consumer; thứ tự quy tắc cố định; suppression theo `user_id` + `email_hash`

**Bối cảnh.** Sơ đồ ban đầu để "decide" ở worker ranh giới 2, nhưng worker chỉ claim được delivery đã tồn tại — phải có ai đó tạo chúng. Decide chỉ cần dữ liệu cục bộ (preference, suppression, sổ dedup, một lệnh Redis), không có RPC. Suppression truyền thống khoá theo địa chỉ email, nhưng Decide chạy trước khi biết email (D-07).

**Quyết định.**
- Decide chạy **trong cùng transaction** với `INSERT notification_request` ở ranh giới 1 (+3–5 ms). `INSERT delivery` cùng transaction → không có request mồ côi. Worker ranh giới 2 chỉ resolve email, render, gửi; re-check rẻ lúc claim: `expires_at`, `marketing_enabled` (bulk), suppression theo hash sau khi resolve.
- Thứ tự 13 bước cố định: lỗi của producer trước (`unknown_template`, `lane_mismatch`, `invalid_template_data`, `missing_dedup_key`, `no_recipient` → DLQ + alert), rồi hết hạn, rồi chính sách theo user (preference → suppression → frequency cap → quiet hours → dedup). Mã lý do là contract vận hành.
- Chưa có preference → mặc định `email_enabled = true`, `in_app_enabled = true`, `marketing_enabled = false`, timezone `Asia/Ho_Chi_Minh`, locale `vi`.
- Suppression ghi cả `user_id` và `email_hash`; Decide chỉ *ghi nhận* theo `user_id` (metric), worker **quyết định** theo hash sau khi resolve — **hash khác nghĩa là user đã đổi email → suppression hết hiệu lực** (D-20 làm rõ: bỏ kênh ngay tại Decide sẽ chặn user vĩnh viễn sau khi đổi email). Không lưu email rõ ở đâu.
- Frequency cap (bulk, 2/ngày/user) đếm ở Decide trên Redis; Redis lỗi → fail-open + alert. Quiet hours đặt `next_attempt_at` một lần; không tính lại.
- Dedup nghiệp vụ khoá theo **`(dedup_key, channel)`**, không phải `dedup_key` đơn — một request sinh nhiều kênh cùng `dedup_key`. Sửa lỗi của bản đầu 02 §5.1.
- In-app đi qua cùng worker path như email; không render trong consumer.

**Bị loại.** *Worker decide riêng*: thêm trạng thái và worker phải vận hành mà không có lợi ích vì không có RPC. *Suppression chỉ theo email*: Decide không biết email; và suppression sẽ dính vĩnh viễn với user dù họ đã đổi địa chỉ. *Frequency cap fail-closed khi Redis chết*: chặn toàn bộ bulk vì một dependency phụ là phản ứng quá tay. *Render in-app trong consumer*: đưa lỗi template vào đường Kafka, hai đường code cho hai kênh.

**Hệ quả.** Consumer transaction rộng hơn (5–6 câu lệnh) — vẫn xa `max.poll.interval.ms`. Track worker cần đọc được `user_id` từ delivery khi ghi suppression. `suppression.superseded_at` để đánh dấu dòng đã bị vượt qua bởi email mới.

Chi tiết: [08-decide.md](./08-decide.md).

## D-12 · Data model: partition theo tháng + sổ dedup không partition; inbox tách riêng; outbox tương thích Debezium; anonymize khi `user.deleted`

**Bối cảnh.** Bảng lớn cần partition theo tháng để retention là `DROP PARTITION` (ước lượng ở tải giả định: ~75 GB/tháng riêng `notification_delivery`). Postgres không cho unique constraint thiếu partition key trên bảng partition, nhưng dedup cần unique không chứa thời gian. In-app có read path khác hẳn hàng đợi. Review đã dùng outbox CDC và `kafka-connect` đã có trong compose. `user.deleted` phải xoá được PII mà không thủng metric.

**Quyết định.**
- Bốn bảng lớn partition theo tháng (`notification_request`, `notification_delivery`, `provider_event`, `inbox_item`); PK gồm partition key. Ba **sổ dedup** không partition (`ingest_dedup`, `delivery_dedup`, `provider_event_dedup`) ghi cùng transaction với bảng nghiệp vụ; purge 30/30/14 ngày.
- `inbox_item` tách khỏi `notification_delivery`: read path (list theo user, mark read) không chạm bảng hot; delivery row vẫn là bản ghi trạng thái. Unread count ở Redis, recompute khi miss.
- `outbox` theo schema Debezium Outbox Event Router; publish qua CDC như Review; đổi sang poller không cần đổi schema.
- Không có cột email rõ trong toàn schema: `suppression.email_hash`, `provider_event.raw` đã hash trước khi ghi, `template_data` của Auth không lưu. Không lưu bản email đã render.
- Retention: request/delivery/inbox 90 ngày, provider_event 30 ngày. Job tạo partition trước 2 tháng + alert khi partition tương lai < 1 tháng.
- `user.deleted` trong 24 h: `DELETE` preference và inbox; **anonymize** request/delivery (`user_id`, `template_data`, `subject` → NULL) để giữ metric; `suppression.user_id → NULL` giữ hash; skip delivery đang chờ; xoá key Redis.
- Trước anonymize, ghi versioned HMAC deleted-user tombstone để replay cũ không tái tạo
  inbox; Order subject high-watermark/ordering key cũng dùng versioned HMAC thay raw subject.
- Frequency cap, unread count, cache email chỉ ở Redis — không có bảng.

**Bị loại.** *Không partition, `DELETE` theo retention*: bloat và vacuum không kịp ở tải giả định. *Unique trên bảng partition có thêm partition key*: mất ý nghĩa dedup. *Dedup bằng Redis*: mất khi restart / TTL sớm hơn Kafka retention. *In-app dùng chung bảng delivery*: `UPDATE read_at` chạm bảng hot, index và retention xung đột. *Xoá hẳn request/delivery khi user xoá*: thủng metric và audit; anonymize đủ cho yêu cầu xoá PII. *Lưu email đã render*: bản PII thứ hai, render lại từ `template_data` là đủ.

**Hệ quả.** Ba bảng dedup là ba job purge phải vận hành. Số liệu partition/retention phải hiệu chỉnh khi có số đo thật (giả định 40/s có thể cao 10–40 lần). Cần Debezium connector cho Postgres của Notification (hoặc poller nếu quá nặng cho môi trường học tập).

Chi tiết: [09-data-model.md](./09-data-model.md).

## D-13 · Contract Notification sở hữu: ba channel request khai báo ngay, một topic outcome, schema chỉ cho template đi qua Kafka, sửa thẳng contract của service khác

**Bối cảnh.** Cần contract thật để Order bắt đầu việc của họ và để mesh/gateway khai báo policy. Dự án có convention AsyncAPI/CloudEvents với `x-*` bắt buộc, `interactions.yaml` là nguồn của ma trận AuthorizationPolicy, và một người duy trì toàn bộ repo.

**Quyết định.**
- `contracts/events/notification/v1/asyncapi.yaml` khai báo **cả ba** channel request (`t.notification.{critical,transactional,bulk}.v1`, key `subject`) dù phase 1 chỉ transactional có producer; và **một** channel outcome `t.notification.outcomes.v1` (key `user_id`) với bốn type `delivered`, `bounced`, `failed`, `complaint-received`.
- `schemas/notification-requested.schema.json` dùng `oneOf` theo `template` để `template_data` bị ràng buộc đúng schema và `dedup_key` đúng công thức; `additionalProperties: false` chặn PII lọt vào. Đã kiểm tra chặn 6 lớp lỗi bằng negative test.
- `templates/catalog.yaml` liệt kê đủ 15 template với `data_source`; **chỉ 5 template Order có file schema** — Auth/Review/Cart đã có schema ở contract của họ, sao chép là tạo hai nguồn sự thật.
- Sửa thẳng contract của Auth (`notification-contact`), Order (`publishNotificationRequests`), `services.yaml`, `interactions.yaml` (7 interaction), mesh §5.2 + retry, gateway route — tất cả `proposed`.

**Bị loại.** *Chỉ khai báo transactional*: producer đầu tiên của critical/bulk phải chờ một PR contract; topic rẻ, hạ tầng lane provision đồng nhất. *Bốn topic outcome*: consumer Analytics cần bốn subscription; các service khác đều dùng một topic nhiều type. *Schema đầy đủ cho cả 15 template*: trùng lặp với Auth OpenAPI và Review schema. *Chỉ soạn đề xuất, không sửa contract của service khác*: thêm một vòng vô nghĩa khi một người duy trì cả repo; convention owner vẫn được tôn trọng bằng `proposed` và ghi rõ trong 10-interfaces §7.

**Hệ quả.** Hai topic trống ở phase 1. Order đã xác nhận deterministic `snapshot_name`
source trong target contract nhưng vẫn cần producer test/runtime evidence. User và Analytics
phải đăng ký consumer phía họ. Mọi thay đổi `catalog.yaml`/`templates/*.schema.json` là thay đổi contract công khai.

Chi tiết: [10-interfaces.md](./10-interfaces.md) §2–3, §7.

## D-14 · Ba bề mặt HTTP: inbox qua Gateway, status internal cho support, ops internal-only

**Bối cảnh.** In-app cần read API cho frontend; support cần trả lời "email đơn này gửi chưa"; SRE cần công tắc pause/redrive/suppression. Preference đã có API ở User Service. Không có webhook nào (SES → SNS → SQS).

**Quyết định.**
- **Inbox** (`contracts/http/notification/v1/openapi.yaml`, qua Gateway, `gatewayIdentity`, `no-store`): list cursor `(created_at, id)` ≤ 50, `unread-count` từ Redis với contract ghi rõ có thể lệch vài giây, `PATCH {read}`, `DELETE` = archive, `POST mark-all-read`. Không realtime phase 1 (poll 30–60 s); không API preference; không API lịch sử email.
- **Status** (`internal/v1`, mTLS, caller `order-service`, `finance-ops`): theo `request_id` và theo `subject`; trả timeline, không `template_data`, không email.
- **Ops** (`internal/v1/notifications/ops/**`, mTLS principal `notification-ops`, **không qua Gateway**): `PUT lanes/{lane}`, `POST deliveries/{id}/redrive`, `POST dlq/redrive` (202), `POST/DELETE suppressions` (không gỡ được `HARD_BOUNCE`/`COMPLAINT`), `PUT replay-mode`. `Idempotency-Key` bắt buộc, audit mọi call. Phase 1 có thể là CLI dùng chung handler.

**Bị loại.** *Ops qua Gateway như `/api/v1/admin/**` của Order*: admin của Order là người dùng nghiệp vụ hằng ngày; ops của Notification là SRE với thao tác hiếm và nguy hiểm — không đáng mở ra edge. *Realtime phase 1*: Gateway đã hỗ trợ WebSocket cho Chat nên thêm sau chỉ là route; poll đủ cho badge. *Gỡ được HARD_BOUNCE/COMPLAINT qua API*: làm hỏng reputation với provider; user đổi email hoặc bounce mới sẽ tự cập nhật.

**Hệ quả.** Gateway thêm một route; mesh thêm 3 dòng policy và 3 dòng retry; principal `notification-ops` phải tồn tại trong mesh. Support tooling của Order/Finance có endpoint để tích hợp.

Chi tiết: [10-interfaces.md](./10-interfaces.md) §5–6.

## D-15 · Runtime Go

**Bối cảnh.** Notification là workload I/O-bound thuần (chờ Kafka, Postgres, Auth API, SES); không có phần CPU nặng ngoài render template. Dự án đã có Auth, User, Cart bằng Go và Product, Inventory, Search, Review, Promotion bằng Java; Payment dự kiến Java. Mục tiêu học tập, chạy nhiều container trên một máy.

**Quyết định.** Go, tại `go/services/notification-service`. Consumer Kafka, deliver worker pool, SQS poller, reaper, HTTP server là các goroutine group trong một binary; lane chọn bằng biến môi trường (`LANE`) hoặc chạy tất cả trong một process ở dev.

**Bị loại.** *Java 21 virtual threads*: hoàn toàn khả thi và có thư viện Kafka/Resilience4j phong phú, nhưng footprint lớn hơn cho đồ án nhiều container, và không có lý do kỹ thuật nào buộc phải cùng stack với các service Java (giao tiếp chỉ qua Kafka và HTTP).

**Hệ quả.** B6 (template engine) chọn trong hệ sinh thái Go: `html/template` + `text/template` là mặc định an toàn (auto-escaping theo ngữ cảnh); MJML nếu cần email responsive thì compile trước ở build time. Development guide theo layout Go của dự án (`docs/common`). Kiểu chứa email phải implement `fmt.Stringer` che giá trị ([11-privacy.md](./11-privacy.md) §3).

## D-16 · PII: Notification không lưu danh tính; hash thay địa chỉ; anonymize trong 24 giờ

**Bối cảnh.** Notification chạm vào email của mọi user nhưng không phải nơi sở hữu danh tính. Kafka không xoá được từng bản ghi. Audit cần trả lời "PII ở đâu, sống bao lâu, ai xoá".

**Quyết định.** Gom thành [11-privacy.md](./11-privacy.md): bản đồ dữ liệu cá nhân theo bảng/Redis/event với retention và cơ chế xoá; email chỉ tồn tại trong memory và cache 15 phút; `email_hash` (`sha256(lower(trim))`, không salt) ở suppression và log provider; không lưu bản render; `template_data` của Auth không lưu; quy tắc log/metric/trace (không email, không label theo người); quy trình 7 bước cho `USER_DELETED` với test tích hợp kiểm chứng; checklist review template.

**Bị loại.** *Salt cho `email_hash`*: mất khả năng so khớp giữa bounce hôm nay và suppression tháng trước; hash không salt của một địa chỉ email là pseudonymous đủ cho mục đích này khi không lưu bảng tra ngược. *Xoá hẳn request/delivery khi user xoá*: thủng metric; anonymize đủ. *Lưu bản render để support xem lại*: bản PII thứ hai; render lại là đủ.

**Hệ quả.** Mọi PR thêm template/nguồn phải qua checklist §7. Thêm SMS sau này phải đi cùng đường (resolve lúc gửi, không lưu, hash).

## D-17 · Render: `html/template`, template trong repo với `embed`, validate lúc khởi động

**Bối cảnh.** `template_data` đến từ service khác qua Kafka và có thể chứa HTML/JS; email đã gửi không được lưu nên phải render lại được; lỗi template phát hiện lúc gửi nghĩa là DLQ đầy giữa đêm.

**Quyết định.** `html/template` + `text/template` của Go (escaping theo ngữ cảnh, không dependency). Template nằm trong repo, `embed` vào binary, một thư mục mỗi `TEMPLATE_ID` × locale với `email.subject.txt`, `email.html`, `email.txt`, `inapp.yaml`. `template_version` = 12 ký tự đầu `sha256` nội dung thư mục + layout. `registry.Load()` lúc khởi động parse với `missingkey=error`, **render thử** mọi template với fixture sinh từ schema, kiểm tra giới hạn in-app và kích thước HTML < 100 KB, kiểm tra catalog embed byte-equal với `contracts/` — lỗi thì process không lên. Model typed, không map. CSS inline và `List-Unsubscribe` (bulk) ở build. Snapshot test golden cho mọi template; test injection cố định. Không I/O trong Render; không lỗi render nào retryable.

**Bị loại.** *Template trong DB, đổi không cần deploy*: mất kiểm soát thay đổi trên thứ gửi thẳng tới user; không có đội content riêng để cần nó. *Engine bên thứ ba*: escaping không theo ngữ cảnh hoặc phụ thuộc developer nhớ filter. *MJML lúc chạy*: toolchain Node cho 15 template; nếu cần thì compile ở build. *Message catalog i18n*: mỗi template là file trọn vẹn theo locale đủ cho 15 × 2.

**Hệ quả.** Đổi template = deploy; rollback binary = rollback template. Người viết template dùng `notifctl render --send-to mailpit`. Golden diff xuất hiện trong PR.

Chi tiết: [12-render.md](./12-render.md).

## D-18 · Môi trường dev: Mailpit + LocalStack sau compose profile; một binary nhiều `ROLE`

**Bối cảnh.** SES sandbox không dùng được cho test lặp lại; đường Track cần SNS/SQS; compose mặc định đang được các service khác dùng.

**Quyết định.** `docker-compose.yml` thêm `mailpit` (SMTP `1025`, UI/API `8025`) và `localstack` (SNS/SQS, `4566`) dưới profile `notification` — `docker compose up` mặc định không đổi. `init-db.sh` tạo `notification_db`. Script `deploy/localstack/init-ses-events.sh` dựng topic → queue (+ DLQ, `maxReceiveCount 5`, envelope SNS giữ nguyên để verify chữ ký). Connector Debezium `deploy/debezium/notification-outbox-connector.json` route `t.notification.outcomes.v1`, key `aggregateid = user_id`, publication tạo bằng migration. Service là một binary với `ROLE`/`LANE`; dev chạy `ROLE=all`. Cổng HTTP host `8091` inbox và `8092` internal (`8090` là dashboard Traefik). Adapter SES thật chỉ có contract test khi có credential; internal API nhận `X-Service-Identity` không xác thực **chỉ** khi `NOTIFICATION_APP_ENV=development` (D-20 sửa tên biến).

**Bị loại.** *SES sandbox ở dev*: cần credential, chỉ gửi tới địa chỉ verify, 200/ngày. *LocalStack SES cho đường gửi*: không sinh event thật; Mailpit cho đường gửi + event mẫu bơm vào SNS cho đường Track là đủ và rõ ràng. *Nhiều binary theo vai trò*: hai đường code cho cùng logic.

**Hệ quả.** Testcontainers cần ghim Docker API version trên máy dev hiện tại. `go/services/notification-service/` tồn tại trước khi có code (chỉ `deploy/` và README).

Chi tiết: [13-development.md](./13-development.md), [14-observability.md](./14-observability.md), [15-verification.md](./15-verification.md), [16-operations.md](./16-operations.md).

## D-19 · Auth giữ topic riêng `t.auth.notification.events`; Notification consume như lane critical thứ hai

**Bối cảnh.** Auth đã có contract `t.auth.notification.events` với payload chỉ có `delivery_id` và nội dung resolve qua `POST .../notification-deliveries/{id}/content` (secret không lên Kafka). Schema chung `notification.requested.v1` mang `template_data` inline, không khớp mô hình đó nếu không thêm biến thể "reference-only".

**Quyết định.** Giữ nguyên topic và contract của Auth. Notification chạy consumer group riêng `notification-auth-critical` đọc topic này; sau Ingest, row được gán `lane = critical` và đi chung pool, quota, chính sách với lane critical (01-lanes §4 vẫn đúng: tách lane ở ranh giới 2). Channel `t.notification.critical.v1` vẫn được khai báo trong contract Notification cho producer critical tương lai (không có producer ở phase 1).

**Bị loại.** *Auth chuyển sang `t.notification.critical.v1`*: cần biến thể schema không có `template_data` inline, sửa contract Auth đang `proposed` và mesh; không có lợi ích vận hành đáng kể vì Auth là producer critical duy nhất. *Gộp cả hai vào một consumer group*: một group không subscribe hai topic với hai schema mà vẫn giữ cô lập rebalance sạch.

**Hệ quả.** Thêm một consumer group phải vận hành và theo dõi lag (`kafka_consumer_lag{lane=critical,topic=t.auth.notification.events}`). Nếu sau này có producer critical thứ hai dùng schema chung, xem xét lại việc gộp — không phá gì vì channel đã tồn tại.

## D-20 · Làm rõ lúc implement: những chỗ tài liệu thiếu hoặc sai so với contract thật

**Bối cảnh.** Khi viết code phase 1 đối chiếu từng dòng tài liệu với contract và code Auth hiện có, một số chỗ thiếu quyết định hoặc sai chi tiết. Ghi lại để tài liệu và code đồng bộ 100 %.

**Quyết định.**
- **Auth request gán template ở Ingest** từ CloudEvents `type` (+ `purpose`); Decide cần template (kênh, dedup, max_age). Bốn template cảnh báo lưu `template_data` từ event; ba template OTP/reset không lưu (secret). Kênh email gọi `content`; kênh in-app của cảnh báo không gọi Auth. `source_ref` giữ `delivery_id` của Auth ở request và delivery.
- **Kênh bị bỏ ở Decide vẫn có row delivery `SKIPPED` + `skip_reason`** để timeline support đầy đủ; thêm `skip_reason` `email_unverified`, `superseded`, `user_deleted`.
- **DLQ có hai nghĩa rõ ràng**: topic `*.dlq` chỉ cho envelope hỏng / lỗi hạ tầng quá số lần retry; lỗi contract producer là row `notification_request.decision = FAILED` (bảng là DLQ).
- **Auth internal API ở cổng 8081** (không phải 8080 như bản đầu của 13-development và Auth OpenAPI `servers`). `GET .../notification-contact` được implement trong auth-service cùng đợt.
- **Hai listener HTTP**: 8080 inbox (qua Gateway, host 8091), 8081 status/ops/metrics/health (host 8092).
- **Ops state trong Postgres** (`ops_control`, `ops_idempotency`, `ops_audit`) thay vì memory — nhiều process phải cùng thấy pause/replay.
- **`notification_delivery` có `subject`** (denormalize cho outcome event, anonymize cùng `user_id`) và `source_ref`; `notification_request` có `event_time`, `source_ref`.
- **`request_id` của outcome = `notification_request.id`** (UUID mới), không phải CloudEvents id.
- **Lớp C3 nhận biết bằng `last_error_code = lease_expired`**; `release` trả `attempt` về khi breaker/rate-limit/pause từ chối; `max_attempts` theo lane → `FAILED (provider_error)`.
- **URL do Notification lắp** cho hai nguồn không có chỗ cho URL: `cart_url` (Cart) và `review_url` (Review).
- **`List-Unsubscribe` không one-click** ở phase 1 (cần endpoint public chưa có contract); **CSS inline viết tay**, không inliner; **chỉ locale `vi`**.
- **Outbox poller** có sẵn cạnh Debezium (`outbox.publisher`), compose dev dùng poller.
- **Consumer group** cho nguồn chưa đặt tên: `notification-review-transactional`, `notification-cart-bulk`, `notification-preferences`, `notification-user-lifecycle`.
- **Suppression được thực thi ở worker, không ở Decide**: Decide bước 9 chỉ ghi metric; worker so `email_hash` sau khi resolve — nếu không, user đổi email vẫn bị chặn mãi (IT-SUPP-02).
- **`track.correlate_provider`** (`ses` production, `mailpit` dev): Track correlate event SES với delivery do provider nào gửi, vì dev bơm event SES mẫu cho mail Mailpit đã gửi.
- **Track**: `Complaint` không đổi status; `Reject` → `BOUNCED` (ghi chú); `UNMATCHED` retry giãn dần, alert sau 3 lần, `IGNORED` sau 24 h.
- **Cách 1 ordering cho fact là no-op phase 1** (Cart không có fact nào vượt qua `cart.abandoned`).
- **Order ordering là ngoại lệ phase 1**: request bắt buộc có sequence riêng; consumer giữ highest sequence và deliver worker dùng per-subject lease. Không gọi ngược Order và không dùng integration fact sequence.
- **`internal/catalog`** giữ bản sao byte-equal của catalog và 5 schema ORDER_*, cộng 10 schema viết tại chỗ cho template mà contract nguồn không có schema `template_data` riêng.

**Hệ quả.** Các file 02, 03, 04, 05, 06, 07, 08, 09, 10, 11, 12, 13, 14 đã được sửa tương ứng; `contracts/http/notification/internal/v1/openapi.yaml` và Auth internal OpenAPI đổi `servers` sang 8081; catalog ghi `note` cho `REVIEW_REPLIED`.

## Quyết định chưa chốt

Không còn. D-01…D-20 khép kín thiết kế phase 1; việc tiếp theo là chạy bộ test tích hợp đầy đủ và thay giả định bằng số đo ([15-verification.md](./15-verification.md) §3).
