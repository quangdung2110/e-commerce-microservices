# ADR-001: Manifest mesh được sinh từ runtime contract, chốt Istio 1.24.2

- Status: Accepted
- Date: 2026-10-16

## Context

Mesh là "target/proposed": [runtime contract](../runtime-contract.md) §11 ghi rằng repository không có manifest Kubernetes, ServiceAccount riêng,
`AuthorizationPolicy`, `VirtualService`, `DestinationRule`, NetworkPolicy hay Helm values, và `<pinned-version>` chưa chốt. Hệ quả: mTLS, ma trận phân quyền
(§5), retry/timeout (§7) và cân bằng tải gRPC chỉ tồn tại trên giấy; `X-Service-Identity` giả được và mọi service tới được nhau.

Không có cluster để áp dụng. Việc có thể làm ngay là viết manifest và chứng minh chúng khớp tài liệu, để ngày có cluster chỉ còn kiểm hành vi chứ không
phải dịch bảng Markdown thành YAML bằng tay (dễ sai, và sai ở chỗ không ai thấy cho tới khi `403` xuất hiện trên production).

## Decision

1. **Nguồn sự thật không đổi**: `docs/mesh/runtime-contract.md` (mục 5, 7, 8), `contracts/catalog/services.yaml`. Manifest nằm ở
   [`deploy/mesh/generated/`](../../../deploy/mesh/README.md), do `deploy/mesh/generate.py` sinh và được commit; không sửa tay. `generate.py --check` (CI) fail khi file lệch.
2. **Phần sinh từ bảng**: Namespace, ServiceAccount (`automountServiceAccountToken: false`), `PeerAuthentication` (STRICT toàn mesh, `PERMISSIVE` chỉ cổng `80`/`443` của
   Traefik), waypoint (`Gateway`, PDB, HPA ≥ 2 replica), `AuthorizationPolicy` L4 (§5.1, §5.3) và L7 (§5.2: mỗi dòng bảng là một rule, mỗi đích một policy gắn `targetRefs` vào Service),
   `ServiceEntry` (§8), `Telemetry`, NetworkPolicy nền.
3. **Phần có dữ liệu phụ** trong `deploy/mesh/`: `topology.yaml` (cổng, selector, egress, giá trị outlier — những thứ bảng không nêu), `traffic-policy.yaml` (các route của §7 dưới
   dạng biểu thức khớp; **con số** `attempts`/`timeout` không phải nguồn thứ hai: test đọc bảng §7 và fail khi lệch), `versions.yaml`, `helm/*.values.yaml`.
4. **Phiên bản.** Istio `1.24.2` (dòng đầu tiên ambient GA), Gateway API CRDs `v1.2.0`, Kubernetes 1.28–1.31. Digest image để `null` cho tới lần cài đầu tiên: không có cluster nên không
   xác minh được ma trận; `runtime-contract §2` ghi rõ điều đó và vẫn cấm `latest`.
5. **Dịch đường dẫn.** `AuthorizationPolicy` chỉ khớp exact, prefix (`/x/*`) và suffix (`*/x`), không khớp wildcard ở giữa. Quy tắc:
   - `/x/**` = `/x` **và** `/x/*` (Istio prefix `/x/*` không khớp `/x`; thiếu nó thì `POST /internal/inventory/v1/reservations` bị từ chối);
   - `x**` dính liền tên = prefix `x*`;
   - wildcard ở cuối = prefix, ở giữa = suffix sau wildcard cuối (`/internal/v1/carts/*/checkout-snapshot` → `*/checkout-snapshot`). Hai dạng này **rộng hơn** contract; mỗi rule rộng được liệt kê trong
     `generated/coverage-report.md` (20 trên 86 pattern). Giới hạn principal luôn chính xác; allowlist `X-Service-Identity` của ứng dụng ([contracts ADR-004](../../../contracts/decisions/ADR-004-service-identity-allowlist.md)) là kiểm tra chính xác phía sau;
   - rule của Order tới Payment có `notPaths: /v1/payments/webhook/*` để không bao trùm webhook công khai (§5.2).
6. **VirtualService.** Mỗi route nêu `attempts` tường minh (`0` tắt retry), `retryOn` chỉ gồm `connect-failure`, `refused-stream`, `unavailable`, `perTryTimeout = timeout / (attempts + 1)`; mỗi
   VirtualService kết thúc bằng route `default` không `match` để traffic public qua Traefik không bị 404. `DestinationRule` mỗi service: outlier detection (5 lỗi 5xx → loại 30 giây, tối đa 50%),
   `LEAST_REQUEST`, connection pool khởi điểm chờ load test.
