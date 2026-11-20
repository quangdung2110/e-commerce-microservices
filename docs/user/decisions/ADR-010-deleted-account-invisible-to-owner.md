---
doc_id: user-adr-010
service: user
status: accepted
date: 2026-10-09
decision_owners:
  - User Service team
supersedes: null
evidence:
  - docs/user/domain.md
  - go/services/user-service/internal/infrastructure/postgres/profile_repo.go
  - go/services/user-service/internal/domain/service/lifecycle_service.go
---

# ADR-010 — Account trong retention window không còn đọc/ghi được bởi owner

## Bối cảnh

[ADR-005](./ADR-005-gdpr-two-stage-deletion.md) giữ row profile (đã anonymize) tới khi
purge, vì deletion record không có foreign key và worker cần một identity ổn định.
Nhưng mọi đường của owner đều chỉ kiểm sự tồn tại của row profile: `GET /profile`
trả `200` với profile ẩn danh, `PATCH /profile` ghi tên và ngày sinh trở lại rồi phát
`USER_PROFILE_UPDATED` sang Chat, `POST /addresses` tạo address (foreign key thoả vì
row còn đó). Việc chặn duy nhất là Gateway thu hồi token, vốn có thể trễ hoặc lỗi.
[domain.md §8](../domain.md) yêu cầu request cập nhật phải bị từ chối.

## Quyết định

Account có retention record (đang chờ purge) được coi như profile không tồn tại với mọi
đường của owner: `404 PROFILE_NOT_FOUND`, không phân biệt với chưa bootstrap hay đã purge.

- `ProfileRepository.GetActive` loại user có retention record bằng `NOT EXISTS`.
- `GetActiveForUpdate` khoá row profile trước rồi kiểm retention record bằng **câu lệnh
  riêng**. Dưới `READ COMMITTED`, câu lệnh sau khi chờ khoá thấy kết quả commit của
  anonymization; gộp kiểm tra vào câu `FOR UPDATE` thì subquery đọc snapshot trước khoá.
- Anonymize lấy advisory lock theo user (cùng khoá với mọi mutation address) trước khi
  chèn deletion record, nên một `POST /addresses` đã qua bước kiểm tra "profile còn
  active" phải commit xong trước khi `DeleteAllForUser` chạy, không để lại address của
  account đã xoá.
- Preferences đã bị xoá khi anonymize nên `GET/PUT /preferences` vốn đã `404`.
- `GetByID`/`GetForUpdate` giữ nguyên nghĩa "đọc row bất kể trạng thái" cho lifecycle và
  purge.

## Các phương án đã cân nhắc

- Thêm cột `anonymized_at` vào profile: phải giữ đồng bộ với `user_deletions`, hai nguồn
  sự thật cho cùng một fact.
- Chỉ dựa vào Gateway thu hồi token: lỗi Redis của revocation (Auth ghi marker sau
  commit) làm lộ khoảng hở.
- Xoá hẳn row profile ngay: mất identity ổn định cho purge và reconciliation.

## Hệ quả

Mỗi đọc profile của owner thêm một `NOT EXISTS` theo `user_deletions.user_id` (đã có
unique index). Profile của account đã xoá chỉ còn thấy được qua lifecycle và purge.

## Xác minh

Unit và integration test: sau Anonymize, `GET/PATCH /profile`, `GET /preferences`,
`GET/POST /addresses` đều `404`, row vẫn ẩn danh, không address mới, không event mới.
Test repository: `GetActive` ẩn account bị xoá còn `GetByID` vẫn đọc được.
