---
doc_id: product-detail-bff-adr-008
service: product-detail-bff
status: accepted
date: 2026-10-09
decision_owners:
  - Storefront team
supersedes: null
amends:
  - ADR-004
  - ADR-005
evidence:
  - contracts/http/product-detail-bff/v1/openapi.yaml
  - docs/product-detail-bff/operations.md
  - java/services/product-detail-bff
  - note/audit-2026-10-07-docs-code-sync.md
---

# ADR-008 — Hiệu chỉnh sau audit: status ngoài contract, storage key, readiness, route lạ, request ID trong log

## Status

`Accepted`. Không supersede ADR nào. Làm rõ [ADR-004](./ADR-004-resilience-budget-and-mesh-split.md)
§3-4 (status nào tính vào circuit breaker) và bổ sung [ADR-005](./ADR-005-pdp-catalog-scope-and-media-url.md)
(ràng buộc của `storage_key` và giới hạn chuỗi). Mọi quy tắc nghiệp vụ, budget, ownership và
bảng degrade của các ADR đó giữ nguyên.

## Context

Báo cáo audit 2026-10-07 xếp Product Detail BFF vào nhóm "đồng bộ tốt nhất" (chỉ B1, B2).
Đọc lại toàn bộ tài liệu, ADR, OpenAPI, code, test và ba provider (Product, Inventory, Review)
ngày 2026-10-09 xác nhận B1, thấy B2 đã được sửa khi đồng bộ Product, và tìm thêm các điểm sau.
Điểm 1, 3 và 4 được chứng minh bằng một probe trên context Spring thật (readiness vẫn `200`
khi `REFUSING_TRAFFIC`, `404` của Spring cho đường dẫn lạ) và log test (dòng cảnh báo không có
request ID) trước khi sửa; điểm 2 và 5 bằng đọc `DependencyGuard`/`ProductPayload`:

1. **Readiness không phản ánh graceful shutdown.** `application.yml` khai báo
   `management.endpoint.health.group.readiness.include: ping`, thay thế `readinessState` mặc
   định của Spring Boot. Khi context phát `ReadinessState.REFUSING_TRAFFIC` (SIGTERM, với
   `server.shutdown=graceful`) `/actuator/health/readiness` vẫn trả `200 UP`, nên health check
   của Gateway/Kubernetes không bao giờ rút instance khỏi pool trước khi nó dừng.
2. **Status ngoài contract bị coi là "lỗi cấu hình".** `DependencyGuard` bỏ qua cho circuit
   breaker mọi status không phải `5xx`. ADR-004 chỉ miễn trừ `400`/`401`/`403` (và `404` của
   Product) và tính "response không đúng contract" là lỗi. Provider không hứa `429`, `408`,
   `409`, `3xx` hay `404` của Inventory/Review; một provider quá tải trả `429` sẽ không bao giờ
   mở circuit.
3. **`operations.md` hứa request ID nằm trong mọi dòng log của request, nhưng code không ghi
   nó vào log nào** (không MDC, dòng cảnh báo của `DependencyGuard` không mang giá trị này);
   không thể tra chéo với log Product/Inventory/Review.
4. **Đường dẫn dưới `/api/v1/storefront/products/` không phải một slug trả lỗi mặc định của
   framework.** Gateway chuyển cả prefix tới BFF, nên `…/products/`, `…/products/smart-watch/`
   (dấu `/` cuối) và `…/products/a/b` nhận `404` JSON của Spring (`timestamp`/`error`/
   `requestId`), không phải `application/problem+json`, không `no-store`, trái với OpenAPI
   ("slug không khớp pattern trả 404") và lập luận RFC 9111 của ADR-007.
5. **`storage_key` có segment `..` hoặc `.`** (Product chỉ kiểm không rỗng và ≤ 1024 ký tự; admin
   nhập tuỳ ý) được nối thẳng vào URL CDN: `https://cdn/media/../x` bị trình duyệt chuẩn hóa
   thoát khỏi thư mục base. `thumbnail_storage_key` không được kiểm tra gì.
