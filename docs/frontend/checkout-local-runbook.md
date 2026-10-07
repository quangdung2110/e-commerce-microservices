# Checkout local — backend readiness và Stripe Sandbox

PowerShell từ gốc repo; phạm vi bước 6 không voucher. Bằng chứng nằm ở
[step-6-verification](./step-6-verification.md). Giữ dữ liệu hiện hữu.

## 1. User, migration và Cart

```powershell
docker compose config --quiet
docker compose up -d --build user-service cart-service
```

Root Compose chạy `user-migrate` trước User, DB `user_service`. Khi thêm migration:
`docker compose run --rm user-migrate`. Volume cũ thiếu DB cần tạo riêng DB trước;
schema nạp thủ công cần đối chiếu trước khi baseline version. Không dùng `down -v`,
migrate down hay tự force version để bỏ qua lỗi. Cart qua Gateway 8088; nội bộ giữ
`cart-service:8080`. Host 8085 dành cho Promotion.

Root PostgreSQL đặt `max_connections=300` cho nhiều service pool, Temporal và CDC;
default 100 có thể cạn khi bật cả stack. Đây là ngân sách local, không thay sizing
production. Thay command PostgreSQL cần recreate container, giữ volume. User image
và standalone dùng GET `/health/ready`; `wget --spider` gửi HEAD không được router hỗ trợ.

## 2. Stripe Sandbox local

Merge biến trong `.env.checkout.example` vào `.env.local` ở **gốc repo**, thay
placeholder bằng khóa cùng Sandbox. File đã ignore; lệnh Compose dùng Payment cần
`--env-file .env.local`. Giữ `PAYMENT_CRYPTO_LOCAL_KEY` ổn định để đọc dữ liệu mã hóa
đã lưu. Không commit file local hoặc in resolved Compose config có secret.

Terminal riêng; CLI phải dùng cùng Sandbox với bộ khóa:

```powershell
stripe login
stripe listen --forward-to localhost:8088/v1/payments/webhook/stripe --events payment_intent.processing,payment_intent.succeeded,payment_intent.payment_failed,payment_intent.canceled
```

Copy signing secret của listener vào `PAYMENT_STRIPE_WEBHOOK_SECRETS`; giữ CLI chạy.
8088 kiểm chứng Gateway; 8089 gọi Payment trực tiếp để debug; frontend 3000 không
rewrite webhook. Dùng snapshot event theo SDK/API pin hiện tại. Payment development
liệt kê thêm canonical refund/dispute events khi mở rộng kiểm thử.

## 3. Start core stack

```powershell
docker compose --env-file .env.local --profile payment up -d --build api-gateway auth-service gateway-auth product-service promotion-service user-service cart-service inventory-service temporal order-service payment-service checkout-service kafka-connect
```

Sau khi đổi env cần recreate để nạp cấu hình:

```powershell
docker compose --env-file .env.local --profile payment up -d --force-recreate payment-service
```

Kiểm tra health, log migration/consumer/worker. Checkout readiness chỉ kiểm tra
Redis/script/config của nó; downstream vẫn có thể khiến Preview/Submit lỗi.
Payment Actuator healthy chưa xác nhận key với Stripe hay outbox đã relay.

## 4. CDC trước fixture mua hàng

```powershell
pwsh -NoProfile -File scripts/register-checkout-connectors.ps1 -ValidateOnly
pwsh -NoProfile -File scripts/register-checkout-connectors.ps1
pwsh -NoProfile -File scripts/register-checkout-connectors.ps1 -CheckOnly
```

Script thay đúng DB fields trong template chuẩn, giữ nguyên EventRouter expression.
Default DB là `postgres:5432`, user/password `postgres` của root dev stack; custom
credential dùng tham số `DatabaseHost`, `DatabasePort`, `DatabaseUsername`,
`DatabasePassword` qua biến local. Script không đọc `.env.local`, không dùng chung
`DB_NAME` cho các service và không in config chứa password.

Connector **và mọi task** phải RUNNING; FAILED task làm script exit lỗi. `CheckOnly`
không PUT config; `ValidateOnly` không gọi mạng. PUT có thể restart connector hiện
hữu; không đăng ký lại giữa một ca test. Script không reset offset/slot.

| Connector | DB | Luồng |
|---|---|---|
| payment-outbox-connector | payment_service | outbox → t.payment.events.v2 → Order |
| order-outbox-connector | order_service | outbox → t.order.events → Cart |
| inventory-outbox-connector | inventory_service | published_event → stock/order topics |

`snapshot.mode=no_data` không snapshot outbox cũ khi khởi tạo connector. Tạo đơn mới
sau khi connector ready; event cũ cần runbook recovery riêng.

