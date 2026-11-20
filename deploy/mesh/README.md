# Mesh manifests (Istio ambient)

Manifest Kubernetes và Istio của [service mesh](../../docs/mesh/runtime-contract.md), sinh từ chính tài liệu đó
([mesh ADR-001](../../docs/mesh/decisions/ADR-001-manifests-generated-from-runtime-contract.md)).

> **Trạng thái:** đã triển khai local trên cluster `kind-ecommerce-mesh` để khắc phục giả danh caller bằng header.
> Xem [ADR-005](../../docs/mesh/decisions/ADR-005-authenticated-workload-authorization.md) và
> [bằng chứng runtime](../../docs/security/mesh-p1-evidence-2026-11-04/results.json).
> Các tiêu chí production, load test, retry và tracing vẫn cần xác minh riêng.

| Đường dẫn | Nội dung | Sửa tay? |
|---|---|---|
| `generated/*.yaml` | Namespace, ServiceAccount, `PeerAuthentication`, waypoint, `AuthorizationPolicy`, `VirtualService`, `DestinationRule`, `ServiceEntry`, `Telemetry`, NetworkPolicy | Không — chạy `python deploy/mesh/generate.py` |
| `generated/coverage-report.md` | Mỗi dòng §5.2 → pattern Istio, kèm rule nào rộng hơn contract | Không |
| `topology.yaml` | Cổng, selector, egress, giá trị outlier — những thứ bảng không nêu | Có |
| `traffic-policy.yaml` | Route của §7 dạng biểu thức khớp; `attempts`/`timeout` được test đối chiếu với bảng | Có |
| `versions.yaml` | Istio 1.31.3, Gateway API 1.6.0, digest image và checksum release archive | Có |
| `kind.yaml`, `local.py` | Cluster local riêng; bootstrap/install/stack/validate bằng kubeconfig riêng | Có |
| `verify_live.py`, `tests/probe*` | HTTP/gRPC thật, giả header, Pod IP, plaintext, metrics, health, RBAC | Có |
| `helm/*.values.yaml` | Values cho bốn chart Istio (`base`, `istiod`, `cni`, `ztunnel`) | Có |

## Đổi một quy tắc

1. Sửa bảng ở `docs/mesh/runtime-contract.md` (§5.2 cho ai được gọi gì, §7 cho retry/timeout, §8 cho egress), và `traffic-policy.yaml` nếu thêm route.
2. `python deploy/mesh/generate.py`
3. `bash deploy/mesh/validate.sh` (cần Python với PyYAML và Docker)

`validate.sh` kiểm: file sinh còn mới; test đối chiếu với runtime contract (mỗi dòng §5.2 có test dương/âm, mỗi route §7 có caller hợp lệ, mỗi grant có route);
`kubeconform`; `istioctl analyze` ngoại tuyến; `helm template` bốn chart.

## Cài đặt local trên Windows

Cần Docker Linux engine đang chạy, kubectl, Python + PyYAML và image ứng dụng được build từ mã nguồn hiện tại.
Ví dụ build bằng `docker compose --profile payment --profile notification build`. Script không tự sửa context
Kubernetes mặc định và chỉ cho phép thao tác context `kind-ecommerce-mesh`, kubeconfig `.tools/mesh/kubeconfig`.

```powershell
py deploy/mesh/local.py bootstrap
py deploy/mesh/local.py create
py deploy/mesh/local.py install
py deploy/mesh/local.py stack
py deploy/mesh/local.py validate
py deploy/mesh/verify_live.py
```

`bootstrap` tải kind/Helm/release Istio chính thức và kiểm tra SHA-256. Chart 1.31.3 lấy từ release archive
đã pin vì index Helm repository chưa có bản patch này lúc kiểm chứng. `stack` nạp image có sẵn vào kind,
giữ nhãn enrollment khi apply namespace và tự rollout image local khi image ID thay đổi. Không dùng trực tiếp
`deploy/k8s/apply.sh services` trên cluster mesh: lớp đó dành cho môi trường không có mesh.

Test dùng image ứng dụng thật, không tạo thanh toán/hoàn tiền/giữ hàng. Caller test có ServiceAccount thật,
token không mount; Checkout test chạy trên node khác backend. Kết quả được ghi vào thư mục evidence ở trên.
`--reuse-probes` chạy lại bộ test với các Pod test đang có. Một phản hồi domain NOT_FOUND được dùng cho các
query ID kỹ thuật không tồn tại; không coi 400/503 là positive test thành công.

Ứng dụng gọi bằng Service DNS. Pod IP bị từ chối kể cả khi principal có quyền gọi Service; đây là hành vi
fail-closed có chủ ý. Prometheus chỉ có GET endpoint metrics qua waypoint. Mailpit/LocalStack local được
Notification gọi qua mTLS và policy L4 giới hạn đúng đích/cổng; không có ngoại lệ tương tự cho business listener.

Để xem cluster mà không đổi context hiện tại:

```powershell
kubectl --kubeconfig .tools/mesh/kubeconfig get pods -A
```

Docker Compose vẫn là runtime không có mesh. Không áp kết luận test local cho production hoặc Compose.

## Kiểm chứng egress local

Chạy `py deploy/mesh/local.py install` để đồng bộ waypoint, ServiceEntry và NetworkPolicy. Sau khi
`py deploy/mesh/verify_live.py` đã tạo các pod kiểm thử, chạy:

```powershell
py deploy/mesh/verify_egress.py --test-waypoint-outage
```

Phép thử dùng TLS handshake với xác minh chứng chỉ và đối chiếu bộ đếm của waypoint. Kết quả nằm ở
`.tools/mesh/p2-egress-verification/egress-results.json`. Cờ `--test-waypoint-outage` tạm scale waypoint
egress về 0 rồi khôi phục replica và kiểm tra lại Stripe. Pod plaintext ở `ecommerce` kiểm đường egress.
Pod plaintext ở `edge` kiểm lớp mTLS đích độc lập với policy egress của `ecommerce`.

## Cài đặt trên cluster khác

Thứ tự: Gateway API CRDs (`versions.yaml`) → `helm/base` → `helm/istiod` → `helm/cni` → `helm/ztunnel` → `generated/00-…` đến `80-…`. Đưa namespace vào ambient
theo thứ tự `observability`, `edge`, `ecommerce`, và chạy policy ở chế độ quan sát trước khi enforce ([implementation plan](../../docs/mesh/implementation_plan.md)).
Digest đã được pin trong `versions.yaml` và Helm values. Deployment và Service của các service nằm ở
[deploy/k8s](../k8s/README.md) (sinh từ docker-compose, cùng tên ServiceAccount, nhãn `app.kubernetes.io/name` và cổng của
`topology.yaml`). Cần tích hợp cùng policy và kiểm chứng lại trên cluster đích; script local từ chối context khác.
