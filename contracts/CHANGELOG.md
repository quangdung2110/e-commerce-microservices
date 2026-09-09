# Contract Changelog

Chỉ ghi thay đổi có thể được provider hoặc consumer quan sát. Refactor nội bộ
không cần xuất hiện ở đây.

## 2026-09-09

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
