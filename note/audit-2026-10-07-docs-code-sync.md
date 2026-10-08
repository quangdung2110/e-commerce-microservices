# Audit đồng bộ docs ↔ contracts ↔ code — 13 service backend (2026-10-07)

> Phạm vi: auth, user, cart, product, promotion, inventory, review, search, product-detail-bff,
> order, payment, checkout, notification. Không gồm chat, analytics.
>
> Phương pháp: đọc `docs/<service>/**`, `contracts/**` (OpenAPI, AsyncAPI, JSON Schema, proto,
> `contracts/catalog/*.yaml`, `ADOPTION.md`), rồi đối chiếu với code ở `go/services/*` và
> `java/services/*` (controller/router, DTO, error code, config `application.yml`/`application.yaml`,
> migration, Kafka consumer/producer, outbox/Debezium connector, HTTP client gọi service khác).
>
> **Lúc audit chưa sửa file nào.** File này dùng làm checklist để sửa trong một session khác.
> Mỗi mục có: mã, mức độ, vị trí, hiện trạng/bằng chứng, hướng sửa đề xuất.
>
> Mức độ:
> - 🔴 **Nghiêm trọng**: bug/logic sai thật trong code, hoặc luồng nghiệp vụ không chạy được.
> - 🟠 **Trung bình**: lệch contract/logic có hệ quả thực tế, hoặc contract sai.
> - 🟡 **Thấp**: docs cũ, tự mâu thuẫn, thiếu thông tin; không đổi hành vi runtime.
>
> Quy ước khi sửa (theo memory dự án): refactor docs phải grep toàn bộ `docs/` + `contracts/`
> cho thuật ngữ cũ; quyết định lớn → ADR mới. `payment-service` và `review-service` chưa được
> git theo dõi → backup trước khi sửa.

---

## 0. Tóm tắt điều hành

| Nhóm | Đánh giá |
|---|---|
| Đồng bộ tốt nhất | Inventory, Review, Product Detail BFF, Checkout: code khớp contract gần như từng route, mã lỗi và config. Review/Search/Cart có schema, script, Lua trong docs giống hệt từng byte với code. |
| Lệch nhiều nhất | Auth, User, Promotion, Search: docs cũ/tự mâu thuẫn và có bug thật trong code. |
| Lỗi lặp lại khắp nơi | Nhãn trạng thái cũ ("proposed", "chưa có implementation") trong `docs/README.md`, `contracts/catalog/services.yaml`, `contracts/ADOPTION.md`, `interactions.yaml` và frontmatter nhiều file. |

### Thứ tự sửa đề xuất
1. Quyết định Delivery (O3) — chặn toàn bộ luồng hoàn tất đơn.
2. Sửa bug code 🔴: PY1, PR8/PR10, A9, U9, N1/U4, S4, S6.
3. Chọn một mô hình xác thực internal (X2) và áp dụng đồng nhất cho Go + Java.
4. Đồng bộ catalog/adoption/index (G1–G5, C1, P5, N5, X1), sau đó dẫn xuất lại mesh policy.
5. Dọn docs 🟡 theo từng service, bổ sung bảng mã lỗi đầy đủ.

---

## 1. Danh sách 🔴 nghiêm trọng (làm trước)

| Mã | Service | Tóm tắt |
|---|---|---|
| O3 | Order (+ Delivery) | Không có Delivery provider; đơn không bao giờ `COMPLETED` |
| PY1 | Payment | Thiếu `GET /v1/payments/{id}` và `GET /v1/refunds/{id}` mà Order đang gọi |
| PR8 + PR10 | Promotion | `technical-cancellations` mở cho `order-service`, không kiểm proof; docs nói đã chặn |
| A9 | Auth | Lỗi Redis sau commit → `USER_DELETED` không bao giờ được phát |
| N1 / U4 | User + Notification | User mặc định `marketing_enabled=true`, Notification mặc định `false` → gửi marketing khi chưa đồng ý |
| U9 | User | Profile đã xoá (ẩn danh) vẫn ghi lại được PII |
| S4 | Search | Tombstone window 30d không dài hơn retention topic Inventory (≥30d) → partition có thể kẹt |
| S6 | Search | URL Product mặc định `:8080`, Product chạy `:8081` → full reindex lỗi ở compose |
| X2 (A10/U10/N3) | Auth, User, Notification | App-level mTLS mâu thuẫn mô hình mesh; production sau Istio ambient sẽ từ chối mọi internal call |

---

## 2. Toàn cục / catalog / index

### G1 🟡 Link tới Delivery README đã bị xoá
- Vị trí: `docs/README.md` §6, `contracts/catalog/services.yaml` (`delivery-service.docs: docs/delivery/README.md`), Order docs.
- Hiện trạng: `docs/delivery/README.md` đang bị xoá trong working tree (git status `D`). Link gãy. Liên quan O3.
- Sửa: hoặc khôi phục file (nếu giữ boundary Delivery), hoặc cập nhật mọi chỗ tham chiếu theo quyết định mới.

### G2 🟡 `docs/README.md` ghi trạng thái cũ
- §3 Payment: "HTTP v1 và payment facts v2 ... chưa xác minh implementation".
- §5 Promotion: "thiết kế đang ở trạng thái `proposed` và chưa có implementation để verification"; nói "16 ADR" nhưng thực có 18 (ADR-001..018); nói Promotion HTTP v1 có "24 operations" (thực tế 25, xem PR3).
- §7 "Product Detail BFF (target)" — đã implement.
- §1 Checkout "Thiết kế ở mức target/proposed".
- §10 "Service ngoài scope": auth "ngoài scope 14 phase", review/search/cart/product ghi chưa sync — đều cũ.
- Sửa: cập nhật từng mục theo trạng thái code hiện tại.

### G3 🟡 `contracts/catalog/services.yaml` sai trạng thái
- `payment-service`: `runtime: java-planned`, `status: documented`, `contract_adoption: not_started` — code đã có (`java/services/payment-service`, Gate 3).
- `notification-service`: `runtime: go-planned`, `status: documented` — code phase 1 có ở `go/services/notification-service`.
- `delivery-service.docs` trỏ file đã xoá (G1).

### G4 🟡 `contracts/ADOPTION.md` cũ
- Cart HTTP v1 "proposed — code cũ dùng nhiều error shape và giá float64": code đã dùng delta/Money/problem+json. `interactions.yaml` lại ghi `checkout-reads-cart-snapshot: implemented`.
- Product gRPC v1 "proposed — proto bị copy, package/service name cũ, giá double": package `ecommerce.product.v1` và Money đã đúng; chỉ còn việc proto bị copy (P7). `interactions.yaml` ghi `implemented`.
- Review HTTP v1 "Service chưa có runtime": `java/services/review-service` đã có.
- Search HTTP v2 "frontend vẫn gọi `/api/v1/search/**`": gateway và `interactions.yaml` nói v1 đã bị gỡ khỏi router (bước 4 frontend).
- Order Events v1 "Checkout chưa có runtime": Checkout đã implement.
- Promotion HTTP v1 "24 operation": thực có 25 (PR3).

