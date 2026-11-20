# Backpressure, cô lập lỗi và giới hạn provider

**Trạng thái:** `implemented` (phase 1) — số liệu tải trong file này là giả định thiết kế, chưa đo (README §5).

## 1. Áp lực chảy về đâu

Nhìn pipeline như hệ thống ống nước và hỏi: khi đầu ra tắc, nước dâng ở đâu?

```
Producer ─▶ Kafka ─▶ Consumer ─▶ DB (bảng delivery) ─▶ Deliver worker ─▶ Provider
                       │              ▲                       │
                       │              └── nước dâng ở đây ────┘  khi provider chậm
                       └── Kafka KHÔNG dâng: consumer chỉ ghi DB (nhanh)
```

Nhờ ranh giới 2, **DB là bể chứa hấp thụ áp lực**; Kafka và producer không cảm nhận gì. Đó là mong muốn — nhưng bể nào cũng có đáy. Ba câu hỏi phải trả lời trước, không phải lúc 3 giờ sáng:

| Câu hỏi | Trả lời |
|---|---|
| **Bể chứa được bao nhiêu?** | 300k row `PENDING` là vài trăm MB — không vấn đề. 30 triệu row (provider chết 2 ngày giữa chiến dịch) thì partial index vẫn ổn nhưng vacuum, backup, disk bắt đầu có chuyện. Alert theo **số lượng** và **tuổi** của row pending, theo lane. |
| **Khi đầy thì đổ đi cái gì?** | *Load shedding.* TTL per template ([05-ordering.md](./05-ordering.md) §4) là cơ chế shedding tự nhiên: bulk quá 24 h tự hết hạn. Cộng công tắc vận hành `pause lane=bulk`: consumer bulk vẫn nhận và ghi DB, deliver worker bulk dừng — nhường toàn bộ quota cho critical và transactional. |
| **Có cần đẩy ngược áp lực lên producer không?** | **Không.** Order Service không nên biết provider đang chết; nó phát event và đi tiếp. Đây là điểm khác với hệ thống đồng bộ và là lý do event-driven hợp với notification. |

## 2. Ví dụ dẫn đường: provider chập chờn 2 giờ

Kịch bản dùng xuyên suốt file này: provider email trả 40 % lỗi từ 10:00 đến 12:00, giữa lúc có 200k email bulk đang xả.

| Giờ | Điều xảy ra | Cơ chế nào làm việc |
|---|---|---|
| 10:00 | Lỗi bắt đầu. Retry rải ra nhờ jitter. Sau 3 phút retry budget chạm 20 % → row fail mới bị đẩy xa 15 phút. Traffic tới provider giữ ~1,2× bình thường thay vì 3× | §4 backoff + jitter, §6 retry budget |
| 10:05 | Breaker chưa mở (40 % < 50 %). Alert `provider_error_rate` bắn. Critical vẫn 100 % thành công: retry sau 30 s hầu như OK, và critical có quota riêng nên không bị bulk chiếm | §3 bulkhead, §4 quota theo lane |
| 10:10 | Ops bật `pause lane=bulk`. Provider chỉ còn nhận critical + transactional (~5/s); tỷ lệ lỗi thực tế với hai lane này giảm vì ít tranh chấp | §1 load shedding |
| 10:00–12:00 | DB tích ~250k row bulk `PENDING`. Vài trăm MB. Kafka lag = 0 suốt. Order, Cart, Auth không biết gì | §1 bể chứa |
| 12:00 | Provider hồi phục. Ops `resume lane=bulk`. Xả 250k row ở đúng 15/s quota → ~4,6 giờ. Row `cart_abandoned` cũ hơn 24 h đã `EXPIRED`, giảm ~10 % lượng phải gửi | §4 rate limiter, TTL |
| Kết quả | **Không** email trùng do rebalance; **không** OTP trễ; **không** row nào vào DLQ oan; **không** upstream nào bị ảnh hưởng | |

## 3. Bulkhead: vách ngăn khoang tàu

"Khoang" là **mọi tài nguyên hữu hạn mà một luồng có thể chiếm hết**:

