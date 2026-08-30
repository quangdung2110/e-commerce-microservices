---
doc_id: cart-adr-008
service: cart
status: accepted
date: 2026-08-30
decision_owners:
  - Cart Service team
supersedes: ADR-003
evidence:
  - docs/cart/data.md
  - docs/cart/scripts
  - docs/cart/workflows.md
---

# ADR-008 — 64 bucketed Redis Stream outbox cùng slot với cart

## Status

`Accepted`. Thay thế [ADR-003](./ADR-003-global-sharded-stream.md).

## Context

ADR-003 đặt mọi cart key và bốn stream dùng chung dưới hash tag cố định `{cart}`.
Trong Redis Cluster, hash tag quyết định slot, nên thiết kế này vô tình dồn toàn bộ
cart state lẫn outbox vào một slot. Bốn stream chỉ tạo song song logic, không phân
tán được dữ liệu hoặc ghi Lua qua cluster.

Cart vẫn cần thay đổi hash item và `XADD` outbox là nguyên tử. Vì Redis Cluster chỉ
cho Lua chạm nhiều key cùng slot, một global stream độc lập với cart không đáp ứng
được ràng buộc này.

## Decision

Dùng 64 bucket cố định `b00..b63`. Application tính
`bucket = xxh3_64(canonical_user_id) mod 64`; công thức, số 64 và namespace `v1`
là data contract.

Cart state và outbox của user cùng bucket dùng các key sau:

```text
cart:v1:{bNN}:u:<user_token>:items    # Hash
cart:v1:{bNN}:outbox                  # Stream
```

Hai key có cùng hash tag `{bNN}`, nên Lua script có thể `HSET`/`HDEL` và `XADD`
nguyên tử. Mỗi bucket có đúng một stream; tổng số stream luôn là 64, không tăng theo
số user.

Mỗi stream có instance consumer group riêng nhưng cùng tên
`cart-outbox-relay-v1`, được tạo bằng `XGROUP CREATE <stream> cart-outbox-relay-v1
0 MKSTREAM`. Group là trạng thái giao nhận của stream, không phải relay worker.

Bốn relay worker mặc định chia 16 stream/worker; mỗi stream chỉ có một active relay
loop. Một worker có thể là consumer của nhiều group. Chỉ một active loop tránh việc
nhiều consumer trong cùng group produce hai entry liên tiếp tới Kafka theo thứ tự đảo.

Relay chỉ `XACK` sau Kafka acknowledge và recovery dùng `XAUTOCLAIM`. `event_id`
được dựng xác định từ `outbox_key + stream_id`; `outbox_bucket`, `stream_id` và
`cart_id` đi theo Kafka headers. `stream_id` là token thứ tự cho một cart, vì mọi
event của cart luôn ở cùng bucket; không coi nó là globally unique giữa 64 stream.

## Alternatives considered

**Giữ bốn global shard với tag `{cart}`.** Đơn giản hơn nhưng toàn bộ state rơi vào
một Redis Cluster slot; không chấp nhận được khi Cart là primary store.

**Một global stream không có hash tag chung với cart.** Không thể `HSET` và `XADD`
trong cùng Lua script trên Redis Cluster, tạo lại cửa sổ dual-write.

**Một stream cho mỗi cart.** Đồng vị trí với cart tự nhiên nhưng số stream tăng theo
số user và cần discovery/scheduling layer riêng.

**256 bucket.** Phân tán mịn hơn nhưng tăng số group, relay loop và thao tác vận
hành mà chưa có bằng chứng capacity cần thiết. 64 là điểm khởi đầu; scale tiếp cần
version routing mới, không chỉnh config tại chỗ.

## Consequences

**Tích cực.** Cart state được trải qua nhiều cluster slot; outbox vẫn atomic. Blast
radius của một stream/relay kẹt giảm từ khoảng một phần tư user xuống khoảng một phần
64. Số stream, group và loop là hằng số, dễ quan sát và trim.

**Tiêu cực.** Có 64 group độc lập để bootstrap, quan sát và trim. Một bucket kẹt vẫn
làm chậm mọi user cùng bucket. Scale relay cần giữ một active reader/stream; nhiều
worker cùng đọc một stream làm ordering relay phức tạp hơn.

**Constraint.** Không được đổi routing function hoặc số bucket lúc runtime. Đổi
namespace/routing cần chạy đồng thời relay/trimmer v1 và v2, drain stream v1 rồi mới
ngừng version cũ.

## Migration impact

Thay đổi này yêu cầu update key builder, Lua `KEYS[]`/`ARGV[]`, relay bootstrap,
trimmer, Kafka headers, deployment mapping và các tài liệu liên quan. `cart_id`
được tách thành logical ID `c1:<user_token>`, nên không còn phụ thuộc Redis key
format. Dữ liệu namespace cũ chỉ được ngừng đọc sau khi stream cũ đã drain theo
runbook migration riêng.

## Verification

- Test vector khẳng định cùng `user_id` luôn route cùng bucket `b00..b63`.
- Lua integration test xác nhận cart key và outbox key cùng slot, không `CROSSSLOT`.
- Bootstrap test xác nhận `XGROUP CREATE ... 0 MKSTREAM` không bỏ entry cũ.
- Relay test xác nhận một active loop giữ thứ tự stream, chỉ ACK sau Kafka success,
  và `XAUTOCLAIM` nhận lại pending entry.
- Dashboard có `XLEN`, PEL size, pending age và relay latency theo bucket.
