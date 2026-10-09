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
| Đồng bộ tốt nhất | Inventory, Review, Product Detail BFF, Checkout: code khớp contract gần như từng route, mã lỗi và config. Review/Search/Cart có schema, script, Lua trong docs giống hệt từng byte với code. *(Inventory: nhận định đúng về route/mã lỗi/config, nhưng kiểm tra lại 2026-10-09 tìm thêm I3–I11 gồm 1 lỗ hổng bảo mật và 3 bug runtime — đã sửa.)* |
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
| A9 | Auth | Lỗi Redis sau commit → `USER_DELETED` không bao giờ được phát — ✅ đã kiểm chứng và sửa 2026-10-09 (ADR-006) |
| N1 / U4 | User + Notification | User mặc định `marketing_enabled=true`, Notification mặc định `false` → gửi marketing khi chưa đồng ý |
| U9 | User | Profile đã xoá (ẩn danh) vẫn ghi lại được PII |
| S4 | Search | Tombstone window 30d không dài hơn retention topic Inventory (≥30d) → partition có thể kẹt *(phía Inventory đã sửa 2026-10-09: retention đúng 30d, xem I8; Search chưa)* |
| I3 | Inventory | Kiểm role ADMIN/allowlist trên URI thô → né được bằng `%xx`/`;` *(đã sửa 2026-10-09)* |
| S6 | Search | URL Product mặc định `:8080`, Product chạy `:8081` → full reindex lỗi ở compose |
| X2 (A10/U10/N3) | Auth, User, Notification | App-level mTLS mâu thuẫn mô hình mesh; production sau Istio ambient sẽ từ chối mọi internal call — ✅ Auth (A10, ADR-007) và User (U10) đã sửa 2026-10-09; ⏳ còn Notification (N3) |

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
- `notification-publishes-outcomes` liệt kê `user-service` là consumer `complaint-received`, nhưng User chưa có consumer (U5). *(đã xong 2026-10-09: User consume complaint-received, catalog cập nhật)*
- `notification-ops-operates-notification` ghi consumer `finance-ops` trong khi mesh/code dùng principal `notification-ops` (N5).
- Các interaction của Checkout (`checkout-calculates-promotions`, `checkout-lists-voucher-options`, `checkout-previews-inventory-allocation`, `checkout-reads-user-address-snapshot`) vẫn `proposed` dù hai phía đã implement (K3).
- `payment-publishes-payment-events`, `stripe-delivers-payment-webhook`, các `notification-*` vẫn `proposed` (PY2, N2).

---

## 3. Auth (`docs/auth`, `contracts/http/auth/**`, `contracts/events/auth/v1`, `go/services/auth-service`)

> **Kiểm chứng và sửa xong 2026-10-09 (mục Auth).** Đối chiếu lại từng mục với code, contract và test chạy thật
> (unit + integration trên PostgreSQL/Redis thật). Kết quả: **A9–A12 và A1–A8, A14 đều đúng**; **A13 đúng về hành vi nhưng
> là chủ ý** (OpenAPI đã ghi "trước hoặc đúng mốc", gateway có comment giải thích; độ nghiêm trọng bị thổi phồng vì
> client chỉ gặp một lần `401` rồi refresh), nên chỉ sửa docs/contract, không sửa code; **N4 (Notification) sai**:
> `client.go:294` đã gửi `X-Service-Identity` và cấu hình mặc định `notification-service`. Quyết định ở Auth
> ADR-006..009 và `contracts/CHANGELOG.md` (2026-10-09 Auth sync). Bổ sung ngoài báo cáo (đều đã sửa trừ khi ghi khác):
> 1. **Lost update ở `auth.accounts`** (nặng hơn mọi mục 🔴 của Auth): `Update` ghi lại cả hàng từ bản đọc cũ, mà login
>    verify Argon2 ~100 ms giữa lúc đọc và lúc lưu. Một login (đúng hoặc sai mật khẩu) chạy chồng lên đổi/reset mật khẩu ghi lại
>    `password_hash` cũ; chồng lên xóa account đưa hàng `DELETED` về `ACTIVE` kèm email sau khi `USER_DELETED` đã phát; hai login sai
>    song song cùng ghi `failed_login_count = 1` nên lọt dưới ngưỡng khóa. Đã sửa bằng compare-and-set trên `updated_at`
>    ([ADR-008](../docs/auth/decisions/ADR-008-account-optimistic-concurrency.md)); test đua + test PostgreSQL thật, mutation check.
> 2. **Đăng nhập lộ trạng thái chưa cần mật khẩu**: `EnsureCanAuthenticate` chạy trước `Verify`, nên ai cũng biết một email đang
>    chờ xác minh (`403`) hoặc bị khóa (`423`). Nay kiểm mật khẩu trước ([ADR-009](../docs/auth/decisions/ADR-009-login-password-before-state.md)).
> 3. **`USER_UNLOCKED` có thể không bao giờ phát**: login/refresh tự mở khóa im lặng ("worker owns that event") nhưng sau đó
>    worker không còn thấy account. Nay bên thắng ghi `LOCKED → ACTIVE` phát event, đúng một lần (ADR-009).
> 4. **A12 cũng đúng với `password-reset-requests`**: 429 theo account chỉ xuất hiện khi account tồn tại (code ghi chú sai rằng
>    "chỉ chủ hộp thư thấy"). Đã sửa cùng A12.
> 5. **`docs/gateway/runtime-contract.md` §6 mâu thuẫn code**: bắt denylist TTL ≤ thời gian còn lại đến `exp`, trong khi gateway
>    chỉ chuyển `jti` nên Auth phải dùng cận trên (access TTL + skew). Đã sửa contract gateway theo code; thêm câu về độ phân giải 1 giây.
> 6. Hợp đồng OpenAPI: thiếu `400` ở `listSessions`, `revokeSession` và hai thao tác xóa account; 7 thao tác công khai thiếu
>    `security: []` (Redocly báo 7 lỗi `security-defined`); thiếu `servers`. Đã sửa; Redocly và `asyncapi validate` sạch.
> 7. `docs/auth` còn nhắc consumer, Kafka lag, DLQ phía Auth (Auth không consume event nào) ở architecture, operations và
>    bảng dependency; ADR-001..005 ghi "chưa có implementation evidence". Đã sửa.
> 8. **Còn mở**: (a) Root Compose không nạp schema Auth (`init-db.sh` chỉ tạo `auth_db` rỗng, không có `auth-migrate` như
>    `user-migrate`) — đã ghi vào `docs/auth/development.md` cách nạp tay, chưa đổi compose vì migration của Auth không idempotent và
>    sẽ vỡ volume đã nạp tay; (b) metric/alert/runbook/reconciliation cho event (ADR-005) và Prometheus metric; (c) Notification
>    còn mTLS ở tầng app (N3) và các cấu hình `NOTIFICATION_AUTH_TLS_*` trở thành thừa; (d) lỗi Redis trả `500` vì contract chưa có
>    `503`; (e) X3 (`UNAUTHORIZED` vs `UNAUTHENTICATED`) chưa thống nhất; (f) contract Auth vẫn `proposed` (chưa có test validate
>    response với OpenAPI, chưa có consumer test).


