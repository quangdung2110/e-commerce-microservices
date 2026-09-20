---
title: Checkout Service
status: proposed
last_updated: 2026-09-20
owners:
  - domain: checkout
    team: commerce-platform
    contact: commerce-platform@example.com
review_cycle_days: 30
source_of_truth: false
evidence:
  - ../../contracts/http/checkout/v1/openapi.yaml
  - ../../contracts/http/cart/v1/openapi.yaml
  - ../../contracts/http/user/v1/openapi.yaml
  - ../../contracts/http/inventory/workflow/v1/openapi.yaml
  - ../../contracts/http/promotion/v1/openapi.yaml
  - ../../contracts/http/order/internal/v1/openapi.yaml
  - ../../contracts/grpc/ecommerce/product/v1/product_service.proto
tags:
  - checkout
  - orchestration
  - caching
---

# Checkout Service

Checkout là Backend for Frontend điều phối một lần mua hàng. Service không sở hữu giỏ hàng, giá sản phẩm, tồn kho, khuyến mãi hoặc đơn hàng; nó chỉ tạo báo giá ngắn hạn từ dữ liệu authoritative và điều phối submit idempotent sang Order Service.

## Phạm vi

- Xác thực người dùng từ trusted gateway headers.
- Lấy cart snapshot qua Cart internal API; không đọc Redis của Cart.
- Enrich SKU bằng Product gRPC, bao gồm `category_ids`.
- Kiểm tra toàn bộ giỏ bằng Inventory allocation preview; khi Inventory không sẵn sàng thì preview thất bại, không tạo degraded quote.
- Tính tối đa một promotion qua Promotion Service.
- Tạo checkout token bất biến, ký client binding và lưu snapshot ngắn hạn trong Redis.
- Submit đúng snapshot đã preview; revalidate giá/promotion khi cần và gọi Order bằng idempotency key ổn định.
- Cho phép client truy vấn kết quả của một submit bị timeout.

## API công khai

| Method | Path | Mục đích |
|---|---|---|
| `POST` | `/api/v1/checkout/preview` | Tạo checkout snapshot mới |
| `POST` | `/api/v1/checkout/submit` | Submit snapshot theo token |
| `GET` | `/api/v1/checkout/submissions/{checkout_token}` | Khôi phục kết quả submit |

Contract chuẩn: [Checkout OpenAPI](../../contracts/http/checkout/v1/openapi.yaml).

Preview chỉ nhận `previous_checkout_token`, `delivery_address_id`, `promotion_id`. Client không được gửi item, quantity, giá, discount hoặc user tier. Submit chỉ nhận `checkout_token`.

## Mô hình trạng thái

Mỗi preview tạo một UUIDv4 ngẫu nhiên và một record riêng. Token cũ không bị ghi đè bởi preview sau. `previous_checkout_token` là optimistic CAS fence để chỉ một successor trở thành active và đóng phiên cũ nếu phiên đó chưa submit.

```text
PREVIEWED -> SUBMITTING -> SUCCEEDED
    |             |            |
    +-> SUPERSEDED+-> RETRYABLE +-> kết quả lưu 24h
                  +-> CONFLICT
                  +-> FAILED
```

Chuyển trạng thái dùng Redis Lua/CAS. Cùng một token chỉ có một owner thực hiện side effect; request trùng nhận lại trạng thái/kết quả đã lưu. Checkout không giữ distributed lock trong lúc gọi service downstream.

## Redis keyspace và TTL

| Key | Giá trị | TTL |
|---|---|---:|
| `checkout:session:{token_hash}` | Snapshot và state machine của token | quote hợp lệ 20 phút; record giữ 30 phút để trả `410`/recovery an toàn |
| `checkout:active:{user_id}` | Hash token preview mới nhất; UX + CAS fence tạo successor, không phải submit authority | 20 phút |
| `checkout:submit-payload:{token_hash}` | Payload Order bất biến được tạo atomically khi claim | 30 phút |
| `checkout:result:{token_hash}` | Kết quả terminal/recovery | success 24 giờ; conflict/failure 30 phút |
| `checkout:idem:{user_id}:{idem_hash}` | Encrypted Preview response replay | 5 phút |
| `checkout:rate:{user_id}:{bucket}` | Bộ đếm rate limit | 60 giây |

Không key nào chứa raw checkout token, client secret hoặc payment secret. Redis là kho trạng thái workflow ngắn hạn, không phải cache bản sao dữ liệu domain.

## Nguồn dữ liệu authoritative

| Dữ liệu | Chủ sở hữu | Cách Checkout lấy |
|---|---|---|
| Cart items | Cart | `GET /internal/v1/carts/{user_id}/checkout-snapshot` |
| SKU, giá, category | Product | `GetBatchSkus` |
| Allocation plan | Inventory | `POST /internal/inventory/v1/allocation-previews` |
| Promotion quote | Promotion | `POST /internal/v1/promotion-discount-calculations` |
| Address | User | `GET /internal/v1/users/{user_id}/addresses/{address_id}/checkout-snapshot` |
| Order/result | Order | `POST /internal/v1/checkout-order-submissions` và `GET /internal/v1/checkout-order-submissions/{checkout_token}` |

Checkout không cache các dữ liệu trên độc lập. Snapshot được ràng buộc với token và chỉ dùng cho đúng workflow đó.

## Bất biến quan trọng

- Giá, quantity, category, tier và totals do server xác lập.
- Promotion hiện tại là một `promotion_id`; không mô tả stacking nhiều voucher.
- `checkout_channel` gửi sang Promotion luôn là trusted constant `CHECKOUT`.
- V1 chưa có tier authority nên luôn omit/null `user_tier`; tier-dependent rule không match. Bổ sung tier cần version đồng bộ Checkout–Order–Promotion.
- Allocation preview là whole-order và fail-closed.
- `user_ack_degraded_pricing` gửi sang Order luôn là `false` để tương thích contract; Checkout không có degraded pricing path.
- Order `Idempotency-Key` bằng chính UUID checkout token theo Order contract; không dùng request id ngẫu nhiên.
- Khi submit timeout/không rõ kết quả, Checkout hỏi Order theo idempotency key trước khi trả lỗi hết hạn.
- Checkout không cache Payment `client_secret`; response đầu relay trực tiếp và mọi replay/status đọc mới từ Order.

## Trạng thái triển khai

Thiết kế và contracts ở trạng thái `proposed`. Gateway chỉ được bật route khi Checkout upstream đã được deploy và health check đạt; tài liệu route không đồng nghĩa route runtime hiện đã active.

## Bản đồ tài liệu

- [Architecture](architecture.md)
- [Domain](domain.md)
- [Interfaces](interfaces.md)
- [Workflows](workflows.md)
- [Data and caching](data.md)
- [Operations](operations.md)
- [Development](development.md)
- [Decisions](decisions/README.md)
