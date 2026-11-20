# ADR-003: Kafka xác thực SASL/SCRAM theo principal của từng client và ACL chủ sở hữu topic

- Status: Accepted
- Date: 2026-10-22
- Đóng: [ADR-002](ADR-002-local-runtime-exposure-and-broker-auth.md) mục 6 và Việc còn mở 1

## Context

Sau [ADR-002](ADR-002-local-runtime-exposure-and-broker-auth.md), broker chỉ tới được từ mạng compose/namespace dự án và
loopback của máy dev, nhưng vẫn PLAINTEXT, không ACL: mọi container trong mạng compose produce được vào mọi topic. Ví dụ của C4:
một container bất kỳ produce `com.ecommerce.payment.transaction.succeeded.v2` lên `t.payment.events.v2` với `source` =
`/services/payment`, và Order (`PaymentIntake`) không có cách phân biệt với event thật. ZooKeeper (không xác thực) lại giữ
metadata của broker.

## Decision

1. **Mọi listener là `SASL_PLAINTEXT` với SCRAM-SHA-512.** Listener `INTERNAL` (`kafka:9092`, cũng là inter-broker) và `HOST`
   (`localhost:29092`, chỉ bind 127.0.0.1). Tên listener không chứa `SASL_` vì bước configure của image cp-kafka khi đó đòi file
   JAAS trong `KAFKA_OPTS`; JAAS đặt theo listener (`listener.name.<l>.scram-sha-512.sasl.jaas.config`). Kết nối không xác thực
   bị đóng, sai mật khẩu nhận `SaslAuthenticationException`.
2. **ACL với `AclAuthorizer` và `allow.everyone.if.no.acl.found=false`.** Hai super user: `kafka-broker` (inter-broker) và
   `kafka-admin` (`kafka-init`, công cụ dev). `infra/kafka/broker-entrypoint.sh` ghi credential SCRAM của hai user này vào ZooKeeper
   trước khi broker khởi động và tạo `/etc/kafka/admin.properties` cho công cụ chạy trong container `kafka`.
3. **Một principal cho mỗi client** (18): `auth-service`, `user-service`, `cart-service`, `notification-service`, `order-service`,
   `payment-service`, `product-service`, `promotion-service`, `review-service`, `search-service`, `kafka-connect` (worker),
   `debezium-<service>` cho sáu connector (product, promotion, inventory, order, payment, review) và `kafka-exporter`.
   [`infra/kafka/acls.txt`](../../../infra/kafka/acls.txt) là trạng thái mong muốn:
   - `WRITE` chỉ cho chủ sở hữu topic. Event nghiệp vụ đi qua outbox, nên chủ sở hữu là connector Debezium của service
     (`debezium-payment` là principal duy nhất ghi `t.payment.events.v2`); service chỉ ghi topic nó publish trực tiếp (Auth, User,
     Cart, Notification outcomes, inbox webhook của Payment) và DLQ của consumer của nó.
   - `READ` theo topic và consumer group (group lạ nhận `GROUP_AUTHORIZATION_FAILED`); `search-reindex-` là group PREFIXED của
     consumer tạm ReindexCatchUp. Product có `DESCRIBE` trên `t.product.events`/`t.category.events` để đọc end offset cho export.
   - Không cấp `IdempotentWrite`: từ Kafka 2.8 broker cho `InitProducerId` khi principal có `WRITE` trên ít nhất một topic. Hệ quả:
     principal không có `WRITE` nào (Order, Search) bị từ chối ngay ở InitProducerId với `CLUSTER_AUTHORIZATION_FAILED`; principal có
     `WRITE` topic khác (Payment) produce vào `t.payment.events.v2` nhận `TOPIC_AUTHORIZATION_FAILED`. Không service nào dùng
     transaction nên không có ACL `TransactionalId`.
   - Worker Connect: `READ` group `product-service-connect-cluster`, `READ/WRITE/CREATE/DESCRIBE_CONFIGS` ba topic nội bộ.
   - `kafka-exporter`: chỉ `DESCRIBE` topic `*` và group `*` (offset, lag; không đọc message).
