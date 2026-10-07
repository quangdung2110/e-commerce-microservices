---
doc_id: product-detail-bff-adr-007
service: product-detail-bff
status: accepted
date: 2026-09-25
decision_owners:
  - Storefront team
  - Inventory Service team
  - Review Service team
supersedes: null
amends:
  - ADR-004
evidence:
  - contracts/http/product-detail-bff/v1/openapi.yaml
  - contracts/http/inventory/read/v1/openapi.yaml
  - contracts/http/review/internal/v1/openapi.yaml
  - docs/product-detail-bff/operations.md
---

# ADR-007 — Làm rõ hot path: budget Product, request ID, cache 404, item Inventory, cờ tắt

## Status

`Accepted`. Amend [ADR-004](./ADR-004-resilience-budget-and-mesh-split.md) ở timeout Product
và cách kiểm chứng việc bỏ pha 2; các con số khác của ADR-004 giữ nguyên.

## Context

Rà soát trước khi implement phát hiện các điểm mà tài liệu và contract hiện hành để ngỏ
hoặc mâu thuẫn:

1. Product bị cắt cố định ở 400 ms trong deadline 1 000 ms, nên sau pha 1 luôn còn ít nhất
   khoảng 600 ms. Quy tắc "còn dưới 100 ms thì bỏ pha 2" không bao giờ xảy ra do Product
   chậm, và test "Product chậm làm cạn budget" của ADR-004 không dựng được.
2. BFF chuyển thẳng `X-Request-Id` do client gửi tới Review (`X-Request-Id`) và Inventory
   (`X-Correlation-ID`), cả hai yêu cầu dài 8–128. Client ẩn danh gửi giá trị ngắn là khiến
   được provider trả `400`, PDP degrade và alert cấu hình bắn.
3. Contract không nói `Cache-Control` của `404`. Theo RFC 9111, `404` không có header vẫn
   được cache heuristic, nên CDN có thể giữ "không tìm thấy" sau khi Product được activate.
4. Inventory cam kết đúng một item cho mỗi SKU, nhưng BFF coi item thiếu là `UNKNOWN` bình
   thường và bỏ qua item thừa mà không ghi nhận, nên vi phạm contract của Inventory bị che.
5. Inventory chưa tồn tại. README yêu cầu mọi SKU là `UNKNOWN` trước rollout gate nhưng
   không nói cơ chế; nếu cứ gọi rồi lỗi, SLO degrade hỏng 100 % và circuit breaker mở liên
   tục. Review đòi `Authorization: Bearer` nhưng tài liệu BFF không nhắc, và `preview_limit`
   chưa được chốt.

## Decision

1. **Timeout Product** = `min(400 ms, deadline còn lại − 50 ms)`. Trước mỗi pha, nếu deadline
   còn dưới 100 ms thì không gọi dependency của pha đó: trước pha Product thì trả `503`
   (`budget_skipped`, không `Retry-After`, không tính vào circuit breaker); trước pha 2 thì
   Inventory/Review degrade như ADR-004. Hai quy tắc là lớp phòng thủ khi request đã tốn thời
   gian trước pha Product; test bằng cách chèn độ trễ trước pha Product, không bằng Product chậm.
2. **Request ID đã chuẩn hóa:** chỉ dùng `X-Request-Id` đầu vào khi khớp
   `^[A-Za-z0-9._:-]{8,128}$`, ngược lại dùng trace ID (32 hex) của span hiện tại. Giá trị
   này đi qua `X-Request-Id` tới Product/Review, `X-Correlation-ID` tới Inventory và vào log.
   Giá trị sai không làm request lỗi.
3. **`404` luôn `Cache-Control: no-store`**, cùng với mọi response degrade và `503`.
4. **Item Inventory thiếu hoặc thừa:** SKU thiếu là `UNKNOWN`, item thừa bị bỏ, các SKU khác
   giữ kết quả; BFF ghi `contract_violation` mức item và alert, không tính vào circuit
   breaker. Trùng `sku_id` vẫn bỏ cả batch như cũ.
5. **Cờ tắt dependency:** `PDP_INVENTORY_ENABLED` (mặc định `false` tới rollout gate) và
   `PDP_REVIEW_ENABLED` (mặc định `true`). Khi tắt, BFF không gọi dependency, map
   `UNKNOWN`/`UNAVAILABLE` với outcome `disabled`, không tính SLO degrade, không alert;
   response vẫn `no-store`.
6. **Review:** BFF gửi `Authorization: Bearer` lấy từ `PDP_REVIEW_SERVICE_TOKEN` (secret
   manager, phải nằm trong `REVIEW_INTERNAL_PDP_TOKENS`); thiếu token khi Review bật thì BFF
   không ready. `preview_limit` = `PDP_REVIEW_PREVIEW_LIMIT`, mặc định 3, tối đa 5.

## Alternatives considered

- **Giữ Product 400 ms cố định và bỏ quy tắc 100 ms.** Đơn giản hơn nhưng mất lớp phòng thủ
  khi pod quá tải làm request tốn thời gian trước pha 1.
- **Trả `400` khi `X-Request-Id` sai.** Biến một header chỉ để tra log thành lý do từ chối
  buyer; gateway cũng không đảm bảo sinh header này.
- **Cache `404` với `max-age` ngắn.** Giảm tải cho slug sai nhưng vẫn giữ trạng thái sai
  trong khoảng TTL; slug sai đã bị chặn rẻ bằng pattern ở BFF.
- **Coi item Inventory thiếu là sai contract cả batch.** Một item lỗi làm mất availability
  của mọi SKU, trái với nguyên tắc degrade theo SKU.
- **Không có cờ, để circuit breaker xử lý khi Inventory chưa có.** Làm SLO và alert vô nghĩa
  suốt giai đoạn trước rollout.

## Consequences

Bảng timeout ở operations.md, mesh §7 và tài liệu vận hành Product ghi Product là "tối đa
400 ms". Có thêm outcome `disabled` và metric vi phạm mức item. Cấu hình BFF có thêm một
secret (Review token) phải xoay vòng theo thứ tự: thêm vào Review, roll BFF, gỡ token cũ.

## Verification

- Chèn độ trễ trước pha Product để deadline còn dưới 100 ms thì trả `503` không gọi Product;
  chèn độ trễ để sau pha 1 còn dưới 100 ms thì bỏ pha 2.
- `X-Request-Id` ngắn, dài hoặc có ký tự lạ bị thay bằng trace ID; Inventory/Review không
  nhận giá trị sai.
- `404` (cả do Product và do slug sai pattern) có `Cache-Control: no-store`.
- Inventory thiếu một item và trả item thừa: SKU khác giữ kết quả, metric vi phạm tăng.
- Cờ tắt: không có request tới dependency, outcome `disabled`, SLO degrade không đổi.
- Thiếu `PDP_REVIEW_SERVICE_TOKEN` khi Review bật thì không ready; token đúng thì Review
  trả `200`.
