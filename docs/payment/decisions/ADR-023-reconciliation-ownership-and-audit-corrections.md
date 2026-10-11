---
doc_id: payment-adr-023
service: payment
status: accepted
date: 2026-10-11
decision_owners:
  - Payment Service team
supersedes: null
evidence:
  - java/services/payment-service/src/main/java/com/platform/paymentservice/adapter/out/persistence/JdbcReconciliationAdapter.java
  - java/services/payment-service/src/test/java/com/platform/paymentservice/integration/ReconciliationOwnershipIT.java
  - contracts/http/payment/internal/v1/openapi.yaml
---

# ADR-023 — Reconciliation ownership và correction sau audit

## Bối cảnh

Partial unique RUNNING/lane ngăn run thứ hai, nhưng row không tự biến mất khi
process chết. Cursor Stripe của cửa sổ cũ không thể tái sử dụng cho cửa sổ mới.
Manual correction FAILED/CANCELED thiếu mã bắt buộc của event schema và chưa
kiểm chứng canceled intent. Freeze trước đây vẫn cho balance ghi ledger.

## Quyết định

Chủ dự án yêu cầu tự nhận diện run bỏ dở, bảo đảm độc quyền lane; chấp thuận mã
lỗi/lý do explicit và canceled provider evidence cho terminal non-capture.
Chủ dự án giao lựa chọn freeze; chọn dừng ghi ledger tự động của balance để bảo
toàn tác dụng của freeze khi điều tra lỗi tài chính.

Giữ PostgreSQL session advisory lock cho từng lane, không dùng timeout/heartbeat
để suy ra process đã chết. Acquirer mới đánh dấu RUNNING cũ FAILED rồi tạo run mới.
Mutation giữ row lock của run, kiểm tra session/status và join financial transaction;
successor phải chờ mutation đang commit. Gọi Stripe ngoài transaction. Hoàn tất/fail
phải explicit unlock trước khi trả connection về pool.

Balance đọc toàn bộ trang với limit ≤100 trong một cửa sổ cố định, luôn bắt đầu
cursor null; scheduler mở rộng lookback từ watermark/lượt thất bại để đọc bù.
Freeze không advance watermark. Duplicate journal dùng canonical movement source
chung giữa balance/webhook/manual; chỉ bỏ qua khi nội dung tài chính khớp.

Correction thêm `error_code` cho PAYMENT_FAILED_CONFIRMED và `reason_code` cho
PAYMENT_CANCELED; evidence canceled phải đúng id PaymentIntent đã lưu. Evidence
tham gia fingerprint. R1 refund tăng financial version của payment với status giữ
nguyên. Manual bind INITIATING→PENDING vẫn chưa hỗ trợ.

KMS, metrics nghiệp vụ và heartbeat tự động được giữ là công việc riêng theo lựa
chọn của chủ dự án. Bảng [runtime status](../runtime-status.md) mô tả chính xác các
giới hạn; không hạ tiêu chuẩn thiết kế thành tuyên bố production đã sẵn sàng.

## Phương án đã cân nhắc

- Timeout RUNNING: có thể takeover worker chỉ đang chậm; cần heartbeat và fencing
  bền vững riêng. Không chọn cho đợt này.
- Phục hồi thủ công: an toàn khi xác nhận worker đã dừng nhưng không đáp ứng yêu
  cầu tự phục hồi của chủ dự án.
- Session lock + run row fence: tự giải phóng khi session mất, không gọi provider
  trong transaction; chọn với điều kiện triển khai connection/session phù hợp.

## Hệ quả và migration

Giữ 12 bảng hiện hữu và không sửa V1/V2 đã áp dụng. Migration V3 bỏ CHECK không âm của refunded/fee reconciliation totals; net refund reversal/fee credit có thể âm, journal line vẫn dương. Giữ thêm connection cho
owner; cần dừng worker reconciliation cũ trước lần nâng cấp này. Không dùng
transaction pooling cho session lock. Freeze chặn balance auto posting nhưng
webhook và Order command vẫn chạy. Balance lỗi cần Ops xử lý; chưa có closing
balance/payout statement comparison hoặc tự tạo review case cho mọi lỗi balance.

## Xác minh

ReconciliationOwnershipIT kiểm live owner exclusion, pg_terminate_backend,
takeover/fencing và successor chờ transaction cũ. BalanceReconciliationIT kiểm
pagination, cửa sổ mới, canonical duplicate, conflict, fee credit và freeze/read bù.
ManualCorrectionIT kiểm evidence/mã event, idempotency và financial version.
HTTP controller tests kiểm strict JSON và Problem response; toàn bộ `mvn verify`
và canonical contract/docs validators phải pass trước khi kết luận audit hoàn tất.

### Bằng chứng correction SUCCESS sau audit 2026-10-11

Theo quyết định chủ dự án, PAYMENT_SUCCESS_CONFIRMED yêu cầu provider_status=succeeded,
provider_object_id là Charge ch_, provider_response_json là object có id bằng
PaymentIntent đã lưu, status=succeeded và latest_charge bằng Charge đó. Charge đã
lưu nếu có cũng phải khớp. Journal CAPTURE dùng source CHARGE/ch_ chung với webhook
và balance, tránh journal CHARGE/pi_ gây ghi kép. Bằng chứng do Finance Ops cung cấp;
API không tự retrieve hoặc xác thực chữ ký của bằng chứng.
