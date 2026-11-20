# CI/CD đợt 3: canary, hiệu năng, phục hồi và SLO

Triển khai mã nguồn ngày 23/10/2026. Đợt này tiếp tục theo phương án bootstrap vì chưa có
hạ tầng đích. Có công cụ và workflow thực thi, kiểm thử local, cấu hình bị khóa khi chưa
đủ đầu vào. Chưa chạy canary, load test hoặc diễn tập DR trên production.
Kết quả: [phase-3-verification.md](phase-3-verification.md).

## Các phần đã triển khai

| Thành phần | Hành vi |
|---|---|
| `scripts/cd/progressive.py` | Render Rollout BFF, AnalysisTemplate, PodMonitor và PrometheusRule theo profile đã đóng băng |
| `scripts/cd/slo.py` | Đánh giá cửa sổ SLO 30 ngày; phân biệt đạt, vi phạm và thiếu dữ liệu |
| `scripts/ops/performance.py` | k6 qua gateway staging, giới hạn tải và so sánh baseline đã review |
| `scripts/ops/recovery.py` | Backup → mất database fixture → restore → kiểm tra fingerprint, FK và RPO/RTO |
| `scripts/ops/bootstrap.py` | Tải manifest Argo Rollouts đúng checksum, thay image bằng digest, xuất để platform review |
| `.github/workflows/operations.yml` | Lịch kiểm tra và chạy thủ công, mặc định chưa bật |
| `deploy/operations/policy.example.json` | Chính sách hiệu năng và mục tiêu diễn tập, chưa có baseline giả |
| `deploy/operations/controllers.lock.json` | Argo Rollouts 1.10.1, checksum manifest và digest image cố định |

GitOps validator kiểm tra thêm loại workload, policy phân tích, query, scrape/revision labels
và SLO rule. Observer chờ Rollout Healthy, đủ replica, stableRS trùng currentPodHash, không
abort/pause, đồng thời vẫn yêu cầu đúng image/state và PostSync thành công.
`workload_kinds` nằm trong state có checksum; promotion vẫn copy nguyên bundle theo đợt 2.

## Canary

Chỉ `product-detail-bff` được chuyển sang Argo Rollout. Đây là dịch vụ HTTP stateless,
không trực tiếp nhận Kafka event hay Temporal task. Các worker tiếp tục dùng RollingUpdate;
không áp tỷ lệ HTTP cho Order, Payment hay consumer để suy ra tỷ lệ công việc chúng nhận.

