---
doc_id: cart-adr-005
service: cart
status: accepted
date: 2026-08-28
decision_owners:
  - Cart Service team
supersedes: null
evidence:
  - docs/cart/workflows.md
  - contracts/grpc/ecommerce/product/v1/product_service.proto
---

# ADR-005 — Pull Model cho dữ liệu Product thay vì đồng bộ đẩy

## Status

`Accepted`. Pull model vẫn là thiết kế hiện hành; [ADR-011](./ADR-011-current-pull-read-and-redis-outbox-scope.md)
chỉ điều chỉnh các chi tiết không còn đúng (GET không còn ghi lại snapshot vào Redis).

## Context

Giỏ hàng phải hiển thị giá hiện tại và trạng thái còn bán của từng sản phẩm, nhưng
Product Service mới là nơi sở hữu dữ liệu đó. Câu hỏi là Cart lấy dữ liệu này bằng
cách nào.

Cách tiếp cận đẩy — Cart lắng nghe event thay đổi giá từ Product và cập nhật snapshot
trong các giỏ liên quan — đòi hỏi một chỉ mục ngược từ SKU sang danh sách giỏ đang
chứa SKU đó. Hệ quả của chỉ mục này lộ ra ngay khi nhìn vào một sản phẩm bán chạy
trong đợt khuyến mãi: một lần đổi giá của một SKU đang nằm trong hàng trăm nghìn giỏ
sẽ sinh ra hàng trăm nghìn thao tác ghi, dồn vào Redis trong vài giây.

Nói cách khác, sự kiện càng quan trọng về mặt kinh doanh — đổi giá một sản phẩm hot —
thì càng có khả năng làm sập chính hệ thống giỏ hàng. Đây là bão ghi, và nó xảy ra
đúng vào lúc tệ nhất.

## Decision

Cart **không** đồng bộ dữ liệu Product theo cơ chế đẩy và **không** xây chỉ mục ngược
từ SKU sang giỏ.

Thay vào đó, Cart lấy dữ liệu Product theo cơ chế kéo, đúng vào lúc người dùng thực
sự cần: khi họ đọc giỏ, và khi họ thêm hàng. Một lời gọi gRPC multi-get cho toàn bộ
SKU trong giỏ, không phải một lời gọi cho mỗi SKU.

Lời gọi này bắt buộc có deadline cứng và bắt buộc được bọc circuit breaker.

Hai đường xử lý khác nhau khi Product không trả lời:

- **Đọc giỏ**: suy giảm mềm. Trả snapshot đang có kèm cờ báo giá có thể chưa mới, và
  vẫn trả thành công. Giỏ hàng không được phép chết theo Product Service.
- **Ghi giỏ**: từ chối. Không xác nhận được sản phẩm còn bán thì không đưa vào giỏ;
  đoán ở đây tệ hơn là báo lỗi.

Snapshot lưu trong Redis là dữ liệu hiển thị tại thời điểm thêm vào giỏ, cố tình cũ.
Giá hiện tại không bao giờ được lưu — nó chỉ tồn tại trong response.

## Alternatives considered

**Chỉ mục ngược cộng consumer event từ Product.** Cho dữ liệu tươi nhất và không phụ
thuộc Product lúc đọc. Nhưng tạo ra bão ghi như mô tả trên, và chỉ mục ngược tự nó là
một cấu trúc dữ liệu lớn cần duy trì đồng bộ. Không chọn.

**Worker nền định kỳ làm mới snapshot toàn bộ giỏ.** Tránh được đỉnh tải tức thời vì
trải đều theo thời gian. Nhưng phần lớn công việc là vô ích — đa số giỏ không được ai
mở ra xem — và dữ liệu vẫn cũ giữa hai lượt chạy. Không chọn.

**Gọi Product cho từng SKU khi đọc giỏ.** Đơn giản về code, nhưng một giỏ 20 SKU
thành 20 lời gọi mạng. Không chọn.

**Không hiển thị giá hiện tại, chỉ hiển thị giá lúc thêm.** Loại bỏ hoàn toàn phụ
thuộc. Nhưng người dùng cần biết giá đã thay đổi trước khi checkout, và giấu thông
tin đó là trải nghiệm tệ. Không chọn.

## Consequences

**Tích cực.** Không có bão ghi, bất kể sản phẩm nóng đến đâu. Không có chỉ mục ngược
để duy trì. Chi phí lấy dữ liệu tỉ lệ với số lần người dùng thực sự xem giỏ, chứ
không tỉ lệ với số lần giá thay đổi. Cart không bao giờ sập kéo theo Product.

**Tiêu cực.** Mỗi lần đọc giỏ là một lời gọi mạng thêm, nằm thẳng trong đường latency
của một API vốn yêu cầu độ trễ thấp. Đây là cái giá trực tiếp và nó xuất hiện ở mọi
request, không chỉ khi có sự cố.

**Dữ liệu cũ có chủ đích.** Giữa hai lần người dùng mở giỏ, dữ liệu giá hoàn toàn có
thể đã lỗi thời. Hệ quả quan trọng: **Checkout Service không được tin giá do Cart trả
về** mà phải tự xác nhận lại. Cart hiển thị giá để tham khảo, không phải để chốt giao
dịch.

**Suy giảm khó nhận biết.** Khi circuit breaker mở kéo dài, mọi response vẫn là `200`
và mọi biểu đồ lỗi vẫn xanh, trong khi toàn bộ người dùng đang xem giá cũ. Vì vậy tỷ
lệ response suy giảm phải là một chỉ số được cảnh báo, không chỉ là một cờ trong
response.

**Ngoại lệ về tính nhất quán.** Khi Product báo một SKU không còn bán, Cart ghi lại
đúng một trường vào Redis dù đang trong đường đọc. Đây là ngoại lệ có chủ đích của
quy tắc chỉ-đọc, vì để trạng thái đó chỉ tồn tại trong một response là không đủ:
luồng checkout tiếp theo cần thấy nó.

## Migration impact

**Code.** Xóa worker đồng bộ snapshot và toàn bộ chỉ mục ngược khỏi thiết kế. Mọi
đường lấy dữ liệu Product phải đi qua client có circuit breaker.

**Data.** Không còn cấu trúc dữ liệu nào ánh xạ từ SKU sang giỏ.

**Contract.** Cart trở thành consumer của gRPC API Product Service; thay đổi ở API đó
ảnh hưởng trực tiếp tới cả đường đọc lẫn đường ghi của Cart.

**Đối với service khác.** Checkout phải tự xác nhận giá; điều này cần được nêu rõ
trong contract giữa hai bên chứ không để ngầm hiểu.

## Verification

- Không tồn tại cấu trúc dữ liệu nào ánh xạ ngược từ SKU sang danh sách giỏ.
- Có test chứng minh đọc giỏ vẫn trả thành công khi Product timeout, kèm cờ suy giảm.
- Có test chứng minh ghi giỏ bị từ chối khi không xác nhận được sản phẩm.
- Có test chứng minh trạng thái không còn bán được ghi lại vào Redis mà không đụng TTL.
- Có metric và cảnh báo trên tỷ lệ response suy giảm và trạng thái circuit breaker.
