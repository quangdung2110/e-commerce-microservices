# Load test và fault injection

> **Trạng thái:** có kết quả đầu tiên (2026-10-16) trên stack Docker Compose local cho các route đọc công khai. Chưa có số liệu cho đường ghi
> (checkout, order, payment) hay cho cluster Kubernetes. Quyết định và phạm vi: [contracts ADR-010](../../contracts/decisions/ADR-010-resilience-evidence-fault-injection-and-load-tests.md).

Mọi ADR resilience của dự án ghi con số khởi điểm "phải được xác nhận bằng load test". Tài liệu này ghi cách đo, số đo thực tế, và những gì số đo **không** chứng minh.

Order [ADR-027](../order/decisions/ADR-027-async-submission-checkpoint-waiters.md) thay submission poll
100 ms/request bằng `DeferredResult`, commit notification và shared batch recovery 1 s. Functional tests
kiểm tra commit/rollback, hai replica listener, deadline và recovery; chưa có benchmark write-path trước/sau.
Không suy ra RPS hoặc mức tăng throughput từ việc bỏ request thread chờ. Khi benchmark, đo đồng thời
Order API latency, waiter/executor queue/rejections, Hikari pending, query rate, CPU DB và saga latency;
thử provider chậm và listener mất kết nối trên môi trường test riêng.
Kết quả kiểm chứng chức năng: [Order async submission verification 2026-10-16](../order/async-submission-verification-2026-10-16.md)
(97 Order test pass; 164 Checkout test, 0 failure/error và 1 live test skip).

[Inventory Reserve trên một SKU nóng: baseline](./inventory-reserve-hot-sku-baseline-2026-10-19.md) (2026-10-19, chưa sửa code): stack tách biệt có giới hạn CPU, lệnh
ReserveOrder theo tải mở. Một SKU nóng đạt SLO tới **250/s** và tối đa khoảng **330 RESERVED/s**; cùng service chia ra 2 000 SKU đạt SLO tới **600/s**. Khi
bão hoà, 22–23 trên 24 connection chờ khoá `stock_position` trong khi CPU còn dư một nửa. Trộn 50/50 ở 600/s làm đơn SKU nguội cũng có p50 3,9 s. Không có
`INVENTORY_BUSY` nào ở mọi bậc, nên alert theo tỉ lệ busy không phát hiện được; [Inventory ADR-032](../inventory/decisions/ADR-032-reserve-saturation-alerts.md) thêm alert latency
Reserve và alert pool. Mức sàn `UPDATE`+`COMMIT` một hàng là khoảng 700–750/s.

[Inventory flash sale trên một SKU: baseline Reserve + Commit](./inventory-flash-sale-baseline-2026-10-19.md) (2026-10-19, chưa sửa code): mỗi đơn khoá hàng nóng hai lần
(Reserve rồi Commit/Release). Với 64 request đồng thời như bulkhead của Order, flash sale đạt SLO tới **100 đơn/s**, trần khoảng **150–167 đơn/s**, và vượt
trần không sụp. Không giới hạn đồng thời thì thông lượng tụt dần (16 đơn/s ở 300 đơn/s đưa vào). Báo cáo này đính chính baseline trên: trần chỉ Reserve là
khoảng 285–290/s, không phải 330/s.

## Cách chạy

```bash
scripts/ci/loadtest.sh                                   # 20 VU, 60 giây, qua gateway (api-gateway:80)
VUS=60 DURATION=45s PDP_URL=http://product-detail-bff:8080 SEARCH_URL=http://search-service:8080 scripts/ci/loadtest.sh   # đo thẳng service
```

`loadtest/k6/storefront-reads.js` chạy trong container k6 gắn vào mạng compose, hoàn toàn chỉ đọc và ẩn danh (không cần tài khoản, không ghi dữ liệu): 60% chi tiết sản phẩm
(`GET /api/v1/storefront/products/{slug}`, BFF ghép Product, Inventory, Review dưới budget từng dependency), 30% tìm kiếm, 10% cây danh mục. Slug được lấy từ Search lúc `setup`. Ngưỡng trong
script là các budget mà thiết kế nêu (p95 < 800 ms, p99 < 1,5 s cho chi tiết và tìm kiếm; không `5xx` nào ngoài `503`), không phải mục tiêu mong muốn.

## Môi trường đo

| | |
|---|---|
| Máy | Windows 11, Docker Desktop (WSL2), 16 CPU, 15 GB cho Docker |
| Stack | Docker Compose, một replica mỗi service, ~28 container trên cùng máy với bộ sinh tải |
| Dữ liệu | Catalog dev nhỏ (vài sản phẩm QA và mẫu), Elasticsearch một node |
| Image | PDP, Product, Search, Review, Inventory build 2026-10-15; Auth, User, Notification cũ hơn code (không nằm trên đường đo) |
| Mạng | k6 trong mạng compose; gateway là Traefik (có rate limit) |

## Kết quả