## 5. Buyer và SKU

Buyer phải verify qua Auth và có profile do User xử lý `USER_VERIFIED_SUCCESS`.
Login không tự bootstrap. Kiểm tra profile/addresses qua phiên Gateway thật, tạo địa
chỉ hợp lệ. Account cũ thiếu profile: kiểm tra consumer group/retention trước replay
được hỗ trợ. Không giả identity/event hoặc tự chèn row để đánh dấu đạt.

Trước khi kiểm chứng bootstrap trên root stack đã có dữ liệu:

```powershell
pwsh -NoProfile -File scripts/check-auth-kafka.ps1
```

Script so Topic ID trong broker với `partition.metadata`, không sửa dữ liệu. Một
volume cũ đã tái hiện ID không khớp: consumer nhận NOT_LEADER dù topic describe có
leader. Restart broker và đổi client protocol không sửa được. Root ZooKeeper nay
persist qua `zookeeper-data`/`zookeeper-log`; dữ liệu stack này đã được copy trước
khi đổi mount. Stack cũ khác phải migration dữ liệu anonymous volume trước khi
áp dụng Compose mới, không mount volume rỗng vào metadata hiện hữu.

Recovery của stack này đã giữ backup Kafka/ZooKeeper và checksum, phục hồi znode
ID theo lịch sử disk nhất quán bằng `scripts/restore-auth-topic-ids.ps1`. Chỉ dùng
script khi đã có audit/backup phù hợp, broker và clients dừng; không áp dụng cho
cluster nhiều broker, divergent partition history hay topic đã được recreate hợp
lệ. Script có rollback journal và từ chối metadata khác audit. Không sửa Topic ID
trong log, xóa topic/volume, reset offset hay tạo event giả. Kafka retention vẫn có
thể dọn segment sau restart; archive phải giữ riêng. Không tự chạy restore định kỳ.

Sau bảo trì broker/ZooKeeper, kiểm tra cả connector lẫn task; REST/health có thể UP
trong khi connector UNASSIGNED. Trên local single-worker nếu không tự phục hồi,
`docker compose restart kafka-connect` rồi chạy helper `-CheckOnly`; không reset CDC
offset/slot. ZooKeeper có readiness probe và Kafka đợi service healthy khi start.

Để giữ API User chạy trong lúc triage, có thể đặt `USER_KAFKA_CONSUMER_ENABLED=false`
trong env local rồi recreate User. Đây là chế độ tạm thời: không tạo profile mới,
không đạt gate bootstrap/mua hàng. Sau phục hồi bật lại true, kiểm tra lag và buyer.

SKU active, giá hợp lệ, có stock khả dụng và kho phù hợp; thêm giỏ qua API/UI.
Hành trình search/PDP cần Search/Elasticsearch/Product Detail BFF/MinIO và relay
Product/Inventory cho projection. Bật luồng Notification/email nếu fixture verify
cần email hoặc kiểm tra hậu mãi; Order PAID không chứng minh notification đã gửi.

## 6. Gate E2E

Thanh toán **đơn từ UI**, đối chiếu token → order ID → attempt → Stripe PaymentIntent
→ webhook → Payment outbox → Kafka → Order PAID → Inventory commit và Order event
→ Cart cleanup. Không log client_secret, key, address hoặc full payload.
`stripe trigger payment_intent.succeeded` chỉ smoke webhook fixture khác, không thay E2E.

| Ca | Tiêu chí |
|---|---|
| Success: 4242 4242 4242 4242 | PAID, Inventory commit, Cart dọn dòng đã mua |
| 3DS: 4000 0025 0000 3155 | Xác thực rồi cập nhật Order từ backend |
| Decline: 4000 0000 0000 0002 | Lỗi đúng, không báo PAID; retry theo attempt policy |
| Quote expired / đổi địa chỉ | Quote mới và user xác nhận lại |
| Submit lặp / mất response / reload | Cùng token chỉ một đơn, phục hồi submission |
| Webhook replay | Không duplicate financial movement/tác dụng nghiệp vụ |
| Ownership | Buyer khác không đọc submission/payment credentials |
| UI | 375/768/1280 px, light/dark, focus, bàn phím, pending/error/empty, Stripe Element |

Dùng thẻ test, ngày tương lai và CVC test; không dùng live key. Tham chiếu:
[Stripe webhooks](https://docs.stripe.com/webhooks), [Stripe testing](https://docs.stripe.com/testing).
Ghi ngày, commit, command/result và ID không nhạy cảm trước khi đánh dấu PASS.
