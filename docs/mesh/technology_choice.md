# Quyết định công nghệ cho Service Mesh

> **Trạng thái:** Quyết định mục tiêu (`target/proposed`) — chưa có cluster Kubernetes nào trong
> repository để xác minh. Hành vi vận hành nằm trong [runtime-contract.md](runtime-contract.md);
> kiến trúc nằm trong [design.md](design.md); thứ tự công việc nằm trong
> [implementation_plan.md](implementation_plan.md).

## Quyết định

Dùng **Istio ở chế độ ambient** làm service mesh cho traffic **east-west** (service ↔ service)
trên Kubernetes. **Traefik giữ nguyên vai trò edge north-south** theo
[gateway/technology_choice.md](../gateway/technology_choice.md) và là một workload bên trong mesh;
**không deploy Istio Ingress Gateway**. Mesh là **nơi thực thi duy nhất** cho xác thực và phân
quyền service-to-service (mTLS + `AuthorizationPolicy`); kiểm tra identity ở tầng ứng dụng chỉ là
defense-in-depth. Đợt 1 chỉ đưa các service tự viết, edge và observability vào mesh; tầng dữ liệu
(Kafka, PostgreSQL, Redis, Elasticsearch, MinIO, Temporal) đứng ngoài.

## Bối cảnh

