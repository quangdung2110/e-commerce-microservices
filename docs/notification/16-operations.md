# Operations và runbook

**Trạng thái:** `proposed` — runbook viết trước khi có runtime để implement bám theo; mỗi bước tham chiếu ops API ([10-interfaces.md](./10-interfaces.md) §6) hoặc `notifctl` ([13-development.md](./13-development.md) §5). Alert kích hoạt từng runbook ở [14-observability.md](./14-observability.md) §3.

## 1. SLO đề xuất

| Lane | Chỉ số | Mục tiêu | Đo bằng |
|---|---|---|---|
| Critical | `end_to_end` p99 (event → provider accepted) | < 5 s | `delivery_end_to_end_seconds{lane=critical}` |
| Critical | Tỷ lệ mất (request `DECIDED` không có delivery trạng thái cuối trong 10 phút) | 0 | Job đối soát |
| Transactional | `end_to_end` p99 | < 60 s | |
| Transactional | Tỷ lệ `possibly_duplicate` | < 0,1 % | |
| Bulk | Xả xong trong `max_age` | ≥ 99 % không `EXPIRED` vì lag | `finished_total{final_status=EXPIRED, reason_code=lag}` |
| Tất cả | Bounce rate 24 h / complaint rate 24 h | < 2 % / < 0,05 % (dưới ngưỡng SES 5 % / 0,1 % một khoảng an toàn) | |

## 2. Công tắc vận hành

| Công tắc | Cách | Tác dụng | Khi nào |
|---|---|---|---|
| Pause lane / lane×channel | `PUT /internal/v1/notifications/ops/lanes/{lane}` hoặc `notifctl lane bulk pause` | Consumer vẫn nhận và ghi DB; deliver worker dừng claim | Provider sự cố, quota sắp hết, reputation đang xấu |
| Replay mode | `PUT .../ops/replay-mode` | Consumer `IGNORED` event cũ hơn X | Trước khi reset offset Kafka về quá khứ |
| Redrive một delivery | `POST .../ops/deliveries/{id}/redrive` | `FAILED`/`EXPIRED` → `PENDING`; xoá dấu `lease_expired` (redrive critical `FAILED (lease_expired)` nghĩa là chủ động gửi lại OTP); delivery Order có sequence đã bị vượt sẽ thành `SKIPPED (superseded)` khi được claim | Sau khi sửa nguyên nhân |
| Redrive DLQ theo lô | `POST .../ops/dlq/redrive` | Theo `lane` + `reason_code` + khoảng thời gian | Sau sự cố provider |
| Suppression thủ công | `POST/DELETE .../ops/suppressions` | Thêm `MANUAL` / gỡ `MANUAL`/`SOFT_BOUNCE` | Yêu cầu pháp lý; địa chỉ nội bộ test |
| Failover provider | Không có ở phase 1 (D-09) | | |

Mọi công tắc yêu cầu `Idempotency-Key`, ghi audit, và **không** đi qua Gateway.

## 3. Runbook

Mỗi runbook: triệu chứng → xác nhận → hành động → kết thúc. Thời gian ghi là ước lượng để biết khi nào leo thang.

### R-01 · Lane trễ (`CriticalLaneDelayed`, `TransactionalLaneDelayed`, `BulkBacklogGrowing`)

1. **Xác nhận** trên dashboard Lane health: `pending_age` tăng ở lane nào; `pending_count` vs `scheduled_count` (scheduled cao là quiet hours/backoff, không phải trễ).
2. **Tìm nguyên nhân** theo thứ tự:
   - `dependency_error_rate{ses|auth_contact}` cao hoặc breaker mở → R-02 / R-03.
   - `rate_limiter_wait_seconds` cao, `tokens_available` ≈ 0 → quota lane không đủ cho volume; nếu là bulk thì bình thường; nếu là critical → tăng quota critical tạm thời (config), điều tra vì sao critical đột biến.
   - `bulkhead_in_use == capacity` ở `db_conns` → pool DB bị chiếm; kiểm tra query chậm (`pg_stat_activity`).
   - `delivery_claim_duration` tăng → R-11 (bloat).
   - Không có gì bất thường ở dependency, `pending_age` vẫn tăng → deliver worker của lane chết hoặc lane đang `paused` (kiểm tra `GET .../ops/lanes/{lane}`).