### A9 🔴 [BUG] `USER_DELETED` có thể không bao giờ được phát — ✅ đã kiểm chứng đúng và sửa 2026-10-09 (ADR-006)
- Vị trí: `go/services/auth-service/internal/domain/service/account_deletion_service.go` hàm `delete`.
- Hiện trạng: transaction DB commit (account `DELETED`, ẩn danh, revoke refresh token) → sau commit gọi `revoker.MarkAllTokensRevoked`. Nếu Redis lỗi, hàm **return error trước** `notifier.Publish(USER_DELETED)`. Client nhận 500; retry → `ACCOUNT_NOT_ACTIVE` 409 "already deleted" → event không bao giờ phát. User/Notification/Review/Chat không bao giờ purge PII. Không có reconciliation nào phát lại.
- Pattern tương tự: `email_change_service.go` `Confirm` (email đã đổi, `RevokeAllSessions` lỗi → không phát `USER_EMAIL_CHANGED`), `password_service.go` (đổi/reset password rồi revoke lỗi → không phát `USER_PASSWORD_CHANGED`).
- Sửa đề xuất: luôn publish event sau commit kể cả khi ghi marker lỗi (ghi log/alert riêng cho marker); hoặc cho phép retry idempotent phát lại event khi account đã `DELETED` mà chưa có bằng chứng publish; hoặc chuyển sang outbox (cần ADR thay ADR-005). Cập nhật WF-AUTH-006 + ADR-005.

### A10 🔴 Mô hình xác thực internal API mâu thuẫn với contract/mesh (xem X2) — ✅ đã kiểm chứng đúng và sửa 2026-10-09 (ADR-007)
- Contract `contracts/http/auth/internal/v1/openapi.yaml` (securitySchemes `notificationServiceMtls`): "mTLS và allowlist principal notification-service do service mesh thực thi; **Auth không nhìn thấy chứng chỉ caller**. Caller gửi `X-Service-Identity: notification-service` cho attribution".
- Code: `internal/api/middleware/service_identity.go` kiểm `r.TLS.VerifiedChains` + CN; `internal/common/config/config.go` (~dòng 374) **từ chối khởi động production** nếu internal listener không có cert mTLS ở tầng app; bỏ qua `X-Service-Identity`.
- Hệ quả: sau Istio ambient (app không thấy cert) phải cấu hình TLS hai lớp, nếu không service không khởi động.
- Sửa: chốt một mô hình (X2), cập nhật code hoặc contract + `docs/auth/interfaces.md` §3.

### A11 🟠 Rate limit internal theo IP thay vì theo principal — ✅ đã kiểm chứng đúng và sửa 2026-10-09 (theo cặp service gọi + `delivery_id`/`user_id`, 120/phút, ADR-007)
- Docs `docs/auth/interfaces.md` §3: "Rate limit riêng cho principal `notification-service`", "rate limit theo client `security.rate_limit.internal_per_client`".
- Code: `internal/api/router/internal_router.go` dùng scope `internal-delivery:ip` với `c.ClientIP()`.
- Sửa: key theo service identity, hoặc sửa docs cho đúng.

### A12 🟠 Endpoint gửi lại OTP lộ sự tồn tại của tài khoản — ✅ đã kiểm chứng đúng và sửa 2026-10-09 (vượt ngân sách theo account thì vẫn `202`, không gửi; áp dụng cả `password-reset-requests`)
- Contract `POST /api/v1/auth/email-verification-requests`: "Luôn trả cùng phản hồi thành công để không tiết lộ email có account chờ xác minh hay không".
- Code `registration_service.go` `RequestVerification`: email không tồn tại / không `PENDING` → `nil` (202); tồn tại + `PENDING` + vượt resend limit → `429 RATE_LIMITED`. 429 chỉ xuất hiện khi tài khoản tồn tại → side-channel enumeration.
- Sửa: khi vượt limit vẫn trả 202 (không gửi OTP), hoặc ghi rõ ngoại lệ trong contract.

### A13 🟠 `revoked_before` theo giây gây từ chối token hợp lệ — ⚠️ hành vi có thật nhưng là chủ ý, không phải bug; chỉ sửa docs/contract 2026-10-09
- Code `internal/infrastructure/redis/revocation_store.go` lưu `issuedBefore.Unix()`; gateway-auth từ chối `iat <= revoked_before`.
- Token được cấp trong cùng giây sau "logout all"/đổi password/đổi email bị từ chối tới khi hết hạn (15 phút). Hiếm nhưng có thể gặp khi client tự đăng nhập lại ngay.
- Sửa: lưu mốc millisecond hoặc so sánh `iat < revoked_before`, đồng bộ `docs/gateway/runtime-contract.md` §6.

