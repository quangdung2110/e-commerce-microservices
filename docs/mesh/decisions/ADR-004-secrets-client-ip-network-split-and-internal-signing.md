# ADR-004: Secret fail-fast, IP client thật qua gateway, tách network, chữ ký cho API nội bộ của Auth, role DB riêng

- Status: Accepted
- Date: 2026-10-22
- Bổ sung: [ADR-002](ADR-002-local-runtime-exposure-and-broker-auth.md) (Việc còn mở 2 một phần, Việc còn mở 4),
  [ADR-003](ADR-003-kafka-sasl-scram-and-acls.md)

## Context

Security review chỉ đọc code ngày 2026-10-22 có bốn lỗi High, sau khi C1–C4 đã sửa ở [ADR-002](ADR-002-local-runtime-exposure-and-broker-auth.md)
và [ADR-003](ADR-003-kafka-sasl-scram-and-acls.md):

| # | Lỗi | Ví dụ khai thác |
|---|---|---|
| H1 | Secret mặc định/placeholder vẫn khởi động được: `PAYMENT_STRIPE_WEBHOOK_SECRETS:-whsec_local_change_me` (route webhook công khai qua gateway), `AUTH_KEYS_MASTER_KEY` hardcode trong compose (mã hoá private key JWT), khoá Checkout, `EXPORT_CURSOR_SECRET` có fallback ngay trong `application.yml` của Product, HMAC và khoá S3 `minioadmin` của Review | Quên đặt biến là ai có repository cũng ký được `payment_intent.succeeded` hoặc giải mã khoá ký JWT |
| H2 | Rate limit theo IP của Auth là một bucket chung: `trusted_proxies: []` nên `c.ClientIP()` luôn là IP container Traefik, và trình duyệt đi qua rewrite `/api/*` của Next nên Traefik lại chỉ thấy IP frontend. Middleware `rate-limit-auth` không gắn router nào; các rate limit khác dùng `ipStrategy.depth: 2` mà không khai báo proxy tin cậy (request không có `X-Forwarded-For` rơi vào khoá rỗng chung) | Vài request ẩn danh khoá login (20/5 phút) và reset mật khẩu (5/giờ) của toàn bộ người dùng |
| H3 | Hạ tầng dùng credential mặc định: mọi service đăng nhập Postgres bằng superuser `postgres/postgres`, Review dùng root MinIO `minioadmin`, Elasticsearch tắt security, REST của Kafka Connect và Temporal không xác thực, Grafana mật khẩu mặc định | Một service bị chiếm đọc/ghi database của mọi service khác; một container đọc mọi bucket |
| H4 | Mạng phẳng: `auth-service:8081/.../notification-deliveries/{id}/content` trả OTP và link reset cho ai gửi `X-Service-Identity: notification-service`; User `:8083`, Cart `/internal/**` cũng chỉ dựa vào header; mọi container (kể cả `frontend` đối mặt Internet, Mailpit, LocalStack) chung `ecommerce-network` | Frontend bị chiếm (SSRF, RCE trong Next) đọc OTP/link reset của bất kỳ ai |

## Decision

### 1. Secret: fail-fast ngoài môi trường dev (H1)

Mọi service có secret dừng lúc khởi động khi **không** ở môi trường dev mà một secret: trống, ngắn hơn mức tối thiểu, trùng giá trị
dev đã commit trong repository, hoặc chứa dấu hiệu placeholder (`change_me`, `change-me`, `changeme`, `change-in-prod`, `local`,
`dev-`, `dev_`, `development`, `example`, `placeholder`, `not-a-secret`, `minioadmin`). Giá trị base64 được kiểm cả sau khi giải mã,
nên khoá Checkout `bG9jYWwtZGV2LW9ubHkt…` ("local-dev-only-…") bị nhận ra.

