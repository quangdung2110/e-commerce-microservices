---
doc_id: review-adr-001
service: review
status: accepted
date: 2026-09-05
decision_owners:
  - Review Service team
supersedes: null
evidence:
  - docs/review/schema/0001_initial_review_schema.sql
---

# ADR-001 — Eligibility 1-1-1-1 và đúng một lần chỉnh sửa

## Status

`Accepted`

## Context

Đánh giá chỉ có giá trị khi nó đến từ người thực sự đã mua và nhận hàng. Cần một cơ
chế cho phép đúng những người đó viết đánh giá, đúng một lần cho mỗi thứ họ đã mua,
trong một khoảng thời gian có hạn.

Đồng thời cần cho phép sửa: người ta đổi ý sau khi dùng sản phẩm thêm vài ngày. Nhưng
sửa không giới hạn mở ra một lỗ hổng — người bán có thể gây áp lực để người mua đổi
đánh giá xấu thành tốt, và đánh giá trở thành thứ có thể thương lượng thay vì một
bản ghi.

Thiết kế trước dùng ba trạng thái `PENDING`, `REVIEWED`, `REJECTED_RETRY_PENDING`,
`REJECTED_FINAL` cùng một bộ đếm `attempt_count`. Cách đặt tên này mô tả sai bản chất
(không có gì bị "từ chối"), và hai câu lệnh trong tài liệu cùng đặt `REJECTED_FINAL`
cho hai tình huống khác nhau. Một câu lệnh reconciliation lọc theo
`status = 'REVIEWED' AND attempt_count = 0` — một tổ hợp không bao giờ tồn tại, vì
hai giá trị đó luôn được đặt cùng lúc thành `REVIEWED`/`1`.

## Decision

Quyền đánh giá được biểu diễn bằng một bản ghi eligibility, khóa nghiệp vụ là
`(order_id, product_id, sku_id, user_id)`, có `expires_at`.

Vòng đời gồm bốn trạng thái, chỉ tiến, không lùi:

| State | Ý nghĩa | `attempt_count` |
|---|---|---|
| `PENDING` | Còn quyền viết đánh giá | 0 |
| `REVIEWED` | Đã viết, còn đúng một lượt sửa | 1 |
| `EDIT_LOCKED` | Đã dùng hết lượt sửa | 2 |
| `EXPIRED` | Hết hạn trước khi viết | 0 |

Ràng buộc `ck_eligibility_status_matches_attempts` bắt buộc `status` và
`attempt_count` khớp nhau ở tầng database, nên không tồn tại tổ hợp vô nghĩa.

Việc giành quyền viết đánh giá được thực hiện bằng một câu `UPDATE` có điều kiện với
`RETURNING`, vừa là kiểm tra vừa là lock. Không dùng `SELECT ... FOR UPDATE` rồi mới
ghi.

Không có luồng tự động đưa eligibility về `PENDING`. Vì việc chuyển trạng thái và
việc tạo đánh giá nằm trong cùng một transaction, tổ hợp "REVIEWED nhưng không có
review" không thể sinh ra do lỗi runtime; nếu xuất hiện thì reconciliation cảnh báo
chứ không tự sửa.

Cửa sổ đánh giá mặc định 30 ngày, cấu hình được qua `REVIEW_WINDOW_DAYS`.

## Alternatives considered

**Sửa không giới hạn.** Thân thiện với người dùng nhất, nhưng biến đánh giá thành
thứ có thể bị gây áp lực để thay đổi, và làm aggregate phải xử lý số lần cập nhật
không lường trước được.

**Không cho sửa.** Đơn giản nhất và bảo vệ tính toàn vẹn tốt nhất, nhưng người dùng
không sửa được cả lỗi chính tả, và họ sẽ bù bằng cách yêu cầu bộ phận hỗ trợ xóa
đánh giá — chuyển tải sang chỗ khác.

**Cửa sổ sửa theo thời gian, ví dụ 24 giờ.** Cho phép sửa thoải mái trong thời gian
ngắn. Nhược điểm: người dùng thường chỉ nhận ra vấn đề của sản phẩm sau vài ngày,
đúng lúc cửa sổ đã đóng. Hạn mức theo số lần khớp với hành vi thực tế hơn.

**Giữ tên trạng thái cũ.** Tránh phải đổi tài liệu, nhưng giữ lại một mô hình mà
người đọc phải học thuộc rằng "REJECTED" không có nghĩa là bị từ chối.

## Consequences

**Tích cực.** Không thể có đánh giá từ người chưa mua. Không thể có hai đánh giá cho
cùng một thứ. Tên trạng thái đọc đúng nghĩa. Database từ chối mọi tổ hợp
`status`/`attempt_count` sai, nên một bug ở tầng ứng dụng không thể âm thầm làm hỏng
dữ liệu.

**Tiêu cực.** Người dùng chỉ được sửa một lần và sẽ có người phàn nàn. Đánh giá của
một đơn bị hủy rồi mua lại sẽ là hai eligibility riêng — đúng về mặt nghiệp vụ nhưng
có thể gây bối rối. Bảng eligibility tăng theo số item đã bán chứ không theo số đánh
giá, nên nó sẽ lớn hơn bảng `reviews` nhiều lần.

**Constraint mới.** Mọi thay đổi trạng thái eligibility phải giữ `attempt_count`
khớp, nếu không transaction bị từ chối.

## Migration impact

Không có dữ liệu để migrate — service chưa được triển khai. Nếu sau này cần: ánh xạ
`REJECTED_FINAL` → `EDIT_LOCKED`, và `REJECTED_RETRY_PENDING` không có dữ liệu tương
ứng vì không luồng nào tạo ra nó.

Tài liệu Review cũ tham chiếu tên trạng thái cũ; bộ tài liệu này thay thế hoàn toàn.

## Verification

Integration test phải chứng minh: hai request tạo đánh giá song song chỉ thành công
một; lần sửa thứ hai bị từ chối với `EDIT_QUOTA_EXHAUSTED`; constraint
`ck_eligibility_status_matches_attempts` từ chối mọi tổ hợp sai khi ghi trực tiếp;
và không tồn tại đường code nào làm giảm `attempt_count`.
