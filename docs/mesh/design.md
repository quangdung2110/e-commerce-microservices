# Thiết kế kiến trúc Service Mesh

> **Trạng thái:** `target/proposed`. Tài liệu này giải thích kiến trúc; hành vi vận hành bắt buộc
> nằm trong [runtime-contract.md](runtime-contract.md), lý do lựa chọn nằm trong
> [technology_choice.md](technology_choice.md).

## Mục đích

Service mesh cung cấp workload identity, mTLS và phân quyền service-to-service cho mọi hop nội
bộ của nền tảng, đồng thời bổ sung traffic policy L7 và telemetry đồng nhất cho traffic
east-west. Mesh không phải API Gateway, không sở hữu business logic và không thay
instrumentation trong ứng dụng.

## Kiến trúc

```
Internet
  │ HTTPS
  ▼
LoadBalancer ──► Traefik (namespace edge, ztunnel)
                   │  ForwardAuth (mTLS qua ztunnel)
                   ├──────────────► gateway-auth (edge, ztunnel) ──► Redis (data, ngoài mesh)
                   │
                   │ HBONE mTLS, principal = edge/traefik
                   ▼
              ecommerce-waypoint (Envoy, L7: AuthorizationPolicy path/method,
                   │              VirtualService retry/timeout, gRPC LB, HTTP metrics/span)
                   ▼
   ┌──────────────────────────────────────────────────────────────┐
   │ namespace ecommerce (ztunnel trên mỗi node)                   │
   │ product · promotion · inventory · review · search (Java)      │
   │ auth · user · cart (Go) · checkout · order · payment · ...    │
   │   service ──HBONE──► waypoint ──HBONE──► service              │
   └──────────────────────────────────────────────────────────────┘
                   │ plaintext/app TLS (đợt 1)              │ OTLP + scrape (mTLS)
                   ▼                                          ▼
   namespace data (ngoài mesh)                 namespace observability (ztunnel)
   Kafka · Kafka Connect · PostgreSQL           OTel Collector · Prometheus · Jaeger
   Redis · Elasticsearch · MinIO · Temporal     Loki · Grafana
```

Ba thành phần Istio:

| Thành phần | Vị trí | Vai trò |
|---|---|---|
| istiod | `istio-system` | Control plane: cấp chứng chỉ SPIFFE, dịch policy sang cấu hình ztunnel/waypoint |
| ztunnel | DaemonSet mỗi node | L4: mTLS (HBONE), identity, `AuthorizationPolicy` theo principal/namespace/port, TCP metrics |
| waypoint | Deployment per namespace (`ecommerce-waypoint`) | L7: `AuthorizationPolicy` theo path/method, `VirtualService`/`DestinationRule`, cân bằng HTTP/2, HTTP metrics/span, access log |

`istio-cni` chạy trên mỗi node để redirect traffic pod vào ztunnel mà không cần init container
đặc quyền trong pod ứng dụng.

## Namespace và phạm vi

| Namespace | Nhãn ambient | Waypoint | Nội dung |
|---|---|---|---|
| `edge` | Có | Không | Traefik, `gateway-auth` |
| `ecommerce` | Có | `ecommerce-waypoint` | Mọi service trong `contracts/catalog/services.yaml` trừ `api-gateway` |
| `observability` | Có | Không | OTel Collector, Prometheus, Jaeger, Loki, Grafana |
| `data` | Không (đợt 1) | Không | Kafka, ZooKeeper, Kafka Connect, PostgreSQL, Redis, Elasticsearch, MinIO, Temporal |
| `istio-system` | — | — | istiod, ztunnel, `istio-cni` |

Một namespace `ecommerce` cho mọi service là quyết định đợt 1 để có đúng một waypoint và một
ma trận policy. Tách theo bounded context là bước sau nếu quyền sở hữu theo team đòi hỏi;
`AuthorizationPolicy` đã gắn theo Service nên việc tách không đổi mô hình.

## Identity

Mỗi service có một ServiceAccount riêng, tên trùng `id` trong
[`contracts/catalog/services.yaml`](../../contracts/catalog/services.yaml). Principal SPIFFE là
`cluster.local/ns/<namespace>/sa/<service-id>`, ví dụ `cluster.local/ns/ecommerce/sa/product-service`,
`cluster.local/ns/edge/sa/traefik`, `cluster.local/ns/edge/sa/gateway-auth`. Không service nào
dùng ServiceAccount `default`. Identity này là **định danh caller duy nhất** mà mesh thực thi.

