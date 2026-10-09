---
doc_id: review-adr-013
service: review
status: accepted
date: 2026-10-09
decision_owners:
  - Review Service team
supersedes: null
evidence:
  - docs/review/schema/0003_privacy_sweep_includes_failed.sql
  - java/services/review-service/src/main/java/com/ecommerce/review/service/PrivacyService.java
  - java/services/review-service/src/main/java/com/ecommerce/review/config/KafkaConfig.java
  - java/services/review-service/src/test/java/com/ecommerce/review/integration/PrivacyIT.java
  - java/services/review-service/src/test/java/com/ecommerce/review/integration/PrivacyConsumerIT.java
---

# ADR-013 — Privacy failure handling: retry a failed purge, never dead-letter a transient `USER_DELETED`

## Status

`Accepted`. Làm rõ [ADR-007](./ADR-007-author-name-snapshot.md): ADR-007 quy định việc
anonymize/purge và deletion guard; ADR này quy định điều phải xảy ra khi một bước của vòng
đời đó thất bại. Không thay thế ADR nào.

## Context

Workflow và runbook luôn nói "một privacy request thất bại alert ở mức critical và được
thử lại, không được âm thầm vào DLQ". Kiểm chứng lại với implementation cho thấy hai chỗ
không làm đúng điều đó:

- `PrivacyService.purgeDue` đánh dấu request `FAILED` khi một lần purge ném lỗi, nhưng
  truy vấn của sweep chỉ chọn `status = 'ANONYMIZED'` (và index
  `ix_privacy_hard_delete_sweep` cũng partial theo đúng giá trị đó). Một request `FAILED`
  vì lỗi tạm thời (lock timeout, mất kết nối) vì vậy không bao giờ được chọn lại; dữ liệu
  trực tiếp của người dùng ở lại quá hạn pháp lý cho tới khi có người sửa tay.
- Listener `USER_DELETED` dùng chung error handler với các listener khác: retry theo
  exponential backoff tối đa 60 giây rồi publish vào `t.auth.events.dlq` và bỏ qua record.
  Một sự cố PostgreSQL kéo dài hơn một phút vì thế làm mất lệnh xóa. Khi đó chưa có
  `privacy_erasure_requests` nào, nên không có guard, không có gì cho gauge quá hạn đếm,
  và không alert nào kêu. Auth đã có thể mất event này trước khi nó tới Review
  ([ADR-007](./ADR-007-author-name-snapshot.md)); mất thêm một lần ở phía Review là lỗi
  không cần có.

## Decision

1. **Purge thất bại được thử lại ở lần chạy kế tiếp.** Request `FAILED` có
   `anonymized_at` là request đến hạn như `ANONYMIZED`; `markPurged` chấp nhận cả hai
   trạng thái và xóa `failure_detail`. Gauge `review_privacy_hard_delete_overdue_total`
   đếm mọi request chưa `PURGED` quá hạn nên alert critical giữ nguyên cho tới khi purge
   thành công, kể cả khi lỗi là loại retry không sửa được.
2. **Một lần chạy duyệt mọi request đến hạn đúng một lần.** Sweep phân trang bằng keyset
   `(hard_delete_after, id)` cho tới khi hết, không chỉ lấy một batch đầu. Request vừa thất
   bại trong lần chạy này không được chọn lại trong chính lần chạy đó, nên một subject
   hỏng không chặn các subject phía sau. Cùng nguyên tắc áp dụng cho dọn dẹp media
   (`(delete_requested_at, id)`).
3. **Listener `USER_DELETED` có error handler riêng.** Lỗi không phải vi phạm contract được
   retry không giới hạn thời gian với backoff tăng dần, tối đa 60 giây mỗi lần; mỗi lần giao
   thất bại tăng counter `review_privacy_consumer_failure_total` để việc kẹt có thể alert.
   Chỉ record vi phạm contract (không phải CloudEvents, `id` không phải UUID, thiếu field
   bắt buộc) mới vào `t.auth.events.dlq`, ngay lần đầu, vì không retry nào sửa được nó.
   Hai listener còn lại cũng đưa record vi phạm contract vào DLQ ngay thay vì sau 60 giây.
4. **Migration `V3`** (`docs/review/schema/0003_privacy_sweep_includes_failed.sql`) dựng lại
   `ix_privacy_hard_delete_sweep` partial theo `status IN ('ANONYMIZED','FAILED')` và sắp theo
   `(hard_delete_after, id)`, đúng keyset của sweep.

## Alternatives considered

- **Giữ `FAILED` là trạng thái chờ người xử lý.** Đơn giản và giữ lỗi hiển thị, nhưng biến
  mọi lỗi tạm thời thành thao tác thủ công trong khi hạn pháp lý đang chạy, và runbook không
  có thao tác nào để đưa `FAILED` về `ANONYMIZED` ngoài SQL tay. Bị loại.
- **Retry `USER_DELETED` có giới hạn rồi DLQ.** Là hành vi cũ. Bị loại vì DLQ không có owner
  nào đang theo dõi cho dữ liệu pháp lý, còn consumer của Review thì idempotent theo receipt
  nên retry vô hạn không gây hại.
- **Quét `FAILED` bằng truy vấn riêng không index.** Không cần migration, nhưng bảng
  `privacy_erasure_requests` tăng một dòng cho mỗi tài khoản đã xóa và không bao giờ bị dọn;
  quét tuần tự trên đường có hạn pháp lý là điều thiết kế này tránh ở mọi chỗ khác.

## Consequences

- Một lỗi retry không sửa được (ví dụ vi phạm CHECK) lặp lại mỗi ngày và log `error` mỗi lần;
  alert quá hạn không tự tắt. Đây là hành vi mong muốn: nó phải được sửa ở code.
- Một record `USER_DELETED` không thể áp dụng vì lý do không phải contract (lỗi dữ liệu xác
  định) chặn partition của consumer group `review-privacy-sync` cho tới khi sửa. Chỉ
  consumer group của Review bị chặn; service khác có group riêng. Counter lỗi và log là
  tín hiệu để can thiệp.
- Purge chạy hằng ngày nên một request thất bại được thử lại sau tối đa một ngày.

## Verification

`PrivacyIT`: purge thất bại → `FAILED`, vẫn tính là quá hạn, guard vẫn chặn input mới; lần
chạy sau → `PURGED` và `failure_detail` bị xóa; một subject hỏng không làm subject phía sau
bị bỏ qua dù batch bằng 1. `PrivacyConsumerIT` (broker thật): record vi phạm contract vào
`t.auth.events.dlq` ngay và record kế tiếp vẫn được áp dụng; lỗi tạm thời được retry, được
đếm, không vào DLQ, và được áp dụng ngay khi lỗi hết. `IndexUsageIT`: sweep dùng
`ix_privacy_hard_delete_sweep`, không `Seq Scan`.