### G5 🟡 `contracts/catalog/interactions.yaml` lệch nhiều chỗ
- `cart-publishes-cart-events` sai hoàn toàn (C1).
- Thiếu interaction Promotion → Product snapshot (P5).
- `notification-publishes-outcomes` liệt kê `user-service` là consumer `complaint-received`, nhưng User chưa có consumer (U5).
- `notification-ops-operates-notification` ghi consumer `finance-ops` trong khi mesh/code dùng principal `notification-ops` (N5).
- Các interaction của Checkout (`checkout-calculates-promotions`, `checkout-lists-voucher-options`, `checkout-previews-inventory-allocation`, `checkout-reads-user-address-snapshot`) vẫn `proposed` dù hai phía đã implement (K3).
- `payment-publishes-payment-events`, `stripe-delivers-payment-webhook`, các `notification-*` vẫn `proposed` (PY2, N2).

---

## 3. Auth (`docs/auth`, `contracts/http/auth/**`, `contracts/events/auth/v1`, `go/services/auth-service`)

### A9 🔴 [BUG] `USER_DELETED` có thể không bao giờ được phát
- Vị trí: `go/services/auth-service/internal/domain/service/account_deletion_service.go` hàm `delete`.
- Hiện trạng: transaction DB commit (account `DELETED`, ẩn danh, revoke refresh token) → sau commit gọi `revoker.MarkAllTokensRevoked`. Nếu Redis lỗi, hàm **return error trước** `notifier.Publish(USER_DELETED)`. Client nhận 500; retry → `ACCOUNT_NOT_ACTIVE` 409 "already deleted" → event không bao giờ phát. User/Notification/Review/Chat không bao giờ purge PII. Không có reconciliation nào phát lại.
- Pattern tương tự: `email_change_service.go` `Confirm` (email đã đổi, `RevokeAllSessions` lỗi → không phát `USER_EMAIL_CHANGED`), `password_service.go` (đổi/reset password rồi revoke lỗi → không phát `USER_PASSWORD_CHANGED`).
- Sửa đề xuất: luôn publish event sau commit kể cả khi ghi marker lỗi (ghi log/alert riêng cho marker); hoặc cho phép retry idempotent phát lại event khi account đã `DELETED` mà chưa có bằng chứng publish; hoặc chuyển sang outbox (cần ADR thay ADR-005). Cập nhật WF-AUTH-006 + ADR-005.

### A10 🔴 Mô hình xác thực internal API mâu thuẫn với contract/mesh (xem X2)
- Contract `contracts/http/auth/internal/v1/openapi.yaml` (securitySchemes `notificationServiceMtls`): "mTLS và allowlist principal notification-service do service mesh thực thi; **Auth không nhìn thấy chứng chỉ caller**. Caller gửi `X-Service-Identity: notification-service` cho attribution".
- Code: `internal/api/middleware/service_identity.go` kiểm `r.TLS.VerifiedChains` + CN; `internal/common/config/config.go` (~dòng 374) **từ chối khởi động production** nếu internal listener không có cert mTLS ở tầng app; bỏ qua `X-Service-Identity`.
- Hệ quả: sau Istio ambient (app không thấy cert) phải cấu hình TLS hai lớp, nếu không service không khởi động.
- Sửa: chốt một mô hình (X2), cập nhật code hoặc contract + `docs/auth/interfaces.md` §3.

### A11 🟠 Rate limit internal theo IP thay vì theo principal
- Docs `docs/auth/interfaces.md` §3: "Rate limit riêng cho principal `notification-service`", "rate limit theo client `security.rate_limit.internal_per_client`".
- Code: `internal/api/router/internal_router.go` dùng scope `internal-delivery:ip` với `c.ClientIP()`.
- Sửa: key theo service identity, hoặc sửa docs cho đúng.

### A12 🟠 Endpoint gửi lại OTP lộ sự tồn tại của tài khoản
- Contract `POST /api/v1/auth/email-verification-requests`: "Luôn trả cùng phản hồi thành công để không tiết lộ email có account chờ xác minh hay không".
- Code `registration_service.go` `RequestVerification`: email không tồn tại / không `PENDING` → `nil` (202); tồn tại + `PENDING` + vượt resend limit → `429 RATE_LIMITED`. 429 chỉ xuất hiện khi tài khoản tồn tại → side-channel enumeration.
- Sửa: khi vượt limit vẫn trả 202 (không gửi OTP), hoặc ghi rõ ngoại lệ trong contract.

### A13 🟠 `revoked_before` theo giây gây từ chối token hợp lệ
- Code `internal/infrastructure/redis/revocation_store.go` lưu `issuedBefore.Unix()`; gateway-auth từ chối `iat <= revoked_before`.
- Token được cấp trong cùng giây sau "logout all"/đổi password/đổi email bị từ chối tới khi hết hạn (15 phút). Hiếm nhưng có thể gặp khi client tự đăng nhập lại ngay.
- Sửa: lưu mốc millisecond hoặc so sánh `iat < revoked_before`, đồng bộ `docs/gateway/runtime-contract.md` §6.

### A1 🟡 Bảng mã lỗi `domain.md` sai / thiếu
- `docs/auth/domain.md` §4: BR-001 `ACCOUNT_NOT_ACTIVE` (đúng), BR-002 `REFRESH_TOKEN_REVOKED` (code: `REFRESH_TOKEN_INVALID`), BR-003 `SESSION_REVOCATION_FAILED` (không có trong code), BR-007 `ACCOUNT_ALREADY_DELETED` (code trả `ACCOUNT_NOT_ACTIVE` 409 detail "already deleted"; ngoài ra có `ACCOUNT_DELETED`).
- §7 chỉ liệt kê 6 mã; code (`internal/common/errors/errors.go`) có 23: `VALIDATION_FAILED, UNAUTHORIZED, FORBIDDEN, INVALID_CREDENTIALS, ACCOUNT_NOT_VERIFIED, ACCOUNT_LOCKED, ACCOUNT_NOT_ACTIVE, ACCOUNT_DELETED, ACCOUNT_NOT_FOUND, NOT_FOUND, METHOD_NOT_ALLOWED, EMAIL_ALREADY_REGISTERED, REFRESH_TOKEN_INVALID, SESSION_NOT_FOUND, OTP_INVALID, RESET_TOKEN_INVALID, PASSWORD_POLICY_VIOLATION, RATE_LIMITED, DELIVERY_NOT_FOUND, DELIVERY_EXPIRED, SIGNING_KEY_MISSING, DEPENDENCY_UNAVAILABLE, INTERNAL_ERROR`. Mapping HTTP ở `internal/api/httpx/problem.go`.
- Comment `errors.go` "Codes match docs/auth/domain.md section 7" là sai.
- OpenAPI không liệt kê `code` nào.
- Sửa: viết lại §4/§7 theo code (kèm HTTP status); cân nhắc thêm enum `code` vào OpenAPI.

### A2 🟡 TTL denylist mô tả mâu thuẫn
- `workflows.md` WF-AUTH-004: TTL denylist "không vượt quá thời gian còn lại của access token".
- `data.md` §6 và code `revoker.go`: TTL = access TTL + clock skew (cận trên, có thể sống lâu hơn token).
- Sửa WF-AUTH-004 theo data.md.

### A3 🟡 State machine thiếu transition
- `domain.md` §5: `LOCKED → ACTIVE` chỉ khi hết `locked_until` bởi worker; §6 "auto unlock chỉ do system worker".
- Code: `entity/account.go` `UnlockIfExpired` được gọi inline trong login/refresh (không phát `USER_UNLOCKED`); `ClearLockout` trong reset password chuyển `LOCKED → ACTIVE`.
- Sửa: bổ sung hai transition vào domain/workflows.

