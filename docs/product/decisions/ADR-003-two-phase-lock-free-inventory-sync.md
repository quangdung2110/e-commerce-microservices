---
doc_id: product-adr-003
service: product
status: superseded
date: 2026-08-30
decision_owners:
  - Product Service team
supersedes: null
superseded_by: ADR-009
evidence: []
---

# ADR-003 — Inventory sync hai pha, lock-free ở pha một

## Status

`Superseded` bởi [ADR-009](./ADR-009-catalog-boundary-and-published-price.md).

Quyết định này thay thế cách xử lý cũ (mỗi event inventory là một transaction có
pessimistic lock trên Product và SKU). Cách cũ chưa từng được ghi thành ADR nên không
có ADR nào bị đánh dấu `Superseded`.

## Context

`INVENTORY_STOCK_CHANGED` là event có tần suất cao nhất mà Product tiêu thụ: mỗi lần
mua hàng sinh ít nhất một event, và một SKU bán chạy có thể nhận hàng trăm event mỗi
giây.

Thiết kế cũ xử lý mỗi event bằng một transaction với hai row lock (Product rồi SKU),
kèm logic auto-hide/auto-reactivate và insert outbox trong cùng transaction. Hệ quả:
tranh chấp lock rất cao trên SKU hot, thời gian giữ lock dài, nguy cơ deadlock chéo với
admin API, và connection pool bị worker chiếm khiến admin API chậm theo.

Bản chất của event này lại rất đơn giản: nó là một field update idempotent
(`display_quantity = available_quantity`), không có logic nghiệp vụ phức tạp cần
serialize.

## Decision

Tách xử lý thành hai pha:

- **Phase 1 (fast path)**: Kafka consumer chỉ chạm Redis. Lua script atomic (artefact đã
  được xóa cùng hạ tầng cũ) so mốc `last_inventory_sync_at`, ghi các field tồn kho vào
  `product:sku:{sku_id}` và đăng ký
  SKU vào ZSET `product:inventory:pending`. Offset chỉ được commit sau khi Redis trả về
  thành công. Phase 1 không chạm PostgreSQL và không ghi field giá.
- **Phase 2 (slow path)**: scheduled worker drain ZSET theo lô, group theo `product_id`,
  mở một transaction cho mỗi product, lock product **một lần cho cả lô SKU**, conditional
  update theo mốc thời gian, đánh giá FSM auto-hide/auto-reactivate trên toàn bộ SKU của
  product, insert outbox, commit, rồi mới `ZREM`.

Phase 2 không dùng cột `version`: điều kiện apply là mốc `last_inventory_sync_at` trong
lô phải mới hơn giá trị đang có trong DB.

## Alternatives considered

- **Giữ pessimistic locking cho từng event**: đơn giản nhất và auto-hide diễn ra tức
  thì, nhưng không chịu được tải và kéo theo cả admin API. Đây là lý do có ADR này.
- **Bỏ hẳn việc ghi tồn kho xuống PostgreSQL**, chỉ giữ trong Redis: bỏ được Phase 2,
  nhưng guard của `SHOW` và của auto-hide cần đọc tồn kho trong cùng transaction với
  trạng thái sản phẩm, và mất Redis là mất toàn bộ dữ liệu tồn kho hiển thị.
- **Gom theo cửa sổ thời gian rồi ghi một lần** mà không qua Redis: giảm được lock,
  nhưng cache không được làm ấm ngay nên trang chi tiết vẫn hiển thị số cũ trong lúc chờ.
- **Optimistic locking bằng cột `version`** ở Phase 2: tương đương về hiệu quả nhưng
  thêm một cột và thêm một nguồn xung đột, trong khi mốc thời gian sự kiện đã đủ để
  quyết định event nào mới hơn.

## Consequences

Tích cực: Phase 1 không có DB lock nên chịu được tần suất cao; cache được làm ấm ngay
nên người mua thấy trạng thái hết hàng gần như tức thì; Phase 2 giảm số lần lock theo
bậc độ lớn vì một lock phục vụ cả lô SKU của một product; hệ thống tự lành vì item chỉ
rời hàng đợi sau khi DB commit.

Tiêu cực: auto-hide và auto-reactivate trễ 500 ms – 2 s; cache và DB lệch nhau trong
khoảng thời gian đó; có thêm một worker phải vận hành và giám sát; hàng đợi pending trở
thành một thứ có thể tồn đọng và cần alert riêng.

Ràng buộc mới: Phase 1 chỉ được ghi các field tồn kho; Phase 2 không được ghi point-read
cache (xem [ADR-005](./ADR-005-single-writer-cache-ownership.md)); và `display_quantity`
không bao giờ được dùng để giữ hàng (xem
[ADR-008](./ADR-008-price-and-inventory-snapshot-ownership.md)).

Rủi ro đã biết: nếu Redis mất dữ liệu, các item pending chưa kịp sync sẽ mất và tồn kho
trong DB sẽ cũ cho tới event kế tiếp của SKU đó.

## Migration impact

Cần script Lua, ZSET pending, một consumer group riêng cho Phase 1 và một scheduled
worker cho Phase 2. Contract event từ Inventory không đổi. Consumer group mới nghĩa là
phải quyết định vị trí offset khi bật lần đầu. Rollback về mô hình một pha sẽ khôi phục
lại đúng vấn đề tranh chấp lock ban đầu.

## Verification

Chứng minh bằng: script Lua trong service khớp với hợp đồng trả về `1`/`0`/`-1`; test
tích hợp cho event stale, cache miss và crash giữa commit với `ZREM`; metric độ sâu và
tuổi của ZSET pending; và số liệu độ trễ event → DB dưới tải thực tế.
