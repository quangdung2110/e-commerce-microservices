# Ordering

**Trạng thái:** `proposed`

## 1. Thứ tự nào thực sự cần?

Ordering là thứ đắt nhất trong hệ phân tán. Câu hỏi đầu tiên phải là: **thứ tự nào, giữa những gì, nhìn từ ai?**


| Loại thứ tự                                                     | Cần?                                            | Lý do                                                                                                                |
| --------------------------------------------------------------- | ----------------------------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| Giữa hai user khác nhau                                         | **Không**                                       | Email của A đến trước hay sau email của B không ai quan tâm                                                          |
| Giữa hai notification không liên quan của cùng user             | **Không**                                       | OTP và email khuyến mãi độc lập                                                                                      |
| Giữa các notification về **cùng một aggregate** (cùng đơn hàng) | **Có — thứ tự nhân quả, không phải thứ tự đến** | "Đơn đã huỷ" không được đến *trước* "Đơn đã xác nhận"; tệ hơn, "Đơn đã xác nhận" không được gửi *sau khi* đơn đã huỷ |
| Giữa các bản cập nhật preferences của cùng user                 | **Có, nghiêm ngặt**                             | Áp "tắt marketing" rồi "bật marketing" ngược thứ tự là sai trạng thái                                                |


Nhu cầu thực là **thứ tự trong phạm vi một aggregate**, không phải toàn cục. Đây là thứ Kafka cho miễn phí bằng partition key — với điều kiện chọn key đúng.

## 2. Partition key: theo aggregate nhỏ nhất mà thứ tự có ý nghĩa


| Key        | Ưu                                                                | Nhược                                                                                                   |
| ---------- | ----------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| `user_id`  | Mọi thứ của một user trên cùng partition → bao cả thứ tự theo đơn | **Hot key**: tài khoản doanh nghiệp đặt 5.000 đơn/ngày dồn vào một partition, 23 partition kia rảnh     |
| `order_id` | Phân tán đều                                                      | "Đơn A xác nhận" và "đơn B xác nhận" của cùng user có thể ngược thứ tự — **không sao**, hai đơn độc lập |


Khuyến nghị: `order_id` cho event đơn hàng, `user_id` cho event tài khoản/preferences, `review_id` cho review. Key thường đã được producer quyết trong contract (`x-message-key`); việc của Notification là **kiểm tra key đó có đủ cho nhu cầu thứ tự của mình không**, và đàm phán nếu không, chứ không tự sửa.

Đối chiếu với contract hiện tại: Auth dùng `user_id` (đúng cho security event), Cart dùng `cart_id`, Review dùng `product_id` (đủ vì notification review theo review_id nằm trong product), User dùng `user_id` (bắt buộc cho preferences). Với request topic `t.notification.*.v1` do Notification sở hữu, key = `subject` (aggregate id, ví dụ `order_id`) — producer đặt `subject` theo aggregate mà thông báo nói về.

## 3. Sự thật khó chịu: thứ tự Kafka mất ở ranh giới 2

Kafka giữ thứ tự trong partition; consumer INSERT vào DB đúng thứ tự đó. Nhưng deliver worker **claim song song 20 row một lúc, 30 worker cùng chạy**, và retry đẩy row về sau bằng `next_attempt_at`. Thứ tự gửi thực tế ra provider là hỗn loạn có kiểm soát — **và đó là chủ ý**, vì song song hoá là toàn bộ mục đích của ranh giới 2.

Hệ quả cụ thể nếu không có cơ chế bảo vệ riêng cho Order:

- Order phát `ORDER_PAYMENT_FAILED` với `sequence=7` lúc 14:00:00, sau đó checkout hết hạn và phát `ORDER_CANCELLED` với `sequence=8` lúc 14:03:00.
- Hai row được ingest đúng thứ tự nhưng deliver worker claim song song.
- Worker A gọi provider cho sequence 7 và bị chậm; worker B gửi sequence 8 ngay.
- Khách có thể nhận thông báo huỷ trước rồi lại nhận thông báo mời thử thanh toán — một nghịch đảo nhân quả có hại.

Vì vậy throughput song song vẫn là mặc định, nhưng lane transactional của Order là ngoại lệ có thứ tự theo từng `order_id`.

## 4. Ba cách bảo vệ thứ tự nhân quả



### Cách 1 — Kiểm tra trạng thái hiện tại trước khi gửi ("read-your-latest")

Ngay trước khi render, worker hỏi: *sự thật này còn đúng không?* Nếu aggregate đã sang trạng thái khiến thông báo vô nghĩa → `SKIPPED (superseded)`, không gửi.

Với quyết định D-07, phạm vi áp dụng của cách này **hẹp hơn** so với thiết kế ban đầu:


| Nguồn                             | Ai chịu trách nhiệm "còn đúng không"                                                                                                                                                 | Notification làm gì                                                                                                                                                        |
| --------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Request** (Order, Review, Auth) | **Producer.** Order phát sequence tăng đơn điệu riêng cho notification trong cùng transaction với outbox command; Auth kèm `expires_at` cho OTP. | Với `/services/order`, persist highest sequence theo `source + subject`, phát hiện collision và skip sequence thấp hơn; không gọi ngược Order và không replicate state machine. Với nguồn khác chỉ tôn trọng contract/TTL tương ứng. |
| **Fact** (Cart)                   | Notification, trong phạm vi dữ liệu nó tự có                                                                                                                                         | Ví dụ: `CART_ABANDONED` đang chờ trong hàng đợi bulk mà nhận `cart.checked-out` cho cùng `cart_id` → `SKIPPED (superseded)`. Cần producer kèm `sequence` (convention đã có) để so sánh. **Phase 1: no-op** — contract Cart chỉ có `cart.abandoned.v1`, không có fact nào "vượt qua" nó; hook `superseded()` trong worker tồn tại nhưng luôn trả false. |


