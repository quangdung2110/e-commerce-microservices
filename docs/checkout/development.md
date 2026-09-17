---
doc_id: checkout-development
service: checkout
scope: target
status: proposed
owners:
  - Checkout Service team
evidence: []
verified_against_commit: null
---

# Checkout Service — Hướng dẫn phát triển

> Repository chưa có implementation Checkout Service để suy ra runtime, command,
> port, package hoặc cấu hình. File này là checklist triển khai/kiểm thử trung
> thực với thiết kế, không bịa lệnh copy-paste chưa tồn tại.

## 1. Điều kiện tiên quyết

Thiết kế cũ để ngỏ runtime: gọi song song bằng `Goroutines` (Go) hoặc
`CompletableFuture` (Java). Khi chốt runtime, cần ghi phiên bản ngôn ngữ/framework,
build tool, formatter/linter, container runtime, Redis (hỗ trợ Lua scripting,
Hash, `SETNX`/`EXPIRE`), gRPC toolchain cho Product client và HTTP client có
timeout/circuit breaker theo từng downstream. Tham khảo
[docs/common](../common) cho quy ước theo runtime.

## 2. Chạy local và dependency

Khi implementation được thêm, tài liệu phải cung cấp command chính thức để cài
dependency, build, start Redis, start Checkout HTTP server, kiểm tra health, và
cleanup an toàn.

Local stack cần Redis và các downstream: Cart (Redis keyspace `cart:user:*`),
User (address list + checkout-snapshot internal endpoint), Product gRPC, Inventory
routing/allocation preview, Promotion (`/internal/v1/promotion-discount-calculations`
và customer tray endpoints), Order (`submitCheckoutOrder` theo internal OpenAPI v1). Downstream có thể mock
bằng contract fixture mô phỏng đúng timeout/fallback (Cart 50ms, User 500ms,
Inventory 3s, Promotion 2s), lỗi có cấu trúc reserve fail của Order và idempotency
theo `checkout_token`. Không mock Redis bằng in-memory map nếu test liên quan tới
Lua/lock.

## 3. Cấu hình local

Cần `.env.example` hoặc profile local không chứa secret thật. Bảng cấu hình phải
bao phủ Redis endpoint, TTL (session/payload/response/routing 20 phút, lock 5s/30s,
rate limit 10/3 req/phút), timeout từng downstream, số lần retry Order (3), Product
gRPC endpoint, mTLS/service credential cho User/Promotion/Inventory. Stripe secret
thuộc Payment; Checkout chỉ chuyển tiếp `client_secret`/`publishable_key` và
không được commit/log giá trị thật.

## 4. Build, lint và format

Command chuẩn phải trùng CI và có tiêu chí pass rõ ràng. Ngoài compile/lint/format,
CI cần kiểm tra Lua script được nhúng đúng, Markdown links/frontmatter của bộ
tài liệu này, và (khi có) OpenAPI Checkout. Chưa có command cụ thể để ghi tại đây.

## 5. Kiểm thử

### Unit test

- Waterfall calculation: `cart_subtotal`, `total_product_price`, `final_amount`;
  quantity luôn từ Cart snapshot; `current_price` từ Product, không dùng
  `unit_price_at_add`.
- `pricing_hash` = `sha256:<64 lowercase hex>` của UTF-8 RFC 8785 canonical JSON
  `{items:[{sku_id,unit_price_minor}],promotion_campaign_revision,total_discount_minor}`,
  items sort theo byte UTF-8 của unique `sku_id`; `promotion_budget_version` không tham gia.
- `items_hash` dùng cùng format/canonicalization trên `{items:[{quantity,sku_id}]}`.
- `selected_vouchers` map không serialize `null`; chỉ slot `PLATFORM_VOUCHER`.
- Optimization Decision Tree chọn đúng downstream theo loại thay đổi.
- Dựng `dropped_promotions[]`/`proposal_checkout_data` từ kết quả re-evaluate;
  không có phép trừ cục bộ.
- Guard `user_ack_degraded_pricing`.

### Integration test (Redis thật)

- Lua check-and-mark: trả `1`/`-1`/`-2`; hai request đồng thời chỉ một pass.
- Lock preview 5s và submit 30s: concurrent request bị `429`/`423`; lock được
  `DEL` sau khi xong; TTL tự hết.
