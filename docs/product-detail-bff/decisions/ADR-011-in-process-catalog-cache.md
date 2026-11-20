---
doc_id: product-detail-bff-adr-011
service: product-detail-bff
status: accepted
date: 2026-10-17
decision_owners:
  - Product Detail BFF team
supersedes: null
evidence:
  - docs/performance/pdp-catalog-cache-benchmark-2026-10-17.md
  - docs/performance/product-bff-timeout-investigation-2026-10-16.md
  - java/services/product-detail-bff/src/main/java/com/ecommerce/pdp/compose/CatalogCache.java
  - java/services/product-detail-bff/src/main/java/com/ecommerce/pdp/config/CatalogCacheProperties.java
  - java/services/product-detail-bff/src/test/java/com/ecommerce/pdp/compose/CatalogCacheTest.java
  - java/services/product-detail-bff/src/test/java/com/ecommerce/pdp/config/CatalogCachePropertiesValidationTest.java
---

# ADR-011 — Cache in-process cho catalog Product, TTL ngắn và gộp lời gọi trùng

## Status

`Accepted`. Chốt storefront cache policy mà [data.md](../data.md#cache-boundary) để ngỏ, cho
riêng phần catalog Product. Sửa một điểm của ADR-004 §2: khi cache bật, client hủy request
không hủy lời gọi Product chung (mục Decision 4).

## Context

Mỗi lượt xem PDP gọi Product buyer-read trước, tuần tự, rồi mới gọi Inventory ∥ Review.
Product chạy khoảng 10 câu SQL trong một transaction giữ connection cho mỗi lời gọi
([điều tra 16/10](../../performance/product-bff-timeout-investigation-2026-10-16.md) §5.1).
Hàng chờ pool Hikari là phần chi phối tail đã đo được (một request 191,6 ms có 145,9 ms chờ
connection), và Product vượt budget 400 ms làm circuit mở thì **mọi** PDP trả `503`.

Catalog (tên, mô tả, giá, ảnh, danh mục, thuộc tính) gần như không đổi giữa hai lượt xem
liên tiếp của cùng một sản phẩm, nhưng tải lên Product tỉ lệ với số lượt xem. Tồn kho và
review thì phải mới theo từng request (PDP-BR-002, PDP-BR-007).

## Decision

1. **Phạm vi.** BFF giữ in-process đúng payload buyer-read `200` của Product đã qua
   validate (ADR-005, ADR-008), key là slug. Không cache response PDP, availability hay
   review: Inventory và Review vẫn được gọi ở mọi request, nên quy tắc degrade và
   `Cache-Control` ([ADR-004](./ADR-004-resilience-budget-and-mesh-split.md) §5) không đổi.
2. **Độ tươi.** Entry hết hạn sau `PDP_CATALOG_CACHE_TTL` (mặc định **10 giây**, cho phép
   1–60 giây) tính từ lúc Product trả, không gia hạn khi được đọc. Không có invalidation
   theo sự kiện: TTL là độ trễ tối đa của mọi thay đổi catalog trên PDP, kể cả category và
   nhãn thuộc tính (vì vậy không dựa vào `catalog_revision`). `404`, timeout, lỗi, circuit
   mở, bulkhead đầy và hết budget **không bao giờ** được giữ: sản phẩm vừa mở bán hiện ngay,
   Product hồi phục thì lượt sau dùng ngay.
3. **Gộp lời gọi trùng (single-flight).** Khi slug chưa có entry, request đầu tiên khởi tạo
   một lời gọi Product chung; request cùng slug tới trong lúc đó chờ chung kết quả, không
   gọi thêm. Entry thành công vào cache trước khi lời gọi chung kết thúc, nên không có khe
   nào cho lời gọi thứ hai.
4. **Budget và hủy.** Lời gọi chung chạy qua `DependencyGuard` với budget Product đầy đủ
   tính từ lúc bắt đầu tải (timeout 400 ms, circuit breaker, bulkhead như cũ), tách khỏi
   request khởi tạo. Mỗi request chờ theo deadline của chính nó; hết deadline thì request đó
   trả `503` (counter `pdp_catalog_cache_wait_timeouts_total`), lời gọi chung vẫn chạy cho
   các request còn chờ. Client hủy request chỉ hủy việc chờ của request đó. Lời gọi chung
   mang `X-Request-Id` của request khởi tạo; request gộp vào không có request ID riêng ở
   Product. Khi slug đã có entry, request không gọi Product nên không bị kiểm budget pha 1.
5. **Bộ nhớ.** Trần `PDP_CATALOG_CACHE_MAX_WEIGHT` (mặc định 64 MB, cho phép 1 MB–1 GB) theo
   kích thước ước lượng của payload; vượt trần thì Caffeine loại entry ít dùng.
6. **Tắt được.** `PDP_CATALOG_CACHE_ENABLED=false` trả lại đúng hành vi trước ADR này: mỗi
   request một lời gọi Product, client hủy thì lời gọi bị hủy.

## Alternatives considered

- **Không cache, giảm số câu SQL của Product.** Giữ độ tươi tuyệt đối nhưng tải vẫn tỉ lệ
  với lượt xem và chỉ giảm chi phí mỗi lời gọi 2–3 lần. Vẫn nên làm khi số đo của Product
  chỉ ra cần, nhưng không giải quyết đỉnh tải của sản phẩm hot.
- **Cache response PDP ở CDN.** Trộn tồn kho vào cache, trái PDP-BR-002 và chính sách
  `no-store` cho response degrade.
- **Redis dùng chung.** Thêm một hop mạng và một dependency có thể hỏng cho dữ liệu dẫn
  xuất; data.md không cho projection bền. In-process đủ vì tải lên Product chỉ nhân theo số
  pod.
- **Invalidation theo sự kiện Product.** Cần Kafka consumer trong BFF (README: BFF không có)
  và vẫn phải có TTL làm lưới an toàn. Để lại cho khi 10 giây trễ không còn chấp nhận được.
- **Phục vụ entry cũ khi Product lỗi (stale-if-error).** Tăng availability nhưng đổi ngữ
  nghĩa `503` hiện hành; cần quyết định riêng.

## Consequences

- Tải lên Product tỉ lệ với số slug khác nhau được xem trong một TTL trên mỗi pod, không
  còn tỉ lệ với lượt xem. Kết quả đo trước/sau: [benchmark 17/10](../../performance/pdp-catalog-cache-benchmark-2026-10-17.md).
- Giá, tên, ảnh, markdown trên PDP có thể trễ tối đa một TTL. Tiền không sai: Checkout lấy
  giá thật từ Product qua `GetBatchSkus` ([Checkout architecture](../../checkout/architecture.md)).
- Khi hit, request không đi qua circuit breaker Product, nên circuit Product thấy ít lời
  gọi hơn; mở circuit vẫn làm các miss trả `503`, còn các hit vẫn phục vụ.
- Thêm một nguồn sai lệch khi điều tra "trang hiện giá cũ": kiểm tra TTL trước.

## Migration impact

Code: `CatalogCache`, `CatalogCacheProperties`, `ProductDetailComposer`, dependency Caffeine.
Test: `CatalogCacheTest`, `CatalogCachePropertiesValidationTest`; test cũ chạy với cache tắt
để giữ nguyên kiểm chứng một lời gọi Product mỗi request. Docs: README, architecture, data,
workflows, interfaces, operations, development.

## Verification

`CatalogCacheTest` kiểm: lượt hai trong TTL không gọi Product nhưng vẫn gọi Inventory và
Review; hết TTL thì gọi lại; `404` và lỗi không được giữ; tồn kho luôn mới; 20 request đồng
thời tạo một lời gọi Product; hủy request khởi tạo không làm hỏng request đang chờ. Benchmark
trước/sau trên stack tách biệt ở [docs/performance](../../performance/pdp-catalog-cache-benchmark-2026-10-17.md).
