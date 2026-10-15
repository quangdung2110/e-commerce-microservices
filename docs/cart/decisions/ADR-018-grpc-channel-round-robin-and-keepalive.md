---
doc_id: cart-adr-018
service: cart
status: accepted
date: 2026-10-15
decision_owners:
  - Cart Service team
supersedes: null
evidence:
  - contracts/decisions/ADR-007-grpc-channel-balancing-and-keepalive.md
  - go/services/cart-service/internal/infrastructure/product/client.go
  - go/services/cart-service/internal/infrastructure/product/channel_test.go
---

# ADR-018 — Kênh gRPC tới Product: `dns:///`, round-robin, keepalive

## Status

`Accepted`. Áp dụng [contracts ADR-007](../../../contracts/decisions/ADR-007-grpc-channel-balancing-and-keepalive.md). Bổ sung ADR-005 (client Product).

## Context

Cart dial Product bằng `grpc.DialContext(address, insecure)`: scheme `passthrough`, một địa chỉ, `pick_first`, không keepalive. Mọi RPC của một pod Cart vào một pod Product, kết nối chết im lặng chỉ lộ qua deadline của RPC kế tiếp, và pod Product mới không nhận tải. Thiết kế dựa hoàn toàn vào waypoint, vốn không có ở Compose ([contracts ADR-007](../../../contracts/decisions/ADR-007-grpc-channel-balancing-and-keepalive.md)).

## Decision

1. `target(address)` thêm `dns:///` cho địa chỉ trần.
2. `dialOptions(cfg)`: service config `round_robin`, keepalive 30 giây / timeout 10 giây (`DefaultKeepAliveTime`, `DefaultKeepAliveTimeout`, ghi đè qua `Config`), không ping khi không có stream, và interceptor `propagate` như trước.
3. `Config.DialOptions` cho phép test chèn resolver; không dùng ở production.
4. Readiness (`connectivity`) và breaker không đổi: `round_robin` báo READY khi có ít nhất một subchannel sẵn sàng.

## Alternatives considered

- **Giữ `pick_first`.** Không chọn: xem ADR-007.
- **Tự re-dial định kỳ trong client.** Không chọn: Product đóng kết nối quá tuổi và gRPC kết nối lại và phân giải lại, không cần mã riêng.

## Consequences

Một pod Cart nói chuyện với nhiều pod Product khi dịch vụ là headless; ClusterIP/waypoint không đổi. Kết nối được tái lập khoảng mỗi 5 phút do Product (RPC đang chạy hoàn tất trong grace 30 giây).

## Migration impact

Code: `client.go`. Không đổi config người dùng; `CART_PRODUCT_ADDRESS` vẫn là `host:port`.

## Verification

`channel_test.go`: ba backend nhận gần đều 30 lời gọi qua resolver thủ công; 2 giây gọi liên tục khi server đóng kết nối mỗi 200 ms không lỗi nào và có ít nhất ba kết nối được chấp nhận; `target()` và khoảng cách keepalive so với Product.
