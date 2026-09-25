# Quy ước vận hành của Service Mesh

> **Trạng thái:** Quy ước mục tiêu (`target/proposed`) — chưa xác minh trên cluster.
> **Nguồn chân lý:** Tài liệu này định nghĩa hành vi bắt buộc của mesh và được ưu tiên hơn
> manifest hoặc kế hoạch khi có mâu thuẫn. Với identity header và route public, nó bổ sung —
> không thay thế — [gateway/runtime-contract.md](../gateway/runtime-contract.md).

## 1. Ranh giới trách nhiệm

Mesh chịu trách nhiệm cấp workload identity, mTLS cho mọi hop nội bộ trong phạm vi, thực thi
phân quyền service-to-service theo principal + path, retry/timeout connect-level trên route đã
khai báo, cân bằng tải L7 và telemetry của các hop đó. Mesh **không** chịu trách nhiệm xác thực
người dùng cuối (thuộc `gateway-auth`), rate limit edge (thuộc Traefik), business retry và
idempotency (thuộc service), authorization nghiệp vụ (thuộc service), truyền trace context trong
ứng dụng (thuộc service) và broker ACL Kafka (thuộc tài liệu từng service).

Ứng dụng **không nhìn thấy identity caller đã xác minh**. Bất kỳ kiểm tra caller nào trong ứng
dụng đều là defense-in-depth; `AuthorizationPolicy` mục 5 là nơi thực thi duy nhất.

## 2. Runtime được hỗ trợ

| Hạng mục | Quy ước |
|---|---|
| Mesh | Istio `<pinned-version>` ambient mode, pin exact version + digest cho istiod, ztunnel, waypoint image và `istio-cni`; cấm `latest` |
| Kubernetes | Phiên bản trong ma trận tương thích của bản Istio đã pin; CNI của cluster phải được `istio-cni` hỗ trợ ở ambient |
| Cài đặt | Helm chart chính thức (`base`, `istiod`, `cni`, `ztunnel`), values được version hóa; không `istioctl install` thủ công trên production |
| Profile | `ambient`; sidecar injection tắt mặc định (`istio-injection` không gắn cho namespace nào) |
| Edge | Traefik `v3.7.1` là workload trong `edge`; không deploy Istio Ingress Gateway |
| Traffic policy east-west | `VirtualService` + `DestinationRule` (waypoint thực thi); `HTTPRoute` chỉ dùng cho Traefik |
| Telemetry | `Telemetry` API → OpenTelemetry Collector (`observability`); Prometheus scrape trực tiếp |
| Local development | Docker Compose **không có mesh**; mọi bảo đảm trong tài liệu này chỉ tồn tại trên cluster |

## 3. Namespace, nhãn và yêu cầu workload

| Namespace | `istio.io/dataplane-mode` | `istio.io/use-waypoint` | mTLS |
|---|---|---|---|
| `edge` | `ambient` | — | STRICT, trừ port `80`/`443` của Traefik: PERMISSIVE |
| `ecommerce` | `ambient` | `ecommerce-waypoint` | STRICT |
| `observability` | `ambient` | — | STRICT |
| `data` | không gắn | — | Ngoài mesh (đợt 1) |

Yêu cầu bắt buộc cho mọi workload trong `edge` và `ecommerce`:

- Một ServiceAccount riêng, `automountServiceAccountToken: false` trừ khi service cần
  Kubernetes API; tên trùng `id` trong [`services.yaml`](../../contracts/catalog/services.yaml).
- Kubernetes Service khai báo `name` và `appProtocol` cho từng port: `http` cho HTTP/1.1,
  `grpc` cho gRPC (`product-service` port gRPC), `http2` nếu có. Port không khai báo
  `appProtocol` được coi là TCP thuần và không có L7 policy.
- Không `hostNetwork`, không `hostPort`; pod dùng `hostNetwork` không được ztunnel bảo vệ.
- Job/CronJob trong hai namespace này chạy bình thường (không có sidecar cần thoát).
- Readiness/liveness probe của kubelet đi thẳng, không qua mTLS — không cần cấu hình thêm.
- Waypoint `ecommerce-waypoint`: `Gateway` với `gatewayClassName: istio-waypoint`, ≥2 replica,
  `PodDisruptionBudget minAvailable: 1`, HPA theo CPU, request/limit được load test xác nhận.