| Tài nguyên | Nếu dùng chung | Tách thế nào |
|---|---|---|
| Thread / goroutine gửi | 50 thread kẹt hết ở email 8 s → in-app không ghi được dù DB khoẻ | Pool riêng theo `lane × channel`: `critical-email` 6, `critical-inapp` 4, `bulk-email` 30, ... |
| HTTP connection pool tới provider | Pool 100 kết nối đầy → worker critical chờ kết nối | Client HTTP riêng cho từng `lane × provider`, `max_connections` riêng |
| **DB connection pool** | Bulk worker chiếm 40/50 kết nối để claim → Kafka consumer critical không có kết nối để INSERT → **critical hỏng vì bulk** | Quota riêng: consumer 10, deliver-critical 5, deliver-transactional 10, deliver-bulk 15, track 5, dự phòng 5 |
| Quota provider | §4 | |
| **Auth internal API** (resolve email — `notification-contact` cho mọi nguồn, `content` cho Auth) | Khi xả 300k backlog, Notification gọi Auth 150 req/s → Auth quá tải → **đăng nhập chậm vì email khuyến mãi** | Limit phía client riêng theo lane (bulk 20 req/s, transactional 30, critical không giới hạn nhưng có breaker), cache email theo `user_id` 15 phút trong Redis/memory, batch API nếu Auth cung cấp. Với Auth `content` API không cache (chứa secret, `no-store`) |

Dòng cuối là dạng lỗi rất thật và khó nhìn ra: Notification được thiết kế để không làm phiền ai, nhưng lúc xả backlog nó trở thành kẻ tấn công upstream. **Mọi dependency Notification gọi ra đều cần giới hạn phía client**, không chỉ provider.

Kênh in-app không có dependency ngoài nào (ghi vào DB của chính Notification), nên nó không cần bulkhead riêng ngoài quota DB connection — và là kênh duy nhất còn hoạt động khi cả SES lẫn Auth API sự cố.

## 4. Rate limiter: khớp quota provider

Provider nào cũng có giới hạn, vượt là bị phạt chứ không chỉ chậm. Với **SES** (D-09):

- **`MaxSendRate`** (email/giây) và **`Max24HourSend`** (email/ngày) do AWS cấp theo account, tăng dần theo lịch sử gửi. Account mới ở **sandbox**: 1 email/s, 200 email/ngày, và **chỉ gửi được tới địa chỉ đã verify** — phải xin production access trước khi có user thật. Vượt rate → `Throttling` (454/400); vượt daily → SES từ chối tới hết chu kỳ 24 h, **kể cả OTP**.
- **Bounce rate > 5 % hoặc complaint rate > 0,1 %** → account bị đưa vào review; cao hơn nữa → bị tạm dừng gửi. Đây là lý do suppression list ([03-tracking.md](./03-tracking.md) §5.2) không phải tính năng phụ.
- Quota là **của account**, không của service: nếu sau này service khác cũng dùng cùng SES account, quota bị chia. Notification nên là nơi duy nhất gọi SES.

(Để tham khảo cho phase sau: FCM giới hạn theo project với `429` + `Retry-After`; Twilio xếp hàng phía họ, queue dài thì tin nhắn trễ hàng giờ.)

### 4.1 Chia quota theo lane, có phần dự trữ không ai được đụng

Với `MaxSendRate = 50/s` sau khi ra khỏi sandbox (giả định, README §5):

| Lane | Quota | Ghi chú |
|---|---|---|
| Critical | 10/s | Dùng thường xuyên ~0,5/s; phần dư **không cho bulk mượn** |
| Transactional | 20/s | |
| Bulk | 15/s | |
| Dự trữ | 5/s | Cho critical đột biến (sự cố buộc reset password hàng loạt) |

Với `Max24HourSend`: bulk phải dừng khi tổng ngày đạt 80 %, để 20 % cho hai lane kia. Bộ đếm ngày là bộ đếm **theo account**, đặt trong Redis, mọi lane cùng tăng.

Nghe lãng phí, nhưng đây là giá của việc critical **luôn** có đường đi. Ở sandbox (1/s, 200/ngày) các con số này vô nghĩa — dev và CI dùng **Mailpit**, không dùng SES.

### 4.2 Limiter phải phân tán

4 replica deliver-bulk không thể mỗi replica tự cho mình 15/s — tổng sẽ là 60/s. Hai cách:

| Cách | Ưu | Nhược |
|---|---|---|
| Chia tĩnh: mỗi replica được `quota / số_replica` | Không phụ thuộc gì | Phải cập nhật khi scale; replica chết thì quota của nó bị bỏ phí |
| Token bucket dùng chung trên Redis (một lệnh Lua nguyên tử) | Chính xác, tự thích ứng khi scale | Thêm ~0,3 ms/lần gửi (không đáng kể so với 200 ms gọi provider); thêm dependency |