| Lần chạy | Tải | Chi tiết (ms) p50 / p95 / p99 | Tìm kiếm (ms) p50 / p95 / p99 | Danh mục (ms) p50 / p95 / p99 | Lỗi |
|---|---|---|---|---|---|
| A. Qua gateway | 20 VU × 60 giây, 149 req/s | 21 / 109 / 220 | 12 / 76 / 244 | 7 / 46 / 126 | 19 `503` PDP trên 5 458 (0,34%), không `5xx` khác |
| B. Qua gateway | 60 VU × 45 giây, ~550 req/s đưa vào | — | — | — | 63% `429` từ gateway (~290 req/s), ~227 req/s trả `200`, **0** `5xx` |
| C. Thẳng service | 60 VU × 45 giây, 513 req/s | 11 / 48 / 130 | 6 / 37 / 150 | 4 / 24 / 132 | 1 `503` PDP trên 23 217 |
| D. Máy bận (Kafka + ZooKeeper dùng ~350% CPU) | 2 VU × 15 giây | p99 5,5 s | — | — | 26% `503` PDP (Product vượt budget 400 ms) |

Đọc kết quả:

- **Dưới giới hạn của gateway, không có lỗi do dịch vụ.** Lần C cho thấy PDP và Search chịu ~510 req/s trên máy này với p99 dưới 160 ms; `5xx` bằng 0.
- **Rate limit của gateway là trần của một client.** Lần B: Traefik trả `429` quanh 220–230 req/s mỗi địa chỉ nguồn. Mọi `429` của route chi tiết đều có `Retry-After` (2 394 trên 2 394),
  đúng yêu cầu của [contracts ADR-005](../../contracts/decisions/ADR-005-retry-after-on-throttling-and-unavailable.md). Muốn đo năng lực service thì bỏ qua gateway như lần C.
- **Budget hoạt động như thiết kế.** Lần D: khi máy quá tải, Product trả chậm hơn 400 ms và PDP chuyển sang `503` có kiểm soát (không treo, không lan sang Search); khi Kafka hạ tải, lần chạy kế
  tiếp sạch lỗi. Điều này chứng minh cơ chế, không chứng minh dung lượng.
- **Lần chạy đầu tiên tìm ra một lỗi** (lần A): 19 `503` của PDP không có `Retry-After` (0 trên 19), dù contract khai báo và [ADR-005](../../contracts/decisions/ADR-005-retry-after-on-throttling-and-unavailable.md)
  bắt buộc. PDP chỉ gửi header khi circuit breaker mở. Đã sửa ([PDP ADR-010](../product-detail-bff/decisions/ADR-010-retry-after-on-every-503.md)); image đang chạy chưa có bản sửa nên lần C vẫn còn 1 `503`
  không header.
- **Khởi động nguội nhạy với tải.** Một lần restart Product khi máy bận: warm-up mất 17,5 giây (lần khác 2,7 giây) và một lần trước đó vượt giới hạn 30 giây nên container thoát với mã 1. Giới hạn
  `product.warmup.timeout` cần dư cho máy bận; hiện chỉ ghi nhận, chưa đổi.

## Không chứng minh được

- **Dung lượng production.** Một máy, một replica, bộ sinh tải cùng máy, catalog nhỏ. Các con số là bằng chứng tương đối và phát hiện lỗi, không phải kế hoạch năng lực.
- **Đường ghi.** Checkout, Order, Payment cần tài khoản, Stripe và dữ liệu; chưa có kịch bản k6 end-to-end. Riêng Inventory Reserve đã đo cô lập
  (gọi thẳng như Order, không có Temporal phía trước) trong [baseline SKU nóng](./inventory-reserve-hot-sku-baseline-2026-10-19.md).
- **Chaos trên stack đang chạy** (dừng container, ngắt mạng). Stack local dùng chung với người đang phát triển nên không thực hiện; thay bằng fault injection ở mức socket trong test (dưới đây).
- **Overhead của mesh** (HBONE, waypoint): chưa có cluster ([mesh runtime contract §10](../mesh/runtime-contract.md)).

## Fault injection ở mức socket

`FaultServer` (test của Order và Search) là server loopback biết làm hỏng đúng những cách provider thật hỏng: không trả lời, reset kết nối, gửi byte không phải HTTP, cắt body giữa chừng, nhỏ giọt body, trả `503`/`429`.

| Client | Test | Kết quả |
|---|---|---|
| Order `HttpRemote` (Inventory, Promotion, Payment) | `HttpRemoteFaultInjectionTest`, 13 test | Mọi lỗi là `UNKNOWN`, trong vòng `read-timeout`; bulkhead nhả permit và hồi phục ngay; **tìm ra lỗi** 2xx với body cụt/hỏng bị coi là `ACCEPTED` với `body == null` rồi gây NPE trong saga ([Order ADR-025](../order/decisions/ADR-025-unreadable-success-body-is-unknown.md)) |
| Search `SnapshotExportClient` (Product, Inventory, Review) | `SnapshotExportClientFaultInjectionTest`, 10 test | Mọi lỗi là exception (không có trang rỗng hay cụt âm thầm) trong vòng timeout quảng bá; metric `search.outbound.http` đếm `io_error` và `server_error`; hồi phục ngay |

Client khác (Checkout, PDP BFF) có test breaker và bulkhead riêng; khi sửa chúng, thêm một `FaultServer` theo cùng mẫu.
