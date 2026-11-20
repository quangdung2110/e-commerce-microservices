# Kubernetes (local, Docker Desktop)

Manifest kustomize để chạy toàn bộ dự án trên cụm Kubernetes của Docker Desktop (kubeadm, một node).
Bố cục namespace theo [docs/mesh/design.md](../../docs/mesh/design.md#namespace-và-phạm-vi); manifest
Istio vẫn ở [deploy/mesh](../mesh/README.md) và chưa được cài.

| Lớp | Namespace | Nội dung | Nguồn |
|---|---|---|---|
| `infra/` | `data` | PostgreSQL, Redis (bắt buộc mật khẩu), checkout-redis, ZooKeeper, Kafka (+ Job `kafka-topics-init`), Kafka Connect, Elasticsearch, MinIO (+ Job tạo bucket và user `review-service`), Job `postgres-roles-init` (role riêng mỗi service, mesh ADR-004), Temporal, Temporal UI, NetworkPolicy | Viết tay |
| `services/` | `ecommerce` | 13 service nghiệp vụ (product, search, review, product-detail-bff, promotion, inventory, order, payment, checkout, cart, auth, user, notification) + Mailpit, LocalStack | **Sinh** từ `docker-compose.yml` |
| `services/` | `edge` | Traefik (Service `api-gateway`), `gateway-auth`, frontend | **Sinh** |
| `services/` | `data` | Job `kafka-connect-init` đăng ký 6 connector Debezium | **Sinh** |
| `observability/` | `observability` | Alloy (`otel-collector`), Prometheus, Alertmanager, Jaeger, Loki, Grafana, 4 exporter (Redis ×2, PostgreSQL, Kafka) | Viết tay; cấu hình lấy từ `infra/observability/` |

## Yêu cầu

- Docker Desktop → Settings → Kubernetes: bật, chọn **Kubeadm**. Cụm kubeadm dùng chung kho image
  với Docker Engine, nên image build bằng `docker compose build` dùng được ngay; image build tại máy có
  `imagePullPolicy: Never`, không cần registry.
- Image phải có sẵn: `docker compose build` (gồm `minio`, `frontend` và các service; `payment-service`,
  `notification-service` nằm trong profile nên build thêm bằng `docker compose --profile payment
  --profile notification build payment-service notification-service`).
- Python có PyYAML để chạy `generate.py`. Hạ tầng cần OpenSSL hỗ trợ `pkcs12 -jdktrust`
  để tạo CA, chứng chỉ TLS và truststore Java cho local. Trên Windows có thể dùng OpenSSL của Git for Windows.
- Không chạy song song với `docker compose up`: trùng cổng host và không đủ RAM cho hai bản. Ba lớp dùng
  khoảng 10,7 GB trong 15 GB của VM Docker Desktop khi đã ổn định (đo 2026-10-21).

## Triển khai

```bash
bash deploy/k8s/apply.sh infra
kubectl -n data get pods             # chờ mọi pod Running
bash deploy/k8s/apply.sh services
bash deploy/k8s/apply.sh observability
bash deploy/k8s/port-forward-observability.sh   # mở UI, giữ terminal; Ctrl+C để đóng
```

Lần đầu, các JVM cùng khởi động mất 3–6 phút; startupProbe cho tối đa 10 phút. `apply.sh`:

- từ chối chạy khi context hiện tại không phải `docker-desktop` (đặt `ALLOW_ANY_CONTEXT=1` nếu chủ ý);
- với lớp `infra`: chạy `data_credentials.py` để cấp Secret theo context. Script tái dùng bộ credential
  local trong `.tools/k8s-data-security/`, ngoài Git. Giữ bộ này trong vùng riêng tư của máy triển khai;
- build bằng `kubectl kustomize --load-restrictor LoadRestrictionsNone`, vì `configMapGenerator` đọc file
  gốc ngoài thư mục (`init-db.sh`, `infra/minio/`, `go/services/*/configs`, migration, config Traefik…).
  Sửa các file đó rồi apply lại là đủ: ConfigMap đổi hash và pod được tạo lại;
- với lớp `services`: chạy `generate.py --check` (dừng nếu file sinh đã cũ) và xoá Job cũ trước khi apply,
  vì spec của Job không sửa được.

## Lớp services được sinh thế nào

`python deploy/k8s/generate.py` ghi lại `deploy/k8s/services/`. **Không sửa tay thư mục đó.**

| Lấy từ | Thành |
|---|---|
| `docker-compose.yml`: `image`/`build`, `environment`, `command`/`entrypoint` | Container; `${VAR:-default}` lấy default, không đọc env của máy chạy script (secret trên máy không lọt vào file sinh) |
| `healthcheck` của compose | startup/readiness probe; liveness chỉ kiểm cổng TCP |
| Bind mount `./…` | ConfigMap mount đúng đường dẫn đó |
| `ports` của compose bind mọi interface (chỉ gateway, frontend) | Service `<tên>-external` kiểu LoadBalancer |
| `ports` của compose bind `127.0.0.1` (mọi service khác) | Dòng trong `services/port-forwards.txt` cho `port-forward-dev.sh`; không LoadBalancer |
| Label `traefik.*` | `services/routes.yml` cho file provider của Traefik, cạnh `dynamic.yml` gốc |
| `deploy/mesh/topology.yaml` | Cổng của Service và tên cổng |
| `deploy/mesh/generated/41-authorizationpolicies-ecommerce.yaml` | `services/networkpolicies.yaml`: default deny ingress cho `ecommerce`/`edge`, mỗi workload chỉ nhận từ pod caller trong ma trận mesh + Prometheus |
| `config/data-client-security.yaml` | Bản vá Kustomize cho thông tin xác thực TLS của Checkout, Search và Job đăng ký connector |

Bảng trong `generate.py` chỉ giữ những gì compose không nói: namespace, bộ nhớ, probe cho service compose
không có healthcheck, alias DNS, và `auth-migrate`. Script dừng với lỗi khi một service compose chưa được
xếp vào lớp nào, khi một label Traefik chưa được hỗ trợ, hoặc khi env của workload dùng tên service ở
namespace khác mà không có alias.

Sau khi sửa `docker-compose.yml` hay `dynamic.yml`: chạy `generate.py`, rồi `apply.sh services`.

## Địa chỉ

Gateway: **http://localhost:8088** (Traefik). Frontend: **http://localhost:3000**. Đây là hai Service LoadBalancer duy nhất
([mesh ADR-002](../../docs/mesh/decisions/ADR-002-local-runtime-exposure-and-broker-auth.md)): LoadBalancer của Docker Desktop nghe trên
mọi interface của máy, trong khi service tin header danh tính và hạ tầng không xác thực mạnh. Cổng host của hạ tầng và service chỉ mở
khi chạy `port-forward-dev.sh` (bind 127.0.0.1, giữ terminal; Ctrl+C để đóng):

```bash
bash deploy/k8s/port-forward-dev.sh            # hạ tầng + service; `infra` hoặc `services` để mở một phần
```

Trong cụm, mỗi namespace có Service `ExternalName` trùng tên compose (`postgres`, `kafka`, `redis`,
`auth-service`…) trỏ sang namespace thật, nên giá trị env của compose (`postgres:5432`, `kafka:9092`,
`http://auth-service:8080`) dùng được không đổi.

| Thành phần | Trong cụm | Trên host (qua `port-forward-dev.sh`) |
|---|---|---|
| PostgreSQL | `postgres.data:5432` | `localhost:15432` |
| Redis (mật khẩu: Secret `redis-credentials`) | `redis.data:6379` | `localhost:6379` |
| Redis của Checkout | `rediss://checkout-redis.data:6379` (ACL riêng cho Checkout, exporter và probe) | — |
| Kafka | `kafka.data.svc.cluster.local:9092` | `localhost:29092` |
| Kafka Connect | `https://kafka-connect.data:8083` (mTLS) | `https://localhost:8084` (mTLS) |
| Elasticsearch | `https://elasticsearch.data:9200` (Basic auth) | `https://localhost:9200` (Basic auth) |
| MinIO | `minio.data:9000` (console 9001) | `localhost:9000`, `localhost:9001` |
| Temporal / UI | `temporal.data:7233` / `temporal-ui.data:8080` | `localhost:7233` / `localhost:8233` |
| Service nghiệp vụ | `<tên>.ecommerce:<cổng topology>` | Cổng host như compose: product 8082 (gRPC 9091), search 8081, review 8087, product-detail-bff 8093, promotion 8085, inventory 8094, order 8095, auth 8086, payment 8089, checkout 8096, notification 8091/8092 |
| Mailpit / LocalStack | `mailpit.ecommerce:8025` / `localstack.ecommerce:4566` | `localhost:8025` (SMTP 1025) / `localhost:4566` |
| Dashboard Traefik | `traefik.edge:8090` | `localhost:8090` |

UI observability dùng script riêng (compose cũng chỉ bind loopback cho chúng):

| UI | Lệnh | Địa chỉ |
|---|---|---|
| Grafana (`admin` / `local-observability`), Prometheus, Alertmanager, Loki, Jaeger | `bash deploy/k8s/port-forward-observability.sh` | http://localhost:3001, :9090, :9093, :3100, :16686 (như compose) |

**NetworkPolicy**: `services/networkpolicies.yaml` (sinh) và `infra/networkpolicies.yaml` chặn ingress mặc định; mỗi workload ở
`ecommerce`/`edge` chỉ nhận từ caller trong ma trận mesh (runtime contract §5.2) và Prometheus. Checkout Redis,
Elasticsearch, Kafka Connect và ZooKeeper có allowlist theo workload/cổng riêng trong `data`.
`kubectl port-forward` không đi qua NetworkPolicy. Cụm kubeadm của Docker Desktop **không thực thi**
NetworkPolicy, nên ở đây chúng là cấu hình sẵn cho cụm có CNI thực thi (Calico, Cilium…). Khi cài mesh, thay bộ này bằng
`deploy/mesh/generated/80-networkpolicies.yaml` cho lớp `services`: traffic L7 đi qua waypoint nên nguồn không còn là pod caller.
Listener nội bộ ở cổng riêng chỉ mở cho caller nội bộ (`INTERNAL_LISTENERS` trong `generate.py`, mesh ADR-004): Auth `internal`
(8081) chỉ Notification, User `internal` (8083) chỉ Checkout; Traefik, gateway-auth và Prometheus chỉ tới cổng public.

Kafka Connect dùng một worker local. Cổng REST gốc bind loopback trong pod, Service trỏ vào proxy mTLS.
Để quản trị qua `port-forward-dev.sh`, dùng thư mục `connect-admin` mà script cấp credential in ra.
Giới hạn quyền Kubernetes `pods/portforward` và `pods/exec` trong `data` cho người vận hành được ủy quyền.

```bash
curl --cacert "$connect_admin/ca.crt" --cert "$connect_admin/tls.crt" \
  --key "$connect_admin/tls.key" https://localhost:8084/connectors
```

Elasticsearch qua port-forward vẫn yêu cầu CA và credential phù hợp với thao tác. CA local có trong
Secret `ecommerce/data-server-trust` (key `ca.crt`). Chứng chỉ bootstrap local có hạn một năm.
Hạ tầng triển khai cần vòng đời Secret và chứng chỉ riêng.

**IP client** (mesh ADR-004): `config/traefik.yml` tin `X-Forwarded-For`/`X-Real-Ip` từ pod CIDR `10.1.0.0/16` (frontend; pod IP
không cố định), Auth tin `X-Real-Ip` từ cùng dải (`AUTH_SECURITY_TRUSTED_PROXIES` trong `generate.py`). Service LoadBalancer dùng
`externalTrafficPolicy: Local` để traffic từ ngoài giữ địa chỉ nguồn, không mang địa chỉ trong pod CIDR. Dải này là mặc định của
Docker Desktop kubeadm; cụm khác phải đổi ở hai chỗ đó.

## Lớp observability

Dùng nguyên file của `infra/observability/` qua `configMapGenerator`: `prometheus.yml`, `rules.yml`,
`alertmanager.yml`, `loki.yml`, `jaeger.yml`, provisioning Grafana và `optional-targets.py`.

- **Target scrape giữ nguyên**: namespace `observability` có `ExternalName` cho mọi tên trong
  `prometheus.yml` (`search-service`, `api-gateway`…), nên nhãn `instance` giống compose và rule/dashboard
  không đổi. `optional-targets.py` chạy như init container của Prometheus với `payment notification`, vì ở
  cụm này hai service đó luôn chạy. Có 24 target.
- **Alloy có bản riêng** [config/config.alloy](config/config.alloy): phần trace giống hệt; log đọc qua
  Kubernetes API (`loki.source.kubernetes`, ClusterRole chỉ `get/list/watch` pods và pods/log) thay cho
  `docker.sock`. `service_name` là nhãn `app.kubernetes.io/name` của pod, riêng `traefik` đổi thành
  `api-gateway`; danh sách service thu log giống bản compose. Sửa phần trace thì sửa cả hai file.
- Service nghiệp vụ gửi OTLP tới `otel-collector:4318` qua alias đã có sẵn ở `ecommerce`/`edge`.
- **Traefik có `KUBERNETES_SERVICE_HOST=""`**: nếu không, resource detector OTel của nó đọc token
  ServiceAccount (pod không mount token), báo `Unable to create tracer` và bỏ tracing mà vẫn chạy bình
  thường.

## Khác với docker-compose

- **`enableServiceLinks: false` trên mọi pod.** Kubernetes tiêm `<SERVICE>_PORT=tcp://…`/`<SERVICE>_HOST`
  cho mỗi Service trong namespace; image Confluent đọc mọi `KAFKA_*`, Temporal đọc `TEMPORAL_*`, service
  đọc `REDIS_PORT`… nên các biến đó làm hỏng cấu hình.
- **Schema Auth được nạp tự động** bằng init container `auth-migrate` (golang-migrate, như `user-migrate`
  của User). Compose để người dùng nạp tay vì migration không chạy lại được trên volume đã nạp tay;
  database của cụm này chỉ do migrate nạp nên không gặp vấn đề đó.
- **`payment-service` và `notification-service` (+ Mailpit, LocalStack) chạy mặc định**, không theo profile.
  Payment dùng khoá Stripe giả của compose; khoá test thật: `kubectl -n ecommerce set env deploy/payment-service PAYMENT_STRIPE_SECRET_KEY=… PAYMENT_STRIPE_WEBHOOK_SECRETS=…` (mất khi apply lại).
- **Kafka** là Service headless với `publishNotReadyAddresses`, readiness kiểm cổng TCP; broker quảng bá
  `kafka.data.svc.cluster.local:9092` cho pod và `localhost:29092` cho host (`port-forward-dev.sh` forward tới `pod/kafka-0`).
  Broker tắt auto-create: Job `kafka-topics-init` tạo topic trong `infra/kafka/topics.txt` (cùng file với `kafka-init` của
  compose), nên apply lớp `infra` và chờ Job xong trước lớp `services`. Topic mới: thêm vào file rồi `apply.sh infra`.
  Broker bắt SASL/SCRAM và ACL ([mesh ADR-003](../../docs/mesh/decisions/ADR-003-kafka-sasl-scram-and-acls.md)): cùng Job tạo
  user SCRAM và ACL của `infra/kafka/acls.txt` từ Secret `kafka-credentials`; Kafka Connect đọc Secret
  `kafka-connect-credentials` (worker và sáu connector). Công cụ từ host qua `localhost:29092` cần cấu hình SASL
  của `kafka-admin`; trong pod: `kubectl -n data exec kafka-0 -- kafka-topics --bootstrap-server localhost:9092 --command-config /etc/kafka/admin.properties --list`.
  ZooKeeper chỉ nhận từ pod `kafka` (NetworkPolicy `zookeeper-from-kafka`).
- **Deployment của lớp services dùng `strategy: Recreate`**: một node, RAM sát; RollingUpdate chạy pod mới
  cạnh pod cũ và nhân đôi số JVM mỗi lần apply.
- Khi chưa apply lớp observability, service log lỗi `otel-collector: Name has no usable address` mỗi lần
  export span. Không ảnh hưởng request.
- Credential là giá trị dev của compose; không dùng cho môi trường thật.

## Vận hành

**Build lại một service** (image tag `latest` không đổi nên phải restart):

```bash
docker compose build order-service
kubectl -n ecommerce rollout restart deploy/order-service
```

**Sau khi Kafka restart**, Kafka Connect có thể báo connector RUNNING trong khi task đã dừng (replication
slot `active = f`). Restart Connect; cấu hình connector nằm trong topic Kafka nên không mất:

```bash
kubectl -n data exec postgres-0 -- psql -U postgres -Atc "select slot_name, active from pg_replication_slots"
kubectl -n data rollout restart deploy/kafka-connect
```

**Tài khoản dev**: `frontend/scripts/create-dev-account.sh` đọc OTP bằng `docker exec redis`, không chạy với
cụm này. Đọc OTP bằng `kubectl -n data exec redis-0 -- redis-cli --scan --pattern 'auth:delivery:*'` (container có
`REDISCLI_AUTH` nên không cần nhập mật khẩu), hoặc
mở Mailpit (http://localhost:8025) khi Notification đã gửi mail; cấp ADMIN bằng
`kubectl -n data exec postgres-0 -- psql -U postgres -d auth_db …`. `frontend/scripts/seed-dev-catalog.sh`
chỉ gọi gateway nên chạy được nguyên.

**Observability**:

- **Jaeger ghim 2.20.0**, cùng `docker-compose.yml`: 2.21+ bỏ API v1 mà datasource Jaeger của Grafana cần
  (chi tiết ở [runbook](../../docs/observability/runbook.md#luồng-dữ-liệu)).
- **Kiểm tra**: `scripts/verify-observability.py` (và `observability-audit.py`) chỉ gọi HTTP theo tên compose,
  nên chạy được trong một pod `python:3.12-alpine` ở namespace `observability` (alias phân giải các tên đó).
  Fixture `camera-luma-home` của hai script không có trong dữ liệu seed của cụm; đổi slug trong một bản sao
  khi chạy. Alloy gắn nhãn `project="e-commerce-microservices"` như compose vì verifier lọc Loki theo nó.
  Health check của datasource Alertmanager luôn 500 `plugin.unavailable`: đó là giới hạn của plugin, không
  phải lỗi (runbook).

## Dữ liệu, tạm dừng và gỡ bỏ

Dữ liệu nằm trong PVC (StorageClass `hostpath`) của namespace `data` và `observability`, giữ qua restart pod
và apply lại. Các namespace `ecommerce`, `edge` không giữ state.

Tạm dừng để lấy lại RAM, giữ dữ liệu (chạy lại `apply.sh` để bật):

```bash
kubectl -n observability scale statefulset,deployment --all --replicas=0
kubectl -n edge scale deployment --all --replicas=0
kubectl -n ecommerce scale deployment --all --replicas=0
kubectl -n data scale statefulset,deployment --all --replicas=0
```

Gỡ lớp services: `kubectl delete namespace ecommerce edge`. Gỡ observability: `kubectl delete namespace
observability` (mất metric/log/trace) và `kubectl delete clusterrole,clusterrolebinding otel-collector-pod-logs`. Gỡ hạ tầng: `kubectl delete namespace data`
xoá luôn PVC, tức là **mất toàn bộ dữ liệu**. Reset cụm trong Docker Desktop (Kubernetes → Reset cluster)
cũng xoá mọi PVC.