4. **`kafka-init` đồng bộ cả ba thứ trong một JVM.** [`infra/kafka/KafkaInit.java`](../../../infra/kafka/KafkaInit.java) chạy bằng
   source launcher của JDK có sẵn trong image cp-kafka ([`init.sh`](../../../infra/kafka/init.sh)): tạo topic thiếu của `topics.txt`,
   tạo credential SCRAM cho principal chưa có (mật khẩu `SCRAM_PASSWORD_<PRINCIPAL>`; `SCRAM_RESET=true` đặt lại mọi mật khẩu),
   thêm ACL thiếu và xoá ACL ALLOW host `*` mà file không còn liệt kê. Mỗi lệnh CLI `kafka-topics`/`kafka-configs`/`kafka-acls` là
   một JVM riêng: bản dựng bằng CLI mất 412 giây cho 59 lệnh trên máy dev; bản Admin API mất khoảng 10 giây. `create-topics.sh`
   bị thay. Job `kafka-topics-init` của k8s chạy cùng chương trình.
5. **Connector produce bằng principal riêng, không bằng worker.** Worker bật `connector.client.config.override.policy=Principal`
   (chỉ cho override `sasl.jaas.config`, `sasl.mechanism`, `security.protocol` — hẹp hơn `All`), và mỗi artifact connector có
   `producer.override.sasl.jaas.config` với `debezium-<service>` và `${env:SCRAM_PASSWORD_DEBEZIUM_<SERVICE>}`. Mật khẩu đến từ
   `EnvVarConfigProvider` của worker (allowlist `^SCRAM_PASSWORD_DEBEZIUM_[A-Z]+$`), nên `GET /connectors/<name>/config` không trả
   mật khẩu. Connector thiếu override không xác thực được (worker không đặt `producer.sasl.jaas.config` mặc định): hỏng đóng, không
   âm thầm chạy bằng quyền worker. `register-connectors.sh` PUT lại config khi config đang chạy khác artifact, và đăng ký cả connector
   Review (trước đây đăng ký tay).
6. **Client.** Java (Order, Payment, Product, Promotion, Review, Search) không đổi code: compose đặt `SPRING_KAFKA_SECURITY_PROTOCOL`,
   `SPRING_KAFKA_PROPERTIES_SASL_MECHANISM` và `SPRING_KAFKA_PROPERTIES_SASL_JAAS_CONFIG` (dòng JAAS đọc `KAFKA_SASL_USERNAME`/
   `KAFKA_SASL_PASSWORD` qua placeholder Spring; anchor `x-kafka-sasl-java`). Mọi consumer/producer/admin của các service này dựng từ
   `KafkaProperties` nên đều nhận cấu hình. Go (Auth, User, Cart, Notification) dùng sarama với SCRAM client của `xdg-go/scram`
   v1.1.2 (`internal/infrastructure/kafka/sasl.go`), bật khi `<PREFIX>_KAFKA_SASL_USERNAME` có giá trị; thiếu mật khẩu thì config bị
   từ chối. Username rỗng giữ PLAINTEXT cho test và compose riêng của từng service.
7. **ZooKeeper chỉ broker tới được.** ZooKeeper không xác thực mà giữ credential SCRAM và ACL; ai sửa được ZooKeeper thì bỏ qua được
   ACL. Compose đặt ZooKeeper trên mạng `kafka-metadata` (`internal: true`) chỉ chung với `kafka`; k8s có NetworkPolicy
   `zookeeper-from-kafka` và loại ZooKeeper khỏi `allow-project-namespaces`.
8. **Mật khẩu dev có một nguồn.** Compose: anchor `x-kafka-passwords` (`${KAFKA_<PRINCIPAL>_PASSWORD:-<principal>-local-dev}`), dùng
   cho cả service lẫn `kafka-init`; Connect và kafka-exporter dựng dòng JAAS/cờ từ biến môi trường trong entrypoint. K8s: Secret
   `kafka-credentials` (Job, broker) và `kafka-connect-credentials` (chỉ worker và sáu connector); service nhận giá trị dev qua env do
   `generate.py` sinh, giống mật khẩu Redis.

## Alternatives considered

- **`SASL/PLAIN` với user trong JAAS của broker.** Không chọn: thêm hay đổi user phải sửa file và restart broker; mật khẩu nằm
  nguyên văn trên broker. SCRAM lưu salted hash và đổi được lúc broker chạy.
- **`connector.client.config.override.policy=All`** (theo gợi ý ban đầu). Không chọn: `Principal` đủ cho việc đổi principal mà không
  cho connector đổi `bootstrap.servers`, `acks` hay interceptor.
