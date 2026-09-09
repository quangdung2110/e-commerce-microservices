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

- `X-Request-Id` là correlation ID, **không** là idempotency key. Khi create/edit
  timeout, buyer dùng recovery read theo eligibility hoặc review ID thay vì retry write
  mù quáng.
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

## Compatibility

Contract đang `proposed`. Thay đổi path, security, status/error code hoặc semantics
missing/null là breaking và phải có HTTP major version mới theo
[`contracts/conventions/compatibility.md`](../../../conventions/compatibility.md).
