---
doc_id: cart-adr-007
service: cart
status: accepted
date: 2026-08-28
decision_owners:
  - Cart Service team
supersedes: null
evidence:
  - docs/cart/scripts/add_or_update_item.lua
  - docs/cart/scripts/batch_delete_items.lua
  - docs/cart/workflows.md
  - docs/cart/operations.md
---

# ADR-007 — Redis Streams cho record outbox tạm thời

## Status

`Accepted`. Redis Streams vẫn là outbox hiện hành;
[ADR-011](./ADR-011-current-pull-read-and-redis-outbox-scope.md) giới hạn phạm vi của outbox
chỉ còn `CART_ABANDONED`.

## Context

[ADR-002](./ADR-002-redis-streams-outbox.md) quyết định ghi event vào outbox trong
cùng Lua script với cart state, thay vì publish Kafka trực tiếp. Vì Redis là nguồn
chân lý, `HSET` cart hash và record outbox cần cùng thành công hoặc cùng thất bại,
tương tự cặp `UPDATE` và `INSERT outbox_events` trong một transaction SQL.

Câu hỏi còn lại là record outbox tạm thời này nên dùng cấu trúc Redis nào. Nó không
chỉ cần lưu payload: relay phải đọc theo thứ tự, chỉ xác nhận sau khi Kafka nhận,
khôi phục được event khi relay chết, và giải phóng RAM một cách an toàn sau khi bàn
giao. Record phải được giữ lại trong Redis cho đến lúc đó; "tạm thời" không có nghĩa
là fire-and-forget.

Redis Stream đã là append-only log có consumer group và định danh entry đơn điệu.
Những khả năng này cũng là đầu vào cho `stream_id`, khóa ordering và idempotency của
Cart theo [ADR-009](./ADR-009-bucket-scoped-event-ordering.md).

## Decision

Dùng Redis Streams làm nơi lưu record outbox tạm thời. Mỗi business write thực hiện
`XADD` trong cùng Lua script đã thay đổi cart hash. Các stream dùng chung được route
theo quy tắc routing bucket của [ADR-008](./ADR-008-bucketed-redis-stream-outbox.md).

Outbox Relay phải dùng consumer group để đọc. Chỉ sau khi Kafka xác nhận produce,
relay mới `XACK`; pending entry của worker chết được nhận lại qua `XAUTOCLAIM`.
Stream Trimmer chỉ dùng `XTRIM MINID` đến mốc an toàn để giải phóng RAM. `stream_id`
do `XADD` sinh ra là định danh record và được chuyển tiếp trong Kafka headers.

Redis Stream chỉ là outbox nội bộ của Cart, không thay Kafka thành event bus dùng
chung. Downstream như Postgres Sync, Notification và Analytics tiêu thụ từ
`t.cart.events`, không được truy cập trực tiếp Redis của Cart.

## Alternatives considered

**Redis Pub/Sub.** Publish nhẹ và đơn giản, nhưng message mất ngay nếu relay không
online tại đúng thời điểm phát. Không có pending, ACK hay khả năng claim lại khi
worker chết. Không chọn vì không giải được dual-write recovery.

**Redis List.** List có thể tạo FIFO queue, ví dụ kết hợp `BRPOPLPUSH` với một
processing list. Nhưng Cart phải tự xây consumer group, ACK, lease, reclaim entry
khi worker chết, theo dõi pending và định danh thứ tự. Các cấu trúc phụ này tạo ra
những race condition mà Streams đã có ngữ nghĩa chuẩn để xử lý. Không chọn.

**Redis Sorted Set.** Có thể xếp event theo score rồi tự claim từng phần tử. Tuy
nhiên score không tự là định danh log đáng tin cậy cho nhiều event cùng thời điểm;
ACK, visibility timeout, reclaim và cleanup an toàn vẫn phải tự xây. Không chọn.

**Hash hoặc String.** Phù hợp cho state/key-value, không cho append-only log gồm
nhiều event, delivery lifecycle hay consumer coordination. Không chọn.

**Kafka trực tiếp làm outbox.** Đã bị loại ở ADR-002: sau Redis write vẫn còn cửa
sổ pod chết hoặc Kafka timeout trước khi publish thành công.

## Consequences

**Tích cực.** Record outbox có thứ tự, consumer group, pending state, ACK và
redelivery sẵn có. Relay có thể crash giữa `XREADGROUP` và `XACK` mà event vẫn được
khôi phục. `stream_id` đồng thời giải quyết ordering và dedupe, không cần sinh UUID
hay version counter riêng.

**Tích cực.** Chỉ relay cần kết nối Redis Stream. Sau khi relay chuyển giao sang
Kafka, các downstream được cô lập theo consumer group Kafka của chính chúng; lag của
một consumer như Analytics không trực tiếp ngăn Cart trim outbox.

**Tiêu cực.** Redis Streams dùng RAM và không phải storage dài hạn. Kafka outage
kéo dài hoặc relay hỏng sẽ làm stream tăng, gây áp lực lên primary store của Cart.
Cần AOF để giảm rủi ro mất event khi Redis restart, cảnh báo theo bucket cho stream
length/pending age, và Stream Trimmer hoạt động đúng quy tắc.

**Constraint.** `XACK` không xóa entry. Không được dùng `MAXLEN` lúc `XADD` vì có
thể xóa event chưa được relay; chỉ trim sau khi xác định mốc an toàn. Mọi key script
chạm tới phải có cùng hash tag bucket `{bNN}` để `HSET` và `XADD` cùng chạy được trên Redis
Cluster.

**Delivery semantic.** Toàn tuyến vẫn là at-least-once: relay retry có thể produce
trùng trước khi `XACK`. Kafka consumer phải dedupe theo `stream_id`, không được giả
định exactly-once.

## Migration impact

**Code.** Không business write nào được publish Pub/Sub, push List/ZSet hoặc ghi
cart state ngoài Lua script. Relay cần thực hiện `XREADGROUP`, `XAUTOCLAIM`, produce
Kafka và `XACK` theo đúng thứ tự.

**Data và operations.** Không có migration bảng. Redis phải bật persistence phù
hợp, khởi tạo consumer group trước khi trimmer được phép cắt, và theo dõi stream theo
từng bucket. Khi stream đã ACK và không còn pending, trimmer mới được giải phóng RAM.

**Contract.** `stream_id` được giữ nguyên từ Redis Stream qua Kafka để mọi consumer
có cùng khóa ordering/idempotency. Kafka vẫn là public integration boundary; thay
đổi cấu trúc Redis Stream không được coi là contract cho downstream.

## Verification

- Có integration test trên Redis thật: một Lua write thành công cập nhật hash và
  sinh đúng một stream entry; script lỗi không để lại một nửa kết quả.
- Có test relay crash sau khi đọc và trước `XACK`; `XAUTOCLAIM` phải nhận lại entry.
- Có test produce Kafka thất bại thì entry không được ACK.
- Có test trimmer không xóa entry pending hay entry của stream chưa có consumer group.
- Có metric/cảnh báo cho stream length, pending count, pending age và số entry trim
  theo từng bucket; Redis AOF được kiểm tra trong cấu hình triển khai.
