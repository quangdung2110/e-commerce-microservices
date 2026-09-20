# Ordering

**Trạng thái:** `proposed`

## 1. Thứ tự nào thực sự cần?

Ordering là thứ đắt nhất trong hệ phân tán. Câu hỏi đầu tiên phải là: **thứ tự nào, giữa những gì, nhìn từ ai?**

| Loại thứ tự | Cần? | Lý do |
|---|---|---|
| Giữa hai user khác nhau | **Không** | Email của A đến trước hay sau email của B không ai quan tâm |
| Giữa hai notification không liên quan của cùng user | **Không** | OTP và email khuyến mãi độc lập |
| Giữa các notification về **cùng một aggregate** (cùng đơn hàng) | **Có — thứ tự nhân quả, không phải thứ tự đến** | "Đơn đã huỷ" không được đến *trước* "Đơn đã xác nhận"; tệ hơn, "Đơn đã xác nhận" không được gửi *sau khi* đơn đã huỷ |
| Giữa các bản cập nhật preferences của cùng user | **Có, nghiêm ngặt** | Áp "tắt marketing" rồi "bật marketing" ngược thứ tự là sai trạng thái |

Nhu cầu thực là **thứ tự trong phạm vi một aggregate**, không phải toàn cục. Đây là thứ Kafka cho miễn phí bằng partition key — với điều kiện chọn key đúng.

## 2. Partition key: theo aggregate nhỏ nhất mà thứ tự có ý nghĩa

| Key | Ưu | Nhược |
|---|---|---|
| `user_id` | Mọi thứ của một user trên cùng partition → bao cả thứ tự theo đơn | **Hot key**: tài khoản doanh nghiệp đặt 5.000 đơn/ngày dồn vào một partition, 23 partition kia rảnh |
| `order_id` | Phân tán đều | "Đơn A xác nhận" và "đơn B xác nhận" của cùng user có thể ngược thứ tự — **không sao**, hai đơn độc lập |

Khuyến nghị: `order_id` cho event đơn hàng, `user_id` cho event tài khoản/preferences, `review_id` cho review. Key thường đã được producer quyết trong contract (`x-message-key`); việc của Notification là **kiểm tra key đó có đủ cho nhu cầu thứ tự của mình không**, và đàm phán nếu không, chứ không tự sửa.

Đối chiếu với contract hiện tại: Auth dùng `user_id` (đúng cho security event), Cart dùng `cart_id`, Review dùng `product_id` (đủ vì notification review theo review_id nằm trong product), User dùng `user_id` (bắt buộc cho preferences).

## 3. Sự thật khó chịu: thứ tự Kafka mất ở ranh giới 2

Kafka giữ thứ tự trong partition; consumer INSERT vào DB đúng thứ tự đó. Nhưng deliver worker **claim song song 20 row một lúc, 30 worker cùng chạy**, và retry đẩy row về sau bằng `next_attempt_at`. Thứ tự gửi thực tế ra provider là hỗn loạn có kiểm soát — **và đó là chủ ý**, vì song song hoá là toàn bộ mục đích của ranh giới 2.

Hệ quả cụ thể:

- Order phát `order.confirmed` lúc 14:00:00 và `order.cancelled` lúc 14:00:03 (khách bấm huỷ ngay).
- Hai row nằm đúng thứ tự trong DB.
- Worker A claim `confirmed`, gọi provider, provider chậm 8 s. Worker B claim `cancelled`, provider trả về ngay.
- Khách nhận "Đơn đã huỷ" lúc 14:00:04 và "Đơn đã xác nhận" lúc 14:00:12.

Không có bug; thứ tự đã bị hy sinh cho throughput.

## 4. Ba cách bảo vệ thứ tự nhân quả

### Cách 1 — Kiểm tra trạng thái hiện tại trước khi gửi ("read-your-latest")

Ngay trước khi render, worker hỏi: *sự thật này còn đúng không?* Với `order_confirmation`, kiểm tra trong bản replicate cục bộ (hoặc gọi Order) xem đơn còn `CONFIRMED` không. Nếu đã `CANCELLED` → `SKIPPED (superseded)`, không gửi.

