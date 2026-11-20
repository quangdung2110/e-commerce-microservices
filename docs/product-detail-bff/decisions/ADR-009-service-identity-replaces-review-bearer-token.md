---
doc_id: product-detail-bff-adr-009
service: product-detail-bff
status: accepted
date: 2026-10-15
decision_owners:
  - Storefront team
supersedes: null
amends: ADR-007-hot-path-clarifications
evidence:
  - contracts/decisions/ADR-004-service-identity-allowlist.md
  - java/services/product-detail-bff/src/main/java/com/ecommerce/pdp/client/ReviewClient.java
  - java/services/product-detail-bff/src/main/java/com/ecommerce/pdp/config/PdpProperties.java
  - java/services/product-detail-bff/src/test/java/com/ecommerce/pdp/ProductDetailApiTest.java
---

# ADR-009 — Bỏ bearer token Review; `X-Service-Identity` là identity duy nhất

## Status

`Accepted`. Sửa mục 6 của [ADR-007](./ADR-007-hot-path-clarifications.md) (Review nhận
`Authorization: Bearer` từ `PDP_REVIEW_SERVICE_TOKEN`) theo [contracts ADR-004](../../../contracts/decisions/ADR-004-service-identity-allowlist.md)
và [Review ADR-015](../../review/decisions/ADR-015-service-identity-header-replaces-bearer-tokens.md).

## Context

Review từng đòi bearer token tĩnh cho route PDP summary, nên BFF giữ `PDP_REVIEW_SERVICE_TOKEN`, từ chối khởi động
khi thiếu token lúc Review bật, và đặt tài liệu xoay vòng token. Review nay kiểm `X-Service-Identity` với
allowlist `pdp-callers` (mặc định `product-detail-bff`), và BFF đã gửi header đó trên mọi lời gọi qua
`UpstreamHttp`.

## Decision

1. `ReviewClient` không gắn `Authorization`; identity là `X-Service-Identity: product-detail-bff`.
2. Bỏ `pdp.review.service-token` / `PDP_REVIEW_SERVICE_TOKEN` và ràng buộc "token bắt buộc khi Review bật" khỏi
   `PdpProperties`; bỏ kiểm tra tương ứng ở readiness và test.
3. Phân loại lỗi không đổi: `401`/`403` từ Review vẫn là lỗi cấu hình (không tính vào circuit breaker, có alert),
   section Review `UNAVAILABLE`.

## Alternatives considered

Giữ token song song với identity: không chọn, vì Review đã bỏ nó (cùng lý do với Review ADR-015).

## Consequences

BFF không còn secret nào cho Review; bớt một biến bắt buộc khi bật Review. Triển khai BFF và Review phải đi cùng
nhau: BFF mới gửi gì Review cũ vẫn chấp nhận, nhưng Review mới từ chối BFF cũ nếu allowlist bị đổi.

## Migration impact

Code: `ReviewClient`, `PdpProperties.ReviewUpstream`, `application.yml`. Test: `AbstractPdpTest`,
`PdpPropertiesValidationTest`, `DisabledDependenciesTest`, `ProductDetailApiTest`. Tài liệu: README,
interfaces, development, operations, workflows. Compose: bỏ `PDP_REVIEW_SERVICE_TOKEN`.

## Verification

`ProductDetailApiTest.dependenciesReceiveOneBatchAndTheRightHeaders`: Review nhận `X-Service-Identity` và không
nhận `Authorization`. `PdpPropertiesValidationTest`: cấu hình đầy đủ khởi động được không cần token.