## 4. Xác thực transport

`PeerAuthentication` `default` trong `istio-system` đặt `mtls.mode: STRICT` toàn mesh.
`PeerAuthentication` riêng cho workload Traefik đặt `portLevelMtls` `80`/`443` = `PERMISSIVE`
vì LoadBalancer bên ngoài mesh kết nối trực tiếp. Không namespace hay workload nào khác được
PERMISSIVE; "mở tạm PERMISSIVE để debug" là vi phạm contract.

Chứng chỉ workload do istiod cấp, TTL 24 giờ, rotate tự động; alert khi chứng chỉ còn dưới 6 giờ
mà chưa rotate.

## 5. Ma trận phân quyền

Ký hiệu principal: `edge/traefik` = `cluster.local/ns/edge/sa/traefik`;
`ecommerce/<id>` = `cluster.local/ns/ecommerce/sa/<id>`. Mỗi namespace có một policy default-deny
(ALLOW không rule). Policy L7 gắn `targetRefs` tới Service đích và được waypoint thực thi;
policy L4 gắn `selector` và được ztunnel thực thi.

### 5.1 Namespace `edge` (L4, ztunnel)

| Đích | Port | Được phép từ |
|---|---|---|
| Traefik | `80`, `443` | Bất kỳ (PERMISSIVE; bảo vệ bằng LoadBalancer/firewall) |
| Traefik | `8090` metrics | `observability/prometheus` |
| `gateway-auth` | HTTP | `edge/traefik` |
| `gateway-auth` | metrics | `observability/prometheus` |

### 5.2 Namespace `ecommerce` (L7, waypoint)

Route public dùng path theo contract trong `contracts/http/*`; nơi gateway route matrix còn dùng
prefix khác (`/api/users/**`) thì contract được ưu tiên và
gateway matrix phải được đối soát (mục 11). `X` = mọi method của route.