### A4 🟡 Mô tả lockout/rate limit sai và thiếu giá trị
- WF-AUTH-002 "Login sai tăng counter theo email và IP". Code: counter bền vững per-account trong DB (`failed_login_count`) + Redis rate limit `login:ip` và `login:account` (theo email chuẩn hoá).
- Giá trị lockout 5 lần / khoá 15 phút / reset counter sau 1 giờ (`configs/application.yaml`) không có trong docs.

### A5 🟡 Docs nói "chưa có implementation / TBD"
- Frontmatter `status: proposed`, README "chưa được xác minh theo implementation"; `development.md` "refactor này không xem code ... không command nào được bịa ra"; `operations.md` mọi giá trị "TBD".
- Code + config đã có giá trị cụ thể: access 15m, refresh sliding 7d / absolute 30d, OTP 5m / 5 lần / resend 3 trong 15m, reset 15m, delivery TTL 30m, key rotation 30d, retirement 24h, interval các worker, cổng 8080/8081.
- Sửa: viết lại development.md/operations.md theo code (tham khảo cách User docs làm).

### A6 🟡 Diagram thiếu consumer
- `architecture.md` diagram: Kafka → chỉ User, Notification. Thiếu Review, Chat (consumer `USER_DELETED`).

### A7 🟡 Sai hướng / SLI thừa
- `interfaces.md` §1 dòng JWKS ghi hướng "Outbound" — Gateway gọi Auth (inbound HTTP).
- `operations.md` có SLI "Kafka consumer lag" dù Auth không consume event nào.

### A8 🟡 WF-AUTH-006 nói "trong transaction ... đặt revoke marker"
- Code ghi marker Redis sau commit (không thể nằm trong transaction PostgreSQL). Sửa mô tả.

### A14 🟡 Lỗi policy password khi đăng ký trả 400
- Register không có 422 trong contract nên vi phạm policy trả `400 VALIDATION_FAILED` — hành vi đúng nhưng chưa được ghi.

Đã khớp: route/status == OpenAPI; event type/topic/key/field == AsyncAPI; `docs/auth/schema/0001_initial_auth_schema.sql` == migration; cookie `auth_refresh` path `/api/v1/auth/sessions`; JWKS RS256.

---

## 4. User (`docs/user`, `contracts/http/user/v1`, `contracts/events/user/v1`, `go/services/user-service`)

### U9 🔴 [BUG] Profile đã xoá vẫn ghi lại được PII
- Sau `USER_DELETED`, `LifecycleService.Anonymize` giữ row profile (ẩn danh) tới hard-delete.
- `ProfileService.Get/Update` (`internal/domain/service/profile_service.go`) và `AddressService.Create` không kiểm `user_deletions`; repo `profile_repo.go` cũng không lọc.
- Hệ quả: GET trả 200 profile ẩn danh; PATCH ghi lại tên/ngày sinh vào profile ẩn danh và phát `USER_PROFILE_UPDATED` sang Chat; POST address tạo address chứa PII cho user đã xoá. Trái `docs/user/domain.md` §8 ("request update phải bị từ chối hoặc không tìm thấy profile đã anonymize"). Chỉ được che bởi revocation ở gateway — vốn có thể hỏng (A9).
- Sửa: coi user có `user_deletions` là `PROFILE_NOT_FOUND` 404 ở mọi đường đọc/ghi (kể cả preferences/address), hoặc thêm cờ trên profile; thêm test.

### U4 🔴 Mặc định `marketing_enabled` mâu thuẫn với Notification (xem N1)
- `docs/user/data.md` + migration `0001`: `marketing_enabled DEFAULT TRUE`; bootstrap tạo preferences mặc định và **không phát** `USER_PREFERENCES_UPDATED`.
- `docs/user/interfaces.md` §4 và `docs/notification/08-decide.md` §5: khi chưa có preference thì marketing **tắt** vì lý do compliance.
- Reconciliation hằng ngày của User phát snapshot hiện hành (`marketing_enabled=true`) → trong 24h Notification bật marketing cho user chưa từng đồng ý.
- Sửa: thống nhất mặc định `false` ở nguồn User (migration mới + docs), hoặc thêm cơ chế consent; cân nhắc phát `USER_PREFERENCES_UPDATED` khi bootstrap.

### U10 🔴 Internal checkout-snapshot dùng app-level mTLS (xem X2)
- Contract `checkoutService`: mesh thực thi, "User không nhìn thấy chứng chỉ caller", caller gửi `X-Service-Identity`.
- Code `internal/api/router/internal_router.go` + `middleware/service_identity.go`: kiểm CN tầng app; production bắt buộc cert. Rate limit `internal-checkout:ip` theo IP (Checkout README cũng ghi gap: 600 req/phút dùng chung cho mọi pod Checkout → 429 → Checkout trả 503).

### U5 🟠 Catalog khai User là consumer `complaint-received` nhưng chưa có
- `interactions.yaml` `notification-publishes-outcomes`: consumers `[analytics-service, user-service]`.
- `docs/user/interfaces.md` §1: "proposed — User chưa đăng ký consumer". Code: consumer chỉ đọc `t.auth.events`.
- Sửa: bỏ khỏi catalog cho tới khi implement, hoặc implement consumer.

### U7 🟠 Không xoá được địa chỉ duy nhất
- BR-004 cấm xoá địa chỉ default và không có thao tác bỏ default → user chỉ có một địa chỉ không bao giờ xoá được (chỉ sửa được). Docs không nêu.
- Sửa: cho phép xoá default khi là địa chỉ cuối cùng, hoặc ghi rõ quyết định.

### U8 🟠 Giới hạn 50 địa chỉ không có trong docs/contract
- Code `internal/common/constants/constants.go` `AddressBookLimit = 50`, vượt → `INVALID_ADDRESS` 422.
- OpenAPI `AddressList.addresses` không có `maxItems` (vi phạm `contracts/conventions/data-types.md` "List MUST có maxItems").

### U11 🟠 Race cache-aside có thể giữ list cũ tới 12h
- `AddressService.List`: đọc DB rồi `Put` cache; nếu một mutation `DEL` cache xen giữa, list cũ được ghi lại và sống tới TTL 12h mà không có lỗi nào. Docs chỉ nói stale "giữa commit và invalidation, hoặc tới TTL nếu DEL thất bại".
- Sửa: version/compare-and-set khi fill cache, hoặc ghi rõ rủi ro.

### U1 🟡 `data.md` nói chưa có migration
- Header: "Chưa tạo migration/schema machine-readable"; §3 "cần tạo migration trước khi đánh dấu implemented". Thực tế `migrations/0001_initial_user_schema.up.sql` có và khớp data.md; README cũng nói đã có.

### U2 🟡 Trạng thái mâu thuẫn
- `architecture.md` header "cấu trúc mục tiêu, không phải implementation đã xác minh" và §7 "Tất cả vẫn là proposed" — trong khi ADR index đều "Đã chấp thuận", README nói runtime đã có.

### U3 🟡 Bảng lỗi thiếu mã
- BR-003 dùng `DEFAULT_ADDRESS_CONFLICT` nhưng §7 không liệt kê. Code còn có `INVALID_PROFILE_FIELD`, `INVALID_ADDRESS` (422), `VALIDATION_FAILED`, `UNAUTHORIZED`, `RATE_LIMITED`, `DEPENDENCY_UNAVAILABLE`... Comment `errors.go` "Codes match docs/user/domain.md section 7" sai.

