# Quyết định kiến trúc

**Trạng thái:** `proposed` — các quyết định dưới đây được chốt trong thảo luận thiết kế, chưa được kiểm chứng bằng runtime. Mỗi mục ghi bối cảnh, quyết định, phương án bị loại và hệ quả, để người đọc sau hiểu *tại sao* chứ không chỉ *là gì*. Khi có ADR chính thức theo template của dự án, các mục này là nguồn để viết.

## D-01 · Ba lane ưu tiên, tách ở topic + consumer group + quota

**Bối cảnh.** Notification gánh ba lớp workload có SLO chênh nhau hàng nghìn lần (OTP giây, khuyến mãi giờ). Kafka partition là FIFO; đi chung một topic thì message quan trọng bị kẹt sau message không quan trọng (head-of-line blocking), lỗi không lộ qua metric và xảy ra đúng lúc business cần nhất.

**Quyết định.** Ba topic `t.notification.{critical,transactional,bulk}.v1`, mỗi topic một consumer group, mỗi lane một deliver pool và một phần quota provider dành riêng. Producer chọn lane theo tiêu chí ghi trong contract; Notification là owner phê duyệt.

**Bị loại.**
- *Một topic, Notification tự route*: tầng router chính là điểm nghẽn chung đang cố tránh.
- *Partition range thay cho topic*: phá auto-rebalance, producer phải tự tính partition, ordering theo key không còn đảm bảo; tiết kiệm được hai topic.
- *Tách thêm theo kênh / tenant*: email và push cùng lane không chặn nhau (pool riêng ở ranh giới 2); tách topic chỉ khi hai luồng có SLO khác nhau **và** có thể chặn nhau.

