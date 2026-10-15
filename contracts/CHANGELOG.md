# Contract Changelog

Chỉ ghi thay đổi có thể được provider hoặc consumer quan sát. Refactor nội bộ
không cần xuất hiện ở đây.

## 2026-10-16 (Bằng chứng resilience, mesh)

- [ADR-010](decisions/ADR-010-resilience-evidence-fault-injection-and-load-tests.md): fault injection ở mức socket cho client HTTP của Order và Search, load test k6 chỉ đọc
  (`loadtest/k6`, kết quả ở [docs/performance/load-test.md](../docs/performance/load-test.md)).
- Cái quan sát được, từ các lỗi test tìm ra: Order không còn coi `2xx` có body cụt hoặc hỏng là `ACCEPTED` (nay `UNKNOWN` `BODY_UNREADABLE`); Product Detail BFF, Order và Inventory gửi
  `Retry-After: 2` trên mọi `503` retryable (trước đây chỉ khi nơi ném lỗi có ước lượng), theo [ADR-005](decisions/ADR-005-retry-after-on-throttling-and-unavailable.md). Không đổi schema.
- Mesh: [`deploy/mesh/`](../deploy/mesh/README.md) có manifest Istio 1.24.2 sinh từ `docs/mesh/runtime-contract.md`; runtime contract §5.2 tách hai hàng cấp quyền chéo (Order submissions, Finance Ops)
  và §7 thêm hai route còn thiếu.

## 2026-10-15 (Provider thật theo contract)

- [ADR-009](decisions/ADR-009-provider-verification-against-running-stack.md): `contracts/tools/verify_providers.py` và
  `provider-probes.yaml` kiểm 39 probe chỉ đọc trên provider đang chạy với OpenAPI của interaction.
- Sửa contract theo những gì provider thật trả (không đổi hành vi runtime): `payment/v1` thêm `UNAUTHENTICATED` và `FORBIDDEN` vào
  enum `code` (Payment trả hai mã này từ [ADR-004](decisions/ADR-004-service-identity-allowlist.md)); `auth/internal/v1` khai báo body
  `application/problem+json` cho cả tám response lỗi (trước đó khai báo không có body).

## 2026-10-15 (Cổng CI)

- [ADR-008](decisions/ADR-008-contract-ci-gates.md): CI chạy `validate-contracts.ps1 -StrictTools` (buf, redocly, asyncapi bắt buộc, lint mọi spec),
  cổng breaking change (`buf breaking` cho proto, `oasdiff` cho OpenAPI; `contracts/tools/check-breaking.sh`) và test của service bị ảnh hưởng
  (`.github/workflows/services.yml`). Spec sai phát hiện khi bật: `user/v1` thêm `servers`, `product-detail-bff/v1` thêm `security: []`
  cho `getProductDetail` (endpoint ẩn danh). Không đổi hành vi runtime.

## 2026-10-15 (Kênh gRPC)

