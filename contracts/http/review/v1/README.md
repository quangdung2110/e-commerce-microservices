# Review HTTP API v1

[`openapi.yaml`](./openapi.yaml) là nguồn chuẩn cho Review public/buyer/admin HTTP
wire contract. API nội bộ cho Product Detail BFF và Search nằm riêng tại
[`../internal/v1/openapi.yaml`](../internal/v1/openapi.yaml) và không được expose
qua API Gateway.

## Caller boundary

| Surface | Authentication | Scope |
|---|---|---|
| `GET /api/v1/reviews/products/**` | Anonymous | Chỉ đọc review `APPROVED` và zero/current aggregate |
| `/api/v1/reviews/**` còn lại | Gateway identity | Buyer chỉ đọc/sửa dữ liệu của mình, report và cấp upload instruction |
| `/api/v1/admin/**` | Gateway identity + Review admin role | Moderation queue theo review, dismiss report, official reply và legal takedown |

`X-User-Id` và `X-User-Roles` là headers do Gateway inject sau JWT verification.
Client không được tự gửi chúng; Review vẫn phải authorize ownership/role.

## Semantics cần lưu ý

- `OwnerReview.content_edited_at` là response field optional/nullable/read-only. Backend
  mới luôn trả field, kể cả null; frontend cũ/mới rollout không đổi meaning các field cũ.
  Nó chỉ ghi transaction time của author PATCH được chấp nhận (rating/content/media),
  không đổi do report/moderation/reply/privacy. `updated_at` là record-update time chung.
  Legacy edit thiếu hoặc mâu thuẫn outbox history giữ null; client hiện “Đã chỉnh sửa”
  không kèm giờ và không fallback sang `updated_at`. Public/Admin/event DTO không thêm field.

- Các field số nguyên (`rating`, `byte_size`) yêu cầu JSON integer token; số thập phân
  (kể cả `1.0`) và chuỗi số bị `400 INVALID_REQUEST` trước khi claim quyền/quota.
  Field lạ và media ID trùng cũng bị 400; errors dùng tên wire field, không phản chiếu
  nội dung bị từ chối hay Java/framework internals.
- Signed PUT phải giữ header đã ký `If-None-Match: *`. Storage phải enforce điều kiện:
  ghi lần đầu thành công, PUT hợp lệ lại cùng key trả 412 và không thay byte, kể cả sau attach.
  Client retry bằng instruction/asset mới khi không xác định được lần PUT trước đã thành công.

- `X-Request-Id` là correlation ID, **không** là idempotency key. Khi create/edit
  timeout, buyer dùng recovery read theo eligibility hoặc review ID thay vì retry write
  mù quáng.
- Bề mặt owner (`/reviews/me/**`) trả snapshot `product_name`/`product_image_url` (và
  `order_id` ở entitlement chờ) lấy từ `ORDER_COMPLETED` lúc mua, nullable với entitlement
  cũ; `GET /reviews/me` liệt kê review của chính caller kể cả khi `HIDDEN_BY_REPORT`/`REMOVED`
  (không signed media, report hay lý do pháp lý). Xem
  [Review ADR-012](../../../../docs/review/decisions/ADR-012-owner-surface-product-snapshot-and-review-list.md).
- Media có ba bước ở client: xin signed upload instruction, upload trực tiếp, gửi
  `asset_id` trong create/edit. Không có verify/poll API; Review tự kiểm metadata object
  immutable trước attach và không trả object key.
- `PATCH` giữ field vắng mặt, `content: null` xóa content, media field vắng mặt giữ
  attachment và media array rỗng gỡ toàn bộ attachment. Reorder attachment đang giữ lại
  không được hỗ trợ trong v1.
- `202` của report không có body để không lộ report count hay trạng thái moderation.
  Chỉ report threshold tự ẩn review; không có manual admin-hide operation.
- Admin chỉ có một surface moderation: `GET /api/v1/admin/reviews?status=...`. Đơn vị
  là review chứ không phải report, vì `report-dismissals` thao tác theo review — một
  review bị ba report là một dòng, không phải ba dòng. `REPORTED` là review còn public
  đã có report chưa dismiss, xử được trước khi bị tự ẩn; `HIDDEN_BY_REPORT` là review
  đã bị ẩn. Phải khóa theo review state vì hide chuyển mọi report `PENDING` thành
  `ACTIONED`, nên queue lọc theo report status sẽ mất case đúng lúc cần phân xử.
- Chỉ surface admin được cấp signed URL cho media của review không `APPROVED`, TTL
  ngắn hơn public. Không có nó thì report về ảnh/video không thể phân xử. Public read
  và owner read vẫn không cấp URL cho media đó.
- `201`/`200` của domain command xác nhận database và outbox đã commit, không xác nhận
  Kafka publication/consumer processing hoàn thành.
- Mọi lỗi là `application/problem+json` với `code` ổn định; danh mục đầy đủ (HTTP status,
  `retryable`) ở [docs/review/interfaces.md mục 7](../../../../docs/review/interfaces.md#7-error-catalog).
  `404 NOT_FOUND`, `405 METHOD_NOT_ALLOWED` và `415 UNSUPPORTED_MEDIA_TYPE` áp dụng cho mọi
  đường dẫn nên không lặp ở từng operation.

## Compatibility

Contract đang `implemented` (xem [ADOPTION](../../../ADOPTION.md)): `java/services/review-service`
phục vụ đủ 14 operation và `HttpContractIT` kiểm tập route bằng đúng tập operation của file này
và validate từng response với schema tương ứng. Chưa `verified` vì chưa có consumer contract
test. Request body là `additionalProperties: false` và được thực thi: field lạ bị `400`.
Thay đổi path, security, status/error code hoặc semantics
missing/null là breaking và phải có HTTP major version mới theo
[`contracts/conventions/compatibility.md`](../../../conventions/compatibility.md).
