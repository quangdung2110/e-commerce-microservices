# Lane ưu tiên

**Trạng thái:** `proposed`

## 1. Vấn đề

Notification không phải một workload mà là nhiều workload có SLO khác nhau đi chung một cửa:

| Lớp | Ví dụ trong dự án | SLO thực tế | Đặc điểm |
|---|---|---|---|
| **Critical** | OTP, reset password, email changed, account locked | p99 < 3–5 s, không được mất | Volume thấp; trễ đồng nghĩa user không đăng nhập được |
| **Transactional** | order confirmed, order completed, payment failed, review replied | p99 < 30–60 s | Volume tỉ lệ với đơn hàng; bursty theo flash sale |
| **Bulk / engagement** | cart abandoned, khuyến mãi, digest | phút → giờ | Volume lớn nhất; có thể throttle hoặc tạm dừng |

Một partition Kafka là log tuần tự: consumer đọc từ offset thấp lên cao, không nhảy cóc được. Nếu ba lớp trên đi chung một topic thì message quan trọng luôn có thể bị kẹt sau hàng trăm nghìn message không quan trọng. Đây là **head-of-line blocking**.

### Ví dụ: đêm flash sale

- 20:00 marketing đẩy 300.000 email khuyến mãi vào Kafka trong 2 phút. Worker gửi được 150 email/s (quota provider).
- 20:03 một user hết phiên, Auth phát `userOtpRequested`. OTP hết hạn sau 5 phút.
- Với một topic chung 24 partition, message OTP rơi vào partition đang có ~12.500 email khuyến mãi phía trước, mỗi partition được ~6 email/s → OTP được gửi sau **~35 phút**.

Dashboard lúc đó: delivered 100 %, error 0 %, lag đang giảm. Không alert nào bắn. Sự cố chỉ lộ ra qua ticket support sáng hôm sau — và chỉ với những user đăng nhập trong lúc chiến dịch chạy, tức những người đang muốn mua.

## 2. Vì sao đây là quyết định kiến trúc số một

1. **Lỗi vô hình với metric thông thường.** Không mất message, không lỗi provider. Chỉ có "đến quá muộn".
2. **Xảy ra đúng lúc business cần nhất.** Hàng đợi chỉ dài khi có chiến dịch lớn — cũng là lúc traffic đăng nhập và thanh toán cao nhất.
3. **Sửa sau rất đắt.** Tách lane sau khi đã có một topic chung nghĩa là sửa contract của mọi producer (Auth, Cart, Review, Order, ...), chờ họ release, chạy dual-publish. Tách từ đầu chỉ là vài dòng `address` khác nhau trong contract. Đây là quyết định **lộ ra ngoài contract**, thuộc nhóm quyết định một chiều.

## 3. Lane là gì: tách ở cả ba tầng

Lane = một đường đi vật lý riêng **từ đầu đến cuối**. Phải tách cả ba tầng; thiếu tầng nào thì tầng đó trở thành điểm nghẽn chung.

| Tầng | Nếu dùng chung | Khi tách lane |
|---|---|---|
| **Topic** | 1 topic `notification.requests` | `t.notification.critical.v1`, `t.notification.transactional.v1`, `t.notification.bulk.v1` |
| **Consumer group + worker pool** | 1 pool đọc tất cả; pool bận xả bulk thì không có tay rảnh cho critical | Consumer group riêng, deliver pool riêng cho từng lane |
| **Quota provider** | 1 rate limiter 150/s dùng chung; 30 worker bulk chiếm hết, provider trả `429` cho chính worker critical | Critical được dành riêng một phần quota mà bulk không được mượn |

Trong một lane, ordering vẫn theo partition key của producer (ví dụ `user_id`). Lane tách theo *loại*, không tách theo *người*.

## 4. Partition, consumer, worker là ba thứ khác nhau

Điểm hay bị lẫn khi cấu hình:

```
Kafka topic ──partition──▶ Kafka consumer ──INSERT──▶ Bảng delivery ──poll──▶ Deliver worker ──▶ Provider
            (ranh giới 1)                              (ranh giới 2)
```

| Khái niệm | Nằm ở | Quyết định | Đổi sau |
|---|---|---|---|
| **Partition** | Kafka | Trần song song của consumer đọc Kafka; phạm vi ordering | Khó: tăng partition đổi hash key → phá ordering; là thay đổi contract |
| **Consumer instance** | Ranh giới 1 | Bao nhiêu process đọc Kafka; tối đa = số partition | Dễ: scale replica |
| **Deliver worker** | Ranh giới 2 | Bao nhiêu thread gọi provider đồng thời | Dễ: config pool |

**Deliver worker không đọc Kafka, nó đọc bảng DB.** Nên số worker không bị giới hạn bởi số partition: lane critical có thể chỉ 3 partition (volume thấp) nhưng vẫn có 10 thread gửi. Đây là lợi ích phụ của việc tách hai ranh giới (xem [02-pipeline.md](./02-pipeline.md)).

## 5. Cấu hình đề xuất (giả định, cần chốt bằng đo đạc)

| Lane | Topic | Partition | Consumer (ranh giới 1) | Deliver pool (ranh giới 2) | Quota email | DLQ |
|---|---|---|---|---|---|---|
| Critical | `t.notification.critical.v1` | 3 | 1–3 replica, group `notification-critical` | 10 thread | 20/s dành riêng | `t.notification.critical.v1.dlq` |
| Transactional | `t.notification.transactional.v1` | 12 | 2–6 replica, group `notification-transactional` | 20 thread | 50/s dành riêng | `t.notification.transactional.v1.dlq` |
| Bulk | `t.notification.bulk.v1` | 24 | 2–12 replica, group `notification-bulk` | 30 thread | tối đa 80/s | `t.notification.bulk.v1.dlq` |