**Hệ quả.** Quyết định lộ ra ngoài contract, khó đảo ngược → phải chốt trước khi producer đầu tiên tích hợp. Cần đàm phán với Auth về `t.auth.notification.events` (quyết định mở #5 trong [README](./README.md)).

Chi tiết: [01-lanes.md](./01-lanes.md).

## D-02 · Hai ranh giới vật lý; bảng trạng thái làm queue cho ranh giới 2

**Bối cảnh.** Handler đơn "consume → gửi" trói consumer Kafka vào tốc độ provider: provider chậm 8 s → vượt `max.poll.interval.ms` → rebalance → gửi trùng; retry tại chỗ gây head-of-line blocking trong lane; không tạm dừng được riêng khâu gửi.

**Quyết định.** Ranh giới 1: Kafka consumer chỉ validate + INSERT + commit (~10 ms). Ranh giới 2: deliver worker poll bảng `notification_delivery` bằng `UPDATE ... FROM (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING`, với `lease_until` + reaper chống crash, `next_attempt_at` + jitter cho retry, partial index cho row đang chờ. Bảng là source of truth duy nhất.

**Bị loại.**
- *Kafka topic nội bộ giữa mỗi stage*: trạng thái rải nhiều nơi, vẫn cần DB làm source of truth → ghi hai nơi; retry cần retry-topics riêng; khó truy vết một notification.
- *Sáu stage vật lý*: tách thêm chỉ khi số liệu chứng minh cần; hai ranh giới đã giải quyết cả bốn vấn đề của handler đơn.

**Hệ quả.** Postgres gánh thêm ~2 UPDATE/delivery; cần autovacuum riêng cho bảng hot, partition theo tháng, archive row hoàn tất. Trần scale ước tính vài nghìn notification/s trên một node — cao hơn nhu cầu nhiều lần; nghẽn thật nằm ở quota provider. Thứ tự Kafka **mất** ở ranh giới 2 theo chủ ý (xem D-05).

Chi tiết: [02-pipeline.md](./02-pipeline.md).

## D-03 · Track: endpoint mỏng + worker poll; bounce/complaint là hành động

**Bối cảnh.** Webhook provider đến sai thứ tự, đến trùng, đến trước cả khi Notification kịp ghi `provider_message_id`; provider retry hung hãn khi endpoint trả lời chậm; volume webhook có thể gấp 5–10 lần số email gửi.

**Quyết định.** Endpoint chỉ verify chữ ký → INSERT `provider_event` (raw, append-only, unique `provider_event_id`) → 200. Track worker poll bảng đó, correlate, chuyển trạng thái theo **thứ hạng** (`SENT < DELIVERED < BOUNCED/COMPLAINED`), event không khớp thì `UNMATCHED` + retry chứ không bỏ. Hard bounce / complaint ghi suppression list và tắt marketing preference. Không bật open/click tracking cho bulk. Với SES, ưu tiên SNS → SQS.

**Bị loại.** *Xử lý đầy đủ trong endpoint*: một lần DB chậm là provider gửi lại → vòng xoáy. *Ghi đè status bằng event mới nhất*: sai với delayed bounce và event đến ngược thứ tự.

**Hệ quả.** Gateway cần route `/webhooks/notification/{provider}` với chính sách như Stripe webhook của Payment. Track worker có quyền ghi suppression và publish event — nó không phải "chỉ cập nhật status".

Chi tiết: [03-tracking.md](./03-tracking.md).

## D-04 · Idempotency bốn lớp; chọn hậu quả theo lane ở lớp provider

**Bối cảnh.** Trùng phát sinh ở sáu điểm dọc pipeline với sáu khoá khác nhau. Khe hở giữa `provider.send()` và `mark_sent` không đóng kín được về lý thuyết.

**Quyết định.**
- Lớp A (Kafka → DB): unique `(source, event_id)` trên `notification_request`; INSERT trước, commit offset sau; row nghiệp vụ chính là bản ghi dedup.
- Lớp B (Decide): `dedup_key` nghiệp vụ theo template, partial unique index; OTP **cố ý không có** dedup_key; chặn spam là rate limiting, không phải idempotency.
- Lớp C (DB → provider): idempotency key phía provider khi có + compare-and-set khi ghi trạng thái + chính sách theo lane khi lease hết hạn (bulk gửi lại; transactional gửi lại có đánh dấu; critical **không** gửi lại).
- Lớp D (Notification → Kafka): outbox, `event_id` sinh lúc INSERT.

**Bị loại.** *Bảng `processed_event_ids` riêng hoặc key Redis*: ghi hai nơi không nguyên tử; Redis mất khi restart / hết TTL sớm hơn Kafka retention. *Dedup bằng hash payload*: vừa chặn nhầm OTP hợp lệ vừa bỏ lọt retry đóng gói lại. *Check `if exists` ở tầng ứng dụng*: race đúng lúc rebalance.

**Hệ quả.** Yêu cầu producer retry với cùng CloudEvent id (convention đã có). "Có idempotency key không" là tiêu chí chọn provider cho lane critical. Test tích hợp bắt buộc: publish cùng event 3 lần + kill worker giữa chừng + webhook trùng → assert số email = 1.

Chi tiết: [04-idempotency.md](./04-idempotency.md).

## D-05 · Ordering: chỉ trong aggregate; bảo vệ bằng kiểm tra trạng thái + TTL, không serialize

**Bối cảnh.** Thứ tự toàn cục vô nghĩa với notification; thứ tự Kafka mất ở ranh giới 2 theo chủ ý; retry phá thứ tự và ta chọn retry. Rủi ro thật là gửi thông tin đã lỗi thời ("đơn đã xác nhận" sau khi đơn đã huỷ).

**Quyết định.** Partition key theo aggregate nhỏ nhất mà thứ tự có nghĩa (`order_id`, `user_id`, `review_id`). Trước khi gửi, worker kiểm tra trạng thái hiện tại của aggregate (cần `sequence` từ producer) → `SKIPPED` nếu đã lỗi thời. Mỗi template có `max_age` → `EXPIRED` nếu quá hạn (OTP 5 phút). Serialize theo `ordering_key` chỉ bật cho template khai báo cần. Preferences đi consumer riêng, áp dụng bằng compare-and-set trên version.

**Bị loại.** *Serialize mặc định theo user*: subquery trong claim, giảm song song, tái tạo head-of-line blocking. *Giữ thứ tự nghiêm ngặt qua retry*: một row fail chặn mọi row sau nó.

**Hệ quả.** Cần producer kèm `sequence`/version trong event (convention đã có extension). TTL vừa là ordering vừa là load shedding.

Chi tiết: [05-ordering.md](./05-ordering.md).

## D-06 · Backpressure: DB là bể chứa; bulkhead theo lane cho mọi tài nguyên; breaker theo provider, hành động theo lane

**Bối cảnh.** Khi provider chậm, áp lực dồn vào bảng delivery, không dồn lên Kafka hay producer. Tài nguyên dùng chung (thread, connection, quota, cả Auth API) bị lane bulk chiếm hết đúng lúc xả backlog. Retry và breaker sai cách làm provider hồi phục chậm hơn và đẩy row vào DLQ oan.

**Quyết định.**
- Alert theo số lượng và tuổi row pending theo lane; công tắc `pause/resume lane`; không đẩy ngược áp lực lên producer.
- Bulkhead theo `lane × channel` cho thread pool, HTTP client, **DB connection**, và limit phía client cho Auth API.
- Quota provider chia theo lane, có phần dự trữ bulk không được mượn; token bucket trên Redis, fail-closed về mức an toàn khi Redis chết.
- Breaker theo provider; khi mở: critical failover provider, transactional chờ, bulk chờ lâu hơn/tự pause; row bị breaker từ chối **không tăng `attempt`**; half-open thăm dò bằng row lane thấp.
- Retry budget ≤ 20 % tổng lượng gửi/phút. Timeout mọi hop ngắn hơn lease; heartbeat gia hạn lease.

**Bị loại.** *Quota chung, bulk mượn phần dư của critical*: critical mất đường đi đúng lúc cần. *Breaker theo lane*: provider chết là chết cho cả ba. *Fail-open limiter khi Redis chết*: vượt quota → provider phạt cả account.

**Hệ quả.** Tốn quota "lãng phí" ở lane critical lúc bình thường — giá của việc critical luôn có đường đi. Cần Redis cho limiter phân tán (đã có trong hạ tầng).

Chi tiết: [06-backpressure.md](./06-backpressure.md).

## Quyết định chưa chốt

Xem [README §6](./README.md#6-quyết-định-đang-mở): kênh phase 1, provider email, command vs fact cho Order lifecycle, runtime, và topic của Auth. Các mục D-01…D-06 không phụ thuộc vào câu trả lời của những câu hỏi này, trừ D-04 lớp C (phụ thuộc provider có idempotency key hay không).
