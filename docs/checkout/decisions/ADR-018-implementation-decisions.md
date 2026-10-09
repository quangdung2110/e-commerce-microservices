---
doc_id: checkout-adr-018
service: checkout
status: accepted
date: 2026-10-07
decision_owners:
  - Checkout Service team
supersedes: null
evidence:
  - java/services/checkout-service
  - docs/checkout/data.md
  - docs/checkout/interfaces.md
  - docker-compose.yml
---

# ADR-018 — Quyết định khi triển khai: DTO theo contract, Redis riêng, parse request nghiêm ngặt, owner của attempt

## Status

`Accepted` 2026-10-07. Bổ sung ADR-011, ADR-013, ADR-015; ghi chú có ngày được thêm vào ADR-008
và ADR-013, không đổi nội dung đã accepted.

## Context

Khi viết code theo tài liệu, một số điểm chưa chốt hoặc mâu thuẫn với hiện trạng repository:

1. [ADR-013](./ADR-013-java-spring-webflux-runtime.md) nói "server stub và client model sinh từ
   OpenAPI". Product Detail BFF cùng stack lại dùng record viết tay theo tolerant reader và kiểm
   bằng schema test; model sinh ra thì strict (`additionalProperties: false`) và đi ngược nguyên tắc
   "provider thêm field không làm Checkout lỗi".
2. Tài liệu bắt buộc Redis `noeviction`, nhưng `docker-compose.yml` chạy một Redis dùng chung với
   `allkeys-lru`; `maxmemory-policy` là cấu hình cả server nên "database index riêng" không giải
   quyết được. Một session `SUBMITTING` bị evict có thể dẫn tới đơn trùng.
3. [ADR-008](./ADR-008-single-payment-provider-stripe.md) nói field thừa như `payment_method` bị
   bỏ qua; OpenAPI (`additionalProperties: false`) và ADR-015 nói field lạ là `400`.
4. Order trả `503 SUBMISSION_IN_PROGRESS` (retryable) khi hết 12 giây chờ mà vẫn `PREPARING`; bảng
   ánh xạ chỉ có "POST `503` → `RESULT_UNKNOWN`", trong khi Order đã nhận đơn.
5. "`SUBMITTING` quá owner timeout" mơ hồ sau một lần recovery claim: tính từ `claimed_at` thì một
   attempt vừa được nhận lại bị coi là chết ngay.
6. Chưa định nghĩa fingerprint idempotency, định dạng khoá, giá trị `Retry-After`, topology Redis.
7. Ba provider chưa đáp ứng contract: Cart chưa có `checkout-snapshot`; Product không trả
   `category_ids`; Promotion bắt buộc `user_tier` ở calculate/Reserve trong khi Checkout và Order
   không có tier authority, và chưa có `promotion-voucher-options`.

## Decision

- **DTO.** Stub gRPC Product sinh từ proto (bản sao trong `proto/` của module, có test chống lệch
  với `contracts/`). DTO HTTP là record viết tay theo tolerant reader, được kiểm với OpenAPI/proto
  của provider bằng contract test; response của Checkout và mọi fixture provider được validate bằng
  JSON Schema của OpenAPI. Mọi vi phạm ràng buộc Checkout kiểm tra là `contract_violation` (`503`).
- **Redis riêng.** Checkout dùng `checkout-redis` (`noeviction`, AOF `everysec`), standalone hoặc
  Sentinel, không Cluster (script chạm nhiều key không cùng hash tag). Một kết nối Lettuce multiplex;
  không pool.
- **Parse nghiêm ngặt.** Body từ client bị từ chối với `400 INVALID_REQUEST` nếu có field lạ hoặc sai
  kiểu (không ép số thành chuỗi). Ghi chú ADR-008 trỏ về đây.
- **Order `503 SUBMISSION_IN_PROGRESS`** → token `RETRYABLE`, public `202 PREPARING`. Các failure
  `CHECKOUT_PAYMENT_WINDOW_EXHAUSTED` và `ORDER_CANCELLED_DURING_PREPARATION` được relay cùng tên ở
  cả POST lẫn GET, vì Order lưu một failure và trả cùng một problem ở hai đường.