### U6 🟡 Payload event mô tả thiếu
- WF-USER-002: `USER_PROFILE_UPDATED` "chỉ với user_id và sender_name" — contract còn `updated_at`.

Đã khớp: route/status/schema; event type/topic/key/payload; consumer retry 10 lần 1s→5m → DLQ; purge mỗi giờ batch 100 `SKIP LOCKED`; reconciliation hằng ngày; cache key/TTL 12h, empty 15m; migration == data.md.

---

## 5. Cart (`docs/cart`, `contracts/http/cart/v1`, `contracts/events/cart/v1`, `go/services/cart-service`)

### C1 🟠 `interactions.yaml` mô tả sai event của Cart
- Entry `cart-publishes-cart-events`: consumers `[cart-service, analytics-service, notification-service]`, notes "Pilot contains CART_ITEM_ADDED only".
- Thực tế (AsyncAPI, README, interfaces, code): Cart chỉ phát `CART_ABANDONED`, Notification là consumer duy nhất, "không phát CART_ITEM_*". Entry còn trùng với `notification-consumes-cart-abandoned`.
- Mesh AuthorizationPolicy dẫn xuất từ catalog sẽ sai. Sửa entry.

### C11 🟠 [BUG nhỏ] TTL idempotency của POST bị hardcode
- `internal/infrastructure/cartredis/scripts/upsert_item.lua` (và bản docs `docs/cart/scripts/upsert_item.lua`): `SET ... 'EX', 86400`. `remove_items.lua` dùng `ARGV[8]` từ config `CART_IDEMPOTENCY_TTL`.
- Sửa: truyền TTL qua ARGV cho upsert; cập nhật cả bản trong docs (đang giống hệt code).

### C12 🟠 Cart không xử lý `USER_DELETED`
- Cart README Known gaps có ghi; nhưng Auth docs/interactions liệt kê các PII owner mà không nhắc Cart. Giỏ hàng chứa `user_id` tồn tại tới 37 ngày sau khi xoá tài khoản.
- Sửa: implement consumer hoặc ghi rõ trong Auth docs/interactions.

### C7 🟠 Contract internal snapshot tự mâu thuẫn với interfaces/code
- OpenAPI `checkoutService`: "X-Service-Identity is attribution only". `docs/cart/interfaces.md` + code (`middleware/service_identity.go`, config `cart.internal_callers`): allowlist header, thiếu → 401, service khác → 403.
- OpenAPI thiếu response 400 (user_id không phải UUID) mà interfaces.md có.

### C8 🟡 Danh mục mã lỗi chưa đầy đủ
- `domain.md` §8 có 8 mã. Code (`internal/domain/errors.go`, `api/dto/response/problem.go`) còn: `VALIDATION_FAILED, FORBIDDEN, RATE_LIMITED, CART_STORE_UNAVAILABLE, PRODUCT_UNAVAILABLE, NOT_FOUND, INTERNAL_ERROR`. Mapping (`ITEM_NOT_FOUND`/`PRODUCT_SNAPSHOT_REQUIRED` → 422; `DELTA_INVALID` → 400) chưa ghi. OpenAPI không liệt kê code.

### C9 🟡 Rate limit chưa được ghi
- 20 rps / burst 40 theo user áp dụng cả `GET /mine`; OpenAPI GET không có 429; bảng config ở operations.md không có rate limit.

### C10 🟡 Retry policy trong AsyncAPI dễ hiểu sai
- `x-retry-policy` (max 3 + DLQ `t.cart.events.dlq`) đọc như policy của relay. Code relay: thử 3 lần/lượt rồi để entry pending, lượt sau thử lại; chỉ DLQ entry malformed. Cần ghi rõ.
- `kafka.event_data_schema` dùng URL tuyệt đối `https://contracts.ecommerce.local/...`, các service khác dùng `/contracts/events/...`; example AsyncAPI thiếu `dataschema`.

### C2 🟡 README trỏ ADR đã bị thay thế
- README §8 "Quyết định ranh giới ... hiện hành: ADR-012" — ADR-012 đã bị ADR-013 thay thế.

### C3 🟡 README nói chưa xác minh với code Go
- §9 "Tài liệu chưa được xác minh với implementation Go"; frontmatter `proposed`. Thực tế Lua docs == code từng byte, code dùng delta/Money/problem+json.

### C4 🟡 Cột "Supersedes" bị ngược nghĩa
- `docs/cart/decisions/README.md`: các dòng Superseded ghi ADR thay thế vào cột "Supersedes" (vd ADR-001 "Supersedes ADR-010"). Đổi tên cột thành "Superseded by" hoặc ghi đúng chiều.

### C5 🟡 "Đặt quantity" không còn đúng
- README §3, WF-CART-001 tiêu đề "Thêm hoặc đặt quantity" — API chỉ nhận delta.

### C6 🟡 Cổng sai trong OpenAPI
- `servers: http://localhost:8085` — theo `development.md`, 8085 là cổng host của Promotion; Cart không publish cổng host.

Đã khớp: path, delta, max 50 SKU / 100 qty clamp, TTL 30d/37d/7d, validation TTL 45s, 64 bucket, relay XACK sau Kafka ack, consumer `order.paid.v1` (type/source/subject/dataschema/sequence/sku_ids ≤200 khớp Order contract), snapshot endpoint.

---

## 6. Product (`docs/product`, `contracts/http/product/v1`, `contracts/events/product/v1`, `contracts/grpc/ecommerce/product/v1`, `java/services/product-service`)

### P5 🟠 Interaction Promotion → Product thiếu trong catalog; allowlist rộng hơn contract
- Promotion gọi `GET /internal/v1/products/{id}/snapshot` để validate trước khi lưu markdown rule. Có trong Product OpenAPI (`searchServiceMtls` description) và code allowlist, mesh matrix cũng có, nhưng **không có** trong `interactions.yaml` và bảng Dependencies của Product README.
- Code `application.yml` đặt `promotion-service` vào nhóm `reindex-clients` → Promotion gọi được cả `/internal/v1/products/export` và `/internal/v1/categories/export` (contract chỉ cho snapshot).
- Sửa: thêm interaction; tách allowlist riêng cho snapshot.

### P7 🟡 Proto bị copy vào service
- `java/services/product-service/proto/ecommerce/product/v1/product_service.proto` (và `common/v1/money.proto`) giống hệt bản trong `contracts/` → vi phạm `contracts/README.md` ("service không copy .proto"). ADOPTION ghi "package/service name cũ, giá double" — đã cũ.
- Sửa: build từ `contracts/` (Maven proto source path) hoặc ghi rõ ngoại lệ.

### P1 🟡 Docs nói chưa implement
- README "Trạng thái implementation: Thiết kế này chưa phải migration hay implementation. Cần tạo migration Flyway..."; `data.md` "migration Flyway phải được tạo"; `development.md` "Trước implementation phải tạo contract...". Code đã có V1–V3, gRPC, outbox, consumer.

### P2 🟡 ADR index cũ
- README "ADR-009 là boundary" — ADR-009 đã bị ADR-017 thay thế. Cột "Supersedes" ngược nghĩa (vd ADR-002 "Supersedes ADR-012"). ADR-015/ADR-018 vẫn "Proposed" dù đã implement và PDP BFF đang dùng.