Nghĩa là Notification không cần gọi ngược Order hay giữ bản sao state machine của đơn hàng; nó chỉ cần theo dõi những fact nó đã consume sẵn.

### Cách 2 — Hết hạn theo nội dung (TTL per template)

Mỗi template khai báo `max_age`; worker claim row có `created_at` cũ hơn `max_age` → `EXPIRED`, không gửi.


| Template          | `max_age`          | Lý do                                                                                                          |
| ----------------- | ------------------ | -------------------------------------------------------------------------------------------------------------- |
| `otp`             | 5 phút             | Quá hạn thì user đã bấm "gửi lại" và đang cầm mã mới; mã cũ đến sau gây nhầm. **Gửi đi còn hại hơn không gửi** |
| `ORDER_CONFIRMED` | 6 giờ              | Sau đó khách đã biết qua app; email trễ chỉ gây bối rối                                                        |
| `cart_abandoned`  | 24 giờ             | Nhắc giỏ hàng của hôm kia là vô nghĩa                                                                          |
| `promotion`       | tới hết chiến dịch |                                                                                                                |


TTL vừa là ordering (không gửi thứ lỗi thời) vừa là backpressure (xem [06-backpressure.md](./06-backpressure.md)): khi hàng đợi dồn và xả mất 3 giờ, row bulk tạo lúc đầu vẫn có nghĩa, nhưng OTP nằm 6 phút thì tự rụng.

### Cách 3 — Serialize theo aggregate ở deliver worker

Với request transactional của Order, cách này **được bật bắt buộc**; các luồng khác mặc định không bật:

1. Logical scope là `source + subject`; persisted `ordering_key` dùng
   `source:key-version:HMAC(subject)` để không giữ raw order ID vô hạn.
2. Worker acquire row trong `notification_ordering_lease` bằng compare-and-set có `lease_until`; chỉ một delivery cùng order được gọi provider tại một thời điểm.
3. Sau khi có lease, worker đọc lại `notification_subject_sequence.highest_sequence`. Nếu delivery có sequence thấp hơn, chuyển `SKIPPED (superseded)` mà không gọi provider.
4. Worker giữ lease xuyên suốt provider call và release trong `finally`; reaper chỉ thu hồi lease hết hạn. Timeout phải lớn hơn provider timeout cộng safety margin.

Lease chỉ serialize **cùng một order**, không serialize toàn bộ partition hay toàn bộ Notification. Thiết kế này tránh subquery/race kiểu "không có row `SENDING`" và vẫn cho phép các order khác chạy song song.

Giới hạn bắt buộc phải hiểu đúng: lease bảo vệ thứ tự **bắt đầu provider call** khi worker
còn quyền sở hữu. Nó không thể buộc SES/mailbox giao email theo thứ tự, và timeout sau khi
provider đã accept tạo kết quả không chắc chắn. Vì vậy Order saga tuyệt đối không suy ra
business state từ thứ tự notification; `ordering_lease_lost`/`possibly_duplicate` phải
được quan sát và xử lý như delivery anomaly, không sửa FSM Order.

## 5. Retry và thứ tự

Với traffic thông thường, retry vẫn có thể đổi thứ tự và TTL/supersession là lưới an toàn. Riêng Order:

- Nếu sequence cũ fail rồi sequence mới được ingest, `highest_sequence` khiến lần retry cũ bị `SKIPPED (superseded)`.
- Nếu sequence cũ đang ở trong provider call, sequence mới phải chờ cùng ordering lease; do đó thứ tự bắt đầu provider call không bị đảo.
- Lease không biến email/SMS thành exactly-once: provider có thể timeout sau khi đã nhận request. Idempotency key theo delivery vẫn là lớp chống gửi trùng; reconciliation xử lý kết quả không chắc chắn.

Không giữ row cũ để chặn vô hạn row mới: sequence mới có quyền supersede retry cũ chưa bắt đầu provider call.

## 6. Preferences: state projection cần thứ tự nghiêm ngặt

Preferences replicate từ `t.user.events` là trạng thái, không phải thông báo. Áp dụng ngược thứ tự là sai vĩnh viễn (cho tới daily reconciliation). Cách xử lý:

- Partition key `user_id` (contract đã có) → thứ tự trong partition đảm bảo.
- Consumer preferences là consumer riêng, không đi qua ranh giới 2 (không có deliver worker nào song song hoá nó).
- Áp dụng bằng compare-and-set trên version: `UPDATE user_preferences SET ... WHERE user_id = ? AND version < ?`. Event cũ đến muộn (sau replay) không ghi đè được trạng thái mới hơn.
- Daily reconciliation của User Service (đã có trong contract) là lưới an toàn cuối.



## 7. Tóm lại

Chỉ trả chi phí ordering trong phạm vi aggregate. Traffic thông thường chấp nhận việc retry/worker song song làm đổi thứ tự và dựa vào TTL/supersession. Order transactional là ngoại lệ rõ ràng: sequence tăng đơn điệu riêng, highest-sequence state và durable per-order lease ngăn thông báo cũ được gửi sau thông báo mới. Preferences vẫn đi đường projection riêng với compare-and-set theo version.