| Service | Secret được kiểm | Tối thiểu | "Môi trường dev" |
|---|---|---|---|
| Auth (Go) | `keys.master_key`, `keys.previous_master_keys`, `internal.signing_key` | 32 byte | `app.env` = `development`/`dev` (đã có `IsProduction()`; thêm `IsDevelopment()` cùng nghĩa với Notification) |
| Notification (Go) | `privacy.hmac_key`, `auth.signing_key` | 32 byte | `app.env` = `development`/`dev` |
| Product | `product.export.cursor-secret` | 32 | profile `dev`, `local` hoặc `test` |
| Review | hai HMAC, `review.storage.access-key`/`secret-key` | 32 / 3 / 20 | như trên |
| Checkout | từng phiên bản trong `token-hmac-keys`, `encryption-keys` | 44 ký tự base64 | như trên |
| Payment | `payment.stripe.secret-key`, từng `webhook-secrets` (chỉ khi được cấu hình; thiếu thì bean webhook không có, route đóng) | 20 | như trên; riêng `test` bỏ qua |

Payment thêm một luật cho chính môi trường dev: placeholder webhook chỉ được chấp nhận khi Stripe API key cũng là placeholder (Payment
khi đó không tạo được PaymentIntent thật nên event giả không có gì để xác nhận). Đặt `sk_test_` thật mà quên secret của
`stripe listen` thì Payment dừng thay vì để webhook ai cũng ký được. `payment.crypto.local-key` vốn đã chỉ được dùng ở profile
`dev`/`test` (`LocalCryptoConfiguration`).

Fallback secret bị gỡ khỏi `application.yml` (Product `EXPORT_CURSOR_SECRET`, cùng giá trị mặc định trong `ProductProperties`; Review
`REVIEW_S3_*`); giá trị dev nằm trong `application-dev.yml` (chạy từ IDE) và `docker-compose.yml`. docker-compose (và `deploy/k8s` sinh
từ nó) bật profile `local` cho Product, Review (`prod,local`) và Checkout; Payment vẫn `dev`; Auth/Notification giữ `app.env:
development` trong `configs/application.yaml`. Java dùng một lớp `SecretPolicy` + `StartupSecretGuard` trong từng service (không có
module chung); Go dùng `internal/common/config/secrets.go` ở Auth và Notification. Hai bản giữ cùng danh sách dấu hiệu.

### 2. IP client thật qua chuỗi Next → Traefik → Auth (H2)

- **Frontend** (`frontend/client-ip-server.cjs`, `CMD` của image) ghi đè `X-Forwarded-For` và `X-Real-Ip` bằng địa chỉ TCP của
  peer trước khi Next xử lý request. Next 16 không tự thêm IP vào rewrite ra ngoài và chuyển nguyên header client gửi, nên trước đây
  client chọn được bucket của mình và gateway chỉ thấy IP frontend.
- **Traefik** chỉ tin `forwardedHeaders` từ frontend: compose `trustedIPs: [10.89.8.10/32]` (địa chỉ cố định của frontend trên
  `ecommerce-edge`); k8s `10.1.0.0/16` (pod CIDR Docker Desktop kubeadm, pod IP không cố định) cùng `externalTrafficPolicy: Local` cho
  Service LoadBalancer để traffic ngoài không mang địa chỉ trong pod CIDR. Từ mọi peer khác, kể cả client gọi thẳng `:8088`, Traefik
  bỏ hai header đó và đặt `X-Real-Ip` = địa chỉ peer.
- **Rate limit của gateway** (`rate-limit-default`, `-api`, `-payment`, `-auth`) dùng `sourceCriterion.requestHeaderName: X-Real-Ip`
  thay cho `ipStrategy.depth: 2`. Router công khai `auth-service` gắn `rate-limit-auth` (10 req/s, burst 20 theo IP).
- **Auth** chỉ tin địa chỉ gateway (`security.trusted_proxies`: compose `10.89.3.2/32` qua `AUTH_SECURITY_TRUSTED_PROXIES`, k8s pod
  CIDR) và chỉ đọc `X-Real-IP` (`security.client_ip_headers`). `X-Forwarded-For` không được đọc: qua Next, hop cuối của nó là
  frontend. User và Notification không dùng IP client nên không đổi.

