# Contract Changelog

Chỉ ghi thay đổi có thể được provider hoặc consumer quan sát. Refactor nội bộ
không cần xuất hiện ở đây.

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
- Thêm Review Internal Rating Aggregate Snapshot Export API v1 cho Search full
  reindex. Export keyset trả `replay_from_offsets` được chụp trước scan; Search
  import snapshot rồi replay inclusive để không bỏ mất mutation trong lúc build.

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