7. **Sửa contract khi test chỉ ra chỗ thiếu/sai** (cùng commit):
   - §7 thiếu `GET /internal/v1/checkout-order-submissions/*` và hai route Finance Ops của Order dù §5.2 cho phép gọi: thêm hai dòng (`0` retry; 15 giây và 10 giây) và `categories/export` vào dòng export;
   - §5.2 ghi `POST/GET` cho hai path của submissions và `GET/POST` cho hai path của financial reconciliation, tức cấp quyền chéo (`GET` trên route chỉ có `POST`...): tách thành các dòng theo từng cặp path và method.
8. **Kiểm tra không cần cluster** (`deploy/mesh/validate.sh`, chạy được trên máy dev và CI): file sinh còn mới; 29 test đối chiếu với §5 và §7 (mỗi dòng §5.2 có test dương cho từng principal, test âm cho principal lạ và method không liệt kê;
   mỗi route §7 khớp VirtualService và có ít nhất một caller được §5.2 cho phép; mỗi grant nội bộ của §5.2 có một route §7); `kubeconform` với schema Kubernetes 1.31 và CRD Istio/Gateway API; `istioctl analyze`
   ngoại tuyến; `helm template` bốn chart Istio 1.24.2 với values của repository.
9. **Chưa làm** (nằm ngoài khả năng của repository hôm nay): Deployment/Service của 14 service ([implementation plan](../implementation_plan.md) 1.2), cài lên cluster và các tiêu chí §10 (HBONE, `403 RBAC` thật, retry, phân bố gRPC, trace, waypoint mất replica).

## Alternatives considered

- **Viết YAML bằng tay.** Không chọn: 42 rule (86 pattern), 24 route, 26 ServiceAccount; thêm một dòng vào bảng mà quên YAML là cách sinh lỗi im lặng.
- **Đưa bảng ra YAML làm nguồn, sinh Markdown ngược lại.** Không chọn: quy ước dự án đặt tài liệu làm nguồn chân lý và ưu tiên hơn manifest; đảo chiều đổi quy ước đó.
- **Helm chart của chính dự án.** Không chọn: manifest mesh phẳng, ít tham số; chart thêm tầng template cho dữ liệu vốn tĩnh. Các chart Istio chính thức dùng cho cài đặt.
- **Một AuthorizationPolicy cho mọi rule.** Không chọn: mỗi đích một policy dễ review và dễ rollback từng service ([implementation plan](../implementation_plan.md) bước 5).
- **Regex trong AuthorizationPolicy.** Không có trong Istio; `VirtualService` thì có, nên route dùng regex còn policy chấp nhận pattern rộng hơn.

## Consequences

- Có manifest để review và để cài; thêm một dòng vào §5.2/§7 mà không chạy lại `generate.py` làm CI đỏ.
- Policy mesh rộng hơn contract ở 20 pattern (liệt kê); lớp allowlist của ứng dụng giữ nguyên vai trò kiểm chính xác.
- **Rủi ro mở**: nhiều giả định về hành vi Istio ambient chưa kiểm trên cluster, đáng chú ý nhất: (a) ztunnel có thực thi `REGISTRY_ONLY` ở ambient hay không; (b) `AuthorizationPolicy` mức namespace
  (L4) cùng `targetRefs` Service/Gateway (L7) kết hợp đúng như dự kiến; (c) NetworkPolicy nền không chặn HBONE/ztunnel với CNI cụ thể. Mỗi cái là một dòng trong tiêu chí xác minh §10 và nên chạy ở chế độ quan sát trước ([implementation plan](../implementation_plan.md) giai đoạn 3).
- Triển khai chưa bật waypoint cần Service headless cho cổng gRPC của Product ([contracts ADR-007](../../../contracts/decisions/ADR-007-grpc-channel-balancing-and-keepalive.md)).

## Verification

- `deploy/mesh/validate.sh`: file sinh còn mới; 29 test xanh; `kubeconform` 89/89 resource hợp lệ; `istioctl analyze` sạch; bốn chart `helm template` thành công với Istio 1.24.2.
- Lỗi bắt được trong lúc làm, đã sửa: base path của `/x/**` (xem 5); quyền chéo method×path (7); route thiếu ở §7 (7).
- Chưa áp dụng lên cluster nào.