Dự án đã có Redis nên token bucket hợp lý. Nhưng khi Redis chết, limiter phải **fail-closed về mức an toàn** (mỗi replica tự giới hạn ở mức thấp, ví dụ `quota / max_replica`), không fail-open thành không giới hạn.

## 5. Circuit breaker: ngắt mạch, không phải retry nhanh hơn

Retry với backoff giải quyết lỗi lẻ tẻ. Khi provider **chết hẳn** (100 % lỗi trong 2 phút), retry chỉ là 30 worker cùng đập vào cánh cửa đóng — tốn thread, tốn connection, làm chậm việc provider hồi phục. Breaker là cách nói "tôi biết anh đang chết, tôi không hỏi nữa trong 30 giây":

```
CLOSED ──[lỗi > 50 % trong cửa sổ 20 request]──▶ OPEN (từ chối ngay, không gọi)
   ▲                                                  │
   │                                            [sau 30 s]
   │                                                  ▼
   └────────[3 request thử thành công]──────── HALF-OPEN (cho qua vài request thăm dò)
```

Ba quyết định quan trọng hơn bản thân cơ chế:

**Breaker theo `provider`, hành động theo `lane`.** Provider chết là chết cho cả ba lane, nhưng mỗi lane phản ứng khác:

| Lane | Khi breaker mở |
|---|---|
| Critical | **Phase 1 (D-09): không có provider dự phòng.** `RETRY` với `next_attempt_at = now + 30 s`, `attempt` không tăng; alert `circuit_breaker_open{provider=ses}` ngay lập tức. In-app vẫn gửi. OTP quá `expires_at` tự `EXPIRED`; user xin mã mới khi SES hồi phục. Adapter interface phải cho phép cắm provider thứ hai ở phase sau mà không sửa worker |
| Transactional | `RETRY`, `next_attempt_at = now + 5 phút`, chờ. Kể cả khi có provider dự phòng sau này cũng không failover lane này, vì domain/reputation của email giao dịch quan trọng và trễ 30 phút chấp nhận được |
| Bulk | Như transactional nhưng `+ 30 phút`; **watchdog tự `pause` bulk × email** khi breaker không đóng quá `deliver.breaker_auto_pause_after` (1 h, tính từ lúc breaker rời `CLOSED`, kể cả khi dao động open ↔ half-open). In-app không bị pause. Chỉ pause, không bao giờ tự resume: ops resume thủ công sau khi breaker đóng (R-02); nếu ops đã resume giữa đợt sự cố thì watchdog không pause lại trong cùng đợt (D-23) |

**Breaker mở không phải lỗi của notification đó.** Row bị breaker từ chối quay về `RETRY` với `attempt` **không tăng** — cụ thể: claim đã tăng `attempt`, nên worker gọi `release` (`attempt - 1`, `last_error_code = breaker_open`, `next_attempt_at` theo bảng trên). Cùng cơ chế cho `rate_limited` (hết 5 s chờ token), `lane_paused`, và với delivery Order: `ordering_busy` (delivery khác cùng order đang giữ ordering lease) / `ordering_lease_lost` ([05-ordering.md](./05-ordering.md) §4). `release` giữ nguyên dấu `lease_expired` nếu row đang mang nó. Nếu không, sự cố provider 1 giờ sẽ đốt hết số lần retry của mọi row và đẩy tất cả vào DLQ dù chưa từng được thử thật. Ngược lại, lỗi retryable **thật** (5xx, timeout) tăng `attempt`; vượt `max_attempts` của lane (config, mặc định 5 critical / 8 còn lại) → `FAILED (provider_error)`.

**Half-open thăm dò bằng request thật, nhưng của lane ít quan trọng.** Không dùng OTP làm request thăm dò; dùng row bulk hoặc transactional: khi breaker half-open, worker email của lane critical nhường (không claim) nếu trong cùng process còn lane thấp hơn. Một process chỉ chạy lane critical (`app.lane=critical`) không có lane thấp hơn để nhường nên vẫn có thể dùng row critical làm probe.

