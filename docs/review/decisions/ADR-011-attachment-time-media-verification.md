---
doc_id: review-adr-011
service: review
status: accepted
date: 2026-09-09
decision_owners:
  - Review Service team
supersedes: null
evidence:
  - docs/review/schema/0001_initial_review_schema.sql
  - contracts/http/review/v1/openapi.yaml
---

# ADR-011 — Xác minh media lúc attach, không có verify/poll API cho client

## Status

`Accepted`

## Context

Media có lifecycle `ISSUED → VERIFIED → ATTACHED`, nhưng thiết kế trước không nói rõ
client báo upload hoàn tất bằng cách nào. Một endpoint verify riêng rồi endpoint poll
đẩy state machine storage vào frontend, dù client chỉ cần upload rồi gửi review.

Ngược lại, cho phép attach/public object chưa được kiểm tra và chờ report là không
chấp nhận được: report là moderation sau khi nội dung đã public, không xác minh object
tồn tại, MIME, dung lượng hoặc byte không bị thay thế sau kiểm tra.

## Decision

Client xin provider-neutral signed upload instruction, upload trực tiếp vào object
storage, rồi gửi `asset_id` trong create/edit review. Không có endpoint verify hoặc
poll asset cho client.

Review đọc metadata của object immutable trước transaction database của create/edit,
xác minh existence, MIME/type, byte size và checksum khi client đã khai báo. Trong
transaction, Review khóa asset, chuyển `ISSUED → VERIFIED → ATTACHED`, gắn media và
ghi domain/outbox change. Signed upload phải single-write/no-overwrite; object key là
opaque và không trả cho client. Asset chưa attach hết hạn theo TTL; quota giới hạn số
asset chưa attach và byte upload theo user.

## Alternatives considered

**Verify/poll API riêng.** Tường minh state cho client, nhưng tăng số round trip và
buộc frontend xử lý lifecycle nội bộ. Bị loại vì không tạo thêm giá trị nghiệp vụ.

**Storage event/worker xác minh bất đồng bộ.** Có thể phù hợp khi storage provider và
scanner đã được chốt. Hiện chưa có provider/event integration; nó đòi response state
và retry model mới cho client.

**Attach trước, kiểm tra sau khi public.** Đơn giản bề ngoài nhưng cho phép object sai
hoặc nguy hiểm xuất hiện công khai. Bị loại.

## Consequences

Client có đúng ba bước: xin URL, upload, create/edit kèm `asset_id`. Lifecycle vẫn
được giữ bên trong Review để ngăn asset foreign/reused/invalid. Create/edit phụ thuộc
vào metadata read từ storage; object missing hoặc chưa visible trả lỗi retryable/domain
phù hợp, nhưng không có storage write trong database transaction.

`HIDDEN_BY_REPORT` giữ attachment để admin có thể restore review; legal takedown hoặc
media replacement mới chuyển asset sang `DELETE_PENDING` để worker xóa byte sau commit.
Không có manual admin-hide state trong decision này.

## Verification

Test signed upload không cho overwrite, quota ngăn unlimited unattached asset, create/
edit tự verify asset không cần verify/poll request, foreign/missing/type-size-checksum
mismatch/reuse bị từ chối, URL đọc chỉ được sinh cho attachment verified của review
`APPROVED`, và takedown/replacement đưa asset vào `DELETE_PENDING`.