6. **Giới hạn chuỗi của contract Product không được kiểm** (giá trị scalar 500 ký tự, nhãn 120,
   `alt_text` 255, slug 180 — code kiểm slug ở 255), trong khi tài liệu nói "sai giới hạn là sai
   contract"; response công khai có thể vượt giới hạn của chính OpenAPI PDP.
7. **Tài liệu và catalog còn trạng thái cũ hoặc thiếu:** 8 tài liệu `scope: target`/`status:
   proposed`; `x-adoption-status: proposed` của OpenAPI PDP; hai interaction `proposed`
   (`storefront-queries-product-detail`, `product-detail-bff-queries-product-catalog`);
   `ADOPTION.md` không có dòng cho PDP HTTP v1 và hai dòng provider (Product, Review) vẫn ghi
   BFF "chưa có consumer test"/"chưa chạy với fixture sinh từ contract" dù
   `ProviderFixturesContractTest` validate fixture của cả ba provider; `docs/README.md` §7
   ghi "(target)" và thiếu ba tài liệu; `development.md` ghi "chưa có Inventory service";
   bảng cấu hình thiếu `PDP_FULL_RESPONSE_CACHE_CONTROL` và `TRACING_SAMPLING_PROBABILITY`;
   "`contract_violation` mức item" bị dùng lẫn với outcome cấp lời gọi cùng tên.
8. **Bộ test chưa phủ các mục "Test bắt buộc" của `development.md`:** vòng đời circuit
   breaker và bulkhead chỉ có cho Review; không có test timeout Product/Inventory, "không
   retry ở ứng dụng", Product 500 SKU và `400`/`401`/`403` của Review.

## Decision

| # | Decision |
|---|---|
| 1 | Bỏ khai báo `group.liveness`/`group.readiness` khỏi `application.yml`; dùng nhóm mặc định (`livenessState`, `readinessState`). Readiness vẫn không phụ thuộc Inventory, Review hay circuit (các indicator đó không nằm trong nhóm) và về `OUT_OF_SERVICE` khi graceful shutdown. |
| 2 | Chỉ `400`/`401`/`403` là lỗi cấu hình (outcome `4xx`, alert, circuit breaker bỏ qua). Mọi status không phải `2xx` khác — `4xx` khác, `3xx`, `404` của Inventory/Review — là response ngoài contract: outcome `contract_violation`, tính vào circuit breaker, degrade như response sai contract của dependency đó. `404` của Product vẫn là kết quả hợp lệ. |
| 3 | `request_id` và `trace_id` được đặt vào Reactor context ở controller và gắn vào mọi dòng log BFF ghi trong phạm vi một request bằng structured argument (cảnh báo `4xx`/ngoài contract/sai contract/lỗi lạ của dependency; cảnh báo item Inventory thiếu hoặc thừa, mới). Không dùng MDC vì logger không đọc được Reactor context. Dòng log không đổi nghĩa metric: counter `pdp_inventory_item_violations_total` vẫn là "`contract_violation` mức item". |
| 4 | `GET /api/v1/storefront/products/**` không khớp `{slug}` trả cùng `404` problem `PRODUCT_DETAIL_NOT_FOUND`, `no-store`, và không gọi dependency. OpenAPI mô tả điều này ở tham số `slug` và response `404`. `405`/`406` ngoài phạm vi (Gateway chỉ chuyển `GET`). |
| 5 | `storage_key` và `thumbnail_storage_key` (khi khác `null`) không rỗng, ≤ 1024 ký tự và không có segment rỗng, `.` hoặc `..` sau khi bỏ dấu `/` đầu; vi phạm là response Product sai contract (`503`, tính vào circuit breaker như các ràng buộc tham chiếu của ADR-005). |
| 6 | BFF kiểm thêm giới hạn chuỗi của contract Product: giá trị scalar ≤ 500, `value_label` và nhãn option ≤ 120, `alt_text` ≤ 255, slug ≤ 180. Vi phạm là response Product sai contract. Field bắt buộc nhưng nullable vắng mặt được đọc như `null` (tolerant reader, ghi trong `development.md`). |
| 7 | Frontmatter 8 tài liệu: `scope: current`, `status: implemented`. `x-adoption-status: implemented` cho OpenAPI PDP; hai interaction thành `implemented`; `ADOPTION.md` có dòng PDP HTTP v1 và hai dòng provider ghi đúng BFF đã validate fixture; `docs/README.md` §7 đủ tài liệu, bỏ "(target)"; `development.md` ghi Inventory đã có nhưng cờ tắt; bảng cấu hình đủ biến. |
| 8 | Bổ sung test cho mọi mục chưa phủ ở Context 8 (xem Verification). |