### P3 🟡 Bảng mã lỗi thiếu
- `domain.md` có 10 mã. OpenAPI `DomainConflict` + `common/ErrorCode.java` còn: `CATEGORY_CYCLE_DETECTED, SLUG_ALREADY_EXISTS, ATTRIBUTE_CODE_ALREADY_EXISTS, ATTRIBUTE_OPTION_CODE_ALREADY_EXISTS, ATTRIBUTE_IN_USE, CATALOG_CONSTRAINT_VIOLATION, PRODUCT_REVISION_MISMATCH (412), MEDIA_NOT_FOUND, ATTRIBUTE_NOT_FOUND, ATTRIBUTE_OPTION_NOT_FOUND, INVALID_ATTRIBUTES, INVALID_REQUEST, INVALID_CURSOR, CATALOG_UNAVAILABLE (503), UNAUTHENTICATED, FORBIDDEN`. Vi phạm slug (BR-013) không có mã riêng được ghi.

### P4 🟡 Dependency `facet_definitions` chưa đóng
- `interfaces.md`: Search v2 cần `facet_definitions` trong category snapshot/export "trước khi Search bật v2 facet". Chưa có trong schema event/export; Search v2 đã chạy và tự dựng label từ token (S5). Cần quyết định: bổ sung vào Product hay hạ thành known gap chung.

### P8 🟡 Tên constraint lệch giữa DDL docs và migration
- `docs/product/schema/schema.sql`: `chk_markdown_state_payload` + `chk_markdown_period`; migration V2: `chk_markdown_state_shape`, `chk_markdown_inactive_reason`. Ngữ nghĩa tương đương. Đồng bộ tên cho dễ đối chiếu.

Đã khớp: path/method, If-Match 412, slug rule, gRPC (batch 100, `category_ids` ≤ 20 root-first, `image_url` là storage key), event type/topic/key/sequence, routing Debezium, markdown guard, ACTIVE đến muộn → EXPIRED; unique `promotion_rule_id` phù hợp vì một markdown rule chỉ cho một product.

---

## 7. Promotion (`docs/promotion`, `contracts/http/promotion/v1`, `contracts/events/promotion/v1`, `java/services/promotion-service`)

### PR8 🔴 [SECURITY/BUG] `technical-cancellations` không có guard như docs mô tả
- Docs (`domain.md` BR-018, `interfaces.md` §2.3, `workflows.md` WF-PROMO-006, `operations.md` RB-08, OpenAPI): chỉ `promotion-legacy-migrator`, bắt buộc header `X-Legacy-Migration-Proof` + body `migration_record_id`, `pre_financial_watermark_evidence_sha256`; "`order-service` is denied by mesh **and application policy**".
- Code:
  - `config/TrustedIdentityFilter.java`: route nằm dưới `ALLOCATION_PATH` → allowlist `allocation-clients = [order-service]`; không có nhóm cho legacy migrator.
  - `web/InternalPromotionController.java` `cancelConfirmed`: nhận `FinalAllocationRequest` (không có field proof), không đọc header proof.
- OpenAPI có `x-known-runtime-gap` thừa nhận, nhưng docs nghiệp vụ vẫn khẳng định đã chặn.
- Hệ quả: Order (hoặc ai giả header khi chưa có mesh) có thể đưa voucher `USED` về `SAVED`/`EXPIRED` và giảm budget đã dùng.
- Sửa: implement guard (nhóm allowlist riêng `promotion-legacy-migrator`, DTO `LegacyTechnicalCancellationRequest`, kiểm header/digest, migration thêm cột) — hoặc tạm gỡ route; đồng bộ docs.

### PR10 🔴 `data.md` mô tả cột không tồn tại
- `data.md` §3.8 `promotion_command_receipts`: `migration_record_id`, `pre_watermark_evidence_sha256` + CHECK theo `CANCEL_CONFIRMED`. `V1__promotion_baseline.sql` không có cột/CHECK này; `FinalCommandService` không xử lý proof. Data.md lại tuyên bố phản ánh DDL V1. Đi cùng PR8.

### PR2 🟠 `checkout_channel=CHECKOUT` chỉ được kiểm ở voucher-options
- Contract `TrustedCheckoutContext.checkout_channel: const CHECKOUT` cho calculate, voucher-options, reserve.
- Code `service/DiscoveryService.java` chỉ kiểm trong `options(...)`; `calculate` và `AllocationService.reserve` không kiểm. Checkout và Order đều gửi `CHECKOUT` nên hiện chưa bị khai thác.
- README §9 tự mâu thuẫn: gạch đầu dòng 1 nói gate "phải bỏ `@NotBlank` userTier + enforce checkoutChannel ... catalog giữ Checkout interaction proposed", gạch sau nói `user_tier` đã sửa. `user_tier` đã optional; phần channel còn mở.
- Sửa: enforce ở calculate + reserve; viết lại README §9.

### PR4 🟠 OpenAPI defect: tham số không thể thoả
- `components.parameters.LegacyMigrationProof`: `format: uuid` + `pattern: '^CANCEL_FINAL:...'`. Pattern này thuộc `TechnicalCancellationIdempotencyKey` (đang không có pattern). Sửa: chuyển pattern sang đúng tham số.

### PR3 🟡 Số operation và trạng thái
- OpenAPI có 25 operation (thêm voucher-options), docs/README + ADOPTION ghi 24. OpenAPI `x-adoption-status: proposed` và `contracts/http/promotion/v1/README.md` "proposed" trong khi ADOPTION ghi "implemented".

### PR1 🟡 Nhiều chỗ nói chưa implement
- `docs/README.md` §5; `decisions/README.md` "Promotion Service hiện chưa có source module"; README §9 "Order/Checkout chưa có client gọi Promotion"; `interfaces.md` §1 trạng thái "contract target exists", §7 "Markdown contract v1 đang proposed", "Trước implementation phải có contract tests".

### PR5 🟡 Số ADR
- `docs/README.md` "16 ADR"; thực có 18.

### PR6 🟡 Cấu hình/cổng lệch
- `operations.md`: `promotion.product.base-url` mặc định `http://product-service:8081`; `application.yml` mặc định `http://localhost:8082` (8082 là User). Compose override đúng `:8081`.
- OpenAPI `servers: http://promotion-service:8080` nhưng `server.port` mặc định 8085.

### PR7 🟡 Bảng mã lỗi lệch
- `domain.md` §10 có `PARTIAL_RESERVATION_STATE` — không có trong `common/ErrorCode.java`. Code có thêm `CAMPAIGN_NOT_FOUND, PROMOTION_NOT_FOUND, MARKDOWN_RULE_NOT_FOUND, PRODUCT_NOT_FOUND, CAMPAIGN_CODE_ALREADY_EXISTS, PROMOTION_CODE_ALREADY_EXISTS, MARKDOWN_RULE_ALREADY_EXISTS, PROMOTION_CONSTRAINT_VIOLATION, INVALID_REQUEST, INVALID_CURSOR, UNAUTHENTICATED, FORBIDDEN, INTERNAL_ERROR, PROMOTION_UNAVAILABLE`.

Đã khớp: TTL reservation 15m + grace 5m tương thích deadline thanh toán của Order (deadline bị cap theo hold expiry − 30s); Order dùng `REDEEM_FINAL:{order_id}:{promotion_id}`; Order không bao giờ gọi technical-cancellations; markdown event schema/topic/key.

---

## 8. Inventory (`docs/inventory`, `contracts/http/inventory/**`, `contracts/events/inventory/v1`, `java/services/inventory-service`)

Docs chặt chẽ nhất, code khớp contract gần như hoàn toàn.

