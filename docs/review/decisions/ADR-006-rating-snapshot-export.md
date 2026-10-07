---
doc_id: review-adr-006
service: review
status: accepted
date: 2026-09-05
decision_owners:
  - Review Service team
  - Search Service team
supersedes: null
evidence:
  - contracts/http/review/internal/v1/openapi.yaml
---

# ADR-006 — Durable export session cho Search rating reindex

## Decision

Search tạo export bằng `POST /internal/v1/reviews/rating-aggregate-exports`. Review
chụp end offset của mọi `t.review.events` partition, lưu một
`rating_export_sessions` row và `rating_export_session_offsets` rows, rồi trả session
ID, expiry và offsets.

Search lấy keyset pages qua `GET .../rating-aggregate-exports/{session_id}`. Mọi page
echo đúng offsets đã lưu. Sau import, Search seek inclusive từ offsets và apply metrics
event bằng `review_revision` guard. Session hết hạn trả 410; Search bỏ cả run và tạo
session mới.

## Rationale

Endpoint GET stateless cũ nói “mọi trang cùng session có offsets không đổi” nhưng
không có `session_id` trong API hay nơi lưu offsets. Nó không thể thực hiện được.
Giữ database transaction xuyên HTTP pages là không an toàn; durable watermark kết hợp
revision guard cho phép mỗi page scan ngắn mà vẫn không mất mutation.

## Consequences

- POST là cần thiết vì tạo session thay đổi state.
- Export trở thành dependency chặn Search cutover; 503/410 buộc restart run.
- Session TTL giới hạn storage và tránh dùng offset cũ quá lâu.
- Kafka partition topology không được đổi giữa session; nếu đổi, abort run.

## Verification

Contract test session creation, fixed offsets on every page, 404/410 behavior,
snapshot/replay overlap, event duplicate/old revision no-op và Search không cutover
khi session lỗi.
