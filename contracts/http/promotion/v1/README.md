# Promotion Service HTTP v1 — đề xuất

Contract machine-readable nằm tại [openapi.yaml](./openapi.yaml). Contract hiện ở trạng thái `proposed`
bởi `java/services/promotion-service` (provider test `HttpContractIntegrationTest`); chưa
`verified` vì Checkout/Order chưa có consumer test.

## Các thay đổi chính so với `docs/promotion/interfaces.md`

- Dùng prefix chuẩn của repository: `/api/v1` cho Gateway API và `/internal/v1` cho
  service-to-service API, thay vì trộn tất cả operation dưới `/v1/promotions`.
- Đổi hai query có checkout context phức tạp từ `GET` sang `POST`; chúng vẫn là read-only,
  không reserve và trả `Cache-Control: no-store`.
- Tách Admin, authenticated customer, Checkout và Order/Temporal bằng security scheme rõ.
  Workload identity của `/internal/v1/**` do service mesh thực thi theo principal
  (`docs/mesh/runtime-contract.md` mục 5.2); header `X-Service-Identity` do caller tự đặt
  (cùng quy ước Product) và Promotion allowlist theo nhóm route chỉ là defense-in-depth;
  không dùng Bearer workload JWT.
- `VoucherEvaluation` mang `distribution_mode` để client biết item PUBLIC cần save hay claim.
- Admin mutation dùng `If-Match` theo parent campaign revision. Published definition không
  update in-place; thay đổi business definition phải clone aggregate DRAFT với child ID mới.
- Chỉ `CONFIRM` và `CANCEL_CONFIRMED` dùng durable `Idempotency-Key`/command receipt.
  Save/claim/reserve/cancel dùng natural business key và guard đã chốt trong domain design.
- Error dùng RFC 9457 `application/problem+json`; `CLAIM_PENDING` là accepted outcome
  `202`, không giả làm durable success.
- ID qua wire là opaque string, không lộ BIGINT/sequence. Money luôn có shape
  `{amount_minor,currency}` và v1 chỉ chấp nhận `currency=VND`; các field `*_vnd` chỉ tồn
  tại trong storage model.
- Checkout Phase 18 chỉ hỗ trợ Stripe nên `context.payment_method` bị khóa `STRIPE`;
  server không dùng payment method tùy ý do client cung cấp để quyết định eligibility.
- Enum response có `UNKNOWN` cho forward compatibility; consumer phải chịu được giá trị
  mới. Rule DSL vẫn strict theo `rule_schema_version` và từ chối node/operator lạ.

## Endpoint inventory

### Admin

| Method | Path | Mục đích |
|---|---|---|
| GET, POST | `/api/v1/admin/promotion-campaigns` | List / tạo campaign DRAFT |
| GET, PATCH | `/api/v1/admin/promotion-campaigns/{campaign_id}` | Read / sửa campaign DRAFT |
| POST | `/api/v1/admin/promotion-campaigns/{campaign_id}/copies` | Clone thành aggregate DRAFT mới |
| POST | `/api/v1/admin/promotion-campaigns/{campaign_id}/transitions` | Publish / pause / cancel |
| GET, POST | `/api/v1/admin/promotion-campaigns/{campaign_id}/promotions` | List / tạo voucher definition |
| GET, PATCH | `/api/v1/admin/promotions/{promotion_id}` | Read / sửa voucher trong DRAFT |
| POST | `/api/v1/admin/promotions/{promotion_id}/grants` | Direct grant cho một user |
| GET, POST | `/api/v1/admin/promotion-campaigns/{campaign_id}/markdown-rules` | List / tạo markdown rule |
| GET, PATCH | `/api/v1/admin/markdown-rules/{markdown_rule_id}` | Read / sửa markdown trong DRAFT |

### Customer và Checkout

| Method | Path | Mục đích |
|---|---|---|
| POST | `/api/v1/me/promotion-voucher-suggestions` | Evaluate voucher SAVED trong wallet |
| POST | `/api/v1/promotion-discovery` | Wallet + public promotion discovery |
| PUT | `/api/v1/me/promotion-vouchers/{promotion_id}` | Save public voucher và evaluate |
| POST | `/api/v1/me/promotion-voucher-claims` | Claim limited voucher; có thể trả `202 CLAIM_PENDING` |
| POST | `/internal/v1/promotion-discount-calculations` | Checkout tính 0/1 platform voucher, không mutate |

### Order / Temporal allocation

| Method | Path | Mục đích |
|---|---|---|
| POST | `/internal/v1/promotion-allocation/reservations` | TRY giữ quota/amount sau Submit |
| POST | `/internal/v1/promotion-allocation/confirmations` | Durable CONFIRM, PostgreSQL-first |
| POST | `/internal/v1/promotion-allocation/cancellations` | Release reservation trước confirm |
| POST | `/internal/v1/promotion-allocation/technical-cancellations` | Deprecated legacy migration only; dedicated `promotion-legacy-migrator` principal + immutable proof, denied to Order |

Không có endpoint refund. Customer partial/full refund không thay đổi wallet hoặc budget
của Promotion.
