---
doc_id: user-adr-007
service: user
status: accepted
date: 2026-08-28
decision_owners:
  - User Service team
supersedes: null
evidence:
  - docs/user/workflows.md
  - docs/user/operations.md
  - contracts/events/user/v1/asyncapi.yaml
---

# ADR-007 — Reconcile projection định kỳ khi không dùng outbox

## Bối cảnh

User publish profile/preference event trực tiếp sau PostgreSQL commit. Retry cạn
có thể làm Chat hoặc Notification bỏ lỡ update; không có outbox, inbox, bảng
dedup event ID hay checkpoint reconciliation trong schema User.

## Quyết định

Giữ direct publish như ADR-004 và chạy `user-projection-reconciliation` mỗi ngày
ngoài giờ cao điểm. Job full-scan current state của profile/preferences active
theo batch/cursor chỉ trong memory của lần chạy, loại user có `user_deletions`,
rồi phát lại hai event chuẩn với CloudEvent `id` mới và `data.updated_at` giữ
nguyên từ PostgreSQL.

Không phát lại `USER_DELETED`, không tạo outbox/checkpoint table, không suy đoán
payload từ log PII và không tự động redrive DLQ. Consumer dùng `updated_at` để
bỏ qua current snapshot cũ hơn; deletion vẫn là terminal theo ADR-006.

## Các phương án đã cân nhắc

- Transactional outbox: độ tin cậy tốt hơn nhưng thêm table, relay/CDC, cleanup
  và vận hành; chưa phù hợp mức phức tạp đã chốt.
- Checkpoint incremental trong database: giảm full scan nhưng thêm schema/state
  lâu dài và phải xử lý checkpoint loss/backfill.
- Không reconciliation: để projection stale vô thời hạn khi retry cạn.

## Hệ quả

Projection có thể stale tối đa đến sweep kế tiếp; worker cần batch/rate limit,
single active run, metric scanned/published/failed/duration và alert khi job không
hoàn tất. Khối lượng dữ liệu tăng vượt ngưỡng full sweep là trigger để đánh giá
lại outbox hoặc checkpoint có migration riêng.

## Xác minh

Failure-injection test phải chứng minh publish failure sau commit được current
snapshot sweep bù lại, event corrective không thay đổi `updated_at`, user đã bị
deletion không được republish, và hai worker reconciliation không chạy chồng.