### A1 🟡 Bảng mã lỗi `domain.md` sai / thiếu — ✅ đúng; đã sửa 2026-10-09 (24 mã gồm `CONCURRENT_UPDATE`, kèm HTTP và retryable; ma trận theo endpoint ở `interfaces.md` §6)
- `docs/auth/domain.md` §4: BR-001 `ACCOUNT_NOT_ACTIVE` (đúng), BR-002 `REFRESH_TOKEN_REVOKED` (code: `REFRESH_TOKEN_INVALID`), BR-003 `SESSION_REVOCATION_FAILED` (không có trong code), BR-007 `ACCOUNT_ALREADY_DELETED` (code trả `ACCOUNT_NOT_ACTIVE` 409 detail "already deleted"; ngoài ra có `ACCOUNT_DELETED`).
- §7 chỉ liệt kê 6 mã; code (`internal/common/errors/errors.go`) có 23: `VALIDATION_FAILED, UNAUTHORIZED, FORBIDDEN, INVALID_CREDENTIALS, ACCOUNT_NOT_VERIFIED, ACCOUNT_LOCKED, ACCOUNT_NOT_ACTIVE, ACCOUNT_DELETED, ACCOUNT_NOT_FOUND, NOT_FOUND, METHOD_NOT_ALLOWED, EMAIL_ALREADY_REGISTERED, REFRESH_TOKEN_INVALID, SESSION_NOT_FOUND, OTP_INVALID, RESET_TOKEN_INVALID, PASSWORD_POLICY_VIOLATION, RATE_LIMITED, DELIVERY_NOT_FOUND, DELIVERY_EXPIRED, SIGNING_KEY_MISSING, DEPENDENCY_UNAVAILABLE, INTERNAL_ERROR`. Mapping HTTP ở `internal/api/httpx/problem.go`.
- Comment `errors.go` "Codes match docs/auth/domain.md section 7" là sai.
- OpenAPI không liệt kê `code` nào.
- Sửa: viết lại §4/§7 theo code (kèm HTTP status); cân nhắc thêm enum `code` vào OpenAPI.

### A2 🟡 TTL denylist mô tả mâu thuẫn — ✅ đúng; đã sửa 2026-10-09 (cả `docs/gateway/runtime-contract.md`)
- `workflows.md` WF-AUTH-004: TTL denylist "không vượt quá thời gian còn lại của access token".
- `data.md` §6 và code `revoker.go`: TTL = access TTL + clock skew (cận trên, có thể sống lâu hơn token).
- Sửa WF-AUTH-004 theo data.md.

### A3 🟡 State machine thiếu transition — ✅ đúng; đã sửa 2026-10-09
- `domain.md` §5: `LOCKED → ACTIVE` chỉ khi hết `locked_until` bởi worker; §6 "auto unlock chỉ do system worker".
- Code: `entity/account.go` `UnlockIfExpired` được gọi inline trong login/refresh (không phát `USER_UNLOCKED`); `ClearLockout` trong reset password chuyển `LOCKED → ACTIVE`.
- Sửa: bổ sung hai transition vào domain/workflows.

### A4 🟡 Mô tả lockout/rate limit sai và thiếu giá trị — ✅ đúng; đã sửa 2026-10-09 (bảng rate limit ở `interfaces.md`, cấu hình ở `operations.md`)
- WF-AUTH-002 "Login sai tăng counter theo email và IP". Code: counter bền vững per-account trong DB (`failed_login_count`) + Redis rate limit `login:ip` và `login:account` (theo email chuẩn hoá).
- Giá trị lockout 5 lần / khoá 15 phút / reset counter sau 1 giờ (`configs/application.yaml`) không có trong docs.

### A5 🟡 Docs nói "chưa có implementation / TBD" — ✅ đúng; đã sửa 2026-10-09 (`scope: current`, `status: implemented`)
- Frontmatter `status: proposed`, README "chưa được xác minh theo implementation"; `development.md` "refactor này không xem code ... không command nào được bịa ra"; `operations.md` mọi giá trị "TBD".
- Code + config đã có giá trị cụ thể: access 15m, refresh sliding 7d / absolute 30d, OTP 5m / 5 lần / resend 3 trong 15m, reset 15m, delivery TTL 30m, key rotation 30d, retirement 24h, interval các worker, cổng 8080/8081.
- Sửa: viết lại development.md/operations.md theo code (tham khảo cách User docs làm).

### A6 🟡 Diagram thiếu consumer — ✅ đúng; đã sửa 2026-10-09
- `architecture.md` diagram: Kafka → chỉ User, Notification. Thiếu Review, Chat (consumer `USER_DELETED`).

### A7 🟡 Sai hướng / SLI thừa — ✅ đúng; đã sửa 2026-10-09
- `interfaces.md` §1 dòng JWKS ghi hướng "Outbound" — Gateway gọi Auth (inbound HTTP).
- `operations.md` có SLI "Kafka consumer lag" dù Auth không consume event nào.

### A8 🟡 WF-AUTH-006 nói "trong transaction ... đặt revoke marker" — ✅ đúng; đã sửa 2026-10-09
- Code ghi marker Redis sau commit (không thể nằm trong transaction PostgreSQL). Sửa mô tả.

### A14 🟡 Lỗi policy password khi đăng ký trả 400 — ✅ đúng; đã ghi vào docs/contract 2026-10-09
- Register không có 422 trong contract nên vi phạm policy trả `400 VALIDATION_FAILED` — hành vi đúng nhưng chưa được ghi.

Đã khớp (theo báo cáo gốc; lần kiểm chứng 2026-10-09 xác nhận, và bổ sung các điểm lệch ở khối ghi chú đầu mục này): route/status == OpenAPI; event type/topic/key/field == AsyncAPI; `docs/auth/schema/0001_initial_auth_schema.sql` == migration; cookie `auth_refresh` path `/api/v1/auth/sessions`; JWKS RS256.

---

## 4. User (`docs/user`, `contracts/http/user/v1`, `contracts/events/user/v1`, `go/services/user-service`)