| Đích | Path / operation | Method | Được phép từ | Nguồn |
|---|---|---|---|---|
| `auth-service` | `/api/v1/auth/**` | X | `edge/traefik` | gateway matrix |
| `auth-service` | `/.well-known/**` | GET | `edge/traefik`, `edge/gateway-auth` | gateway matrix; `gateway-verifies-auth-tokens` |
| `auth-service` | `/internal/v1/auth/notification-deliveries/*/content` | POST | `ecommerce/notification-service` | `notification-resolves-auth-delivery-content`; Auth OpenAPI khai báo POST (operation idempotent theo `delivery_id`, không consume OTP/token) |
| `user-service` | `/api/v1/users/**` | X | `edge/traefik` | User OpenAPI v1 |
| `user-service` | `/internal/v1/users/*/addresses/*/checkout-snapshot` | GET | `ecommerce/checkout-service` | `checkout-reads-user-address-snapshot` |
| `cart-service` | `/api/v1/carts/**` | X | `edge/traefik` | Cart OpenAPI v1 |
| `cart-service` | `/internal/v1/carts/*/checkout-snapshot` | GET | `ecommerce/checkout-service` | `checkout-reads-cart-snapshot` |
| `product-service` | `/api/v1/products/**`, `/api/v1/categories/**`, `/api/v1/attributes/**` | X | `edge/traefik` | gateway matrix |
| `product-service` | `/internal/v1/products/export`, `/internal/v1/categories/export` | X | `ecommerce/search-service` | `search-reindexes-product-catalog` |
| `product-service` | `/internal/v1/products/*/snapshot` | GET | `ecommerce/search-service`, `ecommerce/promotion-service` | Product OpenAPI `searchServiceMtls` |
| `product-service` | `/internal/v1/product-details/*` | GET | `ecommerce/product-detail-bff` | `product-detail-bff-queries-product-catalog` |
| `product-service` (port `grpc`) | `/ecommerce.product.v1.ProductService/*` | POST | `ecommerce/cart-service`, `ecommerce/checkout-service` | `cart-and-checkout-query-product-skus` |
| `promotion-service` | `/api/v1/me/**`, `/api/v1/promotion-discovery`, `/api/v1/admin/**` | X | `edge/traefik` | gateway matrix |
| `promotion-service` | `/internal/v1/promotion-discount-calculations` | POST | `ecommerce/checkout-service` | `checkout-calculates-promotions` |
| `promotion-service` | `/internal/v1/promotion-allocation/{reservations,cancellations,confirmations}` | POST | `ecommerce/order-service` | `order-commands-promotion-allocation` |
| `promotion-service` | `/internal/v1/promotion-allocation/technical-cancellations` | POST | `ecommerce/promotion-legacy-migrator` | `legacy-migrator-technical-cancels-promotion-allocation` |
| `inventory-service` | `/admin/inventory/v1/**` | X | `edge/traefik` | `warehouse-admin-operates-inventory` |
| `inventory-service` | `/internal/inventory/v1/allocation-previews` | POST | `ecommerce/checkout-service` | `checkout-previews-inventory-allocation` |
| `inventory-service` | `/internal/inventory/v1/reservations/**`, `/internal/inventory/v1/refund-scopes/**`, `/internal/inventory/v1/commands/*` | X | `ecommerce/order-service` | `order-commands-inventory-workflow` |
| `inventory-service` | `/internal/inventory/v1/sku-availabilities:batch-get` | POST | `ecommerce/product-detail-bff` | `product-detail-bff-queries-inventory-availability` |
| `inventory-service` | `/exports/inventory/v1/**` | X | `ecommerce/search-service` | `search-exports-inventory-availability` |
| `review-service` | `/api/v1/reviews/**`, `/api/v1/admin/reviews/**` | X | `edge/traefik` | gateway matrix |
| `review-service` | `/internal/v1/reviews/products/*/pdp-summary` | GET | `ecommerce/product-detail-bff` | `product-detail-bff-queries-review-summary` |
| `review-service` | `/internal/v1/reviews/rating-aggregate-exports**` | X | `ecommerce/search-service` | `search-reindexes-review-rating-aggregates` |
| `search-service` | `/api/v2/search/**` | X | `edge/traefik` | gateway matrix |
| `product-detail-bff` | `/api/v1/storefront/products/**` | GET | `edge/traefik` | `storefront-queries-product-detail`; gateway matrix |
| `checkout-service` | `/api/v1/checkout/preview`, `/api/v1/checkout/submit` | POST | `edge/traefik` | `gateway-routes-checkout` |
| `checkout-service` | `/api/v1/checkout/submissions/*` | GET | `edge/traefik` | `gateway-routes-checkout` |
| `order-service` | `/api/v1/orders/**`, `/api/v1/admin/orders/**`, `/api/v1/order-operations/**`, `/api/v1/admin/order-operations/**` | X | `edge/traefik` | Order public OpenAPI v1 |
| `order-service` | `/internal/v1/checkout-order-submissions`, `/internal/v1/checkout-order-submissions/*` | POST/GET | `ecommerce/checkout-service` | `checkout-creates-order` |
| `order-service` | `/internal/v1/orders/*/financial-reconciliation-case`, `/internal/v1/orders/*/financial-reconciliation-decisions` | GET/POST | `ecommerce/finance-ops`, `ecommerce/accounting-ops` | `finance-ops-decides-order-financial-reconciliation` |
| `payment-service` | `/v1/payments/webhook/stripe` | POST | `edge/traefik` | gateway public `/api/payments/webhook/stripe` rewrite sang service path này; route public duy nhất của Payment |
| `payment-service` | `/v1/payments/initiate`, `/v1/payments/{transactionId}/cancel`, `/v1/payments/{transactionId}/refund` | POST | `ecommerce/order-service` | `order-initiates-payment`; policy triển khai phải dùng match tương đương nhưng không được bao trùm webhook |
| `payment-service` | `/v1/payments/{transactionId}`, `/v1/payments/{transactionId}/client-data`, `/v1/refunds/{refundTransactionId}` | GET | `ecommerce/order-service` | `order-initiates-payment` |
| `chat-service` | `/ws/**` | GET (Upgrade) | `edge/traefik` | gateway matrix |
| `auth-service` | `/internal/v1/auth/users/*/notification-contact` | GET | `ecommerce/notification-service` | `notification-resolves-auth-contact` |
| `notification-service` | `/api/v1/notifications/**` | X | `edge/traefik` | `gateway-routes-notification-inbox` |
| `notification-service` | `/internal/v1/notifications/requests/*`, `/internal/v1/notifications/deliveries` | GET | `ecommerce/order-service`, `ecommerce/finance-ops` | `support-queries-notification-status` |
| `notification-service` | `/internal/v1/notifications/ops/**` | X | `ecommerce/notification-ops` | `notification-ops-operates-notification`; không route qua Gateway |
| Mọi service | port metrics | GET | `observability/prometheus` | mục 9 |