- Rate limit `INCR`+`EXPIRE`: 10/phút preview, 3/phút submit.
- Session Hash: `HSET` selective update, `HGETALL`, TTL 20 phút; token mới ghi đè.
- Routing cache hit/miss theo `items_hash`/`address_hash`; đổi voucher không
  invalidate.
- Payload/response cache lifecycle: `SET` sau Lua, `DEL` khi success, `410` khi
  hết TTL, replay trả cùng response cho success lẫn conflict.
- Thứ tự Submit: hash lệch → không chạy Lua, session vẫn `PREVIEW_READY`, lock
  được nhả.

### Contract/consumer test

Checkout là consumer của Promotion OpenAPI v1 (calculation, suggestions,
discovery, save), User OpenAPI v1 (`getCheckoutAddressSnapshot`), Inventory
Workflow OpenAPI v1 (allocation preview) và Product gRPC v1 (batch SKU). Cần
consumer test cho từng contract, gồm unknown field, timeout, lỗi có cấu trúc
reserve fail (`failed_promotion_id`, `failed_reason`, `reserve_mode`) từ Order.
Tên RPC/path còn lệch giữa tài liệu cũ và contract phải được chốt trước khi test
này pass (xem `README.md` §9).

### End-to-end/failure injection

Preview tạo mới → đổi voucher → đổi địa chỉ → Submit success; giá đổi giữa
Preview và Submit → `PRICE_CHANGED`; reserve fail → 409 → Preview với
`surviving_promotions[]` → Submit token mới; Order timeout ×3 → `503` → client
retry → recovery từ payload; payload hết TTL → `410`; Promotion/Inventory timeout →
degraded → Submit không ack `409` → có ack thành công; double-click và hai tab
chỉ tạo một order; Redis Lua fail nhưng lock vẫn chặn duplicate.

## 6. Contract code generation

Checkout chưa có OpenAPI/AsyncAPI riêng. Client stub cho Product gRPC sinh từ
`contracts/grpc/ecommerce/product/v1/product_service.proto`; client cho
Promotion/User/Inventory có thể sinh từ OpenAPI tương ứng. Cần pin tool version,
output path và CI drift check khi có code.

## 7. Database migration

Không áp dụng: Checkout không có database. Thay đổi format session Hash hoặc
công thức `pricing_hash` cần kế hoạch rollout tương thích với session đang sống
20 phút (xem `operations.md` §9).

## 8. Test data và fixture

Fixture tối thiểu: giỏ một/nhiều kho; SKU ACTIVE và không ACTIVE; voucher SAVED,
public ACTIVE, `BUDGET_EXHAUSTED`, quá `expired_at`; địa chỉ hợp lệ/không thuộc
user; response Order success, reserve fail có cấu trúc, timeout; session ở từng
status và cờ degraded. Dùng UUID/số điện thoại/địa chỉ giả, không dùng PII
production.

## 9. Gỡ lỗi

Trace bằng `correlation_id`, `user_id`, `checkout_token`, `order_id`. Inspect
Redis: `HGETALL checkout:session:{user_id}`, `GET checkout:payload/...`,
`GET checkout:response/...`, `TTL` các key, lock hiện có. Không `HSET status`
thủ công để “gỡ kẹt” — token one-time-use là bất biến; user phải Preview lại. Khi
nghi ngờ hash lệch, tính lại hash từ input Product/Promotion hiện tại và so với
`pricing_hash`.

## 10. Lỗi local thường gặp cần ghi khi có code

- Redis không bật Lua hoặc script không được load → Submit luôn lỗi.
- Lock không được `DEL` sau exception → user bị `423`/`429` tới khi TTL hết.
- Mock downstream không giữ đúng timeout → cờ degraded không xuất hiện.
- Quên `EXPIRE` counter rate limit → user bị khóa vĩnh viễn.
- Hash tính từ giá Cart (`unit_price_at_add`) thay vì Product → `PRICE_CHANGED` giả.
- Serialize `null` trong `selected_vouchers` → Promotion từ chối.

## 11. Definition of Done

Một thay đổi chỉ hoàn tất khi build/lint/test tương ứng pass; invariant
`INV-CHK-001..010` có test; thứ tự lock → hash → Lua → payload → Order → response
không bị phá; idempotency/recovery/degraded path có test; interface/data/workflow/
operations docs được cập nhật; breaking decision có ADR mới; secret/PII không
log; evidence/frontmatter được cập nhật trước khi nâng trạng thái khỏi `proposed`.
