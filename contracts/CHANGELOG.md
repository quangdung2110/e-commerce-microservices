# Contract Changelog

Chỉ ghi thay đổi có thể được provider hoặc consumer quan sát. Refactor nội bộ
không cần xuất hiện ở đây.

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
