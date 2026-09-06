---
doc_id: review-adr-007
service: review
status: accepted
date: 2026-09-05
decision_owners:
  - Review Service team
supersedes: null
evidence:
  - docs/review/schema/0001_initial_review_schema.sql
---

# ADR-007 — Tên tác giả là snapshot, không đồng bộ theo profile

## Status

`Accepted`

## Context

Mỗi đánh giá hiển thị tên người viết. Tên đó thuộc quyền sở hữu của Auth/User Service,
không phải Review. Có hai cách để Review có nó: lưu một bản sao tại thời điểm viết, hoặc
consume `USER_PROFILE_UPDATED` và đồng bộ liên tục.

Cách thứ hai nghe có vẻ "đúng" hơn về mặt nhất quán dữ liệu, nhưng nó tạo ra một đường
phụ thuộc mới: Review phải xử lý một luồng event có tần suất cao, chỉ để giữ cho một
trường hiển thị luôn khớp. Tần suất đổi tên hiển thị cao hơn tần suất viết đánh giá
nhiều lần.

Tương tự với `variant_name` — tên biến thể sản phẩm — thuộc Product Service.

## Decision

`reviews.user_name` là snapshot tại thời điểm tạo đánh giá và **không** được đồng bộ
lại. Review **không** consume `USER_PROFILE_UPDATED`.

Trường này chỉ thay đổi trong đúng một luồng: khi Auth phát `USER_DELETED`, Review đặt
nó thành giá trị ẩn danh và đặt `is_anonymized = true`.

`variant_name` cũng là snapshot, lấy từ event `ORDER_COMPLETED`, và không đồng bộ theo
thay đổi của Product.

## Alternatives considered

**Consume `USER_PROFILE_UPDATED` và cập nhật mọi đánh giá của người đó.** Tên luôn
khớp với profile hiện tại. Nhược điểm: một consumer nữa phải vận hành; mỗi lần một
người đổi tên là một lần ghi vào nhiều dòng `reviews` và phát nhiều event `REVIEW_UPDATED`
cho downstream; và Review phụ thuộc vào một contract mà nó không thực sự cần.

**Không lưu tên, gọi User Service khi đọc.** Luôn đúng và không có dữ liệu trùng lặp.
Nhược điểm: mỗi lần hiển thị danh sách 20 đánh giá là 20 lời gọi liên service, hoặc một
lời gọi hàng loạt — trên đường đọc nóng nhất của hệ thống, và tạo phụ thuộc runtime mà
[Architecture, mục 8](../architecture.md#8-architecture-constraints) cấm.

**Lưu tên nhưng làm mới định kỳ theo lô.** Kết hợp nhược điểm của cả hai: vẫn có dữ
liệu trùng lặp, vẫn cần một job, và vẫn không nhất quán tức thời.

## Consequences

**Tích cực.** Review không phụ thuộc vào contract profile của User. Ít một consumer để
vận hành. Đường đọc không có lời gọi liên service nào. Đánh giá phản ánh trạng thái tại
thời điểm nó được viết — điều này thực ra hợp lý về mặt nghiệp vụ: một bản ghi lịch sử
nên giữ bối cảnh lịch sử.

**Tiêu cực.** Người dùng đổi tên hiển thị sẽ thấy đánh giá cũ của mình vẫn mang tên cũ,
và có thể coi đó là lỗi. Không có cách nào để họ tự sửa.

**Hệ quả tương tự với `variant_name`.** Nếu Product đổi tên biến thể từ "Màu Đen, Size
L" thành "Đen / L", đánh giá cũ vẫn hiển thị tên cũ. Ở đây tính chất snapshot thậm chí
là mong muốn: người đọc cần biết người mua đã mua biến thể nào **lúc đó**.

**Ràng buộc mới.** Nếu sau này có yêu cầu đồng bộ tên, nó phải được cân nhắc như một
tính năng có chi phí vận hành riêng, không phải một bản vá nhỏ.

## Migration impact

Không có dữ liệu để migrate. Nếu về sau quyết định đồng bộ, cần: một consumer mới, một
đợt backfill cho dữ liệu cũ, và chấp nhận rằng backfill sẽ phát một lượng lớn
`REVIEW_UPDATED` cho downstream cùng lúc.

## Verification

Test phải chứng minh: Review không đăng ký consumer nào cho `USER_PROFILE_UPDATED`; tên
trong đánh giá không đổi sau khi profile thay đổi; và tên **có** đổi sau khi nhận
`USER_DELETED`, đúng một lần, với điều kiện `is_anonymized = false` ngăn việc phát event
trùng.
