---
doc_id: review-adr-009
service: review
status: accepted
date: 2026-09-05
decision_owners:
  - Review Service team
supersedes: null
evidence:
  - contracts/catalog/services.yaml
  - docs/review/schema/0001_initial_review_schema.sql
---

# ADR-009 — Review sở hữu toàn bộ luồng báo cáo vi phạm

## Status

`Accepted`

## Context

Thiết kế trước mô tả luồng báo cáo theo hai cách mâu thuẫn nhau. Một mặt, nó định nghĩa
bảng `review_reports` nằm trong database của Review. Mặt khác, nó mô tả một consumer đọc
`t.report.events` từ một "Report Service", với ghi chú rằng nếu Report Service không phát
event thì "có thể dùng polling hoặc synchronous call từ API endpoint report".

Report Service không tồn tại: nó không có trong
[`contracts/catalog/services.yaml`](../../../contracts/catalog/services.yaml) và không có
thư mục tài liệu. Consumer đó không thể triển khai được.

Câu hỏi thật sự là: việc báo cáo nội dung vi phạm nên là một năng lực dùng chung cho
toàn hệ thống — báo cáo đánh giá, báo cáo sản phẩm, báo cáo tin nhắn — hay là việc riêng
của từng service sở hữu nội dung.

## Decision

Review sở hữu toàn bộ luồng báo cáo cho nội dung đánh giá: endpoint
`POST /api/v1/reviews/{review_id}/reports`, bảng `review_reports`, việc đếm ngưỡng, việc
ẩn đánh giá, và hàng đợi kiểm duyệt của admin.

Consumer `t.report.events` bị loại bỏ hoàn toàn khỏi thiết kế.

Report Service là hướng mở rộng có thể có trong tương lai, không phải giả định của thiết
kế hiện tại.

## Alternatives considered

**Report Service dùng chung cho mọi loại nội dung.** Đúng về mặt kiến trúc dài hạn: quy
tắc kiểm duyệt, ngưỡng, hàng đợi và giao diện quản trị nằm một chỗ, dùng lại được cho
sản phẩm, tin nhắn và mọi loại nội dung sau này. Nhược điểm ở thời điểm hiện tại: service
đó chưa tồn tại, và nếu Review phụ thuộc vào nó thì tính năng báo cáo sẽ không thể triển
khai cho tới khi có nó. Ngoài ra, việc ẩn đánh giá phải xảy ra trong cùng transaction với
việc đếm — nếu tách service, nó trở thành một luồng bất đồng bộ liên service cho một quyết
định vốn đơn giản.

**Giữ nguyên thiết kế cũ, chờ Report Service.** Nghĩa là giữ trong tài liệu một consumer
không thể triển khai. Tài liệu mô tả thứ không tồn tại thì tệ hơn là không mô tả.

**Review sở hữu dữ liệu báo cáo nhưng Report Service sở hữu quyết định.** Chia trách
nhiệm theo cách khó suy luận: hai service cùng chạm một quyết định, và không ai sở hữu
trọn vẹn tính đúng đắn của nó.

## Consequences

**Tích cực.** Luồng báo cáo triển khai được ngay, không chờ service nào. Việc đếm và việc
ẩn nằm trong cùng một transaction, nên nhiều báo cáo đồng thời quanh ngưỡng không thể
sinh ra hai lần ẩn hay bỏ sót. Ít một dependency liên service.

**Tiêu cực.** Khi có loại nội dung thứ hai cần báo cáo, logic này sẽ bị nhân bản. Quy tắc
kiểm duyệt và ngưỡng nằm rải rác thay vì tập trung. Admin sẽ có nhiều hàng đợi kiểm duyệt
riêng biệt thay vì một.

**Nợ kỹ thuật được chấp nhận.** Đây là quyết định đúng cho thời điểm hiện tại, không phải
đúng vĩnh viễn. Điều kiện để xem lại: khi loại nội dung thứ hai cần báo cáo xuất hiện, hoặc
khi nhu cầu về một giao diện kiểm duyệt thống nhất trở nên rõ ràng.

## Migration impact

Không có dữ liệu để migrate. Ảnh hưởng tới tài liệu: consumer group `review-report-processor`
và topic `t.report.events` không còn tồn tại trong bất kỳ tài liệu nào của Review.

Nếu Report Service ra đời sau này, đường chuyển đổi là: Report Service tiếp nhận việc lưu
report và quyết định ngưỡng; Review giữ lại `report_count` như một trường dẫn xuất và
consume một event kiểu `CONTENT_HIDDEN` để đổi `status`. Bảng `review_reports` khi đó
được migrate sang Report Service. Đây là một thay đổi có thể lên kế hoạch, không phải một
lần viết lại.

## Verification

Test phải chứng minh luồng báo cáo hoạt động end-to-end mà không cần bất kỳ service ngoài
nào. Kiểm tra tài liệu: không còn tham chiếu tới `t.report.events` hay
`review-report-processor` trong `docs/review/`.
