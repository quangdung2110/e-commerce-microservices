---
doc_id: promotion-adr-019
service: promotion
status: accepted
date: 2026-10-10
decision_owners:
  - Promotion Service team
supersedes: null
evidence:
  - java/services/promotion-service/src/main/java/com/platform/promotionservice/config/TrustedIdentityFilter.java
  - java/services/promotion-service/src/main/java/com/platform/promotionservice/service/FinalCommandService.java
  - java/services/promotion-service/src/main/java/com/platform/promotionservice/service/FinalCommandTransactions.java
  - java/services/promotion-service/src/main/resources/db/migration/V2__legacy_migration_proof.sql
  - java/services/promotion-service/src/test/java/com/platform/promotionservice/integration/HttpContractIntegrationTest.java
  - java/services/promotion-service/src/test/java/com/platform/promotionservice/integration/VoucherLifecycleIntegrationTest.java
  - contracts/http/promotion/v1/openapi.yaml
  - note/audit-2026-10-07-docs-code-sync.md
---

# ADR-019 — Sửa sau audit đồng bộ docs ↔ contract ↔ code

## Status

`Accepted` 2026-10-10; implemented. Làm rõ ADR-013, ADR-014, ADR-017 và ADR-018 (không đổi quyết định
của chúng, chỉ đóng chỗ code chưa làm đúng hoặc docs mô tả sai). Nguồn: audit 2026-10-07 mục 7 (PR1–PR8,
PR10) và các điểm kiểm chứng thêm 2026-10-10.

## Context

Audit đối chiếu docs/contract với code và chạy thử từng điểm. Có sáu chỗ code lệch hành vi docs đã
chốt, và hai chỗ docs mô tả thứ không tồn tại:

1. `technical-cancellations` rơi vào nhóm `allocation-clients=[order-service]`, nhận body của
   `confirmations`, không có header/field proof và bảng `promotion_command_receipts` không có hai cột
   proof, trong khi ADR-013/017, OpenAPI và `domain.md` BR-018 nói chỉ `promotion-legacy-migrator` kèm
   proof bất biến mới gọi được và Order bị từ chối ở cả mesh lẫn application. Test `HttpContractIntegrationTest`
   còn chứng minh `order-service` gọi được route (`200`), và `development.md` nêu các test chặn Order không tồn
   tại.
2. Chỉ `voucher-options` kiểm `checkout_channel=CHECKOUT`; calculate và reserve (cùng
   `TrustedCheckoutContext` trong OpenAPI) chấp nhận channel bất kỳ.
3. Hai `confirm` (hoặc legacy cancel) cùng key đến đồng thời: bản thua chờ khoá wallet rồi thấy wallet đã
   `USED` và trả `409 FINAL_COMMAND_STATE_INCONSISTENT` thay vì stored response (đo được 22/54 lần với 6 luồng
   × 9 order), trái PROMO-INV-012 và `data.md`.
4. Reserve yêu cầu wallet còn hạn nhưng `confirm` chạy sau Payment; nếu giữa hai bước một truy vấn của chính
   user chuẩn hoá wallet thành `EXPIRED` (lazy), `confirm` thất bại với `FINAL_COMMAND_STATE_INCONSISTENT` sau
   khi tiền đã thu, ngược ADR-017 (CONFIRM là final sau `PAYMENT_SUCCESS`).
5. Consumer `product-deleted` tăng version fence toàn cục của cache snapshot voucher, vô hiệu hoá cache của mọi
   promotion, trong khi docs (kể cả ADR-014) nói "invalidate product-scoped cache" — không có cache nào theo
   product (markdown rule không nằm trong Redis).
6. Route lạ, sai HTTP method và Content-Type không hỗ trợ trả `500 INTERNAL_ERROR` (và log stack trace ERROR)
   vì `GlobalExceptionHandler` bắt `Exception.class`.
7. `operations.md` nói readiness phản ánh PostgreSQL/Redis; nhóm `readiness` của Actuator chỉ có `readinessState`.
8. `voucher-options` không trả `Cache-Control: no-store` dù OpenAPI hứa; OpenAPI thiếu một số status
   (`404` ở claim/reserve, `400` ở năm GET/list admin), ghi `requestBody.required: true` cho `PUT
   /me/promotion-vouchers` trong khi code và test chấp nhận không body, và tham số `X-Legacy-Migration-Proof`
   vừa `format: uuid` vừa `pattern: ^CANCEL_FINAL:...` (không giá trị nào thoả).