> **Kiểm chứng và sửa xong 2026-10-09 (mục User).** Đối chiếu lại từng mục với code: **U1–U11 đều đúng**,
> không mục nào sai. Ghi chú theo mục bên dưới; chi tiết quyết định ở User ADR-008..012 và
> `contracts/CHANGELOG.md` (2026-10-09 User sync). Bổ sung ngoài báo cáo:
> - U9 còn một race thứ hai: `POST /addresses` đã qua bước kiểm profile có thể commit *sau* khi
>   `Anonymize` xoá address book → address mồ côi của account đã xoá. Đã chặn bằng cách cho `Anonymize`
>   lấy cùng advisory lock theo user. Preferences vốn đã `404` (row bị xoá); `GET /addresses` trả `200 []`
>   cho account đã xoá nay là `404`.
> - U5 nặng hơn mức 🟠: do User không consume `complaint-received`, lần user đổi bất kỳ preference nào
>   sau khi báo spam, marketing bật lại. Đã implement consumer.
> - Frontend `AddressCard` chặn xoá mọi địa chỉ default nên U7 không dùng được từ UI → đã sửa
>   (`isOnlyAddress`).
> - Còn mở: backfill migration `0002` không tắt marketing của user đã đổi cờ email/in-app (không phân
>   biệt được với opt-in thật); Auth/Notification còn mTLS ở tầng app (X2); rate limiter internal
>   của User vẫn fail closed khi Redis lỗi.

### U9 🔴 [BUG] Profile đã xoá vẫn ghi lại được PII — ✅ đã kiểm chứng đúng và sửa 2026-10-09
- Sau `USER_DELETED`, `LifecycleService.Anonymize` giữ row profile (ẩn danh) tới hard-delete.
- `ProfileService.Get/Update` (`internal/domain/service/profile_service.go`) và `AddressService.Create` không kiểm `user_deletions`; repo `profile_repo.go` cũng không lọc.
- Hệ quả: GET trả 200 profile ẩn danh; PATCH ghi lại tên/ngày sinh vào profile ẩn danh và phát `USER_PROFILE_UPDATED` sang Chat; POST address tạo address chứa PII cho user đã xoá. Trái `docs/user/domain.md` §8 ("request update phải bị từ chối hoặc không tìm thấy profile đã anonymize"). Chỉ được che bởi revocation ở gateway — vốn có thể hỏng (A9).
- Sửa: coi user có `user_deletions` là `PROFILE_NOT_FOUND` 404 ở mọi đường đọc/ghi (kể cả preferences/address), hoặc thêm cờ trên profile; thêm test.

### U4 🔴 Mặc định `marketing_enabled` mâu thuẫn với Notification (xem N1) — ✅ đã kiểm chứng đúng và sửa 2026-10-09
- `docs/user/data.md` + migration `0001`: `marketing_enabled DEFAULT TRUE`; bootstrap tạo preferences mặc định và **không phát** `USER_PREFERENCES_UPDATED`.
- `docs/user/interfaces.md` §4 và `docs/notification/08-decide.md` §5: khi chưa có preference thì marketing **tắt** vì lý do compliance.
- Reconciliation hằng ngày của User phát snapshot hiện hành (`marketing_enabled=true`) → trong 24h Notification bật marketing cho user chưa từng đồng ý.
- Sửa: thống nhất mặc định `false` ở nguồn User (migration mới + docs), hoặc thêm cơ chế consent; cân nhắc phát `USER_PREFERENCES_UPDATED` khi bootstrap.

### U10 🔴 Internal checkout-snapshot dùng app-level mTLS (xem X2) — ✅ đã kiểm chứng đúng và sửa 2026-10-09
- Contract `checkoutService`: mesh thực thi, "User không nhìn thấy chứng chỉ caller", caller gửi `X-Service-Identity`.
- Code `internal/api/router/internal_router.go` + `middleware/service_identity.go`: kiểm CN tầng app; production bắt buộc cert. Rate limit `internal-checkout:ip` theo IP (Checkout README cũng ghi gap: 600 req/phút dùng chung cho mọi pod Checkout → 429 → Checkout trả 503).

### U5 🟠 Catalog khai User là consumer `complaint-received` nhưng chưa có — ✅ đã kiểm chứng đúng và sửa 2026-10-09
- `interactions.yaml` `notification-publishes-outcomes`: consumers `[analytics-service, user-service]`.
- `docs/user/interfaces.md` §1: "proposed — User chưa đăng ký consumer". Code: consumer chỉ đọc `t.auth.events`.
- Sửa: bỏ khỏi catalog cho tới khi implement, hoặc implement consumer.

### U7 🟠 Không xoá được địa chỉ duy nhất — ✅ đã kiểm chứng đúng và sửa 2026-10-09
- BR-004 cấm xoá địa chỉ default và không có thao tác bỏ default → user chỉ có một địa chỉ không bao giờ xoá được (chỉ sửa được). Docs không nêu.
- Sửa: cho phép xoá default khi là địa chỉ cuối cùng, hoặc ghi rõ quyết định.

### U8 🟠 Giới hạn 50 địa chỉ không có trong docs/contract — ✅ đã kiểm chứng đúng và sửa 2026-10-09
- Code `internal/common/constants/constants.go` `AddressBookLimit = 50`, vượt → `INVALID_ADDRESS` 422.
- OpenAPI `AddressList.addresses` không có `maxItems` (vi phạm `contracts/conventions/data-types.md` "List MUST có maxItems").

### U11 🟠 Race cache-aside có thể giữ list cũ tới 12h — ✅ đã kiểm chứng đúng và sửa 2026-10-09
- `AddressService.List`: đọc DB rồi `Put` cache; nếu một mutation `DEL` cache xen giữa, list cũ được ghi lại và sống tới TTL 12h mà không có lỗi nào. Docs chỉ nói stale "giữa commit và invalidation, hoặc tới TTL nếu DEL thất bại".
- Sửa: version/compare-and-set khi fill cache, hoặc ghi rõ rủi ro.

### U1 🟡 `data.md` nói chưa có migration — ✅ đã kiểm chứng đúng và sửa 2026-10-09
- Header: "Chưa tạo migration/schema machine-readable"; §3 "cần tạo migration trước khi đánh dấu implemented". Thực tế `migrations/0001_initial_user_schema.up.sql` có và khớp data.md; README cũng nói đã có.

