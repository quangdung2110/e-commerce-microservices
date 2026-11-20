---
doc_id: user-adr-009
service: user
status: accepted
date: 2026-10-09
decision_owners:
  - User Service team
supersedes: null
evidence:
  - docs/notification/08-decide.md
  - contracts/events/notification/v1/asyncapi.yaml
  - go/services/user-service/migrations/0002_marketing_opt_in_default.up.sql
  - go/services/user-service/internal/domain/service/preferences_service.go
---

# ADR-009 — Marketing là opt-in và tắt ở nguồn khi có complaint

## Bối cảnh

User sở hữu ba cờ `email_enabled`, `in_app_enabled`, `marketing_enabled` và
Notification giữ bản replicate. Hai bên bất đồng về mặc định:

- User tạo preferences với cả ba cờ `true` và không phát event lúc bootstrap.
- Notification, khi chưa có row, coi marketing là **tắt** vì gửi marketing cho người
  chưa từng đồng ý là rủi ro compliance ([Notification 08](../../notification/08-decide.md) §5).

Reconciliation hằng ngày, và bất kỳ lần owner đổi một cờ khác, phát snapshot hiện hành
`marketing_enabled=true` sang Notification. Kết quả là marketing bật cho user chưa từng
đồng ý. Ngoài ra Notification tự tắt marketing cục bộ khi nhận complaint (user báo spam)
và phát `complaint-received` để User tắt ở nguồn, nhưng User không có consumer: nguồn
chân lý vẫn `true` và lần đổi preference kế tiếp bật lại marketing cho người đã báo spam.

## Quyết định

1. Marketing là **opt-in**. Account mới có email và in-app bật, marketing tắt
   (`NewDefaultPreferences`, và `DEFAULT FALSE` trong schema). Mặc định này trùng mặc
   định của Notification khi thiếu row, nên không cần event lúc bootstrap.
2. Migration `0002` backfill: các row mà `updated_at` vẫn bằng `created_at` của profile
   (bootstrap ghi cả hai cùng một thời điểm, trigger chỉ đổi `updated_at` khi có thay
   đổi thật) chưa từng được owner chạm tới, nên `true` chỉ là mặc định cũ, không phải
   đồng ý — chuyển về `false`. Row đã bị owner chạm vào bất kỳ cờ nào giữ nguyên, vì dữ
   liệu không phân biệt được với người chủ động bật.
3. User consume `com.ecommerce.notification.complaint-received.v1` trên
   `t.notification.outcomes.v1` (consumer group `user-service`) và tắt
   `marketing_enabled` bằng một câu `UPDATE ... WHERE marketing_enabled` nguyên tử, rồi
   phát `USER_PREFERENCES_UPDATED` như mọi thay đổi preference. Idempotent: cờ đã tắt
   hoặc user không còn preferences thì không làm gì và không phát event. Các outcome khác
   (`delivered`, `bounced`, `failed`) được ack mà không giải mã.
4. Message lỗi vào DLQ riêng `t.notification.outcomes.v1.dlq`, tách khỏi
   `t.auth.events.dlq` để redrive không trộn hai producer.

## Các phương án đã cân nhắc

- Chuyển Notification sang opt-out (thiếu row là marketing bật): đảo quyết định
  compliance đã ghi, rủi ro pháp lý cao hơn.
- Backfill mọi row về `false`: xoá cả sự đồng ý thật của người đã bật.
- Không backfill: giữ nguyên rủi ro cho dữ liệu đã tạo.
- Read-modify-write trong transaction cho complaint: có thể ghi đè một thay đổi email hoặc
  in-app đồng thời của owner bằng giá trị cũ.

## Hệ quả

Hạn chế đã biết của backfill: user chỉ đổi cờ email hoặc in-app (không đụng marketing)
cũng có `updated_at` muộn hơn nên giữ `marketing_enabled=true` dù chưa từng chủ động
đồng ý. Muốn xử lý nốt cần consent log hoặc xin xác nhận lại; ngoài phạm vi ADR này.
Migration không đảo ngược phần backfill. Consumer thêm một topic vào group hiện có nên
lần start đầu đọc lại toàn bộ retention 7 ngày của outcomes; mọi xử lý đều idempotent.

## Xác minh

Unit test: mặc định opt-out, complaint tắt cờ và giữ nguyên các cờ khác, redelivery
không phát event, user lạ được ack. Integration test: `DisableMarketing` nguyên tử và
idempotent, backfill chỉ đổi row chưa bị chạm. Test consumer: routing theo topic, retry,
DLQ theo topic nguồn.