### I1 🟡 Thư mục `brainstorm/` gắn nhãn sai
- Frontmatter các file `docs/inventory/brainstorm/*.md` ghi `scope: target, status: proposed` (riêng `design-charter.md` frontmatter hỏng: `## doc_id`), trong khi nội dung trái với thiết kế đã chốt: release `STOCK_DISCREPANCY` + `order_exception_id`, Commit bị chặn sau `expires_at` (INV-DOM-010), path dạng `reservations:reserve`, `{order_id}:release`, release một line `POST_DISPATCH_EXCEPTION`.
- README nói brainstorm "là evidence, không phải nguồn chuẩn".
- Sửa: đổi frontmatter thành `status: superseded` / `historical` (hoặc thêm banner) và sửa frontmatter hỏng.

### I2 🟡 `interfaces.md` §4 thiếu endpoint ADMIN
- Thiếu: `GET /warehouses/{warehouse_id}`, `/stock-positions/{warehouse_id}/{sku_id}`, `/stock-movements/{movement_id}`, `/commands/{command_id}`, `/reservations/{order_id}`, `/refund-scopes/{refund_scope_id}` (có trong ADMIN OpenAPI và `AdminController`).

Đã khớp: 4 surface (workflow/admin/read/export) == OpenAPI; `ErrorCode` == bảng §5; config (lock 2s, 2 retry, 15m, orphan 60m, freshness 30s, backpressure 100k, snapshot 24h, write-freeze) == docs; topic/key == AsyncAPI; Checkout gửi đủ `X-Correlation-ID` + `X-Causation-ID`; mapping `cancel_reason` của Order khớp lý do release.

---

## 9. Review (`docs/review`, `contracts/http/review/**`, `contracts/events/review/v1`, `java/services/review-service`)

### R1 🟡 Nhãn trạng thái
- Frontmatter `status: proposed` (README/interfaces/development); `interfaces.md` §6 "All contracts remain proposed"; ADOPTION "Service chưa có runtime" — trong khi README nói đã implement.

### R2 🟡 README mô tả schema thiếu V2
- README: "schema the service runs is byte-identical to `0001_initial_review_schema.sql`" — service còn chạy V2 (`V2__eligibility_product_snapshot.sql` == `docs/review/schema/0002_eligibility_product_snapshot.sql`).

Đã khớp: toàn bộ path public/admin/internal; V1/V2 == SQL docs; topic `t.review.events`/`t.order.events`/`t.auth.events`; consumer group; bảng env var == `application.yml` (window 30 ngày, threshold 10, giới hạn media...); event catalog.

---

## 10. Search (`docs/search`, `contracts/http/search/v2`, `java/services/search-service`)

### S4 🔴 Tombstone protection window không dài hơn retention topic
- Quy tắc SEARCH-INV-006 / ADR-011 / `data.md`: cửa sổ bảo vệ tombstone "phải dài hơn retention của mọi topic Search tiêu thụ".
- `application.yml` `search.tombstone.protection-window: 30d` (comment ghi "topic là 14d"), nhưng `t.inventory.stock.events.v1` retention là **"30d-minimum"** (Inventory AsyncAPI).
- Hệ quả: sau purge, event availability được replay gặp document không tồn tại → bị coi là vi phạm readiness → Search không ACK → partition kẹt.
- Sửa: tăng window (> 30d, ví dụ 45d) và/hoặc xử lý riêng trường hợp document đã purge; cập nhật comment + docs.

### S6 🔴 [BUG local] URL Product sai cổng
- `application.yml`: `export.product-base-url: ${PRODUCT_INTERNAL_URL:http://product-service:8080}`. `docker-compose.yml` (service `search-service`) không đặt `PRODUCT_INTERNAL_URL`. Product chạy cổng 8081 (Product `server.port`, traefik label 8081; PDP/Promotion dùng `:8081`).
- Hệ quả: full reindex lỗi khi gọi export Product/Category.
- Sửa: đổi default sang `:8081` và/hoặc thêm env vào compose.

### S7 🟡 CDN base URL mặc định có dấu `/` cuối
- `media.cdn-base-url` mặc định `https://cdn.local/`; quy ước (Cart/PDP ops) là không có `/` cuối và phải bằng nhau giữa Cart/PDP/Search/Checkout. Compose override đúng (`http://localhost:8088/media`).

### S1 🟡 README tự mâu thuẫn
- §1 "Service đang running ... HTTP v1 legacy đã gỡ hoàn toàn" vs §9 "OpenAPI v2 ... provider/frontend chưa migrate" và "Cross-service contract chưa được implement ... cần producer implementation ... rồi mới chuyển Search khỏi adapter legacy" (Product/Inventory/Review producer đều đã có, không còn adapter legacy).
- §6 dòng Product Kafka: "Contract: Cần tạo: AsyncAPI product" — đã có `contracts/events/product/v1/asyncapi.yaml`.

### S2 🟡 Docs nói gateway còn route v1
- `interfaces.md` §2 "Gateway hiện định tuyến `GET /api/v1/search/**` là public-read"; §6 "Frontend/Gateway còn dùng route v1 là migration gap". `docs/gateway/runtime-contract.md` + compose chỉ route `/api/v2/search/*` và nói router v1 đã bỏ.

### S3 🟡 Glossary cursor cũ
- `domain.md` glossary: "Cursor = chuỗi base64 mã hoá sort values của hit cuối" — hiện là signed cursor generation 2 mang PIT + query chuẩn hoá (interfaces, ADR-020).

### S5 🟡 `facet_definitions` vẫn chưa có (xem P4)
- Search tự dựng `code/label` từ token (đã case-fold, bỏ dấu). Gap được ghi ở cả hai phía.

Đã khớp: 6 endpoint == OpenAPI v2; mapping ES + painless script trong `docs/search/schema` giống hệt từng byte resource của code; topic; header `X-Service-Identity: search-service`; bearer `REVIEW_INTERNAL_TOKEN` cho Review export.

---

## 11. Product Detail BFF (`docs/product-detail-bff`, `contracts/http/product-detail-bff/v1`, `java/services/product-detail-bff`)

Docs ↔ code khớp tốt.

### B1 🟡 `docs/README.md` §7 ghi "(target)" — đã implement.
### B2 🟡 `operations.md` ví dụ `PDP_PRODUCT_BASE_URL = http://product-service:8080` — Product chạy 8081 (compose dùng 8081).

Đã khớp: endpoint, regex slug, mã 404/503, `Retry-After`, deadline 1000ms / Product 400ms / Inventory+Review 300ms, bulkhead, circuit breaker, `preview_limit` 3 (tối đa 5 khớp Review contract), `X-Correlation-ID` gửi Inventory, `X-Service-Identity`, bearer token Review.

---

## 12. Order (`docs/order`, `contracts/http/order/**`, `contracts/events/order/v1`, `java/services/order-service`)

### O3 🔴 [SYSTEM GAP] Order phụ thuộc Delivery service không tồn tại
- Order chỉ `PROCESSING → COMPLETED` (T8.3, INV-ORD-006) khi mọi line có `com.ecommerce.delivery.order-line.delivered.v1`. Buyer return (INV-ORD-014) yêu cầu mọi line `DELIVERED`. Kéo theo: event `order.completed.v1` (Review entitlement) và notification `ORDER_COMPLETED`.
- Không có provider Delivery; `docs/delivery/README.md` đang bị xoá; Order vẫn consume `t.delivery.order.events.v1` (`application.yml` `delivery-topic`).
- Hệ quả: hiện không đơn nào lên `COMPLETED` → không có review, không trả hàng/hoàn tiền buyer.
- Sửa (cần quyết định + ADR): (a) khôi phục boundary Delivery docs + làm provider; hoặc (b) cơ chế tạm (admin/system xác nhận giao hàng, hoặc coi physical dispatch + thời gian là delivered) và cập nhật Order domain/workflows/contract.