- [ADR-007](decisions/ADR-007-grpc-channel-balancing-and-keepalive.md): client gRPC dial `dns:///` với `round_robin` và
  keepalive 30 giây/10 giây; provider đóng kết nối quá tuổi và cho phép ping từ 20 giây. Quy tắc ở
  [conventions/grpc.md](conventions/grpc.md#kênh-client). Không đổi proto hay status.
- Cái quan sát được: Cart và Checkout chia RPC tới Product giữa mọi địa chỉ DNS trả về; Product (`application.yml`) đặt
  `grpc.server.max-connection-age` 5 phút, `max-connection-age-grace` 30 giây, `permit-keep-alive-time` 20 giây. Kết nối
  tái lập khoảng mỗi 5 phút (RPC đang chạy hoàn tất). Cụm Kubernetes không có waypoint cần Service headless cho cổng gRPC.

## 2026-10-15 (Chi tiết lỗi gRPC)

- [ADR-006](decisions/ADR-006-grpc-error-details.md): mọi status lỗi do mã ứng dụng của provider gRPC trả phải mang
  `google.rpc.ErrorInfo` (`domain`, `reason` ổn định, metadata `retryable`), kèm `BadRequest` cho `INVALID_ARGUMENT` và `RetryInfo`
  cho `UNAVAILABLE`/`RESOURCE_EXHAUSTED`; catalog của từng service ở `errors.yaml`. Quy tắc ở
  [conventions/grpc.md](conventions/grpc.md#lỗi).
- Product gRPC v1: thêm [`errors.yaml`](grpc/ecommerce/product/v1/errors.yaml) với sáu `reason`. Không đổi proto hay stub. Lỗi
  database trước đây là `UNKNOWN`, nay là `UNAVAILABLE` `CATALOG_STORE_UNAVAILABLE`; `INVALID_ARGUMENT`, `UNAUTHENTICATED`,
  `PERMISSION_DENIED` giữ nguyên mã status và có thêm chi tiết, nên client cũ không bị ảnh hưởng.
- Cái quan sát được: Cart trả `503 PRODUCT_UNAVAILABLE` với `retryable=false` (không `Retry-After`) khi Product từ chối request
  vì lỗi của caller, và không còn đếm các lỗi đó vào circuit breaker; Checkout ghi `reason` trong log.

## 2026-10-15 (Retry-After)

- [ADR-005](decisions/ADR-005-retry-after-on-throttling-and-unavailable.md): mọi `429` và mọi `503` có `retryable: true`
  phải có `Retry-After` (số nguyên giây `1`–`3600`); `503` không retry được không có. 46 response trên 7 OpenAPI trước đó
  không khai báo header: Auth internal, User (`429`), Cart, Notification internal, Payment v1 và internal, Search v2 (`503`).
  Chỉ thêm header response nên tương thích ngược; Payment v1 và internal tách response `Unavailable` khỏi `Problem` để chỉ
  `503` mang header.
- Cái quan sát được: Cart, Notification, Payment (kể cả webhook Stripe) và Search gửi `Retry-After: 2` trên `503` retryable;
  User, Auth, Notification làm tròn lên thời gian còn lại của cửa sổ rate limit thay vì làm tròn xuống.
- `contracts/tools/check_http_contracts.py` kiểm quy tắc này trong CI.

## 2026-10-15 (Danh tính service nội bộ)

- [ADR-004](decisions/ADR-004-service-identity-allowlist.md) chuẩn hoá cách kiểm caller nội bộ: allowlist
  `X-Service-Identity` (gRPC `x-service-identity`) cấu hình được theo nhóm route, thiếu là `401`/`UNAUTHENTICATED`,
  ngoài danh sách là `403`/`PERMISSION_DENIED`; bảng chuẩn ở [conventions/service-identity.md](conventions/service-identity.md).
- Review internal HTTP v1: security scheme đổi từ bearer token sang `apiKey` header `X-Service-Identity`; mã lỗi
  `401 UNAUTHENTICATED` và `403 FORBIDDEN` và body không đổi. Caller (Product Detail BFF, Search) bỏ `Authorization`;
  `REVIEW_INTERNAL_*_TOKENS`, `PDP_REVIEW_SERVICE_TOKEN`, `REVIEW_INTERNAL_TOKEN` bị bỏ. Breaking cho caller ngoài catalog
  còn gửi token và không gửi `X-Service-Identity`; trong catalog không có caller nào như vậy.
- Payment HTTP v1: `401` và `403` được thêm vào sáu operation Order-facing (identity thiếu hoặc ngoài allowlist,
  trước đây `400 VALIDATION_FAILED`). Order coi cả ba là từ chối bền vững như trước.
- Product gRPC v1: Product kiểm `x-service-identity` (`UNAUTHENTICATED`/`PERMISSION_DENIED`); Cart đã gửi nó. Reflection
  tắt mặc định. Stub Go của Cart được sinh lại (thiếu `base_price`, `catalog_revision`, `category_ids`); không đổi
  hành vi vì Cart không đọc các field đó.

## 2026-10-15 (Deadline giữa các service)

- [ADR-003](decisions/ADR-003-deadline-propagation.md) thêm header HTTP `X-Request-Timeout-Ms` (số nguyên
  `1`–`600000`, mili giây caller chờ) và quy tắc deadline gRPC; bảng chuẩn ở
  [conventions/deadlines.md](conventions/deadlines.md). Header tuỳ chọn, bị bỏ qua khi sai hoặc thiếu; không
  contract nào bị breaking. Gateway xoá bản do client gửi.
- Cái quan sát được: Checkout, Product Detail BFF, Order, Search và client Product của Promotion gửi header
  tới provider; deadline gRPC của Checkout → Product bằng timeout hiệu dụng thay vì trần cố định; Cart đọc
  header trên `/internal/v1/carts/{user_id}/checkout-snapshot`; Order không chờ `PAYMENT_READY` quá phần
  ngân sách còn lại; provider đếm request vượt deadline bằng `http.server.caller_deadline_exceeded{route}`.
- Timeout Order tới Inventory và Promotion giảm từ 10 giây xuống 8 giây, timeout export của Search từ 30
  giây xuống 25 giây, để nhỏ hơn timeout mesh ít nhất 1 giây. Mesh §7 thêm dòng cho `GET`
  `/internal/inventory/v1/commands/*` và `GET` Payment.

## 2026-10-15 (Trace và correlation giữa các service)

- [ADR-002](decisions/ADR-002-correlation-and-trace-propagation.md) chuẩn hoá bốn định danh
  (`traceparent`/`tracestate`, `X-Request-Id`, `X-Correlation-Id`, `X-Causation-Id`), định dạng
  `^[A-Za-z0-9._:-]{8,128}$` và quy tắc cho bên gọi/bên nhận; bảng chuẩn ở
  [conventions/tracing.md](conventions/tracing.md), metadata gRPC ở [conventions/grpc.md](conventions/grpc.md).
  Không đổi schema hay mã lỗi; không contract nào bị breaking.
- Cái quan sát được: Cart gửi `x-service-identity`, `x-request-id` và (khi có) `traceparent`/`tracestate`
  tới Product gRPC; Order gửi `X-Request-Id` (và pass-through `traceparent`/`tracestate` khi đang phục vụ
  một request) tới Inventory, Promotion, Payment; Checkout và Product Detail BFF gửi thêm `X-Request-Id`
  cho Inventory; Order và Cart trả `X-Request-Id` trong response. Header sai định dạng bị thay, không bị `400`.

## 2026-10-12 (Inventory ADMIN — unfiltered lists)

- Inventory ADMIN HTTP v1 is `1.1.0` (additive, [Inventory ADR-029](../docs/inventory/decisions/ADR-029-unfiltered-admin-lists.md)).
  `warehouse_id` is no longer required on `GET /dispatch-queue`, `/inspection-queue` and `/quarantine-items`, and
  `GET /stock-positions` and `/stock-movements` no longer require "`warehouse_id` or `sku_id`": every list returns all
  rows when called without parameters and the parameters only narrow it. A request that used to be `400` is now `200`.
- New query parameter `source_operation_type` (exact match) on `GET /stock-movements` (`SELLABLE_RECEIPT` = stock
  receipts, `RETURN_RECEIPT` = returned goods).
- New operation `GET /api/v1/admin/inventory/refund-scope-summaries` (`listAdminRefundScopes`, optional `state`, cursor
  pagination, newest registration first). `GET /refund-scopes?order_id=` is unchanged.
- Frontend `generated/inventory-admin.ts` updated.

## 2026-10-11 (Payment audit)

- Payment HTTP: refund PAYMENT_NOT_SUCCESS/REFUND_AMOUNT_EXCEEDED are HTTP 400;
  malformed/unknown/coerced JSON is 400 VALIDATION_FAILED; DB errors are retryable
  503 DEPENDENCY_UNAVAILABLE, with Problem JSON, correlation and no-store.
  X-Correlation-Id is limited to 128 characters to match the financial event schema.
- Payment Ops correction: PAYMENT_FAILED_CONFIRMED requires error_code;
  PAYMENT_CANCELED requires reason_code; terminal non-capture requires canceled
  evidence identifying the stored PaymentIntent. Evidence participates in canonical
  idempotency. SUCCESS requires Charge ch_ and matching PaymentIntent id/status/latest_charge
  evidence. Validation tightening is approved for the pilot; adoption remains unverified.
  Internal query limit, error statuses and no-store headers are explicit.
- Ops reconciliation executes synchronously (202 COMPLETED); freeze blocks automatic
  balance posting, returns a failed run without advancing the completion watermark.
- Payment catalog is java/implemented/pilot; provider interactions are implemented.
  Business metrics, KMS and automatic heartbeat remain unimplemented.

## 2026-10-11 (Order O3 — delivery confirmation)

- Order HTTP v1 is `1.1.0` (additive): new `POST /api/v1/admin/orders/{order_id}/delivery-confirmations`
  (`confirmDeliveryAsAdmin`, ADMIN only). It is the **only** way a line moves `DISPATCHED -> DELIVERED`; the last
  confirmation completes the order and emits `order.completed.v1`. Body `lines[] {order_line_id, delivered_at,
  delivery_reference}` (1–100 unique lines, all-or-nothing); synchronous `200` with `DeliveryConfirmationResult`; no
  `Idempotency-Key` (idempotent by line state). New problem codes: `DELIVERY_REQUIRES_DISPATCH` (`409`, line still
  `PROCESSING`), `DELIVERY_ALREADY_CONFIRMED` (`409`, different reference/time on an already delivered line),
  `INVALID_DELIVERY_CONFIRMATION` (`422`, unknown/duplicate line, `delivered_at` before dispatch or in the future).
  Frontend `generated/order.ts` regenerated.
- Order Events v1 is `1.1.0`: the `deliveryFacts` channel and `consumeDeliveryFacts` operation are removed.
  **Removed contract:** `contracts/events/delivery/v1` (`com.ecommerce.delivery.order-line.delivered.v1`,
  `t.delivery.order.events.v1`). No Delivery service ever existed, so no provider or consumer migration is needed
  (the contract was `proposed`). Inventory `line-dispatched` is unchanged and still means only "left the warehouse".
- Notification `ORDER_COMPLETED` descriptions (contract + Go copy) say "every line confirmed delivered by an Admin".
- See [Order ADR-021](../docs/order/decisions/ADR-021-admin-delivery-confirmation.md).


## 2026-10-10 (Notification sync)

- Notification internal HTTP v1 is `1.1.0` (additive; `x-adoption-status` stays `proposed`):
  - Every operation now declares the required `X-Service-Identity` header. Caller authentication moved from application-level
    mTLS to the service mesh ([Notification D-21](../docs/notification/decisions.md), same model as Auth ADR-007 and User
    ADR-008): the application checks the header against an allowlist in every environment (missing → `401`, outside the
    allowlist → `403`); it no longer terminates TLS and the header is accepted in every `app.env`, not only in development.
    `securitySchemes` and the `401`/`403` descriptions say so.
  - Responses the service already returned are now declared: `400` on `redriveDelivery` (past `expires_at` without a new one,
    bad body or `Idempotency-Key`), `404` on `getLaneState` (lane outside the enum) and `addManualSuppression` (user without
    a contact), `409` on `setReplayMode` (`Idempotency-Key` conflict) and `503` (`DEPENDENCY_UNAVAILABLE`, retryable) on the
    idempotent mutations. Fixed a YAML defect in `ReplayMode.ignore_events_older_than` (an unquoted comma in the description
    made Redocly reject the file).
- Notification Events v1: new examples `notification-failed.v1.json` and `notification-complaint-received.v1.json`, referenced
  from the AsyncAPI messages. Consumer DLQ messages written by Notification carry a new header `x-dlq-consumer-group`
  (Notification D-22); several consumer DLQs (`t.auth.events.dlq`, `t.review.events.dlq`, `t.user.events.dlq`,
  `t.cart.events.dlq`) are shared with other services and are listed in `docs/notification/10-interfaces.md` §9.
- Catalog: `notification-service` is `runtime: go`, `status: implemented`; new operator workload `notification-ops` (the
  principal the mesh and the code already used) replaces `finance-ops` as the consumer of
  `notification-ops-operates-notification`; `notification-publishes-outcomes`, `gateway-routes-notification-inbox`,
  `support-queries-notification-status` and `notification-ops-operates-notification` are `implemented` (provider side).
  `ADOPTION.md` gains the three Notification rows, all `proposed` with the remaining gap.

## 2026-10-10 (Search sync)

- Search HTTP v2 is `2.2.0` and `implemented` (was `proposed`). Additive, in the same major version:
  - New admin operations under `/api/v2/search/reindex-runs`: `listReindexRuns` (`GET`, `status`, `limit` 1–100),
    `cancelReindexRun` (`POST …/{run_id}/cancellation`, `202`) and `rollbackReindexRun` (`POST …/{run_id}/rollback`,
    synchronous `200`). New run status `CANCELLED` (terminal; `ROLLED_BACK` already existed but had no operation producing it); `ReindexRun` carries `reason` and `actor`
    (a run started by the `catalog-reindex-requested` control event has actor `system:catalog-control`); new
    failure codes `RUN_CANCELLED` and `RUN_ABANDONED`.
  - New error codes `REINDEX_PRECONDITION_FAILED` (`409`, cluster red or free disk below 1.5× the primary store; retryable),
    `REINDEX_RUN_NOT_CANCELLABLE` (`409`), `REINDEX_ROLLBACK_UNAVAILABLE` (`409`), and the protocol codes `NOT_FOUND`,
    `METHOD_NOT_ALLOWED`, `NOT_ACCEPTABLE`, `UNSUPPORTED_MEDIA_TYPE`. `504 SEARCH_TIMEOUT` is declared where Elasticsearch can
    time out. `UNAUTHORIZED` stays and is now in the error catalog.
- Observable behaviour that changed in the service and is now stated
  ([Search ADR-022](../docs/search/decisions/ADR-022-post-implementation-audit-corrections.md)):
  - Request limits of the OpenAPI are enforced with `400 INVALID_QUERY_PARAMETER`, all offending parameters listed together:
    `q` ≤ 256, `category` ≤ 180, ≤ 50 `filter` items, each ≤ 256 and a decodable facet token (before, a garbage token was
    ignored and returned unfiltered results), `cursor` > 16384 is `400 INVALID_CURSOR`. `category` that does not exist is
    `400` naming the field.
  - No error is `500` because of the request: unreadable body, missing parameter, wrong type, unknown route, wrong method,
    wrong `Accept` or `Content-Type` are `400`/`404`/`405`/`406`/`415` problem+json. Elasticsearch `429`/`502`/`503` is
    `503 ELASTICSEARCH_UNAVAILABLE`, `408`/`504` and a search that timed out or lost a shard is `504 SEARCH_TIMEOUT`, never a `200` with partial data.
  - `trace_id` is always present; a caller `X-Request-Id` is echoed only when 16–64 characters of `[A-Za-z0-9._:-]`,
    otherwise Search generates one.
  - A reindex run is now resumable and observable end to end: a stuck run is resumed or abandoned (max 3 resumes), a new
    trigger is no longer blocked by a dead run, the new index is verified (count drift, 200-SKU sample, smoke queries) before
    the atomic alias swap, and old indices are removed after 24 hours.
  - The Product event tombstone protection window of Search is 45 days (30-day topic retention + 7-day segment + margin;
    was 35). `CDN_BASE_URL` must be an absolute `http(s)` URL without trailing slash; default `https://cdn.local`.
- Catalog: `storefront-queries-search` is `implemented`. `ADOPTION.md` Search HTTP v2 row is `implemented`.
- Product Events v1: no schema change; the `asyncapi.yaml` comment now says each consumer picks its own retry policy
  (Search has no DLQ, ADR-005).

## 2026-10-10 (Promotion CDC test)

- Promotion Events v1 (documentation of behaviour that already existed; no schema changed): the Debezium `EventRouter`
  puts the outbox payload on the wire as a JSON object but omits a field whose value is an explicit `null` (for
  example `discount.max_discount_vnd` of an uncapped percentage). Absent and `null` mean the same; Product reads
  both through `hasNonNull`. The AsyncAPI no longer says the payload reaches the wire "verbatim".
  `OutboxCdcIntegrationTest` proves the shipped connector against real Kafka and Debezium.

## 2026-10-10 (Promotion sync)

- Promotion HTTP v1 is `implemented` as a whole (was `proposed`; `x-known-runtime-gap` removed). Observable behaviour
  that changed in the service and is now stated ([Promotion ADR-019](../docs/promotion/decisions/ADR-019-post-implementation-audit-corrections.md)):
  - `POST /internal/v1/promotion-allocation/technical-cancellations` is guarded. Only `promotion-legacy-migrator` reaches it
    (`order-service` and every other caller: `403`). The body is `LegacyTechnicalCancellationRequest`
    (`migration_record_id`, `pre_financial_watermark_evidence_sha256`; `additionalProperties: false`) and the header
    `X-Legacy-Migration-Proof` must equal `migration_record_id`: missing, not a UUID or different is `403 FORBIDDEN` with
    nothing read or written. Both proof fields are part of the request hash (replay with another record or digest is
    `409 IDEMPOTENCY_PAYLOAD_MISMATCH`) and are stored on the receipt (migration `V2`). Before, the route accepted the
    `FinalAllocationRequest` body from `order-service` with no proof, so Order could have un-redeemed a voucher.
  - `LegacyMigrationProof` no longer carries the `CANCEL_FINAL:` pattern, which a UUID can never match; the pattern moved to
    `TechnicalCancellationIdempotencyKey`, which had none.
  - `checkout_channel` of calculate (`calculatePromotionDiscount`) and reserve (`reservePromotionAllocation`) must be
    `CHECKOUT` (`TrustedCheckoutContext`): another value is `400 INVALID_REQUEST`, `errors[].field =
    context.checkout_channel`, as `listPromotionVoucherOptions` already did. Checkout and Order always send `CHECKOUT`.
  - Two identical `confirm` (or technical-cancellation) requests arriving at the same time both get the stored response; before,
    the loser could get `409 FINAL_COMMAND_STATE_INCONSISTENT` although the first had committed.
  - A reservation made while the voucher was valid is redeemed by `confirm` even if the wallet was lazily normalized to
    `EXPIRED` in the meantime (the next query of that user does it). Before, `confirm` failed with
    `FINAL_COMMAND_STATE_INCONSISTENT` after the Payment had succeeded.
  - `listPromotionVoucherOptions` answers `Cache-Control: no-store` as its OpenAPI already said.
  - A request no operation matches is a client error: `404` for an unknown route, `405` (with `Allow`) for another HTTP
    method, `400` for a Content-Type the operation does not accept (PATCH other than `application/merge-patch+json`), all
    `application/problem+json` with code `INVALID_REQUEST`. Before, all of them were `500 INTERNAL_ERROR`.
- Promotion HTTP v1 (documentation of behaviour that already existed; no wire shape changed): `PUT
  /me/promotion-vouchers/{promotion_id}` takes no body or `{}` (`requestBody.required: false`); `claimLimitedPromotionVoucher`
  and `reservePromotionAllocation` declare `404 PROMOTION_NOT_FOUND`; the five admin reads/lists that parse an id, cursor or
  limit declare `400`; `Conflict` lists `PROMOTION_CONSTRAINT_VIOLATION`.
- Catalog: `legacy-migrator-technical-cancels-promotion-allocation` stays `proposed` (the migrator workload does not exist);
  `ADOPTION.md` counts 25 operations.

## 2026-10-09 (Product Detail BFF sync)

- Product Detail BFF HTTP v1: wire shapes are unchanged and `x-adoption-status` is `implemented`. The `404` text now
  also covers any path under `/api/v1/storefront/products/` that is not a slug (empty, trailing `/`, several
  segments): the Gateway forwards the whole prefix, and the BFF answers these with the same `PRODUCT_DETAIL_NOT_FOUND`
  `application/problem+json` and `Cache-Control: no-store` instead of the framework's default JSON error.
- Observable behaviour that changed in the service and is now stated
  ([PDP ADR-008](../docs/product-detail-bff/decisions/ADR-008-post-implementation-audit-corrections.md)):
  - A status outside the provider contract (`4xx` other than `400`/`401`/`403`, `3xx`, `404` from Inventory or
    Review) is a counted contract violation. Only `400`/`401`/`403` are ignored by the circuit breaker. The PDP
    still answers `503`/`UNKNOWN`/`UNAVAILABLE` exactly as before.
  - A Product whose `storage_key` or `thumbnail_storage_key` has an empty, `.` or `..` segment, or whose strings exceed
    the Product limits (scalar 500, labels 120, `alt_text` 255, slug 180), is answered `503`: the CDN URL would escape
    the CDN base directory, or the response would break the PDP schema.
  - `/actuator/health/readiness` is `OUT_OF_SERVICE` once graceful shutdown starts.
- Catalog: `storefront-queries-product-detail` and `product-detail-bff-queries-product-catalog` are `implemented`.
  `ADOPTION.md` has a row for the PDP HTTP contract.

## 2026-10-09 (Checkout sync)

- Checkout HTTP v1: `getCheckoutSubmission` now declares `429`. The status read goes through the Gateway profile
  `api-authenticated`, which limits per client and answers `429` like the three `checkout-*` profiles do on the other
  operations; the contract named only those three. Checkout still never produces a `429`. No wire shape changed.
- Order internal HTTP v1 (documentation of behaviour that already existed; no wire shape changed): the `409` of
  `POST /internal/v1/checkout-order-submissions` also covers `IDEMPOTENCY_KEY_REUSED`,
  `CHECKOUT_PAYMENT_WINDOW_EXHAUSTED` and `ORDER_CANCELLED_DURING_PREPARATION`, and Order replays the one stored
  failure on the first POST, on every later POST and on the recovery read. The operation also states `503
  SUBMISSION_IN_PROGRESS`: the 12 s wait for `PAYMENT_READY` ended while the submission was still `PREPARING`; Order
  has the submission, so the caller retries the identical key and body. Checkout already relied on both.
- Catalog: `checkout-calculates-promotions` and `checkout-lists-voucher-options` are `implemented` (Promotion
  implements both operations and Checkout calls them). `ADOPTION.md` no longer says Checkout lacks a proto copy test
  (`ProtoCopyTest`) or a runtime, or that the frontend does not call it.

## 2026-10-09 (Auth sync)

- Auth HTTP v1 wire shapes are unchanged; the contract text now matches `auth-service`. Observable behaviour that
  changed in the service and is now stated:
  - `POST /api/v1/auth/sessions` checks the password before the account state. A wrong password is always
    `401 INVALID_CREDENTIALS`; `403 ACCOUNT_NOT_VERIFIED` and `423 ACCOUNT_LOCKED` are returned only to a caller who
    proved the password (the lock-triggering attempt still answers `423`). Before, anybody could learn that an
    address was pending or locked without the password. A wrong password against a pending or locked account no
    longer counts as a failure or extends the lock.
  - `POST /email-verification-requests` and `POST /password-reset-requests` answer `202` even after the per-account
    budget (3 resends / 15 min, 5 resets / 15 min) is spent; they send nothing instead of answering `429`, which
    only an existing account could trigger. `429` on these two endpoints now comes from the per-IP limit only.
  - Account writes are compare-and-set: a request can no longer write a stale password hash, email or status over a
    newer one (a login racing a password change or a deletion). A request that loses the race four times in a row
    answers `500 CONCURRENT_UPDATE` (`retryable`), documented on `InternalError`; a login whose password changed
    during verification answers `401`.
  - When Redis fails after a deletion, email change or password change has committed, the event (`USER_DELETED`,
    `USER_EMAIL_CHANGED`, `USER_PASSWORD_CHANGED`) is still published; the request still answers
    `500 DEPENDENCY_UNAVAILABLE`. Before, the event was lost for good and `USER_DELETED` never reached User,
    Notification, Review, Chat or Cart.
  - `400` is declared for `listSessions`, `revokeSession` and both deletion operations (bad cursor, limit, UUID or body).
    Operations that need no identity declare `security: []`. `servers` lists `auth-service:8080` and `localhost:8086`.
- Auth HTTP internal v1: User-style mesh model. Auth no longer terminates TLS or checks the client certificate: the mesh
  enforces mTLS and the `notification-service` principal. Callers must send `X-Service-Identity: notification-service`
  (Notification already did); a missing header is `401`, a service outside `internal.allowed_callers` is `403`. The rate
  limit is 120 requests/minute per (calling service, `delivery_id` or `user_id`) instead of 600/minute per client IP.
  `400` is declared for a non-UUID path parameter.
- Auth Events v1 schemas are unchanged. `USER_UNLOCKED` is now also published when login or refresh lifts an expired
  lock (exactly once per unlock: whoever wins the write publishes); before, it was lost whenever a login got there
  before the worker.
- Catalog: `auth-publishes-identity-events`, `auth-requests-security-notifications`,
  `notification-resolves-auth-delivery-content` and `notification-resolves-auth-contact` are `implemented`.

## 2026-10-09 (Review sync)

- Review HTTP v1 and Review Internal HTTP v1 are `implemented` (were `proposed`); `HttpContractIT` holds the
  service to them. Wire shapes are unchanged. Observable behaviour that changed in the service and is now stated:
  - Request bodies are `additionalProperties: false` and the service now enforces it: a field the contract does
    not define is `400 INVALID_REQUEST` and `errors[].field` names it, on every operation (`PATCH` always did).
    Before, `author_name` on create or `approved_by_admin_id` on takedown was silently dropped.
  - A parameter outside its range answers `400 INVALID_REQUEST` naming the field: `limit` outside 1..50 (1..100 on
    the admin queue, 1..1000 on the export page), `rating` outside 1..5, `preview_limit` above 5, a non-UUID path
    parameter. Before, all of these answered `500 INTERNAL_ERROR`.
  - A request with no operation answers `404 NOT_FOUND`, a known path with another method `405 METHOD_NOT_ALLOWED`
    (with `Allow`) and a non-JSON body `415 UNSUPPORTED_MEDIA_TYPE`. Before, all three answered `500`. These
    apply to every path and are not repeated per operation.
  - `400` bodies no longer carry framework exception text (class names, method signatures); `detail` and
    `errors[].message` are written by Review. `trace_id` echoes `X-Request-Id` only when it is 16..64 characters,
    the bounds of the shared Problem schema (before, any 8..128 character value was echoed and could violate it).
  - A `media_asset_ids` list that repeats an id is `400 INVALID_REQUEST` (it was `409 MEDIA_NOT_VERIFIED`).
  - A lock that cannot be taken (lock timeout, deadlock victim) answers `503 REVIEW_STORE_UNAVAILABLE`
    (retryable), as the OpenAPI always declared; before, only a lost connection or a query timeout did and the
    rest answered `500`.
  - The error code `INVALID_CURSOR` was never produced and is removed from the service; an invalid cursor is
    `INVALID_REQUEST` with `errors[].field = "cursor"`. Full catalog: `docs/review/interfaces.md` section 7.
- Review HTTP v1 text: `400` is declared for `listMyPendingReviewEligibilities`, `getMyEligibilityReviewStatus`,
  `getMyReview` and `dismissReviewReports`; `REVIEW_NOT_REPORTABLE` is stated as reserved and never produced
  (a non-`APPROVED` review answers `404 REVIEW_NOT_FOUND`); `IssueMediaUploadRequest` now states the accepted
  content types, the size limits and that `checksum` is the hex MD5 / storage ETag of the object.
- Review Internal HTTP v1: error responses are declared as `application/problem+json` (they always were on the
  wire); `ProductReviewPreviewItem.content` and `RatingAggregateSnapshot.total_with_media` are `required` (the
  service always sends them; the Events schema already required `total_with_media`); `400` is declared for the
  export page.
- Review Events v1: schemas are unchanged and `x-adoption-status` is `implemented`. A record that violates its
  contract is dead-lettered at once, and the `USER_DELETED` listener retries transient failures without a time
  limit instead of dead-lettering after 60 seconds ([Review ADR-013](../docs/review/decisions/ADR-013-privacy-failure-handling.md)).
  Examples for all eight types are in `contracts/examples/events/review-*.v1.json`.
- Catalog: the six Review interactions (`product-detail-bff-queries-review-summary`, `gateway-routes-review-api`,
  `review-publishes-rating-aggregate-events`, `review-self-consumes-rating-lifecycle-events`,
  `review-requests-user-notifications`, `search-reindexes-review-rating-aggregates`) are `implemented`.

## 2026-10-09 (Product sync)

- Product Events v1 `category-upserted` và Product HTTP v1 `CategoryExport` có thêm `facet_definitions`: mảng
  `{code, label, type}` của các attribute được map trực tiếp vào category, sắp theo `code`, rỗng khi chưa có
  mapping ([Product ADR-019](../docs/product/decisions/ADR-019-category-facet-definitions.md)). Field là
  `required` trong schema (producer luôn gửi). Rollout: consumer chấp nhận trước rồi producer phát; Search hiện
  bỏ qua field lạ nên không bị ảnh hưởng khi rollout; Search nay ghi field này vào index `categories` và dùng
  `label` làm nhãn nhóm facet (nhãn option vẫn dẫn xuất, chưa có trong descriptor).
- Đổi `name` của attribute đã được category map nay tăng `category_revision` và phát lại `category-upserted`
  (`change_kind = CATEGORY_ATTRIBUTE_CHANGED`) cho mọi category map nó, vì tên là `label` trong
  `facet_definitions`. Tạo/sửa option vẫn không phát event.
- Product HTTP v1 internal: `GET /internal/v1/products/{product_id}/snapshot` cho phép `search-service` và
  `promotion-service`, nhưng hai export (`/internal/v1/products/export`, `/internal/v1/categories/export`) chỉ
  cho `search-service`. Trước đây allowlist của Product cho Promotion gọi cả hai export, rộng hơn contract và
  mesh policy. Wire shape không đổi; caller đúng contract không bị ảnh hưởng.
- Product HTTP v1 error responses: mọi lỗi `retryable` (503) có `Retry-After`, kể cả `CATALOG_UNAVAILABLE` khi
  export không chụp được replay offsets (trước đây chỉ lỗi tạm thời của PostgreSQL có header này).
  `DELETE` Product/SKU/media nay khai báo `400` (thiếu hoặc sai `If-Match`). `BadRequest` liệt kê đúng mã
  được phát: `INVALID_PRICE` có trong enum nhưng không bao giờ được trả, giá không dương là `INVALID_REQUEST`.
  `servers` trỏ `product-service:8081` (cổng thật) thay vì `8080`.
- Product HTTP v1 và Product Events v1 là `implemented`; Product gRPC v1 vẫn `proposed` vì `.proto` còn là bản
  copy trong service (ngoại lệ có chủ đích, `ProtoCopyDriftTest` ép bản copy giống hệt nguồn chuẩn). README
  của gRPC contract bỏ "Migration note" về wire cũ vì không còn provider hay consumer nào dùng nó.
- Promotion HTTP v1 `servers`: `promotion-service:8085` (cổng thật) thay vì `8080`, thêm `localhost:8085`. Wire shape không đổi.
- Catalog: thêm `promotion-validates-product-snapshot`; `search-reindexes-product-catalog` và
  `product-publishes-catalog-events` là `implemented`. Mesh matrix trỏ dòng snapshot tới hai interaction này.

## 2026-10-09 (User sync)

- User HTTP v1 wire shapes are unchanged; the contract text now matches `user-service`. Observable behaviour
  that changed in the service and is now stated:
  - `DELETE /api/v1/users/me/addresses/{address_id}` still answers `409 DEFAULT_ADDRESS_CANNOT_BE_DELETED`
    for the default **while other addresses exist**, but the only remaining address (which is the default)
    can now be deleted, leaving an empty address book. Before, the last address could never be removed.
  - Every owner route answers `404 PROFILE_NOT_FOUND` for an account in its retention window (deleted, anonymized,
    not yet purged), exactly like a purged one. Before, `GET /profile` returned the anonymized row, `PATCH
    /profile` wrote names back into it and `POST /addresses` created an address for a deleted account.
  - `AddressList.addresses` has `maxItems: 50`; creating a 51st address is `422 INVALID_ADDRESS`.
  - `marketing_enabled` is **opt-in**: a new account starts with email and in-app on and marketing off
    (it used to start on, which disagreed with Notification's no-row default and switched marketing on for
    users who never agreed). Migration `0002` also switches off the rows whose owner never changed them.
  - The internal checkout-snapshot operation documents `403` (identity outside the allowlist) and that the
    caller must send `X-Service-Identity: checkout-service`. User no longer terminates TLS or checks the client
    certificate itself: the mesh enforces mTLS and the principal, as `docs/mesh/runtime-contract.md` already
    required. Its rate limit is per (calling service, `user_id`) instead of per client IP.
- User Events v1 schemas are unchanged. `USER_PREFERENCES_UPDATED` is now also published when User switches
  `marketing_enabled` off after a spam complaint.
- Notification Events v1 `t.notification.outcomes.v1`: `user-service` now really consumes
  `complaint-received` (group `user-service`, DLQ `t.notification.outcomes.v1.dlq`) and ignores the other
  outcomes. The schema is unchanged.
- Catalog: `checkout-reads-user-address-snapshot` and `user-publishes-profile-preference-events` are
  `implemented`; `notification-publishes-outcomes` notes describe the implemented User consumer.

## 2026-10-09 (Cart sync)

- Cart HTTP v1 and Cart Events v1 are `implemented`. HTTP wire shapes are unchanged; contract text now
  matches `cart-service`: `GET /api/v1/carts/mine` documents `429 RATE_LIMITED` (the local limiter covers
  every `/api/v1/carts/mine/**` route); the internal checkout-snapshot operation documents the required
  `X-Service-Identity` allowlist header (not "attribution only") and its `400` for a non-UUID `user_id`;
  `CartItem.image_url` is now listed as required (the service always sets it); `409 IDEMPOTENCY_CONFLICT`
  no longer promises a `Retry-After` for "in progress" requests (writes are atomic, so that state does not
  exist); `servers` points at the Gateway (`localhost:8088`) and `cart-service:8080` for the internal route
  instead of `localhost:8085` (Promotion's port).
- Cart Events v1: `x-retry-policy` now describes the relay (3 attempts per pass, then the entry stays
  pending and is retried) and that `t.cart.events.dlq` only receives malformed outbox entries. The relay
  stamped an absolute `dataschema` (`https://contracts.ecommerce.local/...`); it now stamps the
  root-relative `/contracts/events/cart/v1/schemas/cart-abandoned.schema.json` already used by
  `contracts/examples/events/cart-abandoned.v1.json` and by every other service.
- Cart consumes `com.ecommerce.auth.user-deleted.v1` from `t.auth.events` (group `cart-auth-consumer`) and
  purges the user's cart ([Cart ADR-014](../docs/cart/decisions/ADR-014-purge-cart-on-user-deleted.md)).
  Auth Events v1 lists `cart-service` among the `USER_DELETED` consumers; the schema is unchanged.
- Catalog: `cart-publishes-cart-events` listed `CART_ITEM_ADDED`, `cart-service` and `analytics-service` as
  consumers; Cart publishes only `CART_ABANDONED` and Notification is its only consumer.
  `cart-consumes-user-deleted` is new. Both Cart event interactions are `implemented`.

## 2026-10-07 (implementation)

- Checkout HTTP v1 is `implemented` (`java/services/checkout-service`); the contract is unchanged except for
  a wording fix in the Preview description (a submission in flight is SUBMITTING or RETRYABLE). Behaviour
  the contract already stated and the code now enforces: unknown request fields are `400 INVALID_REQUEST`.
- Cart HTTP v1 `GET /internal/v1/carts/{user_id}/checkout-snapshot` now exists in `cart-service`; it requires
  `X-Service-Identity: checkout-service` (`401` without it, `403` for another service) and a UUID `user_id`
  (`400`). Wire shape unchanged.
- Product gRPC v1 `Sku.category_ids` is now populated (Product-owned ancestry, root first, at most 20, the
  categories closest to the product when longer). Before, Product never set it, so Promotion category rules
  could not match. The proto copy in `product-service` is refreshed from `contracts/`.
- Promotion HTTP v1: `POST /internal/v1/promotion-voucher-options` is `implemented`; `user_tier` is optional on
  calculate and Reserve as the schema already said (the DTO used to require it); `context` is optional on
  `PUT /api/v1/me/promotion-vouchers/{promotion_id}`. Promotion options accept only
  `checkout_channel=CHECKOUT`.

## 2026-10-07

- Cart HTTP v1 `CartItem.image_url` is now always an absolute CDN URL (or the configured
  placeholder when the SKU has no image), as the schema's `format: uri` already required.
  Cart used to return the raw Product image storage key. The field is now always present;
  the shape is unchanged.
- Checkout HTTP v1 (proposed at that time) is aligned with a real Backend for Frontend
  ([Checkout ADR-014…017](../docs/checkout/decisions/README.md)):
  - every error now uses `CheckoutProblem` with a stable `code` enum, `retryable`, `action`
    and optional `status_url`, `changed_fields`, `sku_ids`, `rejected_lines`,
    `promotion_reason`, `dropped_promotion`; Order outcomes are relayed explicitly;
  - Preview without `previous_checkout_token` starts a fresh quotation that supersedes the
    current `PREVIEWED` one; while a submission is in flight Preview returns
    `409 SUBMISSION_IN_PROGRESS`;
  - new `GET /api/v1/checkout/previews/{checkout_token}/voucher-options`;
  - `PreviewItem` drops `warehouse_id`, adds `list_unit_price`, makes `variant_attributes`
    and `image_url` (CDN URL or placeholder) required and `product_id` a UUID;
  - `X-Request-Id` is a non-failing hint; no shipping fee and `final_amount ≥ 1 VND` are
    stated in the schema. Regenerate the frontend `checkout` types.
- Promotion HTTP v1 (proposed parts, [Promotion ADR-018](../docs/promotion/decisions/ADR-018-checkout-voucher-options-and-context-free-save.md)):
  new internal `POST /internal/v1/promotion-voucher-options` (checkout-service only) and
  optional `context` on `PUT /api/v1/me/promotion-vouchers/{promotion_id}` with nullable
  `evaluation`. Regenerate the frontend `promotion` types.
- Product gRPC v1 documents that `Sku.image_url` carries the storage key of the
  representative image, not an absolute URL (comment only, wire unchanged).

## 2026-10-06

- Order events v1 `order-completed` items add optional `product_name` and
  `product_image_url` (from the checkout line snapshot). Review stores them as nullable
  snapshots ([Review ADR-012](../docs/review/decisions/ADR-012-owner-surface-product-snapshot-and-review-list.md)).
- Review HTTP v1 (proposed, response-only additions): `OwnerReview` adds required-nullable
  `product_name` and `product_image_url`; `PendingEligibility` adds `order_id` and the same
  two fields; new `GET /api/v1/reviews/me` (`listMyReviews`, `OwnerReviewPage`) lists the
  caller's own reviews including hidden/removed ones. Regenerate typed clients.
- Notification `REVIEW_REPLIED` in-app/email link is now
  `{frontend.base_url}/reviews/{review_id}` instead of `/products/{product_id}?review=…`,
  because the storefront addresses products by slug.

## 2026-10-05

- Inventory proposed HTTP v1 pre-implementation corrections
  ([Inventory ADR-027](../docs/inventory/decisions/ADR-027-pre-implementation-contract-corrections.md)):
  Workflow `CloseRefundScopeRequest.reason` is only `REFUNDED`/`REJECTED`; ADMIN
  `Warehouse`/`CreateWarehouseRequest` add `name`, `UpdateWarehouseRequest` requires
  `expected_revision` (stale → `409 WAREHOUSE_CONFLICT`), `DispositionReversalRequest`
  requires `disposition_line_id`, and the catalog adds `409 WAREHOUSE_NOT_FOUND`. Receipt on
  a terminal scope is `REFUND_SCOPE_STATE_CONFLICT`. Inspection, return-receipt-line and
  disposition-line identifiers are globally unique. Workflow/Read/Export callers must send
  `X-Service-Identity` (defense-in-depth allowlist); Export readiness re-registration and
  revocation follow explicit revision rules. The ADMIN UI must regenerate its types.
- Search sends `X-Service-Identity: search-service` on Product and Inventory
  internal/export calls, as the mesh runtime contract requires.

## 2026-10-02

- Inventory proposed events v1 removes the reservation-result `reserved`, `committed`
  and `released` messages, send operations, schemas/examples and unused allocation-line
  helper. Order receives only physical dispatch and selected-scope inspection completion;
  Search availability is unchanged. Reserve/Commit/Release/recovery retain durable HTTP
  receipts, command query/replay and atomic audit/required availability work. Inventory
  and Order docs/catalogs follow [Inventory ADR-020](../docs/inventory/decisions/ADR-020-command-receipts-and-minimal-order-events.md).
  This corrects a proposed pre-implementation contract; an adopted consumer still
  requires the documented breaking-change/version and parallel-support migration.
- Order proposed HTTP v1 adds refund-case `CLOSED_INCOMPLETE` and optional
  `scope_finalization` progress separate from the financial outcome. Generated clients
  must support the new response enum before adoption; incompatible adopted consumers
  require a versioned migration rather than an assumed compatible enum expansion.
- Order internal v1 adds `CLOSE_RETURN_WITHOUT_REFUND` and its conditional
  `return_closure` identities/reason. Only a proof-backed existing Buyer manual case
  with no accepted/unknown payout initiation can use incomplete close or zero-receipt
  cancel; decision acceptance is not closure or payout evidence.
- Inventory workflow v1 clarifies existing close/cancel guards, terminal financial
  reason mapping, response-loss replay and terminal lifetime identity. No endpoint,
  request reason enum, stock transition or public closure event is added. Catalogs
  and both service target documents follow Order ADR-013; adoption remains proposed.

## 2026-09-25

- Product HTTP v1: `GET /internal/v1/product-details/{slug}` trả schema mới
  `BuyerProductDetail` thay cho `Product` của admin: category kèm breadcrumb `path`,
  `attributes[]` có tên/`value_label`, `variant_axes[]` với option đang được SKU `ACTIVE`
  dùng, media phẳng tối đa 20 cấp Product + 10 mỗi SKU, chỉ SKU `ACTIVE` và không còn
  `ETag` (Product ADR-018). Consumer duy nhất là Product Detail BFF, chưa implement.
- Product Detail BFF v1: `ProductDetailResponse` thêm bắt buộc `category`, `attributes`,
  `variant_axes`, `media`; `description` thành bắt buộc (nullable); `ProductDetailSku`
  bắt buộc `sku_code`, `variant_attributes`, `thumbnail_url` (không còn null: URL CDN hoặc
  placeholder), `base_price_vnd`; `variant_attributes` chỉ nhận giá trị scalar. URL media
  do BFF dựng từ storage key (BFF ADR-005).
- Product slug (HTTP v1 `Slug`, event `catalog-common.product.slug`) thêm pattern
  `^[a-z0-9]+(?:-[a-z0-9]+)*$`, giữ max 180 (PRODUCT-BR-013); admin gửi slug sai nhận `400`.
  Product Detail BFF v1 hạ max slug trên path/response từ 255 xuống 180 cho khớp.
- Product Detail BFF v1: `404` luôn `Cache-Control: no-store`; `503` còn xảy ra khi deadline
  còn dưới 100 ms trước khi gọi Product (BFF ADR-007).

## 2026-09-20

- Notification Events v1 mới: ba request lane `t.notification.{critical,transactional,bulk}.v1`
  (message `com.ecommerce.notification.requested.v1`, key `subject`, `template_data`
  ràng buộc `oneOf` theo template, không mang email/secret) và outcome topic
  `t.notification.outcomes.v1` với `delivered`/`bounced`/`failed`/`complaint-received`.
  Catalog 15 template tại `events/notification/v1/templates/catalog.yaml`; 5 schema
  `template_data` cho Order.
- Order Events v1 thêm `publishNotificationRequests` lên lane transactional của Notification
  cho `ORDER_CONFIRMED`, `ORDER_PAYMENT_FAILED`, `ORDER_CANCELLED`, `ORDER_COMPLETED`,
  `ORDER_REFUNDED`; snapshot phải mang tên sản phẩm. Notification không consume Payment/
  Delivery facts vì chúng không có `user_id`.
- Auth internal v1 thêm `GET /internal/v1/auth/users/{user_id}/notification-contact`
  (mTLS, `no-store`) để Notification resolve email lúc gửi cho mọi nguồn không phải Auth.
- Notification HTTP v1 (inbox in-app qua Gateway) và Notification internal v1 (status cho
  support, ops không qua Gateway) mới. Catalog interactions, Gateway route và Mesh
  authorization/deadline matrix cập nhật tương ứng.
- Thêm Checkout HTTP v1 với ba route buyer: Preview, Submit và submission recovery.
  Request không nhận item/quantity/price; response và Payment relay đều
  `Cache-Control: private, no-store`.
- Cart HTTP v1 thêm internal mTLS checkout snapshot chỉ trả
  `product_id`/`sku_id`/`quantity`; Checkout không đọc Redis keyspace của Cart.
- Product gRPC `Sku` thêm `category_ids` để Checkout dựng Promotion facts từ nguồn
  Product-owned; field không mang tồn kho.
- Promotion checkout context khóa `checkout_channel=CHECKOUT`; `user_tier` trở thành
  optional trusted snapshot và tier rule evaluate false khi thiếu authority.
- Order internal v1 khóa compatibility field `user_ack_degraded_pricing=false` và
  yêu cầu mỗi Checkout item mang Product-owned `category_ids` để Promotion Reserve
  dựng lại đúng trusted context; Order submission được siết còn 0/1 PLATFORM_VOUCHER.
  Checkout dùng UUID token làm đúng `Idempotency-Key`; Checkout/Inventory/Order thống
  nhất `line_reference=sku_id` tối đa 64 ký tự; Inventory Preview fail-closed, còn
  Order là caller duy nhất của Inventory Reserve.
- Catalog, Gateway target routes và Mesh authorization/deadline matrix được cập nhật
  đồng thời cho Cart/User/Product/Inventory/Promotion/Order dependencies của Checkout.

## 2026-09-18

- Order Events v1 mở rộng từ Review-only subset thành toàn bộ async integration
  surface của Order. Outbound chỉ còn `com.ecommerce.order.paid.v1` cho Cart và
  `com.ecommerce.order.completed.v1` cho Review; các event created/cancelled/status/
  line/refund không có implemented consumer bị loại khỏi target contract.
- `ORDER_PAID` đổi từ custom `event_type/payload` legacy sang CloudEvents 1.0,
  `order_id` decimal string canonical và payload tối thiểu `order_id`, `user_id`,
  unique `sku_ids[]`. Thêm schema và example provider-owned.
- `ORDER_COMPLETED.order_id` và `subject` được siết từ opaque identifier sang decimal
  string canonical để khớp HTTP contract và storage BIGINT của Order.
- Order AsyncAPI khai báo receive operations cho đúng tập Payment terminal/refund,
  Inventory dispatch/inspection và Delivery proof facts mà workflow thực sự xử lý;
  schema inbound tiếp tục thuộc provider tương ứng.

## 2026-09-15

- Mô hình workload identity cho mọi internal API chốt theo `docs/mesh/`: service mesh (Istio
  ambient) thực thi mTLS và allowlist caller theo principal + path; ứng dụng không nhìn thấy
  chứng chỉ caller. Header `X-Service-Identity` do caller tự đặt (giá trị = ServiceAccount name),
  chỉ dùng attribution/defense-in-depth. Consumer quan sát được: principal không được phép nhận
  `403 text/plain` (`RBAC: access denied`) từ waypoint thay vì `401`/`403` problem+json của
  provider; caller phải gửi `X-Service-Identity` trên mọi internal call.
- Promotion HTTP v1: mô tả `workloadIdentity` sửa từ "mesh/ingress xác minh và đặt header"
  thành "caller tự đặt, mesh thực thi theo principal". Không đổi tên header, không đổi allowlist.
- Product HTTP v1: `searchServiceMtls`, `productDetailBffMtls`, `UnauthorizedService`,
  `ForbiddenService` mô tả lại theo cùng mô hình. Auth internal v1 `notificationServiceMtls`,
  User v1 `checkoutService`, Review internal v1 `serviceIdentity` và Inventory README bổ sung
  cùng ghi chú. Không có thay đổi wire.
- Gateway: Traefik xoá thêm `X-Service-Identity` do client gửi, cùng nhóm với `X-User-*`.

## 2026-09-14

- Promotion HTTP v1 và Promotion Events v1 chuyển `proposed` → `implemented`:
  `java/services/promotion-service` phục vụ đủ 24 operation, ghi markdown snapshot vào
  `promotion_outbox_events` và consume `product-deleted.v1`.
- Promotion HTTP v1: security scheme `workloadIdentity` đổi từ Bearer `workload-jwt` sang
  `apiKey` header `X-Service-Identity` (mesh/ingress xác minh, cùng cơ chế Product/Review);
  allowlist theo nhóm route `checkout-service` (calculations) và `order-service` (allocation).
- Promotion HTTP v1: `VoucherEvaluation` thêm field bắt buộc `distribution_mode` để client
  phân biệt item `PUBLIC` cần save (`PUBLIC_SAVE`) hay claim (`LIMITED_CLAIM`). Conflict code
  bổ sung `EXPECTED_DISCOUNT_MISMATCH`, `VOUCHER_NOT_OWNED`, `CAMPAIGN_NOT_DRAFT`,
  `DISTRIBUTION_MODE_MISMATCH`, `*_ALREADY_EXISTS`. Mô tả confirm/technical-cancellation bỏ
  câu "commit outbox" cho khớp ADR-016 (final command không ghi outbox).
- Product HTTP v1: `searchServiceMtls` mô tả thêm Promotion Service được gọi
  `GET /internal/v1/products/{product_id}/snapshot` để validate Product trước khi tạo/sửa
  markdown rule (Product allowlist `reindex-clients`).
- Product consumer markdown snapshot chấp nhận INACTIVE tombstone (không `discount`/`starts_at`/
  `ends_at`, chỉ `inactive_reason`) theo schema `oneOf`; projection Product có migration V2
  nullable + `inactive_reason`.

## 2026-09-13

- Thêm Promotion Events AsyncAPI v1 tại `events/promotion/v1/`. Promotion sở hữu
  `com.ecommerce.promotion.product-markdown-snapshot.v1`; schema chuyển từ
  `events/product/v1/schemas/` sang `events/promotion/v1/schemas/`, file cũ trở thành shim
  `$ref` để `dataschema` URI cũ vẫn resolve; example đổi `dataschema` sang đường dẫn mới.
  Product AsyncAPI tham chiếu message qua `$ref` và không còn định nghĩa operation
  consume thay cho Promotion. `markdown_discount` chuyển từ `catalog-common` sang
  `promotion-event-common`; wire shape không đổi.
- Chốt phạm vi event Promotion v1: chỉ phát markdown snapshot và chỉ consume
  `product-deleted.v1`. Không có coupon saved/redeemed/technical-cancelled, budget,
  reservation hay Auth welcome-voucher consumer vì không có consumer/producer đã đăng ký;
  thêm sau là additive.
- Thêm Promotion HTTP OpenAPI v1 với 24 operation: quản trị campaign/promotion/markdown,
  customer suggestion/discovery/save/claim, Checkout discount calculation và bốn command
  allocation cho Order/Temporal.
- Chuẩn hóa customer route thành `/api/v1/**`, service-to-service route thành
  `/internal/v1/**`; thay các URI legacy `/v1/promotions/*`. Hai query suggestion/discovery
  dùng POST vì checkout context lớn nhưng vẫn là safe read, không save/reserve.
- Chỉ confirm và technical cancellation dùng durable `Idempotency-Key`; reserve/cancel
  dùng natural business key/payload. Customer refund không có endpoint và không gọi Promotion.
- Promotion wire ID là opaque string; money dùng `{amount_minor,currency}` và contract v1
  khóa `currency=VND`, thay vì lộ BIGINT hoặc field `*_vnd` của storage model.
- Đăng ký các interaction Promotion với Gateway, Checkout và Order trong contract catalog;
  toàn bộ contract vẫn `proposed` vì Promotion Service chưa có runtime/provider tests.

## 2026-09-09

- Auth common CloudEvents envelope v1 nay ràng buộc `id` là UUID, thay vì chỉ là
  string không rỗng của schema CloudEvents dùng chung. `USER_DELETED` có thể được
  Review lưu nguyên giá trị vào `review_event_receipts.event_id` và
  `privacy_erasure_requests.source_event_id`, đều là UUID; trước đó một event hợp lệ
  theo Auth contract nhưng `id` không phải UUID sẽ fail ở persistence boundary này.
  Đây là restriction breaking với producer Auth không conformant, nhưng runtime Auth
  hiện đã sinh UUID. Thêm fixture và test PostgreSQL disposable chứng minh cùng event
  ID insert được cả receipt lẫn privacy request theo Review DDL.
- `INVALID_MEDIA_REFERENCE` nay là `409` ở mọi nơi. Trước đó bảng lỗi domain map `400`
  trong khi OpenAPI mô tả nó trong response `409` của create/patch, nên client không
  viết được retry/UX ổn định. Ranh giới hai lỗi media cũng được chốt theo việc client
  làm được gì: `MEDIA_NOT_VERIFIED` là asset của caller nhưng chưa dùng được và
  re-upload cứu được, `INVALID_MEDIA_REFERENCE` là `asset_id` không giải được thành
  asset khả dụng của caller và retry không bao giờ cứu được. Không tồn tại và thuộc
  người khác dùng chung một code, cố ý không phân biệt để không thành existence oracle.
- `ORDER_COMPLETED.items` thêm `maxItems: 200` theo yêu cầu bounded collection của
  `contracts/conventions/data-types.md`, và chốt luật trùng key: producer phải gom theo
  `(product_id, sku_id)`, consumer nhận trùng thì dedupe giữ phần tử đầu tiên. JSON
  Schema không diễn đạt được uniqueness trên một phần key nên đây là nghĩa vụ contract
  nêu chứ không enforce; nếu không chốt thì `variant_name` được snapshot phụ thuộc thứ
  tự statement.
- `ORDER_COMPLETED.data.order_id` đổi từ `integer/int64` sang opaque string
  (1–64 ký tự). JSON number là IEEE-754 double ở JavaScript nên mọi giá trị trên
  2^53-1 bị làm tròn im lặng — contract cũ cho phép tới 2^63-1, tức là consumer JS có
  thể nhận nhầm ID mà không có lỗi nào. Kiểu string cũng là điều
  `contracts/conventions/data-types.md` yêu cầu cho ID qua boundary. Breaking;
  contract vẫn `proposed` và Review là consumer duy nhất.
- `ORDER_COMPLETED.subject` nới từ `^orders/[1-9][0-9]*$` sang
  `^orders/[A-Za-z0-9_:.-]{1,64}$` để không còn mô tả cùng một ID theo hai kiểu trong
  cùng một file, và để Order đổi định dạng nội bộ không thành breaking change.
- Thêm `contracts/examples/events/order-completed.v1.json`.
- Sửa quy trình customer erasure của Review: mỗi bảng erasable ghép identifier với một
  mốc `*_erased_at` dưới CHECK đòi cả hai hoặc không cột nào, nhưng workflow chỉ mô tả
  việc null hóa identifier. Triển khai đúng câu chữ sẽ bị `check_violation` và rollback
  mỗi lần chạy — lỗi deterministic nên retry mức critical lặp lại y hệt và deadline
  pháp lý trôi qua. Nay liệt kê đủ từng cột phải ghi cho cả bốn bảng.
- Thêm ba partial index tra theo subject cho privacy worker
  (`ix_eligibility_owner_erasure`, `ix_media_asset_owner_erasure`,
  `ix_report_reporter_erasure`). Trước đó media asset không có index nào trên
  `owner_user_id`, report chỉ có index dẫn đầu bằng `review_id`, và
  `ix_eligibility_owner_pending` partial theo `PENDING` nên không phục vụ được review
  đã tồn tại. Cả anonymize lẫn purge đều seq-scan trên đường có deadline pháp lý.
- Thay `GET /api/v1/admin/review-reports` bằng `GET /api/v1/admin/reviews?status=...`.
  Moderation queue của Review nay là một surface duy nhất và đơn vị là review chứ không
  phải report, vì `report-dismissals` thao tác theo review: một review bị ba report là
  một case một dòng. `status` nhận `REPORTED` (còn public, có report chưa dismiss) và
  `HIDDEN_BY_REPORT` (đã bị ẩn), cùng keyset `(first_reported_at, id)` tăng dần nên
  phân trang giữ nguyên ý nghĩa khi review bị ẩn. Breaking; contract vẫn `proposed` và
  chưa consumer nào adopt.
- Queue phải khóa theo review state: hide chuyển mọi report `PENDING` thành `ACTIONED`,
  nên queue cũ lọc theo report status mất case đúng lúc cần phân xử và không còn cách
  lấy `review_id` cho `report-dismissals`.
- `AdminReviewSnapshot` thêm `media`, `visibility_status`, `active_report_count`,
  `hidden_at` và `first_reported_at`. Surface admin nay cấp signed media URL kể cả khi
  review đang `HIDDEN_BY_REPORT` — ngoại lệ có chủ đích, TTL ngắn hơn public, vì report
  về ảnh/video không thể phân xử nếu không xem được. Public read và owner read không đổi.
- Chốt cách outbox của Review trở thành CloudEvent: `review_outbox.payload` là envelope
  hoàn chỉnh và lên wire nguyên văn; Debezium chỉ chọn topic/key/header. `subject`,
  `dataschema`, `time`, `sequence` nằm trong payload, không có cột riêng. Các cột
  `event_id`/`event_type`/`partition_key`/`correlation_id` là projection và bị CHECK
  constraint giữ không lệch khỏi envelope.
- Toàn bộ tám message type của Review dùng đúng một topic `t.review.events` keyed theo
  `product_id`. Connector phải pin `route.topic.replacement` tĩnh; để `EventRouter`
  route mặc định theo `aggregate_type` sẽ tách thành nhiều topic và phá ordering.
- `review_outbox.aggregate_type` bỏ `ADMIN_REPLY`, chỉ còn `REVIEW`
  (`aggregate_id = review_id`, gồm cả hai notification request) và `REVIEW_METRIC`
  (`aggregate_id = product_id`). Đây là metadata, không dùng để route.
- Thêm Inventory Storefront Availability Read API v1 cho Product Detail BFF. Endpoint
  batch mTLS chỉ trả `IN_STOCK`/`OUT_OF_STOCK`/`UNKNOWN`, revision và thời điểm đánh giá;
  không lộ quantity/warehouse/reservation, không dùng Search event/export và không thay
  thế Reserve tại Checkout/Order.
- Thêm Review Public/Admin HTTP API v1: public approved-review read, buyer
  entitlement/create/edit/report/media commands và admin moderation/reply/takedown.
  Contract dùng RFC 9457 Problem Details và tách anonymous read, buyer identity và
  admin role qua trusted Gateway headers.
- Review Media HTTP v1 cấp signed direct-upload instruction nhưng không trả object key
  hay có verify/poll API cho client. Create/edit tự verify object metadata trước attach;
  request PATCH chốt missing/`null`/empty-media-array semantics.
- Review legal takedown chuyển sang một admin actor được lấy từ trusted Gateway
  identity. Request không nhận requester/approver ID; actor, legal case và reason vẫn
  không xuất hiện trong public event payload.

## 2026-09-08

- Mở rộng Review Events v1 từ metrics-only thành contract đầy đủ cho năm lifecycle
  fact, Search-facing rating snapshot và hai Notification request: official reply đầu
  tiên và legal takedown. Không có `ADMIN_REPLY_CREATED` hay
  `REVIEW_AUTHOR_ANONYMIZED` public event.
- `com.ecommerce.review.metrics-updated.v1` nay bắt buộc có `total_with_media`; quy
  ước `sequence = data.review_revision` được chốt cho Search ordering guard.
- Thêm Order Events v1 — Review subset. `ORDER_COMPLETED` là CloudEvents v1 provider
  contract có mandatory immutable `data.items[]` để Review tạo entitlement mà không
  gọi đồng bộ Order/Product.

## 2026-09-07

- Supersede Search-facing stock-position/warehouse facts with one Inventory-owned
  per-SKU `sku-availability.changed.v1` boolean. Add mandatory Search document-readiness
  register/revoke: Inventory cannot publish/export a SKU before its Product document exists.
  Search keeps availability in `products` and adds no Inventory/reverse-lookup index.
- Thay contract Inventory legacy bằng ba OpenAPI target: Workflow cho Checkout/Order
  Saga, ADMIN cho nghiệp vụ kho và Export fixed-watermark cho Search.
- Inventory mutation bắt buộc idempotency/correlation/causation; lỗi dùng RFC 9457
  Problem Details và tách terminal domain outcome khỏi lỗi hạ tầng retryable.
- Inventory Events v1 nay có sáu CloudEvents schema trên hai topic: per-SKU availability fact
  cho Search và reservation/dispatch/refund-inspection fact cho Order. Retention tối
  thiểu tăng từ 14 lên 30 ngày; ordering guard chuyển sang aggregate revision.
- Export cũ được thay bằng readiness + per-SKU snapshot session 24 giờ và change feed
  theo durable export sequence; `410 REPLAY_WINDOW_EXPIRED` yêu cầu bootstrap availability lại.

## 2026-09-06

- Search Storefront API v2: `ReindexRun.status` thêm `SUPERSEDED`, trạng thái kết thúc
  khi một `catalog-reindex-requested` mới tới trong Build/Catch-up (ADR-017). Client
  đang match exhaustive trên enum cũ phải xử lý giá trị này.
- Search Storefront API v2: `ReindexProgress` thêm field bắt buộc
  `rating_aggregates_imported`, tương ứng pha Build rating mà ADR-016 đã thêm.
- Search Storefront API v2: `CategoryNode.product_count_updated_at` trở thành nullable.
  Category vừa được index mà job recompute chưa chạy thì chưa có mốc thời gian nào;
  `category_upsert` không khởi tạo số đếm.
- Search Storefront API v2: ví dụ của query parameter `filter` được sửa cho khớp
  encoding thật `code:type:value`. Token vẫn là chuỗi đục, không phải thay đổi hành vi.
- Search: `/api/v1/search/**` đã bị gỡ khỏi implementation. Consumer duy nhất là
  frontend storefront và phải chuyển sang `/api/v2/search/**`.

## 2026-09-05

- Thêm Review Events v1: `com.ecommerce.review.metrics-updated.v1` phát rating
  aggregate snapshot theo `product_id`, với `review_revision` đơn điệu để Search
  idempotently áp dụng event và snapshot export.
- Thay Review Internal Rating Aggregate Export API v1 stateless bằng durable export
  session: Search tạo session bằng POST, lấy page keyset qua session ID; offsets được
  chụp trước scan và replay inclusive để không bỏ mất mutation trong lúc build.

## 2026-09-04

- Thêm Search Storefront API v2 ở trạng thái proposed: SKU-first search_after,
  suggestion, category tree, facet DISTINCT Product và admin reindex resource qua
  Gateway. V2 dùng RFC 9457 Problem Details; v1 legacy phải chạy song song cho đến
  khi frontend chuyển xong.
- Product Catalog Events v1: `sku.thumbnail_storage_key` trở thành field bắt buộc của
  SKU snapshot. Product resolve IMAGE của SKU trước, fallback IMAGE cấp Product, còn
  lại `null`; consumer không tự derive từ `media[]` (ADR-013).
- Product HTTP API v1: schema `Sku` có thêm `thumbnail_storage_key` với cùng ngữ nghĩa,
  nên full reindex từ `/internal/v1/products/export` và luồng event cho ra cùng giá trị.
- Rollout: producer phải phát field trước khi consumer bật validate `required`. Consumer
  đang validate strict theo schema mới sẽ từ chối event Product phát trước khi rollout.

## 2026-09-03

- Mở rộng tương thích Product gRPC v1: `Sku` có thêm `base_price` (Money) và
  `catalog_revision`. `price` giữ nguyên nghĩa là giá bán công bố sau markdown,
  nên consumer hiện tại không phải đổi.

## 2026-09-02

- Thêm Product Catalog Events v1: CloudEvents/AsyncAPI cho SKU/category snapshots,
  tombstone, markdown state snapshot và category-tree reindex control.
- Thêm Product HTTP API v1 ở trạng thái proposed: admin quản lý Product/SKU/media,
  category/attribute và internal mTLS export/snapshot cho Search. Contract không chứa
  stock field và dùng ETag catalog revision cho Product aggregate mutation.

## 2026-08-28

- Thêm User Event v1: CloudEvents/JSON Schema cho `USER_PROFILE_UPDATED` và
  `USER_PREFERENCES_UPDATED` trên `t.user.events`.
- Mở rộng Auth Event v1: `USER_DELETED` được Auth phát trực tiếp cho User,
  Notification, Review và Chat; User không còn republish `ACCOUNT_DELETED`.

## 2026-08-27

- Thêm Auth Event v1: CloudEvents/JSON Schema cho identity lifecycle và security
  notification request.
- Thêm Auth Internal Notification Delivery API v1: Notification Service resolve
  email và nội dung secret theo `delivery_id` qua mTLS, không qua Kafka.

## 2026-08-25

- Khởi tạo contract foundation độc lập với Go/Java.
- Thêm service và interaction catalog.
- Thêm pilot Cart HTTP, Product gRPC và Cart Kafka event.
- Chuẩn hóa Money, Problem Details, CloudEvents, versioning và validation flow.
