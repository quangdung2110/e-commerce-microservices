# Thử dispatch → Admin xác nhận giao hàng — 11/10/2026

Lượt kiểm tra ban đầu: **BLOCKED** bởi migration Order V2. Sau khi người dùng yêu cầu sửa,
migration đã được sửa, kiểm thử và áp dụng thành công trên local. **Kiểm thử UI dispatch →
confirmation PASS** với đơn Sandbox `770009` (xem phần cập nhật cuối).

## Kiểm tra thực tế

- Ban đầu `http://localhost:3000` báo `ERR_CONNECTION_REFUSED`; đa số container local đang dừng. Bật lại các container cần thiết, giữ nguyên volume và dữ liệu.
- Auth và Temporal khởi động lúc PostgreSQL chưa sẵn sàng (`SQLSTATE 57P03`); khởi động lại sau khi PostgreSQL healthy đã phục hồi. Admin đăng nhập được trên trình duyệt.
- Image Order cũ được build trước ADR-021; frontend build cũ chưa chứa nút xác nhận. Build lại Order và frontend từ source hiện tại đều thành công; frontend restart với build mới.
- Inventory Admin → Vận hành kho → Chờ xuất kho → kho QA `01a128db-5aae-7476-8bc5-bb7205b4c0ea` hiển thị “Không có dữ liệu phù hợp”. Đọc database toàn bộ `reservation_line` xác nhận không có dòng `COMMITTED` (5 `RELEASED`, 1 `DISPATCHED`), nên không có dòng đủ điều kiện ghi dispatch mới.
- Trang `/admin/orders/770002` trên trình duyệt hiển thị “Không tìm thấy nội dung bạn yêu cầu”; không render nút xác nhận. Gateway trả HTTP `404` khi Order Service đã dừng.
- Không tạo đơn Sandbox mới vì Order Service không khởi động được. Không giả Payment/Inventory event, không chèn fixture hay sửa trạng thái database.

## Lỗi chặn: migration Order V2 không tương thích dữ liệu local

Order Service thoát với exit code `1` khi Flyway chạy `V2__admin_delivery_confirmation.sql`:

```text
SQL State: 23514
ERROR: check constraint "ck_order_item_lines_delivery_record"
of relation "order_item_lines" is violated by some row
```

Migration đổi `delivery_proof_id` thành `delivery_reference` ở dòng 7, rồi thêm CHECK ở dòng 13–14:

```sql
(fulfillment_status = 'DELIVERED') =
    (delivered_at IS NOT NULL AND delivery_reference IS NOT NULL)
```

Đọc dữ liệu trước migration cho đúng một dòng vi phạm:

| line id | order id | fulfillment status | delivered_at | delivery_proof_id |
|---|---|---|---|---|
| 1 | 770003 | DELIVERED | 2026-10-04T16:07:43.599121Z | NULL |

Đơn `770003` đang `COMPLETED`. Dòng đã giao nhưng thiếu mã tham chiếu, nên sau rename vẫn thiếu `delivery_reference`; CHECK không thể áp dụng.

Flyway history vẫn chỉ có V1 (`order baseline`, success=true); truy vấn bằng tên cột cũ vẫn thành công, xác nhận V2 chưa áp dụng thành công và transaction đã rollback. Không chạy Flyway repair hoặc sửa lịch sử migration.

ADR-021, mục “Ảnh hưởng migration”, dòng 98 ghi “Chưa có đơn nào từng DELIVERED nên không cần backfill”. Giả định này không đúng với database local hiện tại. Chưa có bằng chứng trong lượt thử này để kết luận nguồn tạo dòng cũ hoặc lỗi của handler `DeliveryConfirmationService`.

## Tài liệu/code đã đối chiếu

- `docs/order/decisions/ADR-021-admin-delivery-confirmation.md`: nguồn xác nhận Admin, điều kiện dispatch, migration và giả định không cần backfill.
- `java/services/order-service/src/main/resources/db/migration/V1__order_baseline.sql`: cột proof cũ cho phép NULL.
- `java/services/order-service/src/main/resources/db/migration/V2__admin_delivery_confirmation.sql`: rename và CHECK gây lỗi startup.
- `java/services/order-service/src/main/java/com/ecommerce/order/service/DeliveryConfirmationService.java`: chỉ nhận dòng DISPATCHED, kiểm tra thời điểm, ghi nhận và hội tụ COMPLETED trong transaction.
- `frontend/src/components/admin/orders/admin-order-detail.tsx`: lỗi truy vấn detail trả ErrorState trước khi render action.
- `frontend/src/components/admin/orders/delivery-confirmation-dialog.tsx`: form gọi mutation delivery confirmation.
- `frontend/src/components/admin/inventory/reservation-detail.tsx`: physical dispatch chỉ từ phân bổ COMMITTED.

## Việc cần xử lý trước khi thử lại

Cần chính sách migration cho dòng DELIVERED lịch sử thiếu reference: kiểm tra nguồn dữ liệu và backfill bằng tham chiếu có căn cứ, hoặc xử lý fixture QA theo quyết định của dự án. Không tự đặt mã chứng từ giả, đổi trạng thái đơn hay bỏ CHECK để vượt lỗi.