**Không có row nào để thăm dò thì dùng health-check của provider.** Watchdog (`workers/providerwatch`, mỗi `deliver.health_probe_interval` = 10 s) gọi `HealthCheck` của adapter — SES `GetAccount` (cũng báo lỗi nếu `SendingEnabled=false`), SMTP `NOOP` — khi breaker đang half-open và không ai (row thật hay watchdog) nhận slot thăm dò trong `deliver.health_probe_idle` (15 s). Kết quả đi qua đúng `Success()`/`Failure()` của breaker nên `half_open_successes` lần thành công đóng mạch, một lần lỗi mở lại. SES cần quyền `ses:GetAccount` ([16-operations.md](./16-operations.md) §6). Đặt `health_probe_interval: 0` để tắt cả watchdog (gồm auto-pause).

## 6. Retry budget và timeout

### 6.1 Retry budget: trần cho tổng lượng retry

Backoff + jitter bảo vệ khỏi retry đồng loạt. Còn một dạng nữa: provider **chập chờn** (30 % lỗi kéo dài hàng giờ), breaker không mở vì chưa vượt ngưỡng, mỗi row fail sinh 5 retry → traffic thực tế gấp 2–3 lần bình thường, đúng lúc provider yếu nhất.

*Retry budget*: **retry không vượt 20 % tổng số lần gửi trong cửa sổ 1 phút**. Vượt → row mới fail được đánh `RETRY` với `next_attempt_at` xa hơn (10–30 phút) thay vì lịch backoff thường. Ý tưởng: khi hệ thống yếu, ưu tiên gửi row **mới** (chưa từng thử, nhiều khả năng thành công) hơn thử lại row cũ.

### 6.2 Timeout: ngân sách thời gian theo từng hop

```
lease 60 s
  ├─ resolve email (Auth)      timeout 2 s
  ├─ render                    < 50 ms
  ├─ acquire rate-limit token  timeout 5 s   (không có token → release: RETRY ngay, không tính attempt)
  └─ provider.send()           connect 2 s, read 10 s
                               ─────────────
                               tối đa ~17 s / row → batch 20 × 17 s = 340 s > lease 60 s !
```

Với timeout xấu nhất, batch 20 **không an toàn** với lease 60 s nếu xử lý tuần tự. Ba cách, có thể kết hợp:

1. Xử lý 20 row **song song** trong worker (goroutine / virtual thread mỗi row).
2. Batch nhỏ hơn cho lane có timeout dài.
3. **Gia hạn lease theo chu kỳ**: heartbeat `UPDATE lease_until = now + 60 s WHERE id IN (...) AND locked_by = me` mỗi 20 s. Chắc nhất; hầu hết job queue trưởng thành đều làm.

Timeout phải **ngắn hơn lease đáng kể** ở mọi hop; hop nào không có timeout là hop có thể giữ lease mãi mãi.

## 7. Alert và công tắc vận hành

Những gì phải có trước khi nhận traffic thật:

| Loại | Tín hiệu | Ngưỡng gợi ý |
|---|---|---|
| Alert | `delivery_pending_age_p99{lane=critical}` | > 30 s |
| Alert | `delivery_pending_age_p99{lane=transactional}` | > 5 phút |
| Alert | `delivery_pending_count{lane=bulk}` | > 1 triệu hoặc tăng liên tục 1 giờ |
| Alert | `provider_error_rate{provider}` | > 10 % trong 5 phút |
| Alert | `dlq_rate{lane}` | > 0 với critical; tăng đột biến với lane khác |
| Alert | `circuit_breaker_state{provider} = OPEN` | ngay lập tức |
| Công tắc | `pause` / `resume` theo `lane` hoặc `lane × channel` | Không cần deploy |
| Công tắc | `failover provider` theo lane | Phase sau, khi có provider thứ hai |
| Công tắc | `replay_mode` với ngưỡng tuổi event | Xem [04-idempotency.md](./04-idempotency.md) §5 |

Alert trên **lag của lane critical** và **DLQ tăng**, không alert trên tổng số lỗi — tổng số lỗi lúc provider sự cố luôn cao và không cho biết điều gì cần làm.

## 8. Tóm lại

DB là bể chứa: phải biết bể to bao nhiêu và đổ gì đi khi đầy. Mọi tài nguyên hữu hạn — thread, connection, quota, cả upstream API — đều có vách ngăn theo lane. Rate limiter khớp quota provider và có phần dự trữ cho critical. Breaker theo provider nhưng hành động theo lane, và không đốt `attempt`. Retry budget và timeout đảm bảo hệ thống đang yếu không tự làm mình yếu thêm. Sợi chỉ xuyên suốt vẫn là: **thứ quan trọng luôn có đường đi, dù mọi thứ khác đang tắc.**
