---
doc_id: review-adr-015
service: review
status: accepted
date: 2026-10-15
decision_owners:
  - Review Service team
supersedes: null
evidence:
  - contracts/decisions/ADR-004-service-identity-allowlist.md
  - java/services/review-service/src/main/java/com/ecommerce/review/config/InternalIdentityFilter.java
  - java/services/review-service/src/main/java/com/ecommerce/review/config/ReviewProperties.java
  - java/services/review-service/src/main/resources/application.yml
  - java/services/review-service/src/test/java/com/ecommerce/review/config/InternalIdentityFilterPathTest.java
  - java/services/review-service/src/test/java/com/ecommerce/review/integration/HttpContractIT.java
  - contracts/http/review/internal/v1/openapi.yaml
---

# ADR-015 — `X-Service-Identity` thay bearer token tĩnh cho `/internal/v1/**`

## Status

`Accepted`. Áp dụng [contracts ADR-004](../../../contracts/decisions/ADR-004-service-identity-allowlist.md).
Thay cơ chế bearer token của `InternalIdentityFilter`; không đổi ADR nào khác của Review.

## Context

`/internal/v1/**` chia hai nhóm route (PDP summary cho Product Detail BFF, rating export cho Search) và mỗi nhóm
có danh sách bearer token tĩnh (`REVIEW_INTERNAL_PDP_TOKENS`, `REVIEW_INTERNAL_EXPORT_TOKENS`). Review là service
duy nhất làm vậy: hai caller phải giữ secret trong biến môi trường, xoay vòng token đúng thứ tự, và log chẩn đoán
khác mọi nơi khác. [Mesh §6/§11](../../mesh/runtime-contract.md) đã ghi đây là ứng viên hợp nhất về
`X-Service-Identity`. Cả PDP BFF và Search đã gửi `X-Service-Identity` trên mọi lời gọi.

## Decision

1. `InternalIdentityFilter` đọc `X-Service-Identity` thay `Authorization: Bearer`: thiếu là `401 UNAUTHENTICATED`,
   ngoài allowlist của nhóm là `403 FORBIDDEN`. Cấu trúc hai nhóm, quyết định theo path đã giải mã bỏ `;param`, và
   "route nội bộ không thuộc nhóm nào thì từ chối mọi caller" giữ nguyên.
2. `review.internal.pdp-callers` (env `REVIEW_INTERNAL_PDP_CALLERS`, mặc định `product-detail-bff`) và
   `review.internal.export-callers` (env `REVIEW_INTERNAL_EXPORT_CALLERS`, mặc định `search-service`) thay
   `pdp-tokens` và `export-tokens`. Bỏ cấu hình token ở profile `dev` và `test`.
3. Body lỗi và mã (`UNAUTHENTICATED`, `FORBIDDEN`) không đổi, nên contract nội bộ chỉ đổi security scheme:
   `apiKey` ở header `X-Service-Identity` thay `http bearer`.
4. PDP BFF và Search bỏ bearer token ([PDP ADR-009](../../product-detail-bff/decisions/ADR-009-service-identity-replaces-review-bearer-token.md),
   [Search ADR-024](../../search/decisions/ADR-024-drop-review-bearer-and-outbound-metrics.md)).

## Alternatives considered

- **Chấp nhận cả hai trong giai đoạn chuyển.** Không chọn: cả hai caller đã gửi identity, nên không cần cửa sổ
  chuyển; hai cơ chế cùng tồn tại là thứ ADR-004 muốn bỏ.
- **Giữ token và thêm identity.** Không chọn: vẫn còn secret phải xoay vòng.

## Consequences

Không còn secret nào giữa Review và hai caller. **Ở môi trường không có mesh (Compose), Review mất lớp bearer
token**; bảo vệ ngang các service khác (header giả được ở local, như mesh §11 đã ghi). Triển khai phải đổi hai
phía cùng lúc: caller mới sẽ không còn gửi `Authorization`, và Review cũ sẽ trả `401` cho caller thiếu token.

## Migration impact

Code: `ReviewProperties.Internal`, `InternalIdentityFilter`, `application*.yml`. Contract:
`contracts/http/review/internal/v1/openapi.yaml`, `contracts/catalog/interactions.yaml`. Tài liệu:
`interfaces.md`, `operations.md`, mesh §6/§11. Compose: bỏ `REVIEW_INTERNAL_*_TOKENS`, `REVIEW_INTERNAL_TOKEN`,
`PDP_REVIEW_SERVICE_TOKEN`.

## Verification

`InternalIdentityFilterPathTest` (thiếu, caller nhóm kia, caller đúng nhóm, path mã hoá và `;param`, route công
khai) và `HttpContractIT` (từng operation với đúng và sai nhóm, problem body) với `X-Service-Identity`.