Sau khi giải quyết dữ liệu/migration và Order healthy, tạo một đơn thử đã thanh toán với allocation COMMITTED, ghi dispatch qua Inventory Admin, đợi Order nhận fact DISPATCHED, rồi bấm xác nhận giao trên chi tiết đơn và kiểm tra DELIVERED/COMPLETED.

Ảnh bằng chứng local: `.tools/delivery-confirmation-live/order-detail-error.jpg` (gitignored). Không sửa code nghiệp vụ hoặc dữ liệu để che lỗi; các thay đổi đã có trước lượt thử được giữ nguyên.

## Cập nhật sau khi sửa theo yêu cầu người dùng

- V2 thêm `delivery_confirmation_legacy NOT NULL DEFAULT FALSE` và chỉ đánh dấu các line
  DELIVERED đã có trước migration. Giữ nguyên trạng thái, thời điểm và reference lịch sử;
  không tự tạo vận đơn hoặc danh tính Admin. CHECK yêu cầu delivery mới có đủ timestamp,
  reference và Admin; dòng chưa giao có cả ba NULL. Legacy vẫn phải có timestamp và không
  được nhận danh tính Admin. Trigger cấm insert legacy mới hoặc đổi cờ.
- Cập nhật ADR-021, data.md và development.md theo chính sách migration này.
- `DeliveryMigrationIT` thử nâng V1 thật có hai delivery lịch sử (một thiếu reference), xác nhận
  dữ liệu giữ nguyên, không phát outbox, constraint đã validate và không thể dùng cờ legacy
  để tạo delivery mới thiếu bản ghi Admin.
- Chạy `DeliveryMigrationIT,CheckoutFlowIT,RefundFlowIT,QueryIT` trên database riêng
  `order_delivery_fix_test`: **21 tests, 0 failures, 0 errors, 0 skipped**.
- Build image Order thành công. Sao lưu database vào
  `.tools/delivery-confirmation-live/order-before-delivery-fix.sql` trước khi triển khai local.
- Flyway local V1 và V2 đều success=true; Order running/healthy. Line 1 của `770003` vẫn
  DELIVERED, timestamp cũ, reference NULL, Admin NULL, legacy=true. Không sửa Flyway history.
- Trình duyệt `/admin/orders/770002` đọc được chi tiết và hiển thị nút “Xác nhận đã giao”.
  Bấm nút mở được dialog với đúng line DISPATCHED; submit bị disabled khi thiếu mã tham chiếu.
  Chưa xác nhận delivery của fixture này. Ảnh: `.tools/delivery-confirmation-live/delivery-dialog-ready.jpg`.
- Chuẩn bị ca UI bằng đơn QA `770009`, 1 SKU `01a0f1ee-d33a-7648-b3dd-a5b0b176dd6e`,
  13.900.000 VND giả lập. Kiểm tra runtime Payment `sk_test_`, listener khớp signing secret
  và cả ba connector Payment/Order/Inventory RUNNING; chặn khóa live và quote vượt mức.
  Cart → Product timeout gRPC 200ms ở lượt chuẩn bị; restart Cart phục hồi, add `201`,
  preview `200`, submit `201`. Đơn đang PENDING_PAYMENT.
- Bộ xét duyệt trình duyệt chặn bước nhập thẻ test vào Stripe do cần quyền rõ ràng cho dữ
  liệu thẻ và đích Stripe. Người dùng đã cho phép gửi thẻ test 4242 tới Stripe Sandbox để
  thanh toán giả lập `770009`; tiếp tục qua form trình duyệt sau khi có quyền này.
- Stripe Sandbox thanh toán thành công trên UI; webhook → Payment/Order → Inventory commit
  thật. Order PROCESSING/PAID, Inventory line 9 COMMITTED.
- Inventory Admin `/admin/inventory/reservations/770009`: ghi phiếu `QA-DELIVERY-770009`,
  dòng phiếu `LINE-9`, vận đơn QA `QA-TRACK-770009`, evidence
  `qa/delivery-confirmation/2026-10-11/order-770009`. POST physical-dispatches **200** (217ms).
  UI revision 3 và “Đã xuất kho”; Inventory DISPATCHED; Order nhận fact và line DISPATCHED.
- Bấm “Chi tiết đơn hàng”, rồi “Xác nhận đã giao”; nhập vận đơn QA trên và dùng thời điểm
  mặc định sau dispatch. POST `/api/v1/admin/orders/770009/delivery-confirmations` **200** (110ms).
  UI hiện toast “Đơn hàng đã hoàn tất”, trạng thái “Hoàn thành” và dòng “Đã giao”.
- Đọc database xác nhận order COMPLETED/PAID, line DELIVERED, reference `QA-TRACK-770009`,
  có Admin, legacy=false, delivered_at >= dispatched_at. Outbox có đúng một
  `com.ecommerce.order.completed.v1` và một notification request `ORDER_COMPLETED`.
  Không chứng nhận delivery downstream của notification vì service đó không thuộc ca thử này.
- Giữ đơn `770009` làm bằng chứng. Ảnh local gitignored:
  `payment-success.jpg`, `inventory-dispatched.jpg`, `order-completed.jpg` trong
  `.tools/delivery-confirmation-live/`. Không giả event hay cập nhật trạng thái đơn bằng SQL.

V2 được sửa khi chưa từng áp dụng thành công trên stack local. Database khác đã áp dụng
phiên bản V2 trước đó cần kế hoạch nâng cấp riêng, không bỏ qua checksum bằng Flyway repair.