### U2 🟡 Trạng thái mâu thuẫn — ✅ đã kiểm chứng đúng và sửa 2026-10-09
- `architecture.md` header "cấu trúc mục tiêu, không phải implementation đã xác minh" và §7 "Tất cả vẫn là proposed" — trong khi ADR index đều "Đã chấp thuận", README nói runtime đã có.

### U3 🟡 Bảng lỗi thiếu mã — ✅ đã kiểm chứng đúng và sửa 2026-10-09
- BR-003 dùng `DEFAULT_ADDRESS_CONFLICT` nhưng §7 không liệt kê. Code còn có `INVALID_PROFILE_FIELD`, `INVALID_ADDRESS` (422), `VALIDATION_FAILED`, `UNAUTHORIZED`, `RATE_LIMITED`, `DEPENDENCY_UNAVAILABLE`... Comment `errors.go` "Codes match docs/user/domain.md section 7" sai.

### U6 🟡 Payload event mô tả thiếu — ✅ đã kiểm chứng đúng và sửa 2026-10-09
- WF-USER-002: `USER_PROFILE_UPDATED` "chỉ với user_id và sender_name" — contract còn `updated_at`.

Đã khớp: route/status/schema; event type/topic/key/payload; consumer retry 10 lần 1s→5m → DLQ; purge mỗi giờ batch 100 `SKIP LOCKED`; reconciliation hằng ngày; cache key/TTL 12h, empty 15m; migration == data.md.

---

## 5. Cart (`docs/cart`, `contracts/http/cart/v1`, `contracts/events/cart/v1`, `go/services/cart-service`)

> **Đã kiểm chứng và sửa xong ngày 2026-10-09.** Đối chiếu từng mục C1–C12 với docs, contract và code:
> cả 12 mục đều đúng, không mục nào sai. Đã sửa code (C11, C12, `dataschema`), contract, catalog và docs;
> chi tiết ở CHANGELOG `2026-10-09 (Cart sync)` và [Cart ADR-014](../docs/cart/decisions/ADR-014-purge-cart-on-user-deleted.md).
> Kiểm chứng thêm: 15 test mới trong `go/services/cart-service` (script chạy cả miniredis lẫn Redis thật), mutation check
> cho C11 (test fail khi hard-code lại `86400`). Phát hiện bổ sung ngoài báo cáo (đều đã sửa):
> 1. `operations.md` ghi env `CART_ACTIVE_TTL`, `CART_BUCKET_COUNT`… nhưng env thật là `CART_CART_*` (khóa nằm dưới phần `cart:`
>    của YAML); đã sửa và thêm test.
> 2. Docs/contract hứa `409` kèm `Retry-After` cho request "đang xử lý"; không tồn tại trạng thái này vì ghi là nguyên tử.
> 3. Relay đóng `dataschema` tuyệt đối (`https://contracts.ecommerce.local/...`) trong khi ví dụ chuẩn
>    `contracts/examples/events/cart-abandoned.v1.json` và mọi service khác dùng dạng root-relative; đã sửa code theo contract.
> 4. File ADR-001/002/012 ghi `status: accepted` dù đã bị thay thế; ADR-005/007/008 bị index ghi nhầm `Superseded` (ADR-011 chỉ
>    điều chỉnh một phần); `data.md` thiếu `abandoned_at_ms` và việc DELETE kích hoạt lại cart `ABANDONED`.
> 5. `CartItem.image_url` luôn có mặt trong response nhưng không nằm trong `required` của OpenAPI.

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

> **Kiểm chứng và sửa xong 2026-10-09 (mục Product).** Đối chiếu lại từng mục với code, migration, OpenAPI,
> AsyncAPI và compose: **P1–P5, P7, P8 đều đúng**; P4 được xử lý bằng cách implement (không hạ thành known gap)
> theo [Product ADR-019](../docs/product/decisions/ADR-019-category-facet-definitions.md). Test: chạy toàn bộ 159 test của
> `product-service` trên PostgreSQL thật, 0 fail; `ProductPropertiesBindingTest` (2 test) thêm sau và pass riêng;
> `schema.sql` và V1–V3 cho cùng
> catalog (141 dòng cột/constraint/index, so bằng `information_schema`/`pg_constraint`/`pg_indexes`);
> OpenAPI qua Redocly lint, AsyncAPI qua `asyncapi validate`, 6 example catalog validate với JSON Schema.
> Bổ sung ngoài báo cáo (đều đã sửa):
> - **P5 nặng hơn mô tả:** `reindex-clients` khớp theo tiền tố `/internal/v1/products/` và `/internal/v1/categories/`, nên
>   Promotion đọc được cả hai export (cả Product `DRAFT`/`HIDDEN`), không chỉ snapshot. Đã tách nhóm `snapshot-clients`.
> - **ADR-013 sai so với code:** ADR, `interfaces.md`, `workflows.md` ghi media Product chỉ phát lại SKU đang fallback;
>   code (và test) phát lại mọi SKU vì `media[]` của snapshot gồm media cấp Product. Code đúng → ADR-020 làm rõ.
> - `INVALID_PRICE` có trong enum, `domain.md` và OpenAPI nhưng không bao giờ được trả (giá không dương là
>   `INVALID_REQUEST`); đã ghi đúng vào docs/OpenAPI, không đổi code.
> - Export `503 CATALOG_UNAVAILABLE` (không chụp được offsets) thiếu `Retry-After` dù OpenAPI hứa → đã thêm header + test.
> - OpenAPI `servers` ghi `product-service:8080` (thật là `8081`); 3 `DELETE` thiếu `400`; `x-adoption-status`
>   `proposed` dù đã implement. ADOPTION không có dòng nào cho Product HTTP/Events → đã thêm.
> - README mục Dependencies ghi Search → Product HTTP là "Outbound"; ADR-002/004/009 frontmatter vẫn `accepted` dù index ghi
>   Superseded; cột "Supersedes" của index lẫn hai chiều → đã tách "Superseded by"; ADR-015/018 `Accepted`.
> - Docs ngoài Product có sai cổng/allowlist của Product: `docs/product-detail-bff/operations.md` (B2),
>   `docs/promotion/README.md` (allowlist), `docs/search/README.md` ("Cần tạo: AsyncAPI product") → đã sửa.
> - Search đã đọc `facet_definitions` (2026-10-09, `FacetLabelResolver`, nhãn nhóm facet; nhãn option vẫn dẫn xuất).
> - Còn mở: `.proto` vẫn là bản copy
>   (chọn giữ + `ProtoCopyDriftTest`, Checkout chưa có test tương đương); chưa có smoke test Debezium thật cho Product;
>   S6 và PR6 đã sửa 2026-10-09 (xem checklist).

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