3. **Hành động** với critical đang trễ và nguyên nhân là tài nguyên chung: `notifctl lane bulk pause` để nhường quota/pool; critical phải hồi trong 1–2 phút. Không hồi → leo thang.
4. **Kết thúc**: `pending_age` về dưới ngưỡng; resume bulk; ghi lại nguyên nhân.

### R-02 · Breaker SES mở (`ProviderBreakerOpen`)

1. Xác nhận: `circuit_breaker_state{dependency=ses} == 2`; `dependency_request_duration{ses}` timeout hay 5xx; AWS Health Dashboard / SES console.
2. Phase 1 không có provider dự phòng: **in-app vẫn gửi**, critical `RETRY` 30 s không tăng `attempt`, OTP quá `expires_at` tự `EXPIRED` — user xin mã mới khi SES hồi.
3. Nếu sự cố > 15 phút: `notifctl lane bulk pause` (không cần thiết về mặt kỹ thuật vì breaker đã chặn, nhưng tránh bão thăm dò half-open từ bulk).
4. Nếu lỗi là `Throttling` hoặc `Daily quota exceeded` chứ không phải outage → R-05.
5. Kết thúc: breaker `CLOSED`, resume bulk, theo dõi `retry_budget_ratio` khi xả; kiểm tra `finished_total{EXPIRED}` để biết bao nhiêu OTP đã mất — đó là số user phải xin lại mã.

### R-03 · Breaker Auth `notification-contact` mở (`AuthContactBreakerOpen`)

1. Xác nhận: `dependency_error_rate{auth_contact}`; Auth có đang deploy/sự cố không.
2. Ảnh hưởng: **mọi email không phải Auth** dừng (Order, Review, Cart); in-app vẫn đi; email của Auth dùng API `content` khác nên có thể vẫn chạy.
3. `contact_cache_hit_ratio`: cache 15 phút đỡ được một phần; không kéo dài TTL cache để "chống chế" — đó là PII.
4. Nếu Notification là nguyên nhân (Auth 429 do Notification xả backlog): pause bulk; kiểm tra bulkhead theo lane tới Auth đang cấu hình đúng chưa.
5. Kết thúc: breaker đóng; row `RETRY` tự xả.

### R-04 · Lỗi contract của producer / DLQ critical (`ProducerContractError`, `DlqNonEmptyCritical`)

1. Xác nhận: `ingest_events_total{decision=FAILED, reason_code=...}` theo `source`; xem vài row DLQ (`decision_reason`, `template`, `source`).
2. Đây là **bug ở producer** (template sai, schema sai, sai topic) hoặc catalog vừa đổi mà producer chưa cập nhật. Không redrive: cùng input sẽ fail lại.
3. Liên hệ owner producer với: `source`, `template`, `reason_code`, ví dụ `event_id`. Với Order: kiểm tra `publishNotificationRequests` và `templates/ORDER_*.schema.json`.
4. Sau khi producer sửa: request cũ trong DLQ **không** redrive (payload sai); producer phát lại nếu nghiệp vụ cần (dedup lớp B chặn trùng nếu cùng `dedup_key`).
5. Với critical: DLQ critical nghĩa là OTP/reset không tới — xem `source` (Auth) ngay, leo thang nếu > 5 phút.

### R-05 · SES quota (`SesDailyQuotaNearLimit`, Throttling)

