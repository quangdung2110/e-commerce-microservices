---
doc_id: product-adr-023
service: product
status: accepted
date: 2026-10-15
decision_owners:
  - Product Service team
supersedes: null
evidence:
  - contracts/decisions/ADR-006-grpc-error-details.md
  - contracts/grpc/ecommerce/product/v1/errors.yaml
  - java/services/product-service/src/main/java/com/platform/productservice/infrastructure/grpc/ProductGrpcError.java
  - java/services/product-service/src/main/java/com/platform/productservice/infrastructure/grpc/ProductGrpcErrors.java
  - java/services/product-service/src/main/java/com/platform/productservice/infrastructure/grpc/ProductGrpcExceptionAdvice.java
  - java/services/product-service/src/test/java/com/platform/productservice/integration/ProductGrpcErrorDetailsIntegrationTest.java
---

# ADR-023 — Lỗi gRPC mang `ErrorInfo`: reason ổn định và cờ `retryable`

## Status

`Accepted`. Áp dụng [contracts ADR-006](../../../contracts/decisions/ADR-006-grpc-error-details.md). Bổ sung ADR-022.

## Context

`ProductService` trả `INVALID_ARGUMENT`, `UNAUTHENTICATED`, `PERMISSION_DENIED` với một dòng mô tả, và để lỗi cơ sở dữ liệu thoát thành `UNKNOWN` không có thông tin. Cart coi mọi lỗi là Product sập; Checkout không phân biệt được lý do ([contracts ADR-006](../../../contracts/decisions/ADR-006-grpc-error-details.md)).

## Decision

1. `ProductGrpcError` (enum) là từ vựng lỗi: `SERVICE_IDENTITY_MISSING`, `SERVICE_IDENTITY_NOT_ALLOWED`, `SKU_ID_INVALID`, `BATCH_TOO_LARGE`, `CATALOG_STORE_UNAVAILABLE`, `INTERNAL_ERROR`; khớp `contracts/grpc/ecommerce/product/v1/errors.yaml`.
2. `ProductGrpcErrors` dựng `google.rpc.Status` với `ErrorInfo` (`domain=ecommerce.product.v1`, `reason`, metadata `retryable`), `BadRequest` cho `INVALID_ARGUMENT` và `RetryInfo` (2 giây) cho `UNAVAILABLE`.
3. `GrpcServiceIdentityInterceptor` và `ProductCatalogGrpcService` dùng nó thay cho `Status.withDescription`.
4. `ProductGrpcExceptionAdvice` (`@GrpcAdvice`): `DataAccessException`/`TransactionException` → `UNAVAILABLE` `CATALOG_STORE_UNAVAILABLE`; mọi `Exception` khác → `INTERNAL` `INTERNAL_ERROR`, nguyên nhân chỉ ở log.

## Alternatives considered

- **Để `UNKNOWN`.** Không chọn: Cart/Checkout không thể phân loại.
- **Mã lỗi riêng trong trailer.** Không chọn: xem contracts ADR-006.

## Consequences

Không đổi proto hay stub. Thêm lỗi mới phải thêm vào enum và `errors.yaml`; test fail nếu lệch.

## Migration impact

Code: ba class mới và hai class sửa. Contract: `errors.yaml`. Docs: `contracts/grpc/.../README.md`, `docs/product/interfaces.md`.

## Verification

`ProductGrpcErrorDetailsIntegrationTest` (gRPC in-process, đủ sáu reason qua dây), `ProductGrpcErrorCatalogTest` (enum khớp `errors.yaml`), `GrpcServiceIdentityInterceptorTest`.