- **Owner của attempt.** Thêm `attempt_started_at`; owner timeout tính từ nó. Recovery claim dùng CAS
  trên `attempt_id` đã quan sát; mọi transition không-terminal của một attempt (`mark`,
  `to_retryable`) bị chặn nếu attempt đó không còn là owner. Terminal là first-writer-wins, vì kết
  quả do Order hoặc chính attempt đã claim quyết định.
- **Idempotency Preview.** Fingerprint là SHA-256 của JSON chuẩn hoá `{delivery_address_id,
  previous_checkout_token, promotion_id}`; chỉ response thành công được lưu; hai request đồng thời
  cùng key được phân xử trong script Wave 4.
- **Khoá.** `v1:<base64>,v2:<base64>`; ref = `{version}:{hex HMAC}` với tiền tố mục đích; envelope
  AES-256-GCM với DEK riêng mỗi giá trị, bọc bằng khoá versioned từ cấu hình (chưa tích hợp KMS).
  Token và attempt id sinh bằng DRBG đã seed lúc khởi động để không đọc `/dev/urandom` trên event
  loop (BlockHound).
- **Nạp script Lua một lần.** Script text được đọc lúc khởi động; để Spring đọc lại file mỗi lần
  chạy là blocking I/O trên event loop (BlockHound phát hiện).
- **Provider.** Cart thêm `GET /internal/v1/carts/{user_id}/checkout-snapshot` (chỉ `X-Service-Identity`
  `checkout-service`, không gọi Product, không chạm TTL); Product trả `category_ids` (ancestry
  root-first, tối đa 20, giữ phần gần product nhất khi dài hơn); Promotion thêm
  `promotion-voucher-options`, `user_tier` tùy chọn và `context` tùy chọn khi lưu voucher
  ([Promotion ADR-018](../../promotion/decisions/ADR-018-checkout-voucher-options-and-context-free-save.md)).
- **Thứ tự lỗi Preview** (CHK-BR-019): hạ tầng thắng nghiệp vụ; giữa các lỗi nghiệp vụ theo thứ tự cố
  định để kết quả không phụ thuộc thứ tự hoàn thành của wave song song.

> **Ghi chú 2026-10-09 — Span.** Bốn span use case, `checkout.dependency` và `checkout.redis` ở
> [operations.md](../operations.md#span) được hiện thực bằng `Spans`: span là Observation của một
> registry chỉ có handler tracing, đặt vào Reactor context để span client `WebClient` nằm dưới span
> của nó. Chọn registry riêng thay vì registry chung của Boot vì registry chung biến mỗi span thành
> một timer, trùng tên với timer `checkout.redis` và counter `checkout.recovery` đã có (Prometheus
> đòi cùng một bộ nhãn cho một tên metric) và làm đôi bảng metric của operations.md. Không thêm
> exporter OTLP: chưa service Java nào có và nền tảng chưa chọn collector.

## Alternatives considered

- **openapi-generator cho toàn bộ DTO:** đúng chữ ADR-013 nhưng model strict và build nặng hơn;
  tolerant reader được chọn vì PDP đã chứng minh cách này và nó khớp nguyên tắc fail-closed theo
  contract thay vì theo schema.
- **Dùng Redis chung, đổi sang `noeviction`:** ảnh hưởng Cart, Promotion, Review trên cùng instance.
- **Dùng Redis chung, giữ `allkeys-lru`:** vi phạm bất biến, chỉ chấp nhận được nếu coi là ngoại lệ
  local; không chọn.
- **Bỏ qua field thừa (ADR-008):** nới validation và phải sửa OpenAPI; không chọn.

## Consequences

- Thêm một Redis cần vận hành và giám sát (`eviction = 0`, memory).
- Thêm `attempt_started_at` vào session hash; `data.md`, `domain.md` đã cập nhật.
- Cart, Product và Promotion đổi cùng đợt; contract và tài liệu của ba service được đồng bộ.

## Migration impact

Compose thêm `checkout-service`, `checkout-redis`, bốn router và ba profile rate limit `checkout-*`;
hai router customer của Promotion (`promotion-me`, `promotion-discovery`) chuyển sang
`authenticated@file` vì voucher phải thuộc đúng user đang checkout.

## Verification

`mvn test` trong `java/services/checkout-service` (Redis Testcontainers, WireMock, gRPC giả, BlockHound,
Lua cạnh tranh, contract test với OpenAPI); test Cart (`router_test.go`), Product
(`ProductCatalogGrpcIntegrationTest`), Promotion (`HttpContractIntegrationTest`).