1. Xác nhận: `provider_daily_quota_used_ratio`; SES console → Sending statistics.
2. Ở 0,8 hệ thống tự dừng bulk ([06-backpressure.md](./06-backpressure.md) §4.1); ở 0,9 alert. Còn 10 % cho critical + transactional tới hết chu kỳ 24 h.
3. Nếu critical + transactional cũng sắp chạm: giảm quota transactional trong config để critical luôn còn; transactional sẽ trễ, chấp nhận.
4. Dài hạn: xin tăng quota ở SES (Service Quotas → SES → Sending quota), kèm lịch sử bounce/complaint tốt.
5. Nếu account **đang sandbox** và có user thật: đây là lỗi triển khai — phải xin production access trước; xem R-12.

### R-06 · Bounce / complaint rate cao (`ComplaintRateHigh`, `BounceRateHigh`)

1. Xác nhận theo `lane` và `template` trên dashboard Quality; SES console → Reputation.
2. **Complaint > 0,1 %**: pause bulk **ngay** (đây là lane sinh complaint). Kiểm tra: template bulk có `List-Unsubscribe` không; `marketing_enabled` mặc định có đúng `false` không (IT-PREF-04); frequency cap có hoạt động không.
3. **Bounce > 5 %**: thường là gửi tới địa chỉ chưa xác minh hoặc dữ liệu cũ. Kiểm tra `email_verified` từ Auth có được tôn trọng cho bulk không; suppression có được ghi và đọc đúng không (IT-SUPP-01).
4. Sau khi sửa: SES cần vài ngày dữ liệu tốt để rate hạ. Bulk chỉ resume khi complaint 24 h < 0,05 %.
5. Nếu SES đặt account vào review: trả lời theo hướng dẫn của họ với bằng chứng suppression, unsubscribe, cap.

### R-07 · `possibly_duplicate` tăng (`PossiblyDuplicateSpike`)

1. Xác nhận: `delivery_lease_expired_total` tăng cùng lúc → worker không kịp trong lease.
2. Nguyên nhân thường gặp: provider chậm + batch lớn + không heartbeat; worker bị OOM kill lặp lại (`kubectl`/`docker` restart count).
3. Hành động: giảm batch size lane đó; bật/kiểm tra heartbeat gia hạn lease; tăng memory nếu OOM.
4. Không có gì phải "sửa dữ liệu": user nhận 2 email transactional là hậu quả đã chấp nhận (D-04); critical không gửi lại.

### R-08 · Partition sắp hết (`PartitionRunway`)

1. Xác nhận: `partition_future_months{table}` < 1; job partition có chạy không (log `workers/partition`).
2. Tạo tay ngay: `notifctl partitions ensure --months 3` (hoặc SQL `CREATE TABLE ... PARTITION OF ... FOR VALUES FROM ... TO ...` cho từng bảng).
3. Tìm vì sao job không chạy (crash, quyền DDL, đồng hồ). Đây là page vì thiếu partition = mọi INSERT lỗi = consumer dừng commit offset = lag tăng nhưng không mất dữ liệu (at-least-once).

### R-09 · Provider event không khớp (`UnmatchedProviderEvents`)

1. Xác nhận: `provider_event WHERE status = 'UNMATCHED' ORDER BY received_at` — `provider_message_id` có tồn tại trong `notification_delivery` không.
2. Có tồn tại nhưng chưa khớp lúc đó → race bình thường, worker sẽ retry; UNMATCHED > 5 phút nghĩa là retry không chạy → kiểm tra track worker.
3. Không tồn tại → email gửi ngoài Notification bằng cùng SES account (vi phạm "Notification là nơi duy nhất gọi SES"), hoặc delivery đã bị anonymize/drop partition (event đến muộn > 90 ngày — rất hiếm). Đánh `IGNORED` với ghi chú; điều tra ai gửi.

### R-10 · Redis suy giảm (`FrequencyCapDegraded`)

