---
doc_id: checkout-adr-020
service: checkout
status: accepted
date: 2026-10-15
decision_owners:
  - Checkout Service team
supersedes: null
evidence:
  - contracts/decisions/ADR-007-grpc-channel-balancing-and-keepalive.md
  - java/services/checkout-service/src/main/java/com/ecommerce/checkout/client/ProductClient.java
  - java/services/checkout-service/src/test/java/com/ecommerce/checkout/client/ProductChannelTest.java
---

# ADR-020 — Kênh gRPC tới Product: round-robin và keepalive

## Status

`Accepted`. Áp dụng [contracts ADR-007](../../../contracts/decisions/ADR-007-grpc-channel-balancing-and-keepalive.md). Bổ sung ADR-014.

## Context

`ProductClient` dựng kênh bằng `forTarget(...).usePlaintext()` với mặc định `pick_first` và không keepalive, nên mọi `GetBatchSkus` của một pod Checkout vào một pod Product ([contracts ADR-007](../../../contracts/decisions/ADR-007-grpc-channel-balancing-and-keepalive.md)).

## Decision

`ProductClient.channelFor(target)` đặt `defaultLoadBalancingPolicy("round_robin")`, `keepAliveTime` 30 giây, `keepAliveTimeout` 10 giây, `keepAliveWithoutCalls(false)`. Constructor bean dùng nó; constructor test vẫn nhận `ManagedChannel` dựng sẵn. Target vẫn là `CHECKOUT_PRODUCT_GRPC_TARGET` (resolver DNS mặc định của grpc-java).

## Alternatives considered

- **Đưa tham số vào `CheckoutProperties`.** Không chọn: chưa có nhu cầu đổi, và bảng validation của properties là bề mặt cấu hình thêm phải bảo trì.

## Consequences

Giống Cart: tải chia đều giữa các pod khi Service headless; kênh kết nối lại khi Product đóng kết nối quá tuổi. Warm-up và `ClientTransports` không đổi.

## Migration impact

Code: `ProductClient`.

## Verification

`ProductChannelTest`: ba backend nhận gần đều 30 lời gọi qua `NameResolverProvider` thử nghiệm; hằng số keepalive trên ngưỡng Product cho phép. `ClientTransportsTest` vẫn xanh.