Lựa chọn ban đầu là **basic canary theo số replica**, giữ Service/gateway hiện có. Có 5 replica,
các bước `20 → 50 → 100`, mỗi bước pause 60 giây rồi phân tích 3 lượt cách nhau 60 giây.
Tỷ lệ pod được làm tròn; HTTP keep-alive và phân phối kết nối khiến tỷ lệ request thực tế
không chính xác theo phần trăm này. Đây chưa phải traffic splitting chính xác bằng L7 router.
Hành vi này được mô tả trong [Argo Rollouts basic canary](https://argo-rollouts.readthedocs.io/en/stable/features/canary/).

Mỗi lần phân tích dùng `Latest` pod-template hash của Rollout, không gộp metrics phiên bản cũ.
PodMonitor đưa namespace, service, source revision và `rollouts-pod-template-hash` vào target labels.
Không thay label selector của Service/Deployment giữa release. Các phép kiểm gồm:

- Ít nhất 30 request và histogram sample trong 5 phút, coverage histogram/count phù hợp.
- Tỷ lệ không có HTTP 5xx và tỷ lệ request trong latency boundary đạt SLO đã duyệt.
- Target scrape đang up, timestamp còn mới; từ chối vector rỗng/nhiều phần tử, NaN và Infinity.

Một measurement sai ngưỡng làm analysis thất bại; lỗi provider cũng bị giới hạn. Không có
`dryRun`, không coi thiếu traffic là thành công. Argo dừng/abort progression; CI không xuất
proof cho release chưa hoàn tất. Việc khôi phục desired state vẫn qua PR rollback của đợt 2,
có allowlist tương thích schema/workflow; không chạy SQL down.

Lần tạo Rollout đầu tiên không có stable ReplicaSet để so sánh, nên Argo có thể bỏ qua các
bước canary. Việc chuyển Deployment sang Rollout là một lần bootstrap controller riêng:
diễn tập ở staging, giữ capacity cho workload cũ/mới và PruneLast, xác nhận Service/UID,
đợi baseline ổn định rồi kiểm tra một release kế tiếp mới là bài canary có stable/canary thật.
Không tuyên bố một lần khởi tạo đầu tiên là canary đã kiểm chứng.

Staging cần traffic trong lúc Rollout đang chạy, trước PostSync. Chạy bài read-only được
duyệt trong cửa sổ này hoặc cung cấp generator riêng; không trông chờ E2E PostSync tạo
mẫu cho canary. Xác minh kết nối gateway thực sự chạm canary vì keep-alive có thể giữ
request ở pod cũ. Thiếu mẫu sẽ abort; không tự giảm ngưỡng để hoàn tất một staging ít traffic.

Nếu production bật canary, staging phải bật cùng policy trước. Trong
`deploy/gitops/environments.json`, `operations.enabled` và `operations.canary.enabled`
mặc định false. Khi bật, yêu cầu thêm:

- Argo Rollouts CRD/controller và Prometheus Operator CRD đã cài, được review và scan.
- HTTPS Prometheus query endpoint, CA tin cậy và credential chỉ đọc. Remote JSON secret
  `<secret_prefix>/operations-metrics` có property `authorization`, ví dụ giá trị header
  Bearer thực tế; pin version trong `secret_versions`. Không commit token.
- Controller được đọc đúng Secret trong namespace ứng dụng; giới hạn quyền/egress của
  controller. Prometheus được chọn PodMonitor/PrometheusRule mang label
  `delivery.ecommerce/monitoring=operations` ở đúng namespace; NetworkPolicy cho phép scrape.
- BFF thực sự có histogram bucket 0.5/1/2 giây đã chọn. Diễn tập phát hiện lỗi, thiếu scrape,
  thiếu traffic và abort trên controller thật trước khi dùng production.

Xuất controller để review, **không tự apply**:

```powershell
python scripts/ops/bootstrap.py --output ci-artifacts/rollouts-bootstrap
```

Công cụ tải release cố định, so checksum rồi pin image theo digest trong manifest. Platform
operator review namespace, RBAC, admission policy và image scan trước khi cài. Cập nhật
bootstrap GitOps/AppProject theo profile mới; export lại các tool/template cùng phiên bản.
Không cài dashboard công khai. Nguồn pin: [release chính thức 1.10.1](https://github.com/argoproj/argo-rollouts/releases/tag/v1.10.1).

## SLO

Profile có đề xuất ban đầu: availability 99.9% (không 5xx), 99% request trong 1 giây,
cửa sổ 30 ngày. Đây là **ngưỡng cần đo và phê duyệt**, không phải cam kết đã đạt.
`slo.approved=false` chặn bật monitoring/analysis cho tới khi có đồng thuận về định nghĩa,
owner, latency bucket và khả năng thu metrics. SLO hiện chỉ bao phủ BFF HTTP; không đại diện
cho toàn bộ checkout, thanh toán, event lag hoặc Temporal workflow.

`slo.enforce=false` cho phép giai đoạn thu thập baseline. Báo cáo chạy riêng vẫn trả
`insufficient_data` nếu thiếu lịch sử; không đổi thành pass để khởi tạo hệ thống.
Khi bật `enforce=true`, observer đánh giá SLO sau rollout và gắn báo cáo vào proof;
thiếu dữ liệu hoặc hết error budget sẽ chặn proof/promotion. Phải bật gate staging trước production.
Token chỉ đọc `PROMETHEUS_AUTHORIZATION` được đặt ở source environment staging và GitOps
environment production cho hai lượt quan sát tương ứng.

Báo cáo yêu cầu tối thiểu 1.000 request, histogram/count khớp, target up, scrape còn mới,
và ít nhất 99% coverage của toàn bộ cửa sổ 30 ngày. Recording rule ghi trạng thái telemetry
mỗi 30 giây; tổng mẫu tốt được chia cho 86.400 mẫu kỳ vọng của đủ 30 ngày, nên lịch sử mới
vài giờ không thể báo coverage 100%. Prometheus cần retention đủ 30 ngày
cộng khoảng đệm; cấu hình local 7 ngày không đáp ứng. API warning/partial result, NaN,
Infinity, vector rỗng hoặc timestamp cũ đều không được chấp nhận.

PrometheusRule có fast burn 14.4× trên 5m/1h và slow burn 6× trên 30m/6h, cùng alert thiếu
telemetry. Namespace/service/SLO là label tường minh để không gộp alert staging và production.
Cách chọn cửa sổ dựa trên [SRE workbook](https://sre.google/workbook/alerting-on-slos/).
Alert phải được nối và diễn tập với Alertmanager/on-call thực tế trước khi bật production.

Khi báo vi phạm: xác nhận scrape/coverage trước; xem error, dependency và trace theo release;
kiểm tra canary đã abort hay chưa, giữ lại proof/log, đối chiếu bản tốt rồi chuẩn bị PR rollback.
Không bấm promote/full để bỏ qua analysis. Thay đổi gate khi xử lý incident phải qua PR có
owner review và ghi incident/evidence; không có bypass tự động trong công cụ này.

## Hiệu năng định kỳ

Sao chép `deploy/operations/policy.example.json` thành `policy.json`. Điền fixture catalog
staging và baseline **đã đo**, gồm source SHA, thời điểm, p95 và request rate. Baseline phải
không quá 30 ngày, cùng rate; công cụ không tự thay baseline bằng kết quả mới.
Giới hạn là mức nhỏ hơn giữa ngưỡng tuyệt đối và baseline × (1 + tỷ lệ regression được duyệt).

Test chỉ GET public product detail qua origin HTTPS staging lấy từ cấu hình. Không nhận URL
từ workflow input, không chạy production, không gửi token mua hàng hoặc tạo giao dịch.
Tải tối đa 20 request/s, 20 VU, 15 phút; mặc định 10 request/s trong 5 phút. Giới hạn VU,
request đạt thực tế, dropped iteration, status/body check và p95 đều là gate. Tải bị giảm vì
server chậm không được tính là đạt. Fixture local dùng để kiểm thử runner không phải baseline.
Thiết kế executor theo [k6 constant arrival rate](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/).

```powershell
python scripts/ops/performance.py --config deploy/gitops/environments.json --policy deploy/operations/policy.json --output ci-artifacts/operations/performance.json
```

Một SKU/slug không đại diện cho danh mục lớn, cache miss, checkout hay tải peak. Trước khi
đặt SLO/scale production, chạy các benchmark riêng trong `loadtest/` trên môi trường chuyên
dụng với dữ liệu/phân bố tương ứng, đo cả dependency và data tier.

## Phục hồi

`recovery.py` chỉ có chế độ `isolated-fixture`: tạo container/network có tên riêng, không
publish cổng host, không nhận DSN ngoài. Tạo backup custom-format của PostgreSQL, kiểm tra
SHA256, ghi thêm một marker sau backup, phá database **fixture của chính bài test**, tạo
database mới rồi restore bằng role không phải superuser. Kiểm tra fingerprint, khóa ngoại,
checkpoint và marker sau backup bị mất đúng dự kiến, đo RPO/RTO rồi dọn tài nguyên của bài test.
Backup chỉ ở RAM/stdin, không upload dữ liệu database vào artifact CI.

```powershell
python scripts/ops/recovery.py --policy deploy/operations/policy.json --output ci-artifacts/operations/recovery.json
```

Kết quả fixture không tự đặt `backup_restore_drill_passed=true`. Nó kiểm tra công cụ, không
chứng minh backup production, encryption, PITR, dung lượng thật hoặc phục hồi toàn hệ thống.
`pg_restore` dùng `--exit-on-error` và một transaction theo [PostgreSQL](https://www.postgresql.org/docs/16/app-pgrestore.html).

Bài diễn tập platform thật cần owner phê duyệt và đích cách ly, theo trình tự:

1. Chốt incident giả lập, RPO/RTO mục tiêu, source backup/snapshot/checksum, schema, source
   image và secret/key versions. Xác nhận restore target mới, network cô lập và quyền cần thiết.
2. Khôi phục PostgreSQL/PITR; kiểm tra DB/schema/history của từng service và quyền runtime.
   Khôi phục key giải mã, object storage và tham chiếu media; kiểm tra điểm nhất quán của backup.
3. Khôi phục Temporal namespace/history và chạy replay với đúng version worker. Với Kafka,
   kiểm tra offset, retention, replay/idempotency và thứ tự event; không xóa topic/consumer group nguồn.
4. Dựng lại Redis/cache và Elasticsearch read model từ nguồn chuẩn, kiểm tra tồn kho, order,
   payment/refund, read-model convergence và outbox/inbox. Không phát email hay charge thật từ đích DR.
5. Chạy smoke, contract, nghiệp vụ sandbox; đo thời điểm checkpoint cuối còn khôi phục được,
   thời điểm bắt đầu sự cố và thời điểm dịch vụ phục hồi để tính RPO/RTO thực.
6. Lưu exercise ID, owner/reviewer, backup IDs, source/config revisions, kiểm tra dữ liệu,
   số đo, gap và hành động khắc phục. Chỉ xác nhận readiness sau khi phạm vi thật đạt.

## Bật lịch kiểm tra

Workflow `Operations Checks` mặc định bị khóa bởi `OPERATIONS_ENABLED` chưa bật. Sau khi
điền config, review `policy.json`, environment/credentials và kết nối runner, mới đặt variable
này thành `true`. Manual run yêu cầu main và chọn performance/slo/recovery; cấu hình thiếu
hoặc flag chưa bật sẽ báo lỗi rõ ràng. Quyền workflow mặc định chỉ Contents read.

| Kiểm tra | Cron UTC | Giờ Asia/Bangkok |
|---|---|---|
| Hiệu năng staging | `0 19 * * 6` | Chủ nhật 02:00 |
| PostgreSQL fixture recovery | `0 20 1 * *` | Ngày 2 hằng tháng 03:00 |
| SLO production | `0 1 * * *` | Hằng ngày 08:00 |

Performance dùng environment `operations-staging`; SLO dùng `operations-production` với
`PROMETHEUS_AUTHORIZATION` chỉ đọc. Các environment chỉ cho main, không cần approval cho
đọc metrics hay fixture cách ly. Artifact JSON giữ 90 ngày, không chứa credential/dump/response body.
Manual và lịch chạy cùng loại kiểm tra dùng chung concurrency group để không cộng dồn tải.
Lịch GitHub có thể trễ; đây không phải cơ chế cảnh báo thời gian thực. Canary/Prometheus/Alertmanager
chịu trách nhiệm phản ứng runtime. Các HIGH/CRITICAL và readiness từ đợt 1–2 vẫn chặn activation.
