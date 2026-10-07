# Kế hoạch triển khai Service Mesh

> **Trạng thái:** Đã lập kế hoạch. Một năng lực chỉ hoàn thành khi tiêu chí nghiệm thu của nó đạt.
> **Nguồn chân lý cho hành vi:** [runtime-contract.md](runtime-contract.md).

## Quy tắc thực hiện

- Thay đổi tài liệu, thay đổi manifest Kubernetes và thay đổi policy mesh là ba loại thay đổi
  review riêng.
- Không có manifest Kubernetes nào trong repository hiện tại; giai đoạn 1 tạo baseline trước khi
  bất kỳ tài nguyên Istio nào được áp dụng.
- Docker Compose không phải môi trường xác minh mesh. Xác minh chạy trên kind/k3d (CI và máy
  dev qua Docker) và trên cluster tích hợp đại diện.
- Policy luôn đi từ audit/quan sát sang enforce; không bao giờ enforce một `AuthorizationPolicy`
  chưa có test dương và âm.

## Giai đoạn 0 — Tài liệu nền tảng

| ID | Công việc | Acceptance criteria | Trạng thái |
|---|---|---|---|
| 0.1 | Công bố technology choice, design, runtime contract | Quyết định edge, mode, caller identity, phạm vi, traffic policy, telemetry | Hoàn thành |
| 0.2 | Đồng bộ gateway docs, service docs và contract với mô hình "mesh là authority" | Không câu nào còn nói header identity do mesh đặt/xác minh; Gateway xoá `X-Service-Identity` | Hoàn thành |
| 0.3 | Chốt `<pinned-version>` Istio và ma trận Kubernetes/CNI | Version ghi vào runtime contract §2 kèm digest | Đã lập kế hoạch |

## Giai đoạn 1 — Baseline Kubernetes

| ID | Công việc | Acceptance criteria | Trạng thái |
|---|---|---|---|
| 1.1 | Namespace `edge`, `ecommerce`, `observability`, `data`; NetworkPolicy deny-by-default | Pod ngoài namespace không tới được `ecommerce` khi chưa có mesh | Đã lập kế hoạch |
| 1.2 | Manifest/Helm cho từng service có runtime (4 Go, 5 Java) với ServiceAccount riêng, Service port có `name`/`appProtocol` | `kubectl get sa` khớp `services.yaml`; không pod nào dùng SA `default`; port gRPC Product có `appProtocol: grpc` | Đã lập kế hoạch |
| 1.3 | Triển khai tầng `data` và `observability` trên cluster | Service khởi động, health check đạt, OTel Collector/Prometheus/Loki nhận dữ liệu như Compose | Đã lập kế hoạch |
| 1.4 | Traefik trên Kubernetes bằng Gateway API (`GatewayClass` Traefik, `HTTPRoute` theo gateway route matrix) — trùng mục 6.2 của [gateway/implementation_plan.md](../gateway/implementation_plan.md) | Route matrix chạy trên cluster không mesh | Đã lập kế hoạch |
| 1.5 | Đối soát prefix route public giữa gateway matrix và contract (User, Order, Payment, Checkout; PDP BFF đã đối soát `/api/v1/storefront/products/**`, Inventory admin đã đối soát `/api/v1/admin/inventory/**`, Cart đã đối soát `/api/v1/carts/mine` và `/api/v1/carts/mine/items`) | Gateway matrix và mesh matrix §5.2 dùng cùng path | Đã lập kế hoạch |

## Giai đoạn 2 — Cài Istio ambient

| ID | Công việc | Acceptance criteria | Trạng thái |
|---|---|---|---|
| 2.1 | Helm values version hóa cho `base`, `istiod`, `cni`, `ztunnel`; profile ambient; `outboundTrafficPolicy: REGISTRY_ONLY`; `defaultHttpRetryPolicy.attempts: 0` | `istioctl analyze` sạch; `istioctl version` khớp pin | Đã lập kế hoạch |
| 2.2 | Gắn `istio.io/dataplane-mode=ambient` cho `observability`, rồi `edge`, rồi `ecommerce` | `istioctl ztunnel-config workload` hiển thị HBONE; ứng dụng không restart, không lỗi | Đã lập kế hoạch |
| 2.3 | `PeerAuthentication` STRICT toàn mesh + PERMISSIVE port 80/443 Traefik | Traffic công khai vào Traefik vẫn chạy; plaintext từ pod ngoài mesh vào `ecommerce` bị từ chối; Prometheus vẫn scrape được | Đã lập kế hoạch |
| 2.4 | `ServiceEntry` cho Stripe và email provider | Egress không khai báo bị chặn; Stripe/email hoạt động | Đã lập kế hoạch |

## Giai đoạn 3 — Phân quyền L4

| ID | Công việc | Acceptance criteria | Trạng thái |
|---|---|---|---|
| 3.1 | Default-deny per namespace ở chế độ quan sát (log ztunnel) | Không lưu lượng hợp lệ nào xuất hiện là "would deny" | Đã lập kế hoạch |
| 3.2 | Policy L4 `edge` (§5.1) và `observability` (§5.3) | `gateway-auth` chỉ nhận từ Traefik; scrape chỉ từ Prometheus | Đã lập kế hoạch |
| 3.3 | Enforce default-deny | Test âm từ pod trong `data` và pod SA lạ → từ chối | Đã lập kế hoạch |

## Giai đoạn 4 — Waypoint và phân quyền L7

