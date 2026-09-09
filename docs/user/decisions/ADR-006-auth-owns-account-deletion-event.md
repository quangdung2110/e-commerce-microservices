---
doc_id: user-adr-006
service: user
status: accepted
date: 2026-08-28
decision_owners:
  - User Service team
supersedes: ADR-005 (chỉ phần republish tín hiệu deletion downstream)
evidence:
  - contracts/events/auth/v1/asyncapi.yaml
  - contracts/events/user/v1/asyncapi.yaml
---

# ADR-006 — Auth sở hữu event xóa account

## Bối cảnh

`USER_DELETED` từ Auth đã chứa `user_id`, `deleted_at`, `reason` và
`hard_delete_after`. Việc User Service nhận event này rồi phát lại
`ACCOUNT_DELETED` cho Notification và Review tạo thêm độ trễ, thêm cửa sổ mất
event do direct publish, và làm hai event diễn đạt cùng một fact nghiệp vụ.

## Quyết định

Auth là nguồn chân lý duy nhất cho fact account đã bị xóa và phát `USER_DELETED`
trực tiếp trên `t.auth.events` cho User, Notification, Review, Chat và API Gateway. Mỗi
consumer tự xử lý dữ liệu mà mình sở hữu, idempotent theo `user_id`/CloudEvent
`id`, và không đợi service khác hoàn tất cleanup.

User vẫn tạo retention record, anonymize profile và xóa address/preferences khi
consume event Auth, nhưng không phát `ACCOUNT_DELETED`. `t.user.events` chỉ mang
`USER_PROFILE_UPDATED` và `USER_PREFERENCES_UPDATED`.

## Các phương án đã cân nhắc

- Republish qua User: giữ thứ tự cleanup User trước downstream nhưng làm trùng
  nghĩa event và thêm điểm lỗi.
- Event `USER_DATA_PURGED` từ User: chỉ bổ sung khi một consumer thật sự cần
  fact User đã purge xong, không thay thế `USER_DELETED`.
- Gọi HTTP tuần tự từ Auth: tạo coupling và không phù hợp cleanup bất đồng bộ.

## Hệ quả

Auth AsyncAPI phải công khai các consumer deletion trực tiếp. Notification,
Review, Chat và API Gateway phải chuyển consumer sang `t.auth.events`. Tín hiệu deletion có
thể đến trước/sau các update projection khác; consumer xem deletion là terminal
và không khôi phục PII từ update đến muộn.

Quyết định này thay thế riêng phần “User phát `ACCOUNT_DELETED`” của ADR-005;
thiết kế anonymization và retention purge hai giai đoạn trong ADR-005 vẫn giữ
nguyên.

## Xác minh

Contract test phải xác nhận Auth `USER_DELETED` được các consumer trực tiếp đọc
được; test duplicate/out-of-order phải chứng minh cleanup idempotent và profile
event đến muộn không tái tạo projection đã xóa.
