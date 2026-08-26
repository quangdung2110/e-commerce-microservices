# Kế hoạch triển khai refactor API Gateway

> **Trạng thái:** Đã lập kế hoạch. Một năng lực chỉ hoàn thành khi các tiêu chí nghiệm thu của nó đạt.
> **Nguồn chân lý cho hành vi:** [runtime-contract.md](runtime-contract.md).

## Quy tắc thực hiện

- Thay đổi tài liệu và thay đổi runtime là các thay đổi riêng biệt để review.
- Cấu hình hiện tại là cấu hình cũ, không phải bằng chứng hoàn thành.
- Production configuration được pin, validate trong CI và test ở integration environment đại diện.
- Build thành công không bao giờ tự chứng minh định tuyến, xác thực, giới hạn hoặc telemetry.

## Giai đoạn 0 — Hoàn thiện tài liệu nền tảng

| ID | Công việc | Acceptance criteria | Trạng thái |
|---|---|---|---|
| 0.1 | Công bố runtime contract | Bao gồm routing, auth, rate policy, observability, failure policy, verification | Hoàn thành |
| 0.2 | Đồng bộ tài liệu kiến trúc và quyết định kỹ thuật | Không nội dung nào mâu thuẫn runtime contract | Hoàn thành |
| 0.3 | Thay checklist cũ | Mọi task còn lại có kết quả quan sát được | Hoàn thành |

## Giai đoạn 1 — Traefik nền tảng hợp lệ

| ID | Công việc | Acceptance criteria | Trạng thái |
|---|---|---|---|
| 1.1 | Pin tag/digest Traefik v3.7.1 | Image đang chạy khớp manifest; không có `latest` | Đã lập kế hoạch |
| 1.2 | Định nghĩa entrypoint public/private | HTTPS hoạt động; metrics/dashboard là private | Đã lập kế hoạch |
| 1.3 | Cấu hình TLS, redirect, trusted proxy CIDRs | TLS hoạt động, IP forwarded giả không đổi rate bucket | Đã lập kế hoạch |
| 1.4 | Thêm ping/readiness, runtime least-privilege, secret handling | Orchestrator checks và security review đạt | Đã lập kế hoạch |

## Giai đoạn 2 — Định tuyến và bảo vệ upstream

| ID | Công việc | Acceptance criteria | Trạng thái |
|---|---|---|---|
| 2.1 | Triển khai route matrix | Mọi route khai báo chỉ đến target; path không xác định trả 404 | Đã lập kế hoạch |
| 2.2 | Chuẩn hóa tên middleware khác provider | Docker references dùng `@file`; config validation đạt | Đã lập kế hoạch |
| 2.3 | Thêm health checks, timeouts, idempotent retry policy | Upstream unhealthy/chậm bị giới hạn; POST không retry | Đã lập kế hoạch |
| 2.4 | Cấu hình/test circuit breakers | Circuit mở và phục hồi ở threshold đã ghi nhận | Đã lập kế hoạch |
| 2.5 | Thêm product public/private priority, Stripe, WebSocket routes | Exceptional behavior bắt buộc đạt integration tests | Đã lập kế hoạch |

## Giai đoạn 3 — Tái cấu trúc xác thực

| ID | Công việc | Acceptance criteria | Trạng thái |
|---|---|---|---|
| 3.1 | Định nghĩa và deploy `gateway-auth` | ForwardAuth protocol, health check, headers, timeouts được test | Đã lập kế hoạch |
| 3.2 | Triển khai JWT/JWKS validation | Issuer/audience/algorithm/expiry/not-before không hợp lệ bị reject | Đã lập kế hoạch |
| 3.3 | Triển khai JWKS cache và Redis revocation checks | Rotation, denylisted token, force logout hoạt động | Đã lập kế hoạch |
| 3.4 | Enforce dependency failure và header-sanitization policy | Dependency outage là 503; client `X-User-*` injection không tới service | Đã lập kế hoạch |
| 3.5 | Xóa `.so` plugin runtime/build paths cũ | Không active deployment nào tham chiếu chúng | Đã lập kế hoạch |