### K1/K2 (cross Checkout) — xem mục Checkout.

### O1 🟡 README cũ / tự mâu thuẫn
- §1 còn câu boilerplate "Mức áp dụng contract liên service chưa được đánh giá trong lần tái cấu trúc này" ngay dưới dòng nói contract đã implemented.
- §6: PostgreSQL "Logical design, chưa có migration evidence"; Debezium "Cấu hình chưa có evidence" — đã có `V1__order_baseline.sql` và `debezium-connector-order-outbox.json` (README §1 nói đã smoke test).
- §9: "Checkout Service ... chưa có runtime" — Checkout đã implement.

### O2 🟡 `interfaces.md` §1
- Promotion activities "contract target exists" — README ghi implemented.

Đã khớp: path public/internal == OpenAPI; HTTP client tới Inventory/Promotion/Payment (path, `Idempotency-Key`, `X-Correlation-ID`, `X-Causation-ID`, `X-Service-Identity`); schema `order-paid`/`order-completed` khớp consumer Cart/Review; config (window 15m, skew 30s, submit wait 12s, outbox 15d, Redis mirror 7d, search 90d) == operations.md; mapping `cancel_reason` → Inventory release reason và → Payment cancel reason khớp contract; Order gửi `checkout_channel=CHECKOUT` khi Reserve Promotion.

---

## 13. Payment (`docs/payment`, `contracts/http/payment/**`, `contracts/events/payment/**`, `java/services/payment-service`)

### PY1 🔴 [BUG] Thiếu hai endpoint đọc
- Contract `contracts/http/payment/v1/openapi.yaml` + `docs/payment/interfaces.md` §2.8 + README capability "Tra cứu": `GET /v1/payments/{transactionId}`, `GET /v1/refunds/{refundTransactionId}`.
- Code `adapter/in/http/PaymentController.java` chỉ map `POST /initiate`, `POST /{id}/cancel`, `POST /{id}/refund`, `GET /{id}/client-data` (+ webhook).
- Order gọi `GET /v1/payments/{id}` trong `service/ReconciliationDecisionService.java` (quyết định external settlement cần trạng thái Payment mới nhất) và có `getRefund` trong `client/HttpGateways.java` → 404/405 khi chạy.
- Sửa: implement 2 endpoint theo schema `PaymentStatus`/`RefundStatus` (contract đã có field `disputes`), thêm test.

### PY2 🟡 Nhãn trạng thái
- `services.yaml` (java-planned/documented), `interactions.yaml` (`payment-publishes-payment-events`, `stripe-delivers-payment-webhook` proposed), `docs/README.md` §3 ("chưa xác minh implementation") vs Payment README "implemented, Gate 3".
- `x-adoption-status: implemented` chỉ có trong working copy chưa commit; `contracts/http/payment/internal/` đang untracked.

### PY3 🟡 `contracts/generated/*` là output bị gitignore
- Do `npm run generate:payment` sinh ra, bị `.gitignore`. Không nên coi là contract; có thể cũ so với nguồn.

Đã khớp: 12 bảng trong V1 == data.md; outbox `aggregate_type` luôn `Payment` → `t.payment.events.v2`, key = `order_id`; 15 event type (dispute/refund tạo bằng suffix); cancel reason/outcome khớp Order; header `X-Correlation-Id`; webhook `/v1/payments/webhook/stripe` + topic nội bộ `t.payment.webhooks.raw`; session 15m; cửa sổ webhook 300s / 256KiB; retention 7 năm/90 ngày/12 tháng/outbox 15 ngày; toàn bộ `payment.*` trong `application.yml` == bảng `operations.md`.

---

## 14. Checkout (`docs/checkout`, `contracts/http/checkout/v1`, `java/services/checkout-service`)

Docs mới nhất, khớp code.

### K1 🟠 [cross Order] Mã `SUBMISSION_IN_PROGRESS` chưa có trong contract Order
- Order trả `503 SUBMISSION_IN_PROGRESS` khi hết 12 giây chờ mà submission vẫn `PREPARING` (`order/common/ErrorCode.java`, `CheckoutSubmissionService.java`). Checkout dựa vào mã này để trả `202 PREPARING` thay vì `RESULT_UNKNOWN`.
- Order internal OpenAPI và `docs/order/interfaces.md` §2.2 chỉ nói "Timeout hạ tầng trả 503 retryable" (chỉ `docs/order/development.md` nhắc tới mã).
- Sửa: thêm mã vào Order OpenAPI (503 response) và interfaces.md.

### K2 🟠 [cross Order] Order POST có thể trả 409 hai mã chưa ghi
- `CheckoutSubmissionService.failure()` trả lại failure đã lưu, gồm `CHECKOUT_PAYMENT_WINDOW_EXHAUSTED`, `ORDER_CANCELLED_DURING_PREPARATION`. Order OpenAPI 409 chỉ liệt kê `PROMOTION_RECALCULATION_NEEDED`, `INVENTORY_RESERVATION_REJECTED`, `PAYMENT_INITIATION_REJECTED`; `docs/order/interfaces.md` gán hai mã kia cho recovery read. Checkout docs/code đã đúng (xử lý trên POST).
- Sửa: bổ sung vào Order OpenAPI + interfaces.md.

### K3 🟡 Nhãn trạng thái
- `architecture.md`/`domain.md` frontmatter `status: proposed`; `interfaces.md` dòng "Voucher options | `proposed`" (Promotion đã implement); các interaction của Checkout trong catalog vẫn `proposed`.

### K4 🟡 Known gap đã ghi (để nhớ khi sửa phía provider)
- Rate limit internal User theo IP 600/phút chung cho mọi pod (U10).
- Product chưa có nhãn biến thể → Order `variant_name` null → ảnh hưởng Review/Notification.
- User snapshot không có tên tỉnh/huyện/xã → Order/Notification dùng mã.
- `user_tier` luôn omit.

Đã khớp: 4 endpoint public; config (deadline 5s/1.5s/20s, timeout, bulkhead, CB, TTL 20m/30m/5m/30s/2m/24h, cờ voucher options mặc định false + compose true) == operations.md; header gửi đi (`X-Service-Identity`, `X-Correlation-ID`, `X-Causation-ID` tới Inventory; `Idempotency-Key` = token tới Order); `checkout_channel=CHECKOUT`.

---

## 15. Notification (`docs/notification`, `contracts/events/notification/v1`, `contracts/http/notification/**`, `go/services/notification-service`)

### N1 🔴 [cross User, compliance] Gửi marketing khi user chưa đồng ý
- Xem U4. `docs/notification/08-decide.md` §5: thiếu preference → `marketing_enabled=false` ("gửi marketing cho người chưa từng đồng ý là rủi ro compliance"). User nguồn mặc định `true` và reconciliation đẩy `true` sang → marketing (vd `CART_ABANDONED` lane bulk) được gửi cho user chưa opt-in.
- Sửa: thống nhất mặc định với User (ưu tiên `false` ở nguồn).