Nền tảng có 9 service đã có runtime (4 Go, 5 Java) và nhiều service đang ở mức thiết kế, gọi nhau
qua HTTP nội bộ (`/internal/**`), gRPC (`product-service:9091`) và Kafka. Toàn bộ tài liệu
service và contract giả định "mTLS/service identity" cho internal API, nhưng chưa có cơ chế nào
cung cấp nó: docker-compose chỉ có một bridge network, header `X-Service-Identity` và `X-User-*`
spoof được từ bất kỳ container nào trong network ([promotion/README.md](../promotion/README.md),
[gateway/runtime-contract.md §1](../gateway/runtime-contract.md#1-ranh-giới-trách-nhiệm)).

Gateway đã chốt Traefik v3.7.1 + `gateway-auth` ForwardAuth + Redis rate limit + OpenTelemetry
Collector, và ghi rõ Kubernetes Gateway API/CRD là nguồn production. Repository chưa có manifest
Kubernetes; máy phát triển không có JDK/Node nhưng chạy được Docker, nên việc kiểm chứng mesh
phải diễn ra trên một cluster (kind/k3d hoặc cluster thật), không phải trên Compose.

## Vì sao cần mesh

| Nhu cầu | Không mesh | Có mesh |
|---|---|---|
| Service chỉ tin `X-User-*` từ gateway | Lời hứa "mạng private" + NetworkPolicy L3/L4 theo IP pod | Principal SPIFFE của Traefik, xác minh bằng mTLS, áp cho từng path |
| `/internal/**` chỉ cho service được phép gọi | Header tự khai, allowlist trong từng service, mỗi ngôn ngữ một cách | Một `AuthorizationPolicy` per service, dẫn xuất từ `contracts/catalog/interactions.yaml` |
| Mã hóa transport nội bộ | Từng service tự cấu hình TLS cho Go và Java | ztunnel làm mTLS đồng nhất, không đổi code |
| gRPC HTTP/2 cân bằng per-request | Kubernetes Service chỉ L4, kết nối dính một pod | Waypoint cân bằng theo request |
| Telemetry đồng nhất | Mỗi service tự instrument (vẫn cần) | Thêm L4/L7 metrics và span từ mesh, cùng chuẩn W3C |

Mesh **không** thay thế instrumentation trong ứng dụng, không truyền trace context thay ứng dụng,
không sở hữu business retry/idempotency và không thay Gateway edge.

## Vì sao chọn Istio

| Lựa chọn | Quyết định | Lý do |
|---|---|---|
| Không dùng mesh, chỉ NetworkPolicy + TLS trong từng service | Loại bỏ | Không có workload identity; policy theo IP không theo path; mỗi ngôn ngữ một cách làm TLS |
| Linkerd | Không chọn | Đủ cho mTLS/L7 policy nhưng không có mode tương đương ambient để tránh sidecar cho JVM; stable release từ 2.15 phụ thuộc nhà cung cấp thương mại |
| Cilium mesh | Không chọn | Gắn chặt với CNI, ràng buộc lựa chọn cluster; L7 policy vẫn cần Envoy per node |
| Istio (ambient) | Chọn | Gateway API cho cả edge lẫn mesh, `AuthorizationPolicy` theo principal + path, ambient GA từ 1.24, cộng đồng và tài liệu rộng |

## Quyết định north-south: Traefik giữ edge

| Lựa chọn | Quyết định | Lý do |
|---|---|---|
| Thay Traefik bằng Istio Ingress Gateway | Loại bỏ | Rate limit phân tán chỉ có qua `envoyproxy/ratelimit` + `EnvoyFilter`; thứ tự "xoá `X-User-*` client gửi → ForwardAuth → enrich" không tường minh trong filter chain Envoy; không có story cho Docker Compose local |
| Chạy song song hai ingress | Loại bỏ | Hai điểm vào công khai, hai bộ policy edge phải đồng bộ |
| Traefik là workload trong mesh, Istio chỉ east-west | Chọn | Giữ nguyên toàn bộ quyết định gateway; hop Traefik → service được mTLS và có principal để service tin `X-User-*` |

Route định nghĩa bằng Gateway API `HTTPRoute` với `GatewayClass` của Traefik, nên phần routing
portable nếu về sau đổi edge; phần **không portable** là ForwardAuth và Redis rate limit. Nếu điều
kiện xem lại (mục cuối) xảy ra, ứng viên là **Envoy Gateway** (ext-auth và global rate limit Redis
trong API chuẩn) chứ không phải Istio Ingress Gateway.

## Quyết định mode: ambient thay vì sidecar

| Tiêu chí | Sidecar | Ambient | Kết luận |
|---|---|---|---|
| mTLS + identity (động lực chính) | Có | Có, chỉ với ztunnel, không đụng pod | Ambient rẻ hơn |
| Tài nguyên: 5 JVM + 4 Go, ≥2 replica mỗi service | +1 Envoy mỗi pod | ztunnel per node + waypoint per namespace | Ambient |
| Job/one-shot (`minio-init`, migration, đăng ký connector) | Sidecar không thoát → Job treo trừ khi native sidecar | Không ảnh hưởng | Ambient |
| Thứ tự khởi động (Kafka consumer, DB connect lúc boot) | Cần `holdApplicationUntilProxyStarts` | Không ảnh hưởng | Ambient |
| Traefik trong mesh | Traefik + Envoy sidecar = hai proxy L7 ở edge | Traefik + ztunnel L4 | Ambient |
| Nâng cấp data plane | Restart mọi pod | Không restart pod | Ambient |
| Policy L7 khác nhau theo caller | Có (proxy phía client) | Không — waypoint đứng phía đích | Không cần: mesh-level retry đã quyết định là đồng nhất theo đích |
| Ứng dụng nhìn thấy identity caller (`X-Forwarded-Client-Cert`) | Có | **Không** — waypoint không forward XFCC | Xem quyết định caller identity bên dưới |
| `EnvoyFilter`/Wasm phía client | Có | Chỉ trên waypoint | Không có nhu cầu |

Hai mode trộn được: nếu một workload cụ thể về sau cần client-side L7, inject sidecar cho riêng
workload đó; phần còn lại giữ ambient.

## Quyết định caller identity: mesh là authority

Trong ambient, waypoint **không** đặt `X-Forwarded-Client-Cert` vào request tới ứng dụng
(maintainer Istio từ chối vì không phải mọi request đều qua waypoint, header sẽ spoof được —
[istio/istio#54888](https://github.com/istio/istio/discussions/54888),
[istio/istio#54995](https://github.com/istio/istio/issues/54995)). Ứng dụng vì thế không có
cách chuẩn để tự xác minh caller.

| Lựa chọn | Quyết định | Lý do |
|---|---|---|
| Mesh là authority; `X-Service-Identity` do caller đặt, chỉ dùng attribution | Chọn | `AuthorizationPolicy` theo principal + path là nơi thực thi duy nhất; code Product/Promotion hiện tại (allowlist header) không cần đổi, trở thành defense-in-depth |
| Waypoint ghi SPIFFE ID vào header bằng `EnvoyFilter` | Loại bỏ | Dựa vào `EnvoyFilter` chưa xác minh trên security-critical path — vi phạm điều kiện xem lại của chính quyết định này |
| Chuyển sang sidecar để có XFCC | Loại bỏ | Mất mọi lợi thế ambient ở trên; cần đổi code đọc XFCC ở mọi service |

Hệ quả bắt buộc: mọi câu "header do mesh/ingress xác minh" trong docs và contract được sửa
thành "mesh thực thi allowlist theo principal; header do caller đặt, chỉ dùng cho log/audit";
Gateway phải xoá `X-Service-Identity` do client gửi như với `X-User-*`; từ chối ở mesh trả `403`
từ waypoint (`text/plain`, `RBAC: access denied`), không phải `application/problem+json`.

## Quyết định phạm vi đợt 1

| Namespace | Đợt 1 | Lý do |
|---|---|---|
| `edge` (Traefik, `gateway-auth`) | ztunnel, không waypoint | Traefik đã là L7 proxy; chỉ cần mTLS và principal |
| `ecommerce` (mọi service tự viết) | ztunnel + một waypoint | Cần L7 ngay: authz theo path `/internal/**`, gRPC LB, HTTP metrics/span |
| `observability` (OTel Collector, Prometheus, Jaeger, Loki, Grafana) | ztunnel, không waypoint | Prometheus phải ở trong mesh mới scrape được pod `STRICT`; OTLP export được mTLS |
| `data` (Kafka, Kafka Connect, PostgreSQL, Redis, Elasticsearch, MinIO, Temporal) | Ngoài mesh | Giảm biến số đợt 1; ambient L4 cho phép thêm sau mà không restart |

## Quyết định traffic policy east-west

Retry/timeout/outlier detection ở mesh cấu hình bằng `VirtualService`/`DestinationRule`
(waypoint thực thi), không dùng Gateway API `HTTPRoute` cho traffic policy vì retry của
Gateway API chưa GA. Mesh chỉ retry lỗi connect-level trên route idempotent đã khai báo; route
không idempotent có `attempts: 0` tường minh; không route nào dựa vào giá trị retry mặc định
của Istio. Ứng dụng giữ business retry và idempotency theo
`Idempotency-Key`/natural key đã chốt trong contract của từng service (`contracts/http/*`).

## Quyết định khả năng quan sát

Mesh export trace qua `Telemetry` API tới OpenTelemetry Collector đã có của gateway (provider
`opentelemetry`, W3C `traceparent`), không dựng Jaeger/Kiali riêng. Prometheus scrape trực tiếp
waypoint và ztunnel. Envoy không emit exemplar; quyết định exemplar trong
[gateway/technology_choice.md](../gateway/technology_choice.md#quyết-định-prometheus-exemplars) chỉ
áp dụng cho metrics do ứng dụng và `gateway-auth` emit.

## Quyết định version

Istio và `istio-cni` pin bằng exact version kèm digest; `latest` bị cấm như với mọi image khác.
Phiên bản cụ thể là `<pinned-version>` cho tới khi có cluster để xác minh ma trận tương thích
Kubernetes/CNI — đây là gap ghi tại [runtime-contract.md §11](runtime-contract.md#11-khoảng-cách-với-trạng-thái-hiện-tại).

## Hệ quả

Lợi ích: workload identity và mTLS cho mọi hop nội bộ mà không đổi code; `AuthorizationPolicy`
biến ma trận dependency đã có thành policy thực thi được; gRPC cân bằng đúng; một chuỗi
telemetry cho gateway, mesh và service.

Chi phí: thêm control plane (istiod), ztunnel, waypoint và `istio-cni` cần vận hành; waypoint là
điểm lỗi tập trung của namespace nên cần ≥2 replica, PDB và sizing; ứng dụng không thể tự xác
minh caller nên audit đối chiếu header với principal phải làm ở waypoint access log; Compose
local không có mesh nên mọi bảo đảm ở đây chỉ được kiểm chứng trên cluster.

## Điều kiện xem lại

- Cần `EnvoyFilter` phía client cho ≥1 tính năng security-critical.
- Cluster mục tiêu không chạy được `istio-cni`/ambient.
- Load test cho thấy waypoint là bottleneck hoặc latency không chấp nhận được.
- Traefik không emit exemplar và team coi đó là blocker, hoặc cần global rate limit ở nhiều entry
  point ngoài Traefik → cân nhắc Envoy Gateway cho edge, không phải Istio Ingress Gateway.
- Istio cung cấp cơ chế chuẩn để waypoint truyền identity caller tới ứng dụng → xem lại mô hình
  defense-in-depth.
