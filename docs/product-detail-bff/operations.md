---
doc_id: product-detail-bff-operations
service: product-detail-bff
scope: current
status: implemented
owners:
  - Storefront team
---

# Product Detail BFF — Operations

## SLO

Giá trị khởi điểm theo [ADR-004](./decisions/ADR-004-resilience-budget-and-mesh-split.md);
là release gate cần load test xác nhận trước khi public PDP. Load test phải gồm Product có
500 SKU và đủ media (5 020 phần tử) theo
[ADR-005](./decisions/ADR-005-pdp-catalog-scope-and-media-url.md), vì payload Product lớn
nhất quyết định chi phí parse/validate/compose.

| SLI | Mục tiêu | Đo |
|---|---:|---|
| PDP availability | 99.9 % / 30 ngày | Tỷ lệ response không phải `5xx`; `200` degrade và `404` tính là thành công |
| PDP latency tại BFF | p95 ≤ 300 ms, p99 ≤ 800 ms | Từ lúc BFF nhận request tới khi ghi xong response |
| Degrade do Inventory | ≤ 1 % request / 30 ngày | Request có mọi SKU `UNKNOWN` vì call Inventory không cho kết quả hợp lệ (lỗi, timeout, circuit open, bulkhead đầy, hết budget); không tính `UNKNOWN` do Inventory trả và không tính khi Inventory bị tắt bằng cờ (`disabled`) |
| Degrade do Review | ≤ 1 % request / 30 ngày | Request có `review_section.status = UNAVAILABLE` vì call Review không cho kết quả hợp lệ; không tính khi Review bị tắt bằng cờ (`disabled`) |

Latency kỳ vọng của provider để các timeout dưới đây có nghĩa:

| Dependency | Latency kỳ vọng | Nơi provider ghi nhận |
|---|---:|---|
| Product `GET /internal/v1/product-details/{slug}` | p99 ≤ 250 ms | [product/operations.md](../product/operations.md) |
| Inventory `POST /internal/inventory/v1/sku-availabilities:batch-get` (≤ 500 SKU) | p99 ≤ 200 ms | [inventory/operations.md](../inventory/operations.md) |
| Review `GET /internal/v1/reviews/products/{product_id}/pdp-summary` | p99 ≤ 200 ms | [review/operations.md](../review/operations.md) |

## Health và degradation

