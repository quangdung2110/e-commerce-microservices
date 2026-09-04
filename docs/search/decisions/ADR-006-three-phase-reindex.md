---
doc_id: search-adr-006
service: search
status: accepted
date: 2026-09-02
decision_owners:
  - Search Service team
supersedes: null
evidence: []
---

# ADR-006 — Reindex ba pha với alias swap

## Context

Mapping của Elasticsearch phần lớn là bất biến: đổi kiểu field hay analyzer bắt
buộc phải tạo index mới. Search phục vụ traffic đọc liên tục nên quá trình này
không được có downtime. Ngoài đổi mapping, reindex còn là công cụ phục hồi duy
nhất khi projection lệch.

## Decision

Reindex gồm đúng ba pha:

1. **Build** — tạo index version mới và backfill theo chunk có checkpoint, đối
   chiếu bổ sung từ export API của Product.
2. **Catch-up** — consumer group tạm đọc từ watermark offset đã ghi trước khi
   build, ghi thẳng vào index mới thay vì qua write alias.
3. **Cutover** — kiểm tra số lượng và sample, rồi chuyển cả read alias lẫn write
   alias bằng một lệnh `_aliases` atomic.

Rollback là alias swap ngược. Không có pha shadow catch-up để giữ index cũ tiếp
tục được cập nhật sau cutover.

Chỉ một run được phép tồn tại tại một thời điểm; trigger thứ hai bị từ chối.

## Alternatives considered

- **Quy trình đầy đủ có shadow catch-up.** Cho phép rollback không mất dữ liệu ở
  bất kỳ thời điểm nào, nhưng phải ghi song song hai index và phức tạp hơn nhiều so
  với tần suất đổi mapping thực tế.
- **Dừng ghi rồi reindex.** Đơn giản nhưng dữ liệu cũ dần trong suốt quá trình và
  cần cửa sổ bảo trì.
- **Tạo index mới rồi để consumer chính tự bắt kịp từ đầu topic.** Phụ thuộc
  retention của Kafka và không phục hồi được dữ liệu cũ hơn retention.

## Consequences

Tích cực: không downtime đọc và ghi; rollback nhanh bằng một lệnh; quy trình đủ
đơn giản để vận hành thực hiện được dưới áp lực.

Tiêu cực: nếu phát hiện lỗi muộn hơn cửa sổ catch-up, alias swap ngược sẽ trả về
một index thiếu vài phút dữ liệu mới nhất, và phải chạy lại reindex để hội tụ.
Trong lúc reindex, cluster phải chứa được hai index đầy đủ kèm replica.

## Migration impact

Ứng dụng chỉ được cấu hình tên alias, không bao giờ tên index vật lý; đây là điều
kiện để cutover hoạt động. Kiểm tra dung lượng là precondition bắt buộc.

## Verification

Test bắt buộc: reindex khi đang có tải ghi; hủy giữa pha build; cutover rồi
rollback; trigger trùng bị từ chối; sau cutover không còn ghi vào index cũ.