Trên Docker Desktop, mọi kết nối từ máy host tới cổng publish mang cùng một địa chỉ của proxy cổng (đo được `10.89.8.128`): mọi
trình duyệt **trên cùng máy dev** vẫn chung một bucket. Đó là giới hạn NAT của Docker Desktop, không phải của chuỗi tin cậy; trên Linux không dùng
userland proxy, địa chỉ client thật đi tới container.

### 3. Tách network trong compose (H4)

| Network | Thành viên | Ý nghĩa |
|---|---|---|
| `ecommerce-network` (10.89.0.0/22, cấp động trong 10.89.0.0/23) | service, data store, broker, Traefik (`10.89.3.2` cố định), Prometheus/Loki/Jaeger/Alertmanager/Alloy | Như cũ |
| `ecommerce-edge` (10.89.8.0/24) | `frontend` (`10.89.8.10`), Traefik (`10.89.8.2`) | Container đối mặt Internet chỉ tới được gateway |
| `notification-providers` | Mailpit, LocalStack, Notification | Giả lập SES/SNS/SQS không thấy service nào |
| `temporal-admin` | Temporal, Temporal UI | UI không xác thực chỉ thấy Temporal. Temporal ở hai network nên `BIND_ON_IP=0.0.0.0` và quảng bá địa chỉ cố định `10.89.3.3` (`TEMPORAL_BROADCAST_ADDRESS`); không vậy auto-setup chỉ bind địa chỉ đầu tiên và healthcheck/Order không tới được |
| `observability` | Grafana, Prometheus, Loki, Jaeger, Alertmanager | Grafana (mật khẩu mặc định) không là đường vào service |
| `kafka-metadata` | ZooKeeper, Kafka | Như ADR-003 |

Ở k8s, NetworkPolicy sinh bởi `deploy/k8s/generate.py` vốn cho mỗi workload đúng caller của nó (frontend, Mailpit, LocalStack không
gọi được service nào). Thêm: listener nội bộ ở cổng riêng (`INTERNAL_LISTENERS`: Auth `internal` 8081 chỉ Notification, User
`internal` 8083 chỉ Checkout); Traefik, gateway-auth, Prometheus chỉ tới cổng public. Cart phục vụ `/internal/**` trên cùng cổng
8080 nên chỉ mesh (L7) tách được.

### 4. Chữ ký cho API nội bộ của Auth (H4, lớp phòng thủ thứ hai)

Mọi request tới `/internal/v1/auth/**` mang `X-Service-Signature: t=<unix giây>,v1=<hex HMAC-SHA256>` trên chuỗi
`t\nMETHOD\nescaped-path\nX-Service-Identity` với khoá chung `AUTH_INTERNAL_SIGNING_KEY` (Auth `internal.signing_key`, Notification
`auth.signing_key`). Auth trả `401` khi thiếu, sai, lệch quá `internal.signature_tolerance` (60 s) hoặc khác method/path/caller; kiểm
sau allowlist `X-Service-Identity`, trước rate limit và handler. Hai đầu có chung một test vector. Khoá trống chỉ được phép ở
development (bỏ kiểm). Không chọn bearer secret tĩnh: request đi HTTP thường trong mạng, chữ ký có thời hạn không để lộ khoá và chỉ
dùng lại được cho đúng path trong 60 s.

Giá trị dev của khoá nằm trong compose (anchor `x-internal-signing-key`) nên với compose, ranh giới thật là tách network ở mục 3; đặt
`AUTH_INTERNAL_SIGNING_KEY` trong `.env` để chữ ký có giá trị ở máy dev. Ngoài development, giá trị đó làm Auth và Notification dừng.

### 5. Credential hạ tầng (H3)

