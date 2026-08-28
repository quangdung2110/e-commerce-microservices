---
doc_id: cart-adr-004
service: cart
status: accepted
date: 2026-08-28
decision_owners:
  - Cart Service team
supersedes: null
evidence:
  - docs/cart/data.md
  - docs/cart/schema/0001_initial_cart_schema.sql
---

# ADR-004 — Thứ tự và idempotency dựa trên Stream ID

## Status

`Accepted`

## Context

Cold store là bản chiếu bất đồng bộ của Redis, nhận dữ liệu qua Kafka. Kafka bảo đảm
at-least-once, nên consumer chắc chắn sẽ gặp message trùng lặp: producer retry,
consumer chết trước khi commit offset, rebalance.

Tệ hơn trùng lặp là sai thứ tự. Người dùng bấm tăng số lượng lên 2 rồi bấm tiếp lên
3. Nếu event "lên 2" đến sau event "lên 3" và consumer ghi mù, cold store lưu 2 —
sai vĩnh viễn cho tới thao tác kế tiếp.

Cần một khóa để trả lời câu hỏi "event này mới hơn hay cũ hơn thứ tôi đã ghi?".
Timestamp nghiệp vụ do ứng dụng sinh không đủ tin: nhiều pod, nhiều đồng hồ, và
đồng hồ có thể nhảy lùi khi đồng bộ NTP. Hai thao tác trong cùng một mili giây cho
ra cùng một giá trị và không phân biệt được thứ tự.

## Decision

`stream_id` — định danh entry do Redis `XADD` sinh ra — là khóa thứ tự và khóa
idempotency duy nhất cho toàn bộ hệ thống event của Cart.

Nó do một tiến trình duy nhất sinh ra (Redis), đơn điệu tăng trong một stream, và
phần sequence xử lý được trường hợp nhiều event trong cùng một mili giây.

Relay bắt buộc truyền `stream_id` cùng các thành phần của nó vào header Kafka.
Consumer lưu giá trị này vào `cart_items.redis_stream_id` cho từng dòng item, và vào
`carts.last_event_stream_id` cho trạng thái giỏ.

Quy tắc ghi: chỉ ghi khi `stream_id` của event **lớn hơn** giá trị đã lưu. Bằng nhau
là duplicate và bị bỏ qua. Không có câu lệnh ghi nào của consumer được phép thiếu
mệnh đề so sánh này.

Timestamp nghiệp vụ vẫn tồn tại trong payload nhưng chỉ để hiển thị và dựng dòng thời
gian; nó không bao giờ được dùng để quyết định thứ tự.

Định danh event ở Kafka cũng được sinh xác định từ `stream_id` và `cart_id`, không
phải UUID ngẫu nhiên, để mọi lần retry cho ra cùng một định danh.

## Alternatives considered

**Timestamp nghiệp vụ do ứng dụng sinh.** Sẵn có trong payload, không cần thêm gì.
Nhưng phụ thuộc đồng hồ của nhiều pod, không phân biệt được các thao tác trong cùng
mili giây, và sai lệch âm thầm khi đồng hồ nhảy lùi. Không chọn.

**Số phiên bản tăng dần lưu trong Redis.** Chính xác về thứ tự, nhưng cần thêm một
trường và thêm một bước tăng trong mỗi script — trong khi Redis đã sinh sẵn một giá
trị có đúng tính chất cần thiết. Không chọn vì trùng lặp chức năng.

**Kafka offset.** Đơn điệu tăng, nhưng chỉ trong phạm vi một partition, và nó thuộc
tầng vận chuyển chứ không thuộc dữ liệu. Replay từ topic khác hoặc đổi số partition
sẽ làm mọi giá trị đã lưu trở nên vô nghĩa. Không chọn.

**Bảng inbox ghi nhận mọi định danh event đã xử lý.** Cách chống trùng tổng quát,
nhưng cần thêm một bảng ghi mỗi message và một tác vụ dọn bảng đó — trong khi guard
so sánh trên chính dòng dữ liệu đã đủ. Không chọn.

## Consequences

**Tích cực.** Chống trùng lặp và chống sai thứ tự bằng cùng một cơ chế, không cần
bảng phụ. Replay từ offset cũ trở thành thao tác an toàn, nên đây cũng chính là công
cụ sửa lệch dữ liệu. Không phụ thuộc đồng hồ của ứng dụng.

**Tiêu cực.** Event cũ bị bỏ qua **im lặng**: câu lệnh không khớp dòng nào và không
có lỗi nào được sinh ra. Điều này đúng về mặt hành vi nhưng khiến việc chẩn đoán khó
hơn — một lỗi logic khiến mọi event bị bỏ qua trông giống hệt một hệ thống đang chạy
tốt. Vì vậy số dòng bị guard bỏ qua phải được đo và theo dõi, không chỉ để trong log.

**Constraint mới.** Việc so sánh `stream_id` phải cho ra thứ tự đúng cả ở phần
sequence, không chỉ phần timestamp. So sánh chuỗi thuần sẽ sai khi độ dài phần
sequence khác nhau — đây là một cái bẫy thật, và là chỗ cần test có chủ đích.

**Ràng buộc kiến trúc.** Cách tiếp cận này gắn chặt cold store với việc dữ liệu đến
từ Redis Streams. Nếu sau này outbox chuyển sang cơ chế khác, khóa thứ tự phải được
thiết kế lại và dữ liệu đã lưu phải được xử lý.

## Migration impact

**Code.** Mọi câu lệnh ghi của consumer phải có mệnh đề guard. Không có ngoại lệ, kể
cả cho các event trạng thái tưởng như vô hại.

**Data.** Hai cột guard là phần bắt buộc của schema; xem
[`0001_initial_cart_schema.sql`](../schema/0001_initial_cart_schema.sql).

**Contract.** Header Kafka mang các trường thứ tự; consumer downstream nào cần
dedupe đều dùng chung cơ chế này.

## Verification

- Có test event trùng lặp không làm thay đổi dữ liệu.
- Có test event cũ đến sau event mới không ghi đè.
- Có test so sánh `stream_id` cho ra thứ tự đúng khi phần sequence có độ dài khác nhau.
- Có test delete đến trước add và trạng thái cuối cùng vẫn đúng.
- Có metric đếm số dòng bị guard bỏ qua, và giá trị này được theo dõi chứ không chỉ
  ghi log.