1. Redis lỗi → frequency cap fail-open (bulk có thể vượt 2/ngày), rate limiter fail-closed về `quota / max_replica` (gửi chậm hơn), unread count recompute từ DB (chậm hơn), cache contact miss (Auth nhận nhiều request hơn — bulkhead bảo vệ).
2. Hành động: nếu Redis không hồi trong 15 phút, pause bulk để không vượt cap và không dồn Auth.
3. Kết thúc: Redis hồi; không cần sửa dữ liệu (key cap có TTL 48 h, sẽ tự đúng lại từ ngày sau).

### R-11 · Bloat `notification_delivery` (`DeadTupleBloat`, claim chậm)

1. Xác nhận: `pg_stat_user_tables` cho partition tháng hiện tại; `EXPLAIN (ANALYZE)` câu claim dùng `idx_delivery_ready` không.
2. `VACUUM (VERBOSE) notification_delivery_<tháng>`; kiểm tra `autovacuum_vacuum_scale_factor = 0.01` đã áp cho bảng cha (kế thừa xuống partition).
3. Nếu autovacuum không theo kịp liên tục: tăng `autovacuum_max_workers` / giảm `autovacuum_vacuum_cost_delay` cho DB này; cân nhắc partition theo tuần nếu số đo §3 của [15-verification.md](./15-verification.md) cho thấy volume cao hơn giả định.

### R-12 · Xin production access SES (một lần, trước khi có user thật)

1. Verify domain gửi (DKIM 2048, SPF, DMARC `p=quarantine` tối thiểu), địa chỉ `MAIL FROM` tuỳ chỉnh.
2. Tạo hai configuration set (`notification-critical-transactional`, `notification-bulk`), event destination → SNS topic → SQS, chỉ bật `Send`, `Delivery`, `Bounce`, `Complaint`, `Reject`, `DeliveryDelay`.
3. Gửi yêu cầu production access với mô tả: loại email (transactional + opt-in marketing), cách xử lý bounce/complaint (suppression tự động, List-Unsubscribe one-click, cap 2/ngày), volume dự kiến.
4. Sau khi được duyệt: đặt `MaxSendRate`/`Max24HourSend` thực vào config chia quota lane ([06-backpressure.md](./06-backpressure.md) §4.1).

### R-13 · Reconciliation với Auth (request bị mất)

Auth publish best-effort không outbox: retry 3 lần rồi bỏ ([README §4](./README.md)). Request OTP có thể mất trước khi tới Kafka mà Notification không biết.

1. Không có cách phát hiện từ phía Notification trong luồng thường; user sẽ bấm "gửi lại".
2. Đối soát định kỳ (hằng ngày, job phía Auth hoặc báo cáo chung): Auth đếm `delivery_id` đã tạo trong ngày; Notification đếm request `source = /services/auth` đã nhận; chênh lệch > 0,1 % → ticket cho Auth (Kafka producer của họ lỗi) — Notification cung cấp danh sách `delivery_id` đã nhận qua status API để Auth đối chiếu.
3. Giải pháp dài hạn nằm ở Auth (outbox), không ở Notification.

### R-14 · Replay có chủ đích

1. Bật replay mode với `ignore_events_older_than` phù hợp (ví dụ `P1D`): event cũ hơn bị `IGNORED (replay_too_old)`, không gửi lại thông báo lỗi thời.
2. Reset offset consumer group của lane cần replay (`kafka-consumer-groups --reset-offsets --to-datetime ...`) khi consumer group **đang dừng**.
3. Khởi động lại consumer; dedup lớp A chặn mọi event đã xử lý trong 30 ngày; chỉ event chưa từng thấy được xử lý.
4. Tắt replay mode (hoặc để tự hết `ttl`). Kiểm tra `ingest_events_total{decision=IGNORED, reason_code=replay_too_old}` và `DUPLICATE` để xác nhận replay đúng phạm vi.

### R-15 · Ordering lease Order bị kẹt (`OrderNotificationOrderingLeaseStuck`)