BFF chỉ ready khi có thể phục vụ request và cấu hình bắt buộc ở mục
[Cấu hình](#cấu-hình) hợp lệ: Product base URL; CDN base URL (tuyệt đối, `https` trừ local) và URL
placeholder cho SKU không ảnh
([ADR-005](./decisions/ADR-005-pdp-catalog-scope-and-media-url.md)); base URL của
Inventory/Review khi dependency đó được bật; và `PDP_REVIEW_SERVICE_TOKEN` khi Review được
bật. Thiếu cấu hình thì BFF không ready thay vì trả URL hỏng hay gọi Review chắc chắn bị
`401`. Readiness không được phụ thuộc vào kết nối tới Inventory hoặc Review, vì hai
dependency đó có degradation behavior riêng. Circuit breaker mở cũng không làm BFF mất
readiness. Readiness và liveness dùng nhóm mặc định của Spring Boot (`readinessState`,
`livenessState`), không khai báo lại danh sách include: khi nhận SIGTERM, graceful shutdown
(20 giây) đưa readiness về `OUT_OF_SERVICE` (`503`) để Gateway ngừng gửi request mới trong
lúc request đang chạy được hoàn tất; liveness chỉ phản ánh tiến trình
([ADR-008](./decisions/ADR-008-post-implementation-audit-corrections.md)).

| Dependency condition | Public behavior | Tính vào circuit breaker | Owner xử lý |
|---|---|---|---|
| Product timeout/lỗi kết nối/`5xx`/response sai contract/status ngoài contract | `503` PDP | Có | Product + Storefront |
| Product `400`/`401`/`403` | `503` PDP; alert cấu hình | Không | Storefront (+ Product nếu contract đổi) |
| Product circuit open | `503` PDP kèm `Retry-After`, không gọi Product | — | Product + Storefront |
| Product bulkhead đầy | `503` PDP | Không | Storefront |
| Deadline còn dưới 100 ms trước khi gọi Product | `503` PDP, không gọi Product (`budget_skipped`) | Không | Storefront |
| Product `404` hoặc slug sai pattern | `404` PDP, `no-store` | Không | — |
| Inventory timeout/lỗi kết nối/`5xx`/response sai contract/status ngoài contract | `200` PDP; mọi SKU `UNKNOWN` | Có | Inventory + Storefront |
| Inventory `400`/`401`/`403` | `200` PDP; mọi SKU `UNKNOWN`; alert cấu hình | Không | Storefront + Inventory |
| Inventory circuit open, bulkhead đầy hoặc hết budget sau pha Product | `200` PDP; mọi SKU `UNKNOWN`, không gọi Inventory | Không | Inventory + Storefront |
| Inventory trả item `UNKNOWN` | `200` PDP; chính SKU đó là `UNKNOWN` | Không | Inventory |
| Inventory thiếu item, hoặc trả item cho SKU không được yêu cầu | `200` PDP; SKU thiếu là `UNKNOWN`, item thừa bị bỏ; ghi `contract_violation` mức item và alert | Không | Inventory |
| Inventory bị tắt (`PDP_INVENTORY_ENABLED=false`) | `200` PDP; mọi SKU `UNKNOWN`, không gọi Inventory (`disabled`); không tính SLO degrade, không alert | Không | Storefront |
| Review timeout/lỗi kết nối/`5xx`/response sai contract/status ngoài contract | `200` PDP; `review_section.status = UNAVAILABLE` | Có | Review + Storefront |
| Review `400`/`401`/`403` | `200` PDP; `UNAVAILABLE`; alert cấu hình | Không | Storefront + Review |
| Review circuit open, bulkhead đầy hoặc hết budget sau pha Product | `200` PDP; `UNAVAILABLE`, không gọi Review | Không | Review + Storefront |
| Review bị tắt (`PDP_REVIEW_ENABLED=false`) | `200` PDP; `UNAVAILABLE`, không gọi Review (`disabled`); không tính SLO degrade, không alert | Không | Storefront |
| Client/gateway hủy request | BFF hủy mọi call đang chạy, không ghi response | Không | — |

Response sai contract gồm body không parse được hoặc vi phạm schema, kể cả chuỗi vượt giới
hạn của contract provider. Với Product còn gồm vi phạm tham chiếu: `media[].sku_id` không có
trong `skus[]`, giá trị `variant_attributes` không có option trong `variant_axes`, trùng
`sku_id` hoặc `media_id`, và `storage_key`/`thumbnail_storage_key` có segment rỗng, `.` hoặc
`..` (URL CDN sẽ thoát khỏi thư mục base). Với Inventory còn gồm trùng `sku_id`, khi đó cả
batch bị bỏ. **Status ngoài contract** là mọi status không phải `2xx` mà không thuộc `400`/
`401`/`403` (lỗi cấu hình ở các hàng trên) và không phải `404` của Product: `4xx` khác như
`408`/`409`/`429`, `3xx`, hay `404` của Inventory/Review. Provider không hứa các status đó nên
chúng là response sai contract: outcome `contract_violation`, tính vào circuit breaker, vẫn
degrade như các hàng tương ứng ([ADR-008](./decisions/ADR-008-post-implementation-audit-corrections.md)). Item Inventory thiếu hoặc thừa không làm hỏng
cả batch nhưng vẫn là vi phạm contract "exactly one item per SKU": BFF ghi
vi phạm mức item (không tính vào circuit breaker) để lỗi phía Inventory không bị che
thành `UNKNOWN` bình thường
([ADR-007](./decisions/ADR-007-hot-path-clarifications.md)). Mọi `200` degrade, mọi `404`
và mọi `503` mang `Cache-Control: no-store`.

Không có Kafka consumer lag, stock projection age, export session hoặc projection
bootstrap runbook tại BFF.

## Resilience và phân chia với mesh/gateway

Quyết định đầy đủ nằm ở [ADR-004](./decisions/ADR-004-resilience-budget-and-mesh-split.md).
Mesh và gateway xử lý sự cố hạ tầng; BFF quyết định budget, fallback và khi nào ngừng
gọi. Timeout ở BFF luôn ngắn hơn timeout mesh của cùng route.

| Dependency | Timeout BFF | Retry BFF | Timeout mesh | Retry mesh | Bulkhead khởi điểm |
|---|---:|---:|---:|---|---:|
| Product buyer-read | `min(400 ms, deadline còn lại − 50 ms)` | 0 | 3 giây | 2 lần, `connect-failure,refused-stream` | 100 |
| Inventory batch-read | `min(300 ms, deadline còn lại − 50 ms)` | 0 | 2 giây | 0 | 80 |
| Review PDP summary | `min(300 ms, deadline còn lại − 50 ms)` | 0 | 2 giây | 2 lần, `connect-failure,refused-stream` | 80 |

- **Deadline PDP** 1 000 ms tính từ lúc BFF nhận request, truyền qua request context.
  Product chạy trước; Inventory và Review chạy song song sau đó. Trước mỗi pha, nếu
  deadline còn lại dưới 100 ms thì không gọi dependency của pha đó: trước pha Product thì
  trả `503`, trước pha 2 thì Inventory/Review degrade. Vì Product bị cắt tối đa 400 ms,
  pha 2 chỉ bị bỏ khi request đã tốn hơn khoảng 500 ms trước khi gọi Product (xếp hàng,
  GC, pod quá tải); đây là lớp phòng thủ, không phải đường chạy bình thường
  ([ADR-007](./decisions/ADR-007-hot-path-clarifications.md)). Gateway chờ response header của route
  PDP tối đa 3 giây và chỉ retry lỗi kết nối tới BFF
  ([gateway/runtime-contract.md §5](../gateway/runtime-contract.md#5-ma-trận-định-tuyến-bắt-buộc)).
- **Retry:** ứng dụng không retry dependency nào. Retry duy nhất là retry lỗi kết nối của
  mesh (Product, Review) theo
  [mesh/runtime-contract.md §7](../mesh/runtime-contract.md#7-traffic-policy-east-west).
  `Retry-After` trong `503` của Inventory không làm BFF chờ hay retry.
- **Circuit breaker** riêng cho từng dependency trong mỗi pod: cửa sổ 10 giây, tối thiểu
  20 call, mở khi tỷ lệ lỗi hoặc tỷ lệ call chậm ≥ 50 % (call chậm vượt 80 % timeout BFF:
  Product 320 ms, Inventory/Review 240 ms), mở 10 giây, half-open 5 call thử.
- **Bulkhead** là semaphore không hàng chờ, giới hạn theo
  `ceil(RPS mục tiêu mỗi pod × p99 kỳ vọng × 2)`; số trong bảng tính cho 200 RPS/pod.
  Connection pool của HTTP client không nhỏ hơn giới hạn bulkhead. `connectionPool`
  trong `DestinationRule` của mesh là giới hạn riêng, bảo vệ provider trước mọi caller.
- Batch size tối đa khớp contract Inventory là 500 SKU.

CDN/in-process cache là optional. Nếu bật, cache policy phải nêu TTL, invalidation,
cache key và behavior khi bypass; response degrade không bao giờ được cache
([data.md](./data.md#cache-boundary)). BFF không có persistent stock cache hoặc recovery
state. Không dùng SKU ID, product ID hoặc slug làm metric label.

## Observability và alerts

Theo dõi latency/error public PDP; theo từng dependency: latency, kết quả (`success`,
`timeout`, `connect_error`, `5xx`, `4xx`, `contract_violation`, `circuit_open`,
`bulkhead_rejected`, `budget_skipped`, `disabled`) và trạng thái circuit breaker; số item
Inventory vi phạm contract (thiếu, thừa); tỷ lệ SKU `UNKNOWN` tách theo nguồn (Inventory
trả, Inventory thiếu item, lỗi call, bỏ qua vì circuit/bulkhead/budget, `disabled`); tỷ lệ
response `review_section.status = UNAVAILABLE` tách theo nguồn tương tự. Tách lỗi Inventory
và Review khỏi Product vì severity/degraded behavior khác nhau. Mesh cung cấp golden metrics
theo cạnh gọi; metric dưới đây là ngữ nghĩa mà chỉ BFF biết.

| Metric | Label | Ý nghĩa |
|---|---|---|
| `pdp_dependency_calls_seconds` (timer) | `dependency`, `outcome` | Mỗi lời gọi và kết quả phân loại; call không phát đi (`circuit_open`, `bulkhead_rejected`, `budget_skipped`, `disabled`) ghi thời lượng 0 |
| `pdp_sku_unknown_total` | `source` = `inventory_returned`, `inventory_missing_item`, `call_failed`, `skipped`, `disabled` | Số SKU `UNKNOWN` theo nguồn |
| `pdp_review_unavailable_total` | `source` = `call_failed`, `skipped`, `disabled` | Số response có review `UNAVAILABLE` |
| `pdp_inventory_item_violations_total` | `kind` = `missing`, `extra` | Item Inventory vi phạm cam kết một item mỗi SKU ("`contract_violation` mức item" trong tài liệu này là counter này kèm một dòng log cảnh báo; outcome `contract_violation` của `pdp_dependency_calls_seconds` chỉ dành cho response sai contract của cả lời gọi) |
| `resilience4j_circuitbreaker_state` | `name` = `product`, `inventory`, `review` | Trạng thái circuit breaker |

SLO "Degrade do Inventory/Review" đếm từ `pdp_dependency_calls_seconds_count` với outcome
khác `success` và khác `disabled`; không dùng số SKU `UNKNOWN` vì `inventory_returned` là
trạng thái Inventory tự khai, không phải lỗi call.

Alert khi PDP availability hoặc latency vượt SLO; circuit Product mở; circuit Inventory
hoặc Review mở liên tục quá 5 phút; tỷ lệ degrade vượt budget ở mục SLO; bất kỳ
`401`/`403` nào từ dependency (sai service identity, `AuthorizationPolicy` hoặc Review
bearer token); bất kỳ `contract_violation` nào, kể cả mức item Inventory; `bulkhead_rejected` tăng đều (cần xem lại giới hạn hoặc số pod);
Inventory `UNKNOWN` do Inventory trả tăng bất thường. Inventory phải theo dõi freshness,
pending recompute và cache/read-model health của chính nó; BFF chỉ quan sát outcome của
query.

BFF truyền `traceparent`/`tracestate` tới cả ba dependency, request ID đã chuẩn hóa qua
`X-Request-Id` tới Product/Review và qua `X-Correlation-ID` tới Inventory
([interfaces.md](./interfaces.md#header-gửi-cho-dependency)). Request ID đó cũng nằm trong
mọi dòng log mà BFF ghi trong phạm vi một request (cảnh báo `4xx`/sai contract/lỗi lạ của
dependency và cảnh báo item Inventory thiếu hoặc thừa) dưới dạng structured field
`request_id` và `trace_id`, để tra chéo với log provider
([ADR-008](./decisions/ADR-008-post-implementation-audit-corrections.md)). Log JSON của
production có hai field này; profile `dev`/`test` in chúng dạng `request_id=...` trong dòng.

## Cấu hình

Runtime là Java 21 + Spring Boot
([ADR-006](./decisions/ADR-006-java-spring-webflux-runtime.md)); các biến dưới đây map sang
Spring property tương ứng. Secret lấy từ secret manager, không commit vào repo.

| Variable | Default | Bắt buộc | Notes |
|---|---|---|---|
| `SERVER_PORT` | `8080` | Không | Khớp `servers` của public OpenAPI và gateway upstream |
| `PDP_PRODUCT_BASE_URL` | none | Có | Ví dụ `http://product-service:8081` (cổng HTTP của Product) |
| `PDP_INVENTORY_ENABLED` | `false` | Không | Để `false` cho tới khi đạt rollout gate ở [README](./README.md#trạng-thái-rollout); khi `false`, mọi SKU `UNKNOWN` với outcome `disabled` |
| `PDP_INVENTORY_BASE_URL` | none | Khi Inventory bật | Ví dụ `http://inventory-service:8080` |
| `PDP_REVIEW_ENABLED` | `true` | Không | Khi `false`, review section `UNAVAILABLE` với outcome `disabled` |
| `PDP_REVIEW_BASE_URL` | none | Khi Review bật | Ví dụ `http://review-service:8080` |
| `PDP_REVIEW_SERVICE_TOKEN` | none | Khi Review bật | Secret; phải nằm trong `REVIEW_INTERNAL_PDP_TOKENS` của Review. Dev dùng `dev-product-detail-bff-token` như docker-compose |
| `PDP_REVIEW_PREVIEW_LIMIT` | `3` | Không | 1–5; startup từ chối giá trị ngoài khoảng |
| `PDP_MEDIA_CDN_BASE_URL` | none | Có | URL tuyệt đối, không có dấu `/` cuối; URL media = base + `/` + storage key percent-encode từng segment. Phải bằng `CDN_BASE_URL` của Search để thẻ sản phẩm và PDP trỏ cùng file |
| `PDP_MEDIA_PLACEHOLDER_URL` | none | Có | URL tuyệt đối cho `thumbnail_url` khi SKU không có ảnh; bằng `THUMBNAIL_PLACEHOLDER_URL` của Search |
| `PDP_MEDIA_REQUIRE_HTTPS` | `true` | Không | Chỉ docker-compose local đặt `false`, vì MinIO được phát qua gateway bằng `http://localhost:8088/media`; mọi môi trường khác bắt buộc `https` |
| `PDP_FULL_RESPONSE_CACHE_CONTROL` | `no-store` | Không | `Cache-Control` của `200` đầy đủ (storefront cache policy, [data.md](./data.md#cache-boundary)); `200` degrade, `404` và `503` luôn `no-store` bất kể giá trị này |
| `TRACING_SAMPLING_PROBABILITY` | `0.1` | Không | Tỷ lệ lấy mẫu trace; `traceparent` vẫn được truyền và trace ID vẫn dùng làm request ID dự phòng khi trace không được lấy mẫu |
| `PDP_DEADLINE_MS`, timeout, circuit breaker, bulkhead | Theo [ADR-004](./decisions/ADR-004-resilience-budget-and-mesh-split.md)/[ADR-007](./decisions/ADR-007-hot-path-clarifications.md) | Không | Cấu hình được để load test hiệu chỉnh: `PDP_DEADLINE_MS` là biến riêng, còn timeout, bulkhead và circuit breaker ghi đè bằng Spring property `pdp.product.*`, `pdp.inventory.*`, `pdp.review.*`, `pdp.circuit-breaker.*` (relaxed binding, ví dụ `PDP_INVENTORY_BULKHEAD`). Đổi giá trị production phải cập nhật ADR và giữ quy tắc timeout BFF < mesh, deadline < gateway − 1 giây |

Cờ tắt dependency chỉ dùng cho rollout hoặc sự cố kéo dài đã có quyết định; không dùng để
né circuit breaker. Response khi cờ tắt vẫn là degrade nên vẫn `no-store`.

## Runbook

**Mọi SKU `UNKNOWN` nhưng không có lỗi Inventory:** kiểm tra outcome `disabled` và giá trị
`PDP_INVENTORY_ENABLED`; tương tự với Review và `PDP_REVIEW_ENABLED`.

**Inventory incident:** xác nhận BFF đang map lỗi sang `UNKNOWN` và response mang
`no-store`, xem kết quả call và trạng thái circuit Inventory, kiểm tra latency/`5xx` của
Availability Read API theo `X-Correlation-ID`, rồi chuyển cho Inventory on-call sửa read
model/cache. Không khôi phục bằng cách replay topic hoặc sửa database BFF vì BFF không có
projection đó. Không tắt circuit breaker để "ép" gọi Inventory.

**Review incident:** tương tự, với `UNAVAILABLE` thay cho `UNKNOWN`; chuyển Review on-call.
`401`/`403` từ Review có body `problem+json` là lỗi token: kiểm tra `PDP_REVIEW_SERVICE_TOKEN`
còn nằm trong `REVIEW_INTERNAL_PDP_TOKENS` (thường do xoay vòng sai thứ tự); `403`
`text/plain` là waypoint mesh chặn principal.

**Product incident:** PDP trả `503`. Kiểm tra circuit Product và kết quả call; `401`/`403`
là lỗi cấu hình identity/mesh (Storefront + platform), `5xx`/timeout chuyển Product
on-call.

**`bulkhead_rejected` hoặc latency tăng mà dependency khỏe:** BFF thiếu capacity; scale
BFF hoặc hiệu chỉnh giới hạn bulkhead theo công thức trên, không tăng timeout.