## Decision

1. **Hiện thực guard của technical-cancellations đúng như ADR-013/017.**
   - Nhóm allowlist riêng `promotion.internal.legacy-migration-clients=[promotion-legacy-migrator]`, kiểm trước
     tiền tố allocation; `order-service` và mọi caller khác `403`.
   - Body riêng `LegacyTechnicalCancellationRequest` (`order_id`, `promotion_id`, `expected_discount`,
     `migration_record_id`, `pre_financial_watermark_evidence_sha256`; `additionalProperties: false`). Header
     `X-Legacy-Migration-Proof` phải là UUID bằng `migration_record_id`; thiếu, không phải UUID hoặc khác là
     `403 FORBIDDEN` trước mọi đọc/ghi. Body sai schema là `400`.
   - Record ID và digest (chữ thường) nằm trong canonical request hash và được lưu trên receipt (migration `V2`: hai cột
     + `CHECK chk_receipts_migration_proof ... NOT VALID`).
   - Promotion không tra cứu record trong registry nào (không có registry): bằng chứng ở tầng ứng dụng là
     allowlist workload + header = body + bất biến trên receipt; tính "đã duyệt" của record và digest là trách nhiệm
     của quy trình migration phía caller.
2. **`TrustedCheckoutContext` được enforce ở cả calculate, voucher options và reserve:** `checkout_channel` khác
   `CHECKOUT` là `400 INVALID_REQUEST` (`errors[].field = context.checkout_channel`) trước mọi bước đọc/ghi. Context
   của suggestion, discovery và save là của client nên không bị ép.
3. **Final command trùng đồng thời là replay.** `FinalCommandTransactions` kiểm receipt theo business key trước
   khi khoá wallet và kiểm lại ngay sau khi giành khoá (legacy cancel còn kiểm lại khi wallet không còn `USED`
   vì bản trùng vừa clear nó). Bản thắng vẫn là bản insert receipt đầu; ràng buộc unique
   `(operation_type, promotion_id, order_id)` vẫn là lưới an toàn cuối.
4. **Reservation là authority của `CONFIRM`:** `confirm` chấp nhận wallet `SAVED` và cả `EXPIRED` (chỉ do chuẩn
   hoá lười sau Reserve), chuyển `USED`. State machine thêm `EXPIRED → USED` chỉ qua `CONFIRM` của reservation
   hợp lệ; save, reserve và discovery vẫn coi `EXPIRED` là hết hạn. Legacy cancel sau đó trả wallet về `EXPIRED`
   vì `expired_at` đã qua.
5. **Consumer `product-deleted` không invalidate cache.** Bỏ lời gọi tăng version fence; docs (WF-PROMO-009,
   `data.md`, `architecture.md`, `operations.md`) ghi đúng rằng không có cache theo product. Câu "invalidate
   product-scoped Redis cache sau commit" trong ADR-014 được hiểu là không áp dụng (ADR-014 không bị sửa lịch
   sử).
6. **Request không khớp operation nào là lỗi client:** route lạ `404`, sai method `405` (kèm `Allow`), Content-Type
   không hỗ trợ `400`; cả ba là problem+json code `INVALID_REQUEST` (cùng quy ước Checkout), không log stack
   trace, không `500`.
7. **Contract khớp code:** `voucher-options` trả `no-store`; `PUT /me/promotion-vouchers/{id}` có
   `requestBody.required: false`; thêm `404` (claim, reserve) và `400` (năm GET/list admin); `Conflict` liệt kê
   `PROMOTION_CONSTRAINT_VIOLATION`; tham số proof chỉ còn `format: uuid`, pattern `CANCEL_FINAL:...` chuyển sang
   `TechnicalCancellationIdempotencyKey`; `x-adoption-status` của cả contract là `implemented` (bỏ
   `x-known-runtime-gap`).
8. **Docs mô tả đúng health:** liveness/readiness của Actuator là trạng thái ứng dụng; `/actuator/health` tổng hợp
   (db, redis, `promotionRuntime`) mới kiểm dependency và là thứ compose/Traefik dùng. Không đổi code.