| ID | Công việc | Acceptance criteria | Trạng thái |
|---|---|---|---|
| 4.1 | Deploy `ecommerce-waypoint` ≥2 replica, PDB, HPA; gắn `istio.io/use-waypoint` | Traffic vào `ecommerce` đi qua waypoint (access log có `source_principal`) | Đã lập kế hoạch |
| 4.2 | `AuthorizationPolicy` L7 cho từng dòng §5.2, mỗi dòng một test dương và một test âm | Test suite policy xanh; `403 RBAC: access denied` cho principal sai | Đã lập kế hoạch |
| 4.3 | Test spoof: `X-User-*` từ principal không phải Traefik, `X-Service-Identity` giả từ principal không được phép | Cả hai bị `403` trước khi tới ứng dụng | Đã lập kế hoạch |
| 4.4 | gRPC Cart → Product qua waypoint | Request phân bố đều trên ≥2 pod Product; `AuthorizationPolicy` theo `/ecommerce.product.v1.ProductService/*` | Đã lập kế hoạch |
| 4.5 | Mất 1 replica waypoint khi đang tải | Không request lỗi; alert replica < 2 bắn | Đã lập kế hoạch |

## Giai đoạn 5 — Traffic policy

| ID | Công việc | Acceptance criteria | Trạng thái |
|---|---|---|---|
| 5.1 | `VirtualService` retry/timeout theo bảng §7 | Route `attempts: 0` không retry khi upstream reset sau khi nhận request; route idempotent retry đúng điều kiện | Đã lập kế hoạch |
| 5.2 | `DestinationRule` outlier detection/connection pool | Pod trả 5xx liên tục bị eject và phục hồi theo ngưỡng | Đã lập kế hoạch |
| 5.3 | Load test có/không waypoint | p95/p99 thêm, CPU/RAM ztunnel/waypoint được ghi nhận và duyệt | Đã lập kế hoạch |

## Giai đoạn 6 — Khả năng quan sát

| ID | Công việc | Acceptance criteria | Trạng thái |
|---|---|---|---|
| 6.1 | `Telemetry` API → OTel Collector, W3C propagation | Một request sinh trace Traefik → waypoint → service → waypoint → service liên kết | Đã lập kế hoạch |
| 6.2 | Waypoint access log JSON → Collector `filelog` → Loki | Log correlate với trace qua `trace_id`; không giá trị nhạy cảm | Đã lập kế hoạch |
| 6.3 | Prometheus scrape waypoint/ztunnel/istiod; dashboards, alerts, runbooks §9 | Controlled alert tests nêu owner/action | Đã lập kế hoạch |
| 6.4 | Truy vấn Loki đối chiếu `X-Service-Identity` (log ứng dụng) với `source_principal` (log waypoint) | Runbook audit header giả có bước chạy được | Đã lập kế hoạch |

## Giai đoạn 7 — Vận hành

| ID | Công việc | Acceptance criteria | Trạng thái |
|---|---|---|---|
| 7.1 | CI: `istioctl analyze`, policy lint, test policy trên kind | Regression policy bị chặn khi merge | Đã lập kế hoạch |
| 7.2 | Nâng cấp Istio canary (revision) cho istiod/ztunnel/waypoint | Nâng cấp không restart pod ứng dụng; rollback được | Đã lập kế hoạch |
| 7.3 | Runbook: policy sai, waypoint down, chứng chỉ hết hạn, istiod down | Operator xử lý không cần hạ PERMISSIVE | Đã lập kế hoạch |
| 7.4 | Đánh giá đợt 2: đưa `data` vào ambient L4; hợp nhất Review static token về `X-Service-Identity` | Quyết định ghi thành ADR riêng | Đã lập kế hoạch |

## Kiểm thử end-to-end bắt buộc

mTLS: HBONE trên mọi pod trong phạm vi, plaintext từ ngoài mesh bị từ chối, PERMISSIVE chỉ ở
80/443 Traefik. Phân quyền: mỗi dòng §5.2 dương/âm, spoof `X-User-*` và `X-Service-Identity`,
path không trong bảng bị từ chối, policy sai rollback được. Traffic: retry/timeout từng route,
outlier eject/recover, gRPC LB, waypoint mất replica. Telemetry: trace liên kết, log correlate,
redaction, alert bắn đúng. Nâng cấp: canary revision không restart ứng dụng. Performance:
baseline không mesh, có ztunnel, có waypoint.

## Migration và rollback

1. Baseline Kubernetes không mesh chạy đủ route và telemetry (giai đoạn 1).
2. Bật ambient cho `observability` trước để xác nhận scrape/OTLP, rồi `edge`, rồi `ecommerce`.
3. STRICT toàn mesh; xác nhận Traefik public port và Prometheus.
4. Default-deny ở chế độ quan sát, thêm policy L4, enforce.
5. Waypoint + policy L7 từng service, bắt đầu từ service không nằm trên đường checkout
   (Search, Review, Product Detail BFF), kết thúc bằng Inventory/Promotion/Order/Payment.
6. Traffic policy và telemetry trước production rollout.

Rollback theo từng bước ngược lại bằng manifest version hóa: gỡ policy L7 → gỡ waypoint label →
gỡ nhãn ambient của namespace. Không bao giờ rollback bằng cách đặt PERMISSIVE cho `ecommerce`
hay xoá default-deny mà giữ nguyên các thứ khác.

## Tiêu chí hoàn thành

Mesh hoàn thành khi mọi verification gate trong [runtime contract §10](runtime-contract.md#10-bảo-mật-và-tiêu-chí-xác-minh)
đạt, CI enforce policy test, operations sở hữu alert/runbook, và mọi tài liệu service/contract
mô tả internal API đúng theo mô hình "mesh là authority, header là attribution".
