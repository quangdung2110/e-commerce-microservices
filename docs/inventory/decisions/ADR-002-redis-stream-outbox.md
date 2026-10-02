---
doc_id: inventory-adr-002
service: inventory
status: superseded
date: 2026-09-05
decision_owners:
  - Inventory Service team
supersedes: null
superseded_by: ADR-011-authoritative-consistency, ADR-014-integration-and-search-contracts
evidence:
  - java/services/inventory-service/src/main/resources/lua
---

# ADR-002 — Redis Stream outbox để event atomic với mutation

## Status

`Superseded` by [ADR-011](./ADR-011-authoritative-consistency.md) and [ADR-014](./ADR-014-integration-and-search-contracts.md). This file is historical evidence and is not implementation authority. Code references point to the removed legacy implementation.

## Context

[ADR-001](./ADR-001-redis-hot-path-commit-source.md) đặt commit của đường nóng ở Redis.
Nhưng PostgreSQL vẫn cần biết mutation đã xảy ra để ghi sổ cái, và Search vẫn cần biết
tồn kho đã đổi. Câu hỏi là: làm sao chuyển thông tin đó ra khỏi Redis mà không mất?

Cách hiển nhiên — mutate Redis rồi produce Kafka ngay trong application code — có một
lỗ hổng không vá được: nếu process chết sau khi Redis đã mutate nhưng trước khi Kafka
nhận message, mutation đó biến mất khỏi mọi hệ thống downstream. Tồn kho đã trừ trong
Redis nhưng sổ cái không bao giờ biết. Với dữ liệu tồn kho, đây là mất mát không chấp
nhận được.

Đây là bài toán dual-write cổ điển, chỉ khác là kho dữ liệu chính là Redis chứ không
phải một database quan hệ có transaction.

## Decision

**Lua script `XADD` event vào một Redis Stream nằm cùng hash slot với stock hash, trong
cùng lần chạy script với mutation. Một Relay Worker riêng đọc stream và forward sang
Kafka.**

Cụ thể:

- Key stream là `inventory:stream:{sku_id:warehouse_id}:events`, dùng chung hash tag
  với stock hash nên chắc chắn cùng slot.
- `XADD` là lệnh cuối trong script, sau khi mutation đã áp dụng. Vì Redis chạy script
  đơn luồng và không có script nào bị dừng giữa chừng, hai thao tác này hoặc cùng có
  hiệu lực hoặc cùng không.
- Relay Worker (consumer group `inventory-stream-relay`) đọc stream, gắn CloudEvents
  header, produce Kafka với key `sku_id`, và **chỉ `XACK` sau khi Kafka xác nhận**.
- Script trả mã idempotent hit thì **không** `XADD`; replay không sinh event mới.

## Alternatives considered

**Produce Kafka trực tiếp từ application sau khi script trả về.** Đơn giản nhất, ít
thành phần nhất. Nhưng có cửa sổ mất event như mô tả ở phần Context. Không chọn.

**Ghi outbox vào PostgreSQL ngay trong đường nóng.** Sẽ khôi phục được tính atomic ở
phía PostgreSQL, nhưng lại đưa một lần ghi database vào đúng đường nóng mà
[ADR-001](./ADR-001-redis-hot-path-commit-source.md) muốn tránh. Mâu thuẫn với quyết
định gốc.

**Đọc Redis keyspace notification.** Redis có thể phát thông báo khi key đổi, nhưng
đây là cơ chế fire-and-forget: client không kết nối thì thông báo mất. Không có
guarantee nào phù hợp với dữ liệu tồn kho.

**Ghi một danh sách Redis (LPUSH) thay vì Stream.** Cũng atomic trong script, nhưng
List không có consumer group, không có pending entries list, và không có cách theo dõi
entry nào đã được xử lý mà chưa ack. Stream cho đúng những thứ đó.

## Consequences

**Tích cực:**

- Không có cửa sổ mất event giữa mutation và việc ghi nhận nó.
- Relay crash là an toàn: entry chưa ack sẽ được đọc lại.
- Kafka down không làm hỏng đường nóng; message tích tụ trong stream và chảy tiếp khi
  Kafka trở lại.

**Tiêu cực:**

- Thêm một worker phải vận hành và giám sát.
- Duplicate là chuyện bình thường, không phải ngoại lệ: crash giữa produce và `XACK`
  làm event được gửi hai lần. Mọi consumer downstream **bắt buộc** phải idempotent —
  ở đây là `inventory_ledger.event_id UNIQUE`.
- Stream tích tụ khi Kafka down, tiêu tốn bộ nhớ Redis. Nếu Redis đầy, đường nóng chết
  theo — nghĩa là sự cố Kafka có thể lan sang đường nóng qua đường bộ nhớ.
- **Chính sách trim stream chưa được xác định.** Đây là nợ kỹ thuật thật: không trim
  thì stream phình vô hạn; trim sai thì mất event chưa xử lý.
- Thứ tự chỉ được bảo đảm trong phạm vi một stream, tức một `(SKU, kho)`. Không có thứ
  tự toàn cục.

**Constraint mới:** không được xóa entry khỏi stream bằng tay để "dọn dẹp". Mỗi entry
bị xóa là một mutation vĩnh viễn không vào sổ cái.

## Migration impact

- **Code:** `XADD` phải nằm trong script, không được gọi từ application sau khi script
  trả về — làm vậy là quay lại đúng bài toán dual-write.
- **Deployment:** Relay Worker phải chạy trước khi bật traffic đường nóng, nếu không
  stream tích tụ ngay từ đầu.
- **Vận hành:** cần metric độ sâu stream chưa ack và alert khi nó tăng.

## Verification

- Cả ba script tại
  `src/main/resources/lua` (legacy code, removed)
  đều kết thúc bằng `XADD` sau khi mutate, và đều trả về sớm mà không `XADD` khi gặp
  idempotent hit hoặc lỗi nghiệp vụ.
- Cần bổ sung: test giết Relay Worker giữa produce và `XACK` rồi kiểm tra sổ cái không
  có dòng trùng; test Kafka down kéo dài rồi kiểm tra không mất event; và một chính
  sách trim đã được chốt. Chưa hạng mục nào được ghi nhận hoàn thành.