- **PostgreSQL: mỗi service một login role, không còn superuser.** [`infra/postgres/service-roles.txt`](../../../infra/postgres/service-roles.txt)
  (database, role, biến mật khẩu) và `service-roles.sh` chạy mỗi lần `docker compose up` (`postgres-init`, mọi service có database chờ
  nó) và là Job `postgres-roles-init` ở k8s. Idempotent: tạo/đặt lại mật khẩu role `NOSUPERUSER NOCREATEDB NOCREATEROLE`, chuyển chủ sở
  hữu database và mọi schema, bảng, partition, view, sequence, function, type sang role (bỏ object của extension), `REVOKE CONNECT ON
  DATABASE … FROM PUBLIC`, và default privileges để object superuser tạo tay vẫn dùng được tới lần chạy sau. Migration chạy bằng role
  của service (`user-migrate`, `auth-migrate` ở k8s, Flyway). Superuser `postgres` còn lại cho Temporal, Debezium (replication),
  postgres-exporter, công cụ local qua `localhost:15432`; mật khẩu của nó vẫn là `postgres` (đổi trên volume có sẵn cần thao tác tay).
- **MinIO:** `minio-init` tạo user `review-service` với policy [`review-media-rw.json`](../../../infra/minio/policies/review-media-rw.json)
  (chỉ bucket `review-media`); Review dùng user này. Root (`MINIO_ROOT_USER/PASSWORD`, mặc định vẫn `minioadmin`) chỉ cho `minio-init`
  và console, đọc lại mỗi lần start nên đổi được trong `.env`.
- **Grafana, Temporal UI, Mailpit, LocalStack:** loopback (ADR-002) và network riêng (mục 3).

## Alternatives considered

- **Bật xpack security cho Elasticsearch ngay**, kèm user riêng cho Search. Chưa làm: `frontend/scripts/seed_search_data.mjs`,
  `tests/load/seed-es-data.js`, `scripts/ci/search-*` và `docs/search` gọi `localhost:9200` không xác thực; healthcheck và manifest k8s
  cũng phải đổi. Search đã đọc `ES_USERNAME`/`ES_PASSWORD`. Sau mục 3, Elasticsearch chỉ tới được từ `ecommerce-network` và loopback.
- **Basic auth cho REST của Kafka Connect** (`BasicAuthSecurityRestExtension` + JAAS file). Chưa làm: `kafka-connect-init`,
  healthcheck, `scripts/` và runbook Debezium đều gọi REST không xác thực, và cấu hình Connect vừa đổi ở ADR-003. Cùng phạm vi tiếp
  cận như Elasticsearch.
- **Xác thực Temporal** (mTLS + authorizer). Không làm ở local: cần CA và cấu hình client của Order; Temporal chỉ có Order và UI gọi.
- **Bearer secret tĩnh hoặc ký `X-User-*`/`X-Service-Identity` cho mọi service.** Ký mọi caller vẫn là việc còn mở 2 của ADR-002
  (13 service). Chỉ API nội bộ của Auth được ký vì nó trả secret (OTP, link reset) ở dạng plaintext.
- **Một biến `ALLOW_DEV_SECRETS` thay cho profile/env.** Không chọn: Payment đã dùng profile `dev`/`test` làm ranh giới và Go đã có
  `app.env`; thêm một biến nữa tạo hai nguồn sự thật.
- **Tin `X-Forwarded-For` với `ipStrategy.depth`** thay cho `X-Real-Ip`. Không chọn: độ sâu phụ thuộc request có đi qua Next hay không
  (client có thể gọi thẳng `:8088`), và request không có header rơi vào khoá rỗng chung.

## Consequences

- Một deployment ngoài dev thiếu hoặc mang secret dev/placeholder không khởi động được; log nêu đúng biến cần đặt.
- Hai trình duyệt khác IP có bucket rate limit riêng ở gateway và ở Auth; header client tự gửi không đổi được bucket.
- Từ frontend, Mailpit, LocalStack, Temporal UI, Grafana không còn mở được kết nối tới service nội bộ; OTP/link reset của Auth cần
  chữ ký. Container mới cần gọi service phải vào đúng network.
- `docker compose up` lần đầu sau thay đổi phải tạo lại network: `docker compose down` (không `-v`) rồi `up -d`. Subnet cố định
  `10.89.0.0/22` và `10.89.8.0/24` không được trùng mạng khác trên máy.