1. Xác nhận `notification_ordering_lease` theo `ordering_key`, `delivery_id`, `locked_by`,
   `lease_until`; đối chiếu delivery owner và log heartbeat. Không xoá
   `notification_subject_sequence`.
2. Nếu owner còn sống/provider call còn trong timeout, chờ heartbeat hoặc timeout; không
   cưỡng bức release vì có thể tạo hai provider call đồng thời cùng order.
3. Nếu owner đã chết và lease quá hạn, delivery kế tiếp của order tiếp quản lease ngay khi acquire (không chờ reaper); reaper (mỗi `deliver.reaper_interval`) compare-and-delete bằng cả
   `(ordering_key, delivery_id)`; worker mới acquire. Không update/delete chỉ theo key.
4. Nếu `ordering_lease_lost{stage=during_send}` tăng, coi kết quả gửi là không chắc chắn:
   kiểm tra `possibly_duplicate`, provider message id/log đã khử PII; không sửa Order FSM
   và không phát lại command với sequence tuỳ ý.
5. Kết thúc khi lease age về 0, pending age của transactional lane giảm và test một order
   khác xác nhận không có global head-of-line blocking.

## 4. Việc định kỳ

| Việc | Tần suất | Cơ chế | Kiểm tra |
|---|---|---|---|
| Tạo partition tháng tới (+2) | Hằng ngày | `workers/partition` | `partition_future_months ≥ 2` |
| Drop partition quá retention | Hằng ngày | `workers/partition`, từ chối nếu còn row chưa kết thúc | Log + metric |
| Purge sổ dedup (30/30/14 ngày), outbox đã publish | Hằng ngày, batch 10k | `workers/purge` | `purge_rows_deleted_total` |
| Reaper delivery + ordering lease | 30 s | `workers/reaper`, compare owner trước khi thu hồi | `delivery_lease_expired_total`, `ordering_lease_oldest_age_seconds` |
| Đối soát Auth (R-13) | Hằng ngày | Báo cáo | Chênh lệch < 0,1 % |
| Kiểm tra reputation SES | Hằng tuần | Dashboard Quality + SES console | Bounce < 2 %, complaint < 0,05 % |
| Rà soát DLQ | Hằng tuần | Status API / SQL | Không có `FAILED` nào chưa được phân loại |
| Backup Postgres | Theo chính sách dự án | | Restore test hằng quý; `provider_event.raw` đã hash nên backup không chứa email |

## 5. Triển khai và rollback

- Một binary, nhiều `ROLE`. Thứ tự rolling: `api` → `track` → `deliver` theo lane bulk → transactional → critical → `consumer` theo cùng thứ tự. Critical cuối cùng để nếu bản mới lỗi thì phát hiện trên lane ít quan trọng trước.
- Migration chạy trước binary mới và phải **tương thích ngược một phiên bản** (thêm cột nullable, không đổi tên) để rollback binary không cần rollback schema.
- Đổi template là đổi binary (embed); rollback binary = rollback template. `template_version` trong `notification_delivery` cho biết email nào render bởi bản nào.
- Đổi catalog (`contracts/`) là thay đổi contract: theo quy trình contract, không phải deploy thường.
- Rollback không xử lý được: event outcome đã publish, email đã gửi. Vì thế deploy critical cuối cùng.

## 6. Bảo mật vận hành

- Ops API chỉ trong mesh, principal `notification-ops`; mọi call có audit. Không có "god mode" qua Gateway.
- Credential: Postgres, Redis, AWS (SES + SQS) qua secret manager; dev dùng compose env. Không bao giờ trong `application.yaml`.
- IAM cho production: policy tối thiểu `ses:SendEmail` (giới hạn theo configuration set và identity), `sqs:ReceiveMessage/DeleteMessage/GetQueueAttributes` trên đúng queue. Không `ses:*`.
- Truy cập DB production của người: read-only role không thấy `template_data` (view che cột) trừ khi có ticket; không có cột email để lộ.
