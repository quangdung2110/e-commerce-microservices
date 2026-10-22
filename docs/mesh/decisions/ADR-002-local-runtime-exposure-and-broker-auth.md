# ADR-002: Runtime ngoài mesh — chỉ gateway ra ngoài, Redis bắt buộc mật khẩu, Kafka không tự tạo topic

- Status: Accepted (mục 6 thay bởi [ADR-003](ADR-003-kafka-sasl-scram-and-acls.md))
- Date: 2026-10-22

## Context

Security review chỉ đọc code ngày 2026-10-22 thấy code ứng dụng nhìn chung đúng (gateway-auth verify JWT chặt và xoá
`X-User-*` do client gửi), nhưng định danh phía service hoàn toàn dựa vào header: `X-User-Id`/`X-User-Roles` do Traefik
gắn và `X-Service-Identity` do caller tự khai. Theo [runtime contract](../runtime-contract.md) và [design](../design.md#ranh-giới-tin-cậy),
thứ bảo đảm header đó là mesh (Istio ambient + `AuthorizationPolicy`), mà mesh chưa được cài ở đâu. Bốn lỗi Critical:

| # | Lỗi | Ví dụ khai thác |
|---|---|---|
| C1 | docker-compose publish cổng mọi service trên `0.0.0.0` (order 8095, payment 8089, auth 8086, product 8082 + gRPC 9091, inventory 8094, promotion 8085, review 8087, search 8081, checkout 8096, BFF 8093, notification 8091/8092); `deploy/k8s` sinh Service `LoadBalancer` cho cùng các cổng đó (LoadBalancer của Docker Desktop nghe trên mọi interface) | `POST :8095/internal/v1/checkout-order-submissions` với `X-Service-Identity: checkout-service` tạo đơn giá tuỳ ý; `X-User-Roles: ADMIN` trên mọi service |
| C2 | Ops API Payment `/internal/v1/payment/**` không kiểm caller | Xử lý ở [Payment ADR-028](../../payment/decisions/ADR-028-finance-ops-caller-allowlist.md) |
| C3 | Redis dùng chung không mật khẩu, publish 6379; chứa reset token `auth:reset:<sha256>` → account, OTP, denylist, rate limit của Auth | Ghi một reset token tự chọn rồi đặt lại mật khẩu bất kỳ tài khoản |
| C4 | Kafka PLAINTEXT, không ACL, publish 9092/29092, `auto.create.topics.enable=true`; ZooKeeper 2181, Postgres 15432, Elasticsearch 9200, MinIO 9000/9001, Kafka Connect 8084, Temporal 7233/8233, Mailpit 1025/8025, LocalStack 4566 cũng mở trên `0.0.0.0` với mật khẩu mặc định | Người mua tự produce `transaction.succeeded` cho đơn của mình (Order tin thuộc tính `source` do bên gửi đặt) |

## Decision

1. **Chỉ gateway và frontend nghe trên mọi interface.** docker-compose bind `127.0.0.1:` cho mọi cổng service, database,
   broker và UI quản trị (giống nhóm observability đã làm); gateway `8088` (`8443`) và frontend `3000` giữ nguyên. Không publish
   ZooKeeper (không xác thực, sở hữu metadata Kafka) và listener `9092` của Kafka (quảng bá `kafka:9092`, host không dùng được).
   Công cụ trên máy dev (psql `:15432`, Connect `:8084`, Kafka `:29092`, gọi thẳng service với header) vẫn dùng `localhost` như cũ.
2. **Kubernetes theo cùng quy tắc, sinh từ compose.** `deploy/k8s/generate.py` chỉ sinh `LoadBalancer` cho cổng compose bind mọi
   interface (Traefik `api-gateway`, `frontend`); cổng bind loopback thành dòng trong `services/port-forwards.txt` cho
   `deploy/k8s/port-forward-dev.sh` (`kubectl port-forward --address 127.0.0.1`). `infra/host-ports.yaml` (LoadBalancer cho Postgres,
   Redis, Kafka, Connect, Elasticsearch, MinIO, Temporal) bị bỏ; script mở cùng các cổng đó. `apply.sh` xoá Service `*-external` cũ.
3. **NetworkPolicy cho cụm không có mesh.** `generate.py` sinh `services/networkpolicies.yaml`: default deny ingress cho `ecommerce` và
   `edge`, rồi mỗi workload một policy chỉ nhận từ đúng pod caller của nó, lấy từ `AuthorizationPolicy` L7 mà
   [`deploy/mesh/generate.py`](../../../deploy/mesh/README.md) sinh từ mục 5.2 (cùng ma trận, ở mức L4: pod nguồn, mọi cổng),
   cộng Prometheus. Traefik và frontend nhận mọi nguồn; `gateway-auth` chỉ từ Traefik. Namespace `data` chỉ nhận từ `data`,
   `ecommerce`, `edge`, `observability` (`infra/networkpolicies.yaml`). Khi cài mesh, bộ policy này được thay bằng
   `deploy/mesh/generated/80-networkpolicies.yaml` (traffic L7 đi qua waypoint, nguồn không còn là pod caller).
4. **Redis dùng chung bắt buộc mật khẩu** (`requirepass`). Giá trị dev là anchor `x-redis-password` của compose
   (`${REDIS_PASSWORD:-local-dev-redis-password}`), truyền cho Auth/User/Notification (`REDIS_PASSWORD`), Cart
   (`CART_REDIS_PASSWORD`), Promotion/Order (`REDIS_PASSWORD` → `spring.data.redis.password`), gateway-auth (trong
   `AUTH_REVOCATION_REDIS_URL`) và redis-exporter. Container Redis có `REDISCLI_AUTH`, nên `docker exec redis redis-cli …` (healthcheck,
   `frontend/scripts/create-dev-account.sh`) không phải đổi. Ở k8s: Secret `redis-credentials` (`infra/kustomization.yaml`).
5. **Kafka không tự tạo topic.** `auto.create.topics.enable=false`; mọi topic nằm trong `infra/kafka/topics.txt`, được `kafka-init`
   (compose, mọi client Kafka `depends_on` nó) và Job `kafka-topics-init` (k8s) tạo, chỉ thêm topic thiếu (từ
   [ADR-003](ADR-003-kafka-sasl-scram-and-acls.md) bằng `infra/kafka/KafkaInit.java` thay cho `create-topics.sh`).
   Produce vào một tên lạ không còn tạo ra topic; topic mới là một dòng trong file đó.
6. ~~**Kafka vẫn PLAINTEXT, chưa có SASL/ACL.**~~ Thay bởi [ADR-003](ADR-003-kafka-sasl-scram-and-acls.md) (2026-10-22): SASL/SCRAM
   theo principal của từng client và ACL theo `infra/kafka/acls.txt`.

## Alternatives considered

- **Bỏ hẳn cổng host của service** thay vì bind loopback. Không chọn: script, test thủ công và tài liệu dùng `localhost:8094`,
  `:8081`, `:8084`, `:15432`…; loopback chặn được truy cập từ máy khác, đó là phần của C1 có thể khai thác từ xa.
- **Ký `X-User-*`/`X-Service-Identity` bằng HMAC ngay** (gateway-auth ký, service verify) hoặc **chuyển tiếp JWT đã verify** để
  service tự verify qua JWKS. Không làm trong ADR này: cả hai phải sửa 13 service (9 Java, 4 Go) cùng lúc, thêm secret/khóa cần xoay
  vòng, và JWT không giải quyết caller service-to-service. Mesh đã có manifest ([ADR-001](ADR-001-manifests-generated-from-runtime-contract.md));
  cài mesh lên cụm k8s là bước kế tiếp rẻ hơn và không bị bỏ đi sau đó. Ghi ở Việc còn mở.
- **SASL/SCRAM + ACL ngay trong ADR này.** Không chọn: 11 client (4 Go dùng sarama, chưa có SCRAM client; 7 Java), Debezium Connect
  (worker + producer/consumer override theo connector) và kafka-exporter phải đổi cùng lúc, kèm ACL theo topic, consumer group và
  idempotent producer; phần lớn rủi ro truy cập từ xa đã được bước 1–2 chặn.
- **Redis ACL user riêng cho từng service.** Chưa chọn: một mật khẩu đã chặn truy cập vô danh; tách user theo key prefix là bước sau.

## Consequences

- Từ máy khác chỉ còn tới được gateway (`8088`) và frontend (`3000`). Bypass gateway bằng header giả, ghi Redis, produce Kafka
  hay sửa connector Debezium đều cần chạy code **trên chính máy dev** hoặc **trong mạng compose/cụm**.
- Trong mạng compose, mọi container vẫn tới được mọi service và vẫn giả được header: đó vẫn là giới hạn đã ghi ở
  [runtime contract §11](../runtime-contract.md#11-khoảng-cách-với-trạng-thái-hiện-tại). Compose không phải bằng chứng bảo mật.
- Ở k8s không có mesh, NetworkPolicy thu hẹp "mọi pod" thành "pod caller hợp lệ" — **chỉ khi CNI thực thi NetworkPolicy**; cụm
  kubeadm của Docker Desktop không thực thi, nên ở đó policy chỉ là cấu hình sẵn cho cụm thật.
- Trên k8s, công cụ local cần `bash deploy/k8s/port-forward-dev.sh` đang chạy.
- Volume Redis cũ dùng được nguyên (mật khẩu chỉ là cấu hình server). Broker cũ giữ topic đã có; `kafka-init` chỉ thêm topic thiếu.
- Một topic chưa khai báo làm producer lỗi `UNKNOWN_TOPIC_OR_PARTITION` sau `max.block.ms` thay vì âm thầm có topic mới.

## Việc còn mở

1. **Đã xong ở [ADR-003](ADR-003-kafka-sasl-scram-and-acls.md)** (policy `Principal` thay `All`, không cần `IDEMPOTENT_WRITE`;
   SASL_SSL và REST Connect không xác thực còn mở ở đó). Nguyên văn:
   **Kafka SASL/SCRAM + ACL theo principal.** Listener `SASL_PLAINTEXT` (sau đó `SASL_SSL`), một user SCRAM cho mỗi service,
   Connect worker và từng connector (`connector.client.config.override.policy=All` + `producer.override.sasl.jaas.config`), ACL
   `WRITE` theo topic chủ sở hữu (ví dụ chỉ principal của connector Payment ghi `t.payment.events.v2`), `READ` theo consumer group,
   `IDEMPOTENT_WRITE` cho producer idempotent; kafka-exporter chỉ `DESCRIBE`. Go cần `xdg-go/scram` cho sarama. Sau khi có ACL,
   Order kiểm `source` của event Payment mới có nghĩa (C4); trước đó Order không có gì để kiểm ngoài nội dung event.
2. **Định danh service-to-service mạnh hơn header.** Ưu tiên cài mesh lên cụm k8s theo [ADR-001](ADR-001-manifests-generated-from-runtime-contract.md)
   và đạt tiêu chí runtime contract §10. Nếu compose cần cùng mức bảo vệ: gateway-auth ký `X-User-*` bằng HMAC kèm thời điểm, service
   verify trong filter chung; caller nội bộ ký `X-Service-Identity` bằng khoá riêng theo service.
3. **Route Finance Ops của Payment chưa có dòng trong runtime contract §5.2**, nên dưới mesh không ai gọi được; thêm khi `finance-ops`
   có runtime.
4. `checkout-redis` chưa có mật khẩu (không publish, chỉ Checkout dùng); credential dev mặc định của Postgres/MinIO/Elasticsearch;
   không có TLS. Thuộc các finding High/Medium của cùng review, ngoài phạm vi ADR này.

## Verification

- `docker compose config` (kể cả `--profile payment --profile notification`): chỉ `8088`, `8443`, `3000` không có `host_ip: 127.0.0.1`.
- Stack chạy lại khoẻ; gọi cổng service bằng IP LAN của máy bị từ chối, bằng `localhost` vẫn được; `redis-cli` không mật khẩu nhận
  `NOAUTH`; produce vào topic không khai báo bị từ chối.
- `python deploy/k8s/generate.py --check`; `kubectl kustomize` ba lớp và `kubeconform -strict` (Kubernetes 1.31) không lỗi.
- Test config Go đọc mật khẩu Redis từ env (auth, user, notification, cart, gateway-auth).