### N3 🔴 Internal API dùng app-level mTLS (xem X2)
- `internal/api/router/internal.go`: không phải dev + không có app mTLS → log "every status/ops call will be rejected". Mesh docs nói Istio ambient thực thi, app không thấy cert → production sau mesh sẽ từ chối mọi call status/ops nếu không cấu hình TLS ở app.

### N4 🟡 Client gọi Auth không gửi `X-Service-Identity`
- `internal/infrastructure/auth/client.go` không set header `X-Service-Identity: notification-service`, trong khi Auth internal OpenAPI nói caller gửi để attribution. Không gây lỗi (Auth bỏ qua header).

### N5 🟡 Tên principal ops không thống nhất
- `interactions.yaml` `notification-ops-operates-notification`: consumers `[finance-ops]` ("acting with the notification-ops principal"); mesh matrix + `configs/application.yaml` `ops_principals: [notification-ops]`. `notification-ops` không có trong `services.yaml`.

### N6 🟡 DLQ dùng chung giữa consumer
- User và Notification cùng đẩy message lỗi của `t.auth.events` vào `t.auth.events.dlq` (Auth AsyncAPI định nghĩa một DLQ cho channel). Khi redrive phải lọc theo consumer group — chưa được ghi ở docs nào.

### N2 🟡 Nhãn trạng thái
- README "**Trạng thái:** `proposed`" dù mã phase 1 đã có (19k dòng); `services.yaml` go-planned/documented; interactions `notification-*` proposed.

Đã khớp: consumer `t.auth.notification.events` (group `notification-auth-critical`), `t.auth.events` (user lifecycle), `t.review.events`, `t.cart.events`, `t.user.events`, `t.notification.{critical,transactional,bulk}.v1`; producer `t.notification.outcomes.v1`; route inbox + internal == OpenAPI; 15 template trong `templates/catalog.yaml` == code; path Auth contact/content; schema `ORDER_*` khớp quy tắc composer của Order (enum `reason`, `refund_expected=false`).

---

## 16. Vấn đề xuyên suốt

### X2 🔴 Hai mô hình xác thực internal không tương thích
- Contract + `docs/mesh/runtime-contract.md`: Istio ambient thực thi mTLS + AuthorizationPolicy theo principal; service **không** thấy cert; `X-Service-Identity` chỉ để attribution/defense-in-depth.
- Service Go (Auth, User, Notification): kiểm mTLS/CN ở tầng app, production bắt buộc cert, bỏ qua header.
- Service Java (Product, Promotion, Inventory, Order, Checkout, PDP, Search...) và Cart (Go): kiểm allowlist header `X-Service-Identity` sau mesh.
- Sửa: chọn một mô hình (khuyến nghị theo mesh: Go chuyển sang allowlist header như Cart/Java), viết ADR chung, cập nhật code + contract của Auth/User/Notification.

### X1 🟡 Mesh matrix không thực sự dẫn xuất từ catalog
- `docs/mesh/runtime-contract.md` §5.2 nói dẫn xuất từ `interactions.yaml`, nhưng có dòng không có trong catalog (Product snapshot cho `promotion-service` — P5) và tên principal khác (N5). Đồng bộ lại sau khi sửa G5.

### X3 🟡 Tên mã lỗi thiếu identity không thống nhất
- Auth/User: `UNAUTHORIZED`; Cart/Product/Promotion/Inventory/Checkout: `UNAUTHENTICATED`. Chọn một tên trong `contracts/conventions/http.md`.

### X4 🟡 Bảng mã lỗi không được liệt kê trong OpenAPI
- Đa số OpenAPI chỉ mô tả response, không enum `code`; docs domain thiếu nhiều mã (A1, U3, C8, P3, PR7). Đề xuất mỗi service có "error catalog" đầy đủ (code, HTTP, retryable) trong `interfaces.md`, như Inventory và Checkout đã làm.

---

## 17. Checklist sửa (đánh dấu khi xong)

### Code
- [ ] PY1 Payment: thêm `GET /v1/payments/{id}`, `GET /v1/refunds/{id}`.
- [ ] PR8/PR10 Promotion: guard technical-cancellations (allowlist migrator, proof header, DTO, migration cột) hoặc gỡ route.
- [ ] PR2 Promotion: enforce `checkout_channel=CHECKOUT` ở calculate + reserve.
- [ ] A9 Auth: luôn phát `USER_DELETED` / `USER_EMAIL_CHANGED` / `USER_PASSWORD_CHANGED` sau commit dù Redis lỗi.
- [ ] A12 Auth: resend OTP không trả 429 phân biệt.
- [ ] A13 Auth + gateway-auth: so sánh `revoked_before` không chặn token cùng giây.
- [ ] A11/U10 Auth/User: rate limit internal theo principal.
- [ ] U9 User: chặn đọc/ghi profile/address/preferences của user đã xoá.
- [ ] U4/N1 User: mặc định `marketing_enabled=false` (migration mới) hoặc consent.
- [ ] U11 User: chống race khi fill cache address (tuỳ chọn).
- [ ] C11 Cart: TTL idempotency upsert lấy từ config (sửa cả `docs/cart/scripts/upsert_item.lua`).
- [ ] S4 Search: tăng tombstone protection window > 30d / xử lý document đã purge.
- [ ] S6 Search: sửa default URL Product `:8081` + env compose.
- [ ] S7 Search: bỏ `/` cuối ở default CDN URL.
- [ ] P5 Product: tách allowlist snapshot cho Promotion.
- [ ] P7 Product: build proto từ `contracts/`.
- [ ] N4 Notification: gửi `X-Service-Identity` khi gọi Auth.
- [ ] X2: thống nhất mô hình xác thực internal (Auth/User/Notification).
- [ ] O3: quyết định + implement đường tới `DELIVERED`/`COMPLETED`.

### Contract / catalog
- [ ] PR4 sửa tham số `LegacyMigrationProof`.
- [ ] K1/K2 bổ sung mã vào Order internal OpenAPI.
- [ ] C7 Cart OpenAPI: mô tả đúng allowlist header + response 400.
- [ ] C6 Cart OpenAPI `servers`; PR6 Promotion OpenAPI `servers`.
- [ ] U8 User OpenAPI `maxItems: 50` cho `AddressList`.
- [ ] C9 Cart OpenAPI 429 cho GET.
- [ ] G3 `services.yaml`; G4 `ADOPTION.md`; G5 `interactions.yaml` (C1, P5, U5, N5, K3, PY2, N2).
- [ ] X1 đồng bộ mesh matrix sau khi sửa catalog.

### Docs
- [ ] G1/G2 `docs/README.md` (Delivery link, trạng thái, số ADR, số operation).
- [ ] Auth: A1–A8, A14.
- [ ] User: U1–U3, U6, U7, U8.
- [ ] Cart: C2–C5, C8–C10, C12.
- [ ] Product: P1–P4, P8.
- [ ] Promotion: PR1–PR3, PR5–PR7, PR10, README §9.
- [ ] Inventory: I1, I2.
- [ ] Review: R1, R2.
- [ ] Search: S1–S3, S5.
- [ ] PDP BFF: B1, B2.
- [ ] Order: O1, O2, K1, K2.
- [ ] Payment: PY2, PY3.
- [ ] Checkout: K3.
- [ ] Notification: N2, N5, N6.
- [ ] X3/X4: thống nhất tên mã lỗi và error catalog.