> **Kiểm tra lại 2026-10-09** (đọc toàn bộ `docs/inventory` gồm 28 ADR, 4 OpenAPI + AsyncAPI + schema, toàn bộ code,
> migration, test và client Order/Checkout/PDP BFF/Search/frontend): nhận định "khớp route, mã lỗi, config" ở trên
> **đúng**, I2 **đúng**, I1 để nguyên theo quyết định của chủ dự án. Nhưng báo cáo **chưa đầy đủ**: còn thiếu I3–I11
> bên dưới (1 lỗ hổng bảo mật, 3 bug runtime, 1 chức năng docs hứa mà code chưa có). Bốn lỗi code đã được tái hiện bằng
> test đỏ trên code cũ (`AuditCorrectionsIT`) rồi mới sửa. Quyết định gom ở
> [ADR-028](../docs/inventory/decisions/ADR-028-post-implementation-audit-corrections.md).

### I1 🟡 Thư mục `brainstorm/` gắn nhãn sai — ⏸ không sửa theo quyết định của chủ dự án (brainstorm là ý tưởng ban đầu)
- Frontmatter các file `docs/inventory/brainstorm/*.md` ghi `scope: target, status: proposed` (riêng `design-charter.md` frontmatter hỏng: `## doc_id`), trong khi nội dung trái với thiết kế đã chốt: release `STOCK_DISCREPANCY` + `order_exception_id`, Commit bị chặn sau `expires_at` (INV-DOM-010), path dạng `reservations:reserve`, `{order_id}:release`, release một line `POST_DISPATCH_EXCEPTION`.
- README nói brainstorm "là evidence, không phải nguồn chuẩn".
- Sửa: đổi frontmatter thành `status: superseded` / `historical` (hoặc thêm banner) và sửa frontmatter hỏng.

### I2 🟡 `interfaces.md` §4 thiếu endpoint ADMIN — ✅ đã kiểm chứng đúng và sửa 2026-10-09
- Thiếu: `GET /warehouses/{warehouse_id}`, `/stock-positions/{warehouse_id}/{sku_id}`, `/stock-movements/{movement_id}`, `/commands/{command_id}`, `/reservations/{order_id}`, `/refund-scopes/{refund_scope_id}` (có trong ADMIN OpenAPI và `AdminController`).
- Đã sửa: `interfaces.md` §4 liệt kê đủ 13 query; ADMIN README nêu nhóm đọc theo định danh.

### I3 🔴 [SECURITY] `IdentityFilter` so khớp URI thô — ✅ phát hiện mới 2026-10-09, đã sửa
- `IdentityFilter` so `request.getRequestURI()` với prefix ADMIN/service, còn Spring MVC route theo path đã decode và bỏ `;param`. `GET /api/v1/admin/inventor%79/warehouses` hoặc `/api/v1/admin/inventory;x=1/warehouses` né cả kiểm role `ADMIN` lẫn allowlist `X-Service-Identity`: test đỏ trên code cũ cho `200` với user `USER`. Mutation chỉ hỏng vì thiếu idempotency namespace; mọi query (kho, tồn, movement, reservation, scope) bị lộ.
- Sửa: kiểm trên `UrlPathHelper.getPathWithinApplication`. Cùng mẫu `getRequestURI()` còn ở filter của Order, Product, Promotion, Review — chưa sửa (ngoài phạm vi Inventory).

### I4 🟠 [BUG] Watermark snapshot = 0 sau khi retention xoá hết fact — ✅ phát hiện mới, đã sửa
- `OutboxStore.latestSequence()` = `max(sequence)` của `published_event`; sau 30 ngày yên tĩnh retention xoá hết nên giá trị `NULL → 0` trong khi `retention_floor > 0`. Snapshot mới có watermark 0, `changes?after_sequence=0` trả `410 REPLAY_WINDOW_EXPIRED` ngay → Search bootstrap lặp vô hạn.
- Sửa: `GREATEST(max(sequence), retention_floor)`; docs `interfaces.md` §7, `data.md` §6, Export OpenAPI.

### I5 🟠 [BUG] Reserve trùng `order_id` song song có thể trả `REJECT_ALL` — ✅ phát hiện mới, đã sửa
- Hai Reserve cùng đơn, khác command ID, cùng qua bước "đã có root?"; lệnh sau chờ khoá position, thấy hết hàng và ghi bền `REJECT_ALL` cho đơn đã có root thay vì `RESERVATION_ALREADY_EXISTS`.
- Sửa: khoá advisory theo `order_id` ở đầu Reserve; `data.md` §4, Workflow OpenAPI/README.

### I6 🟡 Đăng ký lại Refund Scope trả `scope_revision` cũ — ✅ phát hiện mới, đã sửa
- ADR-018 cho phép đăng ký lại cùng `(order_id, refund_request_id)` + dòng và trả scope hiện có, nhưng code luôn trả `scope_revision: 1` dù scope đã tiến triển, và `interfaces.md`/OpenAPI không ghi hành vi này.
- Sửa: trả revision hiện tại; ghi vào `interfaces.md` §2, Workflow OpenAPI/README.

