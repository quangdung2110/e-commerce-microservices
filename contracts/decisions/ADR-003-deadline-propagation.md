# ADR-003: Truyền deadline giữa các service

- Status: Accepted
- Date: 2026-10-15

## Context

Ngân sách timeout đã được tính kỹ ở từng caller (ví dụ
[Checkout ADR-014](../../docs/checkout/decisions/ADR-014-resilience-budget-and-mesh-gateway-split.md),
[PDP BFF ADR-004](../../docs/product-detail-bff/decisions/ADR-004-resilience-budget-and-mesh-split.md)),
nhưng con số đó chỉ nằm trong caller. Khảo sát giao tiếp nội bộ (2026-10-15) cho thấy:

| Hiện trạng | Hệ quả |
|---|---|
| Checkout và PDP BFF tính `min(trần, deadline còn lại − dự phòng)` rồi chỉ dùng nó để hủy lời gọi. Callee không biết caller chờ bao lâu. | Callee tiếp tục làm việc đã vô nghĩa; không có số liệu về việc đó. |
| `ProductClient` của Checkout đặt deadline gRPC cố định bằng trần (`timeouts.product`), không phải phần còn lại. | Product thấy deadline dài hơn thực tế. |
| HTTP không có header deadline. Callee dùng Spring MVC (servlet) khó phát hiện client đã đóng kết nối, trong khi [gateway §5.1](../../docs/gateway/runtime-contract.md) đòi downstream "quan sát cancellation". | Không có cách nào để callee biết. |
| Order dùng một `read-timeout: 10s` cho mọi lời gọi. Mesh cũng đặt 10 giây cho Inventory reservations/refund-scopes và Promotion allocation, nên timeout của app bằng timeout của mesh thay vì nhỏ hơn. Hai nhóm route Order gọi (`GET /internal/inventory/v1/commands/*` và `GET` Payment) không có dòng nào trong [mesh §7](../../docs/mesh/runtime-contract.md). | Mesh có thể cắt trước app và trả lỗi không có problem body. |
| Order chờ `PAYMENT_READY` tối đa 12 giây dù Checkout còn bao nhiêu. | Khi Checkout còn ít thời gian, nó bỏ cuộc trong khi Order vẫn chờ. |

## Decision

### 1. gRPC dùng deadline gốc

Caller MUST đặt deadline của lời gọi bằng đúng timeout hiệu dụng của nó (giá trị cũng dùng để
hủy lời gọi), không dùng một trần cố định. grpc-java và grpc-go tự truyền deadline trong
`grpc-timeout`. Server SHOULD kiểm tra cancellation trong vòng lặp dài.

### 2. HTTP dùng header `X-Request-Timeout-Ms`

| | |
|---|---|
| Tên | `X-Request-Timeout-Ms` |
| Giá trị | Số nguyên thập phân, `1`–`600000`: số mili giây kể từ lúc caller gửi, sau đó caller thôi chờ |
| Là gì | Gợi ý cho callee, không phải cơ chế bảo mật. Giá trị thiếu, không phải số, hoặc ngoài khoảng thì bị bỏ qua và request vẫn được xử lý |
| Vì sao tương đối | Deadline tuyệt đối cần đồng hồ chung giữa các node; khoảng thời gian còn lại thì không |

### 3. Quy tắc cho bên gọi

1. Mọi lời gọi HTTP nội bộ có timeout MUST gửi `X-Request-Timeout-Ms` bằng timeout hiệu dụng của lời gọi
   đó (làm tròn xuống, tối thiểu 1). Đó là cùng giá trị đang dùng để hủy lời gọi.
2. Timeout hiệu dụng = `min(trần cấu hình, deadline còn lại của request đến − dự phòng)`. Request đến
   không có deadline thì chỉ còn trần cấu hình. Công việc không có request đến (Temporal activity,
   relay) dùng trần cấu hình.
3. Còn lại dưới ngưỡng tối thiểu (200 ms) thì không gọi và báo "hết ngân sách" đúng theo cách
   caller đã xử lý timeout, không coi là lỗi của callee.
4. Timeout hiệu dụng MUST nhỏ hơn timeout của mesh cho route đó ít nhất 1 giây
   ([mesh design](../../docs/mesh/design.md)); mọi route caller gọi MUST có dòng trong mesh §7.

### 4. Quy tắc cho bên nhận

1. Chỉ đọc header trên route service-to-service. Gateway MUST xoá header này khỏi request từ client
   ngoài (cùng danh sách với `X-Service-Identity`).
2. Ghi lại thời điểm nhận và giá trị. Khi gọi tiếp downstream trong cùng request, áp quy tắc 3.2 với
   deadline còn lại của request này; khi chờ điều kiện (ví dụ Order chờ `PAYMENT_READY`) không chờ quá
   deadline còn lại trừ 1 giây.
