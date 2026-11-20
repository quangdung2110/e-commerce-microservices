# ADR-026 — Giữ tombstone khi full reindex và so count cùng tập catalog

- Trạng thái: Accepted
- Ngày: 2026-10-16
- Mở rộng ADR-011/022; giữ nguyên contract export Product và cửa sổ bảo vệ 45 ngày.

## Bối cảnh

Export Product chỉ chứa row còn tồn tại; SKU/Product đã xóa được Search giữ dưới dạng tombstone.
Full reindex chỉ import export và replay từ watermark mới sẽ mất các tombstone có trước watermark.
So `_count` toàn index vì vậy có thể chặn một candidate đủ catalog còn sống; bỏ gate đơn thuần lại làm
event upsert cũ có thể tạo lại document sau cutover. Runtime đã quan sát current 121 = 105 sống + 16 tombstone,
candidate 106 = 106 sống, không có tombstone.

## Quyết định

1. Trước Build Category/Catalog và một lần nữa tại VERIFY, `ReindexTombstoneTransfer` scan các tombstone
   trong cả index Product và Category đang phục vụ, copy vào hai physical index đích. Giữ nguyên ID,
   nội dung, revision, `deleted_at`; không đặt lại tuổi tombstone. Copy toàn bộ tombstone nguồn còn giữ,
   kể cả tombstone quá tuổi nhưng chưa qua điều kiện purge/readiness. Không tự purge trong reindex.
2. Ghi copy dùng script riêng `reindex_tombstone_copy`, có thể upsert **chỉ tombstone đã được xác nhận**.
   Lặp cùng/older revision là no-op; gặp document sống có revision cao hơn deletion guard thì fail run
   thay vì ghi đè mâu thuẫn. Luồng event delete bình thường vẫn không upsert document thiếu.
3. Scan dùng PIT và keyset theo ID (giữ đủ search_after sort values), bulk throttle/retry hiện hành, kiểm timeout/shard và item failure.
   Một trang không đầy đủ hoặc copy lỗi làm run FAILED, không cutover. Resume có thể copy lại an toàn.
4. Count drift chỉ so SKU document `is_deleted=false` giữa current/candidate, bao gồm mọi lifecycle/stock
   của catalog chưa xóa, không phải số card Product hay chỉ SKU ACTIVE còn hàng. Ngưỡng 2%, sample diff,
   smoke query và atomic alias cutover không bị nới/tắt. Count shard lỗi cũng fail run.
5. Lượt copy ở VERIFY thu thêm deletion của live indexer trong thời gian build/catch-up; tail replay
   hiện hành tiếp tục xử lý thay đổi sau catch-up/cutover. Không đổi watermark upstream hay replay lịch sử
   từ đầu topic. Các stage/checkpoint và payload progress công khai giữ nguyên.

## Hệ quả và kiểm thử

Reindex cần thêm hai lượt đọc/bulk lượng tombstone còn được giữ. Purge tiếp tục theo lịch và readiness
đã có. Product/Inventory/Review không cần đổi API/event schema. OpenAPI Search v2 mô tả rõ semantic bảo toàn
deletion guard và count; không thêm field hoặc status mới.

Regression dùng ES/Kafka thật: deletion trước watermark, deletion trong lúc export đang chạy, tombstone
Category, retry copy cùng/older revision, cutover rồi phát lại upsert Product/SKU/Category cũ. Count gate
vẫn phải chặn khi candidate thật sự thiếu catalog chưa xóa. Kiểm timestamp purge không bị kéo dài.