## Giai đoạn 4 — Bảo vệ phân tán

| ID | Công việc | Acceptance criteria | Trạng thái |
|---|---|---|---|
| 4.1 | Cấu hình Redis-backed rate state | Hai gateway replicas dùng chung quota | Đã lập kế hoạch |
| 4.2 | Áp dụng contract profiles, body limits, concurrency controls | 429/size/concurrency behavior đúng trên từng route | Đã lập kế hoạch |
| 4.3 | Thêm post-auth identity/API-key/tenant quotas | Hai IP cho cùng identity dùng chung quota | Đã lập kế hoạch |
| 4.4 | Test Redis outage behavior, tune bằng load tests | Failure policy được duyệt và có kết quả ghi nhận | Đã lập kế hoạch |

## Giai đoạn 5 — Khả năng quan sát

| ID | Công việc | Acceptance criteria | Trạng thái |
|---|---|---|---|
| 5.1 | Deploy OTel Collector, bật Traefik OTLP | `traceparent` hợp lệ được tiếp tục; không có context thì tạo trace | Đã lập kế hoạch |
| 5.2 | Instrument auth và downstream services | Một request sinh trace gateway → auth → service liên kết | Đã lập kế hoạch |
| 5.3 | Cấu hình JSON logs/redaction và private metrics | Required fields có mặt; sensitive values vắng mặt | Đã lập kế hoạch |
| 5.4 | Tạo dashboards, alerts, runbooks | Controlled alert tests nêu owner/action | Đã lập kế hoạch |

## Giai đoạn 6 — Vận hành triển khai

| ID | Công việc | Acceptance criteria | Trạng thái |
|---|---|---|---|
| 6.1 | Local Compose integration stack | Full route/auth/telemetry suite chạy local | Đã lập kế hoạch |
| 6.2 | Kubernetes manifests/Helm values | Dùng Gateway API/CRDs và least privilege | Đã lập kế hoạch |
| 6.3 | CI validation/security/load checks | Regression bị chặn khi merge | Đã lập kế hoạch |
| 6.4 | Canary, rollback, SLO documentation | Operator xử lý không cần khôi phục auth fail-open | Đã lập kế hoạch |

## Kiểm thử end-to-end bắt buộc

Routing: mọi matrix route, sai path/method, priority collision, unhealthy upstream. TLS/network: redirect, TLS handshake, forwarded-header spoofing, private operations endpoints. Authentication: missing/malformed/expired/revoked/wrong issuer/audience token, JWKS rotation, outage, header spoofing. Stripe: raw body/signature, JWT bypass, no retry. Limits: threshold, IP/identity dimensions, two replicas, Redis outage. Observability: W3C propagation, log correlation, redaction, connected trace. Resilience: timeout, retry eligibility, circuit recovery, restart. Performance: baseline, auth overhead, steady/spike load, p95/p99, memory và CPU.

## Migration và rollback

1. Đưa Traefik baseline hợp lệ vào mà không đổi public behavior.
2. Migrate một protected route không phải payment sang `gateway-auth`; verify đầy đủ.
3. Migrate protected routes còn lại và xóa legacy plugin path.
4. Bật distributed rate policy ở observed/shadow mode khi phù hợp, sau đó enforce.
5. Bật telemetry và alerts trước production rollout.
6. Migrate Stripe riêng với raw-body/signature tests.

Rollback khôi phục versioned manifest trước đó; không bao giờ khôi phục authentication fail-open configuration. Nếu `gateway-auth` unhealthy, protected routes trả `503`.

## Tiêu chí hoàn thành

Refactor hoàn thành khi mọi verification gate trong [runtime contract](runtime-contract.md) đạt, CI enforce các checks khả thi, operations sở hữu alerts/runbooks và active legacy plugin path bị xóa.