- Service không còn đọc database của service khác; nạp schema bằng `postgres` (Auth ở compose) xong thì chạy `docker compose up
  postgres-init` hoặc nạp bằng role của service. `scripts/` và tài liệu dùng `postgres/postgres` qua `localhost:15432` không đổi.
- Mailpit và LocalStack chỉ còn trong `notification-providers`: công cụ khác muốn gọi chúng dùng cổng loopback trên host.

## Việc còn mở

1. Elasticsearch security, basic auth REST Kafka Connect, xác thực Temporal (Alternatives).
2. Đổi mật khẩu superuser `postgres` và root MinIO mặc định, `checkout-redis` chưa mật khẩu, TLS giữa service.
3. Ký mọi caller nội bộ (User `:8083`, Cart `/internal/**`, Inventory, Order…) hoặc cài mesh — ADR-002 Việc còn mở 2.
4. Pod CIDR `10.1.0.0/16` của `deploy/k8s` là giá trị mặc định của Docker Desktop kubeadm, chưa đo trên cụm đang chạy.

## Verification (2026-10-22/23, stack compose chạy lại sau `down` không `-v`)

- Test: Auth unit (`go vet`, `./internal/... ./test/unit/...`), Notification `./internal/...`, `mvn test` của Product, Review, Payment
  xanh. Checkout: `StartupSecretGuardTest` xanh; ba lần chạy cả bộ mỗi lần có 1–4 test khác nhau lỗi timeout/deadline (`PreviewApiTest`,
  `RecoveryApiTest`, `ResilienceTest`, `ClientTransportsTest`) trong lúc host paging nặng; một lần chạy cùng cây không có guard xanh.
- `docker compose config` (kể cả profile `payment`, `notification`), `generate.py --check`, `kubectl kustomize` + `kubeconform -strict`
  ba lớp (38/101/53 resource), redocly lint Auth internal v1.1.0.
- H2: client `10.89.8.129`, `.130` trên `ecommerce-edge` gửi `X-Forwarded-For`/`X-Real-Ip: 6.6.6.6` thẳng tới gateway và qua
  `frontend:3000`: upstream nhận `X-Real-Ip` = địa chỉ thật của từng client. `password-reset-requests` (5/giờ/IP): client A và C đổi IP
  giả mỗi request vẫn `202×5, 429, 429`; client B, D cùng lúc vẫn `202`. Router `auth-service@docker` có `rate-limit-auth@file`.
- H4: từ `frontend`, `auth-service:8081`, `user-service:8083`, `cart-service:8080`, `postgres`, `redis` đều không kết nối được
  (chỉ `api-gateway:80`); Mailpit, LocalStack, Grafana, Temporal UI không phân giải được `auth-service`. Gọi nội dung delivery từ
  `ecommerce-network` chỉ với `X-Service-Identity` → `401`; chữ ký giả/hết hạn → `401`; chữ ký đúng → `404 DELIVERY_NOT_FOUND`.
  Đăng ký tài khoản mới: OTP tới Mailpit (Notification gọi Auth có ký); đăng nhập qua `:3000` và `:8088` → `200`.
- H3: `pg_stat_activity` — mọi service đăng nhập bằng role của mình, `postgres` chỉ còn ở Debezium, Temporal, exporter; role
  `NOSUPERUSER`; `product_service` vào `order_service` bị từ chối CONNECT. User MinIO `review-service` ghi/đọc `review-media`, bị
  `Access Denied` ở `product-media` và admin API. 6 connector Debezium RUNNING.
- H1: `docker compose run` với `AUTH_APP_ENV=production`, `NOTIFICATION_APP_ENV=staging`, `SPRING_PROFILES_ACTIVE=prod` (Product,
  Review, Checkout) dừng với thông báo nêu đúng biến; Payment `dev` với `sk_test_` thật và `whsec_local_change_me` cũng dừng.
- Luồng checkout qua Next: giỏ → preview `200` → submit `202` → Order tạo đơn; bước `PAYMENT_INITIATE` bị Stripe từ chối vì môi trường
  không có Stripe sandbox key (placeholder, như trước thay đổi) nên webhook Stripe CLI chưa chạy được.