### I7 🟠 `operations.md` §3 hứa kiểm tra mà code không có — ✅ phát hiện mới, đã sửa (implement)
- "Continuous: recompute sampled `SkuAvailabilityState`" và "readiness-suppressed facts" không có trong code (chỉ có gauge backlog/age).
- Sửa: `Reconciliation.auditAvailability()` (detection-only, quét vòng theo `sku_id`, bỏ qua SKU còn dirty/fan-out) + gauge `inventory_integrity_availability_mismatches`, `inventory_availability_readiness_suppressed`; cấu hình `availability-audit-*`; docs `operations.md` §3.

### I8 🟡 Retention topic "30d-minimum" không có trần (nửa Inventory của S4) — ✅ đã sửa phía Inventory 2026-10-09
- AsyncAPI khai `x-retention: 30d-minimum`, không nơi nào provision topic (compose để broker auto-create, mặc định ngắn hơn). Consumer không thể chọn cửa sổ "dài hơn retention".
- Sửa: retention topic là đúng 30 ngày (sàn và trần); AsyncAPI/README/`data.md`/`operations.md`/kafka README; thêm gate production "Platform provision topic" trong `development.md`. **Còn mở (Search):** `search.tombstone.protection-window` vẫn 30d và comment ghi "14d" — phải dài hơn 30d.

### I9 🟡 README ghi `mvn test` chạy integration test nhưng surefire bỏ qua `*IT` — ✅ phát hiện mới, đã sửa
- Lệnh trong README chỉ chạy 7 `DomainRulesTest`. Sửa `pom.xml`: include `**/*Test.java` và `**/*IT.java` (như Order); README cập nhật.

### I10 🟡 Catalog/ADOPTION cũ — ✅ phát hiện mới, đã sửa
- `interactions.yaml`: 5 interaction Inventory còn `proposed` dù cả hai phía đã chạy (`checkout-previews-inventory-allocation`, `warehouse-admin-operates-inventory`, `product-detail-bff-queries-inventory-availability`, `inventory-publishes-public-facts`, `search-exports-inventory-availability`). `ADOPTION.md` ghi "Consumer Order chưa có" (Order có `FulfillmentIntake`/`InspectionIntake`) và "UI còn phải sinh lại type" (đã có `inventory-admin.ts` theo ADR-027).

### I11 🟡 Chỗ docs/contract lệch nhỏ — ✅ phát hiện mới, đã sửa
- Workflow `cancel` thiếu `400`; các query (Workflow + 13 GET ADMIN) thiếu `503`; snapshot session đã bị retention xoá trả `404` (Search xử lý như hết hạn) nhưng contract chỉ nói `410`; "SKU chưa có state được evaluate `false`, revision 1" (thực tế tính từ stock); fan-out pending chỉ tính SKU cursor chưa tới; thứ tự khoá nói `OperationIdentity` đứng đầu nhưng code kiểm bằng đọc không khoá rồi claim sau khi khoá aggregate; `operations.md` §5 ghi `X-Service-Identity` "chỉ để attribution" trong khi service áp allowlist `401/403`.

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
- **2026-10-09 — kiểm chứng đúng; đã xử lý phía Inventory** (retention topic đúng 30d, sàn và trần; xem I8). **Còn mở phía Search:** nâng `protection-window` lên dài hơn 30d (ví dụ 35d) và sửa comment "14d".
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
- Rate limit internal User theo IP 600/phút chung cho mọi pod (U10) — đã sửa 2026-10-09: theo (service gọi, user_id), 120/phút.
- Product chưa có nhãn biến thể → Order `variant_name` null → ảnh hưởng Review/Notification.
- User snapshot không có tên tỉnh/huyện/xã → Order/Notification dùng mã.
- `user_tier` luôn omit.

Đã khớp: 4 endpoint public; config (deadline 5s/1.5s/20s, timeout, bulkhead, CB, TTL 20m/30m/5m/30s/2m/24h, cờ voucher options mặc định false + compose true) == operations.md; header gửi đi (`X-Service-Identity`, `X-Correlation-ID`, `X-Causation-ID` tới Inventory; `Idempotency-Key` = token tới Order); `checkout_channel=CHECKOUT`.

---

## 15. Notification (`docs/notification`, `contracts/events/notification/v1`, `contracts/http/notification/**`, `go/services/notification-service`)

### N1 🔴 [cross User, compliance] Gửi marketing khi user chưa đồng ý
- Xem U4. `docs/notification/08-decide.md` §5: thiếu preference → `marketing_enabled=false` ("gửi marketing cho người chưa từng đồng ý là rủi ro compliance"). User nguồn mặc định `true` và reconciliation đẩy `true` sang → marketing (vd `CART_ABANDONED` lane bulk) được gửi cho user chưa opt-in.
- Sửa: thống nhất mặc định với User (ưu tiên `false` ở nguồn).

### N3 🔴 Internal API dùng app-level mTLS (xem X2) — ⏳ chưa sửa (Notification). Phía Auth đã chuyển sang mô hình mesh 2026-10-09 nên các cấu hình `NOTIFICATION_AUTH_TLS_*` của client trở thành tùy chọn/thừa
- `internal/api/router/internal.go`: không phải dev + không có app mTLS → log "every status/ops call will be rejected". Mesh docs nói Istio ambient thực thi, app không thấy cert → production sau mesh sẽ từ chối mọi call status/ops nếu không cấu hình TLS ở app.

### N4 🟡 Client gọi Auth không gửi `X-Service-Identity` — ❌ báo cáo sai (kiểm chứng 2026-10-09): `client.go:294` gửi header khi `auth.service_identity` khác rỗng, mặc định `notification-service`, test `client_test.go` assert header
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