- **Connector chạy bằng principal của worker.** Không chọn: worker sẽ cần `WRITE` mọi topic outbox, và ai sửa được một connector
  (REST không xác thực) ghi được mọi topic.
- **Sửa `application.yml` của sáu service Java.** Không chọn: phải build lại sáu image cho đúng cấu hình mà env đã diễn đạt được;
  `spring.kafka.properties.*` từ env áp cho mọi client dựng từ `KafkaProperties`.
- **Listener PLAINTEXT trên 127.0.0.1 trong container `kafka` cho công cụ.** Không chọn: một listener PLAINTEXT có super user
  ANONYMOUS là ngoại lệ phải nhớ; `admin.properties` trong container đủ cho `docker exec`.
- **SASL_SSL ngay.** Chưa: cần CA, keystore cho broker và truststore cho 11 client; xem Việc còn mở.

## Consequences

- Produce giả `transaction.succeeded` cần mật khẩu của `debezium-payment` hoặc quyền sửa connector Payment, không còn chỉ cần mạng
  compose. Kiểm `source == /services/payment` của `PaymentIntake` giờ có nghĩa: chỉ connector Payment ghi được topic đó. Order không
  cần đổi code.
- `docker exec kafka kafka-…` phải thêm `--command-config /etc/kafka/admin.properties` (`--producer.config`/`--consumer.config` cho
  console producer/consumer). Công cụ từ host qua `localhost:29092` cần cấu hình SASL tương tự (user `kafka-admin`).
- Topic mới vẫn là một dòng trong `topics.txt`, nhưng producer/consumer của nó cần thêm dòng trong `acls.txt`; consumer group mới cũng
  vậy. Thiếu ACL thì client nhận `*_AUTHORIZATION_FAILED`, không âm thầm chạy.
- Đổi mật khẩu trong `.env`: `docker compose run --rm -e SCRAM_RESET=true kafka-init` rồi tạo lại client. Mật khẩu không được chứa
  `,`, `[`, `]`, `"` hay `$`.
- Health check của `kafka` trong compose là TCP (giống k8s); `kafka-init` mới là bước chờ broker trả lời một request đã xác thực.
- `scripts/restore-auth-topic-ids.ps1` chạy `zookeeper-shell` trên mạng `kafka-metadata`.

## Việc còn mở

1. **REST của Kafka Connect không xác thực.** Ai tới được `kafka-connect:8083` (mọi container trong mạng compose, loopback `8084`) sửa
   được connector, tức produce bằng principal `debezium-*`. Ứng viên: `BasicAuthSecurityRestExtension` (jar có sẵn trong image) hoặc
   NetworkPolicy chỉ cho Job `kafka-connect-init`.
2. **SASL_SSL** thay `SASL_PLAINTEXT` khi có CA cho môi trường dùng chung.
3. **ZooKeeper không xác thực** (chỉ được cô lập mạng). Chuyển KRaft hoặc bật SASL DIGEST cho ZooKeeper với `zookeeper.set.acl=true`.

## Verification (2026-10-22, stack compose)

- `kafka-init`: 18 principal, 104 ACL; chạy lại không thêm/xoá gì. 6 connector và task `RUNNING`; offset các topic
  `__debezium-heartbeat.*` tăng sau khi chuyển (connector ghi bằng principal riêng); chạy lại `kafka-connect-init` không PUT lại.
- 25 consumer group của 10 service có member; không lỗi xác thực trong log service; kafka-exporter xuất `kafka_brokers 1` và lag.
- Client không xác thực tới `kafka:9092` và `localhost:29092`: bị ngắt kết nối; sai mật khẩu: `SaslAuthenticationException`;
  `payment-service` produce `t.payment.events.v2`: `TOPIC_AUTHORIZATION_FAILED`; `order-service`: `CLUSTER_AUTHORIZATION_FAILED`
  (idempotent) / `TOPIC_AUTHORIZATION_FAILED` (không idempotent); `payment-service` tạo topic: `Authorization failed`.
- `bash scripts/ci/verify-providers.sh` 41/41; `python deploy/k8s/generate.py --check`; `kubectl kustomize` ba lớp.
- Go: `go vet ./...` và `go test ./...` của auth, user, cart, notification (gồm trao đổi SCRAM-SHA-512 với server của
  `xdg-go/scram`, sai mật khẩu thì thất bại).