Giải thích:

- **Critical ít partition** vì volume nhỏ và consumer chỉ làm ~10 ms/message; 3 partition chịu được hàng trăm msg/s. Nhiều hơn không hại nhưng vô ích.
- **Bulk nhiều partition** không phải để đọc nhanh hơn mà để xả Kafka nhanh khi 300k message dồn vào, và để dư đầu vì tăng sau khó.
- **Consumer group riêng cho mỗi topic**, không phải một group subscribe ba topic. Group chung thì một rebalance ở bulk vẫn làm dừng consumer critical vài giây. Ba group này thường là ba deployment của cùng codebase, khác biến môi trường `LANE`.
- **Không có fan-out giữa ba topic.** Một message chỉ được producer ghi vào đúng một topic và được Notification đọc đúng một lần. Ba topic là ba hàng đợi song song, không phải ba bản sao. Fan-out (nhiều consumer group cùng đọc một topic) chỉ xảy ra ở các domain fact topic mà Notification consume chung với Analytics, Inventory, ...
- **Deliver pool tách theo lane, bên trong tách theo kênh**: pool critical thực chất là `critical-email` + `critical-inapp` (phase 1, D-08; thêm `critical-push` sau này chỉ là thêm pool). Tách theo kênh xảy ra ở ranh giới 2, **không** tách topic theo kênh, vì kênh là kết quả của stage Decide (sau khi đọc preferences), chưa biết lúc producer publish.

## 6. Ai chọn lane

Phụ thuộc loại đầu vào (D-07):

- **Request**: **producer chọn topic, ghi trong contract.** Order publish `ORDER_CONFIRMED` thẳng lên `t.notification.transactional.v1`; Auth lên lane critical. Người hiểu rõ mức độ khẩn nhất là producer; không có tầng trung gian nào phải đọc hết bulk mới tới OTP.
- **Fact**: producer không biết (và không cần biết) Notification sẽ làm gì với fact, nên **Notification gán lane trong bảng ánh xạ fact → template** ([02-pipeline.md](./02-pipeline.md) §2.2): `cart.abandoned → bulk` (phase 1 chỉ có một nguồn fact). Consumer fact đọc topic của producer rồi INSERT với `lane` tương ứng; từ ranh giới 2 trở đi, row đó đi đúng pool và quota của lane.

Lưu ý: với fact, việc tách lane chỉ bắt đầu từ **ranh giới 2** (pool + quota), không có ở tầng topic — vì topic là của producer. Điều này chấp nhận được vì fact topic (`t.cart.events`) không trộn khối lượng bulk với critical: OTP không bao giờ đi qua đó.

Phương án thay thế cho request — một topic vào, Notification tự route — tạo ra đúng điểm nghẽn chung mà lane đang cố tránh, nên bị loại.

Hệ quả cần quản lý: producer có động cơ tự nâng mình lên critical. Contract phải ghi tiêu chí rõ ràng, và Notification là owner phê duyệt việc gán lane. Tiêu chí đề xuất:

| Lane | Tiêu chí |
|---|---|
| Critical | User đang **chờ chủ động** để hoàn thành một hành động (OTP, reset password), hoặc là cảnh báo bảo mật (email/password changed, account locked). |
| Transactional | Xác nhận một sự thật nghiệp vụ đã xảy ra với user (đơn hàng, giao hàng, phản hồi review). Trễ vài chục phút không gây thiệt hại. |
| Bulk | Không gắn với hành động cụ thể của user; có thể tạm dừng hoặc bỏ nếu quá hạn. |

### Trường hợp Auth hiện tại

Contract Auth đang sở hữu topic riêng `t.auth.notification.events` với `x-access-policy: chỉ Notification consume`, và payload chỉ có `delivery_id` (nội dung resolve qua API riêng). Hai lựa chọn:

1. Auth chuyển sang publish lên `t.notification.critical.v1` theo schema `notification.requested.v1` — một lane, một topic, một group. Nhưng schema chung có `template_data` inline, còn Auth cố ý không đưa secret vào Kafka, nên Auth sẽ cần một biến thể "reference-only" của schema.
2. **Giữ topic Auth** và Notification consume nó bằng consumer group riêng, coi như lane critical thứ hai. Tốn thêm một group phải vận hành, nhưng không phải sửa contract Auth và giữ nguyên mô hình bảo mật của họ.

Khuyến nghị phase 1: **lựa chọn 2**. Đây là quyết định mở A4 trong [README §6](./README.md).

## 7. Không tách quá tay

Ba lane là đủ. Cám dỗ thường gặp là tách thêm theo kênh, theo tenant, theo loại nghiệp vụ → 15 topic, 15 consumer group, phần lớn thời gian nằm không, và mỗi group là một thứ phải monitor.

Quy tắc: **tách topic chỉ khi hai luồng có SLO khác nhau và có khả năng chặn nhau.** Email và in-app cùng lane critical không chặn nhau (pool riêng ở ranh giới 2) nên không cần topic riêng. OTP và khuyến mãi thì có, nên cần.

### Vì sao không dùng partition thay cho topic

Có thể gán partition 0–3 cho critical, 4–47 cho bulk và assign consumer thủ công. Nhưng nó phá auto-rebalance của consumer group, producer phải tự tính partition thay vì để Kafka hash theo key, và ordering theo `user_id` trong cùng lớp không còn đảm bảo. Đổi lại tiết kiệm được hai topic. Không đáng.
