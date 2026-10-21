# Mesh manifests (Istio ambient)

Manifest Kubernetes và Istio của [service mesh](../../docs/mesh/runtime-contract.md), sinh từ chính tài liệu đó
([mesh ADR-001](../../docs/mesh/decisions/ADR-001-manifests-generated-from-runtime-contract.md)).

> **Trạng thái:** đã qua kiểm tra không cần cluster; **chưa được áp dụng lên cluster nào**. Các tiêu chí xác minh của
> [runtime contract §10](../../docs/mesh/runtime-contract.md#10-bảo-mật-và-tiêu-chí-xác-minh) chưa đạt.

| Đường dẫn | Nội dung | Sửa tay? |
|---|---|---|
| `generated/*.yaml` | Namespace, ServiceAccount, `PeerAuthentication`, waypoint, `AuthorizationPolicy`, `VirtualService`, `DestinationRule`, `ServiceEntry`, `Telemetry`, NetworkPolicy | Không — chạy `python deploy/mesh/generate.py` |
| `generated/coverage-report.md` | Mỗi dòng §5.2 → pattern Istio, kèm rule nào rộng hơn contract | Không |
| `topology.yaml` | Cổng, selector, egress, giá trị outlier — những thứ bảng không nêu | Có |
| `traffic-policy.yaml` | Route của §7 dạng biểu thức khớp; `attempts`/`timeout` được test đối chiếu với bảng | Có |
| `versions.yaml` | Istio 1.24.2, Gateway API, digest (chưa điền) | Có |
| `helm/*.values.yaml` | Values cho bốn chart Istio (`base`, `istiod`, `cni`, `ztunnel`) | Có |

## Đổi một quy tắc

1. Sửa bảng ở `docs/mesh/runtime-contract.md` (§5.2 cho ai được gọi gì, §7 cho retry/timeout, §8 cho egress), và `traffic-policy.yaml` nếu thêm route.
2. `python deploy/mesh/generate.py`
3. `bash deploy/mesh/validate.sh` (cần Python với PyYAML và Docker)

`validate.sh` kiểm: file sinh còn mới; test đối chiếu với runtime contract (mỗi dòng §5.2 có test dương/âm, mỗi route §7 có caller hợp lệ, mỗi grant có route);
`kubeconform`; `istioctl analyze` ngoại tuyến; `helm template` bốn chart.

## Cài đặt (khi có cluster)

Thứ tự: Gateway API CRDs (`versions.yaml`) → `helm/base` → `helm/istiod` → `helm/cni` → `helm/ztunnel` → `generated/00-…` đến `80-…`. Đưa namespace vào ambient
theo thứ tự `observability`, `edge`, `ecommerce`, và chạy policy ở chế độ quan sát trước khi enforce ([implementation plan](../../docs/mesh/implementation_plan.md)).
Điền digest vào `versions.yaml` ở lần cài đầu tiên. Deployment và Service của các service nằm ở
[deploy/k8s](../k8s/README.md) (sinh từ docker-compose, cùng tên ServiceAccount, nhãn `app.kubernetes.io/name` và cổng của
`topology.yaml`); chúng chưa chạy cùng mesh.