Không có rule nào cho `analytics-service` inbound HTTP: service này chỉ nhận Kafka.
`notification-service` nhận HTTP từ Traefik (inbox), từ support workload (status) và từ
`notification-ops` (ops); SES event đi qua SNS → SQS nên không có webhook route. Mọi path
không xuất hiện trong bảng bị từ chối. Khi thêm interaction mới vào
`interactions.yaml`, PR phải kèm dòng mới trong bảng này và policy tương ứng.

### 5.3 Namespace `observability` (L4, ztunnel)

| Đích | Port | Được phép từ |
|---|---|---|
| OTel Collector | OTLP gRPC/HTTP | `edge/*`, `ecommerce/*`, `istio-system/*` (waypoint/ztunnel) |
| Prometheus, Jaeger, Loki | UI/API | `observability/grafana`; operator qua port-forward, không public |
| Grafana | HTTP | Theo chính sách truy cập nội bộ, ngoài phạm vi mesh |

## 6. Quy ước identity header

| Header | Ai đặt | Mesh bảo đảm | Ứng dụng được phép làm |
|---|---|---|---|
| `X-User-Id`, `X-User-Roles`, `X-User-Session-Id`, `X-Token-Jti` | `gateway-auth` qua Traefik, theo [gateway/runtime-contract.md §6.1](../gateway/runtime-contract.md#61-ánh-xạ-identity-header) | Chỉ `edge/traefik` chạm được route public | Tin trên route public; bỏ qua trên `/internal/**`, `/exports/**` |
| `X-Service-Identity` | Service caller, giá trị = ServiceAccount name của chính nó | **Không** xác minh giá trị; chỉ bảo đảm principal caller được phép chạm path | Log, audit, problem detail, allowlist defense-in-depth; không dùng làm bằng chứng authorization duy nhất |
| `X-Request-Id`, `traceparent`, `tracestate` | Caller/gateway | Waypoint giữ nguyên và tạo span con | Propagate tiếp |

Traefik xoá `X-Service-Identity` do client gửi cùng nhóm `X-User-*`
([gateway/runtime-contract.md §4](../gateway/runtime-contract.md#4-luồng-xử-lý-request)). Service
gọi internal API bắt buộc gửi `X-Service-Identity`; thiếu header là lỗi contract phía caller dù
mesh đã cho phép.

Review Service hiện dùng static bearer token (`REVIEW_INTERNAL_*_TOKENS`) thay vì
`X-Service-Identity`; đó cũng là defense-in-depth với cùng địa vị, và là ứng viên hợp nhất về
header chung (mục 11).

## 7. Traffic policy east-west

Mesh-wide `defaultHttpRetryPolicy.attempts: 0` (nếu bản đã pin hỗ trợ; nếu không, mọi
`VirtualService` phải khai báo `attempts` tường minh). Không route nào dựa vào retry mặc định
của Istio.

| Route đích | Retry mesh | Timeout mesh | Ghi chú |
|---|---|---|---|
| `GET /internal/v1/products/*/snapshot`, `GET /internal/v1/product-details/*` | 2 lần, `connect-failure,refused-stream` | 3 giây | Idempotent read. PDP BFF cắt `product-details` ở `min(400 ms, deadline còn lại − 50 ms)` và không retry ở ứng dụng ([Product Detail BFF ADR-004](../product-detail-bff/decisions/ADR-004-resilience-budget-and-mesh-split.md), [ADR-007](../product-detail-bff/decisions/ADR-007-hot-path-clarifications.md)); mesh là giới hạn ngoài |
| gRPC `ProductService/GetSku`, `GetBatchSkus` | 2 lần, `connect-failure,refused-stream,unavailable` | 2 giây | Proto ghi deadline 500 ms / 1 giây; mesh là giới hạn ngoài |
| `GET .../checkout-snapshot` (User) | 2 lần, `connect-failure,refused-stream` | 2 giây | Checkout budget 500 ms |
| `GET /internal/v1/carts/*/checkout-snapshot` | 2 lần, `connect-failure,refused-stream` | 1 giây | Pure Cart read; Checkout budget 500 ms |
| `POST /internal/inventory/v1/allocation-previews` | 0 | 4 giây | Advisory nhưng POST; Checkout budget 3 giây |
| `POST /internal/v1/promotion-discount-calculations` | 0 | 3 giây | Pure query nhưng POST; Checkout budget 2 giây |
| `POST /internal/v1/promotion-allocation/{reservations,cancellations,confirmations}` | 0 | 10 giây | Order Temporal activity sở hữu retry |
| `POST /internal/v1/promotion-allocation/technical-cancellations` | 0 | 10 giây | Legacy migrator only; proof verification before mutation |
| `POST /internal/inventory/v1/reservations/**`, `/refund-scopes/**` | 0 | 10 giây | Order Temporal activity sở hữu retry |
| `POST /internal/v1/checkout-order-submissions`, `POST /v1/payments/initiate`, `POST /v1/payments/*/cancel`, `POST /v1/payments/*/refund` | 0 | 15 giây | Caller sở hữu retry/recovery bằng stable idempotency key; mesh không retry. Checkout query Order status sau timeout thay vì nhân ba deadline. Webhook không nằm trong policy này |
| `/internal/v1/reviews/rating-aggregate-exports**`, `/internal/v1/products/export`, `/exports/inventory/v1/**` | 0 | 30 giây | Export session/page |
| `GET /internal/v1/reviews/products/*/pdp-summary`, `POST .../sku-availabilities:batch-get` | 2 lần / 0 | 2 giây | BFF cắt ở `min(300 ms, deadline còn lại − 50 ms)`, không retry ở ứng dụng, có circuit breaker riêng và degrade theo contract ([Product Detail BFF ADR-004](../product-detail-bff/decisions/ADR-004-resilience-budget-and-mesh-split.md)); mesh là giới hạn ngoài |
| `POST /internal/v1/auth/notification-deliveries/*/content` | 2 lần, `connect-failure,refused-stream` | 2 giây | POST nhưng idempotent theo `delivery_id` (Auth OpenAPI ghi rõ retry cùng `delivery_id` trả cùng nội dung); chỉ retry lỗi kết nối, không retry sau khi đã nhận response |
| Route public từ Traefik | 0 | Không đặt ở mesh | Traefik sở hữu ngân sách timeout và retry edge |

`DestinationRule` per service: `outlierDetection` (5 lỗi 5xx liên tiếp → eject 30 giây, tối đa
| `GET /internal/v1/auth/users/*/notification-contact` | 2 lần, `connect-failure,refused-stream` | 2 giây | Pure read; Notification cache 15 phút và có bulkhead theo lane |
| `GET /internal/v1/notifications/requests/*`, `GET /internal/v1/notifications/deliveries` | 2 lần, `connect-failure,refused-stream` | 3 giây | Pure read cho support tooling |
| `/internal/v1/notifications/ops/**` | 0 | 10 giây | Caller sở hữu retry bằng `Idempotency-Key`; mesh không retry |
50 % endpoint), `connectionPool` giới hạn theo load test, `loadBalancer: LEAST_REQUEST`. Giá trị
là chính sách khởi điểm, phải được load test xác nhận và ghi vào manifest version hóa.

## 8. Egress

`meshConfig.outboundTrafficPolicy.mode: REGISTRY_ONLY`. Mọi đích ngoài cluster được khai báo
bằng `ServiceEntry`: Stripe API (Payment), SMTP/email provider (Notification), nhà cung cấp
khác khi có. Đích trong `data` (ngoài mesh) là Kubernetes Service bình thường, không cần
`ServiceEntry`. Không có egress gateway ở đợt 1.

## 9. Quy ước khả năng quan sát

### Trace

`Telemetry` `default` trong `istio-system`: provider `opentelemetry` trỏ
`otel-collector.observability.svc:4317`, `randomSamplingPercentage` theo môi trường (100 % dev,
production theo quyết định chung với gateway). Envoy dùng W3C `traceparent`; waypoint tiếp tục
context đang có, không tạo trace ID riêng nếu request đã có `traceparent` hợp lệ. Ứng dụng
propagate `traceparent`/`tracestate` sang mọi outbound call và Kafka header; mesh không làm thay.

### Nhật ký

Waypoint access log JSON ra stdout với tối thiểu: `start_time`, `method`, `path`,
`response_code`, `response_flags`, `duration`, `source_principal`, `destination_service`,
`trace_id`, `x_request_id`. Không log Authorization, cookie, body, `X-User-*` giá trị. OTel
Collector thu qua `filelog` receiver và export sang Loki cùng pipeline với Traefik access log.

### Metrics và cảnh báo

Prometheus scrape waypoint và ztunnel qua port `15020` (`/stats/prometheus`) và istiod
(`15014`). Bắt buộc có: `istio_requests_total` theo `destination_service`, `response_code`,
`response_flags`, `source_principal`; `istio_request_duration_milliseconds`; `istio_tcp_*`
từ ztunnel; `pilot_xds_push_errors`, `citadel_server_root_cert_expiry_timestamp`. Alert có
owner/runbook cho: tỉ lệ `403` từ waypoint tăng đột biến (policy sai), `response_flags` `UF`/`UO`
(waypoint không tới được upstream / overflow), waypoint replica < 2, chứng chỉ sắp hết hạn,
istiod push error, ztunnel restart. Cardinality: không thêm label theo `request_id`, path đầy
đủ hay user.

## 10. Bảo mật và tiêu chí xác minh

- Không ServiceAccount `default` trong `edge`/`ecommerce`; không PERMISSIVE ngoài port 80/443
  của Traefik; không `EnvoyFilter` trong đợt 1.
- NetworkPolicy L3/L4 deny-by-default cho `ecommerce` độc lập với mesh.
- Manifest Istio (Helm values, `PeerAuthentication`, `AuthorizationPolicy`, `VirtualService`,
  `DestinationRule`, `ServiceEntry`, `Telemetry`) version hóa, validate trong CI bằng
  `istioctl analyze` và policy lint.

Release mesh chỉ hoàn thành khi: `istioctl ztunnel-config workload` cho thấy mọi pod `edge`/
`ecommerce`/`observability` ở mode HBONE; kết nối plaintext từ pod ngoài mesh vào `ecommerce`
bị từ chối; từng dòng mục 5.2 có test dương (principal đúng → `2xx`) và test âm (principal khác
→ `403 RBAC: access denied`); `X-Service-Identity` giả từ principal không được phép vẫn bị `403`;
`X-User-*` gửi từ principal không phải Traefik không tới được route public; gRPC từ Cart phân bố
đều trên ≥2 pod Product; route `attempts: 0` không bị retry khi upstream reset sau khi nhận
request; waypoint mất 1 replica không làm lỗi request; trace từ Traefik → waypoint → service →
waypoint → service liên kết một `trace_id` trên Jaeger; access log waypoint correlate được trên
Loki; và load test ghi nhận p95/p99 thêm do waypoint, CPU/RAM ztunnel và waypoint.

## 11. Khoảng cách với trạng thái hiện tại

Repository chưa có manifest Kubernetes cho bất kỳ service nào, chưa có ServiceAccount riêng,
chưa có Helm values Istio; `<pinned-version>` chưa chốt vì chưa có cluster để xác minh ma trận
tương thích. docker-compose không có mesh nên `X-Service-Identity` và `X-User-*` vẫn spoof
được trong môi trường local — tài liệu từng service đã ghi nhận điều này và không được coi
Compose là bằng chứng bảo mật. Gateway route matrix vẫn còn prefix legacy
`/api/users/**` (route `/api/payments/**` ngoài webhook đã bỏ 2026-09-19); Order public routes đã được đối soát theo OpenAPI
v1; Product Detail BFF đã có dòng `/api/v1/storefront/products/**`. Cart và Inventory admin chưa có đủ dòng
trong gateway matrix — mục 5.2 dùng contract, gateway matrix phải được đối soát. Review Service
dùng static bearer token cho `/internal/v1/**` thay vì `X-Service-Identity`; hợp nhất là việc
riêng, không chặn mesh. Kế hoạch triển khai chuyển các khoảng cách này thành công việc; một
năng lực không được gọi là "hoàn thành" trước khi tiêu chí xác minh của nó đạt.