## Alternatives considered

- **Giữ "mọi `4xx` bị bỏ qua" và sửa tài liệu.** Ít code hơn nhưng làm circuit breaker vô hiệu
  với `429`/`408` (provider quá tải) và đi ngược ADR-004.
- **Bật `Hooks.enableAutomaticContextPropagation()` và MDC cho request ID.** Phủ cả log của
  thư viện nhưng thêm cơ chế toàn cục khó kiểm chứng với BlockHound và event loop; BFF chỉ có
  vài dòng log trong phạm vi request nên structured argument tường minh là đủ.
- **Để `storage_key` cho Product kiểm.** Đúng ownership, nhưng Product chưa chặn và BFF là nơi
  nối URL; kiểm ở BFF là lớp phòng thủ rẻ, không đổi contract Product. Product có thể siết sau
  mà không ảnh hưởng BFF.
- **Trả `400` cho đường dẫn không phải slug.** Contract PDP không có `400`; slug sai luôn là
  `404`.

## Consequences

Provider trả status lạ làm PDP degrade như trước nhưng đếm vào circuit breaker; `429` kéo dài
sẽ mở circuit (đúng ý ADR-004). Một Product có `storage_key` chứa `..` làm PDP của Product đó
trả `503` thay vì phát URL thoát thư mục: lỗi hiện ra sớm ở dữ liệu admin thay vì ở CDN.
Các dòng log cảnh báo dài thêm hai field. Không đổi API công khai ngoài mô tả `404`, không đổi
metric, budget hay cấu hình bắt buộc.

## Verification

- `EdgeBehaviourTest`: readiness `UP` khi cả ba circuit mở, `503 OUT_OF_SERVICE` khi
  `REFUSING_TRAFFIC`; bốn dạng đường dẫn không phải slug nhận `404` problem `no-store`,
  validate với schema `Problem` và không có request nào tới dependency; log cảnh báo chứa
  `request_id` đúng giá trị `X-Request-Id` gửi vào và `trace_id` 32 hex.
- `DependencyStatusTest`: `429`/`404`/`302` từ Inventory, `429`/`404` từ Review và
  `429`/`302` từ Product tính vào circuit breaker và ghi outcome `contract_violation`;
  `400`/`401`/`403` thì không; mỗi dependency chỉ nhận một request khi trả `5xx`; Product và
  Inventory chậm hơn timeout; Product 500 SKU phát đúng một Inventory batch 500 `sku_id`.
- `DependencyIsolationTest`, `ProductBulkheadTest`: circuit Product và Inventory mở,
  half-open rồi đóng độc lập; bulkhead Product và Inventory từ chối ngay, không tính vào circuit.
- `ProductPayloadTest`: `..`, `.`, segment rỗng trong media/thumbnail key bị từ chối, key bình
  thường (khoảng trắng, dấu chấm trong tên file, `/` đầu) hợp lệ; chuỗi vượt giới hạn bị từ chối.