### X2 🔴 Hai mô hình xác thực internal không tương thích — 🔶 đã xong Auth (ADR-007) và User (ADR-008) 2026-10-09; còn Notification (N3)
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
- [x] A9 Auth: luôn phát `USER_DELETED` / `USER_EMAIL_CHANGED` / `USER_PASSWORD_CHANGED` sau commit dù Redis lỗi (ADR-006). *(2026-10-09)*
- [x] A12 Auth: resend OTP (và yêu cầu reset) không trả 429 phân biệt. *(2026-10-09)*
- [x] A13 Auth + gateway-auth: *không sửa code* — hành vi là chủ ý (độ phân giải 1 giây); đã ghi vào `docs/auth/data.md`, ADR-001, OpenAPI và `docs/gateway/runtime-contract.md` §6. *(2026-10-09)*
- [x] A11 Auth: rate limit internal theo (service gọi, `delivery_id`/`user_id`) thay vì IP; `X-Service-Identity` allowlist thay mTLS ở app (ADR-007). *(2026-10-09)* [x] U10 User: rate limit theo (service gọi, `user_id`) thay vì IP, `X-Service-Identity` allowlist thay mTLS ở app (ADR-008). *(2026-10-09)*
- [x] U9 User: chặn đọc/ghi profile/address/preferences của user đã xoá (`GetActive*`, lock trong `Anonymize`, ADR-010). *(2026-10-09)*
- [x] U4/N1 User: mặc định `marketing_enabled=false` + migration `0002` backfill row chưa chạm (ADR-009). *(2026-10-09)*
- [x] U11 User: epoch chống fill cache address muộn (ADR-011). *(2026-10-09)*
- [x] U7 User: cho xoá address duy nhất còn lại (ADR-012) + frontend `AddressCard`. *(2026-10-09)*
- [x] U5 User: consumer `complaint-received` trên `t.notification.outcomes.v1` (ADR-009). *(2026-10-09)*
- [x] C11 Cart: TTL idempotency upsert lấy từ config (sửa cả `docs/cart/scripts/upsert_item.lua`). *(2026-10-09)*
- [x] C12 Cart: consumer `USER_DELETED` + `purge_cart.lua` (ADR-014); Auth contract/docs/catalog đã thêm Cart vào consumers. *(2026-10-09)*
- [ ] S4 Search: tăng tombstone protection window > 30d / xử lý document đã purge.
- [x] S6 Search: default URL Product `:8081` (yml, `SearchProperties`, compose gốc + compose riêng của Search). *(2026-10-09)*
- [ ] S7 Search: bỏ `/` cuối ở default CDN URL.
- [x] P5 Product: tách allowlist snapshot cho Promotion (`snapshot-clients`, Promotion không còn đọc được export). *(2026-10-09)*
- [ ] P7 Product: build proto từ `contracts/`. *(2026-10-09: chọn giữ bản copy + `ProtoCopyDriftTest`, gRPC v1 vẫn `proposed`; Checkout chưa có test tương đương)*
- [x] P4 Product: phát `facet_definitions` trong category event và export (ADR-019). *(2026-10-09; Search đã đọc, chỉ nhãn nhóm — nhãn option vẫn dẫn xuất)*
- [x] N4 Notification: gửi `X-Service-Identity` khi gọi Auth. *(báo cáo sai: đã gửi từ trước, không cần sửa)*
- [ ] X2: thống nhất mô hình xác thực internal (Auth/User/Notification). *(User chuyển sang mô hình mesh 2026-10-09, ADR-008; Auth chuyển 2026-10-09, ADR-007; còn Notification)*
- [ ] O3: quyết định + implement đường tới `DELIVERED`/`COMPLETED`.

### Contract / catalog
- [ ] PR4 sửa tham số `LegacyMigrationProof`.
- [ ] K1/K2 bổ sung mã vào Order internal OpenAPI.
- [x] C7 Cart OpenAPI: mô tả đúng allowlist header + response 400. *(2026-10-09)*
- [x] C6 Cart OpenAPI `servers` *(2026-10-09)*; [x] PR6 Promotion OpenAPI `servers` `promotion-service:8085` + `localhost:8085`; `docs/promotion/operations.md` ghi đúng default `localhost:8082`. *(2026-10-09; lưu ý audit nói 8082 là cổng User — trong compose gốc đó là cổng host của Product nên default code đúng, chỉ docs sai)*
- [x] U8 User OpenAPI `maxItems: 50` cho `AddressList` (kèm 403 cho cổng internal, mô tả xoá default/account đã xoá). *(2026-10-09)*
- [x] C9 Cart OpenAPI 429 cho GET. *(2026-10-09)*
- [ ] G3 `services.yaml`; G4 `ADOPTION.md` (phần Cart và hai dòng User HTTP/Events đã xong 2026-10-09); G5 `interactions.yaml` (C1 và `notification-consumes-cart-abandoned` đã xong 2026-10-09; U5 và interaction `checkout-reads-user-address-snapshot` (K3, phần User) đã xong 2026-10-09; P5 và ba interaction Product (`search-reindexes-product-catalog`, `product-publishes-catalog-events`, `promotion-validates-product-snapshot`) và ba dòng Product trong ADOPTION đã xong 2026-10-09; hai dòng Auth HTTP/Events trong ADOPTION và bốn interaction `auth-*`/`notification-resolves-auth-*` đã xong 2026-10-09; còn N5, phần còn lại của K3, PY2, N2).
- [ ] X1 đồng bộ mesh matrix sau khi sửa catalog. *(2026-10-09: dòng Product snapshot của Promotion đã trỏ tới `promotion-validates-product-snapshot`; còn N5)*

### Docs
- [ ] G1/G2 `docs/README.md` (Delivery link, trạng thái, số ADR, số operation).
- [x] Auth: A1–A8, A14 (cộng ADR-006..009, error catalog đầy đủ, bảng cấu hình, `scope: current`/`status: implemented`). *(2026-10-09)*
- [x] User: U1–U3, U6, U7, U8 (cộng U4, U5, U9–U11 ở docs; frontmatter `scope: current`/`status: implemented`, error catalog đầy đủ, bảng cấu hình, WF-USER-009). *(2026-10-09)*
- [x] Cart: C2–C5, C8–C10, C12. *(2026-10-09)*
- [x] Product: P1–P4, P8 (cộng ADR-020, error catalog đầy đủ, bảng cấu hình, `schema.sql` khớp migration, frontmatter `status: implemented`). *(2026-10-09)*
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
