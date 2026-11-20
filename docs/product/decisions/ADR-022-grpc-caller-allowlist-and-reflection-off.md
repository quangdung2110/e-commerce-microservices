---
doc_id: product-adr-022
service: product
status: accepted
date: 2026-10-15
decision_owners:
  - Product Service team
supersedes: null
evidence:
  - contracts/decisions/ADR-004-service-identity-allowlist.md
  - java/services/product-service/src/main/java/com/platform/productservice/infrastructure/grpc/GrpcServiceIdentityInterceptor.java
  - java/services/product-service/src/main/java/com/platform/productservice/config/ProductProperties.java
  - java/services/product-service/src/main/java/com/platform/productservice/infrastructure/startup/CatalogWarmup.java
  - java/services/product-service/src/main/resources/application.yml
  - java/services/product-service/src/test/java/com/platform/productservice/infrastructure/grpc/GrpcServiceIdentityInterceptorTest.java
---

# ADR-022 — Allowlist caller cho gRPC `ProductService` và tắt reflection mặc định

## Status

`Accepted`. Áp dụng [contracts ADR-004](../../../contracts/decisions/ADR-004-service-identity-allowlist.md)
cho gRPC. Không đổi ADR nào của Product; bổ sung ADR-015 (allowlist theo nhóm route HTTP).

## Context

- Mọi route HTTP `/internal/v1/**` của Product có allowlist theo nhóm (`reindex-clients`,
  `snapshot-clients`, `buyer-read-clients`), nhưng gRPC `ProductService` thì không. Ngoài mesh, bất kỳ ai tới
  được cổng 9091 đều gọi được `GetSku`/`GetBatchSkus`.
- `grpc.server.reflection-service-enabled: true` nằm ở `application.yml` gốc, tức bật cả ở profile `prod`:
  ai tới được cổng đều đọc được toàn bộ schema.
- `CatalogWarmup` gọi `ProductService` qua một channel loopback không gắn identity.
- Cart nay gửi `x-service-identity` ([contracts ADR-002](../../../contracts/decisions/ADR-002-correlation-and-trace-propagation.md)),
  Checkout đã gửi từ trước, nên caller hợp lệ đều có tên để kiểm.

## Decision

1. Thêm `GrpcServiceIdentityInterceptor` (`@GrpcGlobalServerInterceptor`, ưu tiên cao nhất). Chỉ áp cho
   `ecommerce.product.v1.ProductService`, nên health và service khác trên cùng cổng không bị ảnh hưởng.
   Metadata `x-service-identity` thiếu hoặc rỗng là `UNAUTHENTICATED`; tên ngoài `product.internal.grpc-clients`
   là `PERMISSION_DENIED`. Mặc định `grpc-clients = [cart-service, checkout-service]`, khớp interaction
   `cart-and-checkout-query-product-skus`. Allowlist rỗng từ chối mọi caller.
2. Mỗi lần từ chối tăng `product.grpc.server.identity_rejected{reason=missing|forbidden}` và ghi WARN
   (không ghi SKU hay body).
3. `CatalogWarmup` gắn `x-service-identity` bằng caller đầu tiên của `grpc-clients` (cùng cách nó chọn identity
   cho route buyer-read), nên warm-up không bị chính interceptor chặn.
4. Reflection: `reflection-service-enabled: ${PRODUCT_GRPC_REFLECTION:false}` ở `application.yml`, và `true` ở
   `application-dev.yml`. Muốn `grpcurl` trên stack Compose (profile `prod`) thì đặt `PRODUCT_GRPC_REFLECTION=true`.

## Alternatives considered

- **Chỉ dựa vào mesh.** Không chọn: các route HTTP của cùng service đã có lớp thứ hai.
- **Cho warm-up đi vòng interceptor.** Không chọn: một ngoại lệ trong interceptor là chỗ để lách; gắn identity
  hợp lệ đơn giản hơn.
- **Tắt reflection hoàn toàn.** Không chọn: `grpcurl` hữu ích khi phát triển; bật có điều kiện là đủ.

## Consequences

Caller gRPC mới (ví dụ một service thứ ba đọc SKU) phải được thêm vào `product.internal.grpc-clients`. Stack
Compose mất reflection mặc định. Một caller quên gửi identity nay nhận `UNAUTHENTICATED`; Checkout và Cart xếp nó
vào lỗi cấu hình (không retry, không tính vào breaker).

## Migration impact

Code: `GrpcServiceIdentityInterceptor` (mới), `ProductProperties.Internal.grpcClients`, `CatalogWarmup`.
Config: `application.yml`, `application-dev.yml`. Tài liệu: `contracts/grpc/.../README.md`, mesh §5.2.

## Verification

`GrpcServiceIdentityInterceptorTest`: caller hợp lệ qua, thiếu và rỗng là `UNAUTHENTICATED`, ngoài danh sách là
`PERMISSION_DENIED`, allowlist rỗng từ chối mọi caller, counter theo `reason`. `CatalogWarmupTest` vẫn xanh.
