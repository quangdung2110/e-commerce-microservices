---
doc_id: user-adr-011
service: user
status: accepted
date: 2026-10-09
decision_owners:
  - User Service team
supersedes: ADR-002 (chỉ phần cửa sổ stale sau invalidation; cache-aside, key và TTL giữ nguyên)
evidence:
  - docs/user/data.md
  - go/services/user-service/internal/infrastructure/redis/address_cache.go
---

# ADR-011 — Epoch chống refill cache muộn

## Bối cảnh

[ADR-002](./ADR-002-address-cache-aside.md) chỉ nêu hai nguồn stale: `DEL` thất bại và
khoảng giữa commit với invalidation. Còn một race thứ ba: reader miss cache và đọc
PostgreSQL (danh sách cũ); mutation commit rồi `DEL`; reader sau đó `SET` danh sách cũ.
Danh sách cũ sống tới hết TTL 12 giờ mà không có lỗi nào. Checkout không bị ảnh hưởng
(snapshot nội bộ đọc thẳng DB), nhưng owner có thể không thấy address vừa thêm.

## Quyết định

Mỗi user có một bộ đếm epoch `user:caches:addresses-epoch:{user_id}`.

- Reader đọc epoch **trước** khi đọc PostgreSQL và đưa lại cho `Put`.
- `Put` là script Lua: chỉ `SET` khi epoch hiện tại bằng epoch reader đã thấy.
- `Invalidate` là script Lua: `INCR` epoch, `PEXPIRE` bằng TTL của list, rồi `DEL` list.
- Không đọc được epoch (Redis lỗi) thì `Put` bỏ qua, vì fill mù có thể cache danh sách cũ.
- Key thiếu có nghĩa epoch `0`; epoch hết hạn chỉ có thể khiến một fill đang chờ bị từ
  chối, không bao giờ làm một fill cũ lọt qua.

Key, nội dung, TTL (12 giờ, rỗng 15 phút) và hành vi "cache lỗi thì đọc DB" giữ nguyên.

## Các phương án đã cân nhắc

- Single-flight/lock khi fill: giảm stampede nhưng không chặn fill muộn sau invalidation.
- Xoá hai lần có trễ: xác suất, không xác định.
- Version nằm trong value: phải đọc value cũ để so sánh, khó với key đã bị `DEL`.

## Hệ quả

Thêm một key nhỏ mỗi user từng mutate address và hai script Lua. Redis cluster sẽ cần hash
tag để hai key cùng slot; hiện chạy một instance. Stampede vẫn chưa được chặn.

## Xác minh

Integration test trên Redis thật: fill muộn sau `Invalidate` bị từ chối, epoch tăng và TTL
đúng, fill không có epoch bị bỏ qua. Unit test: list đọc trong lúc mutation không bị cache.