Không thuộc quyết định này: persist `reason` của transition/grant (hiện chỉ log hoặc bỏ), phân trang cho
public discovery trên 200 promotion, mã `UNAUTHENTICATED` vs `UNAUTHORIZED` giữa các service (X3), batch
normalization `EXPIRED`.

## Alternatives considered

- **Gỡ route technical-cancellations** (code, receipt `CANCEL_CONFIRMED`, contract, mesh row, catalog, ADR): bỏ
  được bề mặt rủi ro nhưng đổi nghĩa ADR-013/017, mesh matrix và nhiều tài liệu Order; chưa có migrator nên route
  chỉ là capability dự phòng. Không chọn; có thể làm sau bằng ADR thay thế.
- **Proof sai trả `400` thay vì `403`:** coi là lỗi cú pháp. Không chọn vì docs/OpenAPI nói "denied" và proof là
  capability, không phải input nghiệp vụ.
- **Giữ wallet `SAVED` khi có reservation** (bỏ qua wallet có reservation trong lazy expiry): cần tra Redis theo
  từng wallet trong một câu `UPDATE`, hoặc phát sinh trạng thái "reserved" trong PostgreSQL mà ADR-013 cố ý
  không có. Không chọn.
- **Chỉ ghi nhận lazy-expiry là known gap:** bỏ trống đúng tình huống ADR-017 muốn tránh (đã thu tiền mà voucher
  không redeem). Không chọn.
- **Đưa `db`/`redis`/`promotionRuntime` vào nhóm readiness:** khớp docs cũ nhưng pod rời traffic mỗi khi Redis/DB
  chậm. Không chọn; sửa docs.
- **Giữ bump version fence khi tombstone:** an toàn nhưng làm mất cache toàn bộ promotion mỗi lần xoá Product mà
  không có lợi ích.
- **Map `415` thành mã riêng hoặc giữ `415`:** thêm `ErrorCode` và đổi catalog; không chọn để cùng quy ước Checkout.

## Consequences

- Order không còn đường nào un-redeem voucher ở tầng ứng dụng, kể cả khi mesh chưa dựng (docker-compose).
- Migrator tương lai phải gửi header + `migration_record_id` + digest; replay với proof khác bị `409`.
- `EXPIRED → USED` mở rộng state machine của wallet; mọi reader coi `EXPIRED` là terminal-until-confirm cần nhớ
  ngoại lệ này (chỉ `CONFIRM` có reservation).
- `development.md` không còn khẳng định các test không tồn tại; số test là 44.
- Receipt `CANCEL_CONFIRMED` tạo trước `V2` (nếu có) không có proof và được miễn bởi `NOT VALID`.

## Migration impact

- Flyway `V2__legacy_migration_proof.sql` (additive). Code cần `V2` trước khi nhận `technical-cancellations`.
- Cấu hình: thêm `promotion.internal.options-clients` và `legacy-migration-clients` (mặc định trong code và
  `application.yml`).
- Contract: OpenAPI v1 (xem `contracts/CHANGELOG.md` 2026-10-10); frontend sinh lại `promotion.ts`.
- Rollback: bỏ guard đồng nghĩa mở lại lỗ hổng cũ; cột proof có thể giữ nguyên.

## Verification

- `TrustedIdentityFilterTest`, `TrustedIdentityFilterPathTest`: nhóm migrator, kể cả path mã hoá/`;param`.
- `HttpContractIntegrationTest`: Order `403`, migrator thiếu/sai proof `403`, body/digest/field lạ `400`, proof được lưu
  lowercase, replay khác digest `409`, channel `400` ở calculate/reserve (customer discovery vẫn nhận `web`),
  `no-store` của options, route lạ/method/Content-Type.
- `VoucherLifecycleIntegrationTest`: confirm sau lazy-expiry (`EXPIRED→USED` rồi legacy cancel → `EXPIRED`); 6 luồng
  × 5 order confirm và legacy cancel trùng key đều nhận cùng `completed_at`. Kiểm đột biến: bỏ lần kiểm lại sau khoá và
  bỏ nhánh `EXPIRED` thì hai test này đỏ đúng với thông báo cũ.
- Redocly lint sạch cho OpenAPI.