## Ranh giới tin cậy

```
client ──► Traefik: xoá X-User-*, X-Service-Identity do client gửi
             │      ForwardAuth → gateway-auth → enrich X-User-* (allowlist)
             ▼
      waypoint ecommerce: chỉ principal edge/traefik được vào /api/**, /ws/**, /.well-known/**
             ▼
      service: tin X-User-* vì mesh bảo đảm caller là Traefik và Traefik bảo đảm sanitize

service A ──► đặt X-Service-Identity: <A> (tự khai)
             ▼
      waypoint ecommerce: chỉ principal của A được vào /internal/... theo ma trận
             ▼
      service B: đã được mesh bảo đảm caller hợp lệ; allowlist header trong app là defense-in-depth
```

Ba quy tắc:

1. **`X-User-*`** chỉ có nghĩa trên route public (`/api/**`, `/ws/**`). Mesh chỉ cho principal
   Traefik vào các route đó; Traefik chịu trách nhiệm sanitize/enrich theo
   [gateway/runtime-contract.md §4](../gateway/runtime-contract.md#4-luồng-xử-lý-request). Trên
   `/internal/**` ứng dụng phải bỏ qua `X-User-*`.
2. **`X-Service-Identity`** do caller đặt bằng chính ServiceAccount name của nó. Mesh **không**
   xác minh nội dung header; mesh xác minh principal và chỉ cho principal được phép chạm tới path.
   Ứng dụng dùng header cho log/audit/problem detail và allowlist defense-in-depth
   (Product `reindex-clients`, Promotion `TrustedIdentityFilter`, Review static bearer token),
   không bao giờ coi nó là bằng chứng authorization duy nhất.
3. **Từ chối ở mesh** là `403` từ waypoint (`text/plain`, body `RBAC: access denied`), xảy ra
   trước khi request tới ứng dụng. `401`/`403` `application/problem+json` trong các contract
   internal chỉ còn xuất hiện khi request đã qua mesh nhưng thiếu/không khớp header
   defense-in-depth.

NetworkPolicy Kubernetes L3/L4 vẫn được áp dụng làm lớp phòng thủ thứ hai (deny ingress vào
`ecommerce` trừ từ `edge`, `observability` và trong namespace), độc lập với mesh.

## Phân quyền service-to-service

`AuthorizationPolicy` dẫn xuất trực tiếp từ
[`contracts/catalog/interactions.yaml`](../../contracts/catalog/interactions.yaml) (HTTP/gRPC) và
[gateway route matrix](../gateway/runtime-contract.md#5-ma-trận-định-tuyến-bắt-buộc); ma trận đầy
đủ nằm ở [runtime-contract.md §5](runtime-contract.md#5-ma-trận-phân-quyền). Mô hình hai tầng:

| Tầng | Nơi thực thi | Policy | Nội dung |
|---|---|---|---|
| Default deny | ztunnel + waypoint | Một `AuthorizationPolicy` rỗng (ALLOW không rule) per namespace | Không traffic nào được phép nếu không có policy ALLOW khớp |
| L4 | ztunnel của pod đích | `selector` theo workload | Chỉ nhận từ principal waypoint (traffic có L7) hoặc principal cụ thể (traffic L4 thuần như `gateway-auth`, scrape metrics) |
| L7 | `ecommerce-waypoint` | `targetRefs` tới Service đích | `from.source.principals` × `to.operation.paths/methods/ports` |

Kafka không đi qua mesh ở đợt 1; consumer/producer authorization tiếp tục thuộc broker ACL theo
từng tài liệu service.

## Traffic policy east-west

| Trách nhiệm | Mesh (waypoint) | Ứng dụng |
|---|---|---|
| Retry lỗi connect-level (`connect-failure`, `refused-stream`, gRPC `unavailable`) | Chỉ trên route idempotent khai báo tường minh | Không lặp lại cùng điều kiện |
| Retry business (`409`, `503` có `Retry-After`, `202 CLAIM_PENDING`) | Không | Có, theo idempotency key/natural key của contract |
| Timeout | Ngân sách ngoài: ≥ deadline ứng dụng + 1 giây | Deadline thực, ngắn hơn mesh; gửi cho provider qua `X-Request-Timeout-Ms` hoặc deadline gRPC ([contracts ADR-003](../../contracts/decisions/ADR-003-deadline-propagation.md)) |
| Outlier detection / connection pool | Có, per DestinationRule | Circuit breaker business (resilience4j) nếu service đã có |
| Cân bằng tải | Per-request cho HTTP/1.1, HTTP/2, gRPC | Không |

Route không idempotent (`POST /internal/inventory/v1/reservations`, allocation Promotion, tạo
order, payment) có `attempts: 0` tường minh. Mesh timeout không thay ngân sách timeout của
[Checkout](../checkout/operations.md) hay activity timeout của [Order](../order/operations.md); nó
là giới hạn an toàn phía ngoài.

## gRPC

`product-service` expose gRPC trên port riêng (`9091` trong Compose). Kubernetes Service khai
báo port với `name: grpc` và `appProtocol: grpc` để waypoint xử lý HTTP/2 ở L7 và cân bằng
theo request; `cart-service` (và Checkout khi có runtime) gọi qua tên Service, không qua
headless Service hay IP pod. `AuthorizationPolicy` cho gRPC dùng `paths` dạng
`/ecommerce.product.v1.ProductService/*`.

## Traefik trong mesh

Traefik nhận traffic công khai từ LoadBalancer bên ngoài mesh, nên inbound port `80`/`443` của
Traefik phải là `PERMISSIVE` ở mức port; mọi port khác của `edge` là `STRICT`. Traefik → service
đi qua ztunnel của node Traefik tới waypoint `ecommerce` bằng HBONE; Traefik không cần biết
mesh tồn tại và không cấu hình TLS upstream. Trong Kubernetes, Traefik dựa vào readiness của
Endpoints thay vì active health check qua mesh, nên không cần mở path health cho principal
Traefik. Route định nghĩa bằng Gateway API `HTTPRoute` với `GatewayClass` Traefik; Docker label
chỉ dùng cho Compose.

## Khả năng quan sát

- **Trace:** waypoint tạo span server-side cho mỗi request L7 và export OTLP tới OTel Collector
  qua `Telemetry` API với provider `opentelemetry`; propagation W3C `traceparent`/`tracestate`
  cùng chuẩn với gateway. ztunnel không tạo span. Ứng dụng vẫn phải propagate context — mesh
  không làm thay.
- **Metrics:** Prometheus (trong mesh) scrape trực tiếp waypoint (`istio_requests_total`,
  `istio_request_duration_milliseconds`, theo `source_principal`/`destination_service`) và
  ztunnel (`istio_tcp_*`). Không có exemplar từ Envoy.
- **Log:** waypoint access log JSON ra stdout, có `trace_id`, `source_principal`,
  `destination_service`, `response_code`, `response_flags`; OTel Collector thu qua `filelog`
  receiver sang Loki như access log của Traefik. Đây là nơi duy nhất đối chiếu được
  `X-Service-Identity` (do caller khai) với principal thật.

## Chế độ lỗi

| Sự cố | Ảnh hưởng | Hành vi mong muốn |
|---|---|---|
| istiod down | Không cấp/rotate chứng chỉ, không đẩy policy mới | Data plane tiếp tục với cấu hình và chứng chỉ hiện có; alert trước khi chứng chỉ (24 giờ) hết hạn |
| ztunnel trên một node restart | Kết nối đang mở trên node đó bị đứt | Client retry connect-level; pod không restart |
| Waypoint `ecommerce` mất toàn bộ replica | Mọi traffic L7 vào `ecommerce` lỗi, kể cả từ Traefik | ≥2 replica, PDB `minAvailable: 1`, HPA; alert `istio_requests_total{response_flags="UF"}` |
| Policy sai (deny nhầm) | `403 RBAC: access denied` hàng loạt | Rollback manifest policy; không bao giờ đổi namespace về PERMISSIVE để "mở tạm" |
| Prometheus ngoài mesh | Không scrape được pod STRICT | Đã đưa `observability` vào mesh; không hạ mode để scrape |

## Các mục tiêu không thuộc phạm vi

Không dùng Istio `RequestAuthentication` cho JWT người dùng (`gateway-auth` sở hữu, có
revocation); không dùng Istio Ingress Gateway; không đưa tầng `data` vào mesh ở đợt 1; không
multi-cluster; không egress gateway (egress kiểm soát bằng `ServiceEntry` +
`outboundTrafficPolicy: REGISTRY_ONLY`); không dùng mesh để quản lý frame WebSocket sau
handshake.