3. Nếu request kết thúc sau quá giá trị header, ghi một dòng WARN (`request_id`, `elapsed_ms`,
   `budget_ms`, route template) và tăng counter `http.server.caller_deadline_exceeded{route}`. Đây là
   số đo công việc làm thừa; nó không hủy công việc.
4. Bên nhận KHÔNG ĐƯỢC bỏ dở một thao tác ghi chỉ vì header. Hủy chủ động (đọc thuần, thao tác an toàn
   bỏ dở) là quyết định riêng của từng service và cần ADR riêng.
5. Deadline thật sự cưỡng chế được ở service Go (`context.WithTimeout` trên request) nên service Go
   được dùng cách đó.

### 5. Quan hệ với Gateway và mesh

Gateway giữ ngân sách [gateway §5.1](../../docs/gateway/runtime-contract.md); nó không gửi header này
và xoá bản do client gửi. Mesh giữ timeout ngoài và không đọc header.

## Alternatives considered

- **Deadline tuyệt đối (`X-Request-Deadline`, RFC 3339).** Không chọn: lệch đồng hồ giữa các node làm
  deadline sai; mili giây còn lại không có vấn đề này.
- **Định dạng `grpc-timeout` (`300m`) cho HTTP.** Không chọn: cần parser riêng; số nguyên mili giây
  khớp quy ước `_ms` của [data-types](../conventions/data-types.md).
- **Callee tự hủy khi header hết hạn.** Không chọn trong đợt này: một thao tác ghi bị bỏ dở có thể
  để lại trạng thái giữa chừng; cần đo mức làm thừa trước (quy tắc 4.3).
- **Chỉ dựa vào hủy kết nối.** Không chọn: servlet không báo hủy đáng tin cậy.
- **Truyền qua Temporal.** Không chọn: activity timeout đã do workflow giữ; vấn đề nằm ở hop HTTP.

## Consequences

- Callee biết caller chờ bao lâu và đo được công việc làm thừa; Order không chờ lâu hơn Checkout.
- Thêm một header phải giữ cùng định dạng ở mọi caller; kiểm bằng test từng caller.
- Header đến từ client ngoài phải bị Gateway xoá; thiếu bước này, người dùng cuối có thể ép Order
  fail-fast các lời gọi của chính request của họ (chỉ ảnh hưởng chính họ).
- Số `X-Request-Timeout-Ms` không phải SLO; không dùng nó để suy ra giới hạn của callee.

## Migration

| Service | Việc | Đợt này |
|---|---|---|
| Checkout, Product Detail BFF | Gửi header từ timeout hiệu dụng của `DependencyGuard`; `ProductClient` (gRPC) đặt deadline bằng timeout hiệu dụng | Có |
| Order | Gửi header; timeout riêng từng dependency; request thread không vượt deadline còn lại; không chờ `PAYMENT_READY` quá deadline ([Order ADR-023](../../docs/order/decisions/ADR-023-per-dependency-timeouts-and-inbound-deadline.md)) | Có |
| Cart | Đọc header trên `/internal`, bọc `context.WithTimeout` cho request | Có |
| Product, Inventory, Payment, Promotion, Review, Search | Đọc header, WARN và counter khi vượt ([Product ADR-021](../../docs/product/decisions/ADR-021-request-correlation-and-caller-deadline.md), [Inventory ADR-030](../../docs/inventory/decisions/ADR-030-request-correlation-and-caller-deadline.md), [Payment ADR-025](../../docs/payment/decisions/ADR-025-request-correlation-and-caller-deadline.md), [Promotion ADR-022](../../docs/promotion/decisions/ADR-022-request-correlation-and-caller-deadline.md), [Review ADR-014](../../docs/review/decisions/ADR-014-request-correlation-and-caller-deadline.md), [Search ADR-023](../../docs/search/decisions/ADR-023-request-correlation-and-caller-deadline.md)) | Có |
| Search, Promotion (bên gọi) | Search gửi `X-Request-Timeout-Ms` = `search.export.timeout`, hạ từ 30 giây xuống 25 giây để nhỏ hơn mesh 30 giây; client Product của Promotion gửi `promotion.product.timeout` (2 giây, mesh 3 giây) | Có |
| Gateway | Xoá `X-Request-Timeout-Ms` trong `strip-identity-headers` | Có |
| Mesh doc | Thêm dòng cho `GET /internal/inventory/v1/commands/*` và `GET` Payment | Có |
| User, Auth, Notification (Go) | Đọc header | Sau |
| Hủy chủ động công việc đọc thuần | ADR riêng sau khi có số đo | Sau |

## Verification

- Mỗi caller có test kiểm giá trị header gửi tới provider bằng đúng timeout hiệu dụng (kể cả khi bị kẹp
  bởi deadline còn lại) và không gửi khi không có timeout.
- Mỗi receiver có test: header hợp lệ được ghi nhận, header sai bị bỏ qua mà không lỗi, request vượt
  budget tăng counter.
- `ProductClient` của Checkout: test deadline gRPC bằng timeout hiệu dụng.
- Chạy `validate_docs.ps1`.
