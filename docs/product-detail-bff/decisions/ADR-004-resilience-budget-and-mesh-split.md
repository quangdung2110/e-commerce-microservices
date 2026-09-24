---
doc_id: product-detail-bff-adr-004
service: product-detail-bff
status: accepted
date: 2026-09-24
decision_owners:
  - Storefront team
  - Product Service team
  - Inventory Service team
  - Review Service team
supersedes: null
amends: null
evidence:
  - docs/product-detail-bff/operations.md
  - docs/mesh/runtime-contract.md
  - docs/gateway/runtime-contract.md
  - contracts/http/product-detail-bff/v1/openapi.yaml
---

# ADR-004 — Resilience budget của PDP và phân chia trách nhiệm với mesh/gateway

## Status

`Accepted`. Không supersede hay amend ADR nào. Các con số là giá trị khởi điểm: chúng là
release gate phải được load test xác nhận trước khi public PDP, không phải mô tả
implementation hiện tại.

## Context

ADR-001 và ADR-002 yêu cầu BFF có timeout, bulkhead và circuit breaker riêng cho từng
dependency, nhưng chưa chốt con số, thứ tự cắt timeout giữa BFF/mesh/gateway, lỗi nào
tính vào circuit breaker hay response degrade có được cache hay không. Mesh đã có policy
cho ba route BFF gọi (Product 3 giây, Inventory/Review 2 giây,
[mesh/runtime-contract.md §7](../../mesh/runtime-contract.md#7-traffic-policy-east-west)).
Nếu chỉ dựa vào mesh, PDP chạy tuần tự Product rồi song song Inventory/Review có thể chờ
tới 5 giây trước khi degrade, và mesh không biết dependency nào được phép degrade.

## Decision

### 1. Phân chia trách nhiệm

| Tầng | Sở hữu | Không sở hữu |
|---|---|---|
| Gateway (Traefik) | Response-header timeout 3 giây cho route PDP; chỉ retry lỗi kết nối tới BFF; rate profile `public-read` | Retry `5xx`/`504` của BFF, quyết định degrade |
| Mesh | mTLS, authorization theo principal, retry lỗi kết nối (`connect-failure`, `refused-stream`) cho Product và Review, timeout ngoài, outlier detection, `connectionPool` bảo vệ provider | Budget của PDP, fallback, circuit breaker theo dependency, retry sau khi đã nhận response |
| BFF | Deadline PDP, timeout từng call, bulkhead, circuit breaker, phân loại lỗi, map degrade, `Cache-Control`, hủy call khi client ngắt | Pod health, load balancing, mTLS |

Nguyên tắc: timeout ở BFF luôn ngắn hơn timeout mesh của cùng route; deadline BFF nhỏ
hơn timeout gateway ít nhất 1 giây (quy tắc chung của
[gateway/runtime-contract.md §5.1](../../gateway/runtime-contract.md#51-ngân-sách-timeout)).
Retry ở ứng dụng bằng 0 cho cả ba dependency; retry duy nhất trong hot path là retry lỗi
kết nối ở mesh (Product, Review) và gateway (kết nối tới BFF).

### 2. Budget và timeout

| Thành phần | BFF | Mesh | Retry mesh |
|---|---:|---:|---|
| Deadline toàn PDP, tính từ lúc BFF nhận request | 1 000 ms | — | — |
| Product buyer-read (pha 1, tuần tự) | 400 ms | 3 giây | 2 lần, `connect-failure,refused-stream` |
| Inventory batch-read (pha 2, song song) | `min(300 ms, deadline còn lại − 50 ms)` | 2 giây | 0 (POST) |
| Review PDP summary (pha 2, song song) | `min(300 ms, deadline còn lại − 50 ms)` | 2 giây | 2 lần, `connect-failure,refused-stream` |
| Dự phòng compose/serialize | 50 ms | — | — |
| Gateway response-header timeout cho route PDP | — | 3 giây (Traefik) | Chỉ lỗi kết nối |

Nếu sau pha 1 deadline còn lại dưới 100 ms, BFF không gọi Inventory và Review: mọi SKU là
`UNKNOWN`, `review_section.status = UNAVAILABLE`. Deadline được truyền bằng request
context; khi client hoặc gateway hủy request, BFF hủy mọi call đang chạy.

Để các timeout trên có nghĩa, provider cần đạt latency kỳ vọng: Product buyer-read
p99 ≤ 250 ms, Inventory batch-read (≤ 500 SKU) p99 ≤ 200 ms, Review PDP summary
p99 ≤ 200 ms. Các con số này được ghi lại trong tài liệu vận hành của từng provider.

### 3. Circuit breaker và bulkhead

Mỗi dependency có một circuit breaker riêng trong mỗi pod BFF:

- Cửa sổ trượt theo thời gian 10 giây, tối thiểu 20 call trước khi đánh giá.
- Mở khi tỷ lệ lỗi ≥ 50 % **hoặc** tỷ lệ call chậm ≥ 50 %. Call chậm là call vượt 80 %
  timeout ứng dụng của dependency (Product 320 ms, Inventory/Review 240 ms).
- Mở 10 giây, sau đó half-open cho 5 call thử; các request khác vẫn fail fast.
- **Tính là lỗi:** timeout ứng dụng, lỗi kết nối/reset, mọi `5xx` (kể cả `503` do mesh
  trả khi hết endpoint khỏe hoặc `connectionPool` đầy, `504` từ mesh), response không đúng
  contract.
- **Không tính là lỗi:** `404` từ Product (kết quả hợp lệ), `400`/`401`/`403` (lỗi cấu
  hình hoặc authorization, alert riêng), bulkhead từ chối (lỗi phía BFF), call không được
  gọi vì hết budget, call bị hủy vì client ngắt.

Bulkhead là semaphore theo dependency, không có hàng chờ (thời gian chờ tối đa bằng 0).
Giới hạn khởi điểm tính theo `ceil(RPS mục tiêu mỗi pod × p99 kỳ vọng của dependency × 2)`;
với 200 RPS/pod: Product 100, Inventory 80, Review 80. Connection pool của HTTP client
không nhỏ hơn giới hạn bulkhead, để bulkhead là giới hạn đồng thời duy nhất ở phía BFF.
`connectionPool` của mesh là giới hạn khác: nó bảo vệ provider trước mọi caller.

`Retry-After` trong `503` của Inventory không làm BFF chờ hay retry trong hot path; trạng
thái circuit breaker là cơ chế duy nhất để ngừng gọi.

### 4. Phân loại kết quả

| Kết quả của call | Product | Inventory | Review |
|---|---|---|---|
| Timeout, lỗi kết nối, `5xx`, response sai contract | `503` PDP | Mọi SKU `UNKNOWN` | `UNAVAILABLE` |
| `400`/`401`/`403` | `503` PDP + alert cấu hình | Mọi SKU `UNKNOWN` + alert cấu hình | `UNAVAILABLE` + alert cấu hình |
| Circuit open | `503` PDP kèm `Retry-After` | Không gọi; mọi SKU `UNKNOWN` | Không gọi; `UNAVAILABLE` |
| Bulkhead đầy | `503` PDP | Mọi SKU `UNKNOWN` | `UNAVAILABLE` |
| Hết budget sau pha 1 | — | Không gọi; mọi SKU `UNKNOWN` | Không gọi; `UNAVAILABLE` |
| Item thiếu hoặc item `UNKNOWN` | — | Chỉ SKU đó `UNKNOWN` | — |

Response Inventory sai contract gồm body không parse được, vi phạm schema hoặc trùng
`sku_id`; khi đó cả batch bị bỏ. Item cho SKU không được yêu cầu bị bỏ qua và không tính
là lỗi. Slug không khớp pattern của contract trả `404` mà không gọi Product.

### 5. Cache của response degrade

Response `200` có ít nhất một SKU `UNKNOWN` hoặc `review_section.status = UNAVAILABLE`
luôn mang `Cache-Control: no-store`, để CDN không kéo dài sự cố sau khi dependency đã phục
hồi. Response đầy đủ theo cache policy của storefront nếu cache được bật, mặc định
`no-store`. `503` luôn `no-store`.

## Alternatives considered

- **Chỉ dùng timeout/retry/outlier detection của mesh.** Mesh không biết Inventory và
  Review được degrade còn Product thì không, không có fallback và không cắt theo budget
  còn lại; worst case 5 giây trước khi degrade.
- **Retry ở ứng dụng cho dependency degradable.** Tăng tail latency và nhân tải khi
  provider đang yếu; degrade sớm có giá trị hơn một lần thử lại.
- **Một circuit breaker chung cho mọi dependency.** Review lỗi sẽ chặn cả Product; mất
  isolation mà ADR-002 yêu cầu.
- **Cho phép cache response degrade với TTL ngắn.** Vẫn giữ `UNKNOWN`/`UNAVAILABLE` sau
  khi dependency phục hồi; lợi ích tải nhỏ so với rủi ro hiển thị sai kéo dài.

## Consequences

BFF phải cấu hình và test đủ ba nhóm policy cho ba dependency. Provider nhận latency kỳ
vọng rõ ràng. Gateway matrix có dòng riêng cho route PDP. Trang có `UNKNOWN` không được
CDN cache, nên tải về BFF tăng khi Inventory trả `UNKNOWN` nhiều; tỷ lệ này đã được theo
dõi như chỉ số sức khỏe của Inventory.

## Verification

- Test: Product chậm làm cạn budget dẫn tới bỏ pha 2; timeout ứng dụng kích hoạt trước
  timeout mesh; circuit open, half-open rồi đóng; bulkhead từ chối; `4xx` từ từng
  dependency; response sai contract; client hủy request thì call con bị hủy.
- Load test xác nhận latency kỳ vọng của provider, giới hạn bulkhead và SLO PDP trước khi
  public availability.
- Kiểm tra `Cache-Control: no-store` trên mọi response degrade và `503`.