Giải quyết trường hợp tệ nhất (gửi thông tin đã sai) mà không cần serialize gì. Điều kiện: producer kèm **version/sequence** của aggregate trong event (`sequence` extension trong convention đã có), để Notification so sánh "event này cũ hơn trạng thái tôi đã biết".

### Cách 2 — Hết hạn theo nội dung (TTL per template)

Mỗi template khai báo `max_age`; worker claim row có `created_at` cũ hơn `max_age` → `EXPIRED`, không gửi.

| Template | `max_age` | Lý do |
|---|---|---|
| `otp` | 5 phút | Quá hạn thì user đã bấm "gửi lại" và đang cầm mã mới; mã cũ đến sau gây nhầm. **Gửi đi còn hại hơn không gửi** |
| `order_confirmation` | 6 giờ | Sau đó khách đã biết qua app; email trễ chỉ gây bối rối |
| `cart_abandoned` | 24 giờ | Nhắc giỏ hàng của hôm kia là vô nghĩa |
| `promotion` | tới hết chiến dịch | |

TTL vừa là ordering (không gửi thứ lỗi thời) vừa là backpressure (xem [06-backpressure.md](./06-backpressure.md)): khi hàng đợi dồn và xả mất 3 giờ, row bulk tạo lúc đầu vẫn có nghĩa, nhưng OTP nằm 6 phút thì tự rụng.

### Cách 3 — Serialize theo aggregate ở deliver worker

Chỉ khi thực sự cần thứ tự gửi nghiêm ngặt (hiếm — ví dụ chuỗi SMS nhiều phần). Thêm cột `ordering_key`; câu claim thêm điều kiện "không có row nào cùng `ordering_key` đang `SENDING`":

```sql
... AND NOT EXISTS (
        SELECT 1 FROM notification_delivery s
        WHERE  s.ordering_key = d.ordering_key AND s.status = 'SENDING')
```

Đắt hơn nhiều (subquery trong claim, giảm song song) → **chỉ bật cho template khai báo cần**, mặc định tắt.

**Với dự án này, cách 1 + cách 2 đủ cho 99 % trường hợp.** Cách 3 giữ làm lối thoát.

## 5. Retry phá thứ tự — và đó là đúng

Thứ tự và retry là hai mục tiêu xung đột, và ta **chọn retry**. Giữ thứ tự nghiêm ngặt nghĩa là một row fail phải chặn mọi row sau nó cùng key cho tới khi thành công — chính là head-of-line blocking vừa loại bỏ. Cách 1 ở trên là lưới an toàn cho row retry: khi nó cuối cùng được gửi, nếu sự thật đã thay đổi thì tự bỏ.

## 6. Preferences: nơi duy nhất cần thứ tự nghiêm ngặt

Preferences replicate từ `t.user.events` là trạng thái, không phải thông báo. Áp dụng ngược thứ tự là sai vĩnh viễn (cho tới daily reconciliation). Cách xử lý:

- Partition key `user_id` (contract đã có) → thứ tự trong partition đảm bảo.
- Consumer preferences là consumer riêng, không đi qua ranh giới 2 (không có deliver worker nào song song hoá nó).
- Áp dụng bằng compare-and-set trên version: `UPDATE user_preferences SET ... WHERE user_id = ? AND version < ?`. Event cũ đến muộn (sau replay) không ghi đè được trạng thái mới hơn.
- Daily reconciliation của User Service (đã có trong contract) là lưới an toàn cuối.

## 7. Tóm lại

Chỉ trả tiền cho thứ tự trong phạm vi aggregate. Chấp nhận thứ tự Kafka mất ở ranh giới 2 vì đó là chủ ý. Bảo vệ tính đúng đắn bằng *kiểm tra trạng thái trước khi gửi* và *TTL theo template*, không bằng serialize toàn bộ. Preferences là ngoại lệ duy nhất cần thứ tự nghiêm ngặt, và nó đi đường riêng.
