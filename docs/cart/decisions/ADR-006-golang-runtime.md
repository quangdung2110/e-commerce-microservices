---
doc_id: cart-adr-006
service: cart
status: accepted
date: 2026-08-28
decision_owners:
  - Cart Service team
supersedes: null
evidence:
  - go/services/cart-service
---

# ADR-006 — Chọn Go làm runtime của Cart Service

## Status

`Accepted`

## Context

Hệ thống dùng cả Go và Java tùy service. Cart cần một lựa chọn phù hợp với hình dạng
công việc thực tế của nó, chứ không phải theo mặc định của tổ chức.

Hình dạng đó khá đặc trưng. Logic nghiệp vụ mỏng: thêm, sửa số lượng, xóa — không có
rule engine, không có máy trạng thái phức tạp, và phần khó nhất về tính đúng đắn đã
nằm trong Lua script chạy trên Redis chứ không nằm trong mã ứng dụng. Đổi lại, service
có nhiều tiến trình nền chạy liên tục: relay outbox, đồng bộ cold store, consumer đơn
hàng, trimmer, cùng vài cronjob. Yêu cầu độ trễ ở mức vài mili giây, và service cần
scale ngang nhanh khi có đợt khuyến mãi.

## Decision

Cart Service viết bằng Go, dùng Gin cho HTTP và client gRPC cho Product Service.

## Alternatives considered

**Java với Spring Boot.** Hệ sinh thái mạnh và đã được dùng cho các service khác
trong hệ thống. Nhưng những thứ Spring làm tốt nhất — quản lý transaction, ORM, máy
trạng thái, cache nhiều tầng — thì Cart gần như không dùng đến, vì trạng thái nằm
trên Redis và tính nguyên tử do Lua đảm nhiệm. Đổi lại phải trả thời gian khởi động
JVM và mức tiêu thụ bộ nhớ mỗi pod cao hơn đáng kể, đúng hai thứ cản trở việc scale
nhanh khi có đột biến tải. Không chọn vì chi phí không đổi lấy lợi ích tương ứng
trong bối cảnh này.

**Node.js.** Khởi động nhanh và phù hợp với I/O. Nhưng các tiến trình nền chạy dài
với nhiều luồng xử lý song song hợp với mô hình goroutine hơn, và hệ thống đã có sẵn
kinh nghiệm vận hành Go cho các service tương tự. Không chọn.

## Consequences

**Tích cực.** Goroutine phù hợp trực tiếp với hình dạng công việc: mỗi bucket stream
một vòng lặp đọc, mỗi consumer một vòng lặp xử lý, chi phí cho mỗi luồng ở mức không
đáng kể. Binary khởi động nhanh, giúp scale ngang phản ứng kịp với đột biến tải. Mức
tiêu thụ bộ nhớ mỗi pod thấp, nên chạy nhiều pod nhỏ là khả thi về chi phí.

**Tiêu cực.** Tổ chức phải duy trì hai hệ sinh thái backend. Go có ít khuôn khổ sẵn
cho những thứ như quản lý transaction hay ánh xạ dữ liệu, nên phần đó phải viết tay —
chấp nhận được ở đây vì Cart cần rất ít.

**Ràng buộc.** Phần lớn tính đúng đắn của service nằm trong Lua script chứ không nằm
trong mã Go. Điều này nghĩa là kiểm thử phải chạm tới Redis thật; test thuần đơn vị ở
tầng Go sẽ bỏ sót đúng những bất biến quan trọng nhất. Đây là hệ quả của kiến trúc chứ
không phải của Go, nhưng nó định hình cách viết test và cần được nói rõ.

## Migration impact

Không có; đây là quyết định ban đầu của service. Việc đổi runtime sau này đồng nghĩa
viết lại toàn bộ service, trong khi các Lua script và schema có thể giữ nguyên vì
chúng không phụ thuộc ngôn ngữ.

## Verification

- Service khởi động và sẵn sàng nhận traffic trong thời gian đủ ngắn để tự động scale
  có ý nghĩa; ngưỡng cụ thể cần đo trên hạ tầng thật.
- Mức tiêu thụ bộ nhớ mỗi pod nằm trong giới hạn cho phép chạy nhiều pod nhỏ.
- Bộ test bao phủ các bất biến nằm trong Lua script bằng cách chạy trên Redis thật.
