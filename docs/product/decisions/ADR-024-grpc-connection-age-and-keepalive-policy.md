---
doc_id: product-adr-024
service: product
status: accepted
date: 2026-10-15
decision_owners:
  - Product Service team
supersedes: null
evidence:
  - contracts/decisions/ADR-007-grpc-channel-balancing-and-keepalive.md
  - java/services/product-service/src/main/resources/application.yml
  - java/services/product-service/src/test/java/com/platform/productservice/infrastructure/grpc/GrpcServerConnectionPolicyTest.java
---

# ADR-024 — Giới hạn tuổi kết nối và chính sách keepalive của server gRPC

## Status

`Accepted`. Áp dụng [contracts ADR-007](../../../contracts/decisions/ADR-007-grpc-channel-balancing-and-keepalive.md). Bổ sung ADR-022.

## Context

Kết nối HTTP/2 từ Cart và Checkout sống vô hạn: pod Product mới không nhận RPC từ client đã kết nối, và Product mặc định (grpc-java) từ chối ping nhanh hơn 5 phút bằng `too_many_pings`, nên client không thể bật keepalive 30 giây ([contracts ADR-007](../../../contracts/decisions/ADR-007-grpc-channel-balancing-and-keepalive.md)).

## Decision

`application.yml` đặt `grpc.server.max-connection-age` 5 phút, `max-connection-age-grace` 30 giây, `permit-keep-alive-time` 20 giây, `permit-keep-alive-without-calls: false`; ba giá trị đầu đổi được bằng `GRPC_SERVER_MAX_CONNECTION_AGE`, `GRPC_SERVER_MAX_CONNECTION_AGE_GRACE`, `GRPC_SERVER_PERMIT_KEEP_ALIVE_TIME`.

## Alternatives considered

- **`max-connection-idle` thay cho tuổi.** Không chọn: kết nối của Cart/Checkout luôn có tải nên không bao giờ idle.

## Consequences

Client kết nối lại mỗi ~5 phút (jitter ±10%); RPC đang chạy hoàn tất trong grace. Với một replica (Compose) chỉ là kết nối lại cùng đích.

## Migration impact

Config: `application.yml`. Tài liệu: `contracts/grpc/.../README.md`, mesh §11.

## Verification

`GrpcServerConnectionPolicyTest` đọc `application.yml` và kiểm bốn giá trị.
