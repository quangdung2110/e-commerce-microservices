---
doc_id: review-adr-008
service: review
status: accepted
date: 2026-09-05
decision_owners:
  - Review Service team
supersedes: null
evidence:
  - docs/review/schema/0001_initial_review_schema.sql
---

# ADR-008 — Tự động duyệt kèm ngưỡng báo cáo thay cho tiền kiểm duyệt

## Status

`Accepted`

## Context

Đánh giá là nội dung do người dùng viết, nên sẽ có spam, nội dung xúc phạm và đánh giá
giả. Có hai hướng xử lý: kiểm duyệt trước khi hiển thị, hoặc hiển thị ngay rồi xử lý
khi có vấn đề.

Tiền kiểm duyệt cần một đội ngũ vận hành và tạo độ trễ giữa lúc người dùng gửi đánh giá
và lúc nó xuất hiện — người viết sẽ tưởng hệ thống nuốt mất bài của mình. Với một hệ
thống chưa có đội kiểm duyệt, tiền kiểm duyệt trên thực tế nghĩa là đánh giá không bao
giờ hiển thị.

Thiết kế trước đã chọn tự động duyệt, nhưng để lại một mảnh chưa hoàn chỉnh: nó định
nghĩa event `REVIEW_APPROVED` mà không có luồng nào phát ra event đó, và không có cách
nào để hiện lại một đánh giá đã bị ẩn nhầm.

## Decision

Đánh giá được tạo với `status = 'APPROVED'` và hiển thị ngay. Không có bước duyệt trước.

Kiểm soát chất lượng đến từ báo cáo của cộng đồng: khi `active_report_count` đạt
`report_threshold` (mặc định 10, cấu hình qua `REVIEW_REPORT_THRESHOLD`), đánh giá tự
động chuyển `HIDDEN_BY_REPORT` và bị loại khỏi cả danh sách lẫn aggregate.

Việc ẩn là **có thể hoàn tác**. Admin có endpoint bác bỏ báo cáo: đánh giá quay lại
`APPROVED`, các report liên quan chuyển `DISMISSED`, `active_report_count` được tính lại từ
số report còn `PENDING`, và `REVIEW_APPROVED` được phát để aggregator cộng lại.

`REVIEW_APPROVED` vì vậy có đúng một nguồn phát: thao tác hiện lại đánh giá. Đây là
điều làm event đó có nghĩa.

Response của endpoint báo cáo **không** tiết lộ `active_report_count` hay việc đánh giá đã bị
ẩn hay chưa.

## Alternatives considered

**Tiền kiểm duyệt toàn bộ.** An toàn nhất về nội dung. Nhược điểm: cần đội ngũ vận
hành mà hệ thống chưa có; tạo độ trễ mà người dùng cảm nhận được; và biến việc gửi đánh
giá thành một hàng đợi mà khi tồn đọng sẽ chặn toàn bộ tính năng.

**Lọc tự động bằng mô hình phân loại nội dung.** Bắt được spam rõ ràng mà không cần
người. Nhược điểm: cần một dependency mới; có dương tính giả, và một đánh giá tiêu cực
thật bị chặn nhầm là thiệt hại lớn hơn một spam lọt qua. Đây là hướng bổ sung hợp lý về
sau, không phải hướng thay thế.

**Ẩn ngay từ báo cáo đầu tiên, chờ admin duyệt.** Phản ứng nhanh nhất. Nhưng nó biến
mỗi người dùng thành một công tắc tắt đánh giá của người khác — một công cụ quấy rối
hoàn hảo.

**Ngưỡng theo tỷ lệ thay vì số tuyệt đối.** Ví dụ: ẩn khi số báo cáo vượt 5% số người
xem. Công bằng hơn giữa đánh giá cũ và mới, nhưng cần số liệu lượt xem mà Review không
có, và phức tạp hơn nhiều so với giá trị thu được.

## Consequences

**Tích cực.** Người dùng thấy đánh giá của mình ngay. Không cần đội kiểm duyệt để hệ
thống vận hành. Nội dung xấu vẫn có đường biến mất mà không cần can thiệp thủ công. Việc
ẩn hoàn tác được nên sai lầm không vĩnh viễn.

**Tiêu cực.** Nội dung xấu hiển thị cho tới khi đủ người báo cáo. Với sản phẩm ít người
xem, ngưỡng có thể không bao giờ đạt tới.

**Rủi ro lạm dụng.** Một nhóm đủ đông có thể phối hợp ẩn các đánh giá thật. Ba lớp giảm
thiểu: mỗi người chỉ báo cáo một lần cho một đánh giá; response không tiết lộ tiến độ
tới ngưỡng, nên không dò được ngưỡng chính xác; và admin xem lại được, với việc tính
lại counter bảo đảm đánh giá không bị ẩn lại bởi chính những report đã bị bác bỏ.

**`report_threshold` là tham số nhạy cảm.** Quá thấp thì dễ bị lạm dụng; quá cao thì
nội dung xấu tồn tại lâu. Không bao giờ được lộ ra API công khai.

## Migration impact

Không có dữ liệu để migrate. Nếu về sau thêm tiền kiểm duyệt hoặc lọc tự động, sẽ cần
một trạng thái mới của `reviews` (ví dụ `PENDING_MODERATION`) và một ADR thay thế ADR
này — không phải một cờ cấu hình.

## Verification

Test phải chứng minh: báo cáo thứ N chạm ngưỡng sinh **đúng một** `REVIEW_HIDDEN` kể cả
khi mười người báo cáo đồng thời; sau khi admin bác bỏ, đánh giá không bị ẩn lại bởi
những report đó; report mới sau đó tích lũy lại từ đầu; và response của endpoint báo cáo
không chứa `active_report_count` hay trạng thái ẩn.
